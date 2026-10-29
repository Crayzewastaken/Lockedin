package com.lockedin.morning

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Lifetime numbers for the dashboard. Stored as one JSON blob in its own prefs file. */
object Stats {
    private const val FILE = "stats"
    private const val KEY = "json"

    // Watch time is added every second, so it's buffered in memory and saved every ~15 s.
    private val pendingWatch = HashMap<String, Double>()
    private var lastFlush = 0L

    const val PAUSES = "pauses"
    const val REWINDS = "rewinds"
    const val LATEST_JUMPS = "latestJumps"
    const val SKIP_ATTEMPTS = "skipAttempts"
    const val EMERGENCIES = "emergencies"
    const val SKIPPED_DAYS = "skippedDays"
    const val BREAKS = "breaks"
    const val BREAK_MIN = "breakMin"
    const val QUIZ_ATTEMPTS = "quizAttempts"
    const val QUIZ_PASSES = "quizPasses"
    const val QUIZ_CORRECT = "quizCorrect"
    const val QUIZ_QUESTIONS = "quizQuestions"
    const val SUMMARIES = "summaries"
    const val VIDEOS_DONE = "videosDone"
    const val SESSIONS = "sessions"

    @Synchronized
    fun load(ctx: Context): JSONObject {
        val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)
        return try { if (raw != null) JSONObject(raw) else JSONObject() } catch (_: Exception) { JSONObject() }
    }

    @Synchronized
    private fun save(ctx: Context, j: JSONObject) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, j.toString()).apply()
    }

    @Synchronized
    fun inc(ctx: Context, key: String, by: Int = 1) {
        val j = load(ctx)
        j.put(key, j.optInt(key, 0) + by)
        save(ctx, j)
    }

    /** Which bucket a channel name goes in. */
    /** Groups a video's channel name under the YouTuber you added in settings (or its own name). */
    fun bucket(ctx: Context, author: String): String {
        if (author.isBlank()) return "Other"
        val a = author.lowercase()
        if (a.contains("hormozi")) return "Alex Hormozi"
        if (a.contains("jeremy miner") || a.contains("7th level")) return "Jeremy Miner"
        for (name in YouTube.channelList(Prefs(ctx))) if (YouTube.matches(author, name)) return name
        return author.trim()
    }

    @Synchronized
    fun addWatch(ctx: Context, author: String, seconds: Double) {
        if (seconds <= 0) return
        val b = bucket(ctx, author)
        pendingWatch[b] = (pendingWatch[b] ?: 0.0) + seconds
        val now = System.currentTimeMillis()
        if (now - lastFlush > 15_000) flush(ctx)
    }

    @Synchronized
    fun flush(ctx: Context) {
        lastFlush = System.currentTimeMillis()
        if (pendingWatch.isEmpty()) return
        val j = load(ctx)
        val w = j.optJSONObject("watch") ?: JSONObject()
        for ((k, v) in pendingWatch) w.put(k, w.optDouble(k, 0.0) + v)
        j.put("watch", w)
        pendingWatch.clear()
        save(ctx, j)
    }

    @Synchronized
    fun videoDone(ctx: Context, author: String) {
        val j = load(ctx)
        j.put(VIDEOS_DONE, j.optInt(VIDEOS_DONE) + 1)
        val v = j.optJSONObject("videosBy") ?: JSONObject()
        val b = bucket(ctx, author)
        v.put(b, v.optInt(b) + 1)
        j.put("videosBy", v)
        save(ctx, j)
    }

    @Synchronized
    fun quiz(ctx: Context, correct: Int, total: Int, passed: Boolean) {
        val j = load(ctx)
        j.put(QUIZ_ATTEMPTS, j.optInt(QUIZ_ATTEMPTS) + 1)
        if (passed) j.put(QUIZ_PASSES, j.optInt(QUIZ_PASSES) + 1)
        j.put(QUIZ_CORRECT, j.optInt(QUIZ_CORRECT) + correct)
        j.put(QUIZ_QUESTIONS, j.optInt(QUIZ_QUESTIONS) + total)
        val pct = if (total > 0) correct * 100 / total else 0
        j.put("bestPct", maxOf(j.optInt("bestPct", 0), pct))
        val scores = j.optJSONArray("scores") ?: JSONArray()
        scores.put(pct)
        val trimmed = JSONArray()
        for (i in maxOf(0, scores.length() - 30) until scores.length()) trimmed.put(scores.optInt(i))
        j.put("scores", trimmed)
        save(ctx, j)
    }

    /** Watch seconds per bucket, including anything not yet flushed. */
    @Synchronized
    fun watchSeconds(ctx: Context): Map<String, Double> {
        val w = load(ctx).optJSONObject("watch") ?: JSONObject()
        val out = HashMap<String, Double>()
        for (k in w.keys()) out[k] = w.optDouble(k, 0.0)
        for ((k, v) in pendingWatch) out[k] = (out[k] ?: 0.0) + v
        return out
    }

    fun hours(sec: Double): String {
        val m = (sec / 60).toInt()
        return if (m < 60) "${m}m" else "${m / 60}h ${m % 60}m"
    }
}
