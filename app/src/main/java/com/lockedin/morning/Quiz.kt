package com.lockedin.morning

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Writes the quiz. Uses Google Gemini (free tier, no card) by default, or Claude if you
 * only set a Claude key. Gemini can also watch the YouTube video directly, so videos
 * without captions still get a real quiz.
 */
object Quiz {
    data class Q(val q: String, val options: List<String>, val answer: Int, val explain: String, val at: String)

    private val GEMINI_FALLBACK_MODELS = listOf("gemini-3.8-flash", "gemini-3.5-flash", "gemini-2.5-flash")

    fun hasProvider(p: Prefs): Boolean = p.geminiKey.isNotBlank() || p.claudeKey.isNotBlank()

    /** True if a real quiz is possible for the current video. */
    fun canQuiz(ctx: Context, p: Prefs): Boolean =
        p.geminiKey.isNotBlank() || (p.claudeKey.isNotBlank() && Session.readTranscript(ctx).isNotBlank())

    fun generate(ctx: Context, p: Prefs): List<Q> {
        if (!hasProvider(p)) throw Exception("Add your free Gemini API key in Locked In's settings")
        val transcript = Session.readTranscript(ctx)
        val useGemini = p.geminiKey.isNotBlank()
        if (!useGemini && transcript.isBlank()) throw Exception("No transcript for this video")
        val n = p.quizQuestions.coerceIn(3, 20)
        val watchDirectly = useGemini && transcript.isBlank()

        val avoid = p.quizPrevQs.trim()
        val prompt = buildString {
            if (watchDirectly) {
                append("Watch the attached YouTube video \"${p.videoTitle}\" by ${p.videoAuthor} in full.\n\n")
            } else {
                append("Below is the transcript of the YouTube video \"${p.videoTitle}\" by ${p.videoAuthor}. ")
                append("Timestamps in [mm:ss] mark roughly every 30 seconds.\n\n")
                append("<transcript>\n")
                append(transcript.take(180_000))
                append("\n</transcript>\n\n")
            }
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

        val raw = if (useGemini) {
            askGemini(p, prompt, if (watchDirectly) "https://www.youtube.com/watch?v=${p.videoId}" else null)
        } else {
            askClaude(p, prompt)
        }
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) throw Exception("The AI didn't return a quiz")
        val qs = parse(JSONObject(raw.substring(start, end + 1)).optJSONArray("questions") ?: JSONArray())
        if (qs.size < 3) throw Exception("Quiz came back too short")
        return qs.map { shuffleOptions(it) }
    }

    private fun askGemini(p: Prefs, prompt: String, youtubeUrl: String?): String {
        val parts = JSONArray()
        if (youtubeUrl != null) parts.put(JSONObject().put("file_data", JSONObject().put("file_uri", youtubeUrl)))
        parts.put(JSONObject().put("text", prompt))
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
            .toString()

        val chosen = p.geminiModel.trim()
        val models = (listOf(chosen) + GEMINI_FALLBACK_MODELS).filter { it.isNotEmpty() }.distinct()
        var lastErr: Exception? = null
        for (model in models) {
            try {
                val resp = Http.post(
                    "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
                    body,
                    mapOf("x-goog-api-key" to p.geminiKey.trim(), "Content-Type" to "application/json"),
                    timeoutSec = 240,
                )
                val cands = JSONObject(resp).optJSONArray("candidates") ?: throw Exception("Empty reply from Gemini")
                val out = StringBuilder()
                val rParts = cands.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
                for (i in 0 until rParts.length()) out.append(rParts.optJSONObject(i)?.optString("text") ?: "")
                if (out.isNotBlank()) return out.toString()
                lastErr = Exception("Empty reply from Gemini")
            } catch (e: Exception) {
                lastErr = e
                val msg = e.message ?: ""
                // Only try the next model if this one doesn't exist / isn't allowed.
                if (!(msg.contains("HTTP 404") || msg.contains("HTTP 400") && msg.contains("model"))) break
            }
        }
        throw lastErr ?: Exception("Gemini failed")
    }

    private fun askClaude(p: Prefs, prompt: String): String {
        val body = JSONObject()
            .put("model", p.claudeModel.trim().ifEmpty { "claude-sonnet-5-5" })
            .put("max_tokens", 6000)
            .put("system", "You write sharp, fair comprehension quizzes for long talks. You output valid JSON only.")
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        val resp = Http.post(
            "https://api.anthropic.com/v1/messages",
            body.toString(),
            mapOf(
                "x-api-key" to p.claudeKey.trim(),
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
        return text.toString()
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
