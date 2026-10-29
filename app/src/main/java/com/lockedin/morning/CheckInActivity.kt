package com.lockedin.morning

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.ceil

/**
 * Shown after 15–20 min of Instagram/TikTok:
 *   1. "Were you just doom scrolling?"  Yes / No  (either answer → quiz)
 *   2. A forced quiz on previous days' videos (questions + time limit from settings)
 *   3. Pass mark reached → you can move on. Otherwise → a new quiz, until you pass.
 * While this is up you can't leave it (calls and alarms still work).
 */
class CheckInActivity : Activity() {

    companion object {
        private const val ORANGE = 0xFFFF7A1A.toInt()
        private const val CARD = 0xFF1C1C1E.toInt()
        private const val MUTED = 0xFFA0A0A8.toInt()
        private const val GREEN = 0xFF2E9E5B.toInt()
        private const val RED = 0xFFD64545.toInt()
        const val EXTRA_APP = "app"
        const val EXTRA_MIN = "min"
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var p: Prefs
    private var app = ""
    private var minutes = 15
    private var doom = ""          // "yes" / "no"
    private var round = 0
    private var questions: List<Bank.Item> = emptyList()
    private val answers = mutableListOf<Int>()
    private var deadline = 0L
    private var stage = "ask"      // ask → quiz → result → done
    private var tapping = false
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Prefs(this)
        app = intent.getStringExtra(EXTRA_APP) ?: p.recallApp
        minutes = intent.getIntExtra(EXTRA_MIN, p.scrollMinMin)
        Scroll.promptShowing = true
        p.recallApp = app
        p.recallActive = true
        // Safety window for the service: never trap you longer than this.
        p.recallUntil = System.currentTimeMillis() + 60 * 60_000L
        showAsk()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Bounced back here after trying to leave: just carry on where you were.
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        if (finished) Scroll.promptShowing = false
        super.onDestroy()
    }

    @Deprecated("You can't back out of the check-in")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (stage == "done") { leave(false); return }
        Toast.makeText(this, if (stage == "ask") "Pick Yes or No" else "Finish the quiz first", Toast.LENGTH_SHORT).show()
    }

    private val name get() = Scroll.appName(this, app)

    // ------------------------------------------------------------------ 1. the question

    private fun showAsk() {
        stage = "ask"
        val col = column()
        col.addView(label("$minutes min on $name", 15f, ORANGE, true))
        col.addView(label("Were you just doom scrolling?", 30f, Color.WHITE, true), lp(10))
        col.addView(label("Either way, it's quiz time on your past videos. Pass it to carry on.", 15f, MUTED), lp(10))
        col.addView(button("Yes", CARD) { doom = "yes"; Stats.inc(this, "doomYes"); startQuiz() }, lp(28))
        col.addView(button("No", CARD) { doom = "no"; Stats.inc(this, "doomNo"); startQuiz() }, lp(10))
        show(col)
    }

    // ------------------------------------------------------------------ 2. the quiz

    private fun startQuiz() {
        round++
        val n = p.recallQuestions.coerceIn(1, 20)
        questions = Bank.pick(this, n)
        if (questions.isEmpty()) {
            // No saved questions yet: make some from the latest video's transcript.
            val t = Session.readTranscript(this)
            questions = Quiz.builtIn(t, n).mapIndexed { i, q -> Bank.Item("local$i", q, p.videoTitle, p.videoAuthor, p.sessionDay) }
        }
        answers.clear()
        if (questions.isEmpty()) { showNoQuestions(); return }
        deadline = System.currentTimeMillis() + p.recallSeconds.coerceIn(60, 1800) * 1000L
        stage = "quiz"
        showQuestion()
    }

    private fun showQuestion() {
        ui.removeCallbacksAndMessages(null)
        val i = answers.size
        if (i >= questions.size || System.currentTimeMillis() >= deadline) { showResult(); return }
        val item = questions[i]
        val q = item.q
        val col = column()
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(label("Question ${i + 1} of ${questions.size}" + (if (round > 1) " · try $round" else ""), 14f, ORANGE, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val timer = label("", 16f, ORANGE, true)
        top.addView(timer)
        col.addView(top)
        if (item.title.isNotBlank()) col.addView(label("From ${dayLabel(item.day)}: ${item.title}", 13f, MUTED).apply { maxLines = 2 }, lp(8))
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
        col.addView(label("Score ${p.recallPassPercent}% to carry on. Calls still work.", 12f, MUTED).apply { gravity = Gravity.CENTER }, lp(20))
        show(col)
        val tick = object : Runnable {
            override fun run() {
                val ms = deadline - System.currentTimeMillis()
                if (ms <= 0) { showResult(); return }
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
        val q = questions[answers.size].q
        answers.add(k)
        btns.getOrNull(q.answer)?.background = rounded(GREEN)
        if (k != q.answer) btns.getOrNull(k)?.background = rounded(RED)
        ui.postDelayed({ tapping = false; showQuestion() }, 700)
    }

    // ------------------------------------------------------------------ 3. result

    private fun showResult() {
        if (stage != "quiz") return
        ui.removeCallbacksAndMessages(null)
        val n = questions.size
        val right = questions.indices.count { answers.getOrNull(it) == questions[it].q.answer }
        val needed = ceil(p.recallPassPercent * n / 100.0).toInt().coerceAtLeast(1)
        val passed = right >= needed
        Bank.record(this, questions.filter { !it.id.startsWith("local") }.associate { it.id to (answers.getOrNull(questions.indexOf(it)) == it.q.answer) })
        Stats.inc(this, "recallQuizzes")
        Stats.inc(this, "recallCorrect", right)
        Stats.inc(this, "recallQuestions", n)

        val col = column()
        if (passed) {
            stage = "done"
            p.recallActive = false
            Scroll.logCheckIn(this, app, minutes, "Doom scrolling: $doom · quiz $right/$n (try $round)", "", "passed")
            Stats.inc(this, "checkIns")
            col.addView(label("Passed: $right/$n ✅", 30f, Color.WHITE, true))
            col.addView(label("You can carry on.", 16f, MUTED), lp(6))
            addMissed(col)
            col.addView(button("Continue", GREEN) { leave(false) }, lp(24))
            col.addView(button("Close $name", CARD) { leave(true) }, lp(10))
        } else {
            stage = "result"
            col.addView(label("$right/$n: not yet", 30f, Color.WHITE, true))
            col.addView(label("You need $needed/$n (${p.recallPassPercent}%). Here's what you missed. Then go again.", 15f, MUTED), lp(6))
            addMissed(col)
            col.addView(button("Try again", ORANGE) { startQuiz() }, lp(24))
        }
        show(col)
    }

    private fun addMissed(col: LinearLayout) {
        for (i in questions.indices) {
            val it = questions[i]
            if (answers.getOrNull(i) == it.q.answer) continue
            col.addView(label(it.q.q, 15f, Color.WHITE, true), lp(14))
            col.addView(label("Answer: ${it.q.options[it.q.answer]}", 14f, GREEN), lp(4))
            if (it.q.explain.isNotBlank()) col.addView(label(it.q.explain, 13f, MUTED), lp(2))
        }
    }

    /** No questions saved yet and no transcript: a forced 60-second breather instead. */
    private fun showNoQuestions() {
        stage = "result"
        val col = column()
        col.addView(label("Take a breather", 28f, Color.WHITE, true))
        col.addView(label("There are no quiz questions yet. They come from your morning quizzes. For now, put the phone down for a minute.", 15f, MUTED), lp(8))
        val count = label("60", 44f, ORANGE, true)
        col.addView(count, lp(20))
        show(col)
        var left = 60
        val r = object : Runnable {
            override fun run() {
                if (left <= 0) {
                    stage = "done"
                    p.recallActive = false
                    Scroll.logCheckIn(this@CheckInActivity, app, minutes, "Doom scrolling: $doom · breather (no questions yet)", "", "passed")
                    col.addView(button("Continue", GREEN) { leave(false) }, lp(20))
                    col.addView(button("Close $name", CARD) { leave(true) }, lp(10))
                    count.text = "Done"
                    return
                }
                count.text = "$left"
                left--
                ui.postDelayed(this, 1000)
            }
        }
        ui.post(r)
    }

    private fun leave(close: Boolean) {
        if (finished) return
        finished = true
        p.recallActive = false
        Scroll.sinceCheckInSec = 0.0
        Scroll.nextPromptSec = Scroll.newInterval(p)
        Scroll.promptShowing = false
        if (close) {
            Stats.inc(this, "checkInsClosed")
            Scroll.currentApp = ""
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }

    // ------------------------------------------------------------------ helpers

    private fun dayLabel(day: String): String {
        if (day.isBlank() || day == Session.today()) return "today"
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_MONTH, -1)
        if (day == fmt.format(cal.time)) return "yesterday"
        return try {
            java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(fmt.parse(day)!!)
        } catch (_: Exception) { day }
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(56), dp(24), dp(32))
    }

    private fun show(col: LinearLayout) {
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFillViewport = true
            addView(col)
        })
    }

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
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        background = rounded(color)
        stateListAnimator = null
        minHeight = dp(58)
        setOnClickListener { onClick() }
    }
}
