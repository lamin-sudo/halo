package dz.island.a79

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * Game Space / Game Mode state, read from Settings.Global.
 *
 * This is the signal that answers the *game* half of the fullscreen question (#7). It is a
 * settings read, not a hook, which is what makes it cheap: every route that tried to see the
 * status bar from inside SystemUI's view tree failed — the layout listener never fires,
 * `StatusBarStateControllerImpl#setState` is never called on this ROM, the bar's own view keeps
 * `winVis=0 h=107 y=0 alpha=1` while it is gone, and our overlay is always handed a status-bar
 * inset of 0. The bar is taken away by WindowManager, so the answer came from WindowManager
 * instead ([StatusBarFade.installTopAppWatcher]), and the game session came from ColorOS's own
 * settings key, below.
 *
 * ColorOS writes the session state into Settings.Global, so it is readable without a hook and
 * without touching `system_server`. Measured on CPH2553 / ColorOS 13.1 while playing:
 *
 * ```
 * 22:38:29  focus=com.oplus.games        mode=1 flag=0     <- launcher, not a game
 * 22:38:34  focus=com.supercell.brawlstars mode=0 flag=2   <- game session
 * 22:39:16  focus=com.tencent.ig         mode=0 flag=2     <- PUBG, block off
 * 22:39:22  focus=com.tencent.ig         mode=0 flag=3     <- block switched on mid-match
 * 22:41:25  focus=com.tencent.ig         mode=0 flag=3     <- still in the match
 * 22:42:32  focus=com.qoder.mobile       mode=1 flag=0     <- back out, clears by itself
 * ```
 *
 * So `flag >= 2` is "a game owns the screen" and `flag == 3` additionally means the user asked
 * Game Space to reject disturbances. The mode key is deliberately not used: it reads 1 outside a
 * game and 0 inside one, which is the opposite of what its name suggests.
 *
 * The other half of the measurement matters as much: with flag=3 live, a real Twitter
 * notification still reached `NotifCollection` and the island showed it. Game Space blocks
 * ColorOS's own heads-up banner, not the notification. Which is why this module has to make the
 * call itself if the user wants quiet.
 */
object GameSpace {

    const val KEY_FLAG = "disturb_for_game_space_mode_flag"

    /** Raw last value, for the log line that answers "why is there no island right now". */
    @Volatile
    var flag: Int = -1
        private set

    private var resolver: ContentResolver? = null
    private val handler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<(Int) -> Unit>()

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = read()
    }

    fun init(context: Context) {
        if (resolver != null) return
        val res = context.contentResolver
        resolver = res
        read()
        runCatching {
            res.registerContentObserver(Settings.Global.getUriFor(KEY_FLAG), false, observer)
        }.onFailure { Logx.w("game space observer failed: ${it.javaClass.simpleName}") }
        Logx.d("game space: flag=$flag (watching Settings.Global/$KEY_FLAG)")
    }

    /** A game owns the screen. */
    fun inGame(): Boolean = flag >= 2

    /** A game owns the screen and the user asked Game Space to block disturbances. */
    fun blocking(): Boolean = flag >= 3

    fun addChangeListener(onChange: (Int) -> Unit) {
        synchronized(listeners) { listeners.add(onChange) }
    }

    private fun read() {
        val res = resolver ?: return
        val next = runCatching { Settings.Global.getInt(res, KEY_FLAG) }.getOrDefault(0)
        val previous = flag
        if (next == previous) return
        flag = next
        // Both edges are interesting: the rising one hides the island, the falling one is the
        // only proof that the value is not sticky after the game dies.
        Logx.d("game space flag $previous -> $next")
        synchronized(listeners) { listeners.toList() }.forEach { it(next) }
    }
}
