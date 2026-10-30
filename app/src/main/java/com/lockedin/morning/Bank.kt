package com.lockedin.morning

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Every morning-quiz question is saved here, so the scroll check-in can quiz you on
 * previous days' videos. Questions you got wrong (or haven't seen in a while) come up first.
 */
object Bank {
    private const val FILE = "bank"

    data class Item(val id: String, val q: Quiz.Q, val title: String, val author: String, val day: String)

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Synchronized
    private fun all(ctx: Context): JSONArray =
        try { JSONArray(sp(ctx).getString("items", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }

    @Synchronized
    private fun save(ctx: Context, arr: JSONArray) {
        val out = JSONArray()
        for (i in maxOf(0, arr.length() - 500) until arr.length()) out.put(arr.get(i))
        sp(ctx).edit().putString("items", out.toString()).apply()
    }

    private fun idOf(q: Quiz.Q) = Integer.toHexString(q.q.trim().lowercase().hashCode())

    /** Add a morning quiz's questions (and mark the ones you got wrong). */
    @Synchronized
    fun addFromMorning(ctx: Context, qs: List<Quiz.Q>, answers: List<Int>, title: String, author: String, day: String) {
        val arr = all(ctx)
        val existing = HashMap<String, JSONObject>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { existing[it.optString("id")] = it }
        qs.forEachIndexed { i, q ->
            if (isBlankStyle(q.q)) return@forEachIndexed
            val id = idOf(q)
            val wrong = answers.getOrNull(i) != q.answer
            val o = existing[id]
            if (o != null) {
                if (wrong) o.put("wrong", o.optInt("wrong") + 1)
            } else {
                arr.put(
                    JSONObject()
                        .put("id", id)
                        .put("q", q.q)
                        .put("options", JSONArray(q.options))
                        .put("answer", q.options[q.answer])
                        .put("explain", q.explain)
                        .put("at", q.at)
                        .put("title", title)
                        .put("author", author)
                        .put("day", day)
                        .put("wrong", if (wrong) 1 else 0)
                        .put("seen", 0)
                        .put("lastSeen", 0L)
                )
            }
        }
        save(ctx, arr)
    }

    fun size(ctx: Context): Int = all(ctx).length()

    fun isBlankStyle(q: String) = q.trimStart().startsWith("Fill in the blank", ignoreCase = true)

    /** Lesson questions for a video (the ones the scroll check-in uses). */
    @Synchronized
    fun addLessons(ctx: Context, qs: List<Quiz.Q>, videoId: String, title: String, author: String, day: String) {
        val arr = all(ctx)
        val ids = HashSet<String>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { ids.add(it.optString("id")) }
        for (q in qs) {
            if (isBlankStyle(q.q)) continue
            val id = idOf(q)
            if (!ids.add(id)) continue
            arr.put(
                JSONObject().put("id", id).put("kind", "lesson").put("video", videoId)
                    .put("q", q.q).put("options", JSONArray(q.options)).put("answer", q.options[q.answer])
                    .put("explain", q.explain).put("at", "").put("title", title).put("author", author)
                    .put("day", day).put("wrong", 0).put("seen", 0).put("lastSeen", 0L)
            )
        }
        save(ctx, arr)
        val done = (sp(ctx).getString("lessonVideos", "") ?: "").split(",").filter { it.isNotBlank() }.toMutableList()
        if (videoId !in done) done.add(videoId)
        sp(ctx).edit().putString("lessonVideos", done.takeLast(300).joinToString(",")).apply()
    }

    fun hasLessons(ctx: Context, videoId: String) =
        videoId.isNotEmpty() && (sp(ctx).getString("lessonVideos", "") ?: "").split(",").contains(videoId)

    fun lessonCount(ctx: Context): Int {
        val arr = all(ctx); var c = 0
        for (i in 0 until arr.length()) if (arr.optJSONObject(i)?.optString("kind") == "lesson") c++
        return c
    }

    /** Pick [n] questions, from previous days if there are any (today's as a fallback). */
    @Synchronized
    fun pick(ctx: Context, n: Int): List<Item> {
        val arr = all(ctx)
        val lessonsOnly = lessonCount(ctx) >= n
        val today = Session.today()
        val items = mutableListOf<Pair<JSONObject, Double>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val opts = o.optJSONArray("options") ?: continue
            if (opts.length() < 2) continue
            if (isBlankStyle(o.optString("q"))) continue // never use fill-in-the-blank for check-ins
            if (lessonsOnly && o.optString("kind") != "lesson") continue
            // Priority: got it wrong before, not seen for a long time, previous days first.
            val wrong = o.optInt("wrong")
            val ageH = (System.currentTimeMillis() - o.optLong("lastSeen")) / 3_600_000.0
            var score = wrong * 3.0 + minOf(ageH, 72.0) / 12.0 + Math.random() * 2
            if (o.optString("day") == today) score -= 100 // only if nothing older exists
            items.add(o to score)
        }
        return items.sortedByDescending { it.second }.take(n).map { (o, _) ->
            val opts = o.optJSONArray("options")!!
            val list = (0 until opts.length()).map { opts.optString(it) }.shuffled()
            val ans = list.indexOf(o.optString("answer")).coerceAtLeast(0)
            Item(
                o.optString("id"),
                Quiz.Q(o.optString("q"), list, ans, o.optString("explain"), o.optString("at")),
                o.optString("title"), o.optString("author"), o.optString("day"),
            )
        }
    }

    /** Record recall answers: right ones get pushed back, wrong ones come back sooner. */
    @Synchronized
    fun record(ctx: Context, results: Map<String, Boolean>) {
        val arr = all(ctx)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val r = results[o.optString("id")] ?: continue
            o.put("seen", o.optInt("seen") + 1)
            o.put("lastSeen", System.currentTimeMillis())
            o.put("wrong", if (r) maxOf(0, o.optInt("wrong") - 1) else o.optInt("wrong") + 1)
        }
        save(ctx, arr)
    }
}
