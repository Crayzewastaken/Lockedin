package com.lockedin.morning

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Shown every 15–20 min of Instagram/TikTok: a forced 5-minute quiz on previous days' videos.
 * While it's up you can't leave it (calls and alarms still work). If there are no saved
 * questions yet, it falls back to a "what did you gain?" card.
 */
class CheckInActivity : Activity() {

    companion object {
        private const val ORANGE = 0xFFFF7A1A.toInt()
        private const val CARD = 0xFF1C1C1E.toInt()
        private const val SELECTED = 0xFF3A2A1C.toInt()
        private const val MUTED = 0xFFA0A0A8.toInt()
        private const val GREEN = 0xFF2E9E5B.toInt()
        const val EXTRA_APP = "app"
        const val EXTRA_MIN = "min"

        val GAINS = listOf(
            "📚 Learned something useful",
            "💬 Talked to friends / family",
            "😂 Had a laugh, needed a break",
            "💡 Got an idea or inspiration",
            "🫠 Nothing really — just scrolling",
        )
    }

    private val ui = Handler(Looper.getMainLooper())
    private var app = ""
    private var minutes = 15
    private var chosen = -1
    private var answered = false
    private lateinit var keepBtn: Button
    private lateinit var note: EditText
    private val chipViews = mutableListOf<TextView>()

    // ---- recall quiz state
    private var recall: List<Bank.Item> = emptyList()
    private val recallAnswers = mutableListOf<Int>()
    private var recallDone = false
    private var tapping = false
    private lateinit var p: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Prefs(this)
        app = intent.getStringExtra(EXTRA_APP) ?: p.recallApp
        minutes = intent.getIntExtra(EXTRA_MIN, 15)
        Scroll.promptShowing = true
        start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Bounced back here while a quiz is running: just carry on with it.
        if (recall.isNotEmpty() && !recallDone) return
        setIntent(intent)
        app = intent.getStringExtra(EXTRA_APP) ?: app
        minutes = intent.getIntExtra(EXTRA_MIN, minutes)
        if (!answered && recall.isEmpty()) return
        answered = false
        start()
    }

    private fun start() {
        recall = Bank.pick(this, p.recallQuestions.coerceIn(1, 20))
        recallAnswers.clear()
        recallDone = false
        if (recall.isEmpty()) {
            build() // nothing saved yet: "what did you gain?" card
            return
        }
        val now = System.currentTimeMillis()
        if (!p.recallActive || p.recallUntil < now) p.recallUntil = now + p.recallSeconds.coerceIn(60, 1800) * 1000L
        p.recallApp = app
        p.recallActive = true
        showQuestion()
    }

    private fun endRecall() {
        p.recallActive = false
    }

    override fun onDestroy() {
        if (recall.isEmpty() || recallDone || answered) Scroll.promptShowing = false
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Deprecated("Must answer the check-in")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (recall.isNotEmpty() && !recallDone) {
            android.widget.Toast.makeText(this, "Finish the quiz first", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (recall.isNotEmpty()) { finishRecall("closed"); return }
        // Back = "I'm done", the honest way out.
        decide("closed")
    }

    // ------------------------------------------------------------------ recall quiz

    private fun showQuestion() {
        ui.removeCallbacksAndMessages(null)
        val i = recallAnswers.size
        if (i >= recall.size || System.currentTimeMillis() >= p.recallUntil) {
            showRecallResult()
            return
        }
        val item = recall[i]
        val q = item.q
        val name = Scroll.appName(this, app)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(32))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(label("$minutes min on $name · Quick recall", 14f, ORANGE, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val timer = label("", 16f, ORANGE, true)
        top.addView(timer)
        col.addView(top)
        col.addView(label("Question ${i + 1} of ${recall.size} · from ${dayLabel(item.day)}: ${item.title}", 13f, MUTED).apply {
            maxLines = 2
        }, lp(8))
        col.addView(label(q.q, 21f, Color.WHITE, true), lp(14))
        val btns = mutableListOf<TextView>()
        q.options.forEachIndexed { k, opt ->
            val b = label(opt, 16f, Color.WHITE).apply {
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = rounded(CARD)
                setOnClickListener { answer(k, btns) }
            }
            btns.add(b)
            col.addView(b, lp(10))
        }
        col.addView(label("You can't leave until this is done (calls still work).", 12f, MUTED).apply {
            gravity = Gravity.CENTER
        }, lp(20))
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFillViewport = true
            addView(col)
        })
        val tick = object : Runnable {
            override fun run() {
                val ms = p.recallUntil - System.currentTimeMillis()
                if (ms <= 0) { showRecallResult(); return }
                val sec = (ms / 1000).toInt() + 1
                timer.text = String.format("%d:%02d", sec / 60, sec % 60)
                ui.postDelayed(this, 500)
            }
        }
        ui.post(tick)
    }

    private fun answer(k: Int, btns: List<TextView>) {
        if (tapping) return
        tapping = true
        val q = recall[recallAnswers.size].q
        recallAnswers.add(k)
        btns.getOrNull(q.answer)?.background = rounded(GREEN)
        if (k != q.answer) btns.getOrNull(k)?.background = rounded(0xFFD64545.toInt())
        ui.postDelayed({ tapping = false; showQuestion() }, 700)
    }

    private fun showRecallResult() {
        if (recallDone) return
        recallDone = true
        ui.removeCallbacksAndMessages(null)
        endRecall()
        val n = recall.size
        val right = recall.indices.count { recallAnswers.getOrNull(it) == recall[it].q.answer }
        Bank.record(this, recall.indices.associate { recall[it].id to (recallAnswers.getOrNull(it) == recall[it].q.answer) })
        Stats.inc(this, "recallQuizzes")
        Stats.inc(this, "recallCorrect", right)
        Stats.inc(this, "recallQuestions", n)

        val name = Scroll.appName(this, app)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(32))
        }
        col.addView(label(if (right == n) "Sharp. $right/$n ✅" else "$right/$n", 30f, Color.WHITE, true))
        col.addView(label(if (right == n) "You remembered it all." else "Here's what slipped:", 15f, MUTED), lp(6))
        for (i in recall.indices) {
            val it = recall[i]
            if (recallAnswers.getOrNull(i) == it.q.answer) continue
            col.addView(label(it.q.q, 15f, Color.WHITE, true), lp(14))
            col.addView(label("Answer: ${it.q.options[it.q.answer]}", 14f, GREEN), lp(4))
            if (it.q.explain.isNotBlank()) col.addView(label(it.q.explain, 13f, MUTED), lp(2))
        }
        col.addView(button("I'm done, close $name", GREEN) { finishRecall("closed") }, lp(24))
        col.addView(button("Back to $name", CARD) { finishRecall("continued") }, lp(10))
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFillViewport = true
            addView(col)
        })
    }

    private fun finishRecall(decision: String) {
        if (answered) return
        answered = true
        val n = recall.size
        val right = recall.indices.count { recallAnswers.getOrNull(it) == recall[it].q.answer }
        Scroll.logCheckIn(this, app, minutes, "Recall quiz $right/$n", "", decision)
        Stats.inc(this, "checkIns")
        if (decision == "closed") Stats.inc(this, "checkInsClosed")
        Scroll.sinceCheckInSec = 0.0
        Scroll.nextPromptSec = Scroll.newInterval(p)
        Scroll.promptShowing = false
        if (decision == "closed") {
            Scroll.currentApp = ""
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }

    private fun dayLabel(day: String): String {
        if (day == Session.today()) return "today"
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_MONTH, -1)
        val y = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(cal.time)
        if (day == y) return "yesterday"
        return try {
            val d = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(day)
            java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(d!!)
        } catch (_: Exception) { day }
    }

    private fun build() {
        chosen = -1
        chipViews.clear()
        val name = Scroll.appName(this, app)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(56), dp(24), dp(32))
        }
        col.addView(label("$minutes min on $name", 15f, ORANGE, true))
        col.addView(label("What did you gain from it?", 28f, Color.WHITE, true), lp(6))
        col.addView(label("Be honest, it's just for you. Tap one.", 15f, MUTED), lp(6))

        GAINS.forEachIndexed { i, g ->
            val chip = label(g, 16f, Color.WHITE).apply {
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = rounded(CARD)
                setOnClickListener { pick(i) }
            }
            chipViews.add(chip)
            col.addView(chip, lp(10))
        }

        note = EditText(this).apply {
            hint = "What exactly? (optional)"
            setHintTextColor(MUTED)
            setTextColor(Color.WHITE)
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = rounded(CARD)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        col.addView(note, lp(14))

        val done = button("I'm done, close $name", GREEN) { decide("closed") }
        col.addView(done, lp(20))
        keepBtn = button("Keep going", CARD) { decide("continued") }
        keepBtn.isEnabled = false
        keepBtn.alpha = 0.4f
        col.addView(keepBtn, lp(10))
        col.addView(label("Pick an answer first. Once you've done a morning quiz, this becomes a quick quiz on past videos.", 12f, MUTED).apply {
            gravity = Gravity.CENTER
        }, lp(8))

        val sv = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFillViewport = true
            addView(col)
        }
        setContentView(sv)
    }

    private fun pick(i: Int) {
        chosen = i
        chipViews.forEachIndexed { k, v -> v.background = rounded(if (k == i) SELECTED else CARD) }
        ui.removeCallbacksAndMessages(null)
        if (i == GAINS.lastIndex) {
            // "Nothing really": keep going is still allowed, after a 10-second think.
            var left = 10
            keepBtn.isEnabled = false
            keepBtn.alpha = 0.4f
            val r = object : Runnable {
                override fun run() {
                    if (left <= 0) {
                        keepBtn.text = "Keep going anyway"
                        keepBtn.isEnabled = true
                        keepBtn.alpha = 1f
                        return
                    }
                    keepBtn.text = "Keep going anyway ($left)"
                    left--
                    ui.postDelayed(this, 1000)
                }
            }
            ui.post(r)
        } else {
            keepBtn.text = "Keep going"
            keepBtn.isEnabled = true
            keepBtn.alpha = 1f
        }
    }

    private fun decide(decision: String) {
        if (answered) return
        answered = true
        val gain = if (chosen >= 0) GAINS[chosen] else "(no answer)"
        Scroll.logCheckIn(this, app, minutes, gain, note.text.toString().trim(), decision)
        Stats.inc(this, "checkIns")
        if (decision == "closed") Stats.inc(this, "checkInsClosed")
        if (chosen == GAINS.lastIndex) Stats.inc(this, "checkInsNothing")
        Scroll.sinceCheckInSec = 0.0
        Scroll.nextPromptSec = Scroll.newInterval(p)
        if (decision == "closed") {
            Scroll.currentApp = ""
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }

    // ------------------------------------------------------------------ helpers

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(14).toFloat()
    }

    private fun button(text: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = 16f
        background = rounded(color)
        stateListAnimator = null
        minHeight = dp(52)
        setOnClickListener { onClick() }
    }
}
