package dz.island.a79

import android.content.Context
import android.graphics.drawable.Drawable

/**
 * Our own drawables, loaded from the module APK while running inside SystemUI.
 *
 * createPackageContext is the only clean way to reach them: the classloader of this process is
 * SystemUI's, so R is not ours.
 */
object ModuleResources {

    private const val MODULE_ID = "dz.island.a79"

    @Volatile
    private var moduleContext: Context? = null

    private fun resources(host: Context): Context? = moduleContext ?: runCatching {
        host.createPackageContext(MODULE_ID, Context.CONTEXT_IGNORE_SECURITY)
    }.getOrNull()?.also { moduleContext = it }

    private fun drawable(host: Context, name: String): Drawable? {
        val ctx = resources(host) ?: return null
        val id = ctx.resources.getIdentifier(name, "drawable", MODULE_ID)
        if (id == 0) return null
        return runCatching {
            ctx.getDrawable(id)?.also { it.setTint(TINT) }
        }.getOrNull()
    }

    fun chargingIcon(host: Context, plugged: Boolean): Drawable? =
        drawable(host, if (plugged) "ic_island_bolt" else "ic_island_battery")

    fun mediaIcon(host: Context): Drawable? = drawable(host, "ic_island_music")

    /** Bell for the Do-Not-Disturb toggle; the crossed variant is the state DND is already on. */
    fun bellIcon(host: Context, dndOn: Boolean): Drawable? =
        drawable(host, if (dndOn) "ic_island_bell_off" else "ic_island_bell")

    /** Handset for the call card; the end-call disc draws the same glyph rotated. */
    fun callIcon(host: Context): Drawable? = drawable(host, "ic_island_call")

    private const val TINT = 0xFFFFFFFF.toInt()
}
