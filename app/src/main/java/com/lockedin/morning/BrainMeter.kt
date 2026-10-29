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
    const val GREEN = 0xFF2E9E5B.toInt()
    const val AMBER = 0xFFF2A33A.toInt()
    const val RED = 0xFFD64545.toInt()
    private const val MUTED = 0xFFA0A0A8.toInt()

    data class Fact(val text: String, val source: String)

    /** Real findings (see README for links). Worded carefully: links, not proof of cause. */
    val FACTS = listOf(
        Fact("A 2025 review of 70 studies (~98,000 people) found heavier short-video use (TikTok, Reels, Shorts) was linked to weaker attention and self-control, most strongly for compulsive use.",
            "Nguyen et al., Psychological Bulletin, 2025"),
        Fact("Students who cut social media to 30 minutes a day for 3 weeks had less depression and loneliness than those who didn't.",
            "Hunt et al., Journal of Social and Clinical Psychology, 2018"),
        Fact("Average focus on a single screen dropped from about 2½ minutes in 2004 to about 47 seconds in recent studies.",
            "Gloria Mark, UC Irvine"),
        Fact("Getting fully back into a task after an interruption can take almost half an hour.",
            "Gloria Mark, UC Irvine"),
        Fact("Teens with 4+ hours of daily non-school screen time were about 2.5× as likely to report depression symptoms and 1.6× as likely to have irregular sleep.",
            "US CDC, Preventing Chronic Disease, 2025"),
        Fact("The same 2025 review found time spent mattered less than how hooked people felt. Compulsive scrolling showed the strongest links to stress and anxiety.",
            "Nguyen et al., Psychological Bulletin, 2025"),
        Fact("Australians average around 41 hours a week online, with nearly half of it on social media.",
            "We Are Social / Meltwater, Digital 2026: Australia"),
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

    /** A full card for the home screen (or a compact one for the check-in). */
    fun card(ctx: Context, p: Prefs, compact: Boolean = false): LinearLayout {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        fun label(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
            text = t; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
        }
        val todaySec = Scroll.timeByApp(ctx, 1).values.sum()
        val weekSec = Scroll.timeByApp(ctx, 7).values.sum()
        val todayMin = (todaySec / 60).toInt()
        val limit = p.scrollLimitMin.coerceAtLeast(1)
        val frac = (todayMin.toFloat() / limit)
        val color = colorFor(frac)

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply { setColor(0xFF1C1C1E.toInt()); cornerRadius = dp(12).toFloat() }
        }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val ring = Ring(ctx).apply {
            fraction = frac
            centerText = if (frac >= 1f) "💀" else "🧠"
            subText = "$todayMin / $limit min"
        }
        val size = if (compact) dp(110) else dp(140)
        row.addView(ring, LinearLayout.LayoutParams(size, size))

        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        val headline = when {
            frac >= 1f -> "Over your limit"
            frac >= 0.5f -> "Getting close"
            todayMin == 0 -> "Clean so far"
            else -> "You're fine"
        }
        texts.addView(label(headline, 20f, color, true))
        texts.addView(label("$todayMin min scrolled today", 15f, Color.WHITE), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        // Honest projections from your own numbers.
        val tracked = Scroll.daysTracked(ctx, 7)
        val dailyAvgMin = weekSec / 60 / tracked
        val daysPerYear = dailyAvgMin * 365 / 60 / 24
        if (dailyAvgMin >= 1) texts.addView(label(
            "At your ${if (tracked == 1) "current" else "$tracked-day"} pace (${dailyAvgMin.toInt()} min/day) that's ${"%.1f".format(daysPerYear)} full days a year.",
            13f, MUTED), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        if (todayMin >= 20) texts.addView(label(
            "Today's scrolling = ${todayMin / 40} morning video${if (todayMin / 40 == 1) "" else "s"} you could have watched.",
            13f, MUTED), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(row)

        // One research finding, rotating daily.
        val dayIdx = (System.currentTimeMillis() / 86_400_000L).toInt()
        val f = FACTS[Math.floorMod(dayIdx + (if (compact) 3 else 0), FACTS.size)]
        card.addView(View(ctx).apply { setBackgroundColor(0xFF2C2C30.toInt()) },
            LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(14) })
        card.addView(label("🔬  ${f.text}", 14f, 0xFFDDDDDD.toInt()), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        card.addView(label(f.source, 12f, MUTED), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        if (!compact) card.addView(label("Studies show links, not proof that scrolling causes these. Your numbers above are exact.", 11f, 0xFF77777C.toInt()),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        return card
    }
}
