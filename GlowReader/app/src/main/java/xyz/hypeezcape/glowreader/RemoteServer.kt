package xyz.hypeezcape.glowreader

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/** Shared handles so the HTTP server can drive whatever screen is open. */
object AppState {
    val main = Handler(Looper.getMainLooper())

    /** The foreground screen's key handler (set in onResume). */
    @Volatile var keySink: ((Int) -> Unit)? = null

    /** Current reader view, if a book is open (for status reporting). */
    @Volatile var reader: ReaderView? = null
}

/**
 * Tiny HTTP control server on port 8765 — lets the companion phone app
 * change settings, send remote-control commands, and upload books.
 *
 *   GET  /ping                      -> "rokidreader"
 *   GET  /status                    -> JSON: settings + state + book list
 *   POST /set?k=<key>&v=<value>     -> change a setting (applies live)
 *   POST /cmd?k=<command>[&path=..] -> next|prev|playpause|mode|back|open|
 *                                      save_preset|restore_preset|defaults
 *   POST /upload?name=<file>        -> raw body saved into the Books folder
 */
object RemoteServer {
    const val PORT = 8765
    @Volatile private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        thread(isDaemon = true, name = "GlowReaderRemote") {
            try {
                val server = ServerSocket(PORT)
                while (true) {
                    val client = server.accept()
                    thread(isDaemon = true) { handle(client, app) }
                }
            } catch (_: Exception) {
                started = false
            }
        }
    }

    fun localIp(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is InetAddress && !it.isLoopbackAddress && it.hostAddress?.contains('.') == true }
                ?.hostAddress
        } catch (_: Exception) { null }
    }

    // ---------------- request handling ----------------

    private fun handle(socket: Socket, ctx: Context) {
        socket.use { s ->
            try {
                val input = s.getInputStream()
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) return
                val method = parts[0]
                val fullPath = parts[1]

                var contentLength = 0
                while (true) {
                    val h = readLine(input) ?: break
                    if (h.isEmpty()) break
                    if (h.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = h.substringAfter(':').trim().toIntOrNull() ?: 0
                    }
                }

                val path = fullPath.substringBefore('?')
                val query = parseQuery(fullPath.substringAfter('?', ""))

                when {
                    path == "/ping" -> respond(s, 200, "text/plain", "glowreader".toByteArray())
                    path == "/status" -> respond(s, 200, "application/json", status(ctx).toString().toByteArray())
                    path == "/book" -> {
                        val r = AppState.reader
                        if (r == null) respond(s, 409, "application/json", "{\"error\":\"no book open\"}".toByteArray())
                        else respond(s, 200, "application/json", bookInfo(ctx, r).toString().toByteArray())
                    }
                    path == "/text" -> {
                        val r = AppState.reader
                        if (r == null) respond(s, 409, "text/plain", "no book open".toByteArray())
                        else respond(s, 200, "text/plain; charset=utf-8", r.bookText.toByteArray(Charsets.UTF_8))
                    }
                    path == "/goto" && method == "POST" -> {
                        val off = query["offset"]?.toIntOrNull()
                        val r = AppState.reader
                        if (off == null || r == null) respond(s, 400, "text/plain", "bad".toByteArray())
                        else {
                            AppState.main.post { r.gotoFromRemote(off) }
                            respond(s, 200, "text/plain", "ok".toByteArray())
                        }
                    }
                    path == "/hl/add" && method == "POST" -> {
                        val a = query["start"]?.toIntOrNull(); val b = query["end"]?.toIntOrNull()
                        val r = AppState.reader
                        if (a == null || b == null || r == null) respond(s, 400, "text/plain", "bad".toByteArray())
                        else {
                            AppState.main.post { r.addHighlight(a, b) }
                            respond(s, 200, "text/plain", "ok".toByteArray())
                        }
                    }
                    path == "/hl/del" && method == "POST" -> {
                        val a = query["start"]?.toIntOrNull(); val b = query["end"]?.toIntOrNull()
                        val r = AppState.reader
                        if (a == null || b == null || r == null) respond(s, 400, "text/plain", "bad".toByteArray())
                        else {
                            AppState.main.post { r.removeHighlightsOverlapping(a, b) }
                            respond(s, 200, "text/plain", "ok".toByteArray())
                        }
                    }
                    path == "/bm/add" && method == "POST" -> {
                        val off = query["offset"]?.toIntOrNull()
                        val r = AppState.reader
                        if (off == null || r == null) respond(s, 400, "text/plain", "bad".toByteArray())
                        else {
                            val arr = try { org.json.JSONArray(Prefs.bookmarksJson(ctx, r.bookPath)) } catch (_: Exception) { org.json.JSONArray() }
                            arr.put(JSONObject().put("o", off).put("label", query["label"] ?: ""))
                            Prefs.setBookmarksJson(ctx, r.bookPath, arr.toString())
                            respond(s, 200, "text/plain", "ok".toByteArray())
                        }
                    }
                    path == "/bm/del" && method == "POST" -> {
                        val off = query["offset"]?.toIntOrNull()
                        val r = AppState.reader
                        if (off == null || r == null) respond(s, 400, "text/plain", "bad".toByteArray())
                        else {
                            val arr = try { org.json.JSONArray(Prefs.bookmarksJson(ctx, r.bookPath)) } catch (_: Exception) { org.json.JSONArray() }
                            val out = org.json.JSONArray()
                            for (i in 0 until arr.length()) {
                                val o = arr.getJSONObject(i)
                                if (o.optInt("o") != off) out.put(o)
                            }
                            Prefs.setBookmarksJson(ctx, r.bookPath, out.toString())
                            respond(s, 200, "text/plain", "ok".toByteArray())
                        }
                    }
                    path == "/set" && method == "POST" -> {
                        val ok = applySetting(ctx, query["k"] ?: "", query["v"] ?: "")
                        respond(s, if (ok) 200 else 400, "text/plain", (if (ok) "ok" else "bad key").toByteArray())
                    }
                    path == "/cmd" && method == "POST" -> {
                        val ok = command(ctx, query["k"] ?: "", query["path"])
                        respond(s, if (ok) 200 else 400, "text/plain", (if (ok) "ok" else "bad cmd").toByteArray())
                    }
                    path == "/upload" && method == "POST" -> {
                        val name = sanitize(query["name"] ?: "book_${System.currentTimeMillis()}.txt")
                        val dir = ctx.getExternalFilesDir("Books")!!
                        dir.mkdirs()
                        val out = File(dir, name)
                        out.outputStream().use { fos ->
                            val buf = ByteArray(64 * 1024)
                            var remaining = contentLength
                            while (remaining > 0) {
                                val n = input.read(buf, 0, minOf(buf.size, remaining))
                                if (n <= 0) break
                                fos.write(buf, 0, n)
                                remaining -= n
                            }
                        }
                        respond(s, 200, "text/plain", "saved ${out.length()}".toByteArray())
                    }
                    else -> respond(s, 404, "text/plain", "not found".toByteArray())
                }
            } catch (_: Exception) { /* client gone — ignore */ }
        }
    }

    /** Full book metadata for the phone reader: TOC, highlights, bookmarks, position. */
    private fun bookInfo(ctx: Context, r: ReaderView): JSONObject {
        val toc = JSONArray()
        for (t in r.toc) toc.put(JSONObject().put("title", t.title).put("offset", t.offset))
        val hls = JSONArray()
        for (h in r.highlights.toList()) hls.put(JSONArray().put(h[0]).put(h[1]))
        val bms = try { JSONArray(Prefs.bookmarksJson(ctx, r.bookPath)) } catch (_: Exception) { JSONArray() }
        return JSONObject()
            .put("path", r.bookPath)
            .put("title", java.io.File(r.bookPath).nameWithoutExtension)
            .put("length", r.bookText.length)
            .put("offset", r.currentOffset())
            .put("toc", toc)
            .put("highlights", hls)
            .put("bookmarks", bms)
    }

    private fun status(ctx: Context): JSONObject {
        val settings = JSONObject()
            .put("font", Prefs.fontSizeSp(ctx).toInt())
            .put("wpm", Prefs.wpm(ctx))
            .put("bright", Prefs.brightLevel(ctx))
            .put("lowlight", Prefs.lowLight(ctx))
            .put("areatop", Prefs.areaTopPct(ctx))
            .put("areaheight", Prefs.areaHeightPct(ctx))
            .put("strict", Prefs.strictLines(ctx))

        val state = JSONObject()
        val r = AppState.reader
        if (r != null) {
            try { r.fillStatus(state) } catch (_: Exception) { state.put("screen", "reader") }
        } else {
            state.put("screen", "library")
        }

        val books = JSONArray()
        for (b in Books.scan(ctx)) {
            books.put(JSONObject()
                .put("name", b.title)
                .put("path", b.file.absolutePath)
                .put("kb", b.file.length() / 1024))
        }
        return JSONObject().put("app", "glowreader").put("settings", settings)
            .put("state", state).put("books", books)
    }

    private fun applySetting(ctx: Context, key: String, value: String): Boolean {
        when (key) {
            "font" -> Prefs.setFontSizeSp(ctx, value.toFloatOrNull() ?: return false)
            "wpm" -> Prefs.setWpm(ctx, value.toIntOrNull() ?: return false)
            "bright" -> Prefs.setBrightLevel(ctx, value.toIntOrNull() ?: return false)
            "lowlight" -> Prefs.setLowLight(ctx, value == "1" || value == "true")
            "areatop" -> Prefs.setAreaTopPct(ctx, value.toIntOrNull() ?: return false)
            "areaheight" -> Prefs.setAreaHeightPct(ctx, value.toIntOrNull() ?: return false)
            "strict" -> Prefs.setStrictLines(ctx, value == "1" || value == "true")
            else -> return false
        }
        return true
    }

    private fun command(ctx: Context, cmd: String, path: String?): Boolean {
        when (cmd) {
            "next" -> AppState.main.post { AppState.keySink?.invoke(KeyEvent.KEYCODE_DPAD_RIGHT) }
            "prev" -> AppState.main.post { AppState.keySink?.invoke(KeyEvent.KEYCODE_DPAD_LEFT) }
            "up" -> AppState.main.post { AppState.keySink?.invoke(KeyEvent.KEYCODE_DPAD_UP) }
            "down" -> AppState.main.post { AppState.keySink?.invoke(KeyEvent.KEYCODE_DPAD_DOWN) }
            "playpause" -> AppState.main.post { AppState.keySink?.invoke(KeyEvent.KEYCODE_SPACE) }
            "pagenext" -> AppState.main.post {
                AppState.reader?.screenStep(true) ?: AppState.keySink?.invoke(KeyEvent.KEYCODE_DPAD_DOWN)
            }
            "pageprev" -> AppState.main.post {
                AppState.reader?.screenStep(false) ?: AppState.keySink?.invoke(KeyEvent.KEYCODE_DPAD_UP)
            }
            "enter" -> AppState.main.post { AppState.keySink?.invoke(KeyEvent.KEYCODE_ENTER) }
            "mode" -> AppState.main.post { AppState.keySink?.invoke(KeyEvent.KEYCODE_M) }
            "back" -> AppState.main.post { AppState.keySink?.invoke(KeyEvent.KEYCODE_B) }
            "save_preset" -> Prefs.savePreset(ctx)
            "restore_preset" -> Prefs.restorePreset(ctx)
            "defaults" -> Prefs.factoryDefaults(ctx)
            "open" -> {
                val p = path ?: return false
                AppState.main.post {
                    Prefs.setLastBook(ctx, p)
                    ctx.startActivity(
                        Intent(ctx, ReaderActivity::class.java)
                            .putExtra("path", p)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            else -> return false
        }
        return true
    }

    // ---------------- tiny HTTP plumbing ----------------

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
            if (sb.length > 8192) break
        }
        return sb.toString()
    }

    private fun parseQuery(q: String): Map<String, String> =
        q.split('&').filter { it.contains('=') }.associate {
            val k = it.substringBefore('=')
            val v = try { URLDecoder.decode(it.substringAfter('='), "UTF-8") } catch (_: Exception) { it.substringAfter('=') }
            k to v
        }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120)

    private fun respond(socket: Socket, code: Int, type: String, body: ByteArray) {
        val out = BufferedOutputStream(socket.getOutputStream())
        val statusText = if (code == 200) "OK" else "Error"
        out.write("HTTP/1.1 $code $statusText\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(body)
        out.flush()
    }
}
