package dz.island.a79

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Field

/**
 * Privacy indicators: who is using the camera or the microphone right now, iPhone-style.
 *
 * ColorOS 13.1 ships AOSP's own `com.android.systemui.privacy.PrivacyItemController` (confirmed by
 * dumping the device's SystemUI dex: `updatePrivacyList()V`, a `privacyList` field, and a
 * `PrivacyType` enum with TYPE_CAMERA / TYPE_MICROPHONE / TYPE_LOCATION).
 *
 * The obvious hook is `addCallback(Callback)`, but that controller keeps its callbacks in
 * WeakReferences — wrapping the caller's object in a proxy would put *our* proxy in their list, and
 * a collected proxy would silently break the OEM's own privacy chip. So this hooks the producer
 * instead: `updatePrivacyList()` runs, then we read the same field the callbacks are about to be
 * notified with. Read-only, no object substituted into their graph, nothing of ours they hold.
 */
class PrivacySource(private val context: Context, private val window: IslandWindow) {

    private val main = Handler(Looper.getMainLooper())
    private val labels = HashMap<String, String>()

    /**
     * The privacy chip cannot report the screen recorder — its `PrivacyType` has no projection on
     * this build — so the op itself is watched and only that one is taken from here.
     */
    private val appOps = AppOpsWatcher(context) { kind, pkg ->
        if (kind == "screen recording") submit(kind, pkg)
    }

    /** "camera|pkg" → last time it took the island, so a live session does not repeat itself. */
    private val lastShown = HashMap<String, Long>()

    fun install(module: XposedModule, param: PackageLoadedParam) {
        val loader = param.defaultClassLoader
        val controller = runCatching {
            loader.loadClass("com.android.systemui.privacy.PrivacyItemController")
        }.getOrNull()
        if (controller == null) {
            Logx.w("PrivacyItemController not found: no privacy indicators")
            return
        }
        val listField: Field? = runCatching {
            controller.getDeclaredField("privacyList").apply { isAccessible = true }
        }.getOrNull()
        if (listField == null) {
            Logx.w("privacyList field not found: no privacy indicators")
            return
        }
        val method = controller.declaredMethods.firstOrNull {
            it.name == "updatePrivacyList" && it.parameterTypes.isEmpty()
        }
        if (method == null) {
            Logx.w("updatePrivacyList not found: no privacy indicators")
            return
        }
        appOps.start()
        installRecordingHook(module, loader)
        runCatching {
            module.hook(method).intercept { chain ->
                val result = chain.proceed()
                val items = runCatching { listField.get(chain.thisObject) as? List<*> }.getOrNull()
                if (items != null) {
                    val snapshot = items.toList()
                    main.post {
                        runCatching { report(snapshot) }
                            .onFailure { Logx.e("privacy report failed", it) }
                    }
                }
                // void method: null on every path, never Unit.
                return@intercept result
            }
            Logx.d("privacy installed on ${controller.name}#updatePrivacyList")
        }.onFailure { Logx.e("privacy hook failed", it) }
    }

    /**
     * ColorOS records through its own app (`com.oplus.screenrecorder`), which never opens a
     * MediaProjection, so `OP_PROJECT_MEDIA` stays `ignore` and the AppOps route is silent. The
     * AOSP controller inside SystemUI is the one that knows: `updateState(boolean)` sets
     * `mIsRecording`, and the QS tile and the OEM recorder both end up here.
     */
    private fun installRecordingHook(module: XposedModule, loader: ClassLoader) {
        val clazz = runCatching {
            loader.loadClass("com.android.systemui.screenrecord.RecordingController")
        }.getOrNull()
        if (clazz == null) {
            Logx.d("RecordingController not found: no screen-recorder indicator")
            return
        }
        val update = clazz.declaredMethods.firstOrNull {
            it.name == "updateState" && it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Boolean::class.javaPrimitiveType
        }
        if (update == null) {
            Logx.d("updateState(Z) not found: no screen-recorder indicator")
            return
        }
        val isRecording = runCatching { clazz.getDeclaredMethod("isRecording") }
            .getOrNull()?.apply { isAccessible = true }
        runCatching {
            module.hook(update).intercept { chain ->
                val result = chain.proceed()
                val on = runCatching { isRecording?.invoke(chain.thisObject) as? Boolean }
                    .getOrNull() ?: chain.args.getOrNull(0) as? Boolean ?: false
                main.post {
                    if (on) submit("screen recording", "com.oplus.screenrecorder")
                    Logx.d("recording state -> $on")
                }
                // void method: hand back what proceed returned, never Unit.
                return@intercept result
            }
            Logx.d("recording installed on ${clazz.name}#updateState")
        }.onFailure { Logx.e("recording hook failed", it) }
    }

    private fun report(items: List<*>) {
        if (!Config.current.privacy) return
        if (items.isEmpty()) return
        for (item in items) {
            val type = call(item, "getPrivacyType")?.toString() ?: continue
            val pkg = call(call(item, "getApplication"), "getPackageName") as? String ?: continue
            val paused = call(item, "getPaused") as? Boolean ?: false
            if (paused) continue
            val kind = when {
                type.contains("CAMERA") && type.contains("MIC") -> "camera + mic"
                type.contains("CAMERA") -> "camera"
                type.contains("MIC") -> "microphone"
                type.contains("LOCATION") -> "location"
                else -> continue
            }
            if (!submit(kind, pkg)) continue
            Logx.d("privacy: $kind used by $pkg")
        }
    }

    /** One capsule per sensor per app, deduped so a long session does not repeat itself. */
    private fun submit(kind: String, pkg: String): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        val slot = "privacy|$kind|$pkg"
        val last = lastShown[slot]
        if (last != null && now - last < REPEAT_MS) return false
        lastShown[slot] = now
        val typeName = when (kind) {
            "camera" -> "TYPE_CAMERA"
            "microphone" -> "TYPE_MICROPHONE"
            else -> ""
        }
        window.submit(
            IslandEvent(
                key = slot,
                source = Source.PRIVACY,
                // The AOSP controller does not say who started it, and "System UI" would be a
                // useless label; the recorder names itself.
                title = if (kind == "screen recording") "Screen recording" else labelOf(pkg),
                body = null,
                trailing = kind,
                icon = iconFor(typeName),
                avatar = null,
                avatarText = null,
                actions = emptyList(),
                progress = -1,
                accentArgb = accentFor(kind),
                ttlMs = PRIVACY_TTL_MS,
                openIntent = launchIntent(pkg),
                showBell = false,
            )
        )
        return true
    }

    /** The system's own glyph for the sensor, so the pill matches the chip in the bar. */
    private fun iconFor(typeName: String): Drawable? {
        if (typeName.isBlank()) return null
        val loader = context.classLoader
        val enumClass = runCatching {
            loader.loadClass("com.android.systemui.privacy.PrivacyType")
        }.getOrNull() ?: return null
        val value = enumClass.enumConstants?.firstOrNull { it.toString().contains(typeName) }
            ?: return null
        return runCatching {
            enumClass.getMethod("getIcon", Context::class.java).invoke(value, context) as? Drawable
        }.getOrNull()
    }

    private fun accentFor(kind: String): Int = when (kind) {
        "camera" -> CAMERA_GREEN
        "microphone" -> MIC_ORANGE
        "location" -> LOCATION_BLUE
        else -> MIC_ORANGE
    }

    private fun launchIntent(pkg: String): android.app.PendingIntent? = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return null
        android.app.PendingIntent.getActivity(
            context, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE
        )
    }.getOrNull()

    private fun labelOf(pkg: String): String = labels.getOrPut(pkg) {
        runCatching {
            val info: ApplicationInfo = context.packageManager.getApplicationInfo(pkg, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(pkg)
    }

    private fun call(target: Any?, name: String): Any? {
        if (target == null) return null
        return runCatching {
            target.javaClass.getMethod(name).invoke(target)
        }.getOrNull()
    }

    companion object {
        /** A recording session is continuous; the island says it once, not every second. */
        private const val REPEAT_MS = 20_000L
        private const val PRIVACY_TTL_MS = 4_000L
        private val CAMERA_GREEN = 0xFF30D158.toInt()
        private val MIC_ORANGE = 0xFFFF9F0A.toInt()
        private val LOCATION_BLUE = 0xFF6EA8FE.toInt()
    }
}
