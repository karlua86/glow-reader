package xyz.hypeezcape.glowreader

import android.content.Context
import android.content.SharedPreferences

/**
 * Simple persistence: global reading settings + per-book position/mode.
 * Books are keyed by absolute file path.
 */
object Prefs {
    fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("rokidreader", Context.MODE_PRIVATE)

    /** Global setting keys that should trigger a live reader refresh. */
    val SETTING_KEYS = setOf(
        "fontSizeSp", "wpm", "brightLevel", "lowLight",
        "areaTopPct", "areaHeightPct", "strictLines"
    )

    // -- global settings --

    fun fontSizeSp(ctx: Context): Float = sp(ctx).getFloat("fontSizeSp", 26f)
    fun setFontSizeSp(ctx: Context, v: Float) = sp(ctx).edit().putFloat("fontSizeSp", v.coerceIn(14f, 60f)).apply()

    fun wpm(ctx: Context): Int = sp(ctx).getInt("wpm", 300)
    fun setWpm(ctx: Context, v: Int) = sp(ctx).edit().putInt("wpm", v.coerceIn(60, 1200)).apply()

    /** 0 = brightest text .. 4 = dimmest (for night reading) */
    fun brightLevel(ctx: Context): Int = sp(ctx).getInt("brightLevel", 1)
    fun setBrightLevel(ctx: Context, v: Int) = sp(ctx).edit().putInt("brightLevel", v.coerceIn(0, 4)).apply()

    /** Low Light Leakage mode: panel brightness dropped to minimum. */
    fun lowLight(ctx: Context): Boolean = sp(ctx).getBoolean("lowLight", false)
    fun setLowLight(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("lowLight", v).apply()

    /** Display area: top offset as % of screen height (0..60). */
    fun areaTopPct(ctx: Context): Int = sp(ctx).getInt("areaTopPct", 0)
    fun setAreaTopPct(ctx: Context, v: Int) = sp(ctx).edit().putInt("areaTopPct", v.coerceIn(0, 60)).apply()

    /** Display area: height as % of screen height (30..100). */
    fun areaHeightPct(ctx: Context): Int = sp(ctx).getInt("areaHeightPct", 100)
    fun setAreaHeightPct(ctx: Context, v: Int) = sp(ctx).edit().putInt("areaHeightPct", v.coerceIn(30, 100)).apply()

    /** Strict rendering: only draw lines that fit ENTIRELY inside the display
     *  area — no half-cut words at the top/bottom edge during auto-scroll. */
    fun strictLines(ctx: Context): Boolean = sp(ctx).getBoolean("strictLines", true)
    fun setStrictLines(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("strictLines", v).apply()

    fun lastBook(ctx: Context): String? = sp(ctx).getString("lastBook", null)
    fun setLastBook(ctx: Context, path: String) = sp(ctx).edit().putString("lastBook", path).apply()

    // -- preset / defaults --

    fun factoryDefaults(ctx: Context) = sp(ctx).edit()
        .putFloat("fontSizeSp", 26f).putInt("wpm", 300).putInt("brightLevel", 1)
        .putBoolean("lowLight", false).putInt("areaTopPct", 0).putInt("areaHeightPct", 100)
        .putBoolean("strictLines", true).apply()

    fun savePreset(ctx: Context) {
        val s = sp(ctx)
        s.edit()
            .putFloat("preset_fontSizeSp", fontSizeSp(ctx)).putInt("preset_wpm", wpm(ctx))
            .putInt("preset_brightLevel", brightLevel(ctx)).putBoolean("preset_lowLight", lowLight(ctx))
            .putInt("preset_areaTopPct", areaTopPct(ctx)).putInt("preset_areaHeightPct", areaHeightPct(ctx))
            .putBoolean("preset_strictLines", strictLines(ctx)).putBoolean("preset_exists", true)
            .apply()
    }

    /** Restore preferred settings if saved, else factory defaults. Returns true if a preset existed. */
    fun restorePreset(ctx: Context): Boolean {
        val s = sp(ctx)
        if (!s.getBoolean("preset_exists", false)) { factoryDefaults(ctx); return false }
        s.edit()
            .putFloat("fontSizeSp", s.getFloat("preset_fontSizeSp", 26f))
            .putInt("wpm", s.getInt("preset_wpm", 300))
            .putInt("brightLevel", s.getInt("preset_brightLevel", 1))
            .putBoolean("lowLight", s.getBoolean("preset_lowLight", false))
            .putInt("areaTopPct", s.getInt("preset_areaTopPct", 0))
            .putInt("areaHeightPct", s.getInt("preset_areaHeightPct", 100))
            .putBoolean("strictLines", s.getBoolean("preset_strictLines", true))
            .apply()
        return true
    }

    // -- per-book --

    fun bookPos(ctx: Context, path: String): Int = sp(ctx).getInt("pos:$path", 0)
    fun setBookPos(ctx: Context, path: String, charOffset: Int) =
        sp(ctx).edit().putInt("pos:$path", charOffset).apply()

    /** "page" or "rsvp" */
    fun bookMode(ctx: Context, path: String): String = sp(ctx).getString("mode:$path", "page") ?: "page"
    fun setBookMode(ctx: Context, path: String, mode: String) =
        sp(ctx).edit().putString("mode:$path", mode).apply()

    /** Highlights per book: JSON array of [start, end] char-offset pairs. */
    fun highlightsJson(ctx: Context, path: String): String =
        sp(ctx).getString("hl:$path", "[]") ?: "[]"
    fun setHighlightsJson(ctx: Context, path: String, json: String) =
        sp(ctx).edit().putString("hl:$path", json).apply()

    /** Bookmarks per book: JSON array of {"o": offset, "label": text}. */
    fun bookmarksJson(ctx: Context, path: String): String =
        sp(ctx).getString("bm:$path", "[]") ?: "[]"
    fun setBookmarksJson(ctx: Context, path: String, json: String) =
        sp(ctx).edit().putString("bm:$path", json).apply()

    /** Text brightness levels: pure white = maximum glow on the waveguide, down to dim gray. */
    val BRIGHT_COLORS = intArrayOf(0xFFFFFFFF.toInt(), 0xFFC8C8C8.toInt(), 0xFF969696.toInt(), 0xFF6E6E6E.toInt(), 0xFF4B4B4B.toInt())
}
