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

/** Full-screen "what did you gain?" check-in shown every 15–20 min of Instagram/TikTok. */
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = intent.getStringExtra(EXTRA_APP) ?: ""
        minutes = intent.getIntExtra(EXTRA_MIN, 15)
        Scroll.promptShowing = true
        build()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        app = intent.getStringExtra(EXTRA_APP) ?: app
        minutes = intent.getIntExtra(EXTRA_MIN, minutes)
        build()
    }

    override fun onDestroy() {
        Scroll.promptShowing = false
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Deprecated("Must answer the check-in")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // Back = "I'm done", the honest way out.
        decide("closed")
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
        col.addView(label("Pick an answer first. Next check-in in about ${(Prefs(this).scrollMinMin)} min.", 12f, MUTED).apply {
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
        val p = Prefs(this)
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
