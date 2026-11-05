package io.github.lamin_sudo.halo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * The island itself: one View, everything drawn on its Canvas.
 *
 * No child views and no Compose — this runs inside com.android.systemui, where every extra
 * framework is another way to crash the status bar.
 *
 * Three rules come straight off the photos taken on the phone:
 *
 * 1. Nothing is drawn over the camera hole. The layout is split into a left zone and a right
 *    zone with the cutout kept clear between them, which is what realme's capsule does. Drawing
 *    across it clipped letters in half, and a percentage that reads as "4-" is worse than none.
 * 2. The body is translucent, not solid black, so the island floats over the wallpaper the way
 *    the iPhone's does. The hole itself is masked solid, otherwise the camera shows through the
 *    middle of the pill.
 * 3. A progress line exists only for media, and it is green: the earlier white track under every
 *    capsule read as a stray line, and that is still forbidden for notifications and charging.
 */
class IslandView(context: Context, private var geo: Geometry) : View(context) {

    var onOpen: (() -> Unit)? = null
    var onAction: ((Int) -> Unit)? = null
    var onDismiss: (() -> Unit)? = null
    var onToggleDnd: (() -> Unit)? = null
    /** Media only: the pill doubles as the transport surface, so it needs its own callbacks. */
    var onPlayPause: (() -> Unit)? = null
    var onSeek: ((Long) -> Unit)? = null
    var onPrev: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null
    /** Swipe down on the opened card: the targets are small and the thumb is not precise. */
    var onClosePlayer: (() -> Unit)? = null
    var playerMode = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
    /** A burst unfolded into its individual messages, one row per notification. */
    var listMode = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
    /** Swiping one line out dismisses exactly that notification, and only that one. */
    var onRowDismiss: ((String) -> Unit)? = null
    /** One message, unfolded: the full text and the notification's own buttons. */
    var detailMode = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
    /** The "Open" chip in the detail card — launch the app, do not fold the card. */
    var onOpenApp: (() -> Unit)? = null
    /** A live call: the card carries the two buttons the dialer put in its own notification. */
    var callMode = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val holeMask = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val trailingPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val bellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bellGlyph = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chipStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = CHIP_STROKE_COLOR
    }
    private val chipText = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ringOval = RectF()
    private val circle = android.graphics.Path()
    private val bodyPath = android.graphics.Path()
    private val bar = RectF()
    private val initialPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ppPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ppGlyph = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ppPath = android.graphics.Path()
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grabPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var bellShake = 0f
    private var bellShaker: android.animation.ValueAnimator? = null
    /** Set while the list header replaces the icon with the big bell, so the title starts after it. */
    private var textLeftOverride: Float? = null
    /** The rows this card shows; shrinks locally as lines are swiped away. */
    private var shownRows: List<IslandRow> = emptyList()
    /** Hit boxes for the detail card's chips, filled by layoutChips(). */
    private val chipBoxes = ArrayList<RectF>(3)
    private val initialTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    private var event: IslandEvent? = null

    /** The width the window is currently animated to; everything is clipped to it. */
    private var drawnWidth = 0

    /** 1 while content is shown, 0 when only the ring is left. */
    private var contentAlpha = 1f

    /** The neon edge around the capsule body. */
    private var glowAlpha = 0f

    /** The collapse ring around the hole, drawn after the content has gone. */
    private var ringAlpha = 0f

    /** Where the light head of the collapse ring currently is, in degrees. */
    private var ringSweep = 0f

    /**
     * Ambient mode: media is playing and nothing is expanded. The body is gone, only the light
     * keeps sweeping around the camera hole, and a tap on it brings the capsule back.
     */
    private var ambient = false

    private var dndOn = false

    /** Hit boxes for the media control chips, computed in onDraw and reused by touch. */
    private val mediaBoxes = ArrayList<RectF>(2)
    /** prev, rewind, play/pause, forward, next — in that order, filled by layoutControls(). */
    private val controlBoxes = ArrayList<RectF>(5)
    private var controlsTop = 0f

    private var downX = 0f
    private var downY = 0f
    private var lifted = 0f
    private var swiping = false

    init {
        applyTextSizes()
    }

    fun setGeometry(next: Geometry) {
        geo = next
        applyTextSizes()
        invalidate()
    }

    fun bind(next: IslandEvent?) {
        event = next
        shownRows = next?.rows ?: emptyList()
        contentAlpha = 1f
        ringAlpha = 0f
        glowAlpha = 0f
        ambient = false
        mediaBoxes.clear()
        invalidate()
    }

    /** A line the user just swiped away, dropped before the platform echo arrives. */
    fun dropRow(key: String) {
        shownRows = shownRows.filter { it.key != key }
        invalidate()
    }

    fun listRows(): Int = shownRows.size

    /** True when the card is showing the unfolded burst. */
    private fun listOpen() = listMode && shownRows.isNotEmpty()

    /**
     * The height of the pill band inside the current window. Every card (list, call, detail) is a
     * band plus a body, and anything centred on the window instead — the bell sat at 213px in a
     * 427px detail card, on top of the paragraph.
     */
    private fun bandH(): Float =
        if (listOpen() || callMode || detailMode) geo.pillH.toFloat() else h().toFloat()

    fun setDnd(on: Boolean) {
        if (dndOn == on) return
        dndOn = on
        invalidate()
    }

    /** Called by the window on every animation frame. */
    fun setDrawnWidth(widthPx: Int) {
        drawnWidth = widthPx
        invalidate()
    }

    fun setContentAlpha(alpha: Float) {
        contentAlpha = alpha
        invalidate()
    }

    /** The capsule edge lights up when the island appears and dies on collapse. */
    fun setGlow(alpha: Float) {
        glowAlpha = alpha
        invalidate()
    }

    fun setRingAlpha(alpha: Float, sweepDegrees: Float, accentArgb: Int) {
        ringAlpha = alpha
        ringSweep = sweepDegrees
        ringPaint.color = accentArgb
        ringPaint.strokeWidth = 8f
        if (alpha > 0f && !ambient) contentAlpha = 0f
        invalidate()
    }

    /** Stand the ring up on its own (media playing) or take it down. */
    fun setAmbient(on: Boolean, accentArgb: Int) {
        ambient = on
        ringPaint.color = accentArgb
        ringPaint.strokeWidth = 8f
        ringAlpha = if (on) 1f else 0f
        if (on) contentAlpha = 0f
        invalidate()
    }

    fun setAmbientSweep(degrees: Float) {
        ringSweep = degrees
        invalidate()
    }

    private fun applyTextSizes() {
        titlePaint.apply {
            color = TEXT
            textSize = geo.titlePx.toFloat()
            isFakeBoldText = true
        }
        bodyPaint.apply {
            color = TEXT_DIM
            textSize = geo.bodyPx.toFloat()
        }
        trailingPaint.apply {
            color = TEXT
            textSize = geo.titlePx.toFloat()
            isFakeBoldText = true
        }
        bellGlyph.apply {
            color = TEXT
            textSize = geo.bodyPx.toFloat()
        }
        chipText.apply {
            color = TEXT
            textSize = geo.bodyPx.toFloat()
        }
        bgPaint.color = BG_TRANSLUCENT
        holeMask.color = BG_SOLID
    }

    private fun w(): Int = if (drawnWidth > 0) drawnWidth else width
    private fun h(): Int = if (height > 0) height else geo.pillH

    override fun onDraw(canvas: Canvas) {
        val e = event ?: return
        val w = w().toFloat()
        val h = h().toFloat()
        val left = 0f
        val right = w

        if (ambient) {
            // Just the light around the hole; the body is not there any more.
            drawCollapseRing(canvas, w, h)
            return
        }

        // Body, then a solid patch exactly over the hole so the camera does not show through.
        val corner = if (playerMode) PLAYER_CORNER else geo.corner.toFloat()
        // The backdrop paints its own scrim, so the body fill has to stand down: 0xE0 black over
        // the artwork is what made the opened card look like a plain black box.
        val backdrop = if (playerMode) drawPlayerBackdrop(canvas, w, h, corner) else false
        if (!backdrop) {
            bar.set(left, 0f, right, h)
            canvas.drawRoundRect(bar, corner, corner, bgPaint)
        }
        val ringOnly = ringAlpha > 0f
        if (!ringOnly) {
            maskHole(canvas, w)
        }

        if (ringOnly) {
            drawCollapseRing(canvas, w, h)
            return
        }

        drawGlow(canvas, w, h)
        if (contentAlpha <= 0.01f) return
        val alphaBefore = titlePaint.alpha
        applyContentAlpha()

        val inset = geo.contentInset.toFloat()
        // An opened card is a band plus a body. Everything that belongs to the pill — the face,
        // the title, the count — is centred on the band, not on the whole window; the call card
        // used to draw its header through the middle of the buttons.
        val bandH = bandH()
        val pillLeft = (geo.holeCx - w.toInt() / 2f)
        val leftEnd = (geo.leftZoneRight(w.toInt()) - pillLeft).toFloat()
        val rightStart = (geo.rightZoneLeft(w.toInt()) - pillLeft).toFloat()
        val rightEnd = w - inset

        // ── left zone: icon + title (+ body under it) ─────────────────────────────
        val iconSize = geo.iconPx.toFloat()
        val iconLeft = inset.coerceAtMost(leftEnd - iconSize)
        val iconTop = (bandH - iconSize) / 2f + lifted
        val avatar = e.avatar
        if (listOpen()) {
            // He asked for a big bell on the left of the list, with the faces on the rows.
            val box = bellBoxList()
            drawBell(canvas, h, box, BELL_LIST_D)
            textLeftOverride = box.right + 18f
        }
        if (listOpen()) {
            // The rows carry the faces; the band carries the app name and the bell.
        } else if (avatar != null) {
            // The sender's photo, clipped to a circle: what the white dot was standing in for.
            circle.reset()
            circle.addCircle(
                iconLeft + iconSize / 2f, iconTop + iconSize / 2f, iconSize / 2f,
                android.graphics.Path.Direction.CW
            )
            val save = canvas.save()
            canvas.clipPath(circle)
            avatar.setBounds(
                iconLeft.toInt(), iconTop.toInt(),
                (iconLeft + iconSize).toInt(), (iconTop + iconSize).toInt()
            )
            avatar.alpha = (contentAlpha * 255f).toInt().coerceIn(0, 255)
            bar.set(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
            canvas.drawRoundRect(bar, iconSize / 2f, iconSize / 2f, bgPaint)
            avatar.draw(canvas)
            canvas.restoreToCount(save)
        } else if (e.avatarText != null) {
            // No photo to clip — which on this ROM is the normal case, because the platform
            // strips Person pictures before we ever see them. A coloured disc with the sender's
            // initial, the way iOS fills the same hole.
            drawInitial(canvas, e.avatarText, iconLeft, iconTop, iconSize)
        } else {
            e.icon?.setBounds(
                iconLeft.toInt(), iconTop.toInt(),
                (iconLeft + iconSize).toInt(), (iconTop + iconSize).toInt()
            )
            e.icon?.draw(canvas)
        }

        val textLeft = textLeftOverride
            ?: if (e.avatar != null || e.avatarText != null || e.icon != null)
                iconLeft + iconSize + 16f else inset
        textLeftOverride = null
        // In the list card the band is only the top pillH of the window; centring the header text
        // on the whole card put "A79 Island" and the count on top of the second row.
        val bandMid = bandH / 2f + lifted
        val bandTitle = if (detailMode) e.appLabel ?: e.title else e.title
        if (leftEnd > textLeft) {
            if (e.body != null && !detailMode) {
                val mid = bandMid
                drawClipped(canvas, titlePaint, e.title, textLeft, leftEnd, mid - 6f)
                drawClipped(canvas, bodyPaint, e.body, textLeft, leftEnd, mid + geo.bodyPx - 2f)
            } else {
                drawClipped(canvas, titlePaint, bandTitle, textLeft, leftEnd, bandMid + centre(titlePaint))
            }
        }

        // ── right zone: media controls, or percentage / sender, then the bell ────
        var zoneRight = rightEnd
        if (e.showBell) {
            val box = bellBox()
            zoneRight = box.left - 14f
        }
        // No transport chips: the player capsule shows artwork, title, artist and the green line,
        // and the buttons only appear once it is opened as the mini player.
        mediaBoxes.clear()
        controlBoxes.clear()
        if (e.source == Source.MEDIA && playerMode) {
            // Below the pill band: a row of targets a thumb can actually hit.
            layoutControls(w, h)
            drawControls(canvas, e.playing != false)
            drawGrabber(canvas, w, h)
        }
        if (callMode && e.source == Source.CALL) {
            layoutCallControls(w, e.actions.size)
            drawCallControls(canvas, e.actions.size)
            drawGrabber(canvas, w, h)
        }
        // In the list the card *is* the message count, so the header says what is on it — after a
        // line is swiped away the app's own count would still claim the old number.
        val trailing = if (listOpen()) "${shownRows.size} messages" else e.trailing
        if (trailing != null && zoneRight > rightStart) {
            val baseline = bandMid + centre(trailingPaint)
            val avail = zoneRight - rightStart
            val textWidth = trailingPaint.measureText(trailing)
            if (textWidth <= avail) {
                // Right-aligned so it sits as close to the hole as the left text does.
                canvas.drawText(trailing, zoneRight - textWidth, baseline, trailingPaint)
            } else {
                // Too long for the zone: ellipsize it. Clamping the start instead let the tail
                // run out under the bell button.
                drawClipped(canvas, trailingPaint, trailing, rightStart, zoneRight, baseline)
            }
        }

        if (e.showBell && !listOpen()) drawBell(canvas, h)
        // Any item that reports progress gets the line, not only media: a Play Store or Chrome
        // download posts EXTRA_PROGRESS on the same scale, and a capsule that says "45%" with no
        // bar next to it looked like a bug.
        if (e.progress >= 0) {
            drawProgressLine(canvas, w, h, e.progress, lifted)
        }
        if (listOpen()) {
            drawRows(canvas, w, shownRows)
            drawGrabber(canvas, w, h)
        }
        if (detailMode && e.source == Source.NOTIFICATION) {
            drawDetail(canvas, w, e)
            drawGrabber(canvas, w, h)
        }

        titlePaint.alpha = alphaBefore
    }

    /**
     * The green line that moves with the track: a dim full-width rail with the played part drawn
     * over it. Media only — for everything else this used to read as a stray white line.
     */
    /**
     * Where the line sits: bottom of the pill normally, just under the band in the card. The
     * first version used a fraction of the card and pushed the buttons past its bottom edge —
     * they were drawn over whatever was behind the window.
     */
    private fun lineY(w: Float): Float =
        if (playerMode) geo.pillH + LINE_IN_CARD else h() - 15f

    private fun drawProgressLine(canvas: Canvas, w: Float, h: Float, percent: Int, lifted: Float) {
        val inset = if (playerMode) 46f else 22f
        val y = lineY(w) + lifted
        val full = w - inset * 2f
        if (full <= 0f) return
        // The line sits near the bottom edge of a pill whose corner radius is half its height, so
        // without a clip the last few pixels of it hang outside the capsule — which read as a
        // stray green line under the island.
        bodyPath.reset()
        bodyPath.addRoundRect(
            0f, 0f, w, h, (if (playerMode) PLAYER_CORNER else geo.corner).toFloat(),
            (if (playerMode) PLAYER_CORNER else geo.corner).toFloat(),
            android.graphics.Path.Direction.CW
        )
        val save = canvas.save()
        canvas.clipPath(bodyPath)
        linePaint.color = PROGRESS_TRACK
        linePaint.alpha = scaledAlpha(PROGRESS_TRACK_DIM)
        bar.set(inset, y - 2.5f, inset + full, y + 2.5f)
        canvas.drawRoundRect(bar, 3f, 3f, linePaint)
        linePaint.color = PROGRESS_GREEN
        linePaint.alpha = scaledAlpha(0xFF000000.toInt())
        val done = inset + full * (percent / 100f)
        bar.set(inset, y - 2.5f, done.coerceAtLeast(inset + 8f), y + 2.5f)
        canvas.drawRoundRect(bar, 3f, 3f, linePaint)
        canvas.restoreToCount(save)
    }

    /**
     * Three targets — previous, play/pause, next — under the progress line. The ±10s pair was
     * dropped on purpose: he asked for a clean player, and five discs in a 842px card read as
     * noise. Sizes stay generous because the pill itself was impossible to hit with a thumb.
     */
    /**
     * The unfolded burst: newest at the top, one face and two lines per message. Reading a group
     * meant opening the app before; this keeps the shade out of the way.
     */
    private fun drawRows(canvas: Canvas, w: Float, rows: List<IslandRow>) {
        val inset = geo.contentInset.toFloat()
        val face = ROW_AVATAR.toFloat()
        val textX = inset + face + 18f
        val right = w - inset
        // Hairline under the header, so the band still reads as the pill it collapsed from.
        linePaint.color = ROW_DIVIDER
        linePaint.alpha = scaledAlpha(ROW_DIVIDER)
        bar.set(inset, geo.pillH - 1f, w - inset, geo.pillH + 1f)
        canvas.drawRect(bar, linePaint)
        for ((visual, row) in rows.asReversed().withIndex()) {
            val top = geo.pillH + visual * ROW_H
            val cy = top + ROW_H / 2f
            drawFace(canvas, row.avatar, row.initial, inset, cy - face / 2f, face)
            drawClipped(canvas, titlePaint, row.name, textX, right, top + 42f)
            row.snippet?.let { drawClipped(canvas, bodyPaint, it, textX, right, top + 74f) }
        }
    }

    /**
     * The paragraph the pill could not fit, then the buttons the notification actually carries.
     * Four lines is the cap: a longer message is the app's job, not the island's.
     */
    private fun drawDetail(canvas: Canvas, w: Float, e: IslandEvent) {
        val inset = geo.contentInset.toFloat()
        val right = w - inset
        var y = geo.pillH + DETAIL_TOP
        // The band already says who is speaking, so the card opens with the subject and then the
        // paragraph the pill had to cut.
        if (e.appLabel != null && e.title != e.appLabel) {
            drawClipped(canvas, titlePaint, e.title, inset, right, y + geo.titlePx)
            y += DETAIL_TITLE_GAP
        }
        val text = e.detailText ?: e.body ?: e.title
        for (line in wrap(text, right - inset, MAX_DETAIL_LINES)) {
            drawClipped(canvas, bodyPaint, line, inset, right, y + geo.bodyPx)
            y += DETAIL_LEADING
        }
        layoutChips(canvas, w, e, y + DETAIL_GAP)
    }

    /** Word wrap by measurement, not by character count — the pill is 842px, not a terminal. */
    private fun wrap(text: String, width: Float, maxLines: Int): List<String> {
        val out = ArrayList<String>(maxLines)
        val current = StringBuilder()
        var cut = false
        for (word in text.split(' ')) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (bodyPaint.measureText(candidate) <= width) {
                current.setLength(0)
                current.append(candidate)
            } else {
                if (current.isNotEmpty()) out.add(current.toString())
                current.setLength(0)
                current.append(word)
                if (out.size == maxLines) {
                    cut = true
                    break
                }
            }
        }
        if (out.size < maxLines && current.isNotEmpty()) out.add(current.toString())
        while (out.size > maxLines) {
            out.removeAt(out.lastIndex)
            cut = true
        }
        // Say so rather than let the paragraph end mid-thought.
        if (cut && out.isNotEmpty()) {
            val last = out.size - 1
            out[last] = "${out[last]}…"
        }
        return out
    }

    private fun layoutChips(canvas: Canvas, w: Float, e: IslandEvent, top: Float) {
        val labels = ArrayList<String>(3)
        e.actions.forEach { labels.add(shortLabel(it.title)) }
        if (e.openIntent != null) labels.add("Open")
        if (labels.isEmpty()) {
            chipBoxes.clear()
            return
        }
        chipText.textSize = geo.bodyPx.toFloat()
        val h = CHIP_H
        val gap = 18f
        val widths = labels.map { chipText.measureText(it) + 44f }
        var total = widths.sum() + gap * (labels.size - 1)
        // Too many buttons for one row: drop the extras rather than shrink them into nothing.
        var shown = widths.size
        while (shown > 1 && total > w - 2 * geo.contentInset) {
            total -= widths[shown - 1] + gap
            shown--
        }
        var x = (w - total) / 2f
        chipBoxes.clear()
        for (i in 0 until shown) {
            val box = RectF(x, top, x + widths[i], top + h)
            chipBoxes.add(box)
            chipPaint.color = CHIP_FILL
            chipPaint.alpha = scaledAlpha(CHIP_FILL)
            canvas.drawRoundRect(box, h / 2f, h / 2f, chipPaint)
            chipStroke.color = CHIP_STROKE_COLOR
            chipStroke.alpha = scaledAlpha(CHIP_STROKE_COLOR)
            canvas.drawRoundRect(box, h / 2f, h / 2f, chipStroke)
            chipText.color = TEXT
            chipText.alpha = scaledAlpha(0xFF000000.toInt())
            canvas.drawText(labels[i], box.centerX(), box.centerY() + centre(chipText), chipText)
            x += widths[i] + gap
        }
    }

    /** A photo clipped to a circle, else the sender's initial, else the app icon. */
    private fun drawFace(
        canvas: Canvas,
        art: Drawable?,
        initial: String?,
        left: Float,
        top: Float,
        size: Float,
    ) {
        when {
            art != null -> {
                circle.reset()
                circle.addCircle(
                    left + size / 2f, top + size / 2f, size / 2f,
                    android.graphics.Path.Direction.CW
                )
                val save = canvas.save()
                canvas.clipPath(circle)
                art.setBounds(
                    left.toInt(), top.toInt(), (left + size).toInt(), (top + size).toInt()
                )
                art.alpha = (contentAlpha * 255f).toInt().coerceIn(0, 255)
                bar.set(left, top, left + size, top + size)
                canvas.drawRoundRect(bar, size / 2f, size / 2f, bgPaint)
                art.draw(canvas)
                canvas.restoreToCount(save)
            }

            initial != null -> drawInitial(canvas, initial, left, top, size)

            else -> {
                event?.icon?.setBounds(
                    left.toInt(), top.toInt(), (left + size).toInt(), (top + size).toInt()
                )
                event?.icon?.draw(canvas)
            }
        }
    }

    /**
     * The iOS-style grabber at the bottom of the card. A swipe down from the top band is stolen
     * by the status-bar expansion gesture, so closing needs a target you can see and tap.
     */
    private fun grabberBox(w: Float, h: Float): RectF {
        val gw = 150f
        val gh = 44f
        val cy = h() - gh / 2f - 6f
        return RectF((w - gw) / 2f, cy - gh / 2f, (w + gw) / 2f, cy + gh / 2f)
    }

    private fun drawGrabber(canvas: Canvas, w: Float, h: Float) {
        val box = grabberBox(w, h)
        grabPaint.color = PP_BG
        grabPaint.alpha = scaledAlpha(PP_BG)
        canvas.drawRoundRect(box, 22f, 22f, grabPaint)
        // The chevron itself, centred.
        val cx = box.centerX()
        val cy = box.centerY()
        val s = 13f
        // Points up: the card closes with a swipe up, and down reads as "there is more below".
        ppPath.reset()
        ppPath.moveTo(cx - s, cy + s * 0.5f)
        ppPath.lineTo(cx, cy - s * 0.6f)
        ppPath.lineTo(cx + s, cy + s * 0.5f)
        ppGlyph.color = TEXT
        ppGlyph.alpha = scaledAlpha(0xFF000000.toInt())
        ppGlyph.strokeWidth = 5f
        ppGlyph.style = Paint.Style.STROKE
        ppGlyph.strokeCap = Paint.Cap.ROUND
        canvas.drawPath(ppPath, ppGlyph)
        ppGlyph.style = Paint.Style.FILL
    }

    private fun layoutControls(w: Float, h: Float) {
        val side = 96f
        val centre = 118f
        val gap = 38f
        val total = side * 2 + centre + gap * 2
        var x = (w - total) / 2f
        val cy = lineY(w) + CONTROLS_BELOW_LINE
        controlBoxes.clear()
        controlBoxes.add(RectF(x, cy - side / 2f, x + side, cy + side / 2f)); x += side + gap
        controlBoxes.add(RectF(x, cy - centre / 2f, x + centre, cy + centre / 2f)); x += centre + gap
        controlBoxes.add(RectF(x, cy - side / 2f, x + side, cy + side / 2f))
    }

    private fun drawControls(canvas: Canvas, playing: Boolean) {
        for (i in controlBoxes.indices) {
            val box = controlBoxes[i]
            // Same translucent disc for all three. The centre one used to be filled opaque white,
            // which ate the white glyph and left a blank circle.
            ppPaint.color = PP_BG
            ppPaint.alpha = scaledAlpha(PP_BG)
            canvas.drawCircle(box.centerX(), box.centerY(), box.width() / 2f, ppPaint)
            when (i) {
                0 -> drawSkip(canvas, box, backward = true)
                1 -> drawPlayOrPause(canvas, box, playing)
                2 -> drawSkip(canvas, box, backward = false)
            }
        }
    }

    /**
     * Two targets for a call — answer left, hang up right — laid out where the player puts its
     * row, and hit-tested by `onAction(index)` against the dialer's own notification actions.
     */
    private fun layoutCallControls(w: Float, count: Int) {
        val side = CALL_BUTTON_D
        val gap = 70f
        // Only as many discs as the dialer actually gave actions. Two empty circles in a card that
        // only has "End call" is what made the running call look broken.
        val n = count.coerceIn(1, 2)
        val total = side * n + gap * (n - 1)
        var x = (w - total) / 2f
        val cy = geo.pillH + CALL_BUTTONS_Y
        controlBoxes.clear()
        repeat(n) {
            controlBoxes.add(RectF(x, cy - side / 2f, x + side, cy + side / 2f))
            x += side + gap
        }
    }

    private fun drawCallControls(canvas: Canvas, actionCount: Int) {
        val glyph = ModuleResources.callIcon(context)
        for (i in 0 until minOf(controlBoxes.size, actionCount)) {
            val box = controlBoxes[i]
            // Green to answer, red to hang up: the two colours the dialer itself uses, so the
            // buttons read without a label.
            val tint = if (i == 0) CALL_ANSWER else CALL_END
            ppPaint.color = tint
            ppPaint.alpha = scaledAlpha(tint)
            canvas.drawCircle(box.centerX(), box.centerY(), box.width() / 2f, ppPaint)
            if (glyph == null) continue
            val save = canvas.save()
            if (i == 1) {
                // The same handset, turned the way the dialer turns it for end-call.
                canvas.rotate(135f, box.centerX(), box.centerY())
            }
            val size = box.width() * 0.46f
            glyph.setBounds(
                (box.centerX() - size / 2f).toInt(), (box.centerY() - size / 2f).toInt(),
                (box.centerX() + size / 2f).toInt(), (box.centerY() + size / 2f).toInt()
            )
            glyph.alpha = (contentAlpha * 255f).toInt().coerceIn(0, 255)
            glyph.draw(canvas)
            canvas.restoreToCount(save)
        }
    }

    /** |◀ and ▶| */
    private fun drawSkip(canvas: Canvas, box: RectF, backward: Boolean) {        val s = box.width() * 0.19f
        ppGlyph.color = TEXT
        ppGlyph.alpha = scaledAlpha(0xFF000000.toInt())
        val dir = if (backward) -1f else 1f
        val cx = box.centerX()
        ppPath.reset()
        ppPath.moveTo(cx - dir * s * 0.2f, box.centerY() - s * 1.5f)
        ppPath.lineTo(cx - dir * s * 0.2f, box.centerY() + s * 1.5f)
        ppPath.lineTo(cx + dir * s * 1.3f, box.centerY())
        ppPath.close()
        canvas.drawPath(ppPath, ppGlyph)
        val barW = s * 0.42f
        canvas.drawRect(
            cx - dir * s * 1.9f - (if (backward) barW else 0f), box.centerY() - s * 1.5f,
            cx - dir * s * 1.9f + (if (backward) 0f else barW), box.centerY() + s * 1.5f, ppGlyph
        )
    }

    private fun drawTen(canvas: Canvas, box: RectF, forward: Boolean) {
        chipText.color = TEXT
        chipText.alpha = scaledAlpha(0xFF000000.toInt())
        chipText.textSize = box.height() * 0.30f
        canvas.drawText(
            if (forward) "+10" else "-10",
            box.centerX(), box.centerY() + centre(chipText), chipText
        )
    }

    /**
     * The card's background is the artwork of whatever is playing, scaled to cover, with a dark
     * scrim over it so the title and the line still read. A plain black card looked like a
     * spreadsheet; this is what makes it look like a player.
     */
    private fun drawPlayerBackdrop(canvas: Canvas, w: Float, h: Float, corner: Float): Boolean {
        val art = event?.avatar ?: return false
        val save = canvas.save()
        bar.set(0f, 0f, w, h)
        val clip = android.graphics.Path()
        clip.addRoundRect(bar, corner, corner, android.graphics.Path.Direction.CW)
        canvas.clipPath(clip)
        val intrinsic = art.intrinsicWidth.coerceAtLeast(1).toFloat()
        val ratio = maxOf(w / intrinsic, h / art.intrinsicHeight.coerceAtLeast(1))
        val dw = (intrinsic * ratio)
        val dh = (art.intrinsicHeight.coerceAtLeast(1) * ratio)
        art.setBounds(
            ((w - dw) / 2f).toInt(), ((h - dh) / 2f).toInt(),
            ((w + dw) / 2f).toInt(), ((h + dh) / 2f).toInt()
        )
        art.alpha = (contentAlpha * 255f).toInt().coerceIn(0, 255)
        art.draw(canvas)
        canvas.restoreToCount(save)
        scrimPaint.color = BACKDROP_SCRIM
        scrimPaint.alpha = (contentAlpha * 210f).toInt().coerceIn(0, 255)
        bar.set(0f, 0f, w, h)
        canvas.drawRoundRect(bar, corner, corner, scrimPaint)
        return true
    }

    private fun drawPlayOrPause(canvas: Canvas, box: RectF, playing: Boolean) {
        ppGlyph.color = TEXT
        ppGlyph.alpha = scaledAlpha(0xFF000000.toInt())
        val s = box.width() * 0.30f
        if (playing) {
            val gap = s * 0.36f
            canvas.drawRect(
                box.centerX() - s, box.centerY() - s * 1.15f,
                box.centerX() - gap, box.centerY() + s * 1.15f, ppGlyph
            )
            canvas.drawRect(
                box.centerX() + gap, box.centerY() - s * 1.15f,
                box.centerX() + s, box.centerY() + s * 1.15f, ppGlyph
            )
        } else {
            ppPath.reset()
            ppPath.moveTo(box.centerX() - s * 0.7f, box.centerY() - s * 1.15f)
            ppPath.lineTo(box.centerX() - s * 0.7f, box.centerY() + s * 1.15f)
            ppPath.lineTo(box.centerX() + s * 1.05f, box.centerY())
            ppPath.close()
            canvas.drawPath(ppPath, ppGlyph)
        }
    }

    /** The Do-Not-Disturb toggle, parked at the far edge of the capsule. */
    private fun drawBell(canvas: Canvas, h: Float) = drawBell(canvas, h, bellBox(), BELL_D)

    /** Same control at any place and size — the list island wants it big and on the left. */
    private fun drawBell(canvas: Canvas, h: Float, box: RectF, diameter: Float) {
        val save = canvas.save()
        if (bellShake > 0f) {
            // A decaying wobble, so tapping it visibly answers.
            val angle = (Math.sin(bellShake * Math.PI * 3) * 16.0 * (1.0 - bellShake)).toFloat()
            canvas.rotate(angle, box.centerX(), box.centerY())
        }
        val base = if (dndOn) ACCENT_BELL_ON else BELL_IDLE
        bellPaint.color = base
        bellPaint.alpha = scaledAlpha(base)
        canvas.drawCircle(box.centerX(), box.centerY(), diameter / 2f, bellPaint)
        val glyph = ModuleResources.bellIcon(context, dndOn)
        if (glyph != null) {
            val pad = diameter * 0.24f
            val size = diameter - pad * 2f
            glyph.setBounds(
                (box.centerX() - size / 2f).toInt(), (box.centerY() - size / 2f).toInt(),
                (box.centerX() + size / 2f).toInt(), (box.centerY() + size / 2f).toInt()
            )
            glyph.alpha = (contentAlpha * 255f).toInt().coerceIn(0, 255)
            glyph.draw(canvas)
        } else {
            bellGlyph.alpha = (contentAlpha * 255f).toInt().coerceIn(0, 255)
            canvas.drawText("o", box.centerX() - 8f, box.centerY() + centre(bellGlyph), bellGlyph)
        }
        canvas.restoreToCount(save)
    }

    /** The big bell beside the app name, centred on the header band rather than the whole card. */
    private fun bellBoxList(): RectF {
        val d = BELL_LIST_D
        val inset = geo.contentInset.toFloat()
        return RectF(inset, (geo.pillH - d) / 2f, inset + d, (geo.pillH + d) / 2f)
    }

    /** One decaying wobble after the bell is tapped, so the tap is not silently swallowed. */
    fun shakeBell() {
        bellShaker?.cancel()
        bellShaker = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420L
            addUpdateListener {
                bellShake = it.animatedFraction
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    bellShake = 0f
                    invalidate()
                }
            })
            start()
        }
    }

    /**
     * The sender's initial in a flat coloured disc. The colour comes from the letter itself, not
     * from the title: a grouped capsule rewrites its title to the app's name, and seeding on that
     * made the disc change colour the moment a second message arrived.
     */
    private fun drawInitial(canvas: Canvas, letter: String, left: Float, top: Float, size: Float) {
        val cx = left + size / 2f
        val cy = top + size / 2f
        initialPaint.color =
            INITIAL_PALETTE[(letter.hashCode() and Int.MAX_VALUE) % INITIAL_PALETTE.size]
        canvas.drawCircle(cx, cy, size / 2f, initialPaint)
        initialTextPaint.textSize = size * 0.5f
        canvas.drawText(letter, cx, cy + centre(initialTextPaint), initialTextPaint)
    }

    /** "Mark as read" becomes "Mark", "Previous" becomes "Prev" — the zone is only ~200px. */
    private fun shortLabel(title: String?): String {
        val first = title?.trim()?.split(" ")?.firstOrNull().orEmpty().ifBlank { "Open" }
        return if (first.length > 9) first.substring(0, 9) else first
    }

    private fun maskHole(canvas: Canvas, w: Float) {
        val pillLeft = (geo.holeCx - w.toInt() / 2f).toFloat()
        val l = (geo.holeLeft - pillLeft - 6f).coerceAtLeast(0f)
        val r = (geo.holeRight - pillLeft + 6f).coerceAtMost(w)
        if (r <= l) return
        // A rounded band, not a hard rectangle, so the mask blends into the pill body.
        bar.set(l, 0f, r, h().toFloat())
        canvas.drawRoundRect(bar, geo.corner.toFloat(), geo.corner.toFloat(), holeMask)
    }

    private fun applyContentAlpha() {
        val a = (contentAlpha * 255).toInt()
        titlePaint.alpha = a
        bodyPaint.alpha = a
        trailingPaint.alpha = a
        // The bell's own fill is translucent by design (0x33 idle); overwriting it with the
        // content alpha turned the whole button into an opaque white disc with a white glyph
        // inside, which is the flat dot in the corner of the capsule.
        bellPaint.alpha = scaledAlpha(if (dndOn) ACCENT_BELL_ON else BELL_IDLE)
        bellGlyph.alpha = a
        initialPaint.alpha = a
        initialTextPaint.alpha = a
        event?.icon?.alpha = a
    }

    /** Content fade applied on top of a colour's own alpha, not instead of it. */
    private fun scaledAlpha(argb: Int): Int =
        (((argb ushr 24) * contentAlpha).toInt()).coerceIn(0, 255)

    private fun bellBox(): RectF {
        val w = w().toFloat()
        val d = BELL_D
        val band = bandH()
        return RectF(w - geo.contentInset - d, (band - d) / 2f, w - geo.contentInset, (band + d) / 2f)
    }

    /** Which line a touch landed on; rows are a uniform band below the header. */
    private fun rowIndexOf(y: Float): Int {
        val last = shownRows.size - 1
        return if (last < 0) 0 else ((y - geo.pillH) / ROW_H).toInt().coerceIn(0, last)
    }

    private fun centre(paint: Paint): Float = -(paint.ascent() + paint.descent()) / 2f

    /**
     * The capsule edge: three strokes of decreasing alpha, which reads as a neon rim without
     * needing a shadow layer (shadow layers force a software layer, and this runs in SystemUI).
     */
    private fun drawGlow(canvas: Canvas, w: Float, h: Float) {
        if (glowAlpha <= 0.01f) return
        val r = geo.corner.toFloat()
        var inset = 1.5f
        var strength = glowAlpha
        for (i in 0 until 3) {
            glowPaint.color = GLOW
            glowPaint.alpha = (strength * 255 * (1f - i * 0.28f)).toInt().coerceIn(0, 255)
            glowPaint.strokeWidth = 2f + i * 1.5f
            bar.set(inset, inset, w - inset, h - inset)
            canvas.drawRoundRect(bar, r, r, glowPaint)
            inset += 2.5f
            strength *= 0.72f
        }
    }

    /**
     * The collapse light: a bright head sweeping around the camera hole with a short dim tail,
     * then the whole thing dissolves.
     */
    private fun drawCollapseRing(canvas: Canvas, w: Float, h: Float) {
        val radius = geo.ringWidth / 2f
        val cx = w / 2f
        // Centred on the camera hole, not on the view: when the player card was open the window
        // was 300px tall, so h/2 put the light well below the notch and it looked broken.
        val cy = geo.holeH / 2f
        ringOval.set(cx - radius, cy - radius, cx + radius, cy + radius)
        ringPaint.strokeWidth = 8f
        ringPaint.alpha = (ringAlpha * 90f).toInt().coerceIn(0, 255)
        canvas.drawArc(ringOval, ringSweep - 150f, 150f, false, ringPaint)
        ringPaint.alpha = (ringAlpha * 255f).toInt().coerceIn(0, 255)
        canvas.drawArc(ringOval, ringSweep - 42f, 42f, false, ringPaint)
    }

    private fun drawClipped(canvas: Canvas, paint: TextPaint, text: String, left: Float, right: Float, baseline: Float) {
        val avail = right - left
        if (avail <= 0f) return
        val shown = TextUtils.ellipsize(text, paint, avail, TextUtils.TruncateAt.END)
        val save = canvas.save()
        canvas.clipRect(left, 0f, right, h().toFloat())
        canvas.drawText(shown, 0, shown.length, left, baseline, paint)
        canvas.restoreToCount(save)
    }

    /**
     * A short buzz where an interaction changes state. `performHapticFeedback` on the View goes
     * through the SystemUI process's own haptics, so no permission and no VibratorManager — and
     * it is the only acknowledgement a touch on a 127px pill can get.
     */
    private fun tick(strong: Boolean = false) {
        if (!Config.current.haptics) return
        // Logged because nobody can feel a buzz from a PC, and "did the tap even land" needs an
        // answer that is not a phone in hand.
        Logx.d("haptic ${if (strong) "strong" else "light"}")
        runCatching {
            performHapticFeedback(
                if (strong) HapticFeedbackConstants.VIRTUAL_KEY
                else HapticFeedbackConstants.CLOCK_TICK
            )
        }
    }

    override fun onTouchEvent(message: MotionEvent): Boolean {
        val e = event ?: return false
        when (message.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = message.x
                downY = message.y
                swiping = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dy = message.y - downY
                if (dy < -12f) {
                    swiping = true
                    lifted = dy.coerceAtLeast(-h().toFloat())
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val dy = message.y - downY
                lifted = 0f
                val wasSwipe = swiping
                swiping = false
                invalidate()

                // Ambient: the ring is the only thing here, and a tap brings the capsule back.
                if (ambient) {
                    tick()
                    onOpen?.invoke()
                    return true
                }
                // One message unfolded: a chip fires its own action, the band folds the card.
                if (detailMode && e.source == Source.NOTIFICATION) {
                    if ((wasSwipe && dy < -SWIPE_DISMISS) ||
                        grabberBox(w().toFloat(), h().toFloat()).contains(message.x, message.y) ||
                        message.y < geo.pillH
                    ) {
                        tick()
                        onClosePlayer?.invoke()
                        return true
                    }
                    val chip = chipBoxes.indexOfFirst { it.contains(message.x, message.y) }
                    if (chip >= 0) {
                        tick()
                        if (chip < e.actions.size) onAction?.invoke(chip) else onOpenApp?.invoke()
                    }
                    return true
                }
                // A call: the two discs are the dialer's own actions, nothing else is tappable.
                if (callMode && e.source == Source.CALL) {
                    if ((wasSwipe && dy < -SWIPE_DISMISS) ||
                        grabberBox(w().toFloat(), h().toFloat()).contains(message.x, message.y)
                    ) {
                        tick()
                        onClosePlayer?.invoke()
                        return true
                    }
                    val hit = controlBoxes.indexOfFirst { it.contains(message.x, message.y) }
                    if (hit >= 0) {
                        tick(strong = true)
                        onAction?.invoke(hit)
                    }
                    return true
                }
                // Unfolded burst: a line swiped sideways is a line dismissed, and only that line.
                if (listOpen()) {
                    val dx = message.x - downX
                    if (abs(dx) > SWIPE_DISMISS) {
                        val row = shownRows.asReversed().getOrNull(rowIndexOf(downY))
                        if (row != null) {
                            tick(strong = true)
                            onRowDismiss?.invoke(row.key)
                            return true
                        }
                    }
                    if (bellBoxList().contains(message.x, message.y)) {
                        tick()
                        onToggleDnd?.invoke()
                        shakeBell()
                        return true
                    }
                    val closesTheCard =
                        grabberBox(w().toFloat(), h().toFloat()).contains(message.x, message.y) ||
                            (wasSwipe && dy < -SWIPE_DISMISS) ||
                            message.y < geo.pillH
                    if (closesTheCard) {
                        // Up closes, same as the player: down belongs to the notification shade.
                        tick()
                        onClosePlayer?.invoke()
                        return true
                    }
                    return true
                }
                // Opened card: down dismisses it, because tapping a small target was hopeless.
                if (playerMode && e.source == Source.MEDIA) {
                    // Up closes the card back to the ring — down belongs to the notification
                    // shade, which swallows any drag that starts in the top band. The grabber is
                    // there for people who would rather tap.
                    if (wasSwipe && dy < -SWIPE_DISMISS) {
                        tick()
                        onClosePlayer?.invoke()
                        return true
                    }
                    if (grabberBox(w().toFloat(), h().toFloat()).contains(message.x, message.y)) {
                        tick()
                        onClosePlayer?.invoke()
                        return true
                    }
                    when (controlBoxes.indexOfFirst { it.contains(message.x, message.y) }) {
                        0 -> { tick(); onPrev?.invoke() }
                        1 -> { tick(); onPlayPause?.invoke() }
                        2 -> { tick(); onNext?.invoke() }
                    }
                    return true
                }
                if (wasSwipe && dy < -SWIPE_DISMISS) {
                    tick(strong = true)
                    onDismiss?.invoke()
                    return true
                }
                if (abs(dy) > 24f || abs(message.x - downX) > 24f) return true

                // Opened as the player: centre-right is play/pause, the halves seek by 10s.
                if (playerMode && e.source == Source.MEDIA) {
                    if (mediaBoxes.firstOrNull()?.contains(message.x, message.y) == true) {
                        onPlayPause?.invoke()
                    } else if (message.x < w() / 2f) {
                        onSeek?.invoke(-10_000L)
                    } else {
                        onSeek?.invoke(10_000L)
                    }
                    return true
                }
                if (e.showBell && bellBox().contains(message.x, message.y)) {
                    tick()
                    onToggleDnd?.invoke()
                    return true
                }
                val chip = mediaBoxes.indexOfFirst { it.contains(message.x, message.y) }
                if (chip >= 0) {
                    tick()
                    onAction?.invoke(chip)
                    return true
                }
                // Left half opens the app, right half fires the first carried-over action.
                val pillLeft = geo.holeCx - w() / 2f
                tick()
                if (message.x + pillLeft >= geo.rightZoneLeft(w()).toFloat() && e.actions.isNotEmpty()) {
                    onAction?.invoke(0)
                } else {
                    onOpen?.invoke()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                lifted = 0f
                swiping = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(message)
    }

    companion object {
        // Translucent body + solid mask over the hole. The blur behind the window (IslandWindow)
        // is what makes the translucent part read as glass rather than as grey.
        private const val BG_TRANSLUCENT = 0xE0000000.toInt()
        private const val BG_SOLID = 0xFF000000.toInt()
        private const val TEXT = 0xFFFFFFFF.toInt()
        private const val TEXT_DIM = 0xFFC7C7C7.toInt()
        private const val BELL_IDLE = 0x33FFFFFF
        private const val ACCENT_BELL_ON = 0xFF8A65FF.toInt()
        private const val GLOW = 0xFF4DA6FF.toInt()
        private const val CHIP_STROKE_COLOR = 0x40FFFFFF
        private const val CHIP_H = 62f
        // Flat, mid-saturation discs for the sender's initial: readable on the dark body without
        // competing with the neon rim, and stable per name because the index is a hash.
        private val INITIAL_PALETTE = intArrayOf(
            0xFF5B8DEF.toInt(), 0xFF2FB47C.toInt(), 0xFFE0733A.toInt(),
            0xFF9B59D0.toInt(), 0xFFD14D72.toInt(), 0xFF3AAFB9.toInt(),
        )
        private const val BELL_D = 56f
        private const val PP_D = 62f
        private const val PP_BG = 0x40FFFFFF
        private const val PP_BG_ON = 0x73FFFFFF
        private const val PLAYER_CORNER = 46f
        // 66% black: enough that white text still reads over a bright cover, light enough that
        // the artwork is actually visible — at 0xCC the card looked like a plain black box.
        private const val BACKDROP_SCRIM = 0xA8000000.toInt()
        // Everything below the band has to fit in (PLAYER_H - pillH) = 173px: the line at 46,
        // the 118px button row centred at 118, leaving 7px of margin at the bottom.
        private const val LINE_IN_CARD = 46f
        private const val CONTROLS_BELOW_LINE = 72f
        /** Detail card: top padding, line leading, the gap before the chips, and the line cap. */
        private const val DETAIL_TOP = 26f
        private const val DETAIL_TITLE_GAP = 40f
        private const val DETAIL_LEADING = 34f
        private const val DETAIL_GAP = 26f
        private const val MAX_DETAIL_LINES = 4
        private const val CHIP_FILL = 0x2AFFFFFF
        /** One message line in the unfolded burst, and the face beside it. */
        const val ROW_H = 92
        const val ROW_AVATAR = 64
        private const val ROW_DIVIDER = 0x24FFFFFF
        // Bigger than the capsule bell on purpose: it is the toggle for the whole list, and the
        // 56px one was already hard to hit on a pill.
        const val BELL_LIST_D = 88f
        /** The call card: two discs under the band, in the dialer's own colours. */
        private const val CALL_BUTTON_D = 120f
        private const val CALL_BUTTONS_Y = 100f
        private const val CALL_ANSWER = 0xFF30D158.toInt()
        private const val CALL_END = 0xFFFF453A.toInt()
        private const val PROGRESS_GREEN = 0xFF30D158.toInt()
        private const val PROGRESS_TRACK = 0xFFFFFFFF.toInt()
        private const val PROGRESS_TRACK_DIM = 0x33FFFFFF
        private const val SWIPE_DISMISS = 60f
    }
}
