package dz.island.a79

import android.app.Application
import android.content.Context
import android.provider.Settings
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * Entry point for LSPosed (libxposed API 102).
 *
 * Every step is wrapped so that a failure leaves SystemUI exactly as it found it: the worst this
 * module can do is show no island.
 *
 * Emergency stop, no LSPosed UI and no reboot needed:
 *     adb shell settings put global a79_island_disabled 1
 *     adb shell settings put global a79_island_disabled 0
 */
class IslandModule : XposedModule() {

    private var booted = false

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName != TARGET) return

        // The Application instance is the context we need: it carries SystemUI's identity, and
        // with it INTERNAL_SYSTEM_WINDOW (trusted overlay) and PERSISTENT (unkillable).
        val existing = currentApplication()
        if (existing != null) {
            boot(existing, param)
        } else {
            hookApplicationOnCreate(param)
        }
    }

    private fun currentApplication(): Application? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        activityThread.getDeclaredMethod("currentApplication").invoke(null) as? Application
    }.getOrNull()

    private fun hookApplicationOnCreate(param: PackageLoadedParam) {
        // LSPosed dispatches packageLoaded after LoadedApk.makeApplication(), so in practice the
        // Application already exists and boot() runs immediately. These two hooks are the safety
        // net: attach() is final and always called, onCreate() may be overridden without calling
        // super by the OEM, so neither alone is enough on its own.
        val application = runCatching {
            param.defaultClassLoader.loadClass(Application::class.java.name)
        }.getOrNull() ?: return

        installCapture(application, "attach", arrayOf(android.content.Context::class.java), param)
        installCapture(application, "onCreate", emptyArray(), param)
    }

    private fun installCapture(
        clazz: Class<*>,
        methodName: String,
        params: Array<Class<*>>,
        param: PackageLoadedParam,
    ) {
        runCatching {
            // attach(Context) is public in AOSP but not in every OEM stub; try both lookups.
            val method = runCatching { clazz.getMethod(methodName, *params) }
                .getOrElse { clazz.getDeclaredMethod(methodName, *params) }
            method.isAccessible = true
            hook(method).intercept { chain ->
                val result = chain.proceed()
                val app = chain.thisObject as? Application
                if (app != null) boot(app, param)
                result
            }
            Logx.d("context capture armed on ${clazz.name}#$methodName")
        }.onFailure { Logx.d("capture unavailable on $methodName: ${it.javaClass.simpleName}") }
    }

    @Synchronized
    private fun boot(context: Context, param: PackageLoadedParam) {
        if (booted) return
        booted = true

        runCatching {
            // Needs the host's ContentResolver, so it can only happen once we have a context.
            Config.init(context)
            // Read the game-session flag before the first event can ask for it.
            GameSpace.init(context)
            if (isDisabled(context)) {
                Logx.w("kill switch set (a79_island_disabled=1): standing down")
                return
            }

            val cfg = Config.current
            val geometry = Geometry.read(context, cfg.offsetX, cfg.offsetY, cfg.widthScale)
            val fade = StatusBarFade(context)
            val suppressor = HeadsUpSuppressor()
            val window = IslandWindow(context, fade, geometry) { event ->
                // The banner is identified per notification, the slot per app.
                event.sourceKeys.forEach(suppressor::mark)
            }

            val media = MediaSource(context, window)
            val notifications = NotificationSource(context, window)
            notifications.mediaSessionLive = { media.sessionWins }
            window.onMediaPlayPause = { media.togglePlayback() }
            window.onMediaSeek = { delta -> media.seekBy(delta) }
            window.onMediaPrev = { media.previous() }
            window.onMediaNext = { media.next() }
            window.onRowDismiss = { notifications.dismissRow(it) }

            // A match starting is not a notification, so nothing else would clear the island: the
            // capsule that was mid-TTL when Game Space took the screen has to be taken down.
            GameSpace.addChangeListener { flag ->
                if (flag >= 2 && Config.current.gamesHide) {
                    window.standDown("game session (flag=$flag)")
                }
            }

            fade.install(this, param)
            // WindowManager's own "the top app hides the bar", for a fullscreen video with no game
            // session behind it. Off by default until it is measured on a real player.
            fade.addBarHiddenListener {
                if (Config.current.immersiveHide) window.standDown("app took the status bar")
            }
            suppressor.install(this, param)
            notifications.install(this, param)
            media.install(this, param)
            FaceUnlockSource(context, window).install(this, param)
            PrivacySource(context, window).install(this, param)
            // The automation door needs no hook: it rides on the settings observer.
            CustomSource(context, window)

            val charging = ChargingSource(context, window)
            if (cfg.enabled) charging.start()

            Config.addChangeListener {
                val next = Config.current
                if (!next.enabled) {
                    window.clear()
                    charging.stop()
                } else {
                    charging.start()
                    window.updateGeometry(
                        Geometry.read(context, next.offsetX, next.offsetY, next.widthScale)
                    )
                }
            }

            Logx.d("booted: $geometry | cfg=$cfg")
        }.onFailure {
            // Never let our own failure reach SystemUI's startup path.
            Logx.e("boot aborted", it)
        }
    }

    private fun isDisabled(context: Context): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, KILL_SWITCH, 0) == 1
    }.getOrDefault(false)

    companion object {
        private const val TARGET = "com.android.systemui"
        private val KILL_SWITCH = ConfigStore.PREFIX + ConfigStore.KEY_DISABLED
    }
}
