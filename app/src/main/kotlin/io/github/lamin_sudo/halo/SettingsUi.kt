package io.github.lamin_sudo.halo

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The pieces the settings screen is built from, drawn instead of themed.
 *
 * The module ships no AndroidX and no res/layout on purpose (see [SettingsActivity]), so the
 * iPhone-ish look — grouped inset cards, a green switch with a travelling thumb, section
 * headers, chevrons — is produced with Canvas and GradientDrawable here. That also means the
 * screen cannot drift with whatever `Theme.DeviceDefault` the OEM restyles next ColorOS release.
 */
object Ui {

    const val BG = 0xFF000000.toInt()
    const val CARD = 0xFF1C1C1E.toInt()
    const val CARD_HI = 0xFF2C2C2E.toInt()
    const val SEP = 0xFF38383A.toInt()
    const val TEXT = Color.WHITE
    const val DIM = 0xFF98989F.toInt()
    const val GREEN = 0xFF34C759.toInt()
    const val BLUE = 0xFF0A84FF.toInt()
    const val ORANGE = 0xFFFF9F0A.toInt()
    const val PINK = 0xFFFF375F.toInt()
    const val PURPLE = 0xFFAF52DE.toInt()
    const val INDIGO = 0xFF5E5CE6.toInt()
    const val TEAL = 0xFF64D2FF.toInt()
    const val GREY = 0xFF8E8E93.toInt()

    fun dp(context: Context, v: Float): Int = (v * context.resources.displayMetrics.density).toInt()

    fun card(context: Context): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(context, 17f).toFloat()
        setColor(CARD)
    }

    /** A hairline between rows, inset the way iOS insets its separators from the label. */
    fun separator(context: Context, insetDp: Float = 45f): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, .5f))
            .apply { marginStart = dp(context, insetDp) }
        setBackgroundColor(SEP)
    }

    fun sectionHeader(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text.uppercase()
        setTextColor(DIM)
        textSize = 11.5f
        letterSpacing = 0.06f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(dp(context, 24f), 0, dp(context, 24f), dp(context, 7f))
    }

    fun title(context: Context, text: String, size: Float, bold: Boolean = true): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(TEXT)
            textSize = size
            if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

    fun chevron(context: Context): TextView = TextView(context).apply {
        text = "›"
        setTextColor(0xFF48484E.toInt())
        textSize = 17f
        setPadding(dp(context, 4f), 0, 0, 0)
    }
}

/**
 * The iOS switch: 51x31 track, 27 thumb, green when on, and the thumb travels instead of
 * teleporting. Framework `Switch` cannot be made to look like this without a theme, and a theme
 * is exactly what an OEM re-skins.
 */
class IosSwitch(context: Context, private val onChange: (Boolean) -> Unit) : View(context) {

    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private var frac = 0f
    private var anim: ValueAnimator? = null
    private val rect = RectF()

    var checked: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            animateTo(if (value) 1f else 0f)
        }

    init {
        isClickable = true
    }

    fun setInitial(value: Boolean) {
        checked = value
        frac = if (value) 1f else 0f
    }

    private fun animateTo(to: Float) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(frac, to).apply {
            duration = 190L
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { frac = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(Ui.dp(context, 51f), Ui.dp(context, 31f))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = h * 0.13f
        val r = (h - 2 * pad) / 2f
        track.style = Paint.Style.FILL
        track.color = blend(TRACK_OFF, Ui.GREEN, frac)
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, h / 2f, h / 2f, track)
        val cx = pad + r + frac * (w - 2 * pad - 2 * r)
        canvas.drawCircle(cx, h / 2f, r, thumb)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> true
        MotionEvent.ACTION_UP -> {
            // iOS moves the switch wherever the finger lands inside the track, so a tap on the
            // right half means "on" rather than "toggle".
            val want = event.x > width / 2f
            if (want != checked) { checked = want; onChange(want) }
            performClick()
            true
        }
        else -> super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private companion object {
        const val TRACK_OFF = 0xFF39393D.toInt()

        fun blend(from: Int, to: Int, t: Float): Int {
            fun ch(shift: Int) = (((from shr shift and 0xFF) * (1 - t) + (to shr shift and 0xFF) * t)).toInt()
            return Color.rgb(ch(16), ch(8), ch(0))
        }
    }
}

/**
 * Small line glyphs for the settings rows, drawn on Canvas in a 24x24 space.
 *
 * Nine extra vector XML files would have been the conventional move; one file with the shapes in
 * it is shorter, keeps the stroke weight identical everywhere, and lets a row tint its icon
 * without touching resources.
 */
class Glyph(private val kind: Kind, private val color: Int) : Drawable() {

    enum class Kind { HALO, BELL, MUSIC, BOLT, CAMERA, CARD, GAME, EXPAND, WAVE, TIMER, RULE, LOG }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = this@Glyph.color
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = this@Glyph.color
    }
    private val path = Path()
    private val box = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()).toFloat()
        if (s <= 0f) return
        canvas.save()
        canvas.translate(b.left + (b.width() - s) / 2f, b.top + (b.height() - s) / 2f)
        canvas.scale(s / 24f, s / 24f)
        stroke.strokeWidth = 1.9f
        path.rewind()
        when (kind) {
            Kind.HALO -> {
                path.addCircle(12f, 12f, 8.4f, Path.Direction.CW)
                canvas.drawPath(path, stroke)
                canvas.drawCircle(12f, 12f, 3.1f, fill)
            }
            Kind.BELL -> {
                path.moveTo(6.4f, 16.5f); path.lineTo(17.6f, 16.5f)
                path.moveTo(8f, 16.5f); path.lineTo(8f, 11f)
                path.arcTo(RectF(8f, 4.6f, 16f, 12.6f), 180f, 180f)
                path.lineTo(16f, 16.5f)
                canvas.drawPath(path, stroke)
                canvas.drawCircle(12f, 19.4f, 1.7f, fill)
            }
            Kind.MUSIC -> {
                path.moveTo(9f, 17f); path.lineTo(9f, 6.4f); path.lineTo(17f, 5f); path.lineTo(17f, 15.5f)
                canvas.drawPath(path, stroke)
                canvas.drawCircle(6.8f, 17f, 2.3f, fill)
                canvas.drawCircle(14.8f, 15.5f, 2.3f, fill)
            }
            Kind.BOLT -> {
                path.moveTo(13.4f, 2.6f); path.lineTo(6.4f, 13.4f); path.lineTo(11f, 13.4f)
                path.lineTo(9.6f, 21.4f); path.lineTo(17.6f, 9.6f); path.lineTo(12.4f, 9.6f)
                path.close()
                canvas.drawPath(path, stroke)
            }
            Kind.CAMERA -> {
                box.set(3.2f, 6.6f, 20.8f, 17.8f)
                canvas.drawRoundRect(box, 3f, 3f, stroke)
                canvas.drawCircle(12f, 12.2f, 3.3f, stroke)
                path.moveTo(8.4f, 6.6f); path.lineTo(9.8f, 4.4f); path.lineTo(14.2f, 4.4f); path.lineTo(15.6f, 6.6f)
                canvas.drawPath(path, stroke)
            }
            Kind.CARD -> {
                box.set(3.4f, 5.4f, 20.6f, 18.6f)
                canvas.drawRoundRect(box, 3.4f, 3.4f, stroke)
                path.moveTo(6.6f, 10.4f); path.lineTo(17.4f, 10.4f)
                path.moveTo(6.6f, 14f); path.lineTo(13.4f, 14f)
                canvas.drawPath(path, stroke)
            }
            Kind.GAME -> {
                box.set(2.8f, 6.8f, 21.2f, 17.2f)
                canvas.drawRoundRect(box, 5f, 5f, stroke)
                path.moveTo(7.4f, 10.4f); path.lineTo(7.4f, 13.6f)
                path.moveTo(5.8f, 12f); path.lineTo(9f, 12f)
                canvas.drawPath(path, stroke)
                canvas.drawCircle(16.2f, 11f, 1.3f, fill)
                canvas.drawCircle(18.6f, 13.4f, 1.3f, fill)
            }
            Kind.EXPAND -> {
                path.moveTo(4f, 9f); path.lineTo(4f, 4f); path.lineTo(9f, 4f)
                path.moveTo(15f, 4f); path.lineTo(20f, 4f); path.lineTo(20f, 9f)
                path.moveTo(20f, 15f); path.lineTo(20f, 20f); path.lineTo(15f, 20f)
                path.moveTo(9f, 20f); path.lineTo(4f, 20f); path.lineTo(4f, 15f)
                canvas.drawPath(path, stroke)
            }
            Kind.WAVE -> {
                path.arcTo(RectF(7f, 7f, 17f, 17f), 220f, 100f)
                canvas.drawPath(path, stroke)
                path.rewind()
                path.arcTo(RectF(4f, 4f, 20f, 20f), 230f, 80f)
                canvas.drawPath(path, stroke)
                path.rewind()
                path.arcTo(RectF(10f, 10f, 14f, 14f), 210f, 120f)
                canvas.drawPath(path, stroke)
            }
            Kind.TIMER -> {
                canvas.drawCircle(12f, 13.4f, 7.4f, stroke)
                path.moveTo(12f, 13.4f); path.lineTo(12f, 9.4f)
                path.moveTo(10f, 3.4f); path.lineTo(14f, 3.4f)
                path.moveTo(12f, 3.4f); path.lineTo(12f, 6f)
                canvas.drawPath(path, stroke)
            }
            Kind.RULE -> {
                box.set(2.6f, 8f, 21.4f, 16f)
                canvas.drawRoundRect(box, 2.4f, 2.4f, stroke)
                path.moveTo(7f, 8f); path.lineTo(7f, 11.4f)
                path.moveTo(11f, 8f); path.lineTo(11f, 12.6f)
                path.moveTo(15f, 8f); path.lineTo(15f, 11.4f)
                path.moveTo(19f, 8f); path.lineTo(19f, 12.6f)
                canvas.drawPath(path, stroke)
            }
            Kind.LOG -> {
                box.set(5f, 3.4f, 19f, 20.6f)
                canvas.drawRoundRect(box, 2.6f, 2.6f, stroke)
                path.moveTo(8.2f, 8.4f); path.lineTo(15.8f, 8.4f)
                path.moveTo(8.2f, 12f); path.lineTo(15.8f, 12f)
                path.moveTo(8.2f, 15.6f); path.lineTo(13f, 15.6f)
                canvas.drawPath(path, stroke)
            }
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { stroke.alpha = alpha; fill.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        stroke.colorFilter = colorFilter; fill.colorFilter = colorFilter
    }
    @Deprecated("Required by Drawable", level = DeprecationLevel.WARNING)
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

/** The coloured rounded square an iOS settings row puts in front of its label. */
fun iconChip(context: Context, kind: Glyph.Kind, tint: Int): View {
    val size = Ui.dp(context, 28f)
    val chip = android.widget.FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = Ui.dp(context, 12f) }
        background = GradientDrawable().apply {
            cornerRadius = Ui.dp(context, 7f).toFloat()
            setColor(tint)
        }
    }
    val iv = ImageView(context).apply {
        setImageDrawable(Glyph(kind, Color.WHITE))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val pad = Ui.dp(context, 4.5f)
        setPadding(pad, pad, pad, pad)
    }
    chip.addView(iv, android.widget.FrameLayout.LayoutParams(size, size, Gravity.CENTER))
    return chip
}
