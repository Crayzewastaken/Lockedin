package com.lockedin.morning

import android.content.Context
import android.util.Log

/** Makes the check-in "lesson" questions for a finished video, in the background, once per video. */
object Lessons {
    @Volatile private var running = false

    /** Kick off generation for the current video (call while its transcript is still saved). */
    fun ensureAsync(ctx: Context) {
        val app = ctx.applicationContext
        val p = Prefs(app)
        val id = p.videoId
        if (id.isEmpty() || Bank.hasLessons(app, id) || !Quiz.hasProvider(p)) return
        val transcript = Session.readTranscript(app)
        val title = p.videoTitle; val author = p.videoAuthor; val day = p.sessionDay.ifEmpty { Session.today() }
        Thread { make(app, transcript, id, title, author, day) }.start()
    }

    /** Blocking: make lessons for the most recent video. Returns false if it couldn't. */
    fun makeForLatest(ctx: Context): Boolean {
        val p = Prefs(ctx)
        val id = p.videoId.ifEmpty { Session.watchedSet(p).lastOrNull() ?: "" }
        if (!Quiz.hasProvider(p)) return false
        return make(ctx, Session.readTranscript(ctx), id, p.videoTitle, p.videoAuthor, p.sessionDay.ifEmpty { Session.today() })
    }

    @Synchronized
    private fun make(ctx: Context, transcript: String, id: String, title: String, author: String, day: String): Boolean {
        if (running) return false
        running = true
        try {
            if (Bank.hasLessons(ctx, id)) return true
            val qs = Quiz.lessons(Prefs(ctx), transcript, id, title, author)
            Bank.addLessons(ctx, qs, id, title, author, day)
            return true
        } catch (e: Exception) {
            Log.w("LockedIn", "lessons failed: ${e.message}")
            return false
        } finally { running = false }
    }
}
