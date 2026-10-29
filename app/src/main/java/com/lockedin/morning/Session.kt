package com.lockedin.morning

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** The daily session: when it starts, its phases, extensions, and how it ends. */
object Session {
    const val PICKING = "PICKING"
    const val VIDEO = "VIDEO"
    const val QUIZ = "QUIZ"

    const val EXT_TOTAL_MIN = 15
    const val EXT_MAX_USES = 3

    fun today(now: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))

    /** An active session from a previous day that never finished. */
    fun isStale(p: Prefs): Boolean = p.active && p.sessionDay.isNotEmpty() && p.sessionDay != today()

    fun checkStale(ctx: Context) {
        val p = Prefs(ctx)
        if (isStale(p)) finish(ctx, "Missed (session never finished)")
    }

    /** True if an alarm (from any clock app) is due within the buffer — i.e. you're probably not up yet. */
    fun alarmSoon(ctx: Context, p: Prefs): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return false
        val next = am.nextAlarmClock ?: return false
        val diff = next.triggerTime - System.currentTimeMillis()
        return diff > 0 && diff <= p.alarmBufferMin * 60_000L
    }

    /** True during your work hours: no lock at all (an active session waits until after). */
    fun inWorkHours(p: Prefs): Boolean {
        if (!p.workEnabled) return false
        val c = Calendar.getInstance()
        val dow = c.get(Calendar.DAY_OF_WEEK)
        if (p.workWeekdaysOnly && (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY)) return false
        val m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return m >= p.workStartMin && m < p.workEndMin
    }

    fun hhmm(min: Int): String {
        val h = min / 60
        val m = min % 60
        val ampm = if (h < 12) "am" else "pm"
        val h12 = if (h % 12 == 0) 12 else h % 12
        return if (m == 0) "$h12$ampm" else String.format("%d:%02d%s", h12, m, ampm)
    }

    fun parseHhmm(s: String): Int? {
        val m = Regex("^\\s*(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\s*$", RegexOption.IGNORE_CASE).find(s) ?: return null
        var h = m.groupValues[1].toInt()
        val mi = m.groupValues[2].ifEmpty { "0" }.toInt()
        val ap = m.groupValues[3].lowercase()
        if (ap == "pm" && h < 12) h += 12
        if (ap == "am" && h == 12) h = 0
        if (h > 23 || mi > 59) return null
        return h * 60 + mi
    }

    fun shouldStart(ctx: Context): Boolean {
        checkStale(ctx)
        val p = Prefs(ctx)
        if (!p.enabled || p.active) return false
        if (p.completedDay == today()) return false
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (hour < p.windowStartHour || hour >= p.windowEndHour) return false
        if (alarmSoon(ctx, p)) return false
        if (inWorkHours(p)) return false
        return true
    }

    fun start(ctx: Context) {
        val p = Prefs(ctx)
        p.sessionDay = today()
        p.sessionStartedAt = System.currentTimeMillis()
        p.phase = PICKING
        p.pickError = ""
        p.pickChosen = false
        p.playerErrors = 0
        p.videoId = ""
        p.videoTitle = ""
        p.videoAuthor = ""
        p.videoDuration = 0
        p.positionSec = 0
        p.watchedSec = 0
        p.extUses = 0
        p.extMinutesUsed = 0
        p.extUntil = 0L
        p.msgUsedSec = 0
        resetQuiz(p)
        p.quizRound = 0
        p.quizPrevQs = ""
        p.active = true
        Stats.inc(ctx, Stats.SESSIONS)
        launch(ctx, null)
    }

    fun resetQuiz(p: Prefs) {
        p.quizJson = ""
        p.quizAnswers = ""
        p.quizStartedAt = 0L
        p.quizFails = 0
    }

    fun applyPick(ctx: Context, v: YouTube.Picked) {
        val p = Prefs(ctx)
        saveTranscript(ctx, v.transcript)
        p.videoId = v.id
        p.videoTitle = v.title
        p.videoAuthor = v.author
        p.videoDuration = v.lengthSec
        p.positionSec = 0
        p.watchedSec = 0
        p.pickError = ""
        resetQuiz(p)
        p.phase = VIDEO
    }

    fun launch(ctx: Context, msg: String?) {
        val i = Intent(ctx, LockActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (msg != null) i.putExtra("msg", msg)
        try {
            ctx.startActivity(i)
        } catch (_: Exception) {
        }
    }

    // ---------- Extensions ----------
    fun inExtension(p: Prefs): Boolean = p.extUntil > System.currentTimeMillis()
    fun extMinutesLeft(p: Prefs): Int = (EXT_TOTAL_MIN - p.extMinutesUsed).coerceAtLeast(0)
    fun extUsesLeft(p: Prefs): Int = (EXT_MAX_USES - p.extUses).coerceAtLeast(0)

    fun startExtension(p: Prefs, minutes: Int): Boolean {
        if (minutes > extMinutesLeft(p) || extUsesLeft(p) <= 0) return false
        p.extUses = p.extUses + 1
        p.extMinutesUsed = p.extMinutesUsed + minutes
        p.extUntil = System.currentTimeMillis() + minutes * 60_000L
        return true
    }

    // ---------- Transcript ----------
    private fun transcriptFile(ctx: Context) = File(ctx.filesDir, "transcript.txt")
    fun saveTranscript(ctx: Context, text: String) = transcriptFile(ctx).writeText(text)
    fun readTranscript(ctx: Context): String =
        transcriptFile(ctx).let { if (it.exists()) it.readText() else "" }

    // ---------- Watched list ----------
    fun watchedSet(p: Prefs): MutableList<String> =
        p.watchedIds.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()

    fun markWatched(p: Prefs, id: String) {
        val list = watchedSet(p)
        list.remove(id)
        list.add(id)
        p.watchedIds = list.takeLast(400).joinToString(",")
    }

    // ---------- End ----------
    fun finish(ctx: Context, result: String, note: String = "") {
        val p = Prefs(ctx)
        val hist = try { JSONArray(p.historyJson) } catch (_: Exception) { JSONArray() }
        hist.put(
            JSONObject()
                .put("day", p.sessionDay.ifEmpty { today() })
                .put("result", result)
                .put("video", p.videoTitle)
                .put("author", p.videoAuthor)
                .put("extMin", p.extMinutesUsed)
                .put("msgSec", p.msgUsedSec)
                .put("quizRounds", p.quizRound + 1)
                .put("startedAt", p.sessionStartedAt)
                .put("endedAt", System.currentTimeMillis())
                .put("note", note)
        )
        val trimmed = JSONArray()
        val from = (hist.length() - 90).coerceAtLeast(0)
        for (i in from until hist.length()) trimmed.put(hist.get(i))
        p.historyJson = trimmed.toString()

        if (p.videoId.isNotEmpty()) markWatched(p, p.videoId)
        p.completedDay = p.sessionDay.ifEmpty { today() }
        p.active = false
        p.phase = ""
        p.extUntil = 0L
        resetQuiz(p)
        EnforcerService.instance?.clearExtNotification()
    }

    fun history(p: Prefs): List<JSONObject> {
        val arr = try { JSONArray(p.historyJson) } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }
}
