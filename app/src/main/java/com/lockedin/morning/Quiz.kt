package com.lockedin.morning

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Writes the quiz. Tries, in order:
 *  1. Gemini (free) — can also watch the YouTube video itself when there's no transcript
 *  2. OpenRouter free models — from the transcript
 *  3. Claude (paid, optional) — from the transcript
 *  4. A built-in fill-in-the-blank quiz made from the transcript (no AI, always works)
 */
object Quiz {
    data class Q(val q: String, val options: List<String>, val answer: Int, val explain: String, val at: String)

    private val GEMINI_FALLBACK_MODELS = listOf("gemini-3.8-flash", "gemini-flash-latest", "gemini-3.5-flash-lite", "gemini-2.5-flash")

    private fun gemini(p: Prefs) = p.useGemini && p.geminiKey.isNotBlank()
    private fun openRouter(p: Prefs) = p.useOpenRouter && p.openRouterKey.isNotBlank()
    private fun claude(p: Prefs) = p.useClaude && p.claudeKey.isNotBlank()

    fun hasProvider(p: Prefs): Boolean = gemini(p) || openRouter(p) || claude(p)

    /** True if some kind of quiz is possible for the current video. */
    fun canQuiz(ctx: Context, p: Prefs): Boolean {
        if (gemini(p)) return true
        if (Session.readTranscript(ctx).isBlank()) return false
        return openRouter(p) || claude(p) || p.useBuiltIn
    }

    fun generate(ctx: Context, p: Prefs): List<Q> {
        val transcript = Session.readTranscript(ctx)
        val n = p.quizQuestions.coerceIn(3, 20)
        val errors = mutableListOf<String>()

        fun attempt(name: String, call: () -> String): List<Q>? = try {
            parseReply(call())
        } catch (e: Exception) {
            errors.add("$name: ${e.message}")
            null
        }

        if (gemini(p)) {
            val watch = transcript.isBlank()
            attempt("Gemini") {
                askGemini(p, prompt(p, transcript, n, watch), if (watch) "https://www.youtube.com/watch?v=${p.videoId}" else null)
            }?.let { return it }
        }
        if (transcript.isNotBlank()) {
            if (openRouter(p)) {
                attempt("OpenRouter") { askOpenRouter(p, prompt(p, transcript, n, false)) }?.let { return it }
            }
            if (claude(p)) {
                attempt("Claude") { askClaude(p, prompt(p, transcript, n, false)) }?.let { return it }
            }
            if (p.useBuiltIn) {
                val local = builtIn(transcript, n)
                if (local.size >= 3) return local
                errors.add("Built-in quiz: transcript too short")
            }
        } else {
            errors.add("No transcript for this video, so only Gemini (if switched on) can quiz it")
        }
        if (errors.isEmpty()) errors.add("All quiz makers are switched off in settings")
        throw Exception(errors.joinToString("\n"))
    }

    private fun prompt(p: Prefs, transcript: String, n: Int, watchDirectly: Boolean): String = buildString {
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
        val avoid = p.quizPrevQs.trim()
        if (avoid.isNotEmpty()) {
            append("- Do NOT reuse or closely rephrase any of these earlier questions:\n")
            append(avoid.take(6000))
            append("\n")
        }
        append("\nReply with ONLY this JSON and nothing else:\n")
        append("{\"questions\":[{\"q\":\"...\",\"options\":[\"...\",\"...\",\"...\",\"...\"],\"answer\":0,\"explain\":\"...\",\"at\":\"12:34\"}]}")
    }

    /**
     * Questions about the TEACHINGS of a video (for the scroll check-in): principles, frameworks,
     * and how to apply them to a situation. AI only: never fill-in-the-blank.
     */
    fun lessons(p: Prefs, transcript: String, videoId: String, title: String, author: String, n: Int = 12): List<Q> {
        val errors = mutableListOf<String>()
        fun attempt(name: String, call: () -> String): List<Q>? = try {
            parseReply(call())
        } catch (e: Exception) { errors.add("$name: ${e.message}"); null }
        val hasT = transcript.isNotBlank()
        if (gemini(p) && (hasT || videoId.isNotEmpty())) {
            attempt("Gemini") {
                askGemini(p, lessonPrompt(transcript, title, author, n), if (hasT) null else "https://www.youtube.com/watch?v=$videoId")
            }?.let { return it }
        }
        if (hasT && openRouter(p)) {
            // Free models have smaller limits: send an even sample of the whole talk, smaller on retry.
            for (size in listOf(45_000, 18_000)) {
                attempt("OpenRouter") { askOpenRouter(p, lessonPrompt(fit(transcript, size), title, author, n)) }?.let { return it }
            }
        }
        if (hasT && claude(p)) attempt("Claude") { askClaude(p, lessonPrompt(transcript, title, author, n)) }?.let { return it }
        if (errors.isEmpty()) errors.add(if (hasT) "No AI quiz maker is switched on" else "No transcript and Gemini is off")
        throw Exception(errors.joinToString("\n"))
    }

    /** Shrink a transcript to about [max] chars by keeping evenly spaced chunks from start to end. */
    fun fit(t: String, max: Int): String {
        if (t.length <= max) return t
        val chunks = 12
        val each = max / chunks
        val step = t.length / chunks
        return (0 until chunks).joinToString("\n…\n") { i -> t.substring(i * step, minOf(t.length, i * step + each)) }
    }

    private fun lessonPrompt(transcript: String, title: String, author: String, n: Int): String = buildString {
        if (transcript.isBlank()) {
            append("Watch the attached YouTube video \"$title\" by $author.\n\n")
        } else {
            append("Below is the transcript of the YouTube video \"$title\" by $author.\n\n<transcript>\n")
            append(transcript.take(180_000))
            append("\n</transcript>\n\n")
        }
        append("Write $n multiple-choice questions that test whether someone LEARNED the teachings of this video ")
        append("and can apply them, days later.\n")
        append("Rules:\n")
        append("- Test the core ideas: the principles, frameworks, mindsets, methods and the reasons behind them.\n")
        append("- At least half should be short real-life scenarios, e.g. \"A prospect says it's too expensive. Based on this video, what's the best move?\"\n")
        append("- Others ask why something works, what the speaker recommends instead of a common mistake, or which step comes first in their method.\n")
        append("- NEVER ask fill-in-the-blank, word-for-word quotes, exact numbers, timestamps, names of people in stories, or what was said at a moment.\n")
        append("- The question must make sense on its own without having just watched it. Mention the speaker by name where it helps.\n")
        append("- Exactly 4 options, one correct. Wrong options must be believable, common-sense alternatives the video argues against, similar length to the right one.\n")
        append("- \"explain\" is one short sentence stating the lesson.\n")
        append("\nReply with ONLY this JSON and nothing else:\n")
        append("{\"questions\":[{\"q\":\"...\",\"options\":[\"...\",\"...\",\"...\",\"...\"],\"answer\":0,\"explain\":\"...\",\"at\":\"\"}]}")
    }

    private fun parseReply(raw: String): List<Q> {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) throw Exception("didn't return a quiz")
        val qs = parse(JSONObject(raw.substring(start, end + 1)).optJSONArray("questions") ?: JSONArray())
        if (qs.size < 3) throw Exception("quiz came back too short")
        return qs.map { shuffleOptions(it) }
    }

    // ------------------------------------------------------------------ OpenRouter

    private fun askOpenRouter(p: Prefs, prompt: String): String {
        val key = p.openRouterKey.trim()
        val models = listOf(p.openRouterModel.trim(), "openrouter/free").filter { it.isNotEmpty() }.distinct()
        var last: Exception? = null
        for (model in models) {
            // Free models are often busy: one quick retry per model.
            for (tryNo in 0 until 2) {
                try {
                    val body = JSONObject()
                        .put("model", model)
                        .put("max_tokens", 6000)
                        .put("messages", JSONArray()
                            .put(JSONObject().put("role", "system").put("content", "You write sharp, fair comprehension quizzes for long talks. You output valid JSON only."))
                            .put(JSONObject().put("role", "user").put("content", prompt)))
                    val resp = Http.post(
                        "https://openrouter.ai/api/v1/chat/completions",
                        body.toString(),
                        mapOf(
                            "Authorization" to "Bearer $key",
                            "Content-Type" to "application/json",
                            "HTTP-Referer" to "https://github.com/Crayzewastaken/Lockedin",
                            "X-Title" to "Locked In",
                        ),
                        timeoutSec = 150,
                    )
                    val j = JSONObject(resp)
                    j.optJSONObject("error")?.let { throw Exception(it.optString("message", "error")) }
                    val text = j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content") ?: ""
                    if (text.isNotBlank()) return text
                    last = Exception("$model gave an empty reply")
                } catch (e: Exception) {
                    val msg = e.message ?: ""
                    last = Exception("$model: ${friendly(msg)}")
                    if (msg.contains("HTTP 401") || msg.contains("HTTP 403")) {
                        throw Exception("your OpenRouter key was rejected. Copy it again from openrouter.ai/keys.")
                    }
                    if (msg.contains("HTTP 429") && tryNo == 0) Thread.sleep(8000)
                }
            }
        }
        throw last ?: Exception("failed")
    }

    fun testOpenRouter(p: Prefs): String {
        if (p.openRouterKey.isBlank()) throw Exception("No OpenRouter key entered")
        val raw = askOpenRouter(p, "Reply with just the word OK.")
        return raw.trim().take(40)
    }

    // ------------------------------------------------------------------ built-in (no AI)

    private val STOP = (
        "about above after again against because been before being below between both could doing during each " +
        "every further going gonna having here itself just know like little maybe more most much myself never " +
        "other people really right said same should since some something still such than that their them then " +
        "there these they thing things think this those though through today together under until very want wanna " +
        "were what when where which while whole will with would your yours yourself actually basically literally " +
        "anything everything nothing someone everyone because okay alright yeah kind sort pretty another around " +
        "started saying called getting making looking talking trying gotten without within whatever whenever"
        ).split(" ").toSet()

    /** Fill-in-the-blank questions picked evenly across the transcript. */
    fun builtIn(transcript: String, n: Int): List<Q> {
        data class Seg(val at: String, val words: List<String>)
        val segs = mutableListOf<Seg>()
        val re = Regex("\\[([0-9:]+)\\]([^\\[]*)")
        for (m in re.findAll(transcript)) {
            val words = m.groupValues[2].replace(Regex("\\s+"), " ").trim().split(" ").filter { it.isNotBlank() }
            if (words.size >= 15) segs.add(Seg(m.groupValues[1], words))
        }
        if (segs.size < 3) return emptyList()

        fun clean(w: String) = w.lowercase().trim { !it.isLetterOrDigit() }
        fun good(w: String): Boolean {
            val c = clean(w)
            return (c.length >= 6 && c.all { it.isLetter() } && c !in STOP) || (c.any { it.isDigit() } && c.length >= 2)
        }

        val pool = segs.flatMap { s -> s.words.map { clean(it) } }.filter { good(it) }.distinct()
        if (pool.size < 8) return emptyList()

        val out = mutableListOf<Q>()
        val count = minOf(n, segs.size)
        for (k in 0 until count) {
            val seg = segs[((k + 0.5) * segs.size / count).toInt().coerceIn(0, segs.size - 1)]
            val idxs = seg.words.indices.filter { it in 4 until seg.words.size - 3 && good(seg.words[it]) }
            if (idxs.isEmpty()) continue
            val i = idxs.maxByOrNull { clean(seg.words[it]).length + (if (clean(seg.words[it]).any { c -> c.isDigit() }) 10 else 0) }!!
            val answer = clean(seg.words[i])
            val from = (i - 12).coerceAtLeast(0)
            val to = (i + 12).coerceAtMost(seg.words.size - 1)
            val sentence = (from..to).joinToString(" ") { if (it == i) "_____" else seg.words[it] }
            val isNum = answer.any { it.isDigit() }
            val distractors = pool.filter { it != answer && it.any { c -> c.isDigit() } == isNum }
                .sortedBy { kotlin.math.abs(it.length - answer.length) + Math.random() * 3 }
                .take(3)
            if (distractors.size < 3) continue
            out.add(Q(
                "Fill in the blank (at ${seg.at}):\n\"…$sentence…\"",
                listOf(answer) + distractors,
                0,
                "The word was \"$answer\" (said at ${seg.at}).",
                seg.at,
            ))
        }
        return out.map { shuffleOptions(it) }
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
        return callGemini(p, body, timeoutSec = 120)
    }

    /** Quick check used by the "Test Gemini key" button. Returns the model that worked. */
    fun testGemini(p: Prefs): String {
        if (p.geminiKey.isBlank()) throw Exception("No Gemini key entered")
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", "Reply with just the word OK.")))))
            .toString()
        callGemini(p, body, timeoutSec = 45)
        return p.sp.getString("gemini_working_model", "") ?: ""
    }

    /** Tries your chosen model, then whatever Flash models your key can use. */
    private fun callGemini(p: Prefs, body: String, timeoutSec: Int): String {
        val key = p.geminiKey.trim()
        val remembered = p.sp.getString("gemini_working_model", "") ?: ""
        val models = (listOf(remembered, p.geminiModel.trim()) + GEMINI_FALLBACK_MODELS)
            .filter { it.isNotEmpty() }.distinct().toMutableList()
        var listed = false
        var lastErr: Exception? = null
        var i = 0
        // Overall time limit across all models, so a slow Gemini can't hang things.
        val giveUpAt = System.currentTimeMillis() + timeoutSec * 2500L
        while (i < models.size && i < 8) {
            val left = ((giveUpAt - System.currentTimeMillis()) / 1000).toInt()
            if (left < 10) break
            val model = models[i++]
            try {
                val resp = Http.post(
                    "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
                    body,
                    mapOf("x-goog-api-key" to key, "Content-Type" to "application/json"),
                    timeoutSec = minOf(timeoutSec, left),
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
                val timedOut = e is java.net.SocketTimeoutException || msg.contains("timed out", true) || msg.contains("timeout", true)
                val err = Exception(
                    if (timedOut) "Gemini ($model) didn't answer in time" else "Gemini ($model): ${friendly(msg)}"
                )
                lastErr = err
                val code = Regex("HTTP (\\d+)").find(msg)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                when {
                    timedOut -> {
                        // Busy/slow model: try a lighter, faster one next.
                        listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-2.5-flash-lite")
                            .filter { it !in models }.reversed().forEach { models.add(i, it) }
                    }
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
