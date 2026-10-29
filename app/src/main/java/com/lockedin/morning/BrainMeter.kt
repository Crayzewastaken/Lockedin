package com.lockedin.morning

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * "Brain meter": a ring that fills with today's scrolling against your daily limit.
 * Green under half, amber near the limit, red (💀) over it. All numbers are your own usage;
 * the research lines are real findings, worded as associations (not "X times dumber").
 */
object BrainMeter {
    val GREEN = Ui.TEAL
    val AMBER = Ui.AMBER
    val RED = Ui.RED
    private const val MUTED = 0xFFA0A0A8.toInt()

    data class Fact(val text: String, val source: String)

    /** Real findings, kept to one line. These are links, not proof of cause. */
    val FACTS = listOf(
        Fact("Heavy short-video use is linked to weaker attention and self-control.", "Review of 70 studies, Psychological Bulletin 2025"),
        Fact("Cutting social media to 30 min a day lowered depression and loneliness in 3 weeks.", "Hunt et al., 2018"),
        Fact("Average focus on one screen: 2½ minutes in 2004, about 47 seconds now.", "Gloria Mark, UC Irvine"),
        Fact("Getting back into a task after an interruption can take almost 30 minutes.", "Gloria Mark, UC Irvine"),
        Fact("Teens on screens 4+ hours a day reported depression symptoms about 2.5× as often.", "US CDC, 2025"),
        Fact("Compulsive scrolling shows stronger links to stress and anxiety than time spent alone.", "Psychological Bulletin 2025"),
    )

    fun colorFor(fraction: Float): Int = when {
        fraction >= 1f -> RED
        fraction >= 0.5f -> AMBER
        else -> GREEN
    }

    /** The ring itself. */
    class Ring(ctx: Context) : View(ctx) {
        var fraction = 0f
            set(v) { field = v; invalidate() }
        var centerText = "🧠"
        var subText = ""
        private val d = ctx.resources.displayMetrics.density
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 14 * d; color = 0xFF2C2C30.toInt(); strokeCap = Paint.Cap.ROUND }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 14 * d; strokeCap = Paint.Cap.ROUND }
        private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD }
        private val box = RectF()

        override fun onDraw(c: Canvas) {
            val s = minOf(width, height).toFloat()
            val pad = 10 * d
            box.set((width - s) / 2 + pad, (height - s) / 2 + pad, (width + s) / 2 - pad, (height + s) / 2 - pad)
            c.drawArc(box, 0f, 360f, false, track)
            arc.color = colorFor(fraction)
            val sweep = (fraction.coerceIn(0f, 1f) * 360f).coerceAtLeast(if (fraction > 0f) 4f else 0f)
            c.drawArc(box, -90f, sweep, false, arc)
            big.textSize = s * 0.30f
            c.drawText(centerText, width / 2f, height / 2f + s * 0.05f, big)
            small.textSize = s * 0.10f
            c.drawText(subText, width / 2f, height / 2f + s * 0.24f, small)
        }
    }

    /** Compact card: ring, two lines, one finding. */
    fun card(ctx: Context, p: Prefs, compact: Boolean = false): LinearLayout {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        fun label(t: String, size: Float, color: Int, tf: Typeface? = null) = TextView(ctx).apply {
            text = t; textSize = size; setTextColor(color); if (tf != null) typeface = tf
        }
        val todayMin = (Scroll.timeByApp(ctx, 1).values.sum() / 60).toInt()
        val tracked = Scroll.daysTracked(ctx, 7)
        val dailyAvgMin = Scroll.timeByApp(ctx, 7).values.sum() / 60 / tracked
        val limit = p.scrollLimitMin.coerceAtLeast(1)
        val frac = todayMin.toFloat() / limit
        val color = colorFor(frac)

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(16))
            background = GradientDrawable().apply { setColor(Ui.SURFACE); cornerRadius = dp(22).toFloat() }
        }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(ProgressRing(ctx).apply {
            fraction = frac; this.color = color; emoji = true; stroke = 9f
            big = if (frac >= 1f) "💀" else "🧠"
        }, LinearLayout.LayoutParams(if (compact) dp(84) else dp(92), if (compact) dp(84) else dp(92)))
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, 0, 0) }
        texts.addView(label("$todayMin of $limit min", 24f, color, Ui.NUM))
        texts.addView(label("scrolled today", 13f, Ui.MUTED))
        if (dailyAvgMin >= 1) texts.addView(label("≈ ${"%.1f".format(dailyAvgMin * 365 / 1440)} days a year at this pace", 13f, Ui.TEXT),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(row)

        val dayIdx = (System.currentTimeMillis() / 86_400_000L).toInt()
        val f = FACTS[Math.floorMod(dayIdx + (if (compact) 3 else 0), FACTS.size)]
        card.addView(label(f.text, 14f, Ui.TEXT), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        card.addView(label("${f.source} · a link, not proof", 11f, Ui.MUTED), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
        return card
    }
}
