package com.lockedin.morning

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/** Shared look for the home screen: deep navy, teal = learning, red = scrolling, ember = actions. */
object Ui {
    const val BG = 0xFF0E1420.toInt()
    const val SURFACE = 0xFF172033.toInt()
    const val SURFACE2 = 0xFF223049.toInt()
    const val TEXT = 0xFFEEF2F8.toInt()
    const val MUTED = 0xFF8A96AB.toInt()
    const val TEAL = 0xFF3DDC97.toInt()
    const val RED = 0xFFFF5A5F.toInt()
    const val AMBER = 0xFFF5B642.toInt()
    const val EMBER = 0xFFFF7A1A.toInt()
    val NUM: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    val HEAD: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
}

/** A thick ring with a big number in the middle and a small caption under it. */
class ProgressRing(ctx: Context) : View(ctx) {
    var fraction = 0f; set(v) { field = v; invalidate() }
    var color = Ui.TEAL; set(v) { field = v; invalidate() }
    var big = ""; set(v) { field = v; invalidate() }
    var small = ""; set(v) { field = v; invalidate() }
    var emoji = false
    var stroke = 16f
    private val d = ctx.resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Ui.SURFACE2; strokeCap = Paint.Cap.ROUND }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val bigP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = Ui.TEXT; typeface = Ui.NUM }
    private val smallP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = Ui.MUTED }
    private val box = RectF()

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height).toFloat()
        val sw = stroke * d
        track.strokeWidth = sw; arc.strokeWidth = sw; arc.color = color
        val inset = sw / 2 + 2 * d
        box.set((width - s) / 2 + inset, (height - s) / 2 + inset, (width + s) / 2 - inset, (height + s) / 2 - inset)
        c.drawArc(box, 0f, 360f, false, track)
        val f = fraction.coerceIn(0f, 1f)
        if (f > 0f) c.drawArc(box, -90f, (f * 360f).coerceAtLeast(6f), false, arc)
        bigP.textSize = if (emoji) s * 0.30f else s * 0.26f
        c.drawText(big, width / 2f, height / 2f + (if (small.isEmpty()) s * 0.09f else s * 0.03f), bigP)
        if (small.isNotEmpty()) {
            smallP.textSize = s * 0.095f
            c.drawText(small, width / 2f, height / 2f + s * 0.17f, smallP)
        }
    }
}

/** Mon–Sun dots: filled = goal hit, red ring = missed, ember ring = today, dim = still to come. */
class WeekDots(ctx: Context) : View(ctx) {
    var values: List<Float> = List(7) { 0f }; set(v) { field = v; invalidate() } // fraction of the daily goal
    var today = 1 // 1..7
    private val d = ctx.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Ui.HEAD }
    private val letters = listOf("M", "T", "W", "T", "F", "S", "S")

    override fun onDraw(c: Canvas) {
        val col = width / 7f
        val r = minOf(col * 0.32f, 15 * d)
        val cy = r + 2 * d
        ring.strokeWidth = 2.5f * d
        txt.textSize = 12 * d
        for (i in 0 until 7) {
            val cx = col * i + col / 2
            val v = values.getOrElse(i) { 0f }
            val day = i + 1
            when {
                v >= 1f -> { fill.color = Ui.TEAL; c.drawCircle(cx, cy, r, fill) }
                day < today -> {
                    fill.color = Ui.SURFACE2; c.drawCircle(cx, cy, r, fill)
                    if (v > 0f) { fill.color = Ui.TEAL; c.drawCircle(cx, cy, r * v.coerceIn(0.15f, 1f), fill) }
                    ring.color = Ui.RED; c.drawCircle(cx, cy, r, ring)
                }
                day == today -> {
                    fill.color = Ui.SURFACE2; c.drawCircle(cx, cy, r, fill)
                    if (v > 0f) { fill.color = Ui.TEAL; c.drawCircle(cx, cy, r * v.coerceIn(0.15f, 1f), fill) }
                    ring.color = Ui.EMBER; c.drawCircle(cx, cy, r, ring)
                }
                else -> { fill.color = Ui.SURFACE2; c.drawCircle(cx, cy, r, fill) }
            }
            txt.color = if (day == today) Ui.TEXT else Ui.MUTED
            c.drawText(letters[i], cx, cy + r + 16 * d, txt)
        }
    }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        setMeasuredDimension(width, (54 * d).toInt())
    }
}
