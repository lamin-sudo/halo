package dz.island.a79

import android.service.notification.StatusBarNotification
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * Stops the old heads-up banner from doubling up with the island.
 *
 * Deliberately narrow: only a notification the island actually took is silenced, so nothing can
 * be lost because of this module. If the island is off, filtered, or the app is switched off, the
 * banner still appears.
 *
 * Two things about ColorOS 13.1 were measured before this was written, and both cost a round of
 * "but the banner is still there":
 *
 * 1. The gate. `HeadsUpController.showAlertingView(NotificationEntry)` exists on this ROM with
 *    ONE parameter (stock AOSP 13 has three) — and it is never called: an instrumented build saw
 *    zero invocations across real Telegram and X banners. ColorOS decides through
 *    `com.oplusos.systemui.notification.helper.HeadsUpHelper.shouldPeekByOS(SBN, boolean, boolean)`
 *    and draws through `com.oplusos.systemui.notification.util.SimpleHeadsUpController
 *    .show(SBN, boolean[, int])`. So every one of those is hooked, plus the AOSP entry points
 *    that would carry the stock path if the ROM ever uses it.
 * 2. The key. The island's slot key is `notif|<package>` (one slot per app, so a burst groups).
 *    A banner is identified by the *notification's* key. Marking the slot key meant nothing ever
 *    matched, even on a hook that did fire. [mark] therefore takes the real
 *    `StatusBarNotification.key` of every notification the island consumed.
 */
class HeadsUpSuppressor {

    /** Notification keys the island has taken, oldest dropped first. Bounded so this never grows. */
    private val taken = LinkedHashMap<String, Long>(MAX_TAKEN, 0.75f, true)

    fun mark(key: String) {
        synchronized(taken) {
            taken[key] = System.currentTimeMillis()
            if (taken.size > MAX_TAKEN) {
                val oldest = taken.keys.firstOrNull()
                if (oldest != null) taken.remove(oldest)
            }
        }
    }

    private fun isTaken(key: String?): Boolean =
        key != null && synchronized(taken) { taken.containsKey(key) }

    fun install(module: XposedModule, param: PackageLoadedParam) {
        val loader = param.defaultClassLoader
        var hooked = 0
        for (name in TARGETS) {
            val clazz = runCatching { loader.loadClass(name) }.getOrNull()
            if (clazz == null) {
                Logx.d("$name not on this ROM")
                continue
            }
            for (method in clazz.declaredMethods) {
                val behaviour = behaviourFor(method) ?: continue
                val ok = runCatching {
                    method.isAccessible = true
                    module.hook(method).intercept { chain ->
                        // Nothing here may escape: this runs on SystemUI's main thread inside the
                        // notification pipeline, and an escaping exception kills the status bar.
                        // A swallowed one only means the old banner comes back.
                        // Both paths say return@intercept: a bare try/catch as the last statement
                        // of a lambda is the same discarded-value trap that crashed SystemUI once.
                        try {
                            return@intercept decide(behaviour, chain)
                        } catch (t: Throwable) {
                            Logx.e("banner decision failed, showing the normal banner", t)
                            return@intercept chain.proceed()
                        }
                    }
                }
                if (ok.isSuccess) {
                    hooked++
                    Logx.d("banner hook: ${clazz.simpleName}.${method.name}/${method.parameterTypes.size}")
                } else {
                    Logx.e("banner hook failed on ${clazz.simpleName}.${method.name}", ok.exceptionOrNull())
                }
            }
        }
        if (hooked == 0) Logx.w("no heads-up hook point available")
    }

    /**
     * The one decision the hook makes, kept out of the lambda so the return type is checked by
     * the compiler: a non-exhaustive `when` as the last statement of a lambda is a STATEMENT in
     * Kotlin, so the hook used to fall off its end and hand kotlin.Unit to a method declared
     * `boolean` — which crashed SystemUI's main thread inside
     * OplusNotificationInterruptStateProviderImpl.shouldHeadsUpWhenAwake.
     */
    private fun decide(behaviour: Int, chain: XposedInterface.Chain): Any? {
        if (!Config.current.hideHeadsUp) return chain.proceed()
        val key = keyOf(chain.args.getOrNull(0))
        if (!isTaken(key)) return chain.proceed()
        return if (behaviour == DENY) {
            Logx.d("banner denied for $key")
            false
        } else {
            Logx.d("banner skipped for $key")
            null
        }
    }

    /**
     * Which action a candidate method needs, or null when it is not a banner call.
     * Matched on the first parameter type rather than the whole signature, because the OEM
     * changes arity between builds and a missed signature is how this silently stopped working.
     */
    private fun behaviourFor(method: java.lang.reflect.Method): Int? {
        val first = method.parameterTypes.firstOrNull() ?: return null
        return when {
            // The OEM's own yes/no gate on showing a banner: answer it, never touch the drawing.
            method.name == "shouldPeekByOS" && first == StatusBarNotification::class.java -> DENY
            // The call that puts the banner on screen: skipping it leaves nothing half-shown.
            method.name.startsWith("show") && first == StatusBarNotification::class.java -> SKIP
            // Stock path: AlertingNotificationManager.showNotification(String key, boolean).
            method.name == "showNotification" && first == String::class.java -> SKIP
            // Stock path: HeadsUpController.showAlertingView(NotificationEntry, ...).
            method.name == "showAlertingView" -> SKIP
            else -> null
        }
    }

    /** Key from an SBN, a raw key string, or a NotificationEntry if the ROM still uses one. */
    private fun keyOf(arg: Any?): String? = when (arg) {
        null -> null
        is StatusBarNotification -> arg.key
        is String -> arg
        else -> keyOfEntry(arg)
    }

    /** NotificationEntry.key in AOSP 13; the raw field and mSbn.key as fallbacks if it moved. */
    private fun keyOfEntry(entry: Any): String? {
        runCatching {
            (entry.javaClass.getMethod("getKey").invoke(entry) as? String)?.let { return it }
        }
        var c: Class<*>? = entry.javaClass
        while (c != null) {
            for (fieldName in listOf("key", "mKey", "mSbn")) {
                val field = runCatching { c.getDeclaredField(fieldName) }.getOrNull() ?: continue
                runCatching {
                    field.isAccessible = true
                    when (val value = field.get(entry)) {
                        is String -> return value
                        is StatusBarNotification -> return value.key
                    }
                }
            }
            c = c.superclass
        }
        return null
    }

    companion object {
        private const val SKIP = 0
        private const val DENY = 1

        private val TARGETS = listOf(
            "com.oplusos.systemui.notification.helper.HeadsUpHelper",
            "com.oplusos.systemui.notification.util.SimpleHeadsUpController",
            "com.android.systemui.statusbar.AlertingNotificationManager",
            "com.android.systemui.statusbar.notification.interruption.HeadsUpController",
            "com.oplusos.systemui.statusbar.notification.interruption.OplusHeadsUpController",
        )

        // A day of busy notification traffic is a few hundred keys; 512 keeps this a rounding
        // error while making it practically impossible to evict a key still on screen.
        private const val MAX_TAKEN = 512
    }
}
