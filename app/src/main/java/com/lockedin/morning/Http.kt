package com.lockedin.morning

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Tiny blocking HTTP helper. Always call from a background thread. */
object Http {
    fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutSec: Int = 30): String =
        request("GET", url, null, headers, timeoutSec)

    fun post(url: String, body: String, headers: Map<String, String> = emptyMap(), timeoutSec: Int = 30): String =
        request("POST", url, body, headers, timeoutSec)

    private fun request(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String>,
        timeoutSec: Int,
    ): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = timeoutSec * 1000
            conn.instanceFollowRedirects = true
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            if (body != null) {
                conn.doOutput = true
                if (!headers.keys.any { it.equals("Content-Type", ignoreCase = true) }) {
                    conn.setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("HTTP $code: ${text.take(1500)}")
            return text
        } finally {
            conn.disconnect()
        }
    }
}
