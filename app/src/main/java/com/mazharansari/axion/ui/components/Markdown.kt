package com.mazharansari.axion.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.mazharansari.axion.ui.components.Gk
import com.mazharansari.axion.ui.components.IconTap
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mazharansari.axion.ui.theme.P
import com.mazharansari.axion.ui.theme.Palette
import com.mazharansari.axion.ui.theme.Type
import kotlinx.coroutines.delay

sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Para(val text: String) : MdBlock
    data class ListItem(val marker: String, val text: String, val indent: Int) : MdBlock
    data class Code(val lang: String, val code: String, val closed: Boolean) : MdBlock
    data class Table(val rows: List<List<String>>) : MdBlock
    data class Quote(val text: String) : MdBlock
    data object Rule : MdBlock
}

object MdParser {
    fun parse(src: String): List<MdBlock> {
        val out = ArrayList<MdBlock>()
        val lines = src.replace("\r", "").split('\n')
        var i = 0
        val para = StringBuilder()
        fun flush() { if (para.isNotBlank()) out += MdBlock.Para(para.toString().trim()); para.setLength(0) }
        while (i < lines.size) {
            val raw = lines[i]; val t = raw.trim()
            when {
                t.startsWith("```") -> {
                    flush(); val lang = t.removePrefix("```").trim(); val sb = StringBuilder(); i++
                    var closed = false
                    while (i < lines.size) { if (lines[i].trim().startsWith("```")) { closed = true; break }; sb.append(lines[i]).append('\n'); i++ }
                    out += MdBlock.Code(lang, sb.toString().trimEnd('\n'), closed)
                }
                t.isEmpty() -> flush()
                t.startsWith("#") && t.trimStart('#').startsWith(" ") -> { flush(); out += MdBlock.Heading(t.takeWhile { it == '#' }.length, t.trimStart('#').trim()) }
                t.startsWith("|") && t.count { it == '|' } >= 2 -> {
                    flush(); val rows = ArrayList<List<String>>()
                    while (i < lines.size && lines[i].trim().startsWith("|")) {
                        val cells = lines[i].trim().trim('|').split('|').map { it.trim() }
                        if (!cells.all { it.matches(Regex(":?-{2,}:?")) }) rows += cells
                        i++
                    }
                    out += MdBlock.Table(rows); continue
                }
                t == "---" || t == "***" || t == "___" -> { flush(); out += MdBlock.Rule }
                t.startsWith(">") -> { flush(); out += MdBlock.Quote(t.removePrefix(">").trim()) }
                Regex("^([-*+•])\\s+.+").matches(t) -> { flush(); out += MdBlock.ListItem("•", t.substring(2).trim(), (raw.length - raw.trimStart().length) / 2) }
                Regex("^\\d+[.)]\\s+.+").matches(t) -> { flush(); val m = t.substringBefore(' '); out += MdBlock.ListItem(m, t.substringAfter(' ').trim(), (raw.length - raw.trimStart().length) / 2) }
                else -> { if (para.isNotEmpty()) para.append('\n'); para.append(t) }
            }
            i++
        }
        flush()
        return out
    }

    private val inlineRe = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__|`([^`]+)`|\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)|(?<![*\\w])\\*(?!\\s)([^*]+?)\\*(?!\\*)|(https?://[^\\s)]+)")

    fun inline(s: String, p: Palette): AnnotatedString = buildAnnotatedString {
        var last = 0
        for (m in inlineRe.findAll(s)) {
            append(s.substring(last, m.range.first))
            val g = m.groups
            when {
                g[1] != null || g[2] != null -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(inline((g[1] ?: g[2])!!.value, p)) }
                g[3] != null -> withStyle(SpanStyle(fontFamily = com.mazharansari.axion.ui.theme.GoogleSansCode, background = p.chip, fontSize = 14.sp)) { append(" ${g[3]!!.value} ") }
                g[4] != null -> withLink(LinkAnnotation.Url(g[5]!!.value, TextLinkStyles(SpanStyle(color = p.text, textDecoration = TextDecoration.Underline)))) { append(g[4]!!.value) }
                g[6] != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[6]!!.value) }
                g[7] != null -> withLink(LinkAnnotation.Url(g[7]!!.value, TextLinkStyles(SpanStyle(color = p.text, textDecoration = TextDecoration.Underline)))) { append(g[7]!!.value) }
            }
            last = m.range.last + 1
        }
        append(s.substring(last))
    }
}

@Composable
fun Markdown(text: String, modifier: Modifier = Modifier) {
    val p = P
    val blocks = remember(text) { MdParser.parse(text) }
    // v0.3.1: Grok spaces blocks 12 dp apart (bullet pitch 36 dp = 24 sp line + 12).
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        blocks.forEach { b ->
            when (b) {
                is MdBlock.Heading -> Text(
                    remember(b.text, p) { MdParser.inline(b.text, p) },
                    style = Type.body.copy(fontSize = when (b.level) { 1 -> 22.sp; 2 -> 19.sp; else -> 17.sp }, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
                    color = p.text, modifier = Modifier.padding(top = 4.dp),
                )
                is MdBlock.Para -> Text(remember(b.text, p) { MdParser.inline(b.text, p) }, style = Type.body, color = p.text)
                is MdBlock.ListItem -> Row(Modifier.padding(start = (b.indent * 16).dp)) {
                    // Grok: bullet 8 dp in, its text 19 dp in, both in the text colour
                    if (b.marker == "•") Box(Modifier.width(19.dp)) { Text("•", style = Type.body, color = p.text, modifier = Modifier.padding(start = 6.dp)) }
                    else Text(b.marker, style = Type.body, color = p.text, modifier = Modifier.width(24.dp))
                    Text(remember(b.text, p) { MdParser.inline(b.text, p) }, style = Type.body, color = p.text)
                }
                is MdBlock.Code -> CodeBlock(b.lang, b.code)
                is MdBlock.Table -> MdTable(b.rows)
                is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(p.border))
                    Text(remember(b.text, p) { MdParser.inline(b.text, p) }, style = Type.body, color = p.text2, modifier = Modifier.padding(start = 12.dp))
                }
                MdBlock.Rule -> Box(Modifier.fillMaxWidth().height(1.dp).background(p.border))
            }
        }
    }
}

@Composable
fun CodeBlock(lang: String, code: String, title: String? = null) {
    val p = P
    val clip = LocalClipboardManager.current
    val ctxClip = androidx.compose.ui.platform.LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.code).border(1.dp, p.border, RoundedCornerShape(14.dp))) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 0.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title ?: lang.ifBlank { "code" }, style = Type.small, color = p.text2, modifier = Modifier.weight(1f))
            IconTap(if (copied) Gk.check else Gk.copy, "Copy", 18.dp) { com.mazharansari.axion.security.SecureClipboard.copy(ctxClip, "Code", code); copied = true }
        }
        SelectionContainer {
            Text(code, style = Type.mono, color = p.text, softWrap = false,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 12.dp, top = 2.dp))
        }
    }
}

@Composable
fun MdTable(rows: List<List<String>>) {
    if (rows.isEmpty()) return
    val p = P
    val cols = rows.maxOf { it.size }
    // One width per column (from its longest cell) so every row lines up like a real table.
    val widths = remember(rows) { (0 until cols).map { c -> (rows.maxOf { it.getOrElse(c) { "" }.replace("**", "").length } * 7.6f + 26f).coerceIn(64f, 230f).dp } }
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, p.border, RoundedCornerShape(14.dp)).horizontalScroll(rememberScrollState())) {
        Column {
            rows.forEachIndexed { r, row ->
                Row(Modifier.background(if (r == 0) p.surface else Color.Transparent).height(IntrinsicSize.Min).drawBehind {
                    if (r < rows.lastIndex) drawLine(p.border, Offset(0f, size.height), Offset(size.width, size.height), 1f)
                }) {
                    for (c in 0 until cols) {
                        Text(
                            remember(row, c, p) { MdParser.inline(row.getOrElse(c) { "" }, p) },
                            style = Type.small.copy(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = if (r == 0) FontWeight.SemiBold else FontWeight.Normal),
                            color = p.text,
                            modifier = Modifier.width(widths[c]).padding(horizontal = 12.dp, vertical = 9.dp),
                        )
                    }
                }
            }
        }
    }
}
