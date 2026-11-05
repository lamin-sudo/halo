package io.github.lamin_sudo.halo

import android.app.PendingIntent
import android.graphics.drawable.Drawable

/** Where an island payload came from. Also decides the shape it renders in. */
enum class Source {
    NOTIFICATION,
    MEDIA,
    CHARGING,

    /** Who is holding the camera or the microphone right now. */
    PRIVACY,

    /**
     * A live call, taken from the dialer's own CATEGORY_CALL notification: the answer and hang-up
     * buttons are that notification's actions, so no telecom hook is needed.
     */
    CALL;

    /**
     * Everything wide: the split layout (content on both sides of the camera hole) is the only
     * one that keeps text clear of the cutout, so the narrow pill is gone.
     */
    val wantsWide: Boolean get() = true
}

/** A button carried over from the original notification, so the island stays actionable. */
class IslandAction(val title: String, val intent: PendingIntent?)

/**
 * One line in the grouped-notification island: what a burst of Telegram messages unfolds into.
 *
 * [key] is the real notification key, because dismissing a row has to dismiss exactly that
 * notification and nothing else. [deleteIntent] is the app's own "clear" action, kept as the
 * fallback when the platform refuses a cancel from this process.
 */
class IslandRow(
    val key: String,
    val name: String,
    val snippet: String?,
    val avatar: Drawable?,
    val initial: String?,
    val deleteIntent: PendingIntent?,
)

/**
 * One thing to show. Immutable: the queue holds events, the view renders them.
 *
 * [key] identifies the source item so a removal (notification cancelled, media stopped)
 * can dismiss exactly what it replaced and nothing else.
 *
 * The island is split by the camera hole: [title] + icon go in the left zone, [trailing]
 * (percentage, short body) goes in the right zone. [body] stays for the stacked text of a
 * notification, drawn under the title on the left.
 */
class IslandEvent(
    val key: String,
    val source: Source,
    val title: String,
    val body: String?,
    /** Right-zone text: "45%" for charging, the sender for a notification. */
    val trailing: String?,
    val icon: Drawable?,
    /** The sender's own picture when the app provides one; drawn as a circle, over the app icon. */
    val avatar: Drawable?,
    /**
     * The sender's initial, for the many apps that cannot give a picture: ColorOS stamps
     * `android.reduced.images=true` on every notification and the platform strips Person photos
     * before SystemUI ever sees them. iOS answers that with a coloured circle and a letter, so we
     * do the same. Null when there is no name to take an initial from, and the app icon shows.
     */
    val avatarText: String?,
    val actions: List<IslandAction>,
    /** -1 when the item has no progress to report. */
    val progress: Int,
    /** Tint for the collapse ring and the right-zone text. */
    val accentArgb: Int,
    val ttlMs: Long,
    val openIntent: PendingIntent?,
    /** The bell that toggles Do-Not-Disturb, iPhone-style. Notifications only. */
    val showBell: Boolean,
    /** Media only: whether the session is playing, so the mini player shows the right glyph. */
    val playing: Boolean? = null,
    /**
     * False for a media update that only moves the progress line. The ticker re-submits every
     * second, and each re-submit used to restart the TTL — so a playing track kept the capsule on
     * screen forever instead of stepping down to the ring after the usual ~10s.
     */
    val refreshTtl: Boolean = true,
    /**
     * Calls only: true while it is ringing (the dialer offers answer *and* reject), false once the
     * call is up. A ringing call gets the card with the two discs; a running call gets the green
     * ring at the notch instead, so the island does not cover the dialer's own controls.
     */
    val incoming: Boolean = false,
    /**
     * The whole text of a single notification, for the detail card. The pill only ever gets one
     * line each side of the hole, which is right for a glance and useless for a paragraph.
     */
    val detailText: String? = null,
    /** The app's own label, so the card can say who is speaking under the title. */
    val appLabel: String? = null,
    /** The individual messages folded into this slot, newest last. Two or more opens the list. */
    val rows: List<IslandRow> = emptyList(),
    /** The real notification keys folded into this slot. The heads-up suppressor matches on
     *  these, never on [key]: the slot is per app, a banner is per notification. */
    val sourceKeys: List<String> = emptyList(),
    /** When this arrived; an item that was pushed aside by something more important is
     *  dropped once its own TTL has passed, so it never pops up hours later. */
    val bornAt: Long = android.os.SystemClock.elapsedRealtime(),
) {
    fun width(geo: Geometry): Int = geo.activeW
}
