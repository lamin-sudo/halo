package dz.island.a79

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * Feeds notifications into the island by hooking the SystemUI notification pipeline.
 *
 * This is the whole reason the module lives inside com.android.systemui: the notifications are
 * already in this process, so there is no NotificationListenerService to declare, no
 * "allow notification access" dialog, and no second copy of the payload.
 *
 * Target is AOSP-named on this device (verified against the shipped SystemUI dex):
 *   NotifCollection.onNotificationPosted(StatusBarNotification, RankingMap)
 *   NotifCollection.onNotificationRemoved(StatusBarNotification, RankingMap, int)
 */
class NotificationSource(
    private val context: Context,
    private val window: IslandWindow,
) {

    private val main = Handler(Looper.getMainLooper())

    /** Per-slot lines for the list island, newest last, capped so the card never overflows. */
    private val rowsBySlot = LinkedHashMap<String, ArrayDeque<IslandRow>>(8)

    /**
     * Set by [IslandModule] once [MediaSource] has resolved a real MediaSession. Media rows then
     * come from the session, which carries position, duration and artwork; letting the
     * notification path write the same slot would blank the progress line between updates.
     */
    var mediaSessionLive: () -> Boolean = { false }

    private val importanceGetter =
        runCatching { Notification::class.java.getMethod("getImportance") }.getOrNull()
    private val priorityGetter =
        runCatching { Notification::class.java.getMethod("getPriority") }.getOrNull()

    private val pm = context.packageManager

    /** key → (last post time, how many posts it has swallowed, the key of the last post). */
    private val groupCounts = HashMap<String, Triple<Long, Int, String>>()
    private val labelCache = HashMap<String, String>()

    /** Which live notifications each app still has, so a group only clears when the last one goes. */
    private val activeByPkg = HashMap<String, MutableSet<String>>()

    /**
     * Both hook points fire for one notification (verified: two identical `post` lines 1-3ms
     * apart), so a burst would count double. Anything that reaches us twice inside this window is
     * the same delivery, not a second message.
     */
    private val recent = HashMap<String, Long>()

    private fun isDuplicate(sbn: StatusBarNotification): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        val prev = recent[sbn.key]
        recent[sbn.key] = now
        if (recent.size > 128) recent.clear()
        return prev != null && now - prev < 400L
    }

    fun install(module: XposedModule, param: PackageLoadedParam) {
        val clazz = runCatching {
            param.defaultClassLoader.loadClass("com.android.systemui.statusbar.notification.collection.NotifCollection")
        }.getOrElse {
            Logx.w("NotifCollection missing, notifications disabled: ${it.javaClass.simpleName}")
            return
        }

        val posted = clazz.declaredMethods.firstOrNull { m ->
            m.name == "onNotificationPosted" &&
                m.parameterTypes.size == 2 &&
                m.parameterTypes[0] == StatusBarNotification::class.java
        }
        // In AOSP onNotificationPosted calls postNotification, so hooking the first catches both.
        // ColorOS has its own delivery paths, and third-party notifications were seen reaching the
        // heads-up UI without ever touching onNotificationPosted — so hook the funnel as well.
        // submit() dedupes by key, so a notification that hits both only refreshes its timeout.
        val postedDirect = clazz.declaredMethods.firstOrNull { m ->
            m.name == "postNotification" &&
                m.parameterTypes[0] == StatusBarNotification::class.java
        }
        val removed = clazz.declaredMethods.firstOrNull { m ->
            m.name == "onNotificationRemoved" &&
                m.parameterTypes[0] == StatusBarNotification::class.java
        }

        if (posted == null && postedDirect == null) {
            Logx.w("neither onNotificationPosted nor postNotification found, notifications disabled")
            return
        }

        var hooked = 0
        for (method in listOfNotNull(posted, postedDirect)) {
            runCatching {
                method.isAccessible = true
                module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    val sbn = chain.args.getOrNull(0) as? StatusBarNotification
                    if (sbn != null) main.post { handlePosted(sbn) }
                    result
                }
                hooked++
            }.onFailure { Logx.e("posting hook failed for ${method.name}", it) }
        }
        Logx.d("notification source installed on $hooked entry points")

        if (removed != null) {
            runCatching {
                removed.isAccessible = true
                module.hook(removed).intercept { chain ->
                    val result = chain.proceed()
                    val sbn = chain.args.getOrNull(0) as? StatusBarNotification
                    if (sbn != null) main.post { handleRemoved(sbn) }
                    result
                }
            }.onFailure { Logx.e("removal hook failed", it) }
        } else {
            Logx.d("onNotificationRemoved not found: island items expire by TTL only")
        }
    }

    private fun handlePosted(sbn: StatusBarNotification) {
        val cfg = Config.current
        val n = sbn.notification ?: return
        val media = isMedia(n)
        val isCall = !media && n.category == Notification.CATEGORY_CALL
        val importance = importanceOf(n)
        val extras: Bundle? = n.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }
            ?: n.tickerText?.toString()?.takeIf { it.isNotBlank() }
            ?: sbn.packageName
        val body = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()

        /**
         * The green line and the `NN%` are for a task that is actually running, and a
         * `android.progress` extra alone does not prove that.
         *
         * Measured on this phone: Wingo posts a data-balance notification carrying
         * `android.progress=0`, and the island drew a 0% bar under it — a download meter on a
         * message that is just telling him his quota. So three things are required now: the app
         * declares a scale (`progressmax > 0`), it is not the indeterminate spinner, and the row
         * is an active task — ongoing, or `CATEGORY_PROGRESS`, which is Android's own label for
         * downloads and installs. A real Play Store download passes on both counts (measured
         * `ongoing=true progress=0 category=status`), and so does the self test.
         */
        val progressMax = extras?.getInt(Notification.EXTRA_PROGRESS_MAX, 0) ?: 0
        val indeterminate = extras?.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false) ?: false
        val activeTask = sbn.isOngoing || n.category == Notification.CATEGORY_PROGRESS
        val progress = if (
            extras?.containsKey(Notification.EXTRA_PROGRESS) == true &&
            progressMax > 0 && !indeterminate && activeTask
        ) {
            extras.getInt(Notification.EXTRA_PROGRESS, -1).coerceIn(-1, 100)
        } else {
            -1
        }

        val reason = when {
            !cfg.enabled -> "master off"
            !Config.isAppAllowed(sbn.packageName) -> "app off"
            media && !cfg.media -> "media source off"
            // MediaSession owns the player slot once it is live: it has the position and artwork,
            // and letting both write the same key would blank the green line between updates.
            media && mediaSessionLive() -> "media handled by the session"
            !media && !cfg.notification -> "notification source off"
            // Ongoing is normally a foreground service that should not interrupt — a player, a
            // navigation session. A download is ongoing too, and it is the one thing worth
            // showing, so an ongoing row with a progress bar gets through; so does a call.
            !media && !isCall && sbn.isOngoing && progress < 0 -> "ongoing"
            // The summary row a chat app posts beside its messages is bookkeeping for the shade,
            // not a message: counting it made three Telegram chats read as "6 messages" and put a
            // duplicate line in the unfolded list.
            !media && (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0 -> "group summary"
            // Only filter when the platform actually told us the importance. Notification's
            // importance field is filled for listener copies, not always for the copy SystemUI
            // receives, and dropping every real app's notification on an unknown value was the
            // bug: shell posts showed, Telegram/Gmail/X did not. A row with a progress bar is
            // exempt — Play Store and Chrome post downloads at low importance on purpose.
            !media && progress < 0 && importance in 0 until NotificationManager.IMPORTANCE_DEFAULT ->
                "importance $importance < default"
            else -> null
        }
        Logx.d(
            "post ${sbn.packageName}/${sbn.id} media=$media ongoing=${sbn.isOngoing} " +
                "importance=$importance progress=$progress/$progressMax " +
                "category=${n.category} -> ${reason ?: "ISLAND"}"
        )
        if (reason != null) return
        if (isDuplicate(sbn)) {
            Logx.d("duplicate delivery for ${sbn.key} (two entry points), ignored")
            return
        }

        val actions = n.actions?.take(2)?.map { a ->
            IslandAction(a.title?.toString().orEmpty().ifBlank { "Open" }, a.actionIntent)
        } ?: emptyList()

        // One slot per app. A burst of Telegram messages becomes "Telegram • 3 messages" instead
        // of three capsules in a row, which was the thing that made the island feel like noise.
        val key = slotKey(sbn)
        val now = android.os.SystemClock.elapsedRealtime()
        val last = groupCounts[key]
        // A download re-posts the *same* notification on every chunk. Counting that as another
        // message made a single 45%-done update read as "9 messages", so an update of the same
        // key keeps the count it already had.
        val repeated = last != null && last.third == sbn.key
        val count = when {
            last == null || now - last.first >= GROUP_WINDOW_MS -> 1
            repeated -> last.second
            else -> last.second + 1
        }
        groupCounts[key] = Triple(now, count, sbn.key)
        val (avatar, initialRaw) = avatarOf(n, extras, title)
        // A player capsule shows album art or the music glyph — never a letter of the track title.
        val initial = if (media) null else initialRaw
        Logx.d(
            "group $key -> $count in ${GROUP_WINDOW_MS}ms window " +
                "avatar=${avatar != null} initial=$initial"
        )
        activeByPkg.getOrPut(sbn.packageName) { HashSet<String>(4) }.add(sbn.key)

        // Keep the individual lines so a burst can be unfolded. A new window starts a new list;
        // otherwise the card would show messages that were already cleared.
        if (!media) {
            val rows = rowsBySlot.getOrPut(key) { ArrayDeque(MAX_ROWS) }
            if (count == 1) rows.clear()
            val senderRaw = senderName(firstSender(extras))
            // `android.title` of a chat is the platform's own composition ("Lamini: Lamini"), so
            // the conversation name is the next best thing to a sender name — never the title.
            val sender = senderRaw
                ?: runCatching { extras?.getCharSequence("android.conversationTitle")?.toString() }
                    .getOrNull()?.takeIf { it.isNotBlank() }
                ?: title
            if (senderRaw == null) Logx.d("no sender name on ${sbn.id}, row shows: $sender")
            // An edited message re-posts the same key; without this it would appear twice.
            rows.removeAll { it.key == sbn.key }
            rows.addLast(
                IslandRow(
                    key = sbn.key,
                    name = sender,
                    snippet = body,
                    avatar = avatar,
                    initial = initial,
                    deleteIntent = n.deleteIntent,
                )
            )
            while (rows.size > MAX_ROWS) rows.removeFirst()
        }

        val displayTitle = if (!media && count > 1) labelOf(sbn.packageName) else title
        val displayTrailing = when {
            !media && count > 1 -> "$count messages"
            // A download reads better as a number than as "Downloading 1 of 4…" — the bar under
            // it already says how far it is.
            !media && progress >= 0 -> "$progress%"
            else -> body
        }

        window.submit(
            IslandEvent(
                key = key,
                source = when {
                    media -> Source.MEDIA
                    isCall -> Source.CALL
                    else -> Source.NOTIFICATION
                },
                title = displayTitle,
                // One line each side of the hole: title left, text right. Stacking two lines in
                // the left zone only worked on paper — the camera cutout ate the middle of them.
                body = null,
                trailing = displayTrailing,
                icon = loadIcon(n),
                avatar = avatar,
                avatarText = initial,
                // The paragraph the pill had to drop, kept for the detail card.
                detailText = if (media || isCall) null
                else (extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                    ?: body)?.takeIf { it.isNotBlank() },
                appLabel = if (media || isCall) null else labelOf(sbn.packageName),
                rows = if (media || isCall) emptyList() else rowsBySlot[key]?.toList() ?: emptyList(),
                sourceKeys = listOf(sbn.key),
                actions = actions,
                progress = progress,
                accentArgb = if (isCall) ACCENT_CALL else accentOf(n, media),
                // A call lasts as long as it is up; the dialer re-posts on every state change and
                // each one re-arms this.
                // Two actions means the dialer is asking a question; one means it is only
                // reporting. A running call gets no deadline either — the ring stays until the
                // dialer clears the notification.
                incoming = isCall && actions.size >= 2,
                ttlMs = when {
                    !isCall -> cfg.ttlMs
                    actions.size >= 2 -> CALL_TTL_MS
                    else -> 0L
                },
                openIntent = n.contentIntent,
                // The bell belongs on a message, not on a music control row or a call.
                showBell = !media && !isCall,
            )
        )
    }

    /**
     * The island slot for a record. Media and calls own theirs; everything else is one slot per
     * app so a burst collapses. Both the posting and the removal path go through this, because a
     * mismatch here is a capsule that never goes away.
     */
    private fun slotKey(sbn: StatusBarNotification): String {
        val pkg = sbn.packageName
        return when (sbn.notification?.category) {
            Notification.CATEGORY_TRANSPORT -> "media|$pkg"
            Notification.CATEGORY_CALL -> "call|$pkg"
            else -> "notif|$pkg"
        }
    }

    /**
     * A group leaves the island only when the app's last notification does, otherwise reading one
     * message would wipe the capsule that still stands for three more.
     */
    private fun handleRemoved(sbn: StatusBarNotification) {
        rowsBySlot.values.forEach { deque -> deque.removeAll { it.key == sbn.key } }
        val live = activeByPkg[sbn.packageName] ?: return
        live.remove(sbn.key)
        if (live.isNotEmpty()) return
        activeByPkg.remove(sbn.packageName)
        window.cancel(slotKey(sbn))
    }

    /** App labels cost a binder call, and grouping asks for one per burst. */
    private fun labelOf(pkg: String): String =
        labelCache.getOrPut(pkg) {
            runCatching {
                val info = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(info).toString()
            }.getOrDefault(pkg)
        }

    /**
     * Channel importance. `Notification.getImportance()` is not in the public stub, but this code
     * runs as SystemUI, where the hidden-API restrictions that bind ordinary apps do not apply,
     * so a cached reflective call is both accurate and cheap. Falls back to the public priority.
     */
    private fun importanceOf(n: Notification): Int {
        runCatching { (importanceGetter?.invoke(n) as? Int)?.takeIf { it > 0 }?.let { return it } }
        runCatching {
            (priorityGetter?.invoke(n) as? Int)?.takeIf { it != 0 }?.let { return it + 3 }
        }
        // Nothing known: the caller must not filter on it.
        return -1
    }

    /**
     * The collapse ring takes the app's own accent when it set one, so WhatsApp closes green
     * and Gmail closes red, the way the iPhone's live activities do.
     */
    private fun accentOf(n: Notification, media: Boolean): Int {
        if (media) return ACCENT_MEDIA
        val c = n.color
        if (c == 0 || Color.alpha(c) == 0) return ACCENT_DEFAULT
        val r = Color.red(c)
        val g = Color.green(c)
        val b = Color.blue(c)
        if (maxOf(r, g, b) - minOf(r, g, b) < 24) return ACCENT_DEFAULT
        return c
    }

    private fun isMedia(n: Notification): Boolean = n.category == Notification.CATEGORY_TRANSPORT

    /**
     * The sender's picture and, for the far more common case on this ROM, their initial.
     *
     * Pictures are tried in the order the apps actually carry them:
     *
     * 1. the sender's Person inside `android.messages` (MessagingStyle — Telegram, WhatsApp,
     *    Google Messages). The accessor is `getSenderPerson()`; `getPerson()` does not exist on
     *    `Message`, and asking for it is what made every chat avatar silently fail.
     * 2. `android.people.list` — measured on this phone, Telegram Plus posts a plain title/text
     *    row with `category=msg`, no messaging style and no large icon.
     * 3. `getLargeIcon()` / the deprecated bitmap (mail and social apps).
     *
     * On ColorOS 13.1 the first two almost always come back empty: every notification carries
     * `android.reduced.images=true` (9 of 9 in the shade, and my own test row with an explicitly
     * set Person icon included), and the platform strips Person photos before SystemUI sees them.
     * `ro.config.low_ram` is empty and `reduce_icon_size` is unset, so it is an OEM
     * `config_reduceImageResources` overlay, not a user switch — nothing readable is left. Hence
     * the initial, which is what iOS shows in the same situation.
     *
     * Everything is reflective: `getParcelableArrayList` wants a `Parcelable` bound the SDK stubs
     * do not resolve for `MessagingStyle.Message`, and a hook inside SystemUI must not be one
     * unchecked cast away from a status-bar crash.
     */
    private fun avatarOf(
        n: Notification,
        extras: Bundle?,
        fallbackName: String,
    ): Pair<Drawable?, String?> {
        val sender = firstSender(extras)
        val fromMessages = sender?.let { person ->
            runCatching {
                (person.javaClass.getMethod("getIcon").invoke(person)
                    as? android.graphics.drawable.Icon)?.loadDrawable(context)
            }.getOrNull()
        }
        if (fromMessages != null) return fromMessages to null

        val fromPeople = runCatching {
            val people = extras?.getParcelableArray("android.people.list") ?: return@runCatching null
            people.firstOrNull { it is android.app.Person }
                ?.let { (it as android.app.Person).icon }
                ?.loadDrawable(context)
        }.getOrNull()
        if (fromPeople != null) return fromPeople to null

        val large = runCatching { n.getLargeIcon() }.getOrNull()
        large?.let {
            val d = runCatching { it.loadDrawable(context) }.getOrNull()
            if (d != null) return d to null
        }
        val bitmap = @Suppress("DEPRECATION")
        runCatching { n.largeIcon?.let { BitmapDrawable(context.resources, it) } }.getOrNull()
        if (bitmap != null) return bitmap to null

        // No picture anywhere. Logged with the shape so it stays possible to tell "the app sent
        // none" from "the ROM stripped it" without a phone in hand.
        Logx.d(
            "no avatar: messages=${runCatching { extras?.getParcelableArray("android.messages")?.size }.getOrNull()} " +
                "messageIcon=${fromMessages != null} people=${runCatching { extras?.getParcelableArray("android.people.list")?.size }.getOrNull()} " +
                "largeIcon=${large != null} reduced=" +
                runCatching { extras?.getBoolean("android.reduced.images") }.getOrNull()
        )
        return null to initialOf(senderName(sender) ?: fallbackName)
    }

    /**
     * The sender behind the first message, or null if the row is not a conversation.
     *
     * `Notification.Builder` writes `android.messages` as a Parcelable[] of **Bundles**, not of
     * MessagingStyle.Message objects, so the Person has to come out of the bundle. Reading it as a
     * Message only ever worked in the javadoc — which is the reason a Telegram sender's picture
     * never reached the pill, and why every row fell back to the title.
     */
    private fun firstSender(extras: Bundle?): Any? = runCatching {
        val first = extras?.getParcelableArray("android.messages")?.firstOrNull()
            ?: return@runCatching null
        if (first is Bundle) {
            val person = @Suppress("DEPRECATION")
                runCatching { first.getParcelable<android.os.Parcelable>("sender_person") }.getOrNull()
            person ?: first.getString("sender")
        } else {
            listOf("getSenderPerson", "getPerson").firstNotNullOfOrNull { name ->
                runCatching { first.javaClass.getMethod(name).invoke(first) as Any? }.getOrNull()
            }
        }
    }.getOrNull()

    /**
     * The display name off a Person.
     *
     * This ROM's `android.app.Person` has no `getDisplayName()` at all — dumping the framework's
     * own dex lists only getName/getIcon/getIconUri/getKey/getUri/isBot/isImportant — so the
     * documented accessor throws NoSuchMethodException for every chat app, and the sender name
     * has to come off `getName()` instead.
     */
    private fun senderName(person: Any?): String? {
        if (person == null) return null
        if (person is String) return person.takeIf { it.isNotBlank() }
        val read = listOf("getDisplayName", "getName").firstNotNullOfOrNull { name ->
            runCatching {
                (person.javaClass.getMethod(name).invoke(person) as? CharSequence)?.toString()
            }.getOrNull()
        }
        return read?.takeIf { text -> text.isNotBlank() }
    }

    /** One uppercase letter, iOS-style. Takes the first real character so Arabic and Cyrillic
     *  names get their own initial rather than a question mark. */
    private fun initialOf(name: String?): String? =
        name?.trim()?.firstOrNull { !it.isWhitespace() && it.isLetterOrDigit() }
            ?.uppercaseChar()?.toString()

    /**
     * Dismiss one line of the list island. The direct route is what SystemUI's own shade uses,
     * `NotificationManager.cancelNotification(key)`; if the platform refuses it from here we fall
     * back to the notification's own deleteIntent, which is the app's "clear" button. Logged
     * either way, because "which one actually worked" is exactly the thing worth knowing once.
     */
    fun dismissRow(key: String) {
        val direct = runCatching {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val m = nm.javaClass.getMethod("cancelNotification", String::class.java)
            m.invoke(nm, key)
            true
        }.getOrDefault(false)
        if (!direct) {
            val intent = rowsBySlot.values.asSequence().flatten()
                .firstOrNull { it.key == key }?.deleteIntent
            runCatching { intent?.send() }
                .onFailure { Logx.w("row dismiss failed for $key: ${it.javaClass.simpleName}") }
        }
        rowsBySlot.values.forEach { deque -> deque.removeAll { it.key == key } }
        Logx.d("row dismissed via ${if (direct) "cancelNotification" else "deleteIntent"}")
    }

    private fun loadIcon(n: Notification): Drawable? {
        val icon = n.smallIcon ?: return null
        return runCatching {
            icon.loadDrawable(context)?.also { it.setTint(TEXT_WHITE) }
        }.getOrNull()
    }

    companion object {
        private const val TEXT_WHITE = 0xFFFFFFFF.toInt()
        private const val ACCENT_MEDIA = 0xFF4DA6FF.toInt()
        private const val ACCENT_DEFAULT = 0xFF6EA8FE.toInt()

        /** Posts from the same app inside this window collapse into one capsule with a count. */
        private const val GROUP_WINDOW_MS = 3_000L
        private const val MAX_ROWS = 4

        /** A call stays on screen until the dialer clears it; each re-post re-arms this. */
        private const val CALL_TTL_MS = 60_000L
        private val ACCENT_CALL = 0xFF30D158.toInt()
    }
}
