package xyz.hypeezcape.glowreader

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import org.json.JSONArray
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import org.json.JSONObject
import java.io.File

/**
 * The reader. Everything is drawn on a pure black canvas — on the Rokid
 * waveguide black is transparent, so the user sees only floating words.
 *
 * Three modes, cycled with M: page → rsvp → scroll (teleprompter).
 *
 * Keys (arrows AND letters, since some BT keyboard apps have no arrows):
 *   PAGE   : A/D or W/S or ←→↑↓ or SPACE = turn page
 *   RSVP   : SPACE = play/pause · A/D = jump sentence · W/S = speed ±25wpm
 *   SCROLL : SPACE = play/pause · A/D = jump paragraph · W/S = speed ±25wpm
 *   all    : M = mode · +/- = font · T = text brightness · L = low-light
 *            U/N = area up/down · O/P = area shorter/taller · X = strict lines
 *            V = save preferred · R = restore preferred/defaults · 0 = defaults
 *            H = help · BACK/B = library
 *
 * All settings can also be changed live from the companion phone app.
 */
class ReaderActivity : Activity() {

    private lateinit var view: ReaderView
    private lateinit var path: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        path = intent.getStringExtra("path") ?: run { finish(); return }
        view = ReaderView(this, path, onExit = { finish() }, onLowLight = { setLowLight(it) })
        setContentView(view)
        setLowLight(Prefs.lowLight(this))
    }

    /** Low Light Leakage mode: drop panel brightness for this window. */
    fun setLowLight(on: Boolean) {
        val lp = window.attributes
        lp.screenBrightness =
            if (on) 0.03f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
    }

    override fun onResume() {
        super.onResume()
        AppState.keySink = { code -> view.handleKey(code) }
        AppState.reader = view
    }

    override fun onPause() {
        super.onPause()
        if (AppState.reader === view) AppState.reader = null
        view.savePosition()
        view.stopMotion()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean =
        view.handleKey(keyCode) || super.onKeyDown(keyCode, event)
}

class ReaderView(
    context: Context,
    private val path: String,
    private val onExit: () -> Unit,
    private val onLowLight: (Boolean) -> Unit
) : View(context) {

    private val handler = Handler(Looper.getMainLooper())

    // content
    private var text: String = ""
    private var spannable: SpannableString? = null
    private var loading = true
    private var error: String? = null

    // book structure + annotations (also served to the companion phone app)
    var toc: List<TocEntry> = emptyList(); private set
    val highlights: MutableList<IntArray> = ArrayList()
    val bookPath: String get() = path
    val bookText: String get() = text

    // modes: "page" | "rsvp" | "scroll"
    private var mode: String = Prefs.bookMode(context, path)

    // paint
    private val paint = TextPaint().apply { isAntiAlias = true }
    private val hudPaint = TextPaint().apply { isAntiAlias = true; color = 0xFF808080.toInt() }

    // settings mirrored from Prefs (refreshed by the prefs listener)
    private var areaTopPct = Prefs.areaTopPct(context)
    private var areaHeightPct = Prefs.areaHeightPct(context)
    private var strictLines = Prefs.strictLines(context)
    private var wpm = Prefs.wpm(context)

    // pagination state
    private var layout: StaticLayout? = null
    private var pageStartLines: IntArray = IntArray(0)
    private var page = 0

    // scroll (teleprompter) state
    private var scrollY = 0f
    private var scrolling = false

    // rsvp state
    private var words: List<String> = emptyList()
    private var wordOffsets: IntArray = IntArray(0)
    private var wordIndex = 0
    private var playing = false

    // sentence / paragraph step modes
    private var sentStarts = IntArray(0)
    private var sentEnds = IntArray(0)
    private var paraStarts = IntArray(0)
    private var paraEnds = IntArray(0)
    private var chunkIndex = 0
    private var cachedChunkLayout: StaticLayout? = null
    private var cachedChunkKey: String = ""

    // hud
    private var hudText: String? = null
    private var showHelp = false
    private val hideHud = Runnable { hudText = null; invalidate() }

    /** "Book closed": draw absolutely nothing so the glass is fully see-through.
     *  Toggled by double-tap (BACK), Z, or the phone app. Position is kept. */
    private var blanked = false

    fun toggleBlank() {
        blanked = !blanked
        if (blanked) {
            stopMotion()
            savePosition()
            handler.removeCallbacks(hideHud)
            hudText = null
        } else {
            hud("Book reopened")
        }
        invalidate()
    }

    fun isBlanked(): Boolean = blanked

    private val padding: Int get() = (14 * resources.displayMetrics.density).toInt()
    private val areaTop: Int get() = (height * areaTopPct / 100f).toInt() + padding
    private val areaHeight: Int get() = (height * areaHeightPct / 100f).toInt() - padding * 2

    /** Bottom strip reserved for the status line — book text NEVER draws here. */
    private val statusStripH: Int get() = (hudPaint.textSize * 2.2f).toInt()

    /** Text area height clamped so it can never overlap the status strip. */
    private val contentHeight: Int
        get() = areaHeight.coerceAtMost(height - statusStripH - areaTop).coerceAtLeast(50)

    // live settings sync (keys or companion phone app both write Prefs)
    private var pendingReload = false
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in Prefs.SETTING_KEYS) scheduleReload()
    }

    init {
        setBackgroundColor(Color.BLACK)
        // Never take focus: Android's default focus highlight renders as a
        // translucent wash that glows solid green on the waveguide display.
        isFocusable = false
        defaultFocusHighlightEnabled = false
        applyPaint()
        Thread {
            try {
                val book = Books.loadBook(context, File(path))
                post {
                    text = book.text
                    toc = book.toc
                    spannable = SpannableString(text)
                    loadHighlights()
                    loading = false
                    indexWords()
                    buildChunks()
                    rebuildLayout()
                    restorePosition()
                    when (mode) {
                        "rsvp" -> hud("RSVP · $wpm wpm · SPACE to start")
                        "scroll" -> hud("Auto-scroll · $wpm wpm · SPACE to start")
                    }
                    invalidate()
                }
            } catch (e: Exception) {
                post { loading = false; error = e.message ?: e.javaClass.simpleName; invalidate() }
            }
        }.start()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Prefs.sp(context).registerOnSharedPreferenceChangeListener(prefListener)
    }

    override fun onDetachedFromWindow() {
        Prefs.sp(context).unregisterOnSharedPreferenceChangeListener(prefListener)
        super.onDetachedFromWindow()
    }

    private fun scheduleReload() {
        if (pendingReload) return
        pendingReload = true
        post {
            pendingReload = false
            reloadFromPrefs()
        }
    }

    /** Re-read all settings and re-render, keeping the reading position. */
    private fun reloadFromPrefs() {
        areaTopPct = Prefs.areaTopPct(context)
        areaHeightPct = Prefs.areaHeightPct(context)
        strictLines = Prefs.strictLines(context)
        wpm = Prefs.wpm(context)
        applyPaint()
        onLowLight(Prefs.lowLight(context))
        if (!loading && error == null) {
            val off = currentCharOffset()
            rebuildLayout()
            goToOffset(off)
        }
        invalidate()
    }

    private fun applyPaint() {
        // Low-light also dims the text itself two extra levels — guarantees a
        // visible effect even if the OS ignores the window-brightness request.
        var level = Prefs.brightLevel(context)
        if (Prefs.lowLight(context)) level = (level + 2).coerceAtMost(4)
        paint.color = Prefs.BRIGHT_COLORS[level]
        paint.textSize = Prefs.fontSizeSp(context) * resources.displayMetrics.scaledDensity
        hudPaint.textSize = 13f * resources.displayMetrics.scaledDensity
    }

    // ---------------- highlights (marked on the phone, shown here) ----------------

    private fun loadHighlights() {
        highlights.clear()
        try {
            val arr = JSONArray(Prefs.highlightsJson(context, path))
            for (i in 0 until arr.length()) {
                val pair = arr.getJSONArray(i)
                highlights.add(intArrayOf(pair.getInt(0), pair.getInt(1)))
            }
        } catch (_: Exception) { }
        applyHighlightSpans()
    }

    private fun persistHighlights() {
        val arr = JSONArray()
        for (h in highlights) arr.put(JSONArray().put(h[0]).put(h[1]))
        Prefs.setHighlightsJson(context, path, arr.toString())
    }

    /** On the monochrome waveguide a "highlight" = brighter text + underline. */
    private fun applyHighlightSpans() {
        val s = spannable ?: return
        for (sp in s.getSpans(0, s.length, ForegroundColorSpan::class.java)) s.removeSpan(sp)
        for (sp in s.getSpans(0, s.length, UnderlineSpan::class.java)) s.removeSpan(sp)
        for (h in highlights) {
            val a = h[0].coerceIn(0, text.length)
            val b = h[1].coerceIn(0, text.length)
            if (b > a) {
                s.setSpan(ForegroundColorSpan(0xFFFFFFFF.toInt()), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                s.setSpan(UnderlineSpan(), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        cachedChunkKey = ""
        invalidate()
    }

    fun addHighlight(start: Int, end: Int) {
        if (end <= start) return
        highlights.add(intArrayOf(start, end))
        persistHighlights()
        applyHighlightSpans()
        hud("Highlight added")
    }

    fun removeHighlightsOverlapping(offset: Int, endOffset: Int) {
        highlights.removeAll { it[0] < endOffset && it[1] > offset }
        persistHighlights()
        applyHighlightSpans()
        hud("Highlight removed")
    }

    /** Jump requested from the companion phone app (TOC / slider / bookmark / tap). */
    fun gotoFromRemote(offset: Int) {
        if (loading || error != null) return
        stopMotion()
        goToOffset(offset)
        savePosition()
        hud("Moved from phone")
    }

    fun currentOffset(): Int = currentCharOffset()

    /** Status snapshot for the companion app (called from the server thread). */
    fun fillStatus(state: JSONObject) {
        state.put("screen", "reader")
        state.put("book", File(path).nameWithoutExtension)
        state.put("mode", mode)
        state.put("playing", playing || scrolling)
        val l = layout
        val pct = when {
            mode == "rsvp" && words.isNotEmpty() -> wordIndex * 100 / words.size
            mode == "scroll" && l != null && l.height > 0 -> (scrollY * 100 / l.height).toInt()
            mode == "sent" && sentStarts.isNotEmpty() -> chunkIndex * 100 / sentStarts.size
            mode == "para" && paraStarts.isNotEmpty() -> chunkIndex * 100 / paraStarts.size
            pageStartLines.isNotEmpty() -> page * 100 / pageStartLines.size
            else -> 0
        }
        state.put("percent", pct)
        state.put("blanked", blanked)
        state.put("offset", currentCharOffset())
        state.put("length", text.length)
        state.put("path", path)
    }

    // ---------------- layout / pagination ----------------

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (!loading && error == null) {
            val off = currentCharOffset()
            rebuildLayout()
            goToOffset(off)
        }
    }

    private fun rebuildLayout() {
        if (text.isEmpty() || width == 0 || height == 0) return
        val innerW = width - padding * 2
        val cs: CharSequence = spannable ?: text
        val l = StaticLayout.Builder.obtain(cs, 0, cs.length, paint, innerW)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.2f)
            .setIncludePad(false)
            .build()
        layout = l
        val innerH = contentHeight
        val starts = ArrayList<Int>()
        var line = 0
        while (line < l.lineCount) {
            starts.add(line)
            val top = l.getLineTop(line)
            var end = line
            while (end + 1 < l.lineCount && l.getLineBottom(end + 1) - top <= innerH) end++
            line = end + 1
        }
        pageStartLines = starts.toIntArray()
        if (page >= pageStartLines.size) page = pageStartLines.size - 1
    }

    private fun currentCharOffset(): Int {
        val l = layout ?: return 0
        return when (mode) {
            "rsvp" -> wordOffsets.getOrElse(wordIndex) { 0 }
            "scroll" -> l.getLineStart(l.getLineForVertical(scrollY.toInt()))
            "sent" -> sentStarts.getOrElse(chunkIndex) { 0 }
            "para" -> paraStarts.getOrElse(chunkIndex) { 0 }
            else -> {
                if (pageStartLines.isEmpty()) 0
                else l.getLineStart(pageStartLines[page.coerceIn(0, pageStartLines.size - 1)])
            }
        }
    }

    private fun goToOffset(off: Int) {
        val l = layout ?: return
        if (pageStartLines.isEmpty()) return
        val line = l.getLineForOffset(off.coerceIn(0, text.length))
        var p = 0
        for (i in pageStartLines.indices) if (pageStartLines[i] <= line) p = i else break
        page = p
        scrollY = l.getLineTop(line).toFloat()
        wordIndex = nearestWord(off)
        chunkIndex = nearestIn(if (mode == "para") paraStarts else sentStarts, off)
        invalidate()
    }

    // ---------------- rsvp ----------------

    private fun indexWords() {
        val ws = ArrayList<String>(text.length / 6)
        val offs = ArrayList<Int>(text.length / 6)
        for (m in Regex("\\S+").findAll(text)) {
            ws.add(m.value); offs.add(m.range.first)
        }
        words = ws
        wordOffsets = offs.toIntArray()
    }

    private fun nearestWord(off: Int): Int {
        if (wordOffsets.isEmpty()) return 0
        var lo = 0; var hi = wordOffsets.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (wordOffsets[mid] <= off) lo = mid else hi = mid - 1
        }
        return lo
    }

    private val rsvpTick = object : Runnable {
        override fun run() {
            if (!playing) return
            if (wordIndex < words.size - 1) {
                wordIndex++
                invalidate()
                val w = words[wordIndex]
                val base = 60000L / wpm
                val delay = if (w.lastOrNull() in listOf('.', '!', '?', ':', ';')) base * 2
                else if (w.length > 9) base * 3 / 2 else base
                handler.postDelayed(this, delay)
            } else {
                playing = false
                hud("End of book")
            }
        }
    }

    private fun toggleRsvp() {
        playing = !playing
        handler.removeCallbacks(rsvpTick)
        if (playing) handler.postDelayed(rsvpTick, 60000L / wpm)
        invalidate()
    }

    private fun jumpSentence(forward: Boolean) {
        if (words.isEmpty()) return
        var i = wordIndex
        if (forward) {
            while (i < words.size - 1 && words[i].lastOrNull() !in listOf('.', '!', '?')) i++
            wordIndex = (i + 1).coerceAtMost(words.size - 1)
        } else {
            i = (i - 2).coerceAtLeast(0)
            while (i > 0 && words[i].lastOrNull() !in listOf('.', '!', '?')) i--
            wordIndex = if (i == 0) 0 else (i + 1).coerceAtMost(words.size - 1)
        }
        invalidate()
    }

    // ---------------- auto-scroll (teleprompter) ----------------

    private fun scrollPxPerFrame(): Float {
        val l = layout ?: return 0f
        if (l.lineCount == 0 || words.isEmpty()) return 0f
        val wordsPerLine = words.size.toFloat() / l.lineCount
        val avgLineH = l.height.toFloat() / l.lineCount
        val linesPerSec = (wpm / wordsPerLine) / 60f
        return linesPerSec * avgLineH / 30f   // 30 fps ticks
    }

    private val scrollTick = object : Runnable {
        override fun run() {
            if (!scrolling) return
            val l = layout ?: return
            val maxY = (l.height - contentHeight).coerceAtLeast(0).toFloat()
            scrollY = (scrollY + scrollPxPerFrame()).coerceAtMost(maxY)
            invalidate()
            if (scrollY >= maxY) {
                scrolling = false
                hud("End of book")
            } else {
                handler.postDelayed(this, 33)
            }
        }
    }

    private fun toggleScroll() {
        scrolling = !scrolling
        handler.removeCallbacks(scrollTick)
        if (scrolling) handler.postDelayed(scrollTick, 33)
        invalidate()
    }

    private fun jumpParagraph(forward: Boolean) {
        val l = layout ?: return
        if (paraStarts.isEmpty()) return
        val i = nearestIn(paraStarts, currentCharOffset()) + (if (forward) 1 else -1)
        val idx = paraStarts[i.coerceIn(0, paraStarts.size - 1)]
        scrollY = l.getLineTop(l.getLineForOffset(idx.coerceIn(0, text.length))).toFloat()
        invalidate()
    }

    // ---------------- sentence / paragraph chunks ----------------

    /**
     * Split the text into sentences and paragraphs (with offsets). Books whose
     * conversion produced no blank-line breaks get sensible fallbacks, so
     * chunk navigation never degenerates into start/end-of-book jumps.
     */
    private fun buildChunks() {
        // sentences: end at .!? (plus closing quotes) followed by whitespace
        val ss = ArrayList<Int>(); val se = ArrayList<Int>()
        fun add(list1: ArrayList<Int>, list2: ArrayList<Int>, s0: Int, e0: Int) {
            var s = s0; var e = e0
            while (s < e && text[s].isWhitespace()) s++
            while (e > s && text[e - 1].isWhitespace()) e--
            if (e > s) { list1.add(s); list2.add(e) }
        }
        var st = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '.' || c == '!' || c == '?') {
                var j = i + 1
                while (j < text.length && text[j] in "\")'”’»") j++
                if (j >= text.length || text[j].isWhitespace()) {
                    add(ss, se, st, j); st = j; i = j; continue
                }
            } else if (c == '\n') {
                add(ss, se, st, i); st = i + 1
            }
            i++
        }
        add(ss, se, st, text.length)
        sentStarts = ss.toIntArray(); sentEnds = se.toIntArray()

        // paragraphs: blank line → single newline → every ~60 words
        var ps = ArrayList<Int>(); var pe = ArrayList<Int>()
        for (sep in listOf("\n\n", "\n")) {
            ps = ArrayList(); pe = ArrayList()
            var s0 = 0
            while (s0 < text.length) {
                val n = text.indexOf(sep, s0)
                if (n == -1) { add(ps, pe, s0, text.length); break }
                add(ps, pe, s0, n)
                s0 = n + sep.length
            }
            if (ps.size > 1) break
        }
        if (ps.size <= 1 && wordOffsets.size > 60) {
            ps = ArrayList(); pe = ArrayList()
            var w = 0
            while (w < wordOffsets.size) {
                val endW = (w + 60).coerceAtMost(wordOffsets.size - 1)
                val endOff = if (endW == wordOffsets.size - 1) text.length
                else wordOffsets[endW + 1]
                add(ps, pe, wordOffsets[w], endOff)
                w = endW + 1
            }
        }
        paraStarts = ps.toIntArray(); paraEnds = pe.toIntArray()
    }

    private fun nearestIn(starts: IntArray, off: Int): Int {
        if (starts.isEmpty()) return 0
        var lo = 0; var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (starts[mid] <= off) lo = mid else hi = mid - 1
        }
        return lo
    }

    private fun chunkArrays(): Pair<IntArray, IntArray> =
        if (mode == "para") paraStarts to paraEnds else sentStarts to sentEnds

    private fun moveChunk(delta: Int) {
        val (starts, _) = chunkArrays()
        if (starts.isEmpty()) return
        chunkIndex = (chunkIndex + delta).coerceIn(0, starts.size - 1)
        invalidate()
    }

    private val chunkTick = object : Runnable {
        override fun run() {
            if (!playing) return
            val (starts, ends) = chunkArrays()
            if (chunkIndex < starts.size - 1) {
                chunkIndex++
                invalidate()
                handler.postDelayed(this, chunkDelayMs(starts, ends, chunkIndex))
            } else {
                playing = false
                hud("End of book")
            }
        }
    }

    private fun chunkDelayMs(starts: IntArray, ends: IntArray, idx: Int): Long {
        val approxWords = ((ends[idx] - starts[idx]) / 6).coerceAtLeast(2)
        return (approxWords * 60000L / wpm).coerceIn(1200L, 30000L)
    }

    private fun toggleChunkPlay() {
        playing = !playing
        handler.removeCallbacks(chunkTick)
        if (playing) {
            val (starts, ends) = chunkArrays()
            if (starts.isNotEmpty()) handler.postDelayed(chunkTick, chunkDelayMs(starts, ends, chunkIndex.coerceIn(0, starts.size - 1)))
        }
        invalidate()
    }

    /** One screen-step forward/back, whatever the mode — the phone's ‹ › page buttons. */
    fun screenStep(forward: Boolean) {
        when (mode) {
            "page" -> pageKey(if (forward) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
            "rsvp" -> jumpSentence(forward)
            "sent", "para" -> moveChunk(if (forward) 1 else -1)
            "scroll" -> {
                val l = layout ?: return
                val maxY = (l.height - contentHeight).coerceAtLeast(0).toFloat()
                if (forward) {
                    var lastFull = l.getLineForVertical((scrollY + contentHeight).toInt())
                    if (l.getLineBottom(lastFull) > scrollY + contentHeight) lastFull--
                    val nl = (lastFull + 1).coerceAtMost(l.lineCount - 1)
                    scrollY = l.getLineTop(nl).toFloat().coerceAtMost(maxY)
                } else {
                    val target = (scrollY - contentHeight).coerceAtLeast(0f)
                    var fl = l.getLineForVertical(target.toInt())
                    if (l.getLineTop(fl) < target) fl = (fl + 1).coerceAtMost(l.lineCount - 1)
                    scrollY = l.getLineTop(fl).toFloat()
                }
                val pct = if (l.height > 0) (scrollY * 100 / l.height).toInt() else 0
                hud("$pct%")
                invalidate()
            }
        }
    }

    fun stopMotion() {
        playing = false
        scrolling = false
        handler.removeCallbacks(rsvpTick)
        handler.removeCallbacks(scrollTick)
        handler.removeCallbacks(chunkTick)
    }

    // ---------------- mouse / touchpad ----------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (blanked) return true   // book closed: ignore all touches
        if (event.action == MotionEvent.ACTION_UP && !loading && error == null) {
            performClick()
            val x = event.x
            when (mode) {
                "rsvp" -> when {
                    x < width / 4f -> jumpSentence(false)
                    x > width * 3 / 4f -> jumpSentence(true)
                    else -> { toggleRsvp(); hud(if (playing) "▶ $wpm wpm" else "⏸ paused") }
                }
                "scroll" -> when {
                    x < width / 4f -> jumpParagraph(false)
                    x > width * 3 / 4f -> jumpParagraph(true)
                    else -> { toggleScroll(); hud(if (scrolling) "▶ $wpm wpm" else "⏸ paused") }
                }
                "sent", "para" -> when {
                    x < width / 4f -> moveChunk(-1)
                    x > width * 3 / 4f -> moveChunk(1)
                    else -> { toggleChunkPlay(); hud(if (playing) "▶ auto" else "⏸ paused") }
                }
                else -> {
                    if (x < width / 3f) pageKey(KeyEvent.KEYCODE_DPAD_LEFT)
                    else pageKey(KeyEvent.KEYCODE_DPAD_RIGHT)
                }
            }
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // ---------------- keys ----------------

    fun handleKey(keyCode: Int): Boolean {
        if (loading || error != null) {
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_B) { onExit(); return true }
            return false
        }
        // Book closed: swallow everything except the deliberate reopen/exit keys,
        // so an accidental tap or swipe can't light the display mid-conversation.
        if (blanked) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_Z -> toggleBlank()
                KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_ESCAPE -> { savePosition(); onExit() }
            }
            return true
        }
        when (keyCode) {
            // Double-tap on the temple arrives as BACK: "close the book" —
            // stay in the app, show nothing. Double-tap again to reopen.
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_Z -> { toggleBlank(); return true }
            KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_ESCAPE -> {
                savePosition(); onExit(); return true
            }
            KeyEvent.KEYCODE_M -> { switchMode(); return true }
            KeyEvent.KEYCODE_H -> { showHelp = !showHelp; invalidate(); return true }
            KeyEvent.KEYCODE_T -> {
                Prefs.setBrightLevel(context, (Prefs.brightLevel(context) + 1) % 5)
                hud("Brightness ${5 - Prefs.brightLevel(context)}/5"); return true
            }
            KeyEvent.KEYCODE_L -> {
                val on = !Prefs.lowLight(context)
                Prefs.setLowLight(context, on)
                hud(if (on) "Low-light ON" else "Low-light OFF"); return true
            }
            KeyEvent.KEYCODE_X -> {
                val on = !Prefs.strictLines(context)
                Prefs.setStrictLines(context, on)
                hud(if (on) "Full lines only: ON" else "Full lines only: OFF"); return true
            }
            KeyEvent.KEYCODE_V -> {
                Prefs.savePreset(context)
                hud("Saved as preferred settings"); return true
            }
            KeyEvent.KEYCODE_R -> {
                val had = Prefs.restorePreset(context)
                hud(if (had) "Preferred settings restored" else "Default settings restored"); return true
            }
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> {
                Prefs.factoryDefaults(context)
                hud("Default settings restored"); return true
            }
            KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_NUMPAD_ADD -> {
                Prefs.setFontSizeSp(context, Prefs.fontSizeSp(context) + 2)
                hud("Font ${Prefs.fontSizeSp(context).toInt()}"); return true
            }
            KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> {
                Prefs.setFontSizeSp(context, Prefs.fontSizeSp(context) - 2)
                hud("Font ${Prefs.fontSizeSp(context).toInt()}"); return true
            }
            // display area: U/N move up/down, O/P shrink/grow height
            KeyEvent.KEYCODE_U -> { adjustArea(topDelta = -5, heightDelta = 0); return true }
            KeyEvent.KEYCODE_N -> { adjustArea(topDelta = +5, heightDelta = 0); return true }
            KeyEvent.KEYCODE_O -> { adjustArea(topDelta = 0, heightDelta = -10); return true }
            KeyEvent.KEYCODE_P -> { adjustArea(topDelta = 0, heightDelta = +10); return true }
        }
        return when (mode) {
            "rsvp" -> rsvpKey(keyCode)
            "scroll" -> scrollKey(keyCode)
            "sent", "para" -> chunkKey(keyCode)
            else -> pageKey(keyCode)
        }
    }

    private fun chunkKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
            toggleChunkPlay(); hud(if (playing) "▶ auto" else "⏸ paused"); true
        }
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W -> { changeWpm(+25); true }
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S -> { changeWpm(-25); true }
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_D -> { moveChunk(1); true }
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_A -> { moveChunk(-1); true }
        else -> false
    }

    private fun adjustArea(topDelta: Int, heightDelta: Int) {
        val top = (Prefs.areaTopPct(context) + topDelta).coerceIn(0, 60)
        val h = (Prefs.areaHeightPct(context) + heightDelta).coerceIn(30, 100 - top)
        Prefs.setAreaTopPct(context, top)
        Prefs.setAreaHeightPct(context, h)
        hud("Area top $top% · height $h%")
    }

    private fun pageKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_SPACE,
        KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_S -> {
            if (page < pageStartLines.size - 1) { page++; savePosition(); invalidate() }
            hud("${page + 1} / ${pageStartLines.size}"); true
        }
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP,
        KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_W -> {
            if (page > 0) { page--; savePosition(); invalidate() }
            hud("${page + 1} / ${pageStartLines.size}"); true
        }
        else -> false
    }

    private fun rsvpKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
            toggleRsvp(); hud(if (playing) "▶ $wpm wpm" else "⏸ paused"); true
        }
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W -> { changeWpm(+25); true }
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S -> { changeWpm(-25); true }
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D -> { jumpSentence(true); true }
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A -> { jumpSentence(false); true }
        else -> false
    }

    private fun scrollKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
            toggleScroll(); hud(if (scrolling) "▶ $wpm wpm" else "⏸ paused"); true
        }
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W -> { changeWpm(+25); true }
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S -> { changeWpm(-25); true }
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_D -> { jumpParagraph(true); true }
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_A -> { jumpParagraph(false); true }
        else -> false
    }

    private fun changeWpm(delta: Int) {
        Prefs.setWpm(context, wpm + delta)
        wpm = Prefs.wpm(context)
        hud("$wpm wpm")
    }

    private fun switchMode() {
        val off = currentCharOffset()
        stopMotion()
        mode = when (mode) {
            "page" -> "rsvp"
            "rsvp" -> "scroll"
            "scroll" -> "sent"
            "sent" -> "para"
            else -> "page"
        }
        Prefs.setBookMode(context, path, mode)
        cachedChunkKey = ""
        goToOffset(off)
        hud(
            when (mode) {
                "rsvp" -> "RSVP (word by word) · SPACE to start"
                "scroll" -> "Auto-scroll · SPACE to start"
                "sent" -> "Sentence by sentence · SPACE auto-plays"
                "para" -> "Paragraph by paragraph · SPACE auto-plays"
                else -> "Page mode"
            }
        )
    }

    fun savePosition() {
        if (!loading && error == null) Prefs.setBookPos(context, path, currentCharOffset())
    }

    private fun restorePosition() {
        goToOffset(Prefs.bookPos(context, path))
    }

    private fun hud(msg: String) {
        hudText = msg
        handler.removeCallbacks(hideHud)
        handler.postDelayed(hideHud, 2000)
        invalidate()
    }

    // ---------------- drawing ----------------

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        if (blanked) return   // book closed: pure black = invisible glass
        if (loading) { drawCentered(canvas, "Loading…", hudPaint); return }
        error?.let { drawCentered(canvas, "Error: $it  (B = back)", hudPaint); return }
        if (showHelp) { drawHelp(canvas); return }
        when (mode) {
            "rsvp" -> drawRsvp(canvas)
            "scroll" -> drawScroll(canvas)
            "sent", "para" -> drawChunk(canvas)
            else -> drawPage(canvas)
        }
        drawBottomLine(canvas)
    }

    /**
     * Persistent status line in its own reserved strip at the very bottom —
     * always shows wpm in every mode, never overlapped by book text.
     * Transient messages (settings changes etc.) temporarily replace it.
     */
    private fun drawBottomLine(canvas: Canvas) {
        val msg = hudText ?: modeStatus()
        val y = height - hudPaint.textSize * 0.8f
        canvas.drawText(msg, (width - hudPaint.measureText(msg)) / 2f, y, hudPaint)
    }

    private fun modeStatus(): String {
        val l = layout
        return when (mode) {
            "page" -> {
                val total = pageStartLines.size.coerceAtLeast(1)
                "${page + 1}/$total · $wpm wpm"
            }
            "rsvp" -> {
                val pct = if (words.isEmpty()) 0 else wordIndex * 100 / words.size
                "$pct% · $wpm wpm · ${if (playing) "▶" else "⏸"}"
            }
            "scroll" -> {
                val pct = if (l != null && l.height > 0) (scrollY * 100 / l.height).toInt() else 0
                "$pct% · $wpm wpm · ${if (scrolling) "▶" else "⏸"}"
            }
            else -> {
                val (starts, _) = chunkArrays()
                val total = starts.size.coerceAtLeast(1)
                val what = if (mode == "para") "¶" else "S"
                "$what ${chunkIndex + 1}/$total · $wpm wpm · ${if (playing) "▶" else "⏸"}"
            }
        }
    }

    private fun drawPage(canvas: Canvas) {
        val l = layout ?: return
        if (pageStartLines.isEmpty()) return
        val first = pageStartLines[page]
        val innerH = contentHeight
        canvas.save()
        canvas.clipRect(0, areaTop, width, areaTop + innerH)
        canvas.translate(padding.toFloat(), (areaTop - l.getLineTop(first)).toFloat())
        l.draw(canvas)
        canvas.restore()
    }

    private fun drawScroll(canvas: Canvas) {
        val l = layout ?: return
        var clipTop = areaTop
        var clipBottom = areaTop + contentHeight
        if (strictLines && l.lineCount > 0) {
            // Only lines that fit ENTIRELY inside the area are shown — a line
            // partly outside the window is hidden completely (no half words).
            var firstFull = l.getLineForVertical(scrollY.toInt())
            if (l.getLineTop(firstFull) < scrollY) firstFull++
            var lastFull = l.getLineForVertical((scrollY + contentHeight).toInt())
            if (l.getLineBottom(lastFull) > scrollY + contentHeight) lastFull--
            if (firstFull > lastFull || firstFull >= l.lineCount) return
            clipTop = (areaTop + (l.getLineTop(firstFull) - scrollY)).toInt()
            clipBottom = (areaTop + (l.getLineBottom(lastFull) - scrollY)).toInt()
        }
        canvas.save()
        canvas.clipRect(0, clipTop, width, clipBottom.coerceAtMost(areaTop + contentHeight))
        canvas.translate(padding.toFloat(), areaTop - scrollY)
        l.draw(canvas)
        canvas.restore()
    }

    /** Sentence/paragraph mode: current chunk wrapped and centered, auto-shrunk to fit. */
    private fun drawChunk(canvas: Canvas) {
        val (starts, ends) = chunkArrays()
        if (starts.isEmpty()) return
        val idx = chunkIndex.coerceIn(0, starts.size - 1)
        val key = "$mode:$idx:${paint.textSize}:${paint.color}:$width:$areaHeightPct"
        if (key != cachedChunkKey) {
            // subSequence keeps highlight spans; chunks rarely contain newlines
            val chunkText: CharSequence = spannable?.subSequence(starts[idx], ends[idx])
                ?: text.substring(starts[idx], ends[idx]).replace('\n', ' ')
            val p = TextPaint(paint)
            var l: StaticLayout
            while (true) {
                l = StaticLayout.Builder.obtain(chunkText, 0, chunkText.length, p, width - padding * 2)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setLineSpacing(0f, 1.2f)
                    .setIncludePad(false)
                    .build()
                if (l.height <= contentHeight || p.textSize <= 14f) break
                p.textSize = p.textSize * 0.9f
            }
            cachedChunkLayout = l
            cachedChunkKey = key
        }
        val l = cachedChunkLayout ?: return
        val top = (areaTop + (contentHeight - l.height) / 2f).coerceAtLeast(areaTop.toFloat())
        canvas.save()
        canvas.clipRect(0, areaTop, width, areaTop + contentHeight)
        canvas.translate(padding.toFloat(), top)
        l.draw(canvas)
        canvas.restore()
    }

    private fun drawRsvp(canvas: Canvas) {
        val word = words.getOrNull(wordIndex) ?: return
        val big = TextPaint(paint).apply { textSize = paint.textSize * 1.5f }
        var w = big.measureText(word)
        while (w > width - padding * 2 && big.textSize > 12f) {
            big.textSize = big.textSize * 0.92f
            w = big.measureText(word)
        }
        val x = (width - w) / 2f
        val centerY = areaTop + contentHeight / 2f
        val y = centerY - (big.descent() + big.ascent()) / 2f
        canvas.drawText(word, x, y, big)
    }

    private fun drawHelp(canvas: Canvas) {
        val lines = listOf(
            "KEYS  (arrows or letters)",
            "",
            "M      mode: page·RSVP·scroll·sent·para",
            "SPACE  page turn · play/pause",
            "A D    turn · sentence · paragraph",
            "W S    turn · speed ±25 wpm",
            "+ -    font size",
            "T      text brightness",
            "L      low-light leakage mode",
            "X      full-lines-only on/off",
            "U N    move display area up/down",
            "O P    display area shorter/taller",
            "V      save preferred settings",
            "R      restore preferred/defaults",
            "2-tap/Z  close book (invisible) · again = reopen",
            "B      back to library · H close help"
        )
        var y = padding + hudPaint.textSize * 2
        for (s in lines) {
            canvas.drawText(s, padding.toFloat(), y, hudPaint)
            y += hudPaint.textSize * 1.5f
        }
    }

    private fun drawCentered(canvas: Canvas, msg: String, p: TextPaint) {
        canvas.drawText(msg, (width - p.measureText(msg)) / 2f, height / 2f, p)
    }
}
