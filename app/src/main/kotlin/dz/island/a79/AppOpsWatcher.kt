package dz.island.a79

import android.app.AppOpsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Who is holding the camera, the microphone, or the screen right now.
 *
 * The OEM's own privacy chip only knows camera and microphone — `PrivacyType` on this build has
 * exactly TYPE_CAMERA / TYPE_MICROPHONE / TYPE_LOCATION, no projection — so the screen recorder
 * has to come from the op that actually starts it. This registers an AppOps observer, which is the
 * same source the privacy chip itself reads: SystemUI holds the privilege, and no SystemUI class
 * name is being guessed, which is the part that breaks between ColorOS builds.
 *
 * `OPSTR_PROJECT_MEDIA` is not in the public stub, so the op string is written out in the form
 * AppOps uses internally. If the platform refuses the watch, the log says which ops were accepted
 * and the feature is simply off; [PrivacySource] keeps working on its own.
 */
class AppOpsWatcher(private val context: Context, private val onSensor: (kind: String, pkg: String) -> Unit) {

    private val appOps = context.getSystemService(AppOpsManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val active = HashMap<String, Boolean>()

    companion object {
        /** Op strings, as `AppOpsManager` spells them internally. */
        private const val OP_CAMERA = "android:camera"
        private const val OP_MIC = "android:record_audio"
        private const val OP_PROJECTION = "android:project_media"

        private val OPS = mapOf(OP_CAMERA to "camera", OP_MIC to "microphone", OP_PROJECTION to "screen recording")
    }

    fun start() {
        if (appOps == null) {
            Logx.w("no AppOpsManager: no screen-recorder indicator")
            return
        }
        if (startActiveWatch()) return
        startPerOpWatch()
    }

    /** The modern route: one registration, and the callback already carries active/inactive. */
    private fun startActiveWatch(): Boolean = runCatching {
        // onOpChanged(String op, int uid, String packageName, boolean isActive)
        val listener = AppOpsManager.OnOpActiveChangedListener { op, _, pkg, isActive ->
            val kind = OPS[op] ?: return@OnOpActiveChangedListener
            main.post { report(kind, isActive, pkg ?: "unknown") }
        }
        appOps.startWatchingActive(OPS.keys.toTypedArray(), Executors.newSingleThreadExecutor(), listener)
        Logx.d("appops watcher: active watch on ${OPS.size} ops")
        true
    }.getOrElse {
        Logx.d("active watch unavailable: ${it.javaClass.simpleName}")
        false
    }

    /** Fallback: watch each op and ask for its mode when it moves. */
    private fun startPerOpWatch() {
        var ok = 0
        val listener = AppOpsManager.OnOpChangedListener { op, pkg ->
            val kind = OPS[op] ?: return@OnOpChangedListener
            if (pkg.isNullOrBlank()) return@OnOpChangedListener
            val on = runCatching {
                val uid = context.packageManager.getPackageUid(pkg, 0)
                appOps.unsafeCheckOpNoThrow(op, uid, pkg) == AppOpsManager.MODE_ALLOWED
            }.getOrDefault(false)
            main.post { report(kind, on, pkg) }
        }
        for (op in OPS.keys) {
            val done = runCatching {
                appOps.startWatchingMode(op, null, listener)
                true
            }.getOrDefault(false)
            if (done) ok++ else Logx.d("cannot watch $op")
        }
        Logx.d("appops watcher: $ok/${OPS.size} ops")
    }

    private fun report(kind: String, on: Boolean, pkg: String) {
        val was = active[kind] == true
        active[kind] = on
        // Only the rising edge takes the island; a recording session is not worth a buzz a minute.
        if (on && !was) onSensor(kind, pkg)
    }
}
