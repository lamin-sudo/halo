package dz.island.a79

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper


/**
 * The charging scene: plug in, island appears with the percentage and the charge rate.
 *
 * Deliberately not a hook. ACTION_BATTERY_CHANGED is a sticky broadcast delivered inside every
 * process, including this one, so the whole feature is a receiver and some arithmetic.
 *
 * The charge-power extras are @hide constants whose Bundle keys we read by name, which is how
 * any app may read them; that is what lets us tell SUPERVOOC from a slow trickle.
 */
class ChargingSource(
    private val context: Context,
    private val window: IslandWindow,
) {

    private var lastStatus = -1
    private var lastPlugged = false
    private var lastPct = -1
    private var registered = false
    private var keysLogged = false

    private val receiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent != null) onBattery(intent)
        }
    }

    fun start() {
        if (registered) return
        // Seed from the sticky broadcast without triggering a popup at boot.
        val sticky = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        lastStatus = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        lastPlugged = (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        lastPct = sticky?.let { percentOf(it) } ?: -1
        runCatching {
            context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            registered = true
            Logx.d("charging source started (status=$lastStatus plugged=$lastPlugged pct=$lastPct)")
        }.onFailure { Logx.e("charging receiver failed", it) }
    }

    fun stop() {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
        window.cancel(KEY)
    }

    private fun onBattery(intent: Intent) {
        val cfg = Config.current
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val pct = percentOf(intent)

        // A transition earns the island. While it is already up, a new percentage is worth
        // refreshing in place; while it is away, a bare level tick is not.
        val transition = status != lastStatus || plugged != lastPlugged
        val refresh = !transition && pct != lastPct && window.isShowing(KEY)
        if (transition || refresh || !cfg.enabled || !cfg.charging) {
            Logx.d(
                "battery: status=$status/$lastStatus plugged=$plugged/$lastPlugged pct=$pct/$lastPct " +
                    "transition=$transition refresh=$refresh enabled=${cfg.enabled}/charge=${cfg.charging}"
            )
        }

        if (!cfg.enabled || !cfg.charging) {
            lastStatus = status
            lastPlugged = plugged
            lastPct = pct
            window.cancel(KEY)
            return
        }
        if (!transition && !refresh) return

        val wasPlugged = lastPlugged
        lastStatus = status
        lastPlugged = plugged
        lastPct = pct

        val watts = chargeWatts(intent)

        val title = when {
            plugged && !wasPlugged && watts >= FAST_WATTS -> fastName(watts)
            plugged && !wasPlugged -> "Charging"
            !plugged && wasPlugged -> "Unplugged"
            status == BatteryManager.BATTERY_STATUS_FULL -> "Fully charged"
            plugged -> "Charging"
            else -> "Battery"
        }

        val low = pct in 0..LOW_PCT && !plugged
        val accent = when {
            low -> ACCENT_RED
            plugged -> ACCENT_CHARGE
            else -> ACCENT_DEFAULT
        }

        window.submit(
            IslandEvent(
                key = KEY,
                source = Source.CHARGING,
                title = title,
                body = null,
                trailing = if (pct >= 0) "$pct%" else null,
                icon = ModuleResources.chargingIcon(context, plugged),
                avatar = null,
                avatarText = null,
                actions = emptyList(),
                progress = pct,
                accentArgb = accent,
                // A charge event is a glance, not a resident: it always has a timeout.
                ttlMs = if (plugged) cfg.ttlMs else UNPLUGGED_MS,
                openIntent = openBatterySettings(),
                showBell = false,
            )
        )
    }

    private fun percentOf(intent: Intent): Int {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return if (level >= 0 && scale > 0) level * 100 / scale else -1
    }

    /**
     * Charge power, in watts, from the @hide extras OEMs fill in.
     *
     * The key names below are the AOSP ones; they are not in the public stub, so they are read
     * defensively and the raw key set is logged once under the verbose switch to confirm what
     * this ColorOS build actually delivers.
     */
    private fun chargeWatts(intent: Intent): Float {
        if (Config.debugEnabled() && !keysLogged) {
            keysLogged = true
            Logx.d("battery extras: ${intent.extras?.keySet()?.joinToString()}")
        }
        val current = intent.getLongExtra(KEY_MAX_CURRENT, -1L)
        val voltage = intent.getLongExtra(KEY_MAX_VOLTAGE, -1L)
        if (current > 0L && voltage > 0L) {
            // Both are micro-units.
            return (current * voltage) / 1_000_000_000_000f
        }
        return 0f
    }

    private fun fastName(watts: Float): String = when {
        watts >= 60f -> "SUPERVOOC"
        watts >= 18f -> "Fast charging"
        else -> "Charging"
    }

    private fun openBatterySettings(): android.app.PendingIntent? = runCatching {
        android.app.PendingIntent.getActivity(
            context, 0,
            Intent("android.settings.BATTERY_USAGE").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
    }.getOrNull()

    companion object {
        private const val KEY = "charging"
        private const val KEY_MAX_CURRENT = "android.os.extra.MAX_CHARGING_CURRENT"
        private const val KEY_MAX_VOLTAGE = "android.os.extra.MAX_CHARGING_VOLTAGE"
        private const val FAST_WATTS = 15f
        private const val LOW_PCT = 20
        private const val UNPLUGGED_MS = 2_500L
        private const val ACCENT_CHARGE = 0xFF4CAF50.toInt()
        private const val ACCENT_RED = 0xFFFF5252.toInt()
        private const val ACCENT_DEFAULT = 0xFFB3B3B3.toInt()
    }
}
