package xyz.hypeezcape.glowreader

import android.content.Context
import android.os.Environment
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile

data class Book(val file: File) {
    val title: String get() = file.nameWithoutExtension
    val ext: String get() = file.extension.lowercase()
}

data class TocEntry(val title: String, val offset: Int)

/** A loaded book: full plain text plus table-of-contents entries (may be empty). */
data class BookContent(val text: String, val toc: List<TocEntry>)

object Books {

    private val EXTS = setOf("txt", "md", "epub", "pdf")

    /** Folders scanned for books, in priority order. */
    fun scanDirs(ctx: Context): List<File> = listOfNotNull(
        ctx.getExternalFilesDir("Books"),
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
        File(Environment.getExternalStorageDirectory(), "Books")
    )

    fun scan(ctx: Context): List<Book> {
        val seen = HashSet<String>()
        val out = ArrayList<Book>()
        for (dir in scanDirs(ctx)) {
            val files = try { dir.listFiles() } catch (_: Exception) { null } ?: continue
            for (f in files) {
                if (f.isFile && f.extension.lowercase() in EXTS && f.length() > 0 && seen.add(f.name + f.length())) {
                    out.add(Book(f))
                }
            }
        }
        return out.sortedByDescending { it.file.lastModified() }
    }

    /** Load a book's full text + TOC, reflowed to plain paragraphs. Heavy — call off the UI thread. */
    fun loadBook(ctx: Context, file: File): BookContent {
        return when (file.extension.lowercase()) {
            "txt" -> {
                val text = normalize(file.readBytes().toString(Charsets.UTF_8))
                BookContent(text, detectChapters(text))
            }
            "md" -> {
                val text = normalize(stripMarkdown(file.readBytes().toString(Charsets.UTF_8)))
                BookContent(text, detectChapters(text))
            }
            "epub" -> loadEpub(file)
            "pdf" -> {
                val text = normalize(loadPdf(ctx, file))
                BookContent(text, detectChapters(text))
            }
            else -> throw IllegalArgumentException("Unsupported: ${file.name}")
        }
    }

    /** Back-compat: text only. */
    fun loadText(ctx: Context, file: File): String = loadBook(ctx, file).text

    // ---------- chapter detection for flat text (TXT/MD/PDF) ----------

    private fun detectChapters(text: String): List<TocEntry> {
        val toc = ArrayList<TocEntry>()
        val re = Regex(
            "(?im)^(?:chapter|part|book|section|prologue|epilogue|introduction|preface|appendix)\\b[^\\n]{0,70}$"
        )
        for (m in re.findAll(text)) {
            toc.add(TocEntry(m.value.trim().take(70), m.range.first))
            if (toc.size >= 400) break
        }
        return toc
    }

    // ---------- TXT / MD ----------

    private fun stripMarkdown(src: String): String = src
        .replace(Regex("(?m)^#{1,6}\\s*"), "")                      // headings
        .replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), "")              // images
        .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")           // links -> text
        .replace(Regex("(\\*\\*|__|\\*|_|`{1,3})"), "")             // emphasis / code marks
        .replace(Regex("(?m)^\\s*[-*+]\\s+"), "• ")                 // bullets
        .replace(Regex("(?m)^\\s*>\\s?"), "")                       // blockquotes

    // ---------- EPUB ----------

    private fun loadEpub(file: File): BookContent {
        ZipFile(file).use { zip ->
            fun read(path: String): String? {
                val entry = zip.getEntry(path) ?: return null
                return zip.getInputStream(entry).readBytes().toString(Charsets.UTF_8)
            }

            val container = read("META-INF/container.xml")
                ?: throw IllegalStateException("Not a valid EPUB (no container.xml)")
            val opfPath = Regex("full-path=\"([^\"]+)\"").find(container)?.groupValues?.get(1)
                ?: throw IllegalStateException("No OPF path in container.xml")
            val opf = read(opfPath) ?: throw IllegalStateException("Missing OPF: $opfPath")
            val opfDir = opfPath.substringBeforeLast('/', "")

            val hrefById = HashMap<String, String>()
            for (m in Regex("<item\\b[^>]*>").findAll(opf)) {
                val tag = m.value
                val id = Regex("\\bid=\"([^\"]+)\"").find(tag)?.groupValues?.get(1) ?: continue
                val href = Regex("\\bhref=\"([^\"]+)\"").find(tag)?.groupValues?.get(1) ?: continue
                hrefById[id] = href
            }

            // Each spine document becomes a TOC entry. Text is normalized
            // per-chunk so recorded offsets stay valid in the final string.
            val sb = StringBuilder()
            val toc = ArrayList<TocEntry>()
            for (m in Regex("<itemref\\b[^>]*\\bidref=\"([^\"]+)\"").findAll(opf)) {
                val href = hrefById[m.groupValues[1]] ?: continue
                val path = (if (opfDir.isEmpty()) href else "$opfDir/$href")
                val decoded = try { URLDecoder.decode(path, "UTF-8") } catch (_: Exception) { path }
                val xhtml = read(decoded) ?: read(path) ?: continue
                val chunk = normalize(htmlToText(xhtml))
                if (chunk.isBlank()) continue
                val title = extractTitle(xhtml) ?: "Section ${toc.size + 1}"
                toc.add(TocEntry(title, sb.length))
                sb.append(chunk).append("\n\n")
            }
            if (sb.isBlank()) throw IllegalStateException("EPUB spine produced no text")
            return BookContent(sb.toString().trimEnd(), toc)
        }
    }

    private fun extractTitle(xhtml: String): String? {
        for (re in listOf(
            Regex("(?is)<h[1-3][^>]*>(.*?)</h[1-3]>"),
            Regex("(?is)<title[^>]*>(.*?)</title>")
        )) {
            val raw = re.find(xhtml)?.groupValues?.get(1) ?: continue
            val t = decodeEntities(raw.replace(Regex("<[^>]+>"), "")).replace(Regex("\\s+"), " ").trim()
            if (t.isNotBlank() && t.length in 1..80 && !t.equals("unknown", true)) return t
        }
        return null
    }

    private fun htmlToText(src: String): String {
        var s = src
        s = s.replace(Regex("(?is)<(head|style|script)\\b.*?</\\1>"), "")
        s = s.replace(Regex("(?s)<!--.*?-->"), "")
        s = s.replace(Regex("(?i)</(p|h[1-6]|li|blockquote|tr|dt|dd)>"), "\n\n")
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)<(p|h[1-6]|li|blockquote|div|section)\\b[^>]*>"), "\n")
        s = s.replace(Regex("<[^>]+>"), "")
        s = decodeEntities(s)
        return s
    }

    private fun decodeEntities(src: String): String {
        var s = src
            .replace("&nbsp;", " ").replace("&amp;", "&")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'")
            .replace("&#39;", "'").replace("&hellip;", "…")
            .replace("&mdash;", "—").replace("&ndash;", "–")
            .replace("&lsquo;", "‘").replace("&rsquo;", "’")
            .replace("&ldquo;", "“").replace("&rdquo;", "”")
        s = Regex("&#(\\d+);").replace(s) { m ->
            m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
        }
        s = Regex("&#x([0-9a-fA-F]+);").replace(s) { m ->
            m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
        }
        return s
    }

    // ---------- PDF (reflowed to plain text) ----------

    private fun loadPdf(ctx: Context, file: File): String {
        PDFBoxResourceLoader.init(ctx.applicationContext)
        PDDocument.load(file).use { doc ->
            val raw = PDFTextStripper().getText(doc)
            return reflowPdf(raw)
        }
    }

    private fun reflowPdf(raw: String): String {
        val lines = raw.replace("\r\n", "\n").split('\n')
        val sb = StringBuilder()
        val para = StringBuilder()
        fun flush() {
            if (para.isNotBlank()) sb.append(para.toString().trim()).append("\n\n")
            para.setLength(0)
        }
        for (line in lines) {
            val t = line.trim()
            if (t.isEmpty()) { flush(); continue }
            if (para.isNotEmpty()) {
                val last = para.last()
                if ((last == '.' || last == '!' || last == '?' || last == ':') && t.first().isUpperCase()) {
                    flush()
                }
            }
            if (para.isNotEmpty()) para.append(' ')
            if (para.isNotEmpty() && para.length >= 2 && para[para.length - 1] == ' ' && para[para.length - 2] == '-') {
                para.setLength(para.length - 2)
            }
            para.append(t)
        }
        flush()
        return sb.toString()
    }

    private fun normalize(src: String): String = src
        .replace("\r\n", "\n").replace('\r', '\n')
        .replace(Regex("[ \\t\\u00A0]+"), " ")
        .replace(Regex("(?m)^ +| +$"), "")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}
