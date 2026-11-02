
package com.mazharansari.axion

import com.mazharansari.axion.llm.*
import com.mazharansari.axion.local.LlamaNative
import com.mazharansari.axion.local.LocalEngine
import com.mazharansari.axion.local.LocalLlmClient
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Addendum 6, box check: the SAME JNI bridge (app/src/main/cpp/axion_llama.cpp) + the same Kotlin path (LocalEngine →
 * LocalLlmClient) the phone runs, built for x86-64 here and run on a tiny GGUF (SmolLM2-135M). Skipped where that host
 * build isn't present (it lives outside the repo: /tmp/llhost).
 */
class LocalLlamaJniTest {
    private val lib = File(System.getProperty("axion.llhost", "/tmp/llhost") + "/out/libaxionllama.so")
    private val model = File(System.getProperty("axion.gguf", "/tmp/s2dl/smol.gguf"))

    @Test fun runsTinyGgufThroughLocalLlmClient() = runBlocking {
        assumeTrue("host llama build missing", lib.isFile && model.isFile)
        LocalEngine.libDir = File(lib.parentFile.parentFile, "b/bin").absolutePath
        LocalEngine.libPath = lib.absolutePath
        val client = LocalLlmClient(model, ctx = 2048, maxTokens = 48)
        val t0 = System.currentTimeMillis()
        val events = client.stream(LlmRequest("local", listOf(SystemItem("You are a helpful assistant."), UserItem("What is the capital of France? Answer in one word.")))).toList()
        val ms = System.currentTimeMillis() - t0
        val text = events.filterIsInstance<LlmEvent.Text>().joinToString("") { it.delta }
        println("LLAMA systemInfo: " + LlamaNative.systemInfo().trim())
        println("LLAMA reply (${ms} ms, ${events.size} events): " + text.replace("\n", " ⏎ "))
        assertTrue("finished", events.any { it is LlmEvent.Finished })
        assertTrue("produced text", text.isNotBlank())
        assertTrue("answer mentions Paris: $text", text.contains("Paris", ignoreCase = true))
        // chat template from the GGUF (SmolLM2 = ChatML)
        val h = LocalEngine.session(model, 2048)
        val tpl = LlamaNative.applyTemplate(h, arrayOf("user"), arrayOf("hi"))
        assertTrue(tpl, tpl.contains("<|im_start|>user") && tpl.trimEnd().endsWith("<|im_start|>assistant"))
        // stop works mid-generation
        var n = 0
        val code = LlamaNative.generate(h, tpl, 200, 0.7f, object : LlamaNative.TokenCallback { override fun onToken(piece: String): Boolean { n++; return n < 5 } })
        assertTrue("stopped after 5 tokens (n=$n code=$code)", n == 5)
    }
}
