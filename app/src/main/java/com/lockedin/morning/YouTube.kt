package com.lockedin.morning

import android.content.Context
import android.text.Html
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Finds a long video from the chosen channels, checks it can be embedded and has captions,
 * and downloads its transcript (used to write the quiz).
 *
 * Uses YouTube's own app endpoints (no key needed). If you add a YouTube Data API key in
 * settings, the video list comes from the official API instead, which is more reliable.
 */
object YouTube {
    data class Candidate(val id: String, val title: String, val author: String, val lengthSec: Int)
    data class Picked(val id: String, val title: String, val author: String, val lengthSec: Int, val transcript: String)

    private const val HORMOZI_ID = "UCUyDOdBWhC1MCxEjC46d-zw"

    private const val WEB_VERSION = "2.20250310.01.00"
    private const val WEB_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"

    private data class Client(val body: JSONObject, val headers: Map<String, String>)

    private fun androidClient() = Client(
        JSONObject()
            .put("clientName", "ANDROID")
            .put("clientVersion", "20.10.38")
            .put("androidSdkVersion", 34)
            .put("osName", "Android")
            .put("osVersion", "14")
            .put("hl", "en")
            .put("gl", "US"),
        mapOf(
            "User-Agent" to "com.google.android.youtube/20.10.38 (Linux; U; Android 14) gzip",
            "X-YouTube-Client-Name" to "3",
            "X-YouTube-Client-Version" to "20.10.38",
        )
    )

    private fun iosClient() = Client(
        JSONObject()
            .put("clientName", "IOS")
            .put("clientVersion", "20.10.4")
            .put("deviceMake", "Apple")
            .put("deviceModel", "iPhone16,2")
            .put("osName", "iPhone")
            .put("osVersion", "18.3.2.22D82")
            .put("hl", "en")
            .put("gl", "US"),
        mapOf(
            "User-Agent" to "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            "X-YouTube-Client-Name" to "5",
            "X-YouTube-Client-Version" to "20.10.4",
        )
    )

    // ------------------------------------------------------------------ pick

    fun channelList(p: Prefs): List<String> =
        p.channels.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /** Videos (not yet watched) from the chosen channels within the length range, unverified. */
    fun candidates(p: Prefs, channels: List<String>, minSec: Int, maxSec: Int, count: Int): List<Candidate> {
        val pool = loadPool(p)
        val extras = extraIds(p.extraVideoUrls).map { Candidate(it, "", "", 0) }
        val inChannels = pool.filter { c -> channels.isEmpty() || channels.any { matches(c.author, it) } }
        val all = (extras + inChannels).distinctBy { it.id }
            .filter { it.lengthSec == 0 || it.lengthSec in minSec..maxSec }
        if (all.isEmpty()) return emptyList()
        val watched = Session.watchedSet(p).toSet()
        var fresh = all.filter { it.id !in watched }
        if (fresh.isEmpty()) fresh = all // watched them all: start the rotation again
        // Known lengths first (they can be shown in the list), random order within.
        return fresh.shuffled().sortedBy { if (it.lengthSec > 0 && it.title.isNotEmpty()) 0 else 1 }.take(count)
    }

    /** Checks a video can play here and has captions. Throws with the reason if not. */
    fun verify(c: Candidate, minSec: Int, maxSec: Int): Picked {
        val player = playerInfo(c.id)
        if (player == null) {
            // YouTube blocked the lookup; if we already know the length, play it anyway (quiz → summary).
            if (c.lengthSec in minSec..maxSec) return Picked(c.id, c.title, c.author, c.lengthSec, "")
            throw Exception("couldn't read video details")
        }
        val ps = player.optJSONObject("playabilityStatus")
        if (ps == null || ps.optString("status") != "OK") throw Exception("video not playable")
        if (ps.has("playableInEmbed") && !ps.optBoolean("playableInEmbed", true)) throw Exception("video can't be embedded")
        val details = player.optJSONObject("videoDetails") ?: throw Exception("no video details")
        if (details.optBoolean("isLive", false) || details.optBoolean("isUpcoming", false)) throw Exception("live stream")
        val len = details.optString("lengthSeconds").toIntOrNull() ?: 0
        if (len < minSec || len > maxSec) throw Exception("wrong length")
        val transcript = try { transcript(player) } catch (_: Exception) { null }
        if (transcript == null || transcript.length < 1500) throw Exception("no captions")
        return Picked(c.id, details.optString("title", c.title), details.optString("author", c.author), len, transcript)
    }

    /** "Surprise me": first video that passes [verify]. */
    fun pick(ctx: Context, p: Prefs, channels: List<String> = channelList(p), minSec: Int = p.minMinutes * 60, maxSec: Int = p.maxMinutes * 60): Picked {
        val list = candidates(p, channels, minSec, maxSec, 14)
        if (list.isEmpty()) throw Exception("No videos found for ${channels.joinToString()} at that length")
        var lastProblem = "no candidate worked"
        var fallback: Picked? = null
        for (c in list) {
            try {
                val v = verify(c, minSec, maxSec)
                if (v.transcript.isNotEmpty()) return v
                if (fallback == null) fallback = v
            } catch (e: Exception) {
                lastProblem = e.message ?: lastProblem
            }
        }
        fallback?.let { return it }
        throw Exception("Couldn't find a usable video ($lastProblem)")
    }

    fun extraIds(text: String): List<String> {
        val re = Regex("(?:v=|youtu\\.be/|shorts/|embed/|live/)([A-Za-z0-9_-]{11})")
        val out = mutableListOf<String>()
        for (line in text.split("\n", ",", " ")) {
            val t = line.trim()
            if (t.isEmpty()) continue
            val m = re.find(t)
            when {
                m != null -> out.add(m.groupValues[1])
                Regex("^[A-Za-z0-9_-]{11}$").matches(t) -> out.add(t)
            }
        }
        return out
    }

    // ------------------------------------------------------------------ pool

    private fun loadPool(p: Prefs): List<Candidate> {
        if (p.sp.getInt("poolVer", 0) < 2) { p.poolFetchedAt = 0L; p.sp.edit().putInt("poolVer", 2).apply() }
        val fresh = System.currentTimeMillis() - p.poolFetchedAt < 5L * 24 * 3600 * 1000
        if (fresh && p.poolJson.isNotEmpty()) {
            val cached = parsePool(p.poolJson)
            if (cached.isNotEmpty()) return cached
        }
        val names = channelList(p)
        val found = mutableListOf<Candidate>()
        for (name in names) {
            val list = try {
                if (p.youtubeKey.isNotBlank()) dataApiVideos(p, name) else searchVideos(name, p)
            } catch (_: Exception) {
                try { searchVideos(name, p) } catch (_: Exception) { emptyList() }
            }
            found.addAll(list)
        }
        // Keep everything 30+ min (up to 4 h); the morning chooser narrows the length.
        val minSec = p.minMinutes * 60
        val filtered = found.filter { it.lengthSec == 0 || it.lengthSec in minSec..4 * 3600 }.distinctBy { it.id }
        if (filtered.isNotEmpty()) {
            p.poolJson = poolToJson(filtered)
            p.poolFetchedAt = System.currentTimeMillis()
            return filtered
        }
        // Network trouble: use an old pool if we have one.
        return parsePool(p.poolJson)
    }

    private fun poolToJson(list: List<Candidate>): String {
        val arr = JSONArray()
        for (c in list) arr.put(JSONObject().put("id", c.id).put("t", c.title).put("a", c.author).put("l", c.lengthSec))
        return arr.toString()
    }

    private fun parsePool(s: String): List<Candidate> = try {
        val arr = JSONArray(s)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Candidate(o.optString("id"), o.optString("t"), o.optString("a"), o.optInt("l"))
        }.filter { it.id.length == 11 }
    } catch (_: Exception) {
        emptyList()
    }

    /** Search YouTube (videos over 20 minutes) and keep results uploaded by this channel. */
    private fun searchVideos(name: String, p: Prefs): List<Candidate> {
        val all = listOf(
            name, "$name podcast", "$name full", "$name masterclass",
            "$name training", "$name workshop", "$name interview", "$name 2026",
        )
        // Fewer searches per channel when you follow a lot of channels.
        val queries = if (channelList(p).size > 3) all.take(4) else all
        val out = mutableListOf<Candidate>()
        for (q in queries) {
            val body = JSONObject()
                .put("context", JSONObject().put("client",
                    JSONObject().put("clientName", "WEB").put("clientVersion", WEB_VERSION).put("hl", "en").put("gl", "US")))
                .put("query", q)
                .put("params", "EgQQARgC") // type = video, duration = over 20 minutes
            val resp = try {
                Http.post(
                    "https://www.youtube.com/youtubei/v1/search?prettyPrint=false",
                    body.toString(),
                    mapOf(
                        "User-Agent" to WEB_UA,
                        "X-YouTube-Client-Name" to "1",
                        "X-YouTube-Client-Version" to WEB_VERSION,
                        "Origin" to "https://www.youtube.com",
                        "Cookie" to "SOCS=CAI",
                    ),
                )
            } catch (_: Exception) {
                continue
            }
            val renderers = mutableListOf<JSONObject>()
            collect(JSONObject(resp), "videoRenderer", renderers)
            for (r in renderers) {
                val id = r.optString("videoId")
                if (id.length != 11) continue
                val owner = text(r.optJSONObject("ownerText")).ifEmpty { text(r.optJSONObject("longBylineText")) }
                if (!matches(owner, name)) continue
                val len = parseClock(text(r.optJSONObject("lengthText")))
                out.add(Candidate(id, text(r.optJSONObject("title")), owner, len))
            }
        }
        return out
    }

    fun matches(owner: String, name: String): Boolean {
        val o = owner.lowercase().replace(" ", "")
        val n = name.lowercase().replace(" ", "")
        return o.isNotEmpty() && (o == n || o.contains(n) || n.contains(o))
    }

    // ------------------------------------------------------------------ official Data API (optional)

    private fun dataApiVideos(p: Prefs, name: String): List<Candidate> {
        val key = p.youtubeKey.trim()
        val enc = { s: String -> URLEncoder.encode(s, "UTF-8") }
        val chanKey = "chan_" + name.lowercase()
        var channelId = p.sp.getString(chanKey, null)
        if (channelId == null) {
            channelId = if (name.lowercase().contains("hormozi")) HORMOZI_ID else {
                val r = JSONObject(Http.get(
                    "https://www.googleapis.com/youtube/v3/search?part=snippet&type=channel&maxResults=1&q=${enc(name)}&key=${enc(key)}"
                ))
                r.getJSONArray("items").getJSONObject(0).getJSONObject("id").getString("channelId")
            }
            p.sp.edit().putString(chanKey, channelId).apply()
        }
        val uploads = "UU" + channelId!!.substring(2)
        val ids = mutableListOf<String>()
        var pageToken = ""
        for (page in 0 until 4) {
            val url = "https://www.googleapis.com/youtube/v3/playlistItems?part=contentDetails&maxResults=50" +
                "&playlistId=$uploads&key=${enc(key)}" + (if (pageToken.isNotEmpty()) "&pageToken=$pageToken" else "")
            val r = JSONObject(Http.get(url))
            val items = r.optJSONArray("items") ?: break
            for (i in 0 until items.length()) {
                items.optJSONObject(i)?.optJSONObject("contentDetails")?.optString("videoId")?.let { if (it.length == 11) ids.add(it) }
            }
            pageToken = r.optString("nextPageToken", "")
            if (pageToken.isEmpty()) break
        }
        val out = mutableListOf<Candidate>()
        for (chunk in ids.chunked(50)) {
            val r = JSONObject(Http.get(
                "https://www.googleapis.com/youtube/v3/videos?part=contentDetails,snippet&id=${chunk.joinToString(",")}&key=${enc(key)}"
            ))
            val items = r.optJSONArray("items") ?: continue
            for (i in 0 until items.length()) {
                val v = items.optJSONObject(i) ?: continue
                val len = parseIso(v.optJSONObject("contentDetails")?.optString("duration") ?: "")
                val sn = v.optJSONObject("snippet")
                out.add(Candidate(v.optString("id"), sn?.optString("title") ?: "", sn?.optString("channelTitle") ?: name, len))
            }
        }
        return out
    }

    // ------------------------------------------------------------------ player + transcript

    fun playerInfo(videoId: String): JSONObject? {
        for (client in listOf(androidClient(), iosClient())) {
            try {
                val body = JSONObject()
                    .put("context", JSONObject().put("client", client.body))
                    .put("videoId", videoId)
                    .put("contentCheckOk", true)
                    .put("racyCheckOk", true)
                val resp = Http.post(
                    "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
                    body.toString(),
                    client.headers + mapOf("Cookie" to "SOCS=CAI"),
                )
                val j = JSONObject(resp)
                j.put("_ua", client.headers["User-Agent"])
                if (j.optJSONObject("playabilityStatus")?.optString("status") == "OK") return j
            } catch (_: Exception) {
            }
        }
        return null
    }

    /** Returns the transcript as text with a [mm:ss] marker roughly every 30 seconds. */
    fun transcript(player: JSONObject): String? {
        val tracks = player.optJSONObject("captions")
            ?.optJSONObject("playerCaptionsTracklistRenderer")
            ?.optJSONArray("captionTracks") ?: return null
        var best: JSONObject? = null
        var bestScore = -1
        for (i in 0 until tracks.length()) {
            val t = tracks.optJSONObject(i) ?: continue
            val lang = t.optString("languageCode")
            val asr = t.optString("kind") == "asr"
            val score = (if (lang.startsWith("en")) 2 else 0) + (if (!asr) 1 else 0)
            if (score > bestScore) { bestScore = score; best = t }
        }
        val base = best?.optString("baseUrl") ?: return null
        if (base.isEmpty()) return null
        val cleaned = base.replace(Regex("&fmt=[^&]*"), "")
        val headers = mapOf("User-Agent" to player.optString("_ua", WEB_UA), "Cookie" to "SOCS=CAI")

        try {
            val json = Http.get("$cleaned&fmt=json3", headers)
            if (json.isNotBlank()) {
                val out = fromJson3(JSONObject(json))
                if (out.length > 200) return out
            }
        } catch (_: Exception) {
        }
        try {
            val xml = Http.get(cleaned, headers)
            if (xml.isNotBlank()) {
                val out = fromXml(xml)
                if (out.length > 200) return out
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun fromJson3(j: JSONObject): String {
        val events = j.optJSONArray("events") ?: return ""
        val sb = StringBuilder()
        var lastStamp = -30_000L
        for (i in 0 until events.length()) {
            val e = events.optJSONObject(i) ?: continue
            val segs = e.optJSONArray("segs") ?: continue
            val t = e.optLong("tStartMs", 0)
            val piece = StringBuilder()
            for (k in 0 until segs.length()) piece.append(segs.optJSONObject(k)?.optString("utf8") ?: "")
            val s = piece.toString().replace("\n", " ").trim()
            if (s.isEmpty()) continue
            if (t - lastStamp >= 30_000) {
                sb.append("\n[").append(clock((t / 1000).toInt())).append("] ")
                lastStamp = t
            }
            sb.append(s).append(' ')
        }
        return sb.toString().trim()
    }

    private fun fromXml(xml: String): String {
        val re = Regex("<(text|p)\\b([^>]*)>(.*?)</\\1>", RegexOption.DOT_MATCHES_ALL)
        val sb = StringBuilder()
        var lastStamp = -30.0
        for (m in re.findAll(xml)) {
            val attrs = m.groupValues[2]
            val start = Regex("start=\"([0-9.]+)\"").find(attrs)?.groupValues?.get(1)?.toDoubleOrNull()
                ?: Regex("\\bt=\"([0-9]+)\"").find(attrs)?.groupValues?.get(1)?.toDoubleOrNull()?.div(1000.0)
                ?: 0.0
            var s = m.groupValues[3].replace(Regex("<[^>]+>"), "")
            s = decode(decode(s)).replace("\n", " ").trim()
            if (s.isEmpty()) continue
            if (start - lastStamp >= 30) {
                sb.append("\n[").append(clock(start.toInt())).append("] ")
                lastStamp = start
            }
            sb.append(s).append(' ')
        }
        return sb.toString().trim()
    }

    // ------------------------------------------------------------------ helpers

    private fun decode(s: String): String =
        if (s.contains('&')) Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString() else s

    fun clock(sec: Int): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }

    private fun parseClock(s: String): Int {
        val parts = s.trim().split(":").mapNotNull { it.trim().toIntOrNull() }
        if (parts.isEmpty()) return 0
        return parts.fold(0) { acc, v -> acc * 60 + v }
    }

    private fun parseIso(s: String): Int {
        val m = Regex("P(?:(\\d+)D)?T?(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?").find(s) ?: return 0
        val g = m.groupValues
        val d = g[1].toIntOrNull() ?: 0
        val h = g[2].toIntOrNull() ?: 0
        val mi = g[3].toIntOrNull() ?: 0
        val se = g[4].toIntOrNull() ?: 0
        return d * 86400 + h * 3600 + mi * 60 + se
    }

    private fun text(o: JSONObject?): String {
        if (o == null) return ""
        val simple = o.optString("simpleText")
        if (simple.isNotEmpty()) return simple
        val runs = o.optJSONArray("runs") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until runs.length()) sb.append(runs.optJSONObject(i)?.optString("text") ?: "")
        return sb.toString()
    }

    private fun collect(o: Any?, key: String, out: MutableList<JSONObject>) {
        when (o) {
            is JSONObject -> {
                val keys = o.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = o.opt(k)
                    if (k == key && v is JSONObject) out.add(v) else collect(v, key, out)
                }
            }
            is JSONArray -> for (i in 0 until o.length()) collect(o.opt(i), key, out)
        }
    }
}
