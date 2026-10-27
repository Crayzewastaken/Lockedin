package com.lockedin.morning

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Setup + settings screen. Locked while a session is running. */
class MainActivity : Activity() {

    companion object {
        private const val ORANGE = 0xFFFF7A1A.toInt()
        private const val CARD = 0xFF1C1C1E.toInt()
        private const val MUTED = 0xFFA0A0A8.toInt()
        private const val GREEN = 0xFF2E9E5B.toInt()
        private const val RED = 0xFFD64545.toInt()
    }

    private lateinit var p: Prefs
    private lateinit var permBox: LinearLayout
    private lateinit var dashBox: LinearLayout
    private var permTitle: TextView? = null
    private lateinit var statusBox: LinearLayout
    private val savers = mutableListOf<() -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Prefs(this)
        build()
    }

    override fun onResume() {
        super.onResume()
        if (p.active) {
            Session.launch(this, null)
            finish()
            return
        }
        refreshPerms()
        refreshStatus()
        refreshDashboard()
    }

    private fun build() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(32), dp(20), dp(40))
        }
        col.addView(label("Locked In", 30f, Color.WHITE, true))
        col.addView(label("Your first unlock each morning opens a 30+ minute Hormozi or Jeremy Miner video, then a quiz. Your phone is locked until you pass.", 15f, MUTED), lp(6))

        dashBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(section("Dashboard"))
        col.addView(dashBox)

        statusBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(section("Status"))
        col.addView(statusBox)

        permBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        permTitle = section("Setup (do these once)")
        col.addView(permTitle)
        col.addView(permBox)

        col.addView(section("Settings"))
        val enabledSw = Switch(this).apply {
            text = "Locked In is on"
            setTextColor(Color.WHITE)
            isChecked = p.enabled
        }
        col.addView(enabledSw, lp(8))
        savers.add { p.enabled = enabledSw.isChecked }

        col.addView(label("Quiz makers (tried top to bottom). Switch one off to hide its settings.", 13f, MUTED), lp(14))

        // Gemini
        val gemBox = providerBox(col, "Gemini (free)", p.useGemini) { p.useGemini = it }
        textField(gemBox, "Gemini API key", p.geminiKey, secret = true) { p.geminiKey = it.trim() }
        textField(gemBox, "Gemini model", p.geminiModel) { p.geminiModel = it.trim() }
        testButton(gemBox, "Test Gemini key", "Gemini test", "Testing…") {
            "✅ Working (model: ${Quiz.testGemini(p)}). Quizzes are ready to go."
        }

        // OpenRouter
        val orBox = providerBox(col, "OpenRouter (free backup)", p.useOpenRouter) { p.useOpenRouter = it }
        textField(orBox, "OpenRouter API key", p.openRouterKey, secret = true) { p.openRouterKey = it.trim() }
        textField(orBox, "OpenRouter model", p.openRouterModel) { p.openRouterModel = it.trim() }
        testButton(orBox, "Test OpenRouter key", "OpenRouter test", "Testing… (free models can take up to a minute)") {
            "✅ Working. It replied: ${Quiz.testOpenRouter(p)}"
        }

        // Claude
        val clBox = providerBox(col, "Claude (paid)", p.useClaude) { p.useClaude = it }
        textField(clBox, "Claude API key", p.claudeKey, secret = true) { p.claudeKey = it.trim() }
        textField(clBox, "Claude model", p.claudeModel) { p.claudeModel = it.trim() }

        // Built-in
        providerBox(col, "Built-in fill-in-the-blank (no AI, no key)", p.useBuiltIn) { p.useBuiltIn = it }

        col.addView(section("Videos & schedule"))
        textField(col, "Channels (comma separated)", p.channels) { p.channels = it }
        textField(col, "YouTube Data API key (optional, makes video finding more reliable)", p.youtubeKey, secret = true) { p.youtubeKey = it.trim() }
        textField(col, "Extra video links (optional, one per line)", p.extraVideoUrls, multi = true) { p.extraVideoUrls = it }
        intField(col, "Shortest video (minutes)", p.minMinutes) { p.minMinutes = it.coerceAtLeast(1) }
        intField(col, "Longest video (minutes)", p.maxMinutes) { p.maxMinutes = it.coerceAtLeast(p.minMinutes) }
        intField(col, "Lock can start from (hour, 0–23)", p.windowStartHour) { p.windowStartHour = it.coerceIn(0, 23) }
        intField(col, "No new lock after (hour, 1–24)", p.windowEndHour) { p.windowEndHour = it.coerceIn(1, 24) }
        intField(col, "Wait if an alarm is due within (minutes)", p.alarmBufferMin) { p.alarmBufferMin = it.coerceAtLeast(0) }
        intField(col, "Quiz questions", p.quizQuestions) { p.quizQuestions = it.coerceIn(3, 20) }
        intField(col, "Quiz time limit (seconds)", p.quizSeconds) { p.quizSeconds = it.coerceIn(60, 1800) }
        intField(col, "Pass mark (%)", p.passPercent) { p.passPercent = it.coerceIn(10, 100) }
        textField(col, "Messaging apps allowed (package names)", p.messagingApps) {
            p.messagingApps = it.replace("\n", ",")
        }
        intField(col, "Message time per session (minutes)", p.messagingBudgetSec / 60) { p.messagingBudgetSec = it.coerceAtLeast(0) * 60 }
        intField(col, "Longest single message visit (seconds)", p.messagingVisitSec) { p.messagingVisitSec = it.coerceAtLeast(15) }

        col.addView(button("Save settings", ORANGE) {
            if (p.active) return@button
            savers.forEach { it() }
            p.poolFetchedAt = 0L // channel or length changes → rebuild the video list
            toast("Saved")
            refreshStatus()
        }, lp(20))

        col.addView(button("Check for updates (you have build ${Updater.currentBuild(this)})", CARD) {
            Updater.check(this)
        }, lp(10))

        col.addView(button("Start a session now (test)", CARD) {
            AlertDialog.Builder(this)
                .setTitle("Start now?")
                .setMessage("This locks your phone into today's video and quiz right away, exactly like the morning lock. Breaks and the emergency exit work as normal.")
                .setPositiveButton("Start") { _, _ ->
                    savers.forEach { it() }
                    Session.start(this)
                    finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }, lp(10))

        col.addView(label("Breaks: 15 minutes a day in total, up to 3 breaks (5, 10 or 15 min). Calls and alarms always work. Messaging apps open only after a new message arrives, within your message time.", 13f, MUTED), lp(16))

        val sv = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            fitsSystemWindows = true
            addView(col)
        }
        setContentView(sv)
        refreshPerms()
        refreshStatus()
    }

    // ------------------------------------------------------------------ status

    private fun refreshDashboard() {
        dashBox.removeAllViews()
        val st = Stats.load(this)
        val watch = Stats.watchSeconds(this)
        val total = watch.values.sum()
        val vids = st.optJSONObject("videosBy")

        // Watch time: overall + per channel
        val big = card()
        big.addView(label("Total watched", 13f, MUTED))
        big.addView(label(Stats.hours(total), 34f, Color.WHITE, true))
        val split = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (who in listOf("Alex Hormozi", "Jeremy Miner")) {
            val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            c.addView(label(who, 13f, MUTED))
            c.addView(label(Stats.hours(watch[who] ?: 0.0), 22f, ORANGE, true))
            c.addView(label("${vids?.optInt(who) ?: 0} videos finished", 12f, MUTED))
            split.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        big.addView(split, lp(10))
        if ((watch["Other"] ?: 0.0) > 60) big.addView(label("Other videos: ${Stats.hours(watch["Other"] ?: 0.0)}", 12f, MUTED), lp(6))
        dashBox.addView(big, lp(8))

        // Quizzes
        val attempts = st.optInt(Stats.QUIZ_ATTEMPTS)
        val qTotal = st.optInt(Stats.QUIZ_QUESTIONS)
        val avg = if (qTotal > 0) st.optInt(Stats.QUIZ_CORRECT) * 100 / qTotal else 0
        val quiz = card()
        quiz.addView(label("Quizzes", 13f, MUTED))
        val qRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun stat(row: LinearLayout, value: String, name: String) {
            val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            c.addView(label(value, 22f, Color.WHITE, true))
            c.addView(label(name, 12f, MUTED))
            row.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        stat(qRow, "${st.optInt(Stats.QUIZ_PASSES)}/$attempts", "passed")
        stat(qRow, if (attempts > 0) "$avg%" else "–", "average")
        stat(qRow, if (attempts > 0) "${st.optInt("bestPct")}%" else "–", "best")
        stat(qRow, "${streak()}", "day streak")
        quiz.addView(qRow, lp(6))
        // last scores as little bars
        val scores = st.optJSONArray("scores")
        if (scores != null && scores.length() > 0) {
            quiz.addView(label("Last ${minOf(scores.length(), 14)} scores", 12f, MUTED), lp(12))
            val bars = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
            }
            val from = maxOf(0, scores.length() - 14)
            for (i in from until scores.length()) {
                val pct = scores.optInt(i).coerceIn(0, 100)
                val bar = android.view.View(this).apply {
                    background = rounded(if (pct >= p.passPercent) GREEN else RED)
                }
                bars.addView(bar, LinearLayout.LayoutParams(0, dp(6) + dp(54) * pct / 100, 1f).apply { marginEnd = dp(4) })
            }
            quiz.addView(bars, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)).apply { topMargin = dp(4) })
        }
        quiz.addView(label("Written summaries: ${st.optInt(Stats.SUMMARIES)}", 12f, MUTED), lp(8))
        dashBox.addView(quiz, lp(8))

        // Habits while watching
        val hab = card()
        hab.addView(label("While watching", 13f, MUTED))
        val r1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        stat(r1, "${st.optInt(Stats.PAUSES)}", "pauses")
        stat(r1, "${st.optInt(Stats.REWINDS)}", "rewinds")
        stat(r1, "${st.optInt(Stats.LATEST_JUMPS)}", "jumped to latest")
        hab.addView(r1, lp(6))
        val r2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        stat(r2, "${st.optInt(Stats.SKIP_ATTEMPTS)}", "tried to skip ahead")
        stat(r2, "${st.optInt(Stats.BREAKS)} (${st.optInt(Stats.BREAK_MIN)}m)", "breaks")
        stat(r2, "${st.optInt(Stats.EMERGENCIES)}", "emergency exits")
        hab.addView(r2, lp(10))
        if (st.optInt(Stats.SKIPPED_DAYS) > 0) hab.addView(label("Days skipped (video wouldn't load): ${st.optInt(Stats.SKIPPED_DAYS)}", 12f, MUTED), lp(8))
        dashBox.addView(hab, lp(8))
    }

    /** Consecutive days (up to today/yesterday) that ended with a passed quiz or written summary. */
    private fun streak(): Int {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val good = Session.history(p)
            .filter { val r = it.optString("result"); r.startsWith("Passed") || r.startsWith("Wrote") }
            .mapNotNull { try { fmt.parse(it.optString("day"))?.time } catch (_: Exception) { null } }
            .map { it / 86_400_000L }.toSortedSet()
        if (good.isEmpty()) return 0
        val today = (fmt.parse(Session.today())?.time ?: 0L) / 86_400_000L
        var d = if (today in good) today else today - 1
        var n = 0
        while (d in good) { n++; d-- }
        return n
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = rounded(CARD)
    }

    private fun refreshStatus() {
        statusBox.removeAllViews()
        val today = Session.today()
        val doneToday = p.completedDay == today
        statusBox.addView(label(
            if (doneToday) "Today's session: done ✅" else "Today's session: not done yet",
            16f, Color.WHITE, true
        ), lp(6))
        val am = getSystemService(AlarmManager::class.java)
        val next = am?.nextAlarmClock
        val nextText = if (next == null) "No alarm set" else
            "Next alarm: " + SimpleDateFormat("EEE h:mm a", Locale.getDefault()).format(Date(next.triggerTime))
        statusBox.addView(label("$nextText · lock window ${p.windowStartHour}:00–${p.windowEndHour}:00", 14f, MUTED), lp(4))

        val hist = Session.history(p).takeLast(7).reversed()
        if (hist.isNotEmpty()) {
            statusBox.addView(label("Last ${hist.size} days", 14f, ORANGE, true), lp(12))
            for (h in hist) {
                val extra = buildList {
                    if (h.optInt("extMin") > 0) add("${h.optInt("extMin")} min breaks")
                    if (h.optInt("msgSec") > 0) add("${h.optInt("msgSec") / 60}m ${h.optInt("msgSec") % 60}s messages")
                    if (h.optInt("quizRounds") > 1) add("${h.optInt("quizRounds")} quiz tries")
                }.joinToString(" · ")
                val line = "${h.optString("day")}  ${h.optString("result")}" +
                    (if (h.optString("video").isNotEmpty()) "\n${h.optString("video")}" else "") +
                    (if (extra.isNotEmpty()) "\n$extra" else "")
                statusBox.addView(label(line, 13f, Color.WHITE), lp(8))
            }
        }
    }

    // ------------------------------------------------------------------ permissions

    private fun refreshPerms() {
        permBox.removeAllViews()
        val accessOn = (Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .contains("$packageName/")
        val overlayOn = Settings.canDrawOverlays(this)
        val notifOn = NotificationWatcher.isEnabled(this)
        val postOn = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val batteryOn = getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true

        if (Build.VERSION.SDK_INT >= 33 && (!accessOn || !notifOn)) {
            permBox.addView(label("Android blocks Accessibility and Notification access for apps installed outside the Play Store until you allow it: tap below → ⋮ menu (top right) → \"Allow restricted settings\". If you don't see that option, try Accessibility first, then come back."Allow restricted settings\". If you don't see that option, try step 2 first, then come back.", 13f, MUTED), lp(8))
            permBox.addView(button("Open app info (allow restricted settings)", CARD) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }, lp(6))
        }

        permRow("Display over other apps", "Lets the lock open on top of whatever you're doing.", overlayOn) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        permRow("Accessibility: Locked In", "Detects your unlock and brings the video back if you leave it. Settings → Accessibility → Locked In → On.", accessOn) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        permRow("Notification access", "Lets Messenger/SMS open only when a new message actually arrives.", notifOn) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        permRow("Show notifications", "For the break countdown.", postOn) {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        permRow("Unrestricted battery", "Stops Android from killing the lock overnight.", batteryOn) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        if (!Quiz.hasProvider(p) && !p.useBuiltIn) {
            permBox.addView(label("Switch on a quiz maker below (Gemini: aistudio.google.com/apikey, OpenRouter: openrouter.ai/keys) or the built-in quiz. Otherwise you'll write a summary instead.", 13f, RED), lp(10))
        }
        val allDone = permBox.childCount == 0
        permTitle?.visibility = if (allDone) android.view.View.GONE else android.view.View.VISIBLE
        permBox.visibility = if (allDone) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun permRow(title: String, hint: String, ok: Boolean, action: () -> Unit) {
        if (ok) return // done steps disappear
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(CARD)
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(title, 15f, Color.WHITE, true))
        texts.addView(label(hint, 12f, MUTED))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val status = label(if (ok) "✓ Done" else "Set up", 14f, if (ok) GREEN else ORANGE, true)
        status.setPadding(dp(12), 0, 0, 0)
        row.addView(status)
        if (!ok) row.setOnClickListener {
            try { action() } catch (_: Exception) { toast("Couldn't open that screen. Find it in Settings.") }
        }
        permBox.addView(row, lp(8))
    }

    // ------------------------------------------------------------------ helpers

    private fun textField(col: LinearLayout, title: String, value: String, secret: Boolean = false, multi: Boolean = false, save: (String) -> Unit) {
        col.addView(label(title, 13f, MUTED), lp(14))
        val et = EditText(this).apply {
            setText(value)
            setTextColor(Color.WHITE)
            textSize = 15f
            inputType = when {
                secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                multi -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else -> InputType.TYPE_CLASS_TEXT
            }
            if (multi) minLines = 2
            background = rounded(CARD)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        col.addView(et, lp(4))
        savers.add { save(et.text.toString()) }
    }

    /** A switch with a box under it that is only shown while the switch is on. */
    private fun providerBox(col: LinearLayout, title: String, value: Boolean, save: (Boolean) -> Unit): LinearLayout {
        val sw = Switch(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            isChecked = value
            setPadding(0, dp(6), 0, dp(6))
        }
        col.addView(sw, lp(12))
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, dp(4))
            visibility = if (value) android.view.View.VISIBLE else android.view.View.GONE
        }
        col.addView(box, lp(0))
        sw.setOnCheckedChangeListener { _, on ->
            box.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
            save(on)
            refreshPerms()
        }
        savers.add { save(sw.isChecked) }
        return box
    }

    private fun testButton(box: LinearLayout, label: String, title: String, testing: String, run: () -> String) {
        box.addView(button(label, CARD) {
            savers.forEach { it() }
            toast(testing)
            Thread {
                val result = try { run() } catch (e: Exception) { "❌ ${e.message}" }
                runOnUiThread {
                    if (!isFinishing) AlertDialog.Builder(this)
                        .setTitle(title)
                        .setMessage(result)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }.start()
        }, lp(8))
    }

    private fun toggle(col: LinearLayout, title: String, value: Boolean, save: (Boolean) -> Unit) {
        val sw = Switch(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 15f
            isChecked = value
            setPadding(0, dp(4), 0, dp(4))
        }
        col.addView(sw, lp(4))
        savers.add { save(sw.isChecked) }
    }

    private fun intField(col: LinearLayout, title: String, value: Int, save: (Int) -> Unit) {
        col.addView(label(title, 13f, MUTED), lp(14))
        val et = EditText(this).apply {
            setText(value.toString())
            setTextColor(Color.WHITE)
            textSize = 15f
            inputType = InputType.TYPE_CLASS_NUMBER
            background = rounded(CARD)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        col.addView(et, lp(4))
        savers.add { et.text.toString().trim().toIntOrNull()?.let(save) }
    }

    private fun section(t: String) = label(t, 18f, ORANGE, true).apply {
        setPadding(0, dp(24), 0, dp(4))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun lp(top: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(top) }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(12).toFloat()
    }

    private fun button(text: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = 15f
        background = rounded(color)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
