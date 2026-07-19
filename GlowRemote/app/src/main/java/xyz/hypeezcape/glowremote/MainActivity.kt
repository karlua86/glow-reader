package xyz.hypeezcape.glowremote

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Companion remote control for RokidReader on the glasses.
 * Finds the glasses on the same WiFi (port 8765), then offers big-button
 * remote control, live settings, and "send book to glasses".
 */
class MainActivity : Activity() {

    private lateinit var ipBox: EditText
    private lateinit var statusView: TextView
    private lateinit var nowView: TextView
    private lateinit var aiProvider: android.widget.Spinner
    private lateinit var aiLength: android.widget.Spinner
    private lateinit var aiKey: EditText
    private lateinit var aiStatus: TextView

    private val ui = Handler(Looper.getMainLooper())
    private var lastStatus: JSONObject? = null
    private var polling = false

    private val ip: String get() = ipBox.text.toString().trim()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }

        root.addView(title("GLOW REMOTE"))

        // ---- connection ----
        root.addView(section("Connection"))
        ipBox = EditText(this).apply {
            hint = "Glasses IP (shown at bottom of library screen)"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(prefs().getString("ip", "") ?: "")
        }
        root.addView(ipBox)
        root.addView(row(
            btn("Find glasses") { scan() },
            btn("Connect") { checkConnection(true) }
        ))
        statusView = TextView(this).apply { text = "Not connected"; setPadding(0, dp(6), 0, dp(6)) }
        root.addView(statusView)
        nowView = TextView(this).apply { text = ""; setPadding(0, 0, 0, dp(6)) }
        root.addView(nowView)

        // ---- send book ----
        root.addView(section("Books"))
        root.addView(btn("📖  Send a book to the glasses") { pickBook() })
        root.addView(btn("📑  Reader view — navigate & highlight") {
            if (ip.isEmpty()) { toast("Connect to the glasses first") }
            else startActivity(Intent(this, ReaderActivity::class.java).putExtra("ip", ip))
        })
        root.addView(btn("🗂  Manage / delete books…") { manageBooks() })

        // ---- AI summary (bring your own API key) ----
        root.addView(section("AI summary of current book"))
        aiProvider = android.widget.Spinner(this)
        aiProvider.adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            Summarizer.PROVIDERS.map { it.label })
        aiKey = EditText(this).apply {
            hint = "Paste your API key here (stored only on this phone)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        aiProvider.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                aiKey.setText(prefs().getString(Summarizer.PROVIDERS[pos].prefKey, "") ?: "")
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        aiProvider.setSelection(prefs().getInt("ai_provider", 0))
        root.addView(aiProvider)
        root.addView(aiKey)
        aiLength = android.widget.Spinner(this)
        aiLength.adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            Summarizer.LENGTHS.map { it.label })
        aiLength.setSelection(prefs().getInt("ai_length", 1))
        root.addView(aiLength)
        aiStatus = TextView(this).apply { textSize = 13f; setPadding(0, dp(4), 0, dp(4)) }
        root.addView(btn("🤖  Summarize book on glasses → send back as a book") { runSummary() })
        root.addView(aiStatus)

        // ---- remote ----
        root.addView(section("Remote control"))
        root.addView(row(
            btn("‹  Back") { cmd("pageprev") },
            btn("▶ ⏸") { cmd("playpause") },
            btn("Fwd  ›") { cmd("pagenext") }
        ))
        root.addView(row(
            btn("Mode") { cmd("mode") },
            btn("Library") { cmd("back") },
            btn("Open last") { openLast() }
        ))
        root.addView(btn("🙈  Close / reopen book (blank the glasses)") { cmd("blank") })
        root.addView(TextView(this).apply {
            text = "‹ › = one page/step in every mode · Mode cycles: page → word → scroll → sentence → paragraph"
            textSize = 12f; alpha = 0.6f; setPadding(0, dp(4), 0, 0)
        })

        // ---- reading settings ----
        root.addView(section("Reading settings (live)"))
        root.addView(row(
            btn("Font −") { bump("font", -2) },
            btn("Font +") { bump("font", +2) }
        ))
        root.addView(row(
            btn("Speed −25") { bump("wpm", -25) },
            btn("Speed +25") { bump("wpm", +25) }
        ))
        root.addView(row(
            btn("Text dimmer") { cycleBright() },
            btn("Low-light: toggle") { toggle("lowlight") }
        ))
        root.addView(btn("Full lines only: toggle") { toggle("strict") })

        // ---- display area ----
        root.addView(section("Display area position"))
        root.addView(row(
            btn("Move ↑") { bump("areatop", -5) },
            btn("Move ↓") { bump("areatop", +5) }
        ))
        root.addView(row(
            btn("Shorter") { bump("areaheight", -10) },
            btn("Taller") { bump("areaheight", +10) }
        ))

        // ---- presets ----
        root.addView(section("Presets"))
        root.addView(row(
            btn("Save preferred") { cmd("save_preset"); toast("Saved current settings") },
            btn("Restore") { cmd("restore_preset") }
        ))
        root.addView(btn("Reset to defaults") { cmd("defaults") })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        polling = true
        pollLoop()
    }

    override fun onPause() {
        super.onPause()
        polling = false
    }

    // ---------------- networking ----------------

    private fun api(pathAndQuery: String, body: ByteArray? = null, timeoutMs: Int = 4000, done: ((String?) -> Unit)? = null) {
        val host = ip
        if (host.isEmpty()) { done?.invoke(null); return }
        thread {
            val result = try {
                val c = URL("http://$host:8765$pathAndQuery").openConnection() as HttpURLConnection
                c.connectTimeout = 2000
                c.readTimeout = timeoutMs
                if (body != null) {
                    c.requestMethod = "POST"
                    c.doOutput = true
                    c.setFixedLengthStreamingMode(body.size)
                    c.outputStream.use { it.write(body) }
                } else {
                    c.requestMethod = if (pathAndQuery.startsWith("/ping") || pathAndQuery.startsWith("/status")) "GET" else "POST"
                }
                c.inputStream.readBytes().toString(Charsets.UTF_8)
            } catch (_: Exception) { null }
            ui.post { done?.invoke(result) }
        }
    }

    private fun pollLoop() {
        if (!polling) return
        api("/status") { txt ->
            if (txt != null) {
                try {
                    val j = JSONObject(txt)
                    lastStatus = j
                    val s = j.getJSONObject("settings")
                    statusView.text = "✅ Connected to glasses ($ip)"
                    val st = j.getJSONObject("state")
                    val where = if (st.optString("screen") == "reader")
                        (if (st.optBoolean("blanked")) "📕 Book closed (glasses blank) · " else "") +
                        "Reading: ${st.optString("book")} · ${st.optString("mode")} · ${st.optInt("percent")}%${if (st.optBoolean("playing")) " ▶" else ""}"
                    else "On library screen · ${j.getJSONArray("books").length()} book(s)"
                    nowView.text = "$where\nFont ${s.optInt("font")} · ${s.optInt("wpm")} wpm · area ${s.optInt("areatop")}%+${s.optInt("areaheight")}% · low-light ${if (s.optBoolean("lowlight")) "ON" else "off"} · full-lines ${if (s.optBoolean("strict")) "ON" else "off"}"
                } catch (_: Exception) { statusView.text = "⚠️ Unexpected reply" }
            } else {
                statusView.text = "❌ Not connected — open Glow Reader on the glasses, then tap Find glasses"
                nowView.text = ""
            }
            ui.postDelayed({ pollLoop() }, 2000)
        }
    }

    private fun checkConnection(save: Boolean) {
        api("/ping") { r ->
            if (r?.contains("glowreader") == true) {
                if (save) prefs().edit().putString("ip", ip).apply()
                toast("Connected!")
            } else toast("No glasses at $ip — is RokidReader open?")
        }
    }

    /** Scan the local /24 subnet for the glasses (port 8765). */
    private fun scan() {
        toast("Scanning your WiFi for the glasses…")
        thread {
            val base = localSubnet() ?: run { ui.post { toast("WiFi not connected?") }; return@thread }
            val pool = Executors.newFixedThreadPool(32)
            var found: String? = null
            for (i in 1..254) {
                val host = "$base.$i"
                pool.execute {
                    if (found != null) return@execute
                    try {
                        Socket().use { s ->
                            s.connect(InetSocketAddress(host, 8765), 200)
                            // confirm it's actually our app
                            val c = URL("http://$host:8765/ping").openConnection() as HttpURLConnection
                            c.connectTimeout = 500; c.readTimeout = 800
                            if (c.inputStream.readBytes().toString(Charsets.UTF_8).contains("glowreader")) {
                                found = host
                            }
                        }
                    } catch (_: Exception) { }
                }
            }
            pool.shutdown()
            pool.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS)
            ui.post {
                val f = found
                if (f != null) {
                    ipBox.setText(f)
                    prefs().edit().putString("ip", f).apply()
                    toast("Found glasses at $f")
                } else {
                    toast("Not found. Open Glow Reader on the glasses and check both are on the same WiFi.")
                }
            }
        }
    }

    private fun localSubnet(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.count { ch -> ch == '.' } == 3 }
            ?.hostAddress?.substringBeforeLast('.')
    } catch (_: Exception) { null }

    // ---------------- actions ----------------

    private fun cmd(k: String) = api("/cmd?k=$k", ByteArray(0))

    private fun toggle(key: String) {
        val s = lastStatus?.optJSONObject("settings") ?: run { toast("Not connected"); return }
        val cur = s.optBoolean(if (key == "strict") "strict" else "lowlight")
        api("/set?k=$key&v=${if (cur) "0" else "1"}", ByteArray(0))
    }

    private fun bump(key: String, delta: Int) {
        val s = lastStatus?.optJSONObject("settings") ?: run { toast("Not connected"); return }
        val cur = s.optInt(when (key) { "font" -> "font"; "wpm" -> "wpm"; "areatop" -> "areatop"; else -> "areaheight" })
        api("/set?k=$key&v=${cur + delta}", ByteArray(0))
    }

    private fun cycleBright() {
        val s = lastStatus?.optJSONObject("settings") ?: run { toast("Not connected"); return }
        api("/set?k=bright&v=${(s.optInt("bright") + 1) % 5}", ByteArray(0))
    }

    private fun openLast() {
        val books = lastStatus?.optJSONArray("books") ?: run { toast("Not connected"); return }
        if (books.length() == 0) { toast("No books on the glasses yet"); return }
        val path = books.getJSONObject(0).getString("path")
        api("/cmd?k=open&path=${URLEncoder.encode(path, "UTF-8")}", ByteArray(0))
    }

    // ---------------- manage / delete books ----------------

    private fun manageBooks() {
        val books = lastStatus?.optJSONArray("books") ?: run { toast("Not connected"); return }
        if (books.length() == 0) { toast("No books on the glasses"); return }
        val labels = (0 until books.length()).map {
            val b = books.getJSONObject(it)
            "${b.getString("name")}  (${b.optLong("kb")} KB)"
        }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Books on the glasses")
            .setItems(labels) { _, which ->
                val b = books.getJSONObject(which)
                android.app.AlertDialog.Builder(this)
                    .setTitle("Delete \"${b.getString("name")}\"?")
                    .setMessage("This removes the file from the glasses. It cannot be undone.")
                    .setPositiveButton("Delete") { _, _ ->
                        api("/delbook?path=${URLEncoder.encode(b.getString("path"), "UTF-8")}", ByteArray(0)) { r ->
                            toast(if (r == "deleted") "Deleted" else "Could not delete (${r ?: "no reply"})")
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ---------------- AI summary ----------------

    private fun runSummary() {
        if (ip.isEmpty()) { toast("Connect to the glasses first"); return }
        val pos = aiProvider.selectedItemPosition
        val key = aiKey.text.toString().trim()
        if (key.isEmpty()) { toast("Paste your ${Summarizer.PROVIDERS[pos].label} API key first"); return }
        val lenPos = aiLength.selectedItemPosition
        prefs().edit().putString(Summarizer.PROVIDERS[pos].prefKey, key)
            .putInt("ai_provider", pos).putInt("ai_length", lenPos).apply()
        if (Summarizer.running) { toast("A summary is already running"); return }
        aiStatus.text = "Starting… keep the app open."
        Summarizer.summarize(ip, pos, key, lenPos,
            onProgress = { msg -> ui.post { aiStatus.text = "⏳ $msg" } },
            onDone = { ok, msg -> ui.post {
                aiStatus.text = (if (ok) "✅ " else "❌ ") + msg
                toast(msg)
            } })
    }

    // ---------------- book upload ----------------

    private fun pickBook() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/epub+zip", "application/pdf", "text/plain", "text/markdown", "application/octet-stream"
            ))
        }
        startActivityForResult(i, 42)
    }

    @Deprecated("simple flow")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 42 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val name = queryName(uri) ?: "book_${System.currentTimeMillis()}"
        toast("Sending $name …")
        thread {
            try {
                val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                ui.post {
                    api("/upload?name=${URLEncoder.encode(name, "UTF-8")}", bytes, timeoutMs = 120000) { r ->
                        toast(if (r != null) "✅ $name sent — it appears in the glasses library" else "❌ Send failed — check connection")
                    }
                }
            } catch (e: Exception) {
                ui.post { toast("Could not read file: ${e.message}") }
            }
        }
    }

    private fun queryName(uri: Uri): String? =
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }

    // ---------------- ui helpers ----------------

    private fun prefs() = getSharedPreferences("remote", Context.MODE_PRIVATE)

    private fun title(t: String) = TextView(this).apply {
        text = t; textSize = 22f; typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(10))
    }

    private fun section(t: String) = TextView(this).apply {
        text = t.uppercase(); textSize = 13f; typeface = Typeface.DEFAULT_BOLD
        alpha = 0.6f; setPadding(0, dp(18), 0, dp(6))
    }

    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg buttons: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (b in buttons) {
            addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
