package com.lockedin.morning

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Writes the quiz with Claude, from the video's transcript. */
object Quiz {
    data class Q(val q: String, val options: List<String>, val answer: Int, val explain: String, val at: String)

    fun generate(ctx: Context, p: Prefs): List<Q> {
        val key = p.claudeKey.trim()
        if (key.isEmpty()) throw Exception("Add your Claude API key in Locked In's settings")
        val transcript = Session.readTranscript(ctx)
        if (transcript.isBlank()) throw Exception("No transcript for this video")
        val n = p.quizQuestions.coerceIn(3, 20)

        val avoid = p.quizPrevQs.trim()
        val prompt = buildString {
            append("Below is the transcript of the YouTube video \"${p.videoTitle}\" by ${p.videoAuthor}. ")
            append("Timestamps in [mm:ss] mark roughly every 30 seconds.\n\n")
            append("<transcript>\n")
            append(transcript.take(180_000))
            append("\n</transcript>\n\n")
            append("Write $n multiple-choice questions that check whether someone actually watched and understood this whole video.\n")
            append("Rules:\n")
            append("- Spread the questions evenly from the start to the end of the video (at least one from each quarter), in order.\n")
            append("- Ask about specific frameworks, numbers, steps, examples, stories and pieces of advice from THIS video. ")
            append("Never ask something a person could guess from general business knowledge or the title.\n")
            append("- Each question has exactly 4 options and exactly one correct answer. Wrong options must be plausible and similar in length to the right one.\n")
            append("- \"explain\" is one short sentence giving the lesson behind the right answer.\n")
            append("- \"at\" is the mm:ss timestamp where the answer is discussed.\n")
            if (avoid.isNotEmpty()) {
                append("- Do NOT reuse or closely rephrase any of these earlier questions:\n")
                append(avoid.take(6000))
                append("\n")
            }
            append("\nReply with ONLY this JSON and nothing else:\n")
            append("{\"questions\":[{\"q\":\"...\",\"options\":[\"...\",\"...\",\"...\",\"...\"],\"answer\":0,\"explain\":\"...\",\"at\":\"12:34\"}]}")
        }

        val body = JSONObject()
            .put("model", p.claudeModel.trim().ifEmpty { "claude-sonnet-5-5" })
            .put("max_tokens", 6000)
            .put("system", "You write sharp, fair comprehension quizzes for long talks. You output valid JSON only.")
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))

        val resp = Http.post(
            "https://api.anthropic.com/v1/messages",
            body.toString(),
            mapOf(
                "x-api-key" to key,
                "anthropic-version" to "2023-06-01",
                "Content-Type" to "application/json",
            ),
            timeoutSec = 180,
        )
        val content = JSONObject(resp).optJSONArray("content") ?: throw Exception("Empty reply from Claude")
        val text = StringBuilder()
        for (i in 0 until content.length()) {
            val c = content.optJSONObject(i) ?: continue
            if (c.optString("type") == "text") text.append(c.optString("text"))
        }
        val raw = text.toString()
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) throw Exception("Claude didn't return a quiz")
        val qs = parse(JSONObject(raw.substring(start, end + 1)).optJSONArray("questions") ?: JSONArray())
        if (qs.size < 3) throw Exception("Quiz came back too short")
        return qs.map { shuffleOptions(it) }
    }

    private fun parse(arr: JSONArray): List<Q> {
        val out = mutableListOf<Q>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val opts = o.optJSONArray("options") ?: continue
            if (opts.length() < 2) continue
            val list = (0 until opts.length()).map { opts.optString(it) }
            val ans = o.optInt("answer", -1)
            if (ans !in list.indices) continue
            out.add(Q(o.optString("q"), list, ans, o.optString("explain"), o.optString("at")))
        }
        return out
    }

    /** Claude tends to favour certain answer positions, so shuffle them. */
    private fun shuffleOptions(q: Q): Q {
        val idx = q.options.indices.shuffled()
        val opts = idx.map { q.options[it] }
        return q.copy(options = opts, answer = idx.indexOf(q.answer))
    }

    fun toJson(qs: List<Q>): String {
        val arr = JSONArray()
        for (q in qs) {
            arr.put(
                JSONObject()
                    .put("q", q.q)
                    .put("options", JSONArray(q.options))
                    .put("answer", q.answer)
                    .put("explain", q.explain)
                    .put("at", q.at)
            )
        }
        return JSONObject().put("questions", arr).toString()
    }

    fun fromJson(s: String): List<Q> = try {
        parse(JSONObject(s).optJSONArray("questions") ?: JSONArray())
    } catch (_: Exception) {
        emptyList()
    }
}
