package dz.island.a79

import android.content.Context
import android.graphics.Rect
import android.view.DisplayCutout
import android.view.WindowManager

/**
 * Island metrics in **pixels**, derived from the real display cutout.
 *
 * Never dp: this phone reports density 408 while the reference device reports 540, so a dp
 * constant copied from the OEM build renders a pill shorter than the camera hole
 * (36dp = 91.8px here vs a 107px cutout). Everything below is anchored to the cutout and to
 * fractions of the panel width, which are identical on both 1080x2400 panels regardless of
 * the user's "Display size" setting.
 */
class Geometry(
    val screenW: Int,
    val screenH: Int,
    val holeCx: Int,
    val holeW: Int,
    val holeH: Int,
    val offsetX: Int,
    val offsetY: Int,
    val widthScalePercent: Int,
) {
    /** Vertical breathing room around the hole so the pill reads as one object, not a band-aid. */
    private val padV = 10

    /** Horizontal room for the icon/text inside the pill. */
    private val padH = 34

    val pillH: Int get() = holeH + 2 * padV

    /**
     * 0.32 matched the reference device's smallest capsule (102dp at its density), but measured
     * on this panel it ellipsizes "Unplugged 45%" — 0.36 fits the longest charging label.
     */
    val idleW: Int
        get() = scale(kotlin.math.max(holeW + 2 * 40, (0.36f * screenW).toInt()))

    /**
     * 0.65 matched the reference device's base capsule, but measured on the phone it left only
     * 177px in the right zone — a message body came out as "Rabih:". 0.78 gives the right zone
     * ~260px while still stopping short of the panel edges.
     */
    val activeW: Int
        get() = scale((0.78f * screenW).toInt())

    /** Never eat the whole bar: leave a margin so the pill never touches the panel edge. */
    val maxWidth: Int get() = screenW - 2 * 24

    val corner: Int get() = pillH / 2

    /** Text sizes in px. 30px is what the reference device's 9sp resolves to at its density. */
    val titlePx = 32
    val bodyPx = 26
    val iconPx = 52

    val contentInset: Int get() = padH

    /** The camera hole in screen x, and the two content zones that must not be drawn over it. */
    val holeLeft: Int get() = holeCx - holeW / 2
    val holeRight: Int get() = holeCx + holeW / 2

    /** Gap kept clear on each side of the hole, so no glyph is ever cut by the camera. */
    private val holeGap = 18

    fun leftZoneRight(width: Int): Int = (holeLeft - holeGap).coerceAtLeast(leftFor(width) + contentInset)

    fun rightZoneLeft(width: Int): Int = (holeRight + holeGap).coerceAtMost(leftFor(width) + width - contentInset)

    /** Width of the shrinking end-state: a ring that sits exactly around the hole. */
    val ringWidth: Int get() = holeW + 2 * 8

    private fun scale(v: Int) = (v * widthScalePercent / 100).coerceAtMost(maxWidth)

    /** Left edge of the pill for a given width, in screen coordinates. */
    fun leftFor(width: Int): Int = holeCx - width / 2 + offsetX

    fun copyWith(offsetX: Int, offsetY: Int, widthScalePercent: Int) =
        Geometry(screenW, screenH, holeCx, holeW, holeH, offsetX, offsetY, widthScalePercent)

    override fun toString(): String =
        "screen=${screenW}x${screenH} hole=(${holeCx},${holeW}x${holeH}) pillH=$pillH idle=$idleW active=$activeW"

    companion object {
        /**
         * Reads the cutout through the WindowManager of the SystemUI process.
         *
         * Falls back to the measured CPH2553 values if the platform reports no cutout (for
         * example when "hide notch" is on), so the island still lands in the right place.
         */
        fun read(context: Context, offsetX: Int, offsetY: Int, widthScalePercent: Int): Geometry {
            val wm = context.getSystemService(WindowManager::class.java)
            val metrics = wm?.maximumWindowMetrics
            val bounds: Rect = metrics?.bounds ?: Rect(0, 0, 1080, 2400)
            val cutout: DisplayCutout? =
                runCatching { metrics?.windowInsets?.displayCutout }.getOrNull()

            val box: Rect = cutout?.boundingRects?.firstOrNull { it.width() > 0 }
                ?: Rect((bounds.width() / 2) - 38, 0, (bounds.width() / 2) + 38, 107)

            return Geometry(
                screenW = bounds.width(),
                screenH = bounds.height(),
                holeCx = box.centerX(),
                holeW = box.width(),
                holeH = box.height(),
                offsetX = offsetX,
                offsetY = offsetY,
                widthScalePercent = widthScalePercent,
            )
        }
    }
}
