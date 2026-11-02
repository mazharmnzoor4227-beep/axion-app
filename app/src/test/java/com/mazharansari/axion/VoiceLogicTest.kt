package com.mazharansari.axion

import com.mazharansari.axion.voice.GeminiVoice
import com.mazharansari.axion.voice.LiveVoiceCall
import com.mazharansari.axion.llm.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** Plain JVM tests for v0.3 voice logic (no Android). */
class VoiceLogicTest {
    @Test fun sentenceSplitter() {
        val t = "Aaj Karachi mein dhoop hai, takreeban 31.5 degree. Shaam ko thodi"
        val e1 = LiveVoiceCall.readyEnd(t, 0, true)
        println("first piece: '${t.substring(0, e1)}'")
        assert(t.substring(0, e1) == "Aaj Karachi mein dhoop hai, takreeban 31.5 degree.")
        assert(LiveVoiceCall.readyEnd("Hello there", 0, true) == 0)            // nothing complete yet
        assert(LiveVoiceCall.readyEnd("Hi! How", 0, true) == 3)
        val long = "Try setting a consistent bedtime and wake up time every day, and keep your phone away"
        assert(LiveVoiceCall.readyEnd(long, 0, true) > 0)                      // long first clause starts at a comma
        assert(LiveVoiceCall.readyEnd(long, 0, false) == 0)
        assert(LiveVoiceCall.readyEnd("آج موسم اچھا ہے۔ کیا", 0, true) > 0)   // Urdu full stop
    }

    @Test fun groqPerMinuteLimitIsRateNotQuota() {
        val body = """{"error":{"message":"Rate limit reached for model `openai/gpt-oss-120b` in organization `org_01abc` service tier `on_demand` on tokens per minute (TPM): Limit 8000, Used 4000, Requested 4439. Please try again in 28.17s. Need more tokens?","type":"tokens","code":"rate_limit_exceeded"}}"""
        assert(classifyHttp(429, body) == FailKind.RATE)
        assert(parseRetryMs(body, null) == 28_170L)
        val daily = """{"error":{"message":"Rate limit reached ... on tokens per day (TPD): Limit 200000. Please try again in 7m12s."}}"""
        assert(classifyHttp(429, daily) == FailKind.QUOTA)
        assert(parseRetryMs(daily, null) == 432_000L)
    }

    /** The Kotlin Gemini-voice client against the real Live API (ephemeral token through Tab's proxy). */
    @Test fun geminiVoiceLive() {
        val cap = File("/home/user/tab/.vendor-capability.json"); assumeTrue(cap.exists())
        val o = Json.parseToJsonElement(cap.readText()).jsonObject
        val b = o["base_url"]!!.jsonPrimitive.content; val t = o["token"]!!.jsonPrimitive.content
        val exp = java.time.Instant.now().plusSeconds(1200).toString()
        val req = okhttp3.Request.Builder().url("$b/google-ai/v1alpha/auth_tokens").header("Authorization", "Bearer $t")
            .post(okhttp3.RequestBody.create("application/json".toMediaTypeOrNullCompat(), """{"uses":3,"expireTime":"$exp"}""")).build()
        val name = okhttp3.OkHttpClient().newCall(req).execute().use { Json.parseToJsonElement(it.body!!.string()).jsonObject["name"]!!.jsonPrimitive.content }
        GeminiVoice.endpoint = { "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContentConstrained?access_token=$it" }
        val out = ByteArrayOutputStream()
        val t0 = System.currentTimeMillis(); var first = 0L
        runBlocking { GeminiVoice.speak(listOf(name), "Kore", "Assalam o alaikum! Main aapka Axion hoon. Aaj main aapki kya madad kar sakta hoon?") { if (first == 0L) first = System.currentTimeMillis() - t0; out.write(it) } }
        val secs = out.size() / 48000.0
        println("GEMINI VOICE (Kotlin): first audio ${first} ms, ${"%.1f".format(secs)} s of audio")
        File("/tmp/axion_v03_test").mkdirs()
        File("/tmp/axion_v03_test/kotlin_gemini_kore.pcm").writeBytes(out.toByteArray())
        assert(secs > 2.0)
    }
}

private fun String.toMediaTypeOrNullCompat() = okhttp3.MediaType.Companion.run { this@toMediaTypeOrNullCompat.toMediaTypeOrNull() }
