package com.lockedin.morning

import android.Manifest
import android.app.Activity
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
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * First-run setup guide. One step per screen: what it is, a button that does it,
 * and "Next" only unlocks once the step is actually done.
 */
class SetupActivity : Activity() {

    companion object {
        private const val ORANGE = 0xFFFF7A1A.toInt()
        private const val CARD = 0xFF1C1C1E.toInt()
        private const val FIELD = 0xFF2C2C30.toInt()
        private const val MUTED = 0xFFA0A0A8.toInt()
        private const val GREEN = 0xFF2E9E5B.toInt()
        private const val SELECTED = 0xFF3A2A1C.toInt()
    }

    private class Step(
        val icon: String,
        val title: String,
        val body: String,
        val actionLabel: String? = null,
        val action: (() -> Unit)? = null,
        val done: () -> Boolean = { true },
        val waitingHint: String = "",
        val extra: ((LinearLayout) -> Unit)? = null,
    )

    private lateinit var p: Prefs
    private var index = 0
    private lateinit var steps: List<Step>
    private var visitedAppInfo = false
    private var geminiOk = false
    private var openRouterOk = false
    private var provider = "gemini"
    private var testing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Prefs(this)
        visitedAppInfo = p.sp.getBoolean("setupVisitedAppInfo", false)
        geminiOk = p.sp.getString("gemini_working_model", "").orEmpty().isNotEmpty() && p.geminiKey.isNotBlank()
        openRouterOk = p.sp.getBoolean("openRouterOk", false) && p.openRouterKey.isNotBlank()
        provider = if (openRouterOk && !geminiOk) "openrouter" else "gemini"
        index = p.sp.getInt("setupStep", 0)
        steps = buildSteps()
        index = index.coerceIn(0, steps.lastIndex)
        render()
    }

    override fun onResume() {
        super.onResume()
        render() // a setting may have just been switched on
    }

    @Deprecated("Back goes to the previous step")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (index > 0) go(index - 1) else super.onBackPressed()
    }

    // ------------------------------------------------------------------ checks

    private fun overlayOn() = Settings.canDrawOverlays(this)
    private fun accessOn() = (Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").contains("$packageName/")
    private fun notifAccessOn() = NotificationWatcher.isEnabled(this)
    private fun postOn() = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    private fun batteryOn() = getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true
    private fun installOn() = Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()

    private fun open(intent: Intent) {
        try { startActivity(intent) } catch (_: Exception) {
            Toast.makeText(this, "Couldn't open that screen, please find it in your phone's Settings.", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------ steps

    private fun buildSteps(): List<Step> {
        val list = mutableListOf<Step>()
        list += Step("👋", "Welcome to Locked In",
            "Each morning, your first unlock opens a 30+ minute video from YouTubers you choose. Watch it, pass a short quiz, and your phone is yours.\n\nThis guide takes about 3 minutes. Each step has a button that does it. \"Next\" unlocks once it's done.")

        list += Step("🪟", "Show over other apps",
            "So the lock can appear on top of whatever's open.\n\nTap the button, switch Locked In on, then come back.",
            "Open setting", { open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) },
            { overlayOn() }, "Waiting for: Locked In switched on")

        if (Build.VERSION.SDK_INT >= 33) list += Step("🔓", "Allow restricted settings",
            "Android hides the next two switches for apps installed outside the Play Store.\n\nTap the button → tap ⋮ (top right) → \"Allow restricted settings\" → confirm. Then come back.\n\nNo ⋮ option? Do the next step first; Android shows it after you try once.",
            "Open app info", {
                visitedAppInfo = true
                p.sp.edit().putBoolean("setupVisitedAppInfo", true).apply()
                open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            },
            { visitedAppInfo || accessOn() }, "Tap the button first")

        list += Step("♿", "Turn on Accessibility",
            "This is the heart of the app: it notices your first unlock, keeps you on the video, and runs the scroll check-ins.\n\nTap the button → Locked In (it may be under \"Installed apps\" or \"Downloaded apps\") → switch on → Allow.",
            "Open Accessibility", { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            { accessOn() }, "Waiting for: Accessibility → Locked In → On")

        list += Step("💬", "Notification access",
            "So Messenger and texts can still get through while you're locked in, but only when a real message arrives.\n\nTap the button → Locked In → Allow.",
            "Open setting", { open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
            { notifAccessOn() }, "Waiting for: Locked In allowed")

        if (Build.VERSION.SDK_INT >= 33) list += Step("🔔", "Allow notifications",
            "Used for the break countdown.",
            "Allow", { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) },
            { postOn() }, "Waiting for: Allow")

        list += Step("🔋", "Unrestricted battery",
            "Stops Android from quietly switching Locked In off overnight.\n\nTap the button → Allow. (Samsung: also set Battery → Unrestricted.)",
            "Allow", {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            },
            { batteryOn() }, "Waiting for: Allow")

        if (Build.VERSION.SDK_INT >= 26) list += Step("⬇️", "Allow updates",
            "So the \"Update\" button can install new versions without reinstalling.\n\nTap the button → switch \"Allow from this source\" on → come back.",
            "Open setting", { open(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) },
            { installOn() }, "Waiting for: Allow from this source")

        list += Step("📺", "Pick your YouTubers",
            "Tap to add or remove. You'll choose one of their videos each morning.",
            done = { YouTube.channelList(p).isNotEmpty() }, waitingHint = "Pick at least one",
            extra = { channels(it) })

        list += Step("⏱", "How long should videos be?",
            "Each morning you'll get videos about this long. You can change it any time in Settings.",
            extra = { lengths(it) })

        list += Step("🎯", "Weekly learning goal",
            "How much do you want to learn a day? It adds up per week. Fall behind and you can catch up. On Sunday the lock makes you finish what's left.",
            extra = { goals(it) })

        list += Step("🧠", "Set up the quiz",
            "A free AI key lets the app write a real quiz about each video. Pick one (or set up both, the second is a backup):",
            done = { geminiOk || openRouterOk || p.sp.getBoolean("setupSkipKey", false) },
            waitingHint = "Waiting for: a key that passes Test",
            extra = { quizKey(it) })

        list += Step("💼", "Work hours",
            "The lock never starts during work, and pauses if work starts mid-video.",
            extra = { work(it) })

        list += Step("📱", "Scroll check-ins",
            "After 15–20 minutes on Instagram or TikTok: \"Were you just doom scrolling?\", then a quick quiz on your past videos. Pass it to carry on.",
            extra = { scroll(it) })

        list += Step("✅", "You're all set",
            "Tomorrow, after your alarm, your first unlock opens the video picker.\n\nWant to try it now? Use \"Start a session now\" on the home screen.")
        return list
    }

    // ------------------------------------------------------------------ rendering

    private fun go(i: Int) {
        index = i.coerceIn(0, steps.lastIndex)
        p.sp.edit().putInt("setupStep", index).apply()
        render()
    }

    private fun render() {
        val s = steps[index]
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(36), dp(24), dp(32))
        }
        // progress
        col.addView(label("Step ${index + 1} of ${steps.size}", 13f, MUTED, true))
        col.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = steps.size
            progress = index + 1
            progressTintList = android.content.res.ColorStateList.valueOf(ORANGE)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(8) })

        col.addView(label(s.icon, 44f, Color.WHITE), lp(28))
        col.addView(label(s.title, 28f, Color.WHITE, true), lp(8))
        col.addView(label(s.body, 16f, 0xFFCFCFD4.toInt()), lp(12))

        val done = s.done()
        if (s.action != null) {
            col.addView(button(if (done && s.actionLabel != null) "✓ Done" else s.actionLabel ?: "Do it", if (done) FIELD else ORANGE) {
                s.action?.invoke()
            }, lp(24))
        }
        s.extra?.invoke(col)

        if (!done && s.waitingHint.isNotEmpty()) {
            col.addView(label("⏳  ${s.waitingHint}", 14f, ORANGE), lp(16))
        } else if (done && s.action != null) {
            col.addView(label("✓  Done", 15f, GREEN, true), lp(16))
        }

        // nav
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (index > 0) nav.addView(button("Back", CARD) { go(index - 1) },
            LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginEnd = dp(10) })
        val last = index == steps.lastIndex
        val next = button(if (last) "Finish" else if (index == 0) "Get started" else "Next", if (done) ORANGE else FIELD) {
            if (!s.done()) return@button
            if (last) finishSetup() else go(index + 1)
        }
        next.alpha = if (done) 1f else 0.5f
        nav.addView(next, LinearLayout.LayoutParams(0, dp(56), 2f))
        col.addView(nav, lp(32))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            fitsSystemWindows = true
            isFillViewport = true
            addView(col)
        })
    }

    private fun finishSetup() {
        p.sp.edit().putBoolean("onboarded", true).putInt("setupStep", 0).putBoolean("rebuildMain", true).apply()
        finish()
    }

    // ------------------------------------------------------------------ step contents

    private val SUGGESTED = listOf(
        "Alex Hormozi", "Jeremy Miner", "Leila Hormozi", "Codie Sanchez", "The Diary Of A CEO",
        "Ali Abdaal", "Iman Gadzhi", "Andrew Huberman", "Chris Williamson", "Grant Cardone",
        "Russell Brunson", "Dan Martell", "Tony Robbins", "Simon Sinek", "My First Million",
    )

    private fun channels(col: LinearLayout) {
        val mine = YouTube.channelList(p)
        val all = (mine + SUGGESTED).distinctBy { it.lowercase() }
        for (name in all) {
            val on = mine.any { it.equals(name, true) }
            col.addView(chip((if (on) "✓  " else "+  ") + name, on) {
                val cur = YouTube.channelList(p)
                val next = if (cur.any { it.equals(name, true) }) cur.filterNot { it.equals(name, true) } else cur + name
                p.channels = next.joinToString(", ")
                p.poolFetchedAt = 0L
                render()
            }, lp(8))
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val input = field("Someone else? Type a channel name")
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(button("Add", ORANGE) {
            val v = input.text.toString().trim()
            if (v.isNotEmpty()) {
                p.channels = (YouTube.channelList(p) + v).distinctBy { it.lowercase() }.joinToString(", ")
                p.poolFetchedAt = 0L
                render()
            }
        }, LinearLayout.LayoutParams(dp(80), dp(50)).apply { marginStart = dp(8) })
        col.addView(row, lp(12))
    }

    private fun lengths(col: LinearLayout) {
        val cur = Lengths.current(p)
        for (o in Lengths.options(p)) {
            val on = o == cur
            col.addView(chip((if (on) "✓  " else "") + o.label, on) { Lengths.set(p, o); render() }, lp(8))
        }
    }

    private fun goals(col: LinearLayout) {
        val opts = listOf(30 to "30 min a day (3½ h a week)", 60 to "1 hour a day (7 h a week)", 90 to "1½ hours a day (10½ h a week)",
            120 to "2 hours a day (14 h a week)", 0 to "No weekly goal")
        for ((m, t) in opts) {
            val on = p.dailyGoalMin == m
            col.addView(chip((if (on) "✓  " else "") + t, on) { p.dailyGoalMin = m; render() }, lp(8))
        }
    }

    private fun quizKey(col: LinearLayout) {
        // Which service: two tabs.
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((id, name) in listOf("gemini" to "Gemini", "openrouter" to "OpenRouter")) {
            val ok = if (id == "gemini") geminiOk else openRouterOk
            val t = chip((if (ok) "✓ " else "") + name, provider == id) { provider = id; render() }
            t.gravity = Gravity.CENTER
            tabs.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (id == "gemini") marginEnd = dp(8) })
        }
        col.addView(tabs, lp(16))

        val isGemini = provider == "gemini"
        val steps = if (isGemini)
            "1. Tap \"Get a free key\" and sign in with Google\n2. Tap \"Create API key\" and copy it\n3. Paste it below and tap Test"
        else
            "1. Tap \"Get a free key\" and sign up (no card needed)\n2. Tap \"Create Key\", give it any name, and copy it\n3. Paste it below and tap Test (free models can take up to a minute)"
        col.addView(label(steps, 15f, 0xFFCFCFD4.toInt()), lp(14))
        col.addView(button("Get a free ${if (isGemini) "Gemini" else "OpenRouter"} key", ORANGE) {
            open(Intent(Intent.ACTION_VIEW, Uri.parse(if (isGemini) "https://aistudio.google.com/apikey" else "https://openrouter.ai/keys")))
        }, lp(14))

        val key = field("Paste your ${if (isGemini) "Gemini" else "OpenRouter"} key here").apply {
            setText(if (isGemini) p.geminiKey else p.openRouterKey)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        col.addView(key, lp(12))
        val ok = if (isGemini) geminiOk else openRouterOk
        val result = label(if (ok) "✅ Key works" else "", 14f, GREEN)
        col.addView(button(if (testing) "Testing…" else "Test", if (ok) FIELD else ORANGE) {
            if (testing) return@button
            val k = key.text.toString().trim()
            if (k.isEmpty()) { result.setTextColor(0xFFD64545.toInt()); result.text = "Paste a key first"; return@button }
            if (isGemini) { p.geminiKey = k; p.useGemini = true } else { p.openRouterKey = k; p.useOpenRouter = true }
            testing = true
            result.setTextColor(MUTED); result.text = "Testing… (up to a minute)"
            Thread {
                val msg = try {
                    if (isGemini) "✅ Key works (${Quiz.testGemini(p)})" else { Quiz.testOpenRouter(p); "✅ Key works" }
                } catch (e: Exception) { "❌ ${e.message}" }
                runOnUiThread {
                    testing = false
                    val good = msg.startsWith("✅")
                    if (isGemini) geminiOk = good else {
                        openRouterOk = good
                        p.sp.edit().putBoolean("openRouterOk", good).apply()
                    }
                    render()
                    if (!good) Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            }.start()
        }, lp(8))
        col.addView(result, lp(8))
        // If only OpenRouter works, don't keep trying an empty Gemini first.
        if (openRouterOk && p.geminiKey.isBlank()) p.useGemini = false

        col.addView(label("Skip and use the basic built-in quiz instead", 14f, MUTED).apply {
            setPadding(0, dp(18), 0, 0)
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            setOnClickListener {
                p.sp.edit().putBoolean("setupSkipKey", true).apply()
                p.useBuiltIn = true
                render()
            }
        })
    }

    private fun work(col: LinearLayout) {
        col.addView(chip(if (p.workEnabled) "✓  Never lock during work hours" else "Never lock during work hours (off)", p.workEnabled) {
            p.workEnabled = !p.workEnabled; render()
        }, lp(16))
        if (!p.workEnabled) return
        val start = field("Starts (e.g. 8:30am)").apply { setText(Session.hhmm(p.workStartMin)) }
        val end = field("Ends (e.g. 4:30pm)").apply { setText(Session.hhmm(p.workEndMin)) }
        col.addView(label("Work starts", 13f, MUTED), lp(14)); col.addView(start, lp(4))
        col.addView(label("Work ends", 13f, MUTED), lp(10)); col.addView(end, lp(4))
        val save = {
            Session.parseHhmm(start.text.toString())?.let { p.workStartMin = it }
            Session.parseHhmm(end.text.toString())?.let { p.workEndMin = it }
        }
        start.setOnFocusChangeListener { _, f -> if (!f) save() }
        end.setOnFocusChangeListener { _, f -> if (!f) save() }
        col.addView(chip(if (p.workWeekdaysOnly) "✓  Weekdays only (Mon–Fri)" else "Every day", p.workWeekdaysOnly) {
            save(); p.workWeekdaysOnly = !p.workWeekdaysOnly; render()
        }, lp(10))
        // Save the typed times when they move on.
        col.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { save() }
        })
    }

    private fun scroll(col: LinearLayout) {
        col.addView(chip(if (p.scrollEnabled) "✓  On: quiz me while I scroll" else "Off", p.scrollEnabled) {
            p.scrollEnabled = !p.scrollEnabled; render()
        }, lp(16))
    }

    // ------------------------------------------------------------------ helpers

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun lp(top: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(top) }

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

    private fun chip(text: String, on: Boolean, onClick: () -> Unit) = label(text, 16f, if (on) Color.WHITE else 0xFFCFCFD4.toInt()).apply {
        setPadding(dp(16), dp(13), dp(16), dp(13))
        background = rounded(if (on) SELECTED else CARD)
        setOnClickListener { onClick() }
    }

    private fun field(hint: String) = EditText(this).apply {
        this.hint = hint
        setHintTextColor(MUTED)
        setTextColor(Color.WHITE)
        textSize = 16f
        inputType = InputType.TYPE_CLASS_TEXT
        background = rounded(FIELD)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun button(text: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = 17f
        typeface = Typeface.DEFAULT_BOLD
        background = rounded(color)
        stateListAnimator = null
        minHeight = dp(54)
        setOnClickListener { onClick() }
    }
}
