package com.lockedin.morning

import android.content.Context
import android.util.Log

/**
 * Writes the scroll check-in "lesson" questions ahead of time: starts as soon as a video is
 * picked (while you watch), retries if the AI fails, and tops up while you scroll, so the
 * check-in quiz is ready the moment it opens.
 */
object Lessons {
    private const val MIN_READY = 8
    @Volatile private var bgRunningFor = ""
    @Volatile private var lastTry = 0L
    private val lock = Any()

    /** Background: make lessons for the current/last video, retrying up to 6 times (every 90s). */
    fun ensureAsync(ctx: Context) {
        val app = ctx.applicationContext
        val p = Prefs(app)
        val id = p.videoId.ifEmpty { p.sp.getString("lastVideoId", "") ?: "" }
        if (id.isEmpty() || Bank.hasLessons(app, id) || !Quiz.hasProvider(p)) return
        if (bgRunningFor == id) return
        bgRunningFor = id
        val transcript = Session.readTranscript(app)
        val title = p.videoTitle.ifEmpty { p.sp.getString("lastVideoTitle", "") ?: "" }
        val author = p.videoAuthor.ifEmpty { p.sp.getString("lastVideoAuthor", "") ?: "" }
        val day = p.sessionDay.ifEmpty { Session.today() }
        Thread {
            try {
                for (attempt in 1..6) {
                    if (make(app, transcript, id, title, author, day)) break
                    Thread.sleep(90_000)
                }
            } catch (_: InterruptedException) {
            } finally { bgRunningFor = "" }
        }.start()
    }

    /** Called from the scroll tracker: if the check-in has too few questions ready, top up (at most every 3 min). */
    fun topUp(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - lastTry < 3 * 60_000L) return
        lastTry = now
        if (Bank.lessonCount(ctx) >= MIN_READY && Bank.hasLessons(ctx, Prefs(ctx).sp.getString("lastVideoId", "") ?: "")) return
        ensureAsync(ctx)
    }

    /** Blocking (check-in fallback): waits for any background run, else tries once now. */
    fun makeForLatest(ctx: Context): Boolean {
        val p = Prefs(ctx)
        val id = p.videoId.ifEmpty { p.sp.getString("lastVideoId", "") ?: "" }
        if (!Quiz.hasProvider(p) || id.isEmpty()) return false
        return make(ctx, Session.readTranscript(ctx), id,
            p.videoTitle.ifEmpty { p.sp.getString("lastVideoTitle", "") ?: "" },
            p.videoAuthor.ifEmpty { p.sp.getString("lastVideoAuthor", "") ?: "" },
            p.sessionDay.ifEmpty { Session.today() })
    }

    private fun make(ctx: Context, transcript: String, id: String, title: String, author: String, day: String): Boolean =
        synchronized(lock) {
            if (Bank.hasLessons(ctx, id)) return true
            try {
                val qs = Quiz.lessons(Prefs(ctx), transcript, id, title, author)
                Bank.addLessons(ctx, qs, id, title, author, day)
                Log.i("LockedIn", "lessons ready: ${qs.size} for $id")
                true
            } catch (e: Exception) {
                Log.w("LockedIn", "lessons failed: ${e.message}")
                Prefs(ctx).sp.edit().putString("lessonError", e.message ?: "unknown").apply()
                false
            }
        }
}
