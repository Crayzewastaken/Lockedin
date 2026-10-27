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
    }

    private fun build() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(32), dp(20), dp(40))
        }
        col.addView(label("Locked In", 30f, Color.WHITE, true))
        col.addView(label("Your first unlock each morning opens a 30+ minute Hormozi or Jeremy Miner video, then a quiz. Your phone is locked until you pass.", 15f, MUTED), lp(6))

        statusBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(section("Status"))
        col.addView(statusBox)

        permBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(section("Setup (do these once)"))
        col.addView(permBox)

        col.addView(section("Settings"))
        val enabledSw = Switch(this).apply {
            text = "Locked In is on"
            setTextColor(Color.WHITE)
            isChecked = p.enabled
        }
        col.addView(enabledSw, lp(8))
        savers.add { p.enabled = enabledSw.isChecked }

        col.addView(label("Quiz makers (tried top to bottom; switch off any you don't want)", 13f, MUTED), lp(14))
        toggle(col, "Gemini", p.useGemini) { p.useGemini = it }
        toggle(col, "OpenRouter", p.useOpenRouter) { p.useOpenRouter = it }
        toggle(col, "Claude (paid)", p.useClaude) { p.useClaude = it }
        toggle(col, "Built-in fill-in-the-blank (no AI)", p.useBuiltIn) { p.useBuiltIn = it }
        textField(col, "Gemini API key (free, for the quiz)", p.geminiKey, secret = true) { p.geminiKey = it.trim() }
        textField(col, "Gemini model", p.geminiModel) { p.geminiModel = it.trim() }
        col.addView(button("Test Gemini key", CARD) {
            savers.forEach { it() }
            toast("Testing…")
            Thread {
                val result = try {
                    val model = Quiz.testGemini(p)
                    "✅ Working (model: $model). Quizzes are ready to go."
                } catch (e: Exception) {
                    "❌ ${e.message}"
                }
                runOnUiThread {
                    if (!isFinishing) AlertDialog.Builder(this)
                        .setTitle("Gemini test")
                        .setMessage(result)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }.start()
        }, lp(8))
        textField(col, "OpenRouter API key (free backup for the quiz)", p.openRouterKey, secret = true) { p.openRouterKey = it.trim() }
        textField(col, "OpenRouter model", p.openRouterModel) { p.openRouterModel = it.trim() }
        col.addView(button("Test OpenRouter key", CARD) {
            savers.forEach { it() }
            toast("Testing… (free models can take up to a minute)")
            Thread {
                val result = try {
                    "✅ Working. It replied: ${Quiz.testOpenRouter(p)}"
                } catch (e: Exception) {
                    "❌ ${e.message}"
                }
                runOnUiThread {
                    if (!isFinishing) AlertDialog.Builder(this)
                        .setTitle("OpenRouter test")
                        .setMessage(result)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }.start()
        }, lp(8))
        textField(col, "Claude API key (optional, paid)", p.claudeKey, secret = true) { p.claudeKey = it.trim() }
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
            permBox.addView(label("Android blocks steps 2 and 3 for apps installed outside the Play Store until you allow it: tap below → ⋮ menu (top right) → \"Allow restricted settings\". If you don't see that option, try step 2 first, then come back.", 13f, MUTED), lp(8))
            permBox.addView(button("Open app info (allow restricted settings)", CARD) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }, lp(6))
        }

        permRow("1. Display over other apps", "Lets the lock open on top of whatever you're doing.", overlayOn) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        permRow("2. Accessibility: Locked In", "Detects your unlock and brings the video back if you leave it. Settings → Accessibility → Locked In → On.", accessOn) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        permRow("3. Notification access", "Lets Messenger/SMS open only when a new message actually arrives.", notifOn) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        permRow("4. Show notifications", "For the break countdown.", postOn) {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        permRow("5. Unrestricted battery", "Stops Android from killing the lock overnight.", batteryOn) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        if (!Quiz.hasProvider(p)) {
            permBox.addView(label("6. Add a free quiz key below. Gemini: aistudio.google.com/apikey. Backup: openrouter.ai/keys. Without one you get a basic fill-in-the-blank quiz (or a written summary if the video has no transcript).", 13f, RED), lp(10))
        }
    }

    private fun permRow(title: String, hint: String, ok: Boolean, action: () -> Unit) {
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
