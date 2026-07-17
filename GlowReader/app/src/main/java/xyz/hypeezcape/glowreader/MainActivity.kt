package xyz.hypeezcape.glowreader

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Library screen. Pure black background; the only pixels lit are text.
 * Fully automatic: rescans on a timer, no keypress required. UP/DOWN to
 * select, ENTER/SPACE to open. R still forces an immediate rescan.
 */
class MainActivity : Activity() {

    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout
    private var books: List<Book> = emptyList()
    private var selected = 0

    private val handler = Handler(Looper.getMainLooper())
    private val autoRescan = object : Runnable {
        override fun run() {
            rescan()
            handler.postDelayed(this, 2000)
        }
    }

    // Low-light must apply on EVERY screen — previously only the reader set
    // window brightness, so toggling from the phone while on the library
    // looked like it did nothing.
    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "lowLight") handler.post { applyLowLight() }
    }

    private fun applyLowLight() {
        val lp = window.attributes
        lp.screenBrightness =
            if (Prefs.lowLight(this)) 0.03f
            else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        scroll = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            isVerticalScrollBarEnabled = false
            // No focus, no focus highlight, no overscroll glow — any system
            // decoration lights up as green on the see-through display.
            isFocusable = false
            defaultFocusHighlightEnabled = false
            overScrollMode = android.view.View.OVER_SCROLL_NEVER
            addView(list)
        }
        list.defaultFocusHighlightEnabled = false
        setContentView(scroll)

        RemoteServer.start(this)
        maybeRequestPermission()
    }

    override fun onResume() {
        super.onResume()
        AppState.keySink = { code -> onKeyDown(code, null) }
        applyLowLight()
        Prefs.sp(this).registerOnSharedPreferenceChangeListener(prefListener)
        rescan()
        handler.postDelayed(autoRescan, 2000)
    }

    override fun onPause() {
        super.onPause()
        Prefs.sp(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        handler.removeCallbacks(autoRescan)
    }

    // ---------------- storage permission ----------------

    /** Whether we currently have broad read access to shared storage (Download/Documents/Books). */
    private fun hasStorageAccess(): Boolean = when {
        Build.VERSION.SDK_INT >= 30 -> Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT >= 23 -> checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        else -> true
    }

    private fun maybeRequestPermission() {
        if (hasStorageAccess()) return
        if (Build.VERSION.SDK_INT >= 30) {
            // "All files access" — needed because scoped storage blocks plain
            // File.listFiles() on shared folders like Download on API 30+.
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        rescan()
    }

    private fun rescan() {
        books = Books.scan(this)
        if (selected >= books.size) selected = (books.size - 1).coerceAtLeast(0)
        render()
    }

    private fun render() {
        list.removeAllViews()
        val ver = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) { "?" }
        list.addView(label("GLOW READER  v$ver", 20f, Color.WHITE, bold = true))
        // Always show how the phone app can reach us — on every screen.
        val ipLine = RemoteServer.localIp()?.let { "Phone app: $it" }
            ?: "Phone app: WiFi not connected (Rokid Settings → WiFi)"
        list.addView(label(ipLine, 11f, DIMMER))
        list.addView(label("", 6f, Color.BLACK))

        if (books.isEmpty()) {
            if (!hasStorageAccess()) {
                list.addView(label("Storage access not granted yet.", 16f, DIM))
                list.addView(label("", 8f, Color.BLACK))
                list.addView(label("A settings screen should have opened —", 13f, DIM))
                list.addView(label("turn on \"Allow access to manage all files\"", 13f, DIM))
                list.addView(label("for RokidReader, then come back here.", 13f, DIM))
                list.addView(label("", 8f, Color.BLACK))
                list.addView(label("This list refreshes automatically.", 12f, DIMMER))
                return
            }
            list.addView(label("No books found.", 16f, DIM))
            list.addView(label("", 8f, Color.BLACK))
            list.addView(label("Put .epub .pdf .txt .md files in:", 14f, DIM))
            for (d in Books.scanDirs(this)) list.addView(label(d.absolutePath, 12f, DIMMER))
            list.addView(label("", 8f, Color.BLACK))
            list.addView(label("This list refreshes automatically.", 12f, DIMMER))
            return
        }

        val last = Prefs.lastBook(this)
        books.forEachIndexed { i, b ->
            val sel = i == selected
            val marker = if (sel) "▶ " else "   "
            val resume = if (b.file.absolutePath == last) "  (last read)" else ""
            val tv = label("$marker${b.title}$resume", 17f, if (sel) Color.WHITE else DIM, bold = sel)
            tv.maxLines = 1
            // Mouse/touchpad support: single click opens the book directly.
            // Suppress the click ripple/highlight — it glows on the waveguide.
            val open = { _: android.view.View ->
                selected = i
                openSelected()
            }
            tv.background = null
            tv.defaultFocusHighlightEnabled = false
            tv.setOnClickListener(open)
            val sub = label("   ${b.ext.uppercase()} · ${b.file.length() / 1024} KB", 11f, if (sel) DIM else DIMMER)
            sub.background = null
            sub.defaultFocusHighlightEnabled = false
            sub.setOnClickListener(open)
            list.addView(tv)
            list.addView(sub)
        }
        list.addView(label("", 8f, Color.BLACK))
        list.addView(label("W/S or ↑↓ + ENTER · or click a title", 12f, DIMMER))

        // keep selection visible
        scroll.post {
            val child = list.getChildAt((3 + selected * 2).coerceAtMost(list.childCount - 1)) ?: return@post
            val top = child.top - scroll.height / 3
            scroll.smoothScrollTo(0, top.coerceAtLeast(0))
        }
    }

    private fun openSelected() {
        val b = books.getOrNull(selected) ?: return
        Prefs.setLastBook(this, b.file.absolutePath)
        startActivity(Intent(this, ReaderActivity::class.java).putExtra("path", b.file.absolutePath))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_A -> { if (selected > 0) { selected--; render() }; return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_D -> { if (selected < books.size - 1) { selected++; render() }; return true }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> { openSelected(); return true }
            KeyEvent.KEYCODE_R -> { rescan(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun label(text: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            gravity = Gravity.START
            setBackgroundColor(Color.BLACK)
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val DIM = 0xFF9A9A9A.toInt()
        private const val DIMMER = 0xFF5A5A5A.toInt()
    }
}
