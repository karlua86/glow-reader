package xyz.hypeezcape.glowremote

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.view.ActionMode
import android.view.GestureDetector
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

/**
 * Phone-side mirror of the book open on the glasses.
 *
 * - Shows the same text, auto-scrolls to follow the glasses ("Follow")
 * - TOC dropdown, bookmarks and a position slider — all jump the GLASSES
 * - Double-tap a spot in the text to send the glasses there
 * - Select text -> "Highlight" appears in the menu; highlights render
 *   yellow here and brighter/underlined on the glasses
 */
class ReaderActivity : Activity() {

    private lateinit var ip: String
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var titleView: TextView
    private lateinit var tocSpinner: Spinner
    private lateinit var follow: ToggleButton
    private lateinit var seek: SeekBar
    private lateinit var pctView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var textView: TextView

    private var bookPath = ""
    private var bookLen = 1
    private var text: String = ""
    private var span: SpannableString? = null
    private var toc: List<Pair<String, Int>> = emptyList()
    private var highlights = ArrayList<IntArray>()
    private var bookmarks = ArrayList<Pair<Int, String>>()

    private var lastRemoteOffset = -1
    private var seeking = false
    private var suppressSpinner = true
    private var polling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ip = intent.getStringExtra("ip") ?: ""

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        titleView = TextView(this).apply {
            text = "Loading book from glasses…"
            textSize = 16f; typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(6))
        }
        root.addView(titleView)

        // row: TOC dropdown + follow toggle
        tocSpinner = Spinner(this)
        follow = ToggleButton(this).apply {
            textOn = "Following"; textOff = "Follow: off"; isChecked = true
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(tocSpinner, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(follow)
        })

        // row: slider + percent
        seek = SeekBar(this).apply { max = 1000 }
        pctView = TextView(this).apply { text = "0%"; setPadding(dp(8), 0, 0, 0) }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(seek, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(pctView)
        })

        // row: bookmark buttons
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(actionBtn("🔖 Bookmark here") { addBookmark() },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(actionBtn("Bookmarks…") { showBookmarks() },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })

        root.addView(TextView(this).apply {
            text = "Double-tap = send glasses there · select text = highlight"
            textSize = 11f; alpha = 0.6f; setPadding(0, dp(2), 0, dp(4))
        })

        textView = TextView(this).apply {
            textSize = 16f
            setLineSpacing(0f, 1.25f)
            setTextIsSelectable(true)
            setPadding(0, 0, 0, dp(40))
        }
        scroll = ScrollView(this).apply { addView(textView) }
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)

        setupSelectionMenu()
        setupDoubleTap()
        setupSeekBar()
        setupToc()
        loadBook()
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

    // ---------------- loading ----------------

    private fun loadBook() {
        api("/book") { info ->
            if (info == null) {
                toast("No book open on the glasses — open one first")
                finish(); return@api
            }
            try {
                val j = JSONObject(info)
                bookPath = j.getString("path")
                bookLen = j.getInt("length").coerceAtLeast(1)
                titleView.text = j.getString("title")
                val tocArr = j.getJSONArray("toc")
                toc = (0 until tocArr.length()).map {
                    val o = tocArr.getJSONObject(it)
                    o.getString("title") to o.getInt("offset")
                }
                readAnnotations(j)
                lastRemoteOffset = j.optInt("offset")
                fillTocSpinner()
            } catch (e: Exception) { toast("Bad reply: ${e.message}"); finish(); return@api }

            api("/text", timeoutMs = 60000) { t ->
                if (t == null) { toast("Could not load text"); finish(); return@api }
                text = t
                span = SpannableString(text)
                applyHighlightSpans()
                textView.setText(span, TextView.BufferType.SPANNABLE)
                textView.post { scrollToOffset(lastRemoteOffset); updateSlider(lastRemoteOffset) }
            }
        }
    }

    private fun readAnnotations(j: JSONObject) {
        highlights = ArrayList()
        val hl = j.optJSONArray("highlights") ?: JSONArray()
        for (i in 0 until hl.length()) {
            val p = hl.getJSONArray(i)
            highlights.add(intArrayOf(p.getInt(0), p.getInt(1)))
        }
        bookmarks = ArrayList()
        val bm = j.optJSONArray("bookmarks") ?: JSONArray()
        for (i in 0 until bm.length()) {
            val o = bm.getJSONObject(i)
            bookmarks.add(o.getInt("o") to o.optString("label"))
        }
    }

    // ---------------- highlights ----------------

    private fun applyHighlightSpans() {
        val s = span ?: return
        for (sp in s.getSpans(0, s.length, BackgroundColorSpan::class.java)) s.removeSpan(sp)
        for (h in highlights) {
            val a = h[0].coerceIn(0, s.length); val b = h[1].coerceIn(0, s.length)
            if (b > a) s.setSpan(BackgroundColorSpan(0x66FFD54F), a, b, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun setupSelectionMenu() {
        textView.customSelectionActionModeCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(0, 101, 0, "✨ Highlight")
                menu.add(0, 102, 1, "Remove highlight")
                return true
            }
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = true
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                val a = textView.selectionStart; val b = textView.selectionEnd
                if (a < 0 || b <= a) return false
                when (item.itemId) {
                    101 -> {
                        highlights.add(intArrayOf(a, b))
                        applyHighlightSpans()
                        api("/hl/add?start=$a&end=$b", post = true)
                        toast("Highlighted — shown brighter on the glasses")
                    }
                    102 -> {
                        highlights.removeAll { it[0] < b && it[1] > a }
                        applyHighlightSpans()
                        api("/hl/del?start=$a&end=$b", post = true)
                    }
                    else -> return false
                }
                mode.finish()
                return true
            }
            override fun onDestroyActionMode(mode: ActionMode) {}
        }
    }

    // ---------------- navigation ----------------

    private fun setupDoubleTap() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val off = textView.getOffsetForPosition(e.x, e.y)
                if (off >= 0) {
                    api("/goto?offset=$off", post = true)
                    toast("Glasses moved here")
                    updateSlider(off)
                }
                return true
            }
        })
        textView.setOnTouchListener { v, e ->
            detector.onTouchEvent(e)
            false   // let selection/scroll still work
        }
    }

    private fun setupSeekBar() {
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) pctView.text = "${progress / 10}%"
            }
            override fun onStartTrackingTouch(sb: SeekBar) { seeking = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                seeking = false
                val off = (sb.progress.toLong() * bookLen / 1000).toInt()
                api("/goto?offset=$off", post = true)
                scrollToOffset(off)
            }
        })
    }

    private fun setupToc() {
        tocSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (suppressSpinner) { suppressSpinner = false; return }
                val off = toc.getOrNull(pos)?.second ?: return
                api("/goto?offset=$off", post = true)
                scrollToOffset(off)
                updateSlider(off)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun fillTocSpinner() {
        val titles = if (toc.isEmpty()) listOf("(no chapters found)") else toc.map { it.first }
        suppressSpinner = true
        tocSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, titles)
    }

    private fun scrollToOffset(off: Int) {
        val lay = textView.layout ?: return
        val line = lay.getLineForOffset(off.coerceIn(0, text.length))
        val y = lay.getLineTop(line) - dp(120)
        scroll.smoothScrollTo(0, y.coerceAtLeast(0))
    }

    private fun updateSlider(off: Int) {
        if (!seeking) {
            seek.progress = (off.toLong() * 1000 / bookLen).toInt()
            pctView.text = "${off.toLong() * 100 / bookLen}%"
        }
    }

    // ---------------- bookmarks ----------------

    private fun addBookmark() {
        val off = lastRemoteOffset.coerceAtLeast(0)
        val preview = text.substring(off.coerceIn(0, text.length),
            (off + 40).coerceAtMost(text.length)).replace('\n', ' ').trim()
        val label = "${off.toLong() * 100 / bookLen}% · $preview…"
        api("/bm/add?offset=$off&label=${URLEncoder.encode(label, "UTF-8")}", post = true) {
            bookmarks.add(off to label)
            toast("Bookmarked")
        }
    }

    private fun showBookmarks() {
        if (bookmarks.isEmpty()) { toast("No bookmarks yet"); return }
        val labels = bookmarks.map { it.second }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Bookmarks")
            .setItems(labels) { _, which ->
                val (off, label) = bookmarks[which]
                AlertDialog.Builder(this)
                    .setTitle(label)
                    .setPositiveButton("Go here") { _, _ ->
                        api("/goto?offset=$off", post = true)
                        scrollToOffset(off); updateSlider(off)
                    }
                    .setNegativeButton("Delete") { _, _ ->
                        api("/bm/del?offset=$off", post = true)
                        bookmarks.removeAll { it.first == off }
                    }
                    .show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ---------------- follow the glasses ----------------

    private fun pollLoop() {
        if (!polling) return
        api("/status") { txt ->
            if (txt != null) {
                try {
                    val st = JSONObject(txt).getJSONObject("state")
                    if (st.optString("screen") == "reader" && st.optString("path") == bookPath) {
                        val off = st.optInt("offset", -1)
                        if (off >= 0 && off != lastRemoteOffset) {
                            lastRemoteOffset = off
                            updateSlider(off)
                            if (follow.isChecked) scrollToOffset(off)
                        }
                    }
                } catch (_: Exception) { }
            }
            ui.postDelayed({ pollLoop() }, 2000)
        }
    }

    // ---------------- plumbing ----------------

    private fun api(pathAndQuery: String, post: Boolean = false, timeoutMs: Int = 6000, done: ((String?) -> Unit)? = null) {
        thread {
            val result = try {
                val c = URL("http://$ip:8765$pathAndQuery").openConnection() as HttpURLConnection
                c.connectTimeout = 2500
                c.readTimeout = timeoutMs
                c.requestMethod = if (post) "POST" else "GET"
                if (post) { c.doOutput = true; c.setFixedLengthStreamingMode(0) }
                c.inputStream.readBytes().toString(Charsets.UTF_8)
            } catch (_: Exception) { null }
            ui.post { done?.invoke(result) }
        }
    }

    private fun actionBtn(label: String, onClick: () -> Unit) =
        android.widget.Button(this).apply {
            text = label; isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
