package io.github.lamin_sudo.halo

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper

/**
 * The SystemUI half of the automation door. No hook: [Config]'s observer already fires on every
 * Settings.Global write, so a change of the sequence number is the event, and the payload beside
 * it is the request.
 *
 * The first read after a SystemUI restart is deliberately dropped — replaying whatever Tasker sent
 * last, hours later, is not what anyone asked for.
 */
class CustomSource(private val context: Context, private val window: IslandWindow) {

    private val main = Handler(Looper.getMainLooper())
    private var lastSeq = 0

    init {
        // Start from the stored number, not from zero: that way a request made before SystemUI
        // restarted is never replayed, and one made a second after it starts is never dropped.
        lastSeq = ConfigStore.getInt(context.contentResolver, CustomReceiver.KEY_CUSTOM_SEQ, 0)
        Config.addChangeListener { main.post { check() } }
    }

    private fun check() {
        val res = context.contentResolver
        val seq = ConfigStore.getInt(res, CustomReceiver.KEY_CUSTOM_SEQ, 0)
        if (seq <= lastSeq) return
        lastSeq = seq
        val payload = ConfigStore.getString(res, CustomReceiver.KEY_CUSTOM)
        if (payload.isNullOrBlank()) {
            Logx.d("custom seq=$seq but no payload")
            return
        }
        val q = runCatching { Uri.parse("?" + payload) }.getOrNull()
        if (q == null) {
            Logx.w("custom seq=$seq payload did not parse")
            return
        }
        val title = q.getQueryParameter("title")
        if (title.isNullOrBlank()) return
        val slot = "custom|" + (q.getQueryParameter("key") ?: "custom")
        val ttlSeconds = q.getQueryParameter("ttl")?.toIntOrNull()?.coerceIn(1, 120) ?: 6
        val color = runCatching {
            q.getQueryParameter("color")?.removePrefix("0x")?.toLong(16)?.toInt()
        }.getOrNull() ?: Config.current.hashCode()

        window.submit(
            IslandEvent(
                key = slot,
                source = Source.NOTIFICATION,
                title = title,
                body = null,
                trailing = q.getQueryParameter("text")?.takeIf { it.isNotBlank() },
                icon = null,
                avatar = null,
                avatarText = null,
                actions = emptyList(),
                progress = q.getQueryParameter("progress")?.toIntOrNull() ?: -1,
                accentArgb = color,
                ttlMs = ttlSeconds * 1_000L,
                openIntent = null,
                showBell = false,
            )
        )
        Logx.d("custom seq=$seq → $slot ttl=${ttlSeconds}s")
    }
}
