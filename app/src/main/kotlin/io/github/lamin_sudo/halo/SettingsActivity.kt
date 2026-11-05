package io.github.lamin_sudo.halo

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/**
 * Settings for the module. Plain framework views on purpose: this screen is opened once in a
 * while and never runs inside SystemUI, so it should cost nothing to build and nothing to keep.
 *
 * Everything is stored in Settings.Global (see [ConfigStore]) because that is the one place the
 * hooked SystemUI process can actually read. Writing it needs WRITE_SECURE_SETTINGS, granted
 * once from a PC:
 *     adb shell su -c 'pm grant io.github.lamin_sudo.halo android.permission.WRITE_SECURE_SETTINGS'
 * If the grant is missing the switches still move, but a toast says the change did not stick.
 */
class SettingsActivity : Activity() {

    private var warnedAboutGrant = false

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // `am start` resumes the existing instance instead of creating a new one, so the debug
        // entry has to be handled here too or the test never fires a second time.
        setIntent(intent)
        handleTestIntent(intent)
    }

    private fun handleTestIntent(source: Intent?): Boolean =
        when (source?.getStringExtra("test")) {
            "1" -> {
                ensureTestChannel()
                postTestNotification()
                true
            }
            "media" -> {
                ensureTestChannel()
                postMediaTestNotification()
                true
            }
            "chat" -> {
                postChatTestNotification()
                true
            }
            "detail" -> {
                ensureTestChannel()
                postDetailTestNotification()
                true
            }
            "progress" -> {
                ensureTestChannel()
                postProgressTestNotification()
                true
            }
            "nogauge" -> {
                ensureTestChannel()
                postNoGaugeTestNotification()
                true
            }
            "call" -> {
                postCallTestNotification()
                true
            }
            "ongoing" -> {
                postOngoingCallTestNotification()
                true
            }
            // Fullscreen is the one case that cannot be driven from a PC: the app has to actually
            // own the screen with the bar gone. So it does that, posts while it is up, and closes.
            "immersive" -> {
                ensureTestChannel()
                window.decorView.systemUiVisibility =
                    android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                    android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                window.decorView.postDelayed({ postTestNotification() }, 1_500L)
                window.decorView.postDelayed({ finish() }, 6_000L)
                false
            }
            "stop" -> {
                getSystemService(NotificationManager::class.java)?.cancelAll()
                true
            }
            else -> false
        }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Without this the self-test notification is dropped by the platform on API 33+.
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0)
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, Ui.dp(this@SettingsActivity, 8f), 0, Ui.dp(this@SettingsActivity, 36f))
        }

        column.addView(header())

        column.addView(Ui.sectionHeader(this, "Island"))
        column.addView(group(
            switchRow(Config.KEY_ENABLED, "Enabled", Glyph.Kind.HALO, Ui.BLUE),
            switchRow(Config.KEY_NOTIF, "Notifications", Glyph.Kind.BELL, Ui.INDIGO),
            switchRow(Config.KEY_MEDIA, "Media · now playing", Glyph.Kind.MUSIC, Ui.PINK),
            switchRow(Config.KEY_CHARGING, "Charging", Glyph.Kind.BOLT, Ui.GREEN),
            switchRow(Config.KEY_PRIVACY, "Camera / mic indicators", Glyph.Kind.CAMERA, Ui.GREY),
            sliderRow("Time on screen", Glyph.Kind.TIMER, Ui.ORANGE, Config.KEY_TTL,
                min = 2, max = 15, defaultStored = 5_000, scale = 1_000, unit = " s"),
        ))

        column.addView(Ui.sectionHeader(this, "When to stay out of the way"))
        column.addView(group(
            switchRow(Config.KEY_GAMES_HIDE, "Inside a game", Glyph.Kind.GAME, Ui.ORANGE),
            switchRow(Config.KEY_IMMERSIVE_HIDE, "Fullscreen video", Glyph.Kind.EXPAND, Ui.TEAL),
            switchRow(Config.KEY_HIDE_HEADS_UP, "Hide the old heads-up card", Glyph.Kind.CARD, Ui.GREY),
        ))

        column.addView(Ui.sectionHeader(this, "Feel & placement"))
        column.addView(group(
            switchRow(Config.KEY_HAPTICS, "Buzz on taps and swipes", Glyph.Kind.WAVE, Ui.PURPLE),
            sliderRow("Width", Glyph.Kind.RULE, Ui.GREY, Config.KEY_WIDTH_SCALE,
                min = 60, max = 140, defaultStored = 100, scale = 1, unit = " %"),
            sliderRow("Shift sideways", Glyph.Kind.RULE, Ui.GREY, Config.KEY_OFFSET_X,
                min = -60, max = 60, defaultStored = 0, scale = 1, unit = " px"),
            sliderRow("Push down", Glyph.Kind.RULE, Ui.GREY, Config.KEY_OFFSET_Y,
                min = 0, max = 40, defaultStored = 0, scale = 1, unit = " px"),
        ))

        column.addView(Ui.sectionHeader(this, "For debugging"))
        column.addView(group(
            switchRow(Config.KEY_DEBUG_LOG, "Verbose logcat (HaloIsland)", Glyph.Kind.LOG, Ui.GREY),
        ))

        column.addView(button("Send test notification") { postTestNotification() })
        column.addView(note())

        setContentView(ScrollView(this).apply {
            addView(column)
            setBackgroundColor(Ui.BG)
        })

        // `adb shell am start -n io.github.lamin_sudo.halo/.SettingsActivity --es test 1`
        // `adb shell am start -n io.github.lamin_sudo.halo/.SettingsActivity --es test media`
        // `adb shell am start -n io.github.lamin_sudo.halo/.SettingsActivity --es test stop`
        // Posts a real notification from a real channel, so the pipeline can be tested without
        // anyone having to tap the screen. The media variant is a CATEGORY_TRANSPORT row with
        // transport actions, which is what the player capsule and the ambient ring key off.
        // Deferred by one frame: an app-started intent can arrive before the permission grant
        // above has taken effect, and a dropped notification is worse than a 50ms delay.
        if (intent?.hasExtra("test") == true) {
            window.decorView.postDelayed({
                if (handleTestIntent(intent)) finish()
            }, 50L)
        }
    }

    /** iOS large title, then the one-line description the launcher never has room for. */
    private fun header(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(Ui.dp(this@SettingsActivity, 20f), 0, Ui.dp(this@SettingsActivity, 20f),
            Ui.dp(this@SettingsActivity, 22f))
        addView(Ui.title(this@SettingsActivity, getString(R.string.app_name), 32f))
        addView(TextView(this@SettingsActivity).apply {
            text = getString(R.string.app_subtitle)
            setTextColor(Ui.DIM)
            textSize = 13f
            setPadding(0, Ui.dp(this@SettingsActivity, 2f), 0, 0)
        })
    }

    /** A rounded card holding rows, with the separators drawn between them. */
    private fun group(vararg rows: View): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.card(this@SettingsActivity)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = Ui.dp(this@SettingsActivity, 16f)
                marginEnd = Ui.dp(this@SettingsActivity, 16f)
                bottomMargin = Ui.dp(this@SettingsActivity, 20f)
            }
            clipToOutline = true
        }
        rows.forEachIndexed { i, row ->
            if (i > 0) card.addView(Ui.separator(this@SettingsActivity))
            card.addView(row)
        }
        return card
    }

    /** The row shell every variant shares: chip, label, trailing view, fixed height. */
    private fun rowShell(
        label: String,
        kind: Glyph.Kind,
        tint: Int,
        trailing: View?,
        trailingWeight: Boolean = false,
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(this@SettingsActivity, 13f), Ui.dp(this@SettingsActivity, 10f),
                Ui.dp(this@SettingsActivity, 13f), Ui.dp(this@SettingsActivity, 10f))
        }
        row.addView(iconChip(this, kind, tint))
        row.addView(TextView(this).apply {
            text = label
            setTextColor(Ui.TEXT)
            textSize = 15.5f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (trailing != null) {
            if (trailingWeight) {
                row.addView(trailing)
            } else {
                row.addView(trailing, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = Ui.dp(this@SettingsActivity, 8f) })
            }
        }
        return row
    }

    private fun switchRow(key: String, label: String, kind: Glyph.Kind, tint: Int): View {
        val res = contentResolver
        // Value first, listener second: wiring the callback before the initial state would
        // rewrite every setting on the screen each time it opens.
        val sw = IosSwitch(this) { checked ->
            if (!ConfigStore.putBoolean(res, key, checked)) warnAboutGrant()
        }
        sw.setInitial(ConfigStore.getBoolean(res, key, defaultFor(key)))
        return rowShell(label, kind, tint, sw)
    }

    private fun note(): View = TextView(this).apply {
        text = "Changes apply live and are stored in system settings, so the island reads them " +
            "without a restart. If a switch does not stick, grant the writer once from a PC:\n" +
            "adb shell su -c 'pm grant io.github.lamin_sudo.halo android.permission.WRITE_SECURE_SETTINGS'\n\n" +
            "If the island ever misbehaves: adb shell su -c 'settings put global a79_island_disabled 1'"
        textSize = 11.5f
        setTextColor(0xFF6F6F76.toInt())
        setPadding(Ui.dp(this@SettingsActivity, 24f), Ui.dp(this@SettingsActivity, 4f),
            Ui.dp(this@SettingsActivity, 24f), 0)
    }

    /** The one failure this screen can have: the grant was never given, so nothing sticks. */
    private fun warnAboutGrant() {
        if (warnedAboutGrant) return
        warnedAboutGrant = true
        android.widget.Toast.makeText(
            this,
            "Cannot write settings — run: adb shell su -c 'pm grant io.github.lamin_sudo.halo " +
                "android.permission.WRITE_SECURE_SETTINGS'",
            android.widget.Toast.LENGTH_LONG,
        ).show()
    }

    private fun defaultFor(key: String): Boolean =
        key != Config.KEY_DEBUG_LOG && key != Config.KEY_HIDE_HEADS_UP

    /**
     * A number row: the value lives on the right in grey with a chevron, and tapping the row opens
     * the slider in a sheet. Inline sliders were the old design and they were wrong twice over —
     * the label said "(s)" while the value was milliseconds, and a SeekBar cannot represent a
     * negative range without the shift that used to be buried in this file.
     *
     * [scale] is how many stored units make one shown unit: TTL is stored in milliseconds and
     * shown in seconds, everything else is 1.
     */
    private fun sliderRow(
        label: String,
        kind: Glyph.Kind,
        tint: Int,
        key: String,
        min: Int,
        max: Int,
        defaultStored: Int,
        scale: Int,
        unit: String,
    ): View {
        val res = contentResolver
        val shown = { stored: Int -> Math.floorDiv(stored, scale) }
        val display = { stored: Int -> "${shown(stored)}$unit" }
        val value = TextView(this).apply {
            text = display(ConfigStore.getInt(res, key, defaultStored))
            setTextColor(Ui.DIM)
            textSize = 15f
        }
        val row = rowShell(label, kind, tint, null).apply {
            addView(value, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = Ui.dp(this@SettingsActivity, 8f) })
            addView(Ui.chevron(this@SettingsActivity))
            setOnClickListener {
                openSlider(label, min, max, shown(ConfigStore.getInt(res, key, defaultStored)), unit) { v ->
                    val stored = v * scale
                    if (!ConfigStore.putInt(res, key, stored)) warnAboutGrant()
                    value.text = display(stored)
                }
            }
        }
        return row
    }

    /** The sheet a number row opens. Framework dialog, no AndroidX, one SeekBar. */
    private fun openSlider(
        label: String,
        min: Int,
        max: Int,
        initial: Int,
        unit: String,
        onValue: (Int) -> Unit,
    ) {
        var current = initial.coerceIn(min, max)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@SettingsActivity, 20f), Ui.dp(this@SettingsActivity, 8f),
                Ui.dp(this@SettingsActivity, 20f), 0)
        }
        val readout = Ui.title(this, "$current$unit", 26f).apply {
            setTextColor(Ui.BLUE)
            gravity = Gravity.CENTER
        }
        val bar = SeekBar(this).apply {
            this.max = max - min
            progress = current - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    current = p + min
                    readout.text = "$current$unit"
                    onValue(current)
                }

                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        content.addView(readout)
        content.addView(bar)
        android.app.AlertDialog.Builder(this)
            .setTitle(label)
            .setView(content)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun button(label: String, onClick: () -> Unit): View {
        val b = TextView(this).apply {
            text = label
            setTextColor(Ui.BLUE)
            textSize = 15.5f
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(this@SettingsActivity, 13f), 0, Ui.dp(this@SettingsActivity, 13f))
            background = GradientDrawable().apply {
                cornerRadius = Ui.dp(this@SettingsActivity, 13f).toFloat()
                setColor(Ui.CARD_HI)
            }
            setOnClickListener { onClick() }
        }
        b.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = Ui.dp(this@SettingsActivity, 16f)
            marginEnd = Ui.dp(this@SettingsActivity, 16f)
        }
        return b
    }

    /**
     * End-to-end self test: a real notification from this app goes through SystemUI's pipeline,
     * gets intercepted by the hook, and must land in the island.
     */
    private fun postTestNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        ensureTestChannel()

        val open = PendingIntent.getActivity(
            this, 0, android.content.Intent(this, SettingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            // A stand-in sender photo: what the avatar circle is supposed to show for Telegram
            // and friends, so the path can be checked from the PC instead of waiting for a post.
            .setLargeIcon(android.graphics.Bitmap.createBitmap(
                96, 96, android.graphics.Bitmap.Config.ARGB_8888)
                .also { android.graphics.Canvas(it).drawColor(0xFFFF6D00.toInt()) })
            .setContentTitle("Island test")
            .setContentText("Readable in the pill means the pipeline works.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Open", open)
            .addAction(0, "Keep", open)
            .setStyle(Notification.BigTextStyle().setBigContentTitle("Island test")
                .bigText("Readable in the pill means the pipeline works."))
            .build()

        manager.notify(7900, notification)
    }

    /**
     * `--es test ongoing` — the same CATEGORY_CALL row with a single action, which is how the
     * dialer reports a call that is already up. It must become the green ring, not a card.
     */
    private fun postOngoingCallTestNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CALL_CHANNEL, "Island call test", NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(
            this, 0, android.content.Intent(this, SettingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CALL_CHANNEL)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("Alex Sample")
            .setContentText("01:12")
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .addAction(0, "End", open)
            .build()
        manager.notify(7960, notification)
    }

    /**
     * `--es test detail` — one notification with a paragraph and two actions, which is the shape
     * the detail card exists for.
     */
    private fun postDetailTestNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        ensureTestChannel()
        val open = PendingIntent.getActivity(
            this, 0, android.content.Intent(this, SettingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val paragraph = "Le client li dar el commande 4192 yestenna r-réponse, " +
            "el livraison hiya el hamla l3echia, w la facture khassha tbeddel " +
            "el nom dyel el mahal."
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Commande 4192")
            .setContentText("Le client yestenna…")
            .setStyle(Notification.BigTextStyle().setBigContentTitle("Commande 4192").bigText(paragraph))
            .addAction(0, "Call", open)
            .addAction(0, "Reply", open)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(7970, notification)
    }

    /**
     * `--es test progress` — one notification on a fixed id whose bar advances each call, which is
     * the shape a download has. Two things get checked by repeating it: the green line moves, and
     * the group count stays 1 instead of counting every chunk as a new message.
     */
    private fun postProgressTestNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        ensureTestChannel()
        val turn = ++progressTurn
        // Cycle rather than climb: a test that reaches 100% on the third call cannot show the
        // line moving.
        val percent = 5 + (turn * 17) % 95
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading update")
            .setContentText("store.apk")
            .setProgress(100, percent, false)
            .setOngoing(true)
            .build()
        manager.notify(7950, notification)
    }

    /**
     * `--es test nogauge` — the shape that made the island draw an empty 0% bar under a plain
     * message. Measured on this phone in `dumpsys notification --noredact`: Tailscale, OpenCode
     * and Truecaller all post `android.progress=0` with `android.progressMax=0` and no ongoing
     * download behind it. Expected now: capsule, no line, no `0%`.
     */
    private fun postNoGaugeTestNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        ensureTestChannel()
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Data balance")
            .setContentText("0% of your quota used")
            .setProgress(0, 0, false)
            .build()
        manager.notify(7951, notification)
    }

    /**
     * `--es test call` — the shape the dialer actually posts: CATEGORY_CALL, ongoing, and the two
     * actions the island turns into the answer / hang-up discs. The intents point back here, so
     * this proves the card and the hit targets; only a real call proves the dialer's own intents.
     */
    private fun postCallTestNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CALL_CHANNEL, "Island call test", NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(
            this, 0, android.content.Intent(this, SettingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CALL_CHANNEL)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("Alex Sample")
            .setContentText("mobile · incoming")
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setLargeIcon(swatch(0xFF2FB47C.toInt()))
            .addAction(0, "Answer", open)
            .addAction(0, "End", open)
            .build()
        manager.notify(7960, notification)
    }

    private fun ensureTestChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Island test", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    /**
     * `--es test chat` — the shape Telegram actually posts: MessagingStyle, a person with their
     * own picture, HIGH importance, and a group summary right behind the child. Telegram was the
     * app whose messages never took the slot, so the self test has to speak its dialect instead
     * of ours.
     *
     * Each call adds one more message from the next sender, so three calls inside the group window
     * are the burst the list island unfolds — which is the only way to check it from a PC.
     */
    private fun postChatTestNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHAT_CHANNEL, "Island chat test", NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(
            this, 0, android.content.Intent(this, SettingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val turn = ++chatTurn
        val (name, colour, text) = SENDERS[turn % SENDERS.size]
        val me = android.app.Person.Builder().setName("Me").build()
        val sender = android.app.Person.Builder()
            .setName(name)
            .setIcon(android.graphics.drawable.Icon.createWithBitmap(swatch(colour)))
            .build()
        val style = Notification.MessagingStyle(me)
            .addMessage(text, System.currentTimeMillis(), sender)
            .setConversationTitle(name)

        // Child: what a chat app posts per message, grouped under one conversation id.
        // No large icon on purpose — Telegram does not set one either, so the only way this can
        // show a picture is the sender's Person icon inside the messaging style.
        val child = Notification.Builder(this, CHAT_CHANNEL)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setStyle(style)
            .setGroup("tg_test_conv")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()

        // Summary: the same row every time, so a burst never stacks summaries in the shade.
        val summary = Notification.Builder(this, CHAT_CHANNEL)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle(name)
            .setContentText("$turn messages")
            .setStyle(Notification.InboxStyle().addLine("$name: $text"))
            .setGroup("tg_test_conv")
            .setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
            .setGroupSummary(true)
            .build()

        manager.notify(7910 + (turn % 50), child)
        manager.notify(7999, summary)
    }

    /** A flat-coloured square standing in for a chat photo. */
    private fun swatch(argb: Int): android.graphics.Bitmap =
        android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
            .also { android.graphics.Canvas(it).drawColor(argb) }

    /** A stand-in player row: transport category, three transport actions, no real session. */
    private fun postMediaTestNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val open = PendingIntent.getActivity(
            this, 0, android.content.Intent(this, SettingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Cheb Khaled - Achkwelek")
            .setContentText("1998 · Raï")
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOngoing(true)
            .addAction(0, "Previous", open)
            .addAction(0, "Pause", open)
            .addAction(0, "Next", open)
            .build()
        manager.notify(7901, notification)
    }

    companion object {
        private const val CHANNEL = "island_test"
        private const val CHAT_CHANNEL = "island_chat"
        private const val CALL_CHANNEL = "island_call"

        /** Name, disc colour, message — four senders is more than the card ever shows. */
        private val SENDERS = arrayOf(
            Triple("Alex", 0xFF1FA97C.toInt(), "check the marketplace price later"),
            Triple("Sam", 0xFF5B8DEF.toInt(), "is the 25W charger in stock?"),
            Triple("Jordan", 0xFFE0733A.toInt(), "send me the invoice when ready"),
            Triple("Riley", 0xFF9B59D0.toInt(), "the 14:30 customer has arrived"),
        )

        /** Static: `am start` may hand the intent to a fresh instance every time. */
        private var chatTurn = 0
        private var progressTurn = 0
    }
}
