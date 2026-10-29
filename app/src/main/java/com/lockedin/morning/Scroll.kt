package com.lockedin.morning

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * Scroll check-ins: time spent in Instagram / TikTok (or any apps you list) is counted,
 * and every 15–20 minutes of it you get a full-screen "what did you gain?" check-in.
 */
object Scroll {
    private const val FILE = "scroll"

    // Live counters (kept in memory by the always-running service).
    var currentApp = ""
    var sinceCheckInSec = 0.0
    var nextPromptSec = 0.0
    var awaySince = 0L
    var promptShowing = false

    // Daily per-app time is buffered and saved every ~20 s.
    private val pending = HashMap<String, Double>()
    private var lastFlush = 0L

    fun apps(p: Prefs): Set<String> =
        p.scrollApps.split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private var intervalKey = ""
    var lastSeenApp = ""
    var lastSeenAt = 0L

    fun newInterval(p: Prefs): Double {
        val lo = p.scrollMinMin.coerceAtLeast(1)
        val hi = p.scrollMaxMin.coerceAtLeast(lo)
        intervalKey = "$lo-$hi"
        return Random.nextInt(lo * 60, hi * 60 + 1).toDouble()
    }

    /** Pick a new target if there isn't one, or if you changed the minutes in settings. */
    fun ensureInterval(p: Prefs) {
        val key = "${p.scrollMinMin.coerceAtLeast(1)}-${p.scrollMaxMin.coerceAtLeast(p.scrollMinMin.coerceAtLeast(1))}"
        if (nextPromptSec <= 0 || key != intervalKey) nextPromptSec = newInterval(p)
    }

    /** One line for settings: is it counting, and how long until the next check-in. */
    fun status(ctx: Context, p: Prefs): String {
        if (!p.scrollEnabled) return "Off."
        if (p.active) return "Paused while the morning lock is running."
        val seen = if (lastSeenAt == 0L) "Not seen Instagram/TikTok yet since the app started."
            else "Last seen: ${appName(ctx, lastSeenApp)} ${((System.currentTimeMillis() - lastSeenAt) / 1000)}s ago."
        if (nextPromptSec <= 0) return "Waiting for you to open a watched app. $seen"
        val done = sinceCheckInSec.toInt()
        val target = nextPromptSec.toInt()
        return "Scrolled ${done / 60}m ${done % 60}s of ${target / 60}m ${target % 60}s before the next check-in. $seen"
    }

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Synchronized
    fun addTime(ctx: Context, pkg: String, sec: Double) {
        pending[pkg] = (pending[pkg] ?: 0.0) + sec
        if (System.currentTimeMillis() - lastFlush > 20_000) flush(ctx)
    }

    @Synchronized
    fun flush(ctx: Context) {
        lastFlush = System.currentTimeMillis()
        if (pending.isEmpty()) return
        val day = Session.today()
        val j = try { JSONObject(sp(ctx).getString("time", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        val d = j.optJSONObject(day) ?: JSONObject()
        for ((k, v) in pending) d.put(k, d.optDouble(k, 0.0) + v)
        j.put(day, d)
        // keep 60 days
        val keys = j.keys().asSequence().toList().sorted()
        for (k in keys.dropLast(60)) j.remove(k)
        pending.clear()
        sp(ctx).edit().putString("time", j.toString()).apply()
    }

    /** Seconds per app for the last [days] days (1 = today). */
    @Synchronized
    fun timeByApp(ctx: Context, days: Int): Map<String, Double> {
        val j = try { JSONObject(sp(ctx).getString("time", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        val out = HashMap<String, Double>()
        val cal = java.util.Calendar.getInstance()
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        for (i in 0 until days) {
            val d = j.optJSONObject(fmt.format(cal.time))
            if (d != null) for (k in d.keys()) out[k] = (out[k] ?: 0.0) + d.optDouble(k, 0.0)
            cal.add(java.util.Calendar.DAY_OF_MONTH, -1)
        }
        if (days >= 1) for ((k, v) in pending) out[k] = (out[k] ?: 0.0) + v
        return out
    }

    /** How many of the last [days] days have any tracked time (at least 1). */
    @Synchronized
    fun daysTracked(ctx: Context, days: Int): Int {
        val j = try { JSONObject(sp(ctx).getString("time", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        val cal = java.util.Calendar.getInstance()
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        var n = 0
        for (i in 0 until days) {
            if (j.has(fmt.format(cal.time)) || i == 0) n++
            cal.add(java.util.Calendar.DAY_OF_MONTH, -1)
        }
        return n.coerceAtLeast(1)
    }

    @Synchronized
    fun logCheckIn(ctx: Context, pkg: String, minutes: Int, gain: String, note: String, decision: String) {
        val arr = try { JSONArray(sp(ctx).getString("log", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        arr.put(
            JSONObject()
                .put("t", System.currentTimeMillis())
                .put("app", pkg)
                .put("min", minutes)
                .put("gain", gain)
                .put("note", note)
                .put("decision", decision)
        )
        val out = JSONArray()
        for (i in maxOf(0, arr.length() - 300) until arr.length()) out.put(arr.get(i))
        sp(ctx).edit().putString("log", out.toString()).apply()
    }

    fun log(ctx: Context): List<JSONObject> {
        val arr = try { JSONArray(sp(ctx).getString("log", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    fun appName(ctx: Context, pkg: String): String = when (pkg) {
        "com.instagram.android" -> "Instagram"
        "com.zhiliaoapp.musically", "com.ss.android.ugc.trill" -> "TikTok"
        else -> try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg.substringAfterLast('.') }
    }
}
