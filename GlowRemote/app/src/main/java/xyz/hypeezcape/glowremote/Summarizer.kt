package xyz.hypeezcape.glowremote

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * AI book summarizer. Pulls the current book (text + chapters) from the
 * glasses, summarizes it chapter by chapter with the user's own AI API key,
 * and uploads the result back to the glasses as a readable .md "book".
 *
 * Bring-your-own-key by design: nothing is embedded in the APK.
 */
object Summarizer {

    data class Provider(val label: String, val prefKey: String)

    val PROVIDERS = listOf(
        Provider("DeepSeek", "key_deepseek"),
        Provider("OpenAI", "key_openai"),
        Provider("Claude (Anthropic)", "key_claude")
    )

    @Volatile var running = false; private set

    fun summarize(
        ip: String,
        providerIndex: Int,
        apiKey: String,
        onProgress: (String) -> Unit,
        onDone: (Boolean, String) -> Unit
    ) {
        if (running) { onDone(false, "A summary is already running"); return }
        running = true
        thread {
            try {
                onProgress("Fetching book from glasses…")
                val info = JSONObject(
                    http("http://$ip:8765/book", timeout = 10000)
                        ?: throw Exception("No book open on the glasses")
                )
                val title = info.getString("title")
                val tocArr = info.getJSONArray("toc")
                val text = http("http://$ip:8765/text", timeout = 60000)
                    ?: throw Exception("Could not fetch book text")

                val chunks = buildChunks(text, tocArr)
                onProgress("Summarizing ${chunks.size} chapter(s)…")

                val sb = StringBuilder()
                sb.append("# $title — AI Summary\n\n")
                sb.append("Generated ${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())} · ${PROVIDERS[providerIndex].label} · chapter by chapter\n\n")

                for ((i, chunk) in chunks.withIndex()) {
                    onProgress("Chapter ${i + 1} of ${chunks.size}: ${chunk.first.take(30)}…")
                    val summary = try {
                        callApi(providerIndex, apiKey, prompt(title, chunk.first, chunk.second))
                    } catch (e: Exception) {
                        try { callApi(providerIndex, apiKey, prompt(title, chunk.first, chunk.second)) }
                        catch (e2: Exception) { "(Summary failed for this chapter: ${e2.message})" }
                    }
                    sb.append("## ${chunk.first}\n\n").append(summary.trim()).append("\n\n")
                }

                onProgress("Sending summary to the glasses…")
                val name = (title.take(60) + " - AI Summary.md")
                val body = sb.toString().toByteArray(Charsets.UTF_8)
                val up = http(
                    "http://$ip:8765/upload?name=${URLEncoder.encode(name, "UTF-8")}",
                    post = body, timeout = 60000
                ) ?: throw Exception("Upload to glasses failed")

                running = false
                onDone(true, "Done! \"$name\" is now in the glasses library ($up)")
            } catch (e: Exception) {
                running = false
                onDone(false, "Failed: ${e.message}")
            }
        }
    }

    // ---------------- chunking ----------------

    /** Returns (chapterTitle, chapterText) pairs. Falls back to size-based parts. */
    private fun buildChunks(text: String, toc: JSONArray): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        if (toc.length() >= 2) {
            for (i in 0 until toc.length()) {
                val o = toc.getJSONObject(i)
                val start = o.getInt("offset").coerceIn(0, text.length)
                val end = if (i + 1 < toc.length())
                    toc.getJSONObject(i + 1).getInt("offset").coerceIn(0, text.length)
                else text.length
                if (end - start < 200) continue   // skip cover pages / blanks
                out.add(o.getString("title") to text.substring(start, end).take(60000))
            }
        }
        if (out.isEmpty()) {
            // no usable chapters: split into ~30k-char parts at paragraph breaks
            var start = 0
            var part = 1
            while (start < text.length) {
                var end = (start + 30000).coerceAtMost(text.length)
                if (end < text.length) {
                    val brk = text.lastIndexOf("\n\n", end)
                    if (brk > start + 5000) end = brk
                }
                out.add("Part $part" to text.substring(start, end))
                start = end
                part++
            }
        }
        // cap the number of API calls: merge adjacent chunks beyond 60
        while (out.size > 60) {
            val merged = ArrayList<Pair<String, String>>()
            var i = 0
            while (i < out.size) {
                if (i + 1 < out.size && merged.size < 60) {
                    merged.add("${out[i].first} / ${out[i + 1].first}" to (out[i].second + "\n\n" + out[i + 1].second).take(60000))
                    i += 2
                } else { merged.add(out[i]); i++ }
            }
            out.clear(); out.addAll(merged)
        }
        return out
    }

    private fun prompt(bookTitle: String, chapterTitle: String, chapterText: String): String =
        "You are summarizing one chapter of the book \"$bookTitle\".\n" +
        "Write a detailed summary of this chapter: its core ideas, key arguments or events in the order they appear, and the main takeaways. " +
        "Length: 150-300 words. Respond in the same language as the chapter text. " +
        "Output only the summary, no preamble.\n\n" +
        "Chapter: $chapterTitle\n\nText:\n$chapterText"

    // ---------------- providers ----------------

    private fun callApi(providerIndex: Int, key: String, prompt: String): String {
        return when (providerIndex) {
            2 -> callClaude(key, prompt)
            1 -> callOpenAiStyle("https://api.openai.com/v1/chat/completions", "gpt-4o-mini", key, prompt)
            else -> callOpenAiStyle("https://api.deepseek.com/chat/completions", "deepseek-chat", key, prompt)
        }
    }

    private fun callOpenAiStyle(url: String, model: String, key: String, prompt: String): String {
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.3)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        val resp = httpJson(url, body.toString(), mapOf("Authorization" to "Bearer $key"))
        return JSONObject(resp).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
    }

    private fun callClaude(key: String, prompt: String): String {
        val body = JSONObject()
            .put("model", "claude-haiku-4-5-20251001")
            .put("max_tokens", 1500)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        val resp = httpJson(
            "https://api.anthropic.com/v1/messages", body.toString(),
            mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01")
        )
        return JSONObject(resp).getJSONArray("content").getJSONObject(0).getString("text")
    }

    // ---------------- http ----------------

    private fun httpJson(url: String, json: String, headers: Map<String, String>): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 180000
        c.requestMethod = "POST"
        c.setRequestProperty("Content-Type", "application/json")
        for ((k, v) in headers) c.setRequestProperty(k, v)
        c.doOutput = true
        val bytes = json.toByteArray(Charsets.UTF_8)
        c.setFixedLengthStreamingMode(bytes.size)
        c.outputStream.use { it.write(bytes) }
        if (c.responseCode !in 200..299) {
            val err = c.errorStream?.readBytes()?.toString(Charsets.UTF_8)?.take(300)
            throw Exception("API ${c.responseCode}: $err")
        }
        return c.inputStream.readBytes().toString(Charsets.UTF_8)
    }

    private fun http(url: String, post: ByteArray? = null, timeout: Int = 6000): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 3000
        c.readTimeout = timeout
        if (post != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setFixedLengthStreamingMode(post.size)
            c.outputStream.use { it.write(post) }
        }
        c.inputStream.readBytes().toString(Charsets.UTF_8)
    } catch (_: Exception) { null }
}
