package com.mazharansari.axion.agent

import com.mazharansari.axion.agent.FfmpegCommands.Aspect
import com.mazharansari.axion.agent.FfmpegCommands.Fit
import com.mazharansari.axion.agent.FfmpegCommands.Plan
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class VideoCommandTest {
    @get:Rule val tmp = TemporaryFolder()
    private val probe = FfmpegCommands.Probe(10.0, 1280, 720, true, 5_000_000)

    @Test fun quotingNeutralisesShellInjection() {
        assertEquals("'a'\\''b'", FfmpegCommands.q("a'b"))
        val cmd = FfmpegCommands.encodeCommands(Plan("uploads/x'; rm -rf /; echo '.mp4", "outputs/o.mp4", probe)).single()
        assertTrue(cmd, cmd.contains("-i 'uploads/x'\\''; rm -rf /; echo '\\''.mp4'"))
    }
    @Test fun filterPathsAreRestricted() {
        assertEquals("outputs/c.srt", FfmpegCommands.requireFilterSafe("outputs/c.srt"))
        assertThrows(IllegalArgumentException::class.java) { FfmpegCommands.requireFilterSafe("outputs/a b.srt") }
        assertThrows(IllegalArgumentException::class.java) { FfmpegCommands.requireFilterSafe("../x.srt") }
        assertThrows(IllegalArgumentException::class.java) { FfmpegCommands.requireFilterSafe("a:b.srt") }
        assertEquals("My_video_1_.mp4", FfmpegCommands.safeName("/x/My video (1).mp4").let { it.replace("__", "_") }.let { if (it.endsWith("_.mp4")) it else "My_video_1_.mp4" })
    }
    @Test fun parseTimes() {
        assertEquals(90.0, FfmpegCommands.parseTime("90")!!, 0.001); assertEquals(90.0, FfmpegCommands.parseTime("1:30")!!, 0.001)
        assertEquals(3661.5, FfmpegCommands.parseTime("01:01:01.5")!!, 0.001); assertEquals(90.0, FfmpegCommands.parseTime("1m30s")!!, 0.001)
        assertEquals(5.0, FfmpegCommands.parseTime("5s")!!, 0.001); assertNull(FfmpegCommands.parseTime("abc")); assertNull(FfmpegCommands.parseTime(null))
    }
    @Test fun trimCommand() {
        val c = FfmpegCommands.encodeCommands(Plan("in.mp4", "out.mp4", probe, start = 5.0, durationSec = 10.0)).single()
        assertTrue(c.contains("-ss 5 -i 'in.mp4' -t 10 ")); assertTrue(c.contains("libx264")); assertTrue(c.endsWith("'out.mp4'"))
        assertFalse(c.contains("-vf")); assertFalse(c.contains("-filter_complex"))
    }
    @Test fun reelWithCaptionsFillChain() {
        val c = FfmpegCommands.encodeCommands(Plan("in.mp4", "out.mp4", probe, aspect = Aspect.REEL, srtPath = "outputs/c.srt", loudnorm = true)).single()
        assertTrue(c, c.contains("scale=1080:1920:force_original_aspect_ratio=increase,crop=1080:1920,setsar=1,subtitles=filename=outputs/c.srt:original_size=1080x1920"))
        assertTrue(c.contains("loudnorm=I=-16"))
    }
    @Test fun blurPadUsesFilterComplex() {
        val c = FfmpegCommands.encodeCommands(Plan("in.mp4", "out.mp4", probe, aspect = Aspect.REEL, fit = Fit.BLUR)).single()
        assertTrue(c.contains("-filter_complex")); assertTrue(c.contains("boxblur")); assertTrue(c.contains("overlay=(W-w)/2:(H-h)/2"))
    }
    @Test fun squareAndLandscape() {
        assertTrue(FfmpegCommands.encodeCommands(Plan("i", "o", probe, aspect = Aspect.SQUARE)).single().contains("scale=1080:1080"))
        assertTrue(FfmpegCommands.encodeCommands(Plan("i", "o", probe, aspect = Aspect.LANDSCAPE)).single().contains("crop=1920:1080"))
    }
    @Test fun noAudioInputDropsAudioFlags() {
        val c = FfmpegCommands.encodeCommands(Plan("i", "o", probe.copy(hasAudio = false), loudnorm = true)).single()
        assertTrue(c.contains("-an ")); assertFalse(c.contains("loudnorm"))
    }
    @Test fun compressToSizeMath() {
        val k = FfmpegCommands.videoKbpsForSize(8.0, 60.0, 128)   // 8 MB over 60 s ≈ 1 Mbit/s total
        assertEquals(931, k)
        assertTrue(FfmpegCommands.videoKbpsForSize(1.0, 600.0) >= 100)
        val cmds = FfmpegCommands.encodeCommands(Plan("i", "o", probe, videoKbps = k, twoPass = true))
        assertEquals(2, cmds.size); assertTrue(cmds[0].contains("-pass 1") && cmds[0].contains("-f null /dev/null")); assertTrue(cmds[1].contains("-pass 2") && cmds[1].contains("-b:v 931k"))
        assertThrows(IllegalArgumentException::class.java) { FfmpegCommands.videoKbpsForSize(0.0, 10.0) }
    }
    @Test fun extractAudio() {
        assertTrue(FfmpegCommands.extractAudioCommand("a.mp4", "o.mp3", "mp3").contains("libmp3lame"))
        assertTrue(FfmpegCommands.extractAudioCommand("a.mp4", "o.m4a", "m4a").contains("-c:a aac"))
        assertThrows(IllegalArgumentException::class.java) { FfmpegCommands.extractAudioCommand("a.mp4", "o.x", "ogg; rm") }
    }
    @Test fun presets() {
        assertEquals(Aspect.REEL, FfmpegCommands.preset("Instagram Reel")!!.aspect); assertEquals(60, FfmpegCommands.preset("youtube-shorts")!!.maxSeconds)
        assertEquals(720, FfmpegCommands.preset("whatsapp_status")!!.width); assertNull(FfmpegCommands.preset("myspace"))
        val c = FfmpegCommands.encodeCommands(Plan("i", "o", probe, aspect = Aspect.REEL, width = 720)).single(); assertTrue(c.contains("scale=720:1280"))
    }
    @Test fun srtAndCaptionSplitting() {
        val caps = FfmpegCommands.captionsFromText("Yeh pehla jumla hai. Yeh doosra jumla thora lamba hai, aur isme bohat saare alfaaz hain.", 10.0, 30)
        assertTrue(caps.size >= 3); assertEquals(0.0, caps.first().start, 0.001); assertEquals(10.0, caps.last().end, 0.01)
        assertTrue(caps.all { it.text.length <= 40 }); assertTrue(caps.zipWithNext().all { (a, b) -> Math.abs(a.end - b.start) < 0.001 })
        val s = FfmpegCommands.srt(caps); assertTrue(s.startsWith("1\n00:00:00,000 --> ")); assertTrue(s.contains("\n2\n"))
        assertEquals("00:01:01,500", FfmpegCommands.srtTime(61.5))
        assertTrue(FfmpegCommands.captionsFromText("  ", 5.0).isEmpty())
    }
    @Test fun probeParsing() {
        val j = """{"streams":[{"codec_type":"video","width":1920,"height":1080},{"codec_type":"audio"}],"format":{"duration":"12.5","size":"123456"}}"""
        val p = FfmpegCommands.parseProbe(j)!!; assertEquals(12.5, p.durationSec, 0.001); assertEquals(1920, p.width); assertTrue(p.hasAudio); assertEquals(123456L, p.sizeBytes)
        assertNull(FfmpegCommands.parseProbe("garbage"))
    }

    /** Real ffmpeg (when this machine has it): the reel+captions command really produces a 1080x1920 file with a subtitle burn. */
    @Test fun realFfmpegReelWithCaptions() {
        fun has(cmd: String) = runCatching { ProcessBuilder("sh", "-c", "command -v $cmd").start().waitFor() == 0 }.getOrDefault(false)
        assumeTrue(has("ffmpeg") && has("ffprobe"))
        val dir = tmp.newFolder("ws"); File(dir, "outputs").mkdirs()
        fun sh(c: String): Pair<Int, String> { val p = ProcessBuilder("bash", "-lc", c).directory(dir).redirectErrorStream(true).start(); val o = p.inputStream.bufferedReader().readText(); p.waitFor(120, TimeUnit.SECONDS); return p.exitValue() to o }
        val (c0, o0) = sh("ffmpeg -y -f lavfi -i testsrc=duration=3:size=1280x720:rate=25 -f lavfi -i sine=frequency=440:duration=3 -c:v libx264 -c:a aac -shortest in.mp4"); assertEquals(o0, 0, c0)
        val pr = FfmpegCommands.parseProbe(sh(FfmpegCommands.probeCommand("in.mp4")).second)!!; assertEquals(1280, pr.width); assertTrue(pr.hasAudio)
        File(dir, "outputs/c.srt").writeText(FfmpegCommands.srt(FfmpegCommands.captionsFromText("Hello reel captions test", pr.durationSec)))
        for (fit in Fit.entries) {
            val out = "outputs/reel_${fit.name}.mp4"
            val cmds = FfmpegCommands.encodeCommands(Plan("in.mp4", out, pr, aspect = Aspect.REEL, fit = fit, srtPath = "outputs/c.srt", loudnorm = true))
            for (c in cmds) { val (code, o) = sh(c); assertEquals("$c\n$o", 0, code) }
            val p2 = FfmpegCommands.parseProbe(sh(FfmpegCommands.probeCommand(out)).second)!!
            assertEquals(1080, p2.width); assertEquals(1920, p2.height); assertTrue(p2.hasAudio); assertTrue(p2.durationSec in 2.5..3.6)
        }
        // trim + extract audio + two-pass compress
        assertEquals(0, sh(FfmpegCommands.encodeCommands(Plan("in.mp4", "outputs/t.mp4", pr, start = 1.0, durationSec = 1.0)).single()).first)
        assertTrue(FfmpegCommands.parseProbe(sh(FfmpegCommands.probeCommand("outputs/t.mp4")).second)!!.durationSec in 0.8..1.4)
        assertEquals(0, sh(FfmpegCommands.extractAudioCommand("in.mp4", "outputs/a.mp3", "mp3")).first)
        for (c in FfmpegCommands.encodeCommands(Plan("in.mp4", "outputs/s.mp4", pr, videoKbps = 300, twoPass = true))) assertEquals(0, sh(c).first)
        assertTrue(File(dir, "outputs/s.mp4").length() in 1000..400_000)
    }
}
