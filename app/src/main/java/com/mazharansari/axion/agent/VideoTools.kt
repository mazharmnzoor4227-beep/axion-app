package com.mazharansari.axion.agent

import com.mazharansari.axion.agent.FfmpegCommands.Aspect
import com.mazharansari.axion.agent.FfmpegCommands.Fit
import com.mazharansari.axion.agent.FfmpegCommands.Plan
import com.mazharansari.axion.data.Artifact
import com.mazharansari.axion.data.StepKind
import com.mazharansari.axion.linux.LinuxHub
import com.mazharansari.axion.linux.LinuxRuntime
import com.mazharansari.axion.llm.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.math.min

/**
 * v0.6 (Phase 1.6) VIDEO SKILL tools — everything runs on the phone with ffmpeg inside Axion's Ubuntu (no cloud, no keys).
 * `video_probe` reads a video's size/length; `video_edit` does trim, captions, reframe, reel, normalize, compress, extract_audio, preset.
 * The pure command logic is in [FfmpegCommands] (unit-tested, including runs against a real ffmpeg).
 * Idea/tool contract credit: kajisho5/ffmpeg-skill (MIT) — see third_party/NOTICE-ffmpeg-skill.md.
 */
internal object VideoRunner {
    class Fail(msg: String) : Exception(msg)

    fun linux(ctx: ToolContext): LinuxRuntime {
        val l = LinuxHub.linux
        if (l == null || !l.isInstalled() || ctx.workspace.root.canonicalPath != l.workspaceHost.canonicalPath)
            throw Fail("Video editing runs locally inside Axion's Linux. Install it first: Settings → Developer tools → Linux. Then ask again.")
        return l
    }

    suspend fun ensureFfmpeg(l: LinuxRuntime, ctx: ToolContext) {
        val (c0, o0) = l.run(FfmpegCommands.INSTALL_CHECK, 30_000)
        if (c0 == 0 && o0.contains("FFMPEG_OK")) return
        ctx.progress("Installing the video engine (ffmpeg) — first time only, needs internet…")
        val (c, o) = l.run(FfmpegCommands.INSTALL, 1_500_000) { line -> if (line.isNotBlank()) ctx.progress("Installing ffmpeg… " + line.takeLast(160)) }
        if (!o.contains("FFMPEG_OK")) throw Fail("Could not install ffmpeg (exit $c): ${o.takeLast(400)}")
    }

    suspend fun probe(l: LinuxRuntime, rel: String): FfmpegCommands.Probe {
        val (code, out) = l.run(FfmpegCommands.probeCommand(rel), 60_000)
        return FfmpegCommands.parseProbe(out)?.takeIf { code == 0 && it.width > 0 } ?: throw Fail("That file is not a readable video: ${out.takeLast(300)}")
    }

    /** Runs one ffmpeg command, turning `-progress` lines into "Encoding 0:12 / 1:30" for the live card. */
    suspend fun exec(l: LinuxRuntime, cmd: String, totalSec: Double, label: String, ctx: ToolContext): String {
        val (code, out) = l.run(cmd, 1_500_000) { line ->
            if (line.startsWith("out_time=")) {
                val t = FfmpegCommands.parseTime(line.removePrefix("out_time=").substringBefore('.')) ?: 0.0
                ctx.progress("$label ${mmss(t)}" + if (totalSec > 0) " / ${mmss(totalSec)}" else "")
            }
        }
        if (code != 0) throw Fail("ffmpeg failed (exit $code):\n" + out.lines().filter { it.isNotBlank() && !it.startsWith("out_time") && !it.startsWith("progress=") && !it.contains("=") || it.contains("rror") }.takeLast(14).joinToString("\n"))
        return out
    }
    fun mmss(t: Double) = "%d:%02d".format(t.toInt() / 60, t.toInt() % 60)
}

class VideoProbeTool : Tool {
    override val spec = ToolSpec("video_probe",
        "Read a video's duration, resolution and whether it has sound (runs locally). Use before editing so captions and trims fit.",
        schema { str("path", "Video path relative to the workspace, e.g. uploads/clip.mp4") })
    override fun label(args: JsonObject) = StepLabel(StepKind.OTHER, "Checked video", args.s("path").orEmpty().substringAfterLast('/'))
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput = withContext(Dispatchers.IO) {
        try {
            val l = VideoRunner.linux(ctx); VideoRunner.ensureFfmpeg(l, ctx)
            val f = ctx.workspace.resolve(args.s("path").orEmpty()); if (!f.isFile) return@withContext ToolOutput("No such file: ${args.s("path")}", isError = true)
            val p = VideoRunner.probe(l, ctx.workspace.rel(f))
            ToolOutput("duration ${"%.1f".format(p.durationSec)} s, ${p.width}x${p.height}, audio: ${if (p.hasAudio) "yes" else "no"}, size ${p.sizeBytes / 1024} KB")
        } catch (e: CancellationException) { throw e } catch (e: VideoRunner.Fail) { ToolOutput(e.message.orEmpty(), isError = true) } catch (e: Throwable) { ToolOutput("Video error: ${e.message}", isError = true) }
    }
}

class VideoEditTool : Tool {
    override val spec = ToolSpec("video_edit",
        "Edit a video locally on the phone (ffmpeg, no cloud). operation: trim | reframe | captions | reel | normalize | compress | extract_audio | preset. " +
            "trim: start+end. reframe: aspect 9:16|1:1|16:9 and fit fill|blur. captions: burn text onto the video — pass `captions` as the words to show (spread over the video, or the trimmed part) or as SRT text, or `srt_path`. " +
            "reel = reframe to 9:16 + captions + loudness in one go (the 'make this a reel with captions' request). normalize = fix loudness. compress: target_mb. extract_audio: format mp3|m4a|wav. " +
            "preset: instagram_reel | tiktok | youtube_shorts | youtube | instagram_post | whatsapp_status (right size, length cap, bitrate, loudness). " +
            "The result is saved in outputs/ and in the phone's Gallery (Movies/Axion). There is no speech-to-text here: caption words come from the user or from you.",
        schema {
            str("operation", "trim | reframe | captions | reel | normalize | compress | extract_audio | preset")
            str("path", "Input video, relative to the workspace, e.g. uploads/clip.mp4")
            str("output", "Output file name (optional), e.g. reel.mp4", required = false)
            str("start", "Trim start, seconds or mm:ss (optional)", required = false)
            str("end", "Trim end, seconds or mm:ss (optional)", required = false)
            str("aspect", "9:16 | 1:1 | 16:9 (reframe / reel)", required = false)
            str("fit", "fill (centre-crop, default) | blur (whole picture over a blurred background)", required = false)
            str("captions", "Words to burn in as captions, or SRT text (captions / reel / preset)", required = false)
            str("srt_path", "An existing .srt in the workspace (alternative to captions)", required = false)
            str("target_mb", "compress: wanted size in MB, e.g. 8", required = false)
            str("format", "extract_audio: mp3 | m4a | wav", required = false)
            str("preset", "preset name for operation=preset", required = false)
        })
    override fun label(args: JsonObject) = StepLabel(StepKind.OTHER, "Video · ${args.s("operation").orEmpty()}", args.s("path").orEmpty().substringAfterLast('/'))

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput = withContext(Dispatchers.IO) {
        try { edit(args, ctx) }
        catch (e: CancellationException) { throw e }
        catch (e: VideoRunner.Fail) { ToolOutput(e.message.orEmpty(), isError = true) }
        catch (e: IllegalArgumentException) { ToolOutput("Bad video request: ${e.message}", isError = true) }
        catch (e: Throwable) { ToolOutput("Video error: ${e.message}", isError = true) }
    }

    private suspend fun edit(args: JsonObject, ctx: ToolContext): ToolOutput {
        val op = args.s("operation").orEmpty().trim().lowercase()
        require(op in setOf("trim", "reframe", "captions", "reel", "normalize", "compress", "extract_audio", "preset")) { "unknown operation '$op'" }
        val l = VideoRunner.linux(ctx); VideoRunner.ensureFfmpeg(l, ctx)
        val f = ctx.workspace.resolve(args.s("path").orEmpty()); if (!f.isFile) return ToolOutput("No such file: ${args.s("path")}", isError = true)
        val inRel = ctx.workspace.rel(f); val probe = VideoRunner.probe(l, inRel)
        val base = FfmpegCommands.safeName(f.nameWithoutExtension)
        fun outName(suffix: String, ext: String): String = FfmpegCommands.safeName(args.s("output")?.takeIf { it.isNotBlank() } ?: "${base}_$suffix.$ext").let { n -> if (n.contains('.')) n else "$n.$ext" }

        var start = FfmpegCommands.parseTime(args.s("start")); var end = FfmpegCommands.parseTime(args.s("end"))
        if (op == "trim") { require(start != null || end != null) { "trim needs start and/or end" } }
        start = start?.coerceIn(0.0, probe.durationSec); end = end?.coerceIn(0.0, probe.durationSec)
        if (start != null && end != null) require(end > start) { "end must be after start" }
        val cut = (end ?: probe.durationSec) - (start ?: 0.0)

        // captions → an .srt Axion writes itself (user text never reaches the command line)
        fun srtFor(durSec: Double): String? {
            val given = args.s("srt_path")?.takeIf { it.isNotBlank() }
            if (given != null) { val sf = ctx.workspace.resolve(given); require(sf.isFile) { "No such file: $given" }; return FfmpegCommands.requireFilterSafe(ctx.workspace.rel(sf)) }
            val text = args.s("captions")?.takeIf { it.isNotBlank() } ?: return null
            val rel = "outputs/${base}_captions.srt"
            File(ctx.workspace.root, rel).writeText(if (text.contains("-->")) text else FfmpegCommands.srt(FfmpegCommands.captionsFromText(text, durSec)))
            return rel
        }

        if (op == "extract_audio") {
            val fmt = (args.s("format") ?: "mp3").lowercase(); val outRel = "outputs/" + outName("audio", if (fmt == "aac") "m4a" else fmt)
            VideoRunner.exec(l, FfmpegCommands.extractAudioCommand(inRel, outRel, fmt), probe.durationSec, "Extracting audio", ctx)
            return finish(ctx, outRel, if (fmt == "wav") "audio/wav" else if (fmt == "mp3") "audio/mpeg" else "audio/mp4", op)
        }
        if (op == "normalize") {
            val outRel = "outputs/" + outName("normalized", "mp4")
            VideoRunner.exec(l, FfmpegCommands.normalizeCommand(inRel, outRel), probe.durationSec, "Fixing loudness", ctx)
            return finish(ctx, outRel, "video/mp4", op)
        }

        val outRel = "outputs/" + outName(when (op) { "reel" -> "reel"; "preset" -> (args.s("preset") ?: "preset").replace(Regex("[^A-Za-z0-9]+"), "_"); else -> op }, "mp4")
        var plan = Plan(inRel, outRel, probe, start = start, durationSec = if (start != null || end != null) cut else null)
        when (op) {
            "trim" -> {}
            "reframe" -> plan = plan.copy(aspect = Aspect.of(args.s("aspect") ?: "9:16") ?: throw IllegalArgumentException("aspect must be 9:16, 1:1 or 16:9"), fit = fitOf(args))
            "captions" -> plan = plan.copy(srtPath = srtFor(cut) ?: throw IllegalArgumentException("captions needs `captions` text or `srt_path`"))
            "reel" -> plan = plan.copy(aspect = Aspect.REEL, fit = fitOf(args), srtPath = srtFor(cut), loudnorm = probe.hasAudio)
            "compress" -> {
                val mb = args.s("target_mb")?.toDoubleOrNull() ?: throw IllegalArgumentException("compress needs target_mb, e.g. 8")
                val kbps = FfmpegCommands.videoKbpsForSize(mb, cut)
                plan = plan.copy(videoKbps = kbps, twoPass = true, width = null).let { if (kbps < 700 && probe.height > 720) it.copy(aspect = if (probe.width >= probe.height) Aspect.LANDSCAPE else Aspect.REEL, width = if (probe.width >= probe.height) 1280 else 720) else it }
            }
            "preset" -> {
                val pr = FfmpegCommands.preset(args.s("preset")) ?: throw IllegalArgumentException("preset must be one of: " + FfmpegCommands.PRESETS.joinToString { it.id })
                val dur = min(cut, (pr.maxSeconds ?: Int.MAX_VALUE).toDouble())
                plan = plan.copy(durationSec = if (dur < probe.durationSec - (start ?: 0.0) - 0.05) dur else plan.durationSec, aspect = pr.aspect, fit = fitOf(args), width = pr.width, videoKbps = pr.videoKbps, audioKbps = pr.audioKbps,
                    fps = pr.fps, loudnorm = pr.loudnorm && probe.hasAudio, srtPath = srtFor(dur))
            }
        }
        val cmds = FfmpegCommands.encodeCommands(plan)
        cmds.forEachIndexed { i, c -> VideoRunner.exec(l, c, plan.durationSec ?: probe.durationSec, if (cmds.size > 1) "Pass ${i + 1}/${cmds.size}" else "Encoding", ctx) }
        return finish(ctx, outRel, "video/mp4", op)
    }

    private fun fitOf(a: JsonObject) = if (a.s("fit")?.trim()?.lowercase() in setOf("blur", "pad", "blurpad")) Fit.BLUR else Fit.FILL

    private suspend fun finish(ctx: ToolContext, outRel: String, mime: String, op: String): ToolOutput {
        val out = File(ctx.workspace.root, outRel)
        if (!out.isFile || out.length() == 0L) throw VideoRunner.Fail("ffmpeg finished but produced no file.")
        val l = VideoRunner.linux(ctx)
        val info = if (mime.startsWith("video/")) runCatching { VideoRunner.probe(l, outRel) }.getOrNull() else null
        val saved = LinuxHub.graph?.app?.let { GallerySaver.save(it, out, mime, out.name) }
        val desc = (info?.let { "${it.width}x${it.height}, ${"%.1f".format(it.durationSec)} s, " } ?: "") + "${out.length() / 1024} KB"
        val text = "Done ($op): $outRel — $desc." + (saved?.let { " Saved to $it." } ?: " Tap Download on the card to save it to the Gallery.")
        return ToolOutput(text, artifact = Artifact(out.name, mime, out.absolutePath, out.length()), exitCode = 0, doneTitle = "Video ready")
    }
}
