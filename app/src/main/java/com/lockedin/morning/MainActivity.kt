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
        private const val FIELD = 0xFF2C2C30.toInt()
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
        // One-time: the old default stopped new locks at 1pm; with work hours it can run in the evening too.
        if (!p.sp.getBoolean("migratedWorkHours", false)) {
            if (p.windowEndHour == 13) p.windowEndHour = 22
            p.sp.edit().putBoolean("migratedWorkHours", true).apply()
        }
        build()
    }

    override fun onResume() {
        super.onResume()
        if (p.active) {
            Session.launch(this, null)
            finish()
            return
        }
        if (!p.sp.getBoolean("onboarded", false)) {
            startActivity(Intent(this, SetupActivity::class.java))
            return
        }
        if (p.sp.getBoolean("rebuildMain", false)) {
            // Setup changed settings: rebuild so the settings screen shows the new values.
            p.sp.edit().putBoolean("rebuildMain", false).apply()
            savers.clear(); summaries.clear(); groupOpeners.clear()
            inSettings = false
            build()
        }
        refreshPerms()
        refreshStatus()
        refreshSummary()
        refreshDashboard()
        refreshBrain()
        Updater.refreshLatest(this) { refreshPerms() }
        // The accessibility service can take a moment to connect after the app starts.
        permBox.postDelayed({ if (!isFinishing) refreshPerms() }, 2500)
    }

    // ------------------------------------------------------------------ layout: home + settings

    private lateinit var homeView: ScrollView
    private lateinit var settingsView: ScrollView
    private lateinit var summaryBox: LinearLayout
    private lateinit var detailWrap: LinearLayout
    private lateinit var histBox: LinearLayout
    private lateinit var moreLink: TextView
    private var inSettings = false
    private lateinit var brainBox: LinearLayout
    private var liveTicker: (() -> Unit)? = null
    private val summaries = mutableListOf<() -> Unit>()

    private fun build() {
        // ---------------- HOME: today, setup (if needed), your numbers
        val home = column()
        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        topRow.addView(label("Locked In", 30f, Color.WHITE, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        topRow.addView(pill("⚙  Settings") { openSettings() })
        home.addView(topRow)

        statusBox = card()
        home.addView(statusBox, lp(20))

        brainBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        home.addView(brainBox, lp(12))

        permBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        permTitle = label("Needs your attention", 18f, ORANGE, true).apply { setPadding(0, dp(24), 0, 0) }
        home.addView(permTitle)
        home.addView(permBox)

        home.addView(label("Your progress", 18f, Color.WHITE, true).apply { setPadding(0, dp(28), 0, 0) })
        summaryBox = card()
        home.addView(summaryBox, lp(10))

        moreLink = label("See all stats  ▾", 15f, ORANGE, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(6))
            setOnClickListener {
                val open = detailWrap.visibility != android.view.View.VISIBLE
                detailWrap.visibility = if (open) android.view.View.VISIBLE else android.view.View.GONE
                text = if (open) "Hide stats  ▴" else "See all stats  ▾"
            }
        }
        home.addView(moreLink)
        detailWrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = android.view.View.GONE }
        dashBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        histBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        detailWrap.addView(dashBox)
        detailWrap.addView(histBox)
        home.addView(detailWrap)

        home.addView(button("Start a session now", CARD) { confirmStart() }, lp(28))
        home.addView(label("Check for updates  ·  build ${Updater.currentBuild(this)}", 13f, MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(8))
            setOnClickListener { Updater.check(this@MainActivity) }
        })
        homeView = scrollOf(home)

        // ---------------- SETTINGS: one tidy group per topic, collapsed
        val set = column()
        val back = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        back.addView(label("‹  Back", 17f, ORANGE, true).apply {
            setPadding(0, dp(4), dp(16), dp(4))
            setOnClickListener { closeSettings() }
        })
        set.addView(back)
        set.addView(label("Settings", 30f, Color.WHITE, true), lp(8))
        set.addView(label("Tap a section to open it. Changes save when you go back.", 14f, MUTED), lp(4))

        val enabledSw = Switch(this).apply {
            text = "Locked In is on"
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            isChecked = p.enabled
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(CARD)
        }
        set.addView(enabledSw, lp(16))
        savers.add { p.enabled = enabledSw.isChecked }

        // 1. YouTubers & videos
        val yt = group(set, "📺  YouTubers & videos") {
            "${YouTube.channelList(p).joinToString()} · ${Lengths.current(p).label}"
        }
        channelEditor(yt)
        lengthPicker(yt)
        textField(yt, "Extra video links (optional, one per line)", p.extraVideoUrls, multi = true) { p.extraVideoUrls = it }
        textField(yt, "YouTube Data API key (optional)", p.youtubeKey, secret = true) { p.youtubeKey = it.trim() }

        // 2. Schedule
        val sch = group(set, "⏰  When it locks") {
            "${hour(p.windowStartHour)}–${hour(p.windowEndHour)}" +
                (if (p.workEnabled) " · not at work ${Session.hhmm(p.workStartMin)}–${Session.hhmm(p.workEndMin)}" else "")
        }
        intField(sch, "Earliest lock (hour, 0–23)", p.windowStartHour) { p.windowStartHour = it.coerceIn(0, 23) }
        intField(sch, "No new lock after (hour, 1–24)", p.windowEndHour) { p.windowEndHour = it.coerceIn(1, 24) }
        intField(sch, "Wait if an alarm is due within (minutes)", p.alarmBufferMin) { p.alarmBufferMin = it.coerceAtLeast(0) }
        val workBox = providerBox(sch, "Never lock during work hours", p.workEnabled) { p.workEnabled = it }
        textField(workBox, "Work starts (e.g. 8:30am)", Session.hhmm(p.workStartMin)) { v ->
            Session.parseHhmm(v)?.let { p.workStartMin = it } ?: toast("Couldn't read work start time \"$v\"")
        }
        textField(workBox, "Work ends (e.g. 4:30pm)", Session.hhmm(p.workEndMin)) { v ->
            Session.parseHhmm(v)?.let { p.workEndMin = it } ?: toast("Couldn't read work end time \"$v\"")
        }
        toggle(workBox, "Weekdays only (Mon–Fri)", p.workWeekdaysOnly) { p.workWeekdaysOnly = it }

        // 3. Quiz
        val qz = group(set, "🧠  Quiz") {
            val makers = buildList {
                if (p.useGemini) add("Gemini"); if (p.useOpenRouter) add("OpenRouter")
                if (p.useClaude) add("Claude"); if (p.useBuiltIn) add("built-in")
            }.joinToString(" → ").ifEmpty { "no quiz maker on" }
            "${p.quizQuestions} questions · ${p.quizSeconds / 60} min · pass ${p.passPercent}% · $makers"
        }
        intField(qz, "Questions", p.quizQuestions) { p.quizQuestions = it.coerceIn(3, 20) }
        intField(qz, "Time limit (seconds)", p.quizSeconds) { p.quizSeconds = it.coerceIn(60, 1800) }
        intField(qz, "Pass mark (%)", p.passPercent) { p.passPercent = it.coerceIn(10, 100) }
        qz.addView(label("Who writes the quiz (tried top to bottom)", 13f, MUTED), lp(18))
        val gemBox = providerBox(qz, "Gemini (free)", p.useGemini) { p.useGemini = it }
        textField(gemBox, "Gemini API key", p.geminiKey, secret = true) { p.geminiKey = it.trim() }
        textField(gemBox, "Gemini model", p.geminiModel) { p.geminiModel = it.trim() }
        testButton(gemBox, "Test Gemini key", "Gemini test", "Testing…") {
            "✅ Working (model: ${Quiz.testGemini(p)}). Quizzes are ready to go."
        }
        val orBox = providerBox(qz, "OpenRouter (free backup)", p.useOpenRouter) { p.useOpenRouter = it }
        textField(orBox, "OpenRouter API key", p.openRouterKey, secret = true) { p.openRouterKey = it.trim() }
        textField(orBox, "OpenRouter model", p.openRouterModel) { p.openRouterModel = it.trim() }
        testButton(orBox, "Test OpenRouter key", "OpenRouter test", "Testing… (free models can take up to a minute)") {
            "✅ Working. It replied: ${Quiz.testOpenRouter(p)}"
        }
        val clBox = providerBox(qz, "Claude (paid)", p.useClaude) { p.useClaude = it }
        textField(clBox, "Claude API key", p.claudeKey, secret = true) { p.claudeKey = it.trim() }
        textField(clBox, "Claude model", p.claudeModel) { p.claudeModel = it.trim() }
        providerBox(qz, "Built-in fill-in-the-blank (no key)", p.useBuiltIn) { p.useBuiltIn = it }

        // 4. Scroll check-ins
        val sc = group(set, "📱  Scroll check-ins") {
            if (!p.scrollEnabled) "Off" else "Every ${p.scrollMinMin}–${p.scrollMaxMin} min · ${p.recallQuestions} questions · pass ${p.recallPassPercent}%"
        }
        val scrollBox = providerBox(sc, "Quiz me while I scroll", p.scrollEnabled) { p.scrollEnabled = it }
        scrollBox.addView(label("After ${p.scrollMinMin}–${p.scrollMaxMin} min in Instagram or TikTok: \"Were you just doom scrolling?\" then a forced quiz on earlier videos. Score the pass mark to carry on, or redo it. Questions saved so far: ${Bank.size(this)}.", 13f, MUTED), lp(6))
        val live = label("", 13f, ORANGE)
        scrollBox.addView(live, lp(8))
        val accessOk = (Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").contains("$packageName/")
        val liveTick = object : Runnable {
            override fun run() {
                live.text = if (!accessOk || EnforcerService.instance == null)
                    "⚠️ Accessibility for Locked In is off, so nothing is being counted. Turn it on from the home screen."
                else "Live: " + Scroll.status(this@MainActivity, p)
                if (inSettings) live.postDelayed(this, 1000)
            }
        }
        liveTicker = { live.removeCallbacks(liveTick); live.post(liveTick) }
        scrollBox.addView(button("Test check-in now", FIELD) {
            saveAll()
            startActivity(Intent(this, CheckInActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(CheckInActivity.EXTRA_APP, "com.instagram.android")
                .putExtra(CheckInActivity.EXTRA_MIN, p.scrollMinMin))
        }, lp(8))
        intField(scrollBox, "Daily scrolling limit for the brain meter (minutes)", p.scrollLimitMin) { p.scrollLimitMin = it.coerceIn(5, 600) }
        intField(scrollBox, "Check in after at least (minutes)", p.scrollMinMin) { p.scrollMinMin = it.coerceIn(1, 120) }
        intField(scrollBox, "…and at most (minutes)", p.scrollMaxMin) { p.scrollMaxMin = it.coerceIn(p.scrollMinMin, 180) }
        intField(scrollBox, "Quiz questions", p.recallQuestions) { p.recallQuestions = it.coerceIn(1, 20) }
        intField(scrollBox, "Quiz time limit (minutes)", p.recallSeconds / 60) { p.recallSeconds = it.coerceIn(1, 30) * 60 }
        intField(scrollBox, "Pass mark (%) — below this you redo the quiz", p.recallPassPercent) { p.recallPassPercent = it.coerceIn(10, 100) }
        textField(scrollBox, "Apps to watch (package names)", p.scrollApps, multi = true) { p.scrollApps = it.replace("\n", ",") }
        scrollBox.addView(label("Instagram = com.instagram.android · TikTok = com.zhiliaoapp.musically · YouTube = com.google.android.youtube · Facebook = com.facebook.katana", 12f, MUTED), lp(4))

        // 5. Messages
        val msg = group(set, "💬  Messages during the lock") {
            "${p.messagingBudgetSec / 60} min total · ${p.messagingVisitSec / 60} min per visit"
        }
        msg.addView(label("Messenger and SMS open only after a new message arrives. Calls and alarms always work.", 13f, MUTED), lp(6))
        intField(msg, "Message time per session (minutes)", p.messagingBudgetSec / 60) { p.messagingBudgetSec = it.coerceAtLeast(0) * 60 }
        intField(msg, "Longest single visit (seconds)", p.messagingVisitSec) { p.messagingVisitSec = it.coerceAtLeast(15) }
        textField(msg, "Messaging apps (package names)", p.messagingApps) { p.messagingApps = it.replace("\n", ",") }

        set.addView(button("Save", ORANGE) { saveAll(); toast("Saved") }, lp(24))
        set.addView(button("Run the setup guide again", CARD) {
            saveAll()
            startActivity(Intent(this, SetupActivity::class.java))
        }, lp(10))
        set.addView(label("Breaks: 15 min a day in total, up to 3 breaks.", 13f, MUTED).apply { gravity = Gravity.CENTER }, lp(14))
        settingsView = scrollOf(set)

        setContentView(homeView)
        refreshPerms()
        refreshStatus()
    }

    private fun hour(h: Int): String = Session.hhmm((h % 24) * 60).let { if (h == 24) "midnight" else it }

    private val groupOpeners = HashMap<String, () -> Unit>()

    private fun openSettings(section: String? = null) {
        inSettings = true
        section?.let { groupOpeners[it]?.invoke() }
        summaries.forEach { it() }
        setContentView(settingsView)
        settingsView.scrollTo(0, 0)
        liveTicker?.invoke()
    }

    private fun closeSettings() {
        saveAll()
        inSettings = false
        setContentView(homeView)
        refreshPerms(); refreshStatus(); refreshDashboard()
    }

    private fun saveAll() {
        if (p.active) return
        savers.forEach { it() }
        p.poolFetchedAt = 0L // channel or length changes → rebuild the video list
        Scroll.ensureInterval(p) // new check-in minutes take effect straight away
        summaries.forEach { it() }
    }

    @Deprecated("Back closes settings")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (inSettings) closeSettings() else super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        if (inSettings) saveAll()
    }

    private fun confirmStart() {
        if (Session.inWorkHours(p)) {
            toast("It's work hours right now, so the lock won't run.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Start now?")
            .setMessage("This locks your phone into today's video and quiz right away, exactly like the morning lock.")
            .setPositiveButton("Start") { _, _ ->
                savers.forEach { it() }
                Session.start(this)
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Video length: one tap to choose. Used every morning. */
    private fun lengthPicker(col: LinearLayout) {
        col.addView(label("Video length", 13f, MUTED), lp(16))
        val chips = mutableListOf<TextView>()
        val opts = Lengths.options(p)
        fun paint() {
            val cur = Lengths.current(p)
            chips.forEachIndexed { i, v ->
                val on = opts[i] == cur
                v.background = rounded(if (on) 0xFF3A2A1C.toInt() else FIELD)
                v.setTextColor(if (on) Color.WHITE else MUTED)
                v.text = (if (on) "✓  " else "") + opts[i].label
            }
        }
        for (o in opts) {
            val c = label(o.label, 15f, Color.WHITE).apply {
                setPadding(dp(14), dp(11), dp(14), dp(11))
                setOnClickListener { Lengths.set(p, o); paint(); summaries.forEach { it() } }
            }
            chips.add(c)
            col.addView(c, lp(6))
        }
        paint()
    }

    /** A collapsible settings section: title + one-line summary; tap to open. */
    private fun group(parent: LinearLayout, title: String, summary: () -> String): LinearLayout {
        val box = card()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(title, 17f, Color.WHITE, true))
        val sum = label("", 13f, MUTED).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        texts.addView(sum, lp(2))
        head.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val chev = label("›", 26f, MUTED).apply { setPadding(dp(12), 0, dp(2), 0) }
        head.addView(chev)
        box.addView(head)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = android.view.View.GONE; setPadding(0, dp(6), 0, dp(4)) }
        box.addView(body)
        groupOpeners[when {
            title.contains("Quiz") -> "quiz"
            title.contains("YouTubers") -> "youtubers"
            title.contains("locks") -> "schedule"
            title.contains("Scroll") -> "scroll"
            else -> "messages"
        }] = { body.visibility = android.view.View.VISIBLE; chev.text = "⌄" }
        head.setOnClickListener {
            val open = body.visibility != android.view.View.VISIBLE
            body.visibility = if (open) android.view.View.VISIBLE else android.view.View.GONE
            chev.text = if (open) "⌄" else "›"
            if (!open) { savers.forEach { it() }; summaries.forEach { it() } }
        }
        summaries.add { sum.text = summary() }
        sum.text = summary()
        parent.addView(box, lp(10))
        return body
    }

    private fun pill(text: String, onClick: () -> Unit) = label(text, 14f, Color.WHITE, true).apply {
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = rounded(CARD).apply { cornerRadius = dp(20).toFloat() }
        setOnClickListener { onClick() }
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(28), dp(20), dp(40))
    }

    private fun scrollOf(v: android.view.View) = ScrollView(this).apply {
        setBackgroundColor(Color.BLACK)
        fitsSystemWindows = true
        addView(v)
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
        // One tile per YouTuber: the ones you follow, plus anyone else you've watched. Two per row.
        val names = (YouTube.channelList(p) + watch.keys.filter { it != "Other" && (watch[it] ?: 0.0) > 60 })
            .distinct()
            .sortedByDescending { watch[it] ?: 0.0 }
        names.chunked(2).forEachIndexed { rowIdx, pair ->
            val split = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (who in pair) {
                val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                c.addView(label(who, 13f, MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                c.addView(label(Stats.hours(watch[who] ?: 0.0), 22f, ORANGE, true))
                c.addView(label("${vids?.optInt(who) ?: 0} videos finished", 12f, MUTED))
                split.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            if (pair.size == 1) split.addView(android.view.View(this), LinearLayout.LayoutParams(0, 1, 1f))
            big.addView(split, lp(if (rowIdx == 0) 10 else 12))
        }
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

        // Scrolling
        if (p.scrollEnabled || st.optInt("checkIns") > 0) {
            val sc = card()
            sc.addView(label("Scrolling", 13f, MUTED))
            val today = Scroll.timeByApp(this, 1)
            val week = Scroll.timeByApp(this, 7)
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            stat(r, Stats.hours(today.values.sum()), "today")
            stat(r, Stats.hours(week.values.sum()), "last 7 days")
            stat(r, Stats.hours(week.values.sum() / 7), "daily average")
            sc.addView(r, lp(6))
            // per app this week
            val byName = HashMap<String, Double>()
            for ((k, v) in week) { val n = Scroll.appName(this, k); byName[n] = (byName[n] ?: 0.0) + v }
            if (byName.isNotEmpty()) {
                sc.addView(label(byName.entries.sortedByDescending { it.value }
                    .joinToString("  ·  ") { "${it.key} ${Stats.hours(it.value)}" } + "  (7 days)", 12f, MUTED), lp(6))
            }
            val ci = st.optInt("checkIns")
            val r2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            stat(r2, "$ci", "check-ins")
            stat(r2, "${st.optInt("checkInsClosed")}", "times you closed it")
            val rq = st.optInt("recallQuestions")
            stat(r2, if (rq > 0) "${st.optInt("recallCorrect") * 100 / rq}%" else "–", "recall score")
            sc.addView(r2, lp(10))
            val dy = st.optInt("doomYes"); val dn = st.optInt("doomNo")
            if (dy + dn > 0) sc.addView(label("You said you were doom scrolling $dy of ${dy + dn} times.", 13f, MUTED), lp(8))
            val recent = Scroll.log(this).takeLast(3).reversed()
            if (recent.isNotEmpty()) {
                sc.addView(label("Recent answers", 12f, MUTED), lp(10))
                val tf = SimpleDateFormat("EEE h:mma", Locale.getDefault())
                for (e in recent) {
                    val noteTxt = e.optString("note").let { if (it.isNotBlank()) " — \"$it\"" else "" }
                    val dec = if (e.optString("decision") == "closed") "closed it" else "kept going"
                    sc.addView(label("${tf.format(Date(e.optLong("t")))} · ${Scroll.appName(this, e.optString("app"))} ${e.optInt("min")}m\n${e.optString("gain")}$noteTxt · $dec", 13f, Color.WHITE), lp(6))
                }
            }
            dashBox.addView(sc, lp(8))
        }
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
        val doneToday = p.completedDay == Session.today()
        val work = Session.inWorkHours(p)
        val (icon, head) = when {
            !p.enabled -> "⏸" to "Locked In is off"
            doneToday -> "✅" to "Done for today"
            work -> "💼" to "Work hours: no lock"
            else -> "⏳" to "Today's video: not done yet"
        }
        statusBox.addView(label("$icon  $head", 22f, Color.WHITE, true))
        val am = getSystemService(AlarmManager::class.java)
        val next = am?.nextAlarmClock
        val sub = when {
            !p.enabled -> "Switch it on in Settings."
            work && !doneToday -> "Your phone is free until ${Session.hhmm(p.workEndMin)}."
            doneToday -> "Nice work. See you tomorrow morning."
            next != null -> "Next alarm " + SimpleDateFormat("EEE h:mma", Locale.getDefault()).format(Date(next.triggerTime)).lowercase() +
                ". The lock starts on your first unlock after it."
            else -> "The lock starts on your first unlock between ${hour(p.windowStartHour)} and ${hour(p.windowEndHour)}."
        }
        statusBox.addView(label(sub, 15f, MUTED), lp(6))

        // Last 7 days → in "See all stats"
        histBox.removeAllViews()
        val hist = Session.history(p).takeLast(7).reversed()
        if (hist.isNotEmpty()) {
            val c = card()
            c.addView(label("Last ${hist.size} days", 13f, MUTED))
            for (h in hist) {
                val r = h.optString("result")
                val icon2 = when { r.startsWith("Passed") -> "✅"; r.startsWith("Wrote") -> "📝"; else -> "⚠️" }
                c.addView(label("$icon2  ${h.optString("day")} · $r", 14f, Color.WHITE, true), lp(10))
                if (h.optString("video").isNotEmpty()) c.addView(label(h.optString("video"), 13f, MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, lp(2))
            }
            histBox.addView(c, lp(8))
        }
    }

    private fun refreshBrain() {
        brainBox.removeAllViews()
        if (p.scrollEnabled) brainBox.addView(BrainMeter.card(this, p))
    }

    /** The simple home numbers: watched, quiz average, streak, and per-YouTuber time. */
    private fun refreshSummary() {
        summaryBox.removeAllViews()
        val st = Stats.load(this)
        val watch = Stats.watchSeconds(this)
        val q = st.optInt(Stats.QUIZ_QUESTIONS)
        val avg = if (q > 0) "${st.optInt(Stats.QUIZ_CORRECT) * 100 / q}%" else "–"
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((v, n) in listOf(Stats.hours(watch.values.sum()) to "watched", avg to "quiz average", "${streak()}" to "day streak")) {
            val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            c.addView(label(v, 26f, Color.WHITE, true))
            c.addView(label(n, 13f, MUTED))
            row.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        summaryBox.addView(row)
        val names = (YouTube.channelList(p) + watch.keys.filter { it != "Other" && (watch[it] ?: 0.0) > 60 })
            .distinct().sortedByDescending { watch[it] ?: 0.0 }.take(5)
        if (names.isNotEmpty()) {
            summaryBox.addView(android.view.View(this).apply { setBackgroundColor(0xFF2C2C30.toInt()) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(14) })
            for (n in names) {
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                r.addView(label(n, 15f, Color.WHITE).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                r.addView(label(Stats.hours(watch[n] ?: 0.0), 15f, ORANGE, true))
                summaryBox.addView(r, lp(10))
            }
        }
        if (p.scrollEnabled) {
            val today = Scroll.timeByApp(this, 1).values.sum()
            val ci = Scroll.log(this).count { System.currentTimeMillis() - it.optLong("t") < 86_400_000L && SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(it.optLong("t"))) == Session.today() }
            summaryBox.addView(label("📱  Scrolling today: ${Stats.hours(today)}" + (if (ci > 0) " · $ci check-in${if (ci == 1) "" else "s"}" else ""), 14f, MUTED), lp(14))
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
            permRow("First: allow restricted settings", "App info → ⋮ (top right) → Allow restricted settings.", false) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }

        permRow("Display over other apps", "So the lock can open over other apps.", overlayOn) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        permRow("Accessibility", "Spots your unlock and keeps you on the video.", accessOn) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        permRow("Notification access", "So messages can still get through.", notifOn) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        permRow("Show notifications", "For the break timer.", postOn) {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        permRow("Unrestricted battery", "So Android doesn't switch the lock off.", batteryOn) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        // Switched on but not actually running (Android sometimes stops it after an update).
        if (accessOn && EnforcerService.instance == null) {
            permRow("Restart Accessibility", "It's on but not running. Turn Locked In off, then on again.", false) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        // No AI quiz key at all (Gemini or OpenRouter or Claude).
        val anyKey = (p.useGemini && p.geminiKey.isNotBlank()) || (p.useOpenRouter && p.openRouterKey.isNotBlank()) || (p.useClaude && p.claudeKey.isNotBlank())
        if (!anyKey && !p.sp.getBoolean("setupSkipKey", false))
            permRow("Add a quiz key", "Free from Gemini or OpenRouter, so you get real quizzes. Settings → Quiz.", false) { openSettings("quiz") }
        // A newer build is out.
        val latest = Updater.latestKnown(this)
        if (latest > Updater.currentBuild(this))
            permRow("Update available: build $latest", "You have build ${Updater.currentBuild(this)}. Tap to update.", false) { Updater.check(this) }
        if (!Quiz.hasProvider(p) && !p.useBuiltIn) {
            permBox.addView(label("No quiz maker is on. Open Settings → Quiz and switch one on, or you'll write a summary instead of a quiz.", 13f, RED), lp(10))
        }
        val allDone = permBox.childCount == 0
        permTitle?.text = if (permBox.childCount == 1) "Needs your attention (1)" else "Needs your attention (${permBox.childCount})"
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
            background = rounded(FIELD)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        col.addView(et, lp(4))
        savers.add { save(et.text.toString()) }
    }

    private val SUGGESTED = listOf(
        "Alex Hormozi", "Jeremy Miner", "Leila Hormozi", "Codie Sanchez", "The Diary Of A CEO",
        "Ali Abdaal", "Iman Gadzhi", "Andrew Huberman", "Chris Williamson", "Grant Cardone",
        "Russell Brunson", "Dan Martell", "Tony Robbins", "Simon Sinek", "My First Million",
    )

    /** Your YouTubers as removable chips, an "add" box, and one-tap suggestions. */
    private fun channelEditor(col: LinearLayout) {
        col.addView(label("YouTubers (tap ✕ to remove)", 13f, MUTED), lp(14))
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(box, lp(4))
        val sugBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        fun setList(list: List<String>) {
            p.channels = list.distinctBy { it.lowercase() }.joinToString(", ")
            p.poolFetchedAt = 0L // rebuild the video list with the new channels
        }
        lateinit var redraw: () -> Unit
        redraw = {
            box.removeAllViews()
            val list = YouTube.channelList(p)
            for (name in list) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(10), dp(8), dp(10))
                    background = rounded(FIELD)
                }
                row.addView(label(name, 15f, Color.WHITE), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val x = label("✕", 18f, MUTED).apply {
                    setPadding(dp(12), dp(2), dp(8), dp(2))
                    setOnClickListener {
                        if (list.size <= 1) { toast("Keep at least one YouTuber"); return@setOnClickListener }
                        setList(list - name); redraw()
                    }
                }
                row.addView(x)
                box.addView(row, lp(6))
            }
            sugBox.removeAllViews()
            val sugg = SUGGESTED.filter { s -> list.none { it.equals(s, true) } }
            if (sugg.isNotEmpty()) {
                sugBox.addView(label("Suggestions (tap to add)", 12f, MUTED), lp(8))
                sugg.chunked(2).forEach { pair ->
                    val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    for (sname in pair) {
                        val chip = label("+ $sname", 13f, Color.WHITE).apply {
                            setPadding(dp(12), dp(8), dp(12), dp(8))
                            background = rounded(0xFF333338.toInt())
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            setOnClickListener { setList(YouTube.channelList(p) + sname); redraw() }
                        }
                        r.addView(chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
                    }
                    if (pair.size == 1) r.addView(android.view.View(this), LinearLayout.LayoutParams(0, 1, 1f))
                    sugBox.addView(r, lp(6))
                }
            }
        }
        val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val input = EditText(this).apply {
            hint = "Add a YouTuber (channel name)"
            setHintTextColor(MUTED)
            setTextColor(Color.WHITE)
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            background = rounded(FIELD)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        addRow.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addRow.addView(button("Add", ORANGE) {
            val v = input.text.toString().trim()
            if (v.isNotEmpty()) { setList(YouTube.channelList(p) + v); input.setText(""); redraw() }
        }, LinearLayout.LayoutParams(dp(80), dp(46)).apply { marginStart = dp(8) })
        col.addView(addRow, lp(8))
        col.addView(sugBox)
        redraw()
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
        box.addView(button(label, FIELD) {
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
            background = rounded(FIELD)
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
