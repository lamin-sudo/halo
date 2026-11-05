package io.github.lamin_sudo.halo

import android.content.Context
import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.ref.WeakReference

/**
 * Dims the status-bar content while the island is up.
 *
 * The active pill spans x=189..891 on this panel while the icon cluster starts at x=642, so the
 * overlap is not a style question. The reference device does the same thing.
 *
 * The first child level of the status-bar fragment root is faded, which covers the clock and the
 * icons without naming a single OEM resource id — those names change between ColorOS builds.
 */
class StatusBarFade(private val context: Context) {

    private var root: WeakReference<View>? = null
    private var pendingAlpha = 1f
    private var treeLogged = false

    fun install(module: XposedModule, param: PackageLoadedParam) {
        installTopAppWatcher(module, param.defaultClassLoader)
        installFade(module, param)
    }

    /**
     * The one signal that actually knows the status bar is gone: WindowManager itself.
     *
     * `DisplayPolicy` computes "the top app on this display hides the status bar" and pushes it
     * over `IStatusBar` into `com.android.systemui.statusbar.CommandQueue#setTopAppHidesStatusBar`
     * `(Z)V`, which then dispatches `MSG_SET_TOP_APP_HIDES_STATUS_BAR` to the callbacks. Found by
     * reading the shipped dex (`CommandQueue` constants, five owners of the method name), not by
     * guessing — and it is a read-only tap on a method SystemUI already calls, so nothing here
     * touches `system_server`.
     *
     * This is what the four failed routes could not see: the bar's own view keeps
     * `winVis=0 h=107 y=0 alpha=1` while WM refuses to place it, and AOSP's
     * `StatusBarStateControllerImpl#setState` is never called on this ROM at all.
     */
    private fun installTopAppWatcher(module: XposedModule, loader: ClassLoader?) {
        val clazz = runCatching {
            loader?.loadClass("com.android.systemui.statusbar.CommandQueue")
                ?: Class.forName("com.android.systemui.statusbar.CommandQueue")
        }.getOrNull()
        if (clazz == null) {
            Logx.w("no CommandQueue; fullscreen hide stays blind")
            return
        }
        val method = clazz.declaredMethods.firstOrNull {
            it.name == "setTopAppHidesStatusBar" && it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Boolean::class.javaPrimitiveType
        }
        if (method == null) {
            Logx.w("CommandQueue#setTopAppHidesStatusBar(boolean) missing; hide stays blind")
            return
        }
        runCatching {
            module.hook(method).intercept { chain ->
                val result = chain.proceed()
                val hides = chain.args.firstOrNull() as? Boolean ?: false
                if (hides != topAppHidesBar) {
                    topAppHidesBar = hides
                    Logx.d("window manager: top app hides status bar = $hides")
                    if (hides) synchronized(barListeners) { barListeners.toList() }
                        .forEach { it() }
                }
                result
            }
            Logx.d("fullscreen watcher installed on ${clazz.name}#setTopAppHidesStatusBar")
        }.onFailure { Logx.e("fullscreen watcher failed", it) }
    }

    @Volatile
    private var topAppHidesBar = false

    private val barListeners = mutableListOf<() -> Unit>()

    /** Called on the rising edge — the island that is already up has to come down. */
    fun addBarHiddenListener(onHidden: () -> Unit) {
        synchronized(barListeners) { barListeners.add(onHidden) }
    }

    private fun installFade(module: XposedModule, param: PackageLoadedParam) {
        val loader = param.defaultClassLoader
        val candidates = listOf(
            "com.android.systemui.statusbar.phone.fragment.CollapsedStatusBarFragment",
            "com.android.systemui.statusbar.phone.CollapsedStatusBarFragment",
        )
        for (name in candidates) {
            val clazz = runCatching { loader.loadClass(name) }.getOrNull() ?: continue
            val method = clazz.declaredMethods.firstOrNull {
                it.name == "onViewCreated" && it.parameterTypes.size == 2
            } ?: continue
            runCatching {
                module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    (chain.args.getOrNull(0) as? View)?.let { onStatusBarRoot(it) }
                    result
                }
                Logx.d("fade installed on ${clazz.name}#onViewCreated")
            }.onFailure { Logx.e("fade hook failed for ${clazz.name}", it) }
            return
        }
        Logx.w("no CollapsedStatusBarFragment found; island will overlap the status icons")
    }

    private fun onStatusBarRoot(view: View) {
        root = WeakReference(view)
        // Re-apply whatever the island is doing right now (config change, dark mode, rotation).
        setAlpha(pendingAlpha)
        if (Config.debugEnabled()) logTreeOnce()
    }

    /**
     * Is the status bar off screen right now? Fullscreen games and video players take it away,
     * and the island should not be the thing hanging over a game.
     *
     * WindowManager's push is the first answer ([installTopAppWatcher]). The view probe below is
     * kept as a fallback for whatever WM does not consider a bar-hiding app, and because it is
     * free: it is asked at the moment an event wants the island, never tracked in between.
     *
     * History, so nobody re-trims this: the first version hooked
     * `StatusBarStateControllerImpl.setState` and watched the root view for layout passes; neither
     * ever fired on this ROM — ColorOS hides the bar by moving the window, which gives the view no
     * layout pass, and it does not drive AOSP's state controller at all. Measured during forced
     * immersive, the probe reported `hidden=false` with `winVis=0 alpha=true h=107 y=0` while
     * `dumpsys window` said `ITYPE_STATUS_BAR: invisible`.
     */
    fun isBarHidden(): Boolean {
        if (topAppHidesBar) return true
        val r = root?.get() ?: return false
        val top = r.rootView
        // getWindowVisibility is the window's own state, not the view's — which is the only
        // difference that matters here, because the bar is hidden by hiding its window.
        val winVis = r.windowVisibility
        val vis = winVis == View.VISIBLE && r.visibility == View.VISIBLE && top.visibility == View.VISIBLE
        val alpha = r.alpha > 0.05f && top.alpha > 0.05f
        val h = r.height
        runCatching { r.getLocationOnScreen(screenPos) }.onFailure { return false }
        val hidden = !vis || !alpha || h == 0 || screenPos[1] + h <= 0
        // Only the interesting answer is logged. Measured on this ROM during forced immersive, the
        // check reports hidden=false with vis=true winVis=0 alpha=true h=107 y=0 topY=0.0 while
        // `dumpsys window` says ITYPE_STATUS_BAR: invisible — see HANDOFF, "Fullscreen".
        if (hidden) {
            Logx.d(
                "bar hidden: winVis=$winVis alpha=$alpha h=$h y=${screenPos[1]} " +
                    "topY=${top.translationY}"
            )
        }
        return hidden
    }

    private val screenPos = IntArray(2)

    /** Called from the animation on the main thread. */
    fun setAlpha(alpha: Float) {
        pendingAlpha = alpha
        val r = root?.get() ?: return
        if (r is ViewGroup && r.childCount > 0) {
            for (i in 0 until r.childCount) r.getChildAt(i).alpha = alpha
            return
        }
        r.alpha = alpha
    }

    /** Diagnostic: log the id names that are actually present on this build. */
    fun logTreeOnce() {
        if (treeLogged) return
        treeLogged = true
        val r = root?.get() ?: return
        val out = StringBuilder("status bar tree:")
        walk(r, 0, out)
        Logx.d(out.toString())
    }

    private fun walk(view: View, depth: Int, out: StringBuilder) {
        val id = view.id
        val name = if (id != View.NO_ID) {
            runCatching { context.resources.getResourceName(id) }.getOrNull()
                ?.substringAfterLast('/') ?: "0x${Integer.toHexString(id)}"
        } else {
            view.javaClass.simpleName
        }
        out.append(' '); repeat(depth) { out.append(' ') }; out.append(name)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) walk(view.getChildAt(i), depth + 1, out)
        }
    }
}
