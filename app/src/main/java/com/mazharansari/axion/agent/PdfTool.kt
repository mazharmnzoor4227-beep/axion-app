package com.mazharansari.axion.agent

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import com.mazharansari.axion.data.Artifact
import com.mazharansari.axion.data.StepKind
import com.mazharansari.axion.llm.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CreatePdfTool : Tool {
    override val spec = ToolSpec(
        "create_pdf",
        "Create a professional PDF report from markdown (headings #/##/###, paragraphs, **bold**, bullet and numbered lists, " +
            "pipe tables, > callouts, --- rules). Any language/script works (Urdu, Hindi, English). The PDF appears in chat as a page-1 preview card " +
            "the user taps to open (share/download inside). Use whenever the user asks for a PDF, report, document, notes, CV, invoice or audit.",
        schema {
            str("title", "Report title (shown on top)")
            str("filename", "File name ending in .pdf, e.g. Clawora Beauty Audit Report.pdf")
            str("markdown", "Full report body in markdown")
        },
    )

    override fun label(args: JsonObject) = StepLabel(StepKind.PDF, "Creating PDF", args.s("filename") ?: "report.pdf")

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput = withContext(Dispatchers.IO) {
        var name = (args.s("filename") ?: args.s("title") ?: "Report").replace(Regex("[\\\\/:*?\"<>|]"), " ").trim()
        if (!name.lowercase().endsWith(".pdf")) name += ".pdf"
        val out = File(ctx.workspace.outputs, name)
        val pages = runCatching { PdfWriter.write(out, args.s("title") ?: name.removeSuffix(".pdf"), args.s("markdown").orEmpty()) }
            .getOrElse { return@withContext ToolOutput("PDF failed: ${it.message}", isError = true) }
        ToolOutput(
            "Created outputs/$name ($pages pages, ${out.length() / 1024} KB). It is already shown to the user above your reply as a PDF preview card; " +
                "do not paste its contents again — reply with a short summary of what's inside (a few bullets), in the user's language and script.",
            artifact = Artifact(name, "application/pdf", out.absolutePath, out.length(), pages),
            doneTitle = "Created PDF",
        )
    }
}

object PdfWriter {
    private const val W = 595; private const val H = 842; private const val M = 48f
    private val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10.5f; color = Color.rgb(28, 28, 28) }
    private val small = TextPaint(body).apply { textSize = 8.5f; color = Color.rgb(120, 120, 120) }
    private val line = Paint().apply { color = Color.rgb(210, 210, 210); strokeWidth = 0.6f }
    private val fill = Paint().apply { color = Color.rgb(244, 244, 244) }

    /** Where pages go: a real PdfDocument on the phone, bitmaps in tests/previews. */
    interface Pages { fun begin(n: Int): Canvas; fun end(n: Int) }

    private class Ctx(val pages: Pages) { var c: Canvas? = null; var y = M; var n = 0 }

    fun write(file: File, title: String, md: String): Int {
        val doc = PdfDocument()
        var page: PdfDocument.Page? = null
        val n = render(title, md, object : Pages {
            override fun begin(n: Int): Canvas { page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, n).create()); return page!!.canvas }
            override fun end(n: Int) { doc.finishPage(page) }
        })
        file.parentFile?.mkdirs()
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        runCatching { writePreviews(file, title, md) }
        return n
    }

    /**
     * v0.3.1: page images next to the PDF (Grok's chat card shows page 1; the in-app viewer shows every page) — drawn by
     * the same renderer, so they look exactly like the PDF even where the phone's PDF engine is slow or missing.
     */
    fun previewDir(pdf: File) = File(pdf.parentFile, ".previews/" + pdf.name)

    fun writePreviews(pdf: File, title: String, md: String, scale: Float = 1.6f, maxPages: Int = 40) {
        val dir = previewDir(pdf).apply { deleteRecursively(); mkdirs() }
        var bmp: android.graphics.Bitmap? = null
        render(title, md, object : Pages {
            override fun begin(n: Int): Canvas {
                val b = android.graphics.Bitmap.createBitmap((W * scale).toInt(), (H * scale).toInt(), android.graphics.Bitmap.Config.ARGB_8888)
                b.eraseColor(Color.WHITE); bmp = b
                return Canvas(b).apply { scale(scale, scale) }
            }
            override fun end(n: Int) {
                val b = bmp ?: return
                if (n <= maxPages) File(dir, "page_%03d.png".format(n)).outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                b.recycle(); bmp = null
            }
        })
    }

    const val PAGE_W = W; const val PAGE_H = H

    fun render(title: String, md: String, pages: Pages): Int {
        val x = Ctx(pages)
        newPage(x)
        // Title block
        val tp = TextPaint(body).apply { textSize = 22f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); color = Color.BLACK }
        draw(x, layout(title, tp), 0f)
        x.y += 4
        draw(x, layout("Prepared by Axion · " + SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).format(Date()), small), 0f)
        x.y += 8; x.c!!.drawLine(M, x.y, W - M, x.y, line); x.y += 14

        val lines = md.replace("\r", "").lines()
        var i = 0
        val para = StringBuilder()
        fun flush() { if (para.isNotBlank()) { draw(x, layout(inline(para.toString().trim()), body), 0f); x.y += 7 }; para.clear() }
        while (i < lines.size) {
            val l = lines[i]; val t = l.trim()
            when {
                t.isEmpty() -> flush()
                t.startsWith("```") -> {
                    flush(); val code = StringBuilder(); i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) { code.appendLine(lines[i]); i++ }
                    val cp = TextPaint(body).apply { typeface = Typeface.MONOSPACE; textSize = 8.8f }
                    val lay = layout(code.toString().trimEnd(), cp, inset = 16f)
                    ensure(x, minOf(lay.height.toFloat(), 120f) + 12)
                    x.c!!.drawRect(M, x.y, W - M, x.y + lay.height + 12, fill)
                    x.y += 6; draw(x, lay, 8f); x.y += 12
                }
                t.startsWith("#") -> {
                    flush()
                    val level = t.takeWhile { it == '#' }.length
                    val size = when (level) { 1 -> 17f; 2 -> 14f; else -> 12f }
                    val hp = TextPaint(body).apply { textSize = size; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); color = Color.BLACK }
                    x.y += if (level <= 2) 10 else 6
                    ensure(x, size * 3)
                    draw(x, layout(inline(t.trimStart('#').trim()), hp), 0f)
                    x.y += if (level <= 2) 6 else 4
                }
                t.startsWith("|") -> {
                    flush(); val rows = mutableListOf<List<String>>()
                    while (i < lines.size && lines[i].trim().startsWith("|")) {
                        val cells = lines[i].trim().trim('|').split("|").map { it.trim() }
                        if (!cells.all { it.matches(Regex(":?-{2,}:?")) }) rows += cells
                        i++
                    }
                    table(x, rows); x.y += 8; continue
                }
                t == "---" || t == "***" -> { flush(); x.y += 4; ensure(x, 8f); x.c!!.drawLine(M, x.y, W - M, x.y, line); x.y += 10 }
                t.startsWith(">") -> {
                    flush(); val lay = layout(inline(t.removePrefix(">").trim()), TextPaint(body).apply { color = Color.rgb(60, 60, 60) }, inset = 14f)
                    ensure(x, lay.height + 10f)
                    x.c!!.drawRect(M, x.y - 2, M + 2.5f, x.y + lay.height + 2, Paint().apply { color = Color.rgb(40, 40, 40) })
                    draw(x, lay, 12f); x.y += 8
                }
                Regex("^([-*•]|\\d+[.)])\\s+.*").matches(t) -> {
                    flush()
                    val indent = (l.length - l.trimStart().length) / 2 * 12f
                    val bullet = Regex("^(\\d+[.)])").find(t)?.value ?: "•"
                    val text = t.replaceFirst(Regex("^([-*•]|\\d+[.)])\\s+"), "")
                    val lay = layout(inline(text), body, inset = 16f + indent)
                    ensure(x, minOf(lay.height.toFloat(), 40f))
                    x.c!!.drawText(bullet, M + indent, x.y + body.textSize, body)
                    draw(x, lay, 16f + indent); x.y += 3
                }
                else -> para.append(t).append(' ')
            }
            i++
        }
        flush()
        finishPage(x)
        return x.n
    }

    private fun inline(s: String): CharSequence {
        val sb = SpannableStringBuilder()
        val re = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`|\\[([^\\]]+)]\\(([^)]+)\\)|\\*(\\S[^*]*?)\\*")
        var last = 0
        for (m in re.findAll(s)) {
            sb.append(s, last, m.range.first)
            val start = sb.length
            when {
                m.groups[1] != null -> { sb.append(m.groupValues[1]); sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                m.groups[2] != null -> { sb.append(m.groupValues[2]); sb.setSpan(TypefaceSpan("monospace"), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                m.groups[3] != null -> sb.append(m.groupValues[3])
                m.groups[5] != null -> { sb.append(m.groupValues[5]); sb.setSpan(StyleSpan(Typeface.ITALIC), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            }
            last = m.range.last + 1
        }
        sb.append(s, last, s.length)
        return sb
    }

    private fun layout(text: CharSequence, p: TextPaint, inset: Float = 0f, width: Int = (W - 2 * M - inset).toInt()): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, p, maxOf(40, width)).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(2f, 1f).build()

    private fun newPage(x: Ctx) {
        x.n++
        x.c = x.pages.begin(x.n); x.y = M
    }

    private fun finishPage(x: Ctx) {
        x.c!!.drawText("Page ${x.n}", W - M - small.measureText("Page ${x.n}"), H - 24f, small)
        x.c!!.drawText("Axion", M, H - 24f, small)
        x.pages.end(x.n)
    }

    private fun ensure(x: Ctx, h: Float) { if (x.y + h > H - M - 10) { finishPage(x); newPage(x) } }

    /** Draws a layout line by line so long paragraphs flow across pages. */
    private fun draw(x: Ctx, lay: StaticLayout, dx: Float) {
        var line = 0
        while (line < lay.lineCount) {
            val lh = (lay.getLineBottom(line) - lay.getLineTop(line)).toFloat()
            ensure(x, lh)
            var last = line
            var hgt = lh
            while (last + 1 < lay.lineCount) {
                val next = (lay.getLineBottom(last + 1) - lay.getLineTop(last + 1)).toFloat()
                if (x.y + hgt + next > H - M - 10) break
                hgt += next; last++
            }
            val c = x.c!!
            c.save()
            val top = lay.getLineTop(line).toFloat()
            c.translate(M + dx, x.y - top)
            c.clipRect(0f, top, lay.width.toFloat(), lay.getLineBottom(last).toFloat())
            lay.draw(c)
            c.restore()
            x.y += hgt
            line = last + 1
        }
    }

    private fun table(x: Ctx, rows: List<List<String>>) {
        if (rows.isEmpty()) return
        val cols = rows.maxOf { it.size }
        val colW = (W - 2 * M) / cols
        val bold = TextPaint(body).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textSize = 9.5f }
        val cellP = TextPaint(body).apply { textSize = 9.5f }
        rows.forEachIndexed { r, row ->
            val lays = (0 until cols).map { cI -> layout(inline(row.getOrElse(cI) { "" }), if (r == 0) bold else cellP, width = (colW - 12).toInt()) }
            val h = lays.maxOf { it.height } + 10f
            ensure(x, h)
            val c = x.c!!
            if (r == 0) c.drawRect(M, x.y, W - M, x.y + h, fill)
            lays.forEachIndexed { cI, lay -> c.save(); c.translate(M + cI * colW + 6, x.y + 5); lay.draw(c); c.restore() }
            c.drawLine(M, x.y + h, W - M, x.y + h, line)
            x.y += h
        }
    }
}
