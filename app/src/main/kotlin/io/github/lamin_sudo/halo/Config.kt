package io.github.lamin_sudo.halo

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * Settings written by [SettingsActivity] and read here through [ConfigStore].
 *
 * They are not read through libxposed remote preferences: that path returns an empty key set on
 * this LSPosed build, which is how "Hide the old heads-up card" sat switched-on in the UI while
 * the hook kept showing the old banner. See [ConfigStore] for the reasoning and the grant.
 *
 * A ContentObserver on Settings.Global makes the change live: no SystemUI restart, no dialog.
 */
object Config {

    const val KEY_ENABLED = "enabled"
    const val KEY_NOTIF = "src_notification"
    const val KEY_MEDIA = "src_media"
    const val KEY_CHARGING = "src_charging"
    const val KEY_TTL = "ttl_ms"
    const val KEY_OFFSET_X = "offset_x"
    const val KEY_OFFSET_Y = "offset_y"
    const val KEY_WIDTH_SCALE = "width_scale"
    const val KEY_DEBUG_LOG = "debug_log"
    const val KEY_HIDE_HEADS_UP = "hide_heads_up"
    const val KEY_HAPTICS = "haptics"
    const val KEY_IMMERSIVE_HIDE = "immersive_hide"
    const val KEY_GAMES_HIDE = "games_hide"
    const val KEY_PRIVACY = "privacy"
    const val KEY_ALLOW_PREFIX = "allow_"

    /**
     * Immutable copy so the hot path reads fields instead of a settings query per event.
     *
     * `haptics` and `gamesHide` default on because both are the behaviour the island is supposed
     * to have: the first is the buzz that makes a swipe feel real, the second is what the user
     * asked Game Space for when he switched on "reject notifications" in a match. `immersiveHide`
     * also defaults on now that WindowManager's own `setTopAppHidesStatusBar` is the source
     * ([StatusBarFade.installTopAppWatcher]) — measured on YouTube fullscreen, and measured *not*
     * to trigger on the keyguard, sleep, or wake, so the lock-screen island is safe.
     */
    class Snapshot(
        val enabled: Boolean,
        val notification: Boolean,
        val media: Boolean,
        val charging: Boolean,
        val ttlMs: Long,
        val offsetX: Int,
        val offsetY: Int,
        val widthScale: Int,
        val debugLog: Boolean,
        val hideHeadsUp: Boolean,
        val haptics: Boolean,
        val immersiveHide: Boolean,
        val gamesHide: Boolean,
        val privacy: Boolean,
        /**
         * Bumped by [CustomReceiver] in the app process. It is part of the snapshot so a payload
         * that is identical to the last one still reaches the island.
         */
        val customSeq: Int,
    ) {
        override fun toString(): String =
            "on=$enabled notif=$notification media=$media charge=$charging ttl=${ttlMs}ms " +
                "xy=$offsetX,$offsetY scale=$widthScale% headsUpHidden=$hideHeadsUp " +
                "haptics=$haptics immersiveHide=$immersiveHide gamesHide=$gamesHide " +
                "privacy=$privacy seq=$customSeq"

        /** True when every field matches, so a reload that changed nothing stays quiet. */
        fun sameAs(other: Snapshot): Boolean =
            enabled == other.enabled && notification == other.notification &&
                media == other.media && charging == other.charging &&
                ttlMs == other.ttlMs && offsetX == other.offsetX && offsetY == other.offsetY &&
                widthScale == other.widthScale && debugLog == other.debugLog &&
                hideHeadsUp == other.hideHeadsUp && haptics == other.haptics &&
                immersiveHide == other.immersiveHide && gamesHide == other.gamesHide &&
                privacy == other.privacy &&
                customSeq == other.customSeq
    }

    private var resolver: ContentResolver? = null

    @Volatile
    var current = Snapshot(
        enabled = true, notification = true, media = true, charging = true, ttlMs = 5_000L,
        offsetX = 0, offsetY = 0, widthScale = 100, debugLog = false, hideHeadsUp = false,
        haptics = true, immersiveHide = true, gamesHide = true, privacy = true, customSeq = 0,
    )
        private set

    private val listeners = mutableListOf<() -> Unit>()
    private val handler = Handler(Looper.getMainLooper())

    // Global settings change for reasons that have nothing to do with us, and every write of a
    // slider fires several of these. One reload is ~10 queries, so coalesce instead of optimising.
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            handler.removeCallbacks(reloadRunnable)
            handler.postDelayed(reloadRunnable, 120L)
        }
    }

    private val reloadRunnable = Runnable { reload() }

    @Synchronized
    fun init(context: Context) {
        if (resolver != null) return
        resolver = context.contentResolver
        runCatching {
            context.contentResolver.registerContentObserver(
                Settings.Global.CONTENT_URI, true, observer
            )
        }.onFailure { Logx.w("settings observer unavailable: ${it.javaClass.simpleName}") }
        reload()
    }

    fun addChangeListener(onChange: () -> Unit) {
        synchronized(listeners) { listeners.add(onChange) }
    }

    fun reload() {
        val res = resolver ?: return
        val next = Snapshot(
            enabled = ConfigStore.getBoolean(res, KEY_ENABLED, true),
            notification = ConfigStore.getBoolean(res, KEY_NOTIF, true),
            media = ConfigStore.getBoolean(res, KEY_MEDIA, true),
            charging = ConfigStore.getBoolean(res, KEY_CHARGING, true),
            ttlMs = ConfigStore.getInt(res, KEY_TTL, 5_000).toLong(),
            offsetX = ConfigStore.getInt(res, KEY_OFFSET_X, 0),
            offsetY = ConfigStore.getInt(res, KEY_OFFSET_Y, 0),
            widthScale = ConfigStore.getInt(res, KEY_WIDTH_SCALE, 100),
            debugLog = ConfigStore.getBoolean(res, KEY_DEBUG_LOG, false),
            hideHeadsUp = ConfigStore.getBoolean(res, KEY_HIDE_HEADS_UP, false),
            haptics = ConfigStore.getBoolean(res, KEY_HAPTICS, true),
            immersiveHide = ConfigStore.getBoolean(res, KEY_IMMERSIVE_HIDE, true),
            gamesHide = ConfigStore.getBoolean(res, KEY_GAMES_HIDE, true),
            privacy = ConfigStore.getBoolean(res, KEY_PRIVACY, true),
            customSeq = ConfigStore.getInt(res, CustomReceiver.KEY_CUSTOM_SEQ, 0),
        )
        // Global settings move for reasons that have nothing to do with us — this ROM writes them
        // every few seconds — so a reload that changed nothing stays silent instead of filling
        // logcat with the same line.
        if (next.sameAs(current)) return
        current = next
        Logx.d("config reloaded: $current")
        synchronized(listeners) { listeners.toList() }.forEach { it() }
    }

    /** Apps are on by default; the settings screen can switch one off explicitly. */
    fun isAppAllowed(pkg: String): Boolean =
        ConfigStore.getBoolean(resolver, KEY_ALLOW_PREFIX + pkg, true)

    fun debugEnabled(): Boolean = current.debugLog
}
