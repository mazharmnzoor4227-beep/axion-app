package com.mazharansari.axion.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mazharansari.axion.data.Step
import com.mazharansari.axion.data.StepKind
import com.mazharansari.axion.data.StepStatus
import com.mazharansari.axion.ui.components.*
import com.mazharansari.axion.ui.theme.P
import com.mazharansari.axion.ui.theme.Type

/**
 * v0.3 (mazhar 10:12, Grok style): every agent step row opens INLINE into a box with what it really did — the code
 * a file was written with (syntax-coloured), a PDF's markdown, a page's extract, a search's results — and closes on
 * a second tap. Monospace, scrolls both ways, copy button. Bash steps keep their own BashCard (command + output).
 */
@Composable
fun ExpandableStep(s: Step, size: Int = 15, startOpen: Boolean = false) {
    var open by rememberSaveable(s.id) { mutableStateOf(startOpen) }
    val body = s.detail.ifBlank { s.output }
    // v0.5 Build: while a file is being written its code streams into the open box (tail shown); when the step ends the box
    // shows the diff (+/- lines) the edit made.
    val streaming = s.status == StepStatus.RUNNING && s.kind == StepKind.WRITE && s.detail.isNotBlank()
    LaunchedEffect(streaming) { if (streaming) open = true }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).then(if (body.isNotBlank()) Modifier.clickable { open = !open } else Modifier).padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { StepText(s, size) }
            if (s.status == StepStatus.RUNNING) PulseDot()
            else if (body.isNotBlank()) Box(Modifier.graphicsLayer { rotationZ = if (open) 180f else 0f }) { GkIcon(Gk.chevronDown, if (open) "Collapse" else "Expand", 16.dp, P.text3) }
        }
        AnimatedVisibility(open && body.isNotBlank(), enter = expandVertically(tween(200)) + fadeIn(tween(160)), exit = shrinkVertically(tween(180)) + fadeOut(tween(120))) {
            val isDiff = s.detail.startsWith("--- a/")
            CodeBox(
                header = when (s.kind) { StepKind.WRITE -> s.target.substringAfterLast('/') + if (streaming) " · writing…" else if (isDiff) " · changes" else ""; StepKind.PDF -> "report.md"; else -> s.title },
                code = if (streaming) body.lines().takeLast(36).joinToString("\n") else body,
                lang = when { isDiff -> "diff"; s.detail.isNotBlank() -> langOf(s.target); else -> "text" }, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
            )
        }
    }
}

@Composable
fun CodeBox(header: String, code: String, lang: String, modifier: Modifier = Modifier) {
    val p = P
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    val shape = RoundedCornerShape(14.dp)
    val text = remember(code, lang) { highlight(code.take(60_000), lang) }
    Column(modifier.fillMaxWidth().clip(shape).background(p.code).border(1.dp, if (p.isDark) Color(0xFF222222) else p.border, shape)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(header, style = Type.small.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium), color = p.text2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("${code.lines().size} lines", style = Type.small.copy(fontSize = 12.sp), color = p.text3)
            IconTap(Gk.copy, "Copy", 17.dp, p.text2, 36.dp) { clip.setText(AnnotatedString(code)) }
        }
        Box(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            SelectionContainer {
                Text(text, style = Type.mono.copy(fontSize = 12.5.sp, lineHeight = 19.sp), softWrap = false,
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 12.dp))
            }
        }
    }
}

fun langOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
    "html", "htm", "xml", "svg" -> "html"
    "js", "ts", "jsx", "tsx", "json", "css", "kt", "java", "c", "cpp", "go", "rs", "swift", "dart" -> "c"
    "py", "sh", "rb", "yaml", "yml", "toml" -> "py"
    "md" -> "md"
    else -> "c"
}

private val KW = Regex("\\b(function|const|let|var|return|if|else|for|while|do|switch|case|break|continue|class|interface|extends|implements|import|export|from|as|new|this|try|catch|finally|throw|async|await|def|lambda|pass|elif|in|is|not|and|or|None|True|False|true|false|null|undefined|fun|val|private|public|protected|static|void|int|string|package|struct|enum|echo|then|fi|done)\\b")
private val STR = Regex("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|`[^`]*`")
private val NUM = Regex("\\b\\d+(?:\\.\\d+)?\\b")
private val TAG = Regex("</?[A-Za-z][\\w:-]*|/?>")
private val ATTR = Regex("\\s([a-zA-Z-:]+)(?==)")

/** A small, fast syntax colouring (Grok's dark code palette): comments, strings, keywords, numbers, HTML tags. */
fun highlight(code: String, lang: String): AnnotatedString {
    val cComment = Color(0xFF6A737D); val cStr = Color(0xFFA5D6A7); val cKw = Color(0xFFC792EA); val cNum = Color(0xFFF78C6C)
    val cTag = Color(0xFF82AAFF); val cAttr = Color(0xFFFFCB6B); val cText = Color(0xFFD6D6D6)
    if (lang == "text") return buildAnnotatedString { withStyle(SpanStyle(color = Color(0xFFB0B0B0))) { append(code) } }
    if (lang == "diff") return buildAnnotatedString {
        code.lines().forEachIndexed { n, l ->
            val style = when {
                l.startsWith("+++") || l.startsWith("---") -> SpanStyle(color = Color(0xFF8A8A8A))
                l.startsWith("@@") -> SpanStyle(color = Color(0xFF82AAFF))
                l.startsWith("+") -> SpanStyle(color = Color(0xFF7EE787), background = Color(0x1F3FB950))
                l.startsWith("-") -> SpanStyle(color = Color(0xFFFFA198), background = Color(0x1FF85149))
                else -> SpanStyle(color = Color(0xFFB0B0B0))
            }
            withStyle(style) { append(l) }; if (n < code.lines().lastIndex) append('\n')
        }
    }
    val colors = arrayOfNulls<Color>(code.length)
    fun paint(r: Regex, c: Color, group: Int = 0) = r.findAll(code).forEach { m ->
        val range = if (group == 0) m.range else m.groups[group]?.range ?: return@forEach
        for (i in range) if (colors[i] == null) colors[i] = c
    }
    // comments first so nothing inside them is recoloured
    when (lang) {
        "html" -> paint(Regex("<!--[\\s\\S]*?-->"), cComment)
        "py" -> paint(Regex("#[^\\n]*"), cComment)
        "md" -> { paint(Regex("(?m)^#{1,6} .*$"), cTag); paint(Regex("\\*\\*[^*]+\\*\\*"), cAttr) }
        else -> { paint(Regex("/\\*[\\s\\S]*?\\*/"), cComment); paint(Regex("(?<![:\"'])//[^\\n]*"), cComment) }
    }
    if (lang == "html") { paint(Regex("/\\*[\\s\\S]*?\\*/"), cComment); paint(TAG, cTag); paint(ATTR, cAttr, 1) }
    paint(STR, cStr)
    if (lang != "md") { paint(KW, cKw); paint(NUM, cNum) }
    return buildAnnotatedString {
        var i = 0
        while (i < code.length) {
            val c = colors[i] ?: cText
            var j = i + 1
            while (j < code.length && (colors[j] ?: cText) == c) j++
            withStyle(SpanStyle(color = c)) { append(code, i, j) }
            i = j
        }
    }
}
