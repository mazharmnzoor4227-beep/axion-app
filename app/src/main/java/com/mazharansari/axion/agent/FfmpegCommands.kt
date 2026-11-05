package com.mazharansari.axion.agent

import org.json.JSONObject
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * v0.6 (Phase 1.5/1.6) VIDEO SKILL — pure command builders for local ffmpeg (runs inside Axion's PRoot Ubuntu, no cloud).
 * Tool contract inspired by the operations list of kajisho5/ffmpeg-skill (MIT, see third_party/NOTICE-ffmpeg-skill.md): trim, captions,
 * reframe, loudness normalisation, compress-to-size, audio extraction, platform presets. All code here is Axion's own.
 * Everything that reaches a shell goes through [q]; user text never gets near the command line except as a quoted argument or as
 * the content of an .srt file Axion writes itself.
 */
object FfmpegCommands {
    enum class Aspect(val id: String, val w: Int, val h: Int) {
        REEL("9:16", 1080, 1920), SQUARE("1:1", 1080, 1080), LANDSCAPE("16:9", 1920, 1080);
        companion object { fun of(s: String?): Aspect? = entries.firstOrNull { it.id == s?.trim() || it.name.equals(s?.trim(), true) } }
    }
    enum class Fit { FILL, BLUR }          // FILL = centre-crop to fill the frame; BLUR = whole picture centred over a blurred copy

    data class Preset(val id: String, val title: String, val aspect: Aspect, val maxSeconds: Int?, val videoKbps: Int, val audioKbps: Int = 128, val fps: Int = 30, val width: Int? = null, val loudnorm: Boolean = true)
    val PRESETS: List<Preset> = listOf(
        Preset("instagram_reel", "Instagram Reel", Aspect.REEL, 90, 4500),
        Preset("tiktok", "TikTok", Aspect.REEL, 600, 4000),
        Preset("youtube_shorts", "YouTube Shorts", Aspect.REEL, 60, 5000),
        Preset("youtube", "YouTube (1080p)", Aspect.LANDSCAPE, null, 8000, 192),
        Preset("instagram_post", "Instagram square post", Aspect.SQUARE, 60, 3500),
        Preset("whatsapp_status", "WhatsApp status", Aspect.REEL, 30, 1500, 96, 30, 720),
    )
    fun preset(id: String?): Preset? = PRESETS.firstOrNull { it.id.equals(id?.trim()?.replace(' ', '_')?.replace('-', '_'), true) }

    /** Single-quote a value for bash. */
    fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** A workspace-relative path that is safe to splice into an ffmpeg filter (our own generated files only). */
    private val FILTER_SAFE = Regex("^[A-Za-z0-9_./-]+$")
    fun requireFilterSafe(path: String): String { require(FILTER_SAFE.matches(path) && !path.contains("..")) { "Unsafe path for a filter: $path" }; return path }
    fun safeName(s: String): String = s.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_', '.').ifBlank { "video" }.take(60)

    // ── time ──
    /** "90", "1:30", "00:01:30.5", "1m30s" → seconds. */
    fun parseTime(s: String?): Double? {
        val t = s?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        Regex("^(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+(?:\\.\\d+)?)s?)?$").matchEntire(t)?.takeIf { t.any { c -> c in "hms" } }?.let { m ->
            return (m.groupValues[1].toIntOrNull() ?: 0) * 3600.0 + (m.groupValues[2].toIntOrNull() ?: 0) * 60.0 + (m.groupValues[3].toDoubleOrNull() ?: 0.0)
        }
        val parts = t.split(':'); if (parts.size > 3) return null
        var total = 0.0
        for (p in parts) total = total * 60 + (p.toDoubleOrNull() ?: return null)
        return total
    }
    fun srtTime(sec: Double): String {
        val ms = (sec.coerceAtLeast(0.0) * 1000).roundToInt(); val h = ms / 3_600_000; val m = ms / 60_000 % 60; val s = ms / 1000 % 60
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, ms % 1000)
    }

    // ── captions ──
    data class Caption(val start: Double, val end: Double, val text: String)
    fun srt(c: List<Caption>): String = c.filter { it.text.isNotBlank() && it.end > it.start }.mapIndexed { i, x ->
        "${i + 1}\n${srtTime(x.start)} --> ${srtTime(x.end)}\n${x.text.trim()}\n"
    }.joinToString("\n")

    /** Spreads plain text over [durationSec] in short caption cards (sentence / phrase breaks first, then word wrap), time proportional to length. */
    fun captionsFromText(text: String, durationSec: Double, maxChars: Int = 34, from: Double = 0.0): List<Caption> {
        val clean = text.replace(Regex("\\s+"), " ").trim(); if (clean.isEmpty() || durationSec <= 0) return emptyList()
        val chunks = ArrayList<String>()
        for (sent in clean.split(Regex("(?<=[.!?؟۔।])\\s+"))) {
            var cur = ""
            for (w in sent.split(' ')) {
                if (cur.isNotEmpty() && cur.length + 1 + w.length > maxChars) { chunks += cur; cur = w } else cur = if (cur.isEmpty()) w else "$cur $w"
            }
            if (cur.isNotEmpty()) chunks += cur
        }
        val total = chunks.sumOf { it.length }.toDouble(); var t = from
        return chunks.map { c -> val d = durationSec * c.length / total; Caption(t, t + d, c).also { t += d } }
    }

    fun subtitlesFilter(srtPath: String, w: Int, h: Int): String {
        val size = max(18, (w / 17.0).roundToInt()); val margin = (h * 0.11).roundToInt()
        return "subtitles=filename=${requireFilterSafe(srtPath)}:original_size=${w}x${h}:force_style='FontName=DejaVu Sans,FontSize=$size,Bold=1,PrimaryColour=&H00FFFFFF&,OutlineColour=&H00000000&,BorderStyle=1,Outline=3,Shadow=0,Alignment=2,MarginV=$margin'"
    }

    // ── probe ──
    data class Probe(val durationSec: Double, val width: Int, val height: Int, val hasAudio: Boolean, val sizeBytes: Long)
    fun probeCommand(input: String) = "ffprobe -v error -print_format json -show_format -show_streams ${q(input)}"
    fun parseProbe(json: String): Probe? = runCatching {
        val o = JSONObject(json.substring(json.indexOf('{')))
        val streams = o.optJSONArray("streams"); var w = 0; var h = 0; var audio = false
        for (i in 0 until (streams?.length() ?: 0)) { val s = streams!!.getJSONObject(i)
            if (s.optString("codec_type") == "video" && w == 0) { w = s.optInt("width"); h = s.optInt("height") }
            if (s.optString("codec_type") == "audio") audio = true }
        val f = o.optJSONObject("format")
        Probe(f?.optString("duration")?.toDoubleOrNull() ?: 0.0, w, h, audio, f?.optString("size")?.toLongOrNull() ?: 0L)
    }.getOrNull()

    // ── the encode plan ──
    data class Plan(
        val input: String, val output: String, val probe: Probe?,
        val start: Double? = null, val durationSec: Double? = null,
        val aspect: Aspect? = null, val fit: Fit = Fit.FILL, val width: Int? = null,
        val srtPath: String? = null, val loudnorm: Boolean = false,
        val videoKbps: Int? = null, val audioKbps: Int = 128, val crf: Int = 21, val fps: Int? = null,
        val twoPass: Boolean = false, val passLog: String = "outputs/.ffpass",
    )

    private fun scaleTo(a: Aspect, width: Int?): Pair<Int, Int> {
        if (width == null || width >= a.w) return a.w to a.h
        val h = (width.toDouble() * a.h / a.w).roundToInt().let { it - it % 2 }; return width to h
    }

    /** The -vf / -filter_complex chain; null when the picture is untouched. */
    fun videoFilter(p: Plan): Pair<String, Boolean>? {   // (graph, isComplex)
        val parts = ArrayList<String>(); var complex = false
        var outW = p.probe?.width ?: 0; var outH = p.probe?.height ?: 0
        if (p.aspect != null) {
            val (w, h) = scaleTo(p.aspect, p.width); outW = w; outH = h
            if (p.fit == Fit.BLUR) {
                complex = true
                parts += "split[a][b];[a]scale=$w:$h:force_original_aspect_ratio=increase,crop=$w:$h,boxblur=24:6[bg];[b]scale=$w:$h:force_original_aspect_ratio=decrease[fg];[bg][fg]overlay=(W-w)/2:(H-h)/2"
            } else parts += "scale=$w:$h:force_original_aspect_ratio=increase,crop=$w:$h"
            parts += "setsar=1"
        }
        if (p.srtPath != null) {
            if (outW == 0 || outH == 0) { outW = 1080; outH = 1920 }
            parts += subtitlesFilter(p.srtPath, outW, outH)
        }
        if (p.fps != null) parts += "fps=${p.fps}"
        if (parts.isEmpty()) return null
        // the blur chain is one graph already containing commas-free pieces joined by ',' for the following filters
        val graph = if (complex) parts[0] + parts.drop(1).joinToString("") { ",$it" } else parts.joinToString(",")
        return graph to complex
    }

    fun encodeCommands(p: Plan): List<String> {
        val c = ArrayList<String>()
        val pre = StringBuilder("ffmpeg -y -hide_banner -nostats -progress pipe:1 ")
        if (p.start != null && p.start > 0) pre.append("-ss ${fmt(p.start)} ")
        pre.append("-i ${q(p.input)} ")
        if (p.durationSec != null && p.durationSec > 0) pre.append("-t ${fmt(p.durationSec)} ")
        val vf = videoFilter(p)
        val vfArg = vf?.let { if (it.second) "-filter_complex ${q(it.first)} " else "-vf ${q(it.first)} " }.orEmpty()
        val af = if (p.loudnorm) "-af loudnorm=I=-16:TP=-1.5:LRA=11 " else ""
        val hasAudio = p.probe?.hasAudio != false
        val rate = if (p.videoKbps != null) "-b:v ${p.videoKbps}k -maxrate ${(p.videoKbps * 1.4).roundToInt()}k -bufsize ${p.videoKbps * 2}k " else "-crf ${p.crf} "
        val venc = "-c:v libx264 -preset veryfast -pix_fmt yuv420p $rate"
        if (p.twoPass && p.videoKbps != null) {
            c += "$pre$vfArg${venc}-pass 1 -passlogfile ${q(p.passLog)} -an -f null /dev/null"
            c += "$pre$vfArg${venc}-pass 2 -passlogfile ${q(p.passLog)} ${if (hasAudio) "$af-c:a aac -b:a ${p.audioKbps}k " else "-an "}-movflags +faststart ${q(p.output)}"
        } else {
            c += "$pre$vfArg$venc${if (hasAudio) "$af-c:a aac -b:a ${p.audioKbps}k " else "-an "}-movflags +faststart ${q(p.output)}"
        }
        return c
    }
    private fun fmt(d: Double) = String.format(Locale.US, "%.3f", d).trimEnd('0').trimEnd('.')

    /** Bitrate that lands a file near [targetMb] (3 % headroom for the container). */
    fun videoKbpsForSize(targetMb: Double, durationSec: Double, audioKbps: Int = 128): Int {
        require(targetMb > 0 && durationSec > 0) { "target size and duration must be positive" }
        val totalKbit = targetMb * 8 * 1024 * 0.97
        return max(100, (totalKbit / durationSec - audioKbps).roundToInt())
    }

    /** Loudness-normalise (EBU R128, -16 LUFS) without touching the picture. */
    fun normalizeCommand(input: String, output: String) = "ffmpeg -y -hide_banner -nostats -progress pipe:1 -i ${q(input)} -c:v copy -af loudnorm=I=-16:TP=-1.5:LRA=11 -c:a aac -b:a 160k -movflags +faststart ${q(output)}"

    fun extractAudioCommand(input: String, output: String, format: String): String {
        val codec = when (format.lowercase()) { "mp3" -> "-c:a libmp3lame -q:a 2"; "wav" -> "-c:a pcm_s16le"; "m4a", "aac" -> "-c:a aac -b:a 192k"; else -> throw IllegalArgumentException("format must be mp3, m4a or wav") }
        return "ffmpeg -y -hide_banner -nostats -progress pipe:1 -i ${q(input)} -vn $codec ${q(output)}"
    }

    const val INSTALL_CHECK = "command -v ffmpeg >/dev/null 2>&1 && command -v ffprobe >/dev/null 2>&1 && fc-list 2>/dev/null | grep -qi dejavu && echo FFMPEG_OK"
    const val INSTALL = "export DEBIAN_FRONTEND=noninteractive; apt-get update -y 2>&1 | tail -3; apt-get install -y --no-install-recommends ffmpeg fonts-dejavu-core fontconfig 2>&1 | tail -15; command -v ffmpeg && echo FFMPEG_OK"
}
