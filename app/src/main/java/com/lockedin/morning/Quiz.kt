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

    private val GEMINI_FALLBACK_MODELS = listOf("gemini-3.8-flash", "gemini-flash-latest", "gemini-3.5-flash-lite", "gemini-2.5-flash")

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
        if (youtubeUrl != null) {
            // Low frame rate + low resolution: a 60-min talk fits comfortably in the free tier.
            parts.put(
                JSONObject()
                    .put("fileData", JSONObject().put("fileUri", youtubeUrl))
                    .put("videoMetadata", JSONObject().put("fps", 0.1))
            )
        }
        parts.put(JSONObject().put("text", prompt))
        val gen = JSONObject().put("responseMimeType", "application/json")
        if (youtubeUrl != null) gen.put("mediaResolution", "MEDIA_RESOLUTION_LOW")
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", gen)
            .toString()
        return callGemini(p, body, timeoutSec = 150)
    }

    /** Quick check used by the "Test Gemini key" button. Returns the model that worked. */
    fun testGemini(p: Prefs): String {
        if (p.geminiKey.isBlank()) throw Exception("No Gemini key entered")
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", "Reply with just the word OK.")))))
            .toString()
        callGemini(p, body, timeoutSec = 40)
        return p.sp.getString("gemini_working_model", "") ?: ""
    }

    /** Tries your chosen model, then whatever Flash models your key can use. */
    private fun callGemini(p: Prefs, body: String, timeoutSec: Int): String {
        val key = p.geminiKey.trim()
        val remembered = p.sp.getString("gemini_working_model", "") ?: ""
        val models = (listOf(p.geminiModel.trim(), remembered) + GEMINI_FALLBACK_MODELS)
            .filter { it.isNotEmpty() }.distinct().toMutableList()
        var listed = false
        var lastErr: Exception? = null
        var i = 0
        while (i < models.size && i < 8) {
            val model = models[i++]
            try {
                val resp = Http.post(
                    "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
                    body,
                    mapOf("x-goog-api-key" to key, "Content-Type" to "application/json"),
                    timeoutSec = timeoutSec,
                )
                val j = JSONObject(resp)
                val cand = j.optJSONArray("candidates")?.optJSONObject(0)
                val rParts = cand?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
                val out = StringBuilder()
                for (k in 0 until rParts.length()) {
                    val part = rParts.optJSONObject(k) ?: continue
                    if (!part.optBoolean("thought", false)) out.append(part.optString("text"))
                }
                if (out.isNotBlank()) {
                    p.sp.edit().putString("gemini_working_model", model).apply()
                    return out.toString()
                }
                val why = cand?.optString("finishReason")?.takeIf { it.isNotEmpty() }
                    ?: j.optJSONObject("promptFeedback")?.optString("blockReason")
                    ?: "no text"
                lastErr = Exception("Gemini ($model) gave an empty reply: $why")
            } catch (e: Exception) {
                val msg = e.message ?: ""
                val err = Exception("Gemini ($model): ${friendly(msg)}")
                lastErr = err
                val code = Regex("HTTP (\\d+)").find(msg)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                when {
                    code == 401 || code == 403 && !msg.contains("model", true) ->
                        throw Exception("Your Gemini key was rejected. Check it in settings (copy it again from aistudio.google.com/apikey).")
                    code == 404 || code == 429 || code == 400 && msg.contains("model", true) || code >= 500 -> {
                        // This model isn't available / out of free quota: try another one.
                        if (!listed) {
                            listed = true
                            try { models.addAll(listFlashModels(key).filter { it !in models }) } catch (_: Exception) {}
                        }
                    }
                    code == 400 && msg.contains("token", true) ->
                        throw Exception("This video is too long for the free Gemini quiz.")
                    else -> throw err
                }
            }
        }
        throw lastErr ?: Exception("Gemini failed")
    }

    /** Asks Google which Flash models this key can use, newest first. */
    private fun listFlashModels(key: String): List<String> {
        val resp = Http.get(
            "https://generativelanguage.googleapis.com/v1beta/models?pageSize=200",
            mapOf("x-goog-api-key" to key),
            timeoutSec = 20,
        )
        val arr = JSONObject(resp).optJSONArray("models") ?: return emptyList()
        val bad = listOf("image", "tts", "audio", "live", "embedding", "vision", "exp", "thinking", "native")
        val names = mutableListOf<String>()
        for (k in 0 until arr.length()) {
            val m = arr.optJSONObject(k) ?: continue
            val name = m.optString("name").removePrefix("models/")
            val methods = m.optJSONArray("supportedGenerationMethods")?.toString() ?: ""
            if (!methods.contains("generateContent")) continue
            if (!name.contains("flash") || bad.any { name.contains(it) }) continue
            names.add(name)
        }
        fun ver(n: String) = Regex("gemini-(\\d+(?:\\.\\d+)?)").find(n)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        return names.sortedWith(compareByDescending<String> { ver(it) }
            .thenBy { if (it.contains("lite")) 1 else 0 }
            .thenBy { if (it.contains("preview")) 1 else 0 }).take(5)
    }

    private fun friendly(msg: String): String {
        val code = Regex("HTTP (\\d+)").find(msg)?.groupValues?.get(1) ?: ""
        val inner = try {
            JSONObject(msg.substringAfter(": ", "")).optJSONObject("error")?.optString("message")
        } catch (_: Exception) { null }
        val text = inner?.takeIf { it.isNotBlank() } ?: msg
        return when (code) {
            "429" -> "free quota used up for now (${text.take(140)})"
            else -> text.take(220)
        }
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
