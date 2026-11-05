package io.github.lamin_sudo.halo

import android.content.ContentResolver
import android.provider.Settings

/**
 * Settings.Global is the only store both halves of this module can actually reach.
 *
 * What was tried first: libxposed remote preferences (`module.getRemotePreferences`). On this
 * stack — LSPosed 2.2.1, API 102, ColorOS 13.1 — the object comes back with an empty key set
 * (`remote prefs keys=[]` in logcat) while the settings screen on the same device shows the
 * values it wrote, so every switch the user flips would be ignored by the hook. Reading the
 * module's own shared_prefs file from SystemUI is not an option either: it is `app_data_file`
 * with the app's MLS categories and SystemUI runs as `system_app`.
 *
 * Global settings fix both directions: readable by anyone, and writable by the settings screen
 * once it holds WRITE_SECURE_SETTINGS, which is an app-op permission and therefore grantable
 * from a PC — one command, after every reinstall:
 *
 *     adb shell su -c 'pm grant io.github.lamin_sudo.halo android.permission.WRITE_SECURE_SETTINGS'
 *
 * The kill switch (`a79_island_disabled`) already lives here, so this keeps one mechanism for
 * everything instead of two.
 */
object ConfigStore {

    /** Namespace so the module cannot collide with ColorOS' own global keys. */
    const val PREFIX = "a79_island_"

    /** The emergency stop, read before anything else is wired up. */
    const val KEY_DISABLED = "disabled"

    fun getInt(res: ContentResolver?, key: String, def: Int): Int =
        runCatching { res?.let { Settings.Global.getInt(it, PREFIX + key, def) } }.getOrNull() ?: def

    fun getBoolean(res: ContentResolver?, key: String, def: Boolean): Boolean =
        getInt(res, key, if (def) 1 else 0) != 0

    /** False when the app lacks WRITE_SECURE_SETTINGS — the caller tells the user, loudly. */
    fun putInt(res: ContentResolver, key: String, value: Int): Boolean =
        runCatching { Settings.Global.putInt(res, PREFIX + key, value) }.getOrDefault(false)

    fun putBoolean(res: ContentResolver, key: String, value: Boolean): Boolean =
        putInt(res, key, if (value) 1 else 0)

    /** For the automation payload, which is a query string rather than a number. */
    fun getString(res: ContentResolver?, key: String): String? =
        runCatching { res?.let { Settings.Global.getString(it, PREFIX + key) } }.getOrNull()

    fun putString(res: ContentResolver, key: String, value: String): Boolean =
        runCatching { Settings.Global.putString(res, PREFIX + key, value) }.getOrDefault(false)
}
