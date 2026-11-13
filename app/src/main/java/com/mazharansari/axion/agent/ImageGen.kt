package com.mazharansari.axion.agent

import android.util.Base64
import com.mazharansari.axion.data.Artifact
import com.mazharansari.axion.data.StepKind
import com.mazharansari.axion.llm.Http
import com.mazharansari.axion.llm.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Image generation across whatever is connected, best first:
 *  1. Gemini image models (needs a Gemini key on a tier that allows image output; Google's free tier currently gives 0/day)
 *  2. Hugging Face FLUX.1-schnell (HF token, free monthly credits)
 *  3. Pollinations (no key; verified 1 Nov 2026: works, 1 image / 45 s per IP, small watermark)
 */
class ImageGen(private val keys: () -> Map<String, String>, private val outDir: File) {
    data class Result(val file: File, val provider: String, val model: String? = null)
    private val slow = Http.client.newBuilder().readTimeout(150, TimeUnit.SECONDS).build()

    companion object {
        /**
         * P1-11: Gemini image models, verified 13 Nov 2026 against Google's AI Studio models page and its
         * deprecations page (https://aistudio.google.com/docs/models). gemini-3.8-live is the default Live model
         * (Stable) — see LiveCallProtocol.MODEL. Order: newest first.
         *  - gemini-nano-banana-2.1 — Nano Banana 2.1, New + Stable.
         *  - gemini-3.1-flash-image — Nano Banana 2, listed.
         *  - gemini-3.1-flash-lite-image — Nano Banana 2 Lite, listed.
         *  - gemini-2.5-flash-image — listed, retires 15 Mar 2027 (last resort).
         * REMOVED: gemini-3.1-flash-image-preview — retired 25 Jun 2026 (deprecations page).
         */
        val GEMINI_IMAGE_MODELS = listOf(
            "gemini-nano-banana-2.1", "gemini-3.1-flash-image", "gemini-3.1-flash-lite-image", "gemini-2.5-flash-image",
        )
    }

    suspend fun generate(prompt: String, aspect: String = "1:1"): Result = withContext(Dispatchers.IO) {
        outDir.mkdirs()
        val k = keys()
        val errors = mutableListOf<String>()
        k["gemini"]?.let { key -> runCatching { return@withContext gemini(prompt, aspect, key) }.onFailure { errors += "Gemini: ${it.message}" } }
        k["huggingface"]?.let { key -> runCatching { return@withContext hf(prompt, aspect, key) }.onFailure { errors += "Hugging Face: ${it.message}" } }
        runCatching { return@withContext pollinations(prompt, aspect, k["pollinations"]) }.onFailure { errors += "Pollinations: ${it.message}" }
        error(errors.joinToString(" · "))
    }

    private fun dims(aspect: String) = when (aspect) { "9:16" -> 768 to 1344; "16:9" -> 1344 to 768; "3:4" -> 896 to 1152; "4:3" -> 1152 to 896; else -> 1024 to 1024 }
    private fun newFile(ext: String) = File(outDir, "image_${System.currentTimeMillis()}.$ext")

    private fun gemini(prompt: String, aspect: String, key: String): Result {
        val body = buildJsonObject {
            putJsonArray("contents") { addJsonObject { putJsonArray("parts") { addJsonObject { put("text", prompt) } } } }
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") { add("TEXT"); add("IMAGE") }
                putJsonObject("imageConfig") { put("aspectRatio", aspect) }
            }
        }.toString()
        var last = "no image model"
        for (model in GEMINI_IMAGE_MODELS) {
            val req = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .header("x-goog-api-key", key).post(body.toRequestBody("application/json".toMediaType())).build()
            val (code, text) = slow.newCall(req).execute().use { it.code to it.body?.string().orEmpty() }
            if (code == 429 && "limit: 0" in text) error("this key's tier has no image quota (Google free tier = 0 images/day)")
            if (code != 200) { last = "HTTP $code"; if (code == 429 || code == 404 || code == 400 || code == 403) continue else break }
            val parts = Json.parseToJsonElement(text).jsonObject["candidates"]?.jsonArray?.firstOrNull()?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray.orEmpty()
            val data = parts.firstNotNullOfOrNull { it.jsonObject["inlineData"]?.jsonObject }
                ?: run { last = "no image returned"; null } ?: continue
            val f = newFile(if (data["mimeType"]?.jsonPrimitive?.content?.contains("jpeg") == true) "jpg" else "png")
            f.writeBytes(Base64.decode(data["data"]!!.jsonPrimitive.content, Base64.DEFAULT))
            return Result(f, "Gemini", model)
        }
        error(last)
    }

    private fun hf(prompt: String, aspect: String, key: String): Result {
        val (w, h) = dims(aspect)
        val body = buildJsonObject { put("inputs", prompt); putJsonObject("parameters") { put("width", w); put("height", h) } }.toString()
        val req = Request.Builder().url("https://router.huggingface.co/hf-inference/models/black-forest-labs/FLUX.1-schnell")
            .header("Authorization", "Bearer $key").header("Accept", "image/png")
            .post(body.toRequestBody("application/json".toMediaType())).build()
        slow.newCall(req).execute().use { r ->
            val bytes = r.body?.bytes() ?: ByteArray(0)
            if (!r.isSuccessful || r.header("content-type")?.startsWith("image") != true) error("HTTP ${r.code} ${String(bytes).take(120)}")
            val f = newFile(if (r.header("content-type")!!.contains("jpeg")) "jpg" else "png"); f.writeBytes(bytes)
            return Result(f, "Hugging Face FLUX", "black-forest-labs/FLUX.1-schnell")
        }
    }

    private fun pollinations(prompt: String, aspect: String, key: String?): Result {
        val (w, h) = dims(aspect)
        val url = "https://image.pollinations.ai/prompt/" + URLEncoder.encode(prompt.take(900), "UTF-8").replace("+", "%20") +
            "?width=$w&height=$h&nologo=true&seed=${(Math.random() * 1_000_000).toInt()}"
        val rb = Request.Builder().url(url).header("User-Agent", Http.UA)
        key?.let { rb.header("Authorization", "Bearer $it") }
        slow.newCall(rb.build()).execute().use { r ->
            val bytes = r.body?.bytes() ?: ByteArray(0)
            if (r.code == 402 || r.code == 429) {
                val wait = Regex("Retry after (\\d+)s").find(String(bytes))?.groupValues?.get(1)
                error("free limit, 1 image per 45 s" + (wait?.let { " (retry in ${it}s)" } ?: ""))
            }
            if (!r.isSuccessful || r.header("content-type")?.startsWith("image") != true) error("HTTP ${r.code}")
            val f = newFile("jpg"); f.writeBytes(bytes)
            return Result(f, "Pollinations (free)")
        }
    }
}

class GenerateImageTool(private val gen: ImageGen) : Tool {
    override val spec = ToolSpec(
        "generate_image",
        "Create an image from a text description (product shots, posters, logos, social posts). The image is shown to the user in chat. Write a detailed English prompt.",
        schema { str("prompt", "Detailed visual description"); str("aspect", "1:1, 9:16, 16:9, 3:4 or 4:3 (default 1:1)", required = false) },
    )
    override fun label(args: JsonObject) = StepLabel(StepKind.OTHER, "Generating image", args.s("prompt").orEmpty().take(60))
    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutput {
        val r = runCatching { gen.generate(args.s("prompt").orEmpty(), args.s("aspect") ?: "1:1") }
            .getOrElse { return ToolOutput("Image generation failed: ${it.message}", isError = true, doneTitle = "Image failed") }
        return ToolOutput("Image created with ${r.provider}${r.model?.let { " · $it" } ?: ""} and shown to the user (${ctx.workspace.rel(r.file)}).",
            artifact = Artifact(r.file.name, Mime.of(r.file.name), r.file.absolutePath, r.file.length()), doneTitle = "Generated image")
    }
}
