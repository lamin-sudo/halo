package dz.island.a79

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * Sweeps the light around the camera hole when the phone unlocks by face.
 *
 * The signal is AOSP-named and carries the modality, which is what makes this an honest "face"
 * trigger rather than "the keyguard went away":
 *
 *   com.android.systemui.statusbar.phone.BiometricUnlockController
 *       #onBiometricAuthenticated(int userId, BiometricSourceType, boolean fromKeyguardAuthenticating)
 *
 * Both that class and ColorOS' own `BaseBiometricUnlockController` declare the same signature, and
 * which one runs is the ROM's business, so both are hooked. A double delivery is harmless:
 * [IslandWindow.pulseRing] ignores a pulse while one is already sweeping.
 *
 * The hook body never lets anything escape and returns the target's own type (void here), per the
 * rules in AGENTS.md — a wrong return value on this exact kind of hook crashed SystemUI once.
 */
class FaceUnlockSource(
    private val context: Context,
    private val window: IslandWindow,
) {

    private val main = Handler(Looper.getMainLooper())

    fun install(module: XposedModule, param: PackageLoadedParam) {
        val loader = param.defaultClassLoader
        var hooked = 0
        for (name in CONTROLLERS) {
            val clazz = runCatching { loader.loadClass(name) }.getOrNull()
            if (clazz == null) {
                Logx.d("$name not on this ROM")
                continue
            }
            val method = clazz.declaredMethods.firstOrNull { m ->
                m.name == "onBiometricAuthenticated" && m.parameterTypes.size == 3 &&
                    m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    m.parameterTypes[2] == java.lang.Boolean.TYPE
            }
            if (method == null) {
                Logx.d("onBiometricAuthenticated(int, source, boolean) not in ${clazz.simpleName}")
                continue
            }
            val ok = runCatching {
                method.isAccessible = true
                module.hook(method).intercept { chain ->
                    try {
                        // BiometricSourceType is an enum: FACE / FINGERPRINT / IRIS / EXTERNAL.
                        val source = chain.args.getOrNull(1)?.toString()
                        if (source.equals("FACE", ignoreCase = true)) {
                            // Both controllers report the same unlock; pulseRing() keeps one sweep.
                            Logx.d("face authenticated via ${chain.thisObject.javaClass.simpleName}")
                            main.post { window.pulseRing() }
                        }
                    } catch (t: Throwable) {
                        Logx.e("face unlock hook failed", t)
                    }
                    return@intercept chain.proceed()
                }
                hooked++
            }
            if (!ok.isSuccess) Logx.e("face hook failed on $name", ok.exceptionOrNull())
        }
        Logx.d(if (hooked > 0) "face unlock source on $hooked entry points"
            else "no face-unlock signal available on this ROM")
    }

    companion object {
        private val CONTROLLERS = listOf(
            "com.android.systemui.statusbar.phone.BiometricUnlockController",
            "com.oplusos.systemui.keyguard.base.BaseBiometricUnlockController",
        )
    }
}
