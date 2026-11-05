package io.github.lamin_sudo.halo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * The automation door: Tasker, Termux, an SSH session, or a friend's app can put a capsule on the
 * island without the module having to be installed as anything but itself.
 *
 *     adb shell am broadcast -p io.github.lamin_sudo.halo -a io.github.lamin_sudo.halo.CUSTOM \
 *       --es title "Order shipped" --es text "#4192 to Rafiq" \
 *       --ei progress 60 --ei ttl 8 --es color 0xFF30D158
 *
 * The `-p` is not decoration: since Android 8 an implicit broadcast to a manifest receiver is
 * filtered out unless the target package is named, and a custom action is never on the exemption
 * list. In Tasker that is a "Send Broadcast" task with the package field filled in.
 *
 * Why it works this way: the receiver runs in *this* app, which is the half that holds
 * WRITE_SECURE_SETTINGS, and it writes the payload into Settings.Global — the one store the hooked
 * SystemUI can read (see [ConfigStore]). The sequence number is what makes a repeat of the exact
 * same message show up again.
 *
 * It is exported with no permission on purpose: a third-party automation must be able to reach it,
 * and the worst it can do is put a pill on the screen. The kill switch
 * (`a79_island_disabled`) and the master toggle still win.
 */
class CustomReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION) return
        val title = intent.getStringExtra("title")?.takeIf { it.isNotBlank() }
        if (title == null) {
            Logx.w("custom: no title extra, ignored")
            return
        }
        val res = context.contentResolver
        val payload = Uri.Builder()
            .appendQueryParameter("key", intent.getStringExtra("key") ?: "custom")
            .appendQueryParameter("title", title)
            .appendQueryParameter("text", intent.getStringExtra("text").orEmpty())
            .appendQueryParameter(
                "color",
                intent.getStringExtra("color") ?: Integer.toHexString(DEFAULT_COLOR),
            )
            .appendQueryParameter("progress", intent.getIntExtra("progress", -1).toString())
            .appendQueryParameter("ttl", intent.getIntExtra("ttl", DEFAULT_TTL_SECONDS).toString())
            .build()
            .encodedQuery
        val wrote = payload != null && ConfigStore.putString(res, KEY_CUSTOM, payload)
        val seq = ConfigStore.getInt(res, KEY_CUSTOM_SEQ, 0) + 1
        ConfigStore.putInt(res, KEY_CUSTOM_SEQ, seq)
        Logx.d("custom posted seq=$seq title=$title wrote=$wrote")
        if (!wrote) {
            Logx.w(
                "custom: cannot write settings — run: adb shell su -c 'pm grant io.github.lamin_sudo.halo " +
                    "android.permission.WRITE_SECURE_SETTINGS'"
            )
        }
    }

    companion object {
        const val ACTION = "io.github.lamin_sudo.halo.CUSTOM"

        /** Where the payload lives; [ConfigStore] adds the namespace. */
        const val KEY_CUSTOM = "custom"
        const val KEY_CUSTOM_SEQ = "custom_seq"

        private const val DEFAULT_TTL_SECONDS = 6
        private val DEFAULT_COLOR = 0xFF6EA8FE.toInt()
    }
}
