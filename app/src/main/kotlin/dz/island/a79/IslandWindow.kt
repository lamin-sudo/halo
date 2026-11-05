package dz.island.a79

import android.animation.Animator
import android.animation.ValueAnimator
import android.app.NotificationManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowInsets
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator

/**
 * Owns the overlay window: queue, TTL, the collapse choreography, and the status-bar fade.
 *
 * The window is added while something is showing, and stays added while a media session is
 * playing: that is the ambient ring around the camera hole, which is the whole point of the
 * player mode.
 *
 * Collapse is three beats: content fades, the body shrinks onto the camera hole, a light sweeps
 * around the hole. For media the third beat does not end — it keeps sweeping until playback
 * stops, and tapping it brings the capsule back with the controls.
 */
class IslandWindow(
    private val context: Context,
    private val fade: StatusBarFade,
    initialGeometry: Geometry,
    private val onTaken: (IslandEvent) -> Unit = {},
) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val view = IslandView(context, initialGeometry)
    private val handler = Handler(Looper.getMainLooper())

    private var geo = initialGeometry
    private var params: WindowManager.LayoutParams? = null
    private var attached = false
    private var animator: ValueAnimator? = null
    private var ambientAnimator: ValueAnimator? = null
    /** The one-pass sweep around the hole, so a new capsule can stop it instead of fighting it. */
    private var ringAnimator: ValueAnimator? = null

    /** Current animated width in px; 0 means hidden. */
    private var widthNow = 0f

    /**
     * Live items by slot key. This is bookkeeping for removal, not a waiting line: the newest
     * item takes the island, the rest stay in the shade. An earlier version queued them and let
     * the oldest win, which is why a Telegram or X message arriving while another capsule was up
     * never showed at all.
     */
    private val live = LinkedHashMap<String, IslandEvent>()
    private var visible: IslandEvent? = null

    /** The media session the ring is standing in for, while nothing is expanded. */
    private var ambient: IslandEvent? = null

    private var ttlRunnable: Runnable? = null
    private var detachRunnable: Runnable? = null

    /**
     * A game or a video that took the screen also takes the island.
     *
     * Two signals, both from outside SystemUI's own view tree, because nothing in it can see the
     * bar being taken: the Game Space flag ([GameSpace]) says a game owns the screen, and
     * WindowManager's `setTopAppHidesStatusBar` ([StatusBarFade.installTopAppWatcher]) says the
     * top app asked for the bar — measured on a real YouTube fullscreen and on a real PUBG match.
     * Both are on by default; the view probe behind them is only a fallback.
     *
     * Asked as a question at the moment an event arrives, never tracked in between.
     */
    private fun hiddenForFullscreen(): Boolean {
        val cfg = Config.current
        if (cfg.gamesHide && GameSpace.inGame()) {
            Logx.d("hidden: game session (flag=${GameSpace.flag})")
            return true
        }
        return cfg.immersiveHide && fade.isBarHidden()
    }

    /**
     * The screen was taken over (a match started, a video went fullscreen) while something was
     * already up: take the island off now, rather than leaving a capsule frozen on top of it until
     * its TTL happens to expire.
     */
    fun standDown(reason: String) {
        handler.post {
            if (!attached && visible == null && ambient == null) return@post
            Logx.d("standing down: $reason")
            clear()
        }
    }

    // The inset route was tried and is wrong on this window: an overlay with
    // FLAG_LAYOUT_IN_SCREEN and cutout ALWAYS is handed a status-bar inset of 0 whether the bar is
    // there or not, so `statusBarInset == 0` silently dropped *every* event (measured: a normal
    // test post disappeared while `bar check hidden=false`). The bar is taken away by the window
    // manager moving the surface, which no view in SystemUI's process can see.

    private val notifications = context.getSystemService(NotificationManager::class.java)

    /** Filled in by [IslandModule] once [MediaSource] exists: the mini player's transport. */
    var onMediaPlayPause: (() -> Unit)? = null
    var onMediaSeek: ((Long) -> Unit)? = null
    var onMediaPrev: (() -> Unit)? = null
    var onMediaNext: (() -> Unit)? = null
    /** Filled in by [IslandModule]: swiping one line of the list out dismisses that notification. */
    var onRowDismiss: ((String) -> Unit)? = null

    init {
        view.onOpen = { visible?.let { openIt(it) } ?: expandFromAmbient() }
        view.onOpenApp = { visible?.let { launchApp(it) } }
        view.onAction = { index -> visible?.let { fireAction(it, index) } }
        view.onDismiss = { visible?.let { cancel(it.key) } ?: stopAmbient() }
        view.onToggleDnd = { toggleDnd() }
        view.onPlayPause = { onMediaPlayPause?.invoke() }
        view.onSeek = { delta -> onMediaSeek?.invoke(delta) }
        view.onPrev = { onMediaPrev?.invoke() }
        view.onNext = { onMediaNext?.invoke() }
        view.onClosePlayer = {
            when {
                view.callMode -> setCallMode(false)
                view.listMode -> setListMode(false)
                view.detailMode -> setDetailMode(false)
                else -> setPlayerMode(false)
            }
            visible?.let { scheduleTtl(it.ttlMs) }
        }
        view.onRowDismiss = { key ->
            onRowDismiss?.invoke(key)
            view.dropRow(key)
            if (view.listRows() < 2) {
                // What is left is no longer a list worth a card.
                setListMode(false)
            } else {
                // Grow shorter with the list, instead of leaving a blank row where it was.
                resizeWindow(listHeight(view.listRows()), 0)
            }
        }
    }

    fun updateGeometry(next: Geometry) {        geo = next
        view.setGeometry(next)
        val e = visible
        if (e != null && attached) apply(e.width(geo), 1f)
    }

    /**
     * Must be called on the main thread.
     *
     * Same key replaces the queued item instead of stacking behind it — that is what makes a
     * burst of Telegram messages read as one capsule with a count, not a queue of five.
     */
    fun submit(event: IslandEvent) {
        if (hiddenForFullscreen()) {
            Logx.d("event dropped (fullscreen): ${event.key}")
            return
        }
        live[event.key] = event
        onTaken(event)
        val current = visible
        if (current == null) {
            // A progress tick must not drag the capsule back on screen while it is standing by
            // as the ring: the ring has no line to move. Only a real event re-expands it.
            if (ambient?.key == event.key && !event.refreshTtl) {
                ambient = event
                return
            }
            show(event, event.ttlMs)
            return
        }
        if (event.key == current.key) {
            // A call that stops ringing is the same notification updated, so it never reaches
            // show(): without this the answer card would stay open over the dialer.
            if (event.source == Source.CALL && !event.incoming) {
                visible = event
                view.bind(event)
                setCallMode(false)
                enterAmbient(event)
                Logx.d("call answered: the card folds into the ring")
                return
            }
            // Same slot, newer content: a grouped capsule updates in place ("2 messages" →
            // "3 messages") without restarting its animation.
            visible = event
            view.bind(event)
            view.setGlow(1f)
            if (event.source == Source.MEDIA) view.setAmbient(false, event.accentArgb)
            // A progress tick keeps the deadline it already had; only real events reset it.
            if (event.refreshTtl) scheduleTtl(event.ttlMs)
            if (event.showBell) refreshDnd()
            return
        }
        if (event.priorityOf() >= current.priorityOf()) show(event, event.ttlMs)
    }

    fun cancel(key: String) {
        live.remove(key)
        if (ambient?.key == key) stopAmbient()
        if (visible?.key == key) {
            val leaving = visible!!
            visible = null
            hide(leaving, allowAmbient = false)
        }
    }

    /**
     * The timeout ran out. A media item that is still playing goes to stand by as the ring;
     * everything else just collapses and goes.
     */
    private fun expireVisible() {
        val leaving = visible ?: return
        visible = null
        live.remove(leaving.key)
        // The ring means "still playing". Paused or stopped media collapses and goes away, which
        // is what he asked for: closing the player must close the island by itself.
        hide(
            leaving,
            allowAmbient = (leaving.source == Source.MEDIA && leaving.playing == true) ||
                leaving.source == Source.CALL,
        )
    }

    fun clear() {
        val leaving = visible
        live.clear()
        visible = null
        stopAmbient()
        if (leaving != null) hide(leaving, allowAmbient = false)
    }

    /** True when this exact item is the one currently on screen. */
    fun isShowing(key: String): Boolean = visible?.key == key && attached

    /**
     * A lone sweep of the light around the camera hole, for events that are not content — face
     * unlock today. Deliberately refuses to disturb anything already on screen: a capsule, the
     * media ring, or an animation still running owns the island, and the pulse is simply dropped.
     */
    fun pulseRing() {
        // Logged rather than silent: "why didn't it sweep" is otherwise unanswerable from a PC.
        if (hiddenForFullscreen()) {
            Logx.d("ring pulse skipped: fullscreen")
            return
        }
        if (visible != null || ambient != null) {
            Logx.d("ring pulse skipped: ${visible?.key ?: ambient?.key ?: "nothing"} owns the island")
            return
        }
        if (animator?.isRunning == true || ringAnimator?.isRunning == true) {
            Logx.d("ring pulse skipped: animation running")
            return
        }
        // Both BiometricUnlockController classes report the same unlock, and the animator this
        // guard would check is not started until the posted block runs — so a second call slips
        // through 1ms later and sweeps the ring twice. A timestamp settles it.
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastPulseAt < RING_MS) {
            Logx.d("ring pulse skipped: already swept")
            return
        }
        lastPulseAt = now
        handler.post {
            ensureAttached(geo.pillH)
            widthNow = geo.ringWidth.toFloat()
            apply(widthNow.toInt(), 0f)
            flashRingOnce(ACCENT_DEFAULT)
            Logx.d("ring pulse (hole ${geo.holeCx}, w=${geo.ringWidth})")
        }
    }

    private var lastPulseAt = 0L

    private fun show(event: IslandEvent, ttlMs: Long) {
        visible = event
        // A new arrival owns a collapsed island: a list card left open behind it would stretch the
        // window for content that is no longer unfolded.
        if (view.listMode) setListMode(false)
        if (view.detailMode) setDetailMode(false)
        // A notification or battery capsule takes the island back cleanly: the transport surface
        // belongs to media alone.
        if (event.source != Source.MEDIA) view.playerMode = false
        // A ringing call opens its card; a call that is already up does not — the dialer has its
        // own controls and a card over them is the "weird look" he reported. That one stands by
        // as the green ring around the hole instead.
        val callCard = event.source == Source.CALL && event.incoming
        view.bind(event)
        if (event.source == Source.CALL && !event.incoming) {
            ensureAttached(geo.pillH)
            setCallMode(false)
            enterAmbient(event)
            Logx.d("call already up: green ring, no card")
            return
        }
        val target = event.width(geo)
        // The height has to be in the layout from the first addView: `setCallMode` before the
        // window exists resizes nothing, and the call stayed a plain pill on the first ring.
        ensureAttached(if (callCard) geo.pillH + CALL_CARD_EXTRA else geo.pillH)
        setCallMode(callCard)
        stopAmbientSpinner()
        ringAnimator?.cancel()
        ringAnimator = null
        animator?.cancel()
        view.setRingAlpha(0f, 0f, event.accentArgb)
        view.setGlow(1f)
        animateTo(target.toFloat(), 1f)
        scheduleTtl(ttlMs)
        Logx.d("show ${event.source} key=${event.key} w=$target h=${geo.pillH}")
    }

    /**
     * Beat 1: content fades while the body shrinks onto the hole.
     * Beat 2: a light sweeps around the hole — and for media it never stops.
     */
    private fun hide(leaving: IslandEvent, allowAmbient: Boolean) {
        ttlRunnable?.let { handler.removeCallbacks(it) }
        ttlRunnable = null
        if (view.listMode) setListMode(false)
        if (view.callMode) setCallMode(false)
        if (view.detailMode) setDetailMode(false)
        if (!attached) return

        val from = widthNow
        var cancelled = false
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = COLLAPSE_MS
            interpolator = EXIT
            addUpdateListener {
                val t = it.animatedFraction
                widthNow = lerp(from, geo.ringWidth.toFloat(), t)
                view.setContentAlpha(1f - t)
                view.setGlow(1f - t)
                apply(widthNow.toInt().coerceAtLeast(1), 1f - t)
            }
            addListener(object : Animator.AnimatorListener {
                override fun onAnimationStart(a: Animator) {}
                override fun onAnimationEnd(a: Animator) {
                    // A new capsule took over (or the shrink was cut short): this collapse no
                    // longer owns the island, so it must not queue the ring or a detach.
                    if (cancelled || visible != null) return
                    // Something was pushed aside while this was up. It gets its turn now,
                    // as long as it has not aged out of its own look.
                    val next = newestLive()
                    if (next != null && next.key != leaving.key) {
                        show(next, next.ttlMs)
                        return
                    }
                    if (allowAmbient && leaving.source != Source.CHARGING) enterAmbient(leaving)
                    else flashRingOnce(leaving.accentArgb)
                }
                override fun onAnimationCancel(a: Animator) { cancelled = true }
                override fun onAnimationRepeat(a: Animator) {}
            })
            // Tracked: a message arriving mid-collapse must stop this, or the two animators
            // fight over the width every frame and the new capsule is squeezed back to the ring.
            animator = this
            start()
        }
    }

    /** The standing ring: media is playing, nothing is expanded. Tap it to bring the capsule back. */
    private fun enterAmbient(event: IslandEvent) {
        ambient = event
        visible = null
        view.setAmbient(true, event.accentArgb)
        widthNow = geo.ringWidth.toFloat()
        apply(widthNow.toInt(), 0f)
        startAmbientSpinner()
        Logx.d("ambient ring for ${event.key}")
    }

    private fun expandFromAmbient() {
        val event = ambient ?: return
        stopAmbientSpinner()
        view.setAmbient(false, event.accentArgb)
        // Re-submit so the count and the track title are current.
        show(event, event.ttlMs)
        // Tapping the ring goes straight to the player: two taps to reach the controls was one
        // too many, and the pill is too small to hit anyway. A call opens the dialer and the ring
        // stays, because the call is still up.
        when (event.source) {
            Source.MEDIA -> setPlayerMode(true)
            Source.CALL -> event.openIntent?.let { intent ->
                runCatching { intent.send() }
                    .onFailure { Logx.w("call openIntent send failed: ${it.javaClass.simpleName}") }
            }
            else -> {}
        }
    }

    /**
     * Called by [MediaSource] when the session is gone (swiped away, killed, removed by
     * MediaDataManager). Without this the ring kept sweeping forever — the visible capsule had
     * already expired into ambient and nothing else owned it.
     */
    fun endMedia() {
        val key = "media|" + mediaPackage
        if (ambient?.key == key || visible?.key == key) {
            Logx.d("media session gone: closing the island")
            cancel(key)
        }
        if (ambient != null) stopAmbient()
    }

    var mediaPackage: String = ""

    /**
     * The floating player: the same window, grown downward into a card. It also drops 14px so the
     * card does not start flush with the top edge of the panel — the pill has to hug the notch,
     * but a 300px card that touches the bezel looks like it is in the wrong place.
     */
    fun setPlayerMode(on: Boolean) {
        if (view.playerMode == on) return
        view.playerMode = on
        resizeWindow(if (on) PLAYER_H else geo.pillH, if (on) PLAYER_Y else 0)
        Logx.d(if (on) "player opened (${PLAYER_H}px card at y=${PLAYER_Y})" else "player closed")
    }

    /**
     * The unfolded burst: the same window grown to a header band plus one row per message, so a
     * group can be read and cleared line by line without opening the app or pulling the shade.
     */
    fun setListMode(on: Boolean) {
        if (view.listMode == on) return
        view.listMode = on
        resizeWindow(listHeight(if (on) view.listRows() else 0), 0)
        Logx.d(if (on) "list opened (${view.listRows()} rows)" else "list closed")
    }

    private fun listHeight(rows: Int): Int =
        geo.pillH + rows.coerceAtMost(MAX_LIST_ROWS) * IslandView.ROW_H + LIST_FOOTER

    /**
     * The call card: the band plus the two discs. It opens by itself when the dialer posts, because
     * a ringing phone is the one thing nobody wants to have tap twice.
     */
    fun setCallMode(on: Boolean) {
        if (view.callMode == on) return
        view.callMode = on
        resizeWindow(if (on) geo.pillH + CALL_CARD_EXTRA else geo.pillH, 0)
        Logx.d(if (on) "call card opened" else "call card closed")
    }

    /**
     * One message, unfolded in place. The alternative was launching the app to read two lines,
     * which is exactly what he asked the island to stop doing.
     */
    fun setDetailMode(on: Boolean) {
        if (view.detailMode == on) return
        view.detailMode = on
        resizeWindow(if (on) geo.pillH + DETAIL_EXTRA else geo.pillH, 0)
        Logx.d(if (on) "detail card opened" else "detail card closed")
    }

    private fun resizeWindow(height: Int, top: Int) {
        val p = params ?: return
        if (p.height == height && p.y == top) return
        p.height = height
        p.y = top
        runCatching { wm.updateViewLayout(view, p) }
            .onFailure { Logx.e("window resize failed", it) }
    }

    private fun stopAmbient() {
        if (ambient == null) return
        ambient = null
        stopAmbientSpinner()
        view.setAmbient(false, ACCENT_DEFAULT)
        if (!attached) return
        flashRingOnce(ACCENT_DEFAULT)
    }

    private fun startAmbientSpinner() {
        ambientAnimator?.cancel()
        ambientAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = AMBIENT_LOOP_MS
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            addUpdateListener { view.setAmbientSweep(it.animatedFraction * 360f) }
            start()
        }
    }

    private fun stopAmbientSpinner() {
        ambientAnimator?.cancel()
        ambientAnimator = null
    }

    /** One pass of the light, then the window goes away. */
    private fun flashRingOnce(accentArgb: Int) {
        Logx.d("collapse ring accent=${Integer.toHexString(accentArgb)} for ${RING_MS}ms")
        // Back to pill geometry first: the ring is drawn around the hole, and a card-sized window
        // left it sweeping below the camera instead of around it.
        resizeWindow(geo.pillH, 0)
        var cancelled = false
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = RING_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedFraction
                val pulse = if (t < 0.25f) t / 0.25f else 1f - (t - 0.25f) / 0.75f
                view.setRingAlpha(pulse, SWEEP_DEGREES * t, accentArgb)
            }
            addListener(object : Animator.AnimatorListener {
                override fun onAnimationStart(a: Animator) {}
                override fun onAnimationEnd(a: Animator) {
                    // If a capsule arrived while the light was sweeping, the sweep is over but
                    // the island is not: clearing the view here used to blank the new capsule.
                    if (cancelled || visible != null) return
                    view.setRingAlpha(0f, SWEEP_DEGREES, accentArgb)
                    view.bind(null)
                    if (ambient == null) scheduleDetach()
                }
                override fun onAnimationCancel(a: Animator) { cancelled = true }
                override fun onAnimationRepeat(a: Animator) {}
            })
            ringAnimator = this
            start()
        }
    }

    private fun ensureAttached(height: Int) {
        if (attached) return
        // On this ROM the StatusBar window sits ABOVE TYPE_APPLICATION_OVERLAY (measured:
        // window list is top-first, StatusBar #7 vs our overlay #9), which left the status icons
        // painted over the pill. 2021 is the layer SystemUI itself uses for extra status-bar
        // content; 2038 stays as the fallback.
        val types = intArrayOf(2021, 2038)
        for (type in types) {
            val p = WindowManager.LayoutParams(
                1,
                height,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = geo.leftFor(1)
                y = geo.offsetY
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                title = "a79-island"
                // Real blur of what is behind the window, so the translucent body reads as
                // glass. Ignored silently where the compositor does not support it.
                runCatching {
                    flags = flags or FLAG_BLUR_BEHIND
                    setBlurBehindRadius(BLUR_RADIUS)
                }.onFailure { Logx.d("blur behind unavailable: ${it.javaClass.simpleName}") }
            }
            val ok = runCatching {
                detachRunnable?.let { handler.removeCallbacks(it) }
                wm.addView(view, p)
                params = p
                attached = true
            }.isSuccess
            if (ok) {
                Logx.d("window attached type=$type h=$height geo=$geo")
                return
            }
            Logx.w("addView rejected for type=$type, trying the next layer")
        }
        Logx.e("no window layer accepted the island")
    }

    private fun scheduleDetach() {
        detachRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            if (!attached || visible != null || ambient != null) return@Runnable
            runCatching { wm.removeViewImmediate(view) }
                .onFailure { Logx.e("removeView failed", it) }
            attached = false
            params = null
            view.setDrawnWidth(0)
            fade.setAlpha(1f)
        }
        detachRunnable = r
        handler.postDelayed(r, 80)
    }

    /**
     * Drives width and status-bar alpha from one number, so the fade is exactly as fast as the
     * growth: clock.alpha = 1 - island.visibility.
     */
    private fun animateTo(targetWidth: Float, targetCoverage: Float) {
        val fromWidth = widthNow
        val fromCoverage = coverage
        animator?.cancel()
        val a = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (targetWidth > fromWidth) ENTER_MS else EXIT_MS
            interpolator = if (targetWidth > fromWidth) ENTER else EXIT
            addUpdateListener {
                val t = it.animatedFraction
                widthNow = lerp(fromWidth, targetWidth, t)
                coverage = lerp(fromCoverage, targetCoverage, t)
                apply(widthNow.toInt().coerceAtLeast(1), coverage)
            }
        }
        animator = a
        a.start()
    }

    private var coverage = 0f

    private fun apply(widthPx: Int, coverageNow: Float) {
        val p = params ?: return
        p.width = widthPx
        p.x = geo.leftFor(widthPx)
        runCatching { wm.updateViewLayout(view, p) }
            .onFailure { Logx.e("updateViewLayout failed", it) }
        view.setDrawnWidth(widthPx)
        fade.setAlpha(1f - coverageNow)
    }

    private fun scheduleTtl(ttlMs: Long) {
        ttlRunnable?.let { handler.removeCallbacks(it) }
        if (ttlMs <= 0L) return
        val r = Runnable { expireVisible() }
        ttlRunnable = r
        handler.postDelayed(r, ttlMs)
    }

    private fun openIt(event: IslandEvent) {
        if (event.source == Source.MEDIA) {
            openPlayer(event)
            return
        }
        if (event.rows.size >= 2) {
            toggleList(event)
            return
        }
        if (event.detailText != null) {
            toggleDetail(event)
            return
        }
        val intent = event.openIntent
        if (intent == null) {
            cancel(event.key)
            return
        }
        runCatching { intent.send() }
            .onFailure { Logx.w("openIntent send failed: ${it.javaClass.simpleName}") }
        cancel(event.key)
    }

    /**
     * Tapping the player capsule opens it in place — the pill becomes the transport surface with
     * play/pause and 10s seeks — rather than launching YouTube Music, which is what a notification
     * would have done. Tapping it again hands it back to the ambient ring.
     */
    private fun openPlayer(event: IslandEvent) {
        if (view.playerMode) {
            setPlayerMode(false)
            scheduleTtl(event.ttlMs)
            return
        }
        setPlayerMode(true)
        // The card stays as long as it is open, then behaves like any other capsule.
        scheduleTtl(PLAYER_OPEN_MS)
    }

    /**
     * Tapping a grouped capsule unfolds it in place instead of launching the app — that is what
     * makes the count readable. Tapping the header again folds it back.
     */
    private fun toggleList(event: IslandEvent) {
        if (view.listMode) {
            setListMode(false)
            scheduleTtl(event.ttlMs)
            return
        }
        setListMode(true)
        scheduleTtl(LIST_OPEN_MS)
    }

    /**
     * Tapping a single-message capsule unfolds it; tapping the band again folds it back. The Open
     * chip inside the card is what actually launches the app.
     */
    private fun toggleDetail(event: IslandEvent) {
        if (view.detailMode) {
            setDetailMode(false)
            scheduleTtl(event.ttlMs)
            return
        }
        setDetailMode(true)
        scheduleTtl(DETAIL_OPEN_MS)
    }

    private fun launchApp(event: IslandEvent) {
        val intent = event.openIntent ?: run { cancel(event.key); return }
        runCatching { intent.send() }
            .onFailure { Logx.w("openIntent send failed: ${it.javaClass.simpleName}") }
        cancel(event.key)
    }

    private fun fireAction(event: IslandEvent, index: Int) {
        val action = event.actions.getOrNull(index) ?: run { openIt(event); return }
        runCatching { action.intent?.send() }
            .onFailure { Logx.w("action send failed: ${it.javaClass.simpleName}") }
        // Media and calls keep their slot: pausing a track or answering a call should not dismiss
        // the capsule — the app re-posts it with the new state.
        if (event.source != Source.MEDIA && event.source != Source.CALL) cancel(event.key)
    }

    /**
     * The bell. From SystemUI we may set the interruption filter directly, which is the same
     * call the quick-settings tile makes. Total silence, because that is what the phone's own
     * "Do not disturb" means; change to INTERRUPTION_FILTER_PRIORITY for the iPhone-style
     * "allow exceptions" behaviour.
     */
    private fun toggleDnd() {
        val on = isDndOn()
        val filter = if (on) NotificationManager.INTERRUPTION_FILTER_ALL
        else NotificationManager.INTERRUPTION_FILTER_NONE
        runCatching { notifications.setInterruptionFilter(filter) }
            .onFailure { Logx.w("setInterruptionFilter failed: ${it.javaClass.simpleName}") }
        refreshDnd()
        Logx.d("dnd toggled: was $on, now ${isDndOn()}")
    }

    private fun isDndOn(): Boolean = runCatching {
        notifications.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    }.getOrDefault(false)

    private fun refreshDnd() {
        view.setDnd(isDndOn())
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /**
     * The newest item that has not aged past its own TTL. Expired leftovers are dropped here —
     * only the visible item ever gets a timer, so anything that was pushed aside would otherwise
     * sit in `live` until its app cancelled it.
     */
    private fun newestLive(): IslandEvent? {
        val now = android.os.SystemClock.elapsedRealtime()
        val stale = live.filterValues { now - it.bornAt > it.ttlMs }.keys.toList()
        stale.forEach { live.remove(it) }
        return live.values.lastOrNull()
    }

    /** A message outranks the player, the player outranks the battery, and privacy outranks none. */
    private fun IslandEvent.priorityOf(): Int = when (source) {
        Source.NOTIFICATION -> 3
        Source.MEDIA -> 2
        Source.CHARGING -> 1
        Source.PRIVACY -> 0
        Source.CALL -> 4
    }

    companion object {
        private const val ENTER_MS = 260L
        private const val EXIT_MS = 190L

        // Heavier than before on purpose: the collapse has to be readable as the capsule
        // travelling back onto the camera hole, not as a pop.
        private const val COLLAPSE_MS = 380L
        private const val RING_MS = 560L
        private const val SWEEP_DEGREES = 420f
        private const val AMBIENT_LOOP_MS = 2600L
        private const val BLUR_RADIUS = 60
        /** An opened player stays up long enough to be worth using. */
        private const val PLAYER_OPEN_MS = 45_000L
        /** Card height: the pill band plus a control row that a thumb can actually hit. */
        private const val PLAYER_H = 300
        /** How far the opened card sits below the top edge of the panel. */
        private const val PLAYER_Y = 14
        /** The list card is header band + rows + the grabber, and never more than four messages. */
        private const val MAX_LIST_ROWS = 4
        private const val LIST_FOOTER = 60
        private const val LIST_OPEN_MS = 45_000L
        /** Detail card: band + up to four lines + the chip row + the grabber. */
        private const val DETAIL_EXTRA = 300
        private const val DETAIL_OPEN_MS = 30_000L
        /** Band + the two call discs + the grabber. */
        private const val CALL_CARD_EXTRA = 210
        private const val ACCENT_DEFAULT = 0xFF8A65FF.toInt()

        // Our own curves, not extracted from anything: fast out, settle long.
        private val ENTER = PathInterpolator(0.2f, 0f, 0.1f, 1f)
        private val EXIT = PathInterpolator(0.4f, 0f, 1f, 1f)

        /** WindowManager.LayoutParams.FLAG_BLUR_BEHIND, referenced by value to stay compilable. */
        private const val FLAG_BLUR_BEHIND = 0x20000000
    }
}
