package io.github.lamin_sudo.halo

import android.content.Context
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * The now-playing scene, read from the session rather than from the media notification.
 *
 * The first version keyed media off `CATEGORY_TRANSPORT` notifications, which gave a title and
 * nothing else: no position, no duration, no artwork, and a play/pause pair copied from the row.
 * SystemUI already resolves the real session for its own media player —
 * `com.android.systemui.media.MediaDataManager#onMediaDataLoaded(String key, String oldKey,
 * MediaData data)` — and `MediaData` carries song, artist, artwork, `isPlaying` and the session
 * `token`. From the token a `MediaController` gives position, duration and state, which is what
 * the green line needs to move with the music.
 *
 * `MediaData` is a SystemUI class, so every field is read reflectively: the module compiles
 * against the SDK, not against the ROM.
 *
 * While a session is live this source owns the media slot; [NotificationSource] stands down for
 * media rows (see [sessionWins]) so the two cannot overwrite each other's progress.
 */
class MediaSource(private val context: Context, private val window: IslandWindow) {

    private val main = Handler(Looper.getMainLooper())

    private var controller: MediaController? = null
    private var callback: MediaController.Callback? = null
    private var pkg: String = ""
    private var boundToken: MediaSession.Token? = null

    /** Position at the moment we last sampled the session, and when we sampled it. */
    private var basePositionMs = 0L
    private var baseRealtime = 0L
    private var durationMs = 0L
    private var playing = false

    private var ticker: Runnable? = null

    fun install(module: XposedModule, param: PackageLoadedParam) {
        val clazz = runCatching {
            param.defaultClassLoader.loadClass(MANAGER)
        }.getOrNull()
        if (clazz == null) {
            Logx.d("$MANAGER not on this ROM: media stays notification-driven")
            return
        }
        val method = clazz.declaredMethods.firstOrNull { m ->
            m.name == "onMediaDataLoaded" && m.parameterTypes.size == 3 &&
                m.parameterTypes[2].name == MEDIA_DATA
        }
        if (method == null) {
            Logx.d("onMediaDataLoaded(String, String, MediaData) not found: media stays notification-driven")
            return
        }
        val loaded = runCatching {
            method.isAccessible = true
            module.hook(method).intercept { chain ->
                try {
                    val data = chain.args.getOrNull(2)
                    if (data != null) main.post { handle(data) }
                } catch (t: Throwable) {
                    Logx.e("media load hook failed", t)
                }
                // Void method: proceed() returns Unit, which is the correct value here.
                return@intercept chain.proceed()
            }
        }
        if (!loaded.isSuccess) {
            Logx.e("media hook failed", loaded.exceptionOrNull())
            return
        }

        // Removal is what stops the ring. Without it a swiped-away player left the light sweeping
        // forever: the capsule had already expired into ambient and nothing else owned the slot.
        var removals = 0
        for (name in listOf("removeEntry", "removeAllForPackage")) {
            val m = clazz.declaredMethods.firstOrNull {
                it.name == name && it.parameterTypes.size == 1
            } ?: continue
            val ok = runCatching {
                m.isAccessible = true
                module.hook(m).intercept { chain ->
                    val result = runCatching { chain.proceed() }.getOrNull()
                    try {
                        // Ask the manager itself: one session dying while others play must not
                        // close the island.
                        val anyLeft = any(chain.thisObject, "hasAnyMedia") as? Boolean
                        Logx.d("media removed: anyLeft=$anyLeft")
                        if (anyLeft != true) main.post {
                            stopTicker()
                            window.endMedia()
                        }
                    } catch (t: Throwable) {
                        Logx.e("media removal hook failed", t)
                    }
                    return@intercept result
                }
            }
            if (ok.isSuccess) removals++
            else Logx.e("media removal hook failed on $name", ok.exceptionOrNull())
        }
        Logx.d("media source installed on $MANAGER (removal hooks: $removals)")
    }

    /** True once a real session has been seen, so the notification path can stand down. */
    var sessionWins = false
        private set

    private fun handle(data: Any) {
        val song = text(data, "getSong").orEmpty()
        val artist = text(data, "getArtist").orEmpty()
        val packageName = text(data, "getPackageName") ?: text(data, "getApp").orEmpty()
        if (packageName.isEmpty() && song.isEmpty()) return
        sessionWins = true
        pkg = packageName
        window.mediaPackage = packageName

        bindController(data)

        submit(song, artist, artwork(data), packageName)
    }

    /** The artwork MediaData carries as an Icon, loaded through the host context. */
    private fun artwork(data: Any): Drawable? {
        val icon = any(data, "getArtwork") ?: any(data, "getAppIcon") ?: return null
        return runCatching {
            (icon as? android.graphics.drawable.Icon)?.loadDrawable(context)
        }.getOrNull()
    }

    /**
     * Attach to the session behind the token: this is what turns a static title into a moving
     * line, and it is also how play/pause and the ±10s seek reach the player without a notification
     * action being involved.
     */
    private fun bindController(data: Any) {
        val token = any(data, "getToken") as? MediaSession.Token ?: return
        if (boundToken == token) {
            sample()
            return
        }
        releaseController()
        // Public constructor is (Context, MediaSession.Token); callbacks take their own Handler.
        controller = runCatching { MediaController(context, token) }.getOrNull()
        boundToken = token
        val c = controller ?: return
        callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                applyState(state)
                main.post { resubmit() }
            }

            override fun onMetadataChanged(metadata: MediaMetadata?) {
                main.post { resubmit() }
            }

            override fun onSessionDestroyed() {
                Logx.d("media session destroyed: dropping the capsule")
                main.post {
                    stopTicker()
                    window.cancel("media|$pkg")
                }
            }
        }
        runCatching { c.registerCallback(callback!!, main) }
            .onFailure { Logx.w("registerCallback failed: ${it.javaClass.simpleName}") }
        applyState(runCatching { c.playbackState }.getOrNull())
    }

    private fun applyState(state: PlaybackState?) {
        if (state == null) return
        playing = state.state == PlaybackState.STATE_PLAYING ||
            state.state == PlaybackState.STATE_BUFFERING
        basePositionMs = state.position.coerceAtLeast(0L)
        baseRealtime = SystemClock.elapsedRealtime()
        durationMs = runCatching {
            controller?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        }.getOrDefault(0L)
    }

    /** Where the track is right now, extrapolated between session callbacks. */
    private fun currentPosition(): Long {
        val extra = if (playing) SystemClock.elapsedRealtime() - baseRealtime else 0L
        return (basePositionMs + extra).coerceAtLeast(0L)
    }

    private fun sample() {
        runCatching { controller?.playbackState }?.getOrNull()?.let { applyState(it) }
    }

    private fun resubmit(keepDeadline: Boolean = false) {
        val meta = runCatching { controller?.metadata }.getOrNull()
        val song = meta?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        submit(song, artist, artworkOf(meta), pkg, keepDeadline)
    }

    private fun artworkOf(meta: MediaMetadata?): Drawable? = runCatching {
        meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
    }.getOrNull()?.let { android.graphics.drawable.BitmapDrawable(context.resources, it) }

    private fun submit(
        song: String,
        artist: String,
        art: Drawable?,
        packageName: String,
        keepDeadline: Boolean = false,
    ) {
        val position = currentPosition()
        val percent = if (durationMs > 0) ((position * 100) / durationMs).toInt().coerceIn(0, 100) else -1
        val title = song.ifBlank { artist.ifBlank { packageName } }
        window.submit(
            IslandEvent(
                key = "media|$packageName",
                source = Source.MEDIA,
                title = title,
                body = null,
                trailing = artist.takeIf { it.isNotBlank() },
                icon = ModuleResources.mediaIcon(context),
                avatar = art,
                avatarText = null,
                actions = emptyList(),
                progress = percent,
                accentArgb = ACCENT_MEDIA,
                ttlMs = Config.current.ttlMs,
                openIntent = null,
                showBell = false,
                playing = playing,
                // A tick must not restart the countdown, or a playing track never steps down
                // to the ring.
                refreshTtl = !keepDeadline,
            )
        )
        if (playing) startTicker() else stopTicker()
    }

    /** 1s per step: the line has to visibly move, and the session only calls back on changes. */
    private fun startTicker() {
        if (ticker != null) return
        val r = object : Runnable {
            override fun run() {
                resubmit(keepDeadline = true)
                main.postDelayed(this, TICK_MS)
            }
        }
        ticker = r
        main.postDelayed(r, TICK_MS)
    }

    private fun stopTicker() {
        ticker?.let { main.removeCallbacks(it) }
        ticker = null
    }

    private fun releaseController() {
        val cb = callback
        runCatching { if (cb != null) controller?.unregisterCallback(cb) }
        callback = null
        controller = null
        boundToken = null
    }

    // ── controls the mini player calls ─────────────────────────────────────────────

    fun togglePlayback() {
        val c = controller ?: return
        runCatching {
            val state = c.playbackState
            val actions = state?.actions ?: 0L
            if (playing && actions and PlaybackState.ACTION_PAUSE != 0L) c.transportControls.pause()
            else if (actions and PlaybackState.ACTION_PLAY != 0L) c.transportControls.play()
            else c.transportControls.playFromMediaId(null, null)
        }.onFailure { Logx.w("togglePlayback failed: ${it.javaClass.simpleName}") }
        playing = !playing
        main.post { resubmit() }
    }

    fun previous() {
        runCatching { controller?.transportControls?.skipToPrevious() }
            .onFailure { Logx.w("skipToPrevious failed: ${it.javaClass.simpleName}") }
        main.post { resubmit() }
    }

    fun next() {
        runCatching { controller?.transportControls?.skipToNext() }
            .onFailure { Logx.w("skipToNext failed: ${it.javaClass.simpleName}") }
        main.post { resubmit() }
    }

    fun seekBy(deltaMs: Long) {
        val c = controller ?: return
        val to = (currentPosition() + deltaMs).coerceAtLeast(0L)
        runCatching { c.transportControls.seekTo(to) }
            .onFailure { Logx.w("seekBy failed: ${it.javaClass.simpleName}") }
        basePositionMs = to
        baseRealtime = SystemClock.elapsedRealtime()
        main.post { resubmit() }
    }

    // ── reflective access into the ROM's MediaData ─────────────────────────────────

    private fun any(target: Any, getter: String): Any? =
        runCatching { target.javaClass.getMethod(getter).invoke(target) }.getOrNull()

    private fun text(target: Any, getter: String): String? =
        (any(target, getter) as? CharSequence)?.toString()?.takeIf { it.isNotBlank() }

    companion object {
        private const val MANAGER = "com.android.systemui.media.MediaDataManager"
        private const val MEDIA_DATA = "com.android.systemui.media.MediaData"
        private const val ACCENT_MEDIA = 0xFF30D158.toInt()
        private const val TICK_MS = 1_000L
    }
}
