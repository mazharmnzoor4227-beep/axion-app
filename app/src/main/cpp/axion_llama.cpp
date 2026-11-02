// Axion "Local model": a small JNI bridge over llama.cpp (on-device GGUF). The same file is built for the phone (arm64, via the
// app's CMake) and for the x86 test box (LocalLlamaHostTest), so the code path tested on the box is the one shipped.
#include <jni.h>
#include <string>
#include <vector>
#include <atomic>
#include <mutex>
#include <cstring>
#include "llama.h"
#include "ggml-backend.h"

namespace {
struct Session {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    std::atomic<bool> stop{false};
    std::mutex lock;
    int n_ctx = 0;
};
std::string g_last_error;
std::once_flag g_backend_once;

std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

// Java's NewStringUTF wants modified UTF-8; build the string from UTF-16 instead so emoji and Urdu survive.
jstring utf8ToJ(JNIEnv *env, const std::string &s) {
    std::u16string u;
    for (size_t i = 0; i < s.size();) {
        unsigned char c = (unsigned char) s[i];
        uint32_t cp; int n;
        if (c < 0x80) { cp = c; n = 1; } else if ((c >> 5) == 6) { cp = c & 0x1f; n = 2; } else if ((c >> 4) == 14) { cp = c & 0x0f; n = 3; } else if ((c >> 3) == 30) { cp = c & 0x07; n = 4; } else { i++; continue; }
        if (i + n > s.size()) break;
        for (int k = 1; k < n; k++) cp = (cp << 6) | ((unsigned char) s[i + k] & 0x3f);
        i += n;
        if (cp >= 0x10000) { cp -= 0x10000; u.push_back((char16_t) (0xD800 + (cp >> 10))); u.push_back((char16_t) (0xDC00 + (cp & 0x3ff))); }
        else u.push_back((char16_t) cp);
    }
    return env->NewString((const jchar *) u.data(), (jsize) u.size());
}

// How many bytes at the end of s form an incomplete UTF-8 sequence (held back until the next token completes it).
size_t incompleteTail(const std::string &s) {
    size_t n = s.size();
    for (size_t back = 1; back <= 4 && back <= n; back++) {
        unsigned char c = (unsigned char) s[n - back];
        if ((c & 0xC0) == 0x80) continue;           // continuation byte
        int need = (c < 0x80) ? 1 : ((c >> 5) == 6) ? 2 : ((c >> 4) == 14) ? 3 : ((c >> 3) == 30) ? 4 : 1;
        return need > (int) back ? back : 0;
    }
    return 0;
}

void quietLog(ggml_log_level level, const char *text, void *) {
    if (level == GGML_LOG_LEVEL_ERROR && text) g_last_error = text;
}
}

extern "C" {

JNIEXPORT void JNICALL Java_com_mazharansari_axion_local_LlamaNative_init(JNIEnv *env, jclass, jstring libDir) {
    std::string dir = jstr(env, libDir);
    std::call_once(g_backend_once, [&] {
        llama_log_set(quietLog, nullptr);
        // CPU backend variants (armv8.0 / armv8.2+dotprod / +fp16 …) are separate .so files; ggml picks the best one this CPU runs.
        if (!dir.empty()) ggml_backend_load_all_from_path(dir.c_str()); else ggml_backend_load_all();
        llama_backend_init();
    });
}

JNIEXPORT jstring JNICALL Java_com_mazharansari_axion_local_LlamaNative_lastError(JNIEnv *env, jclass) {
    return env->NewStringUTF(g_last_error.c_str());
}

JNIEXPORT jstring JNICALL Java_com_mazharansari_axion_local_LlamaNative_systemInfo(JNIEnv *env, jclass) {
    std::string out;
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        auto *d = ggml_backend_dev_get(i);
        out += std::string(ggml_backend_dev_name(d)) + " (" + ggml_backend_dev_description(d) + ")\n";
    }
    out += llama_print_system_info();
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jlong JNICALL Java_com_mazharansari_axion_local_LlamaNative_load(JNIEnv *env, jclass, jstring jpath, jint nCtx, jint nThreads) {
    std::string path = jstr(env, jpath);
    g_last_error.clear();
    auto mp = llama_model_default_params();
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;   // map the GGUF instead of copying it into RAM
    llama_model *model = llama_model_load_from_file(path.c_str(), mp);
    if (!model) { if (g_last_error.empty()) g_last_error = "could not load the model file"; return 0; }
    auto cp = llama_context_default_params();
    cp.n_ctx = nCtx > 0 ? nCtx : 4096;
    cp.n_batch = 512;
    cp.n_threads = nThreads > 0 ? nThreads : 4;
    cp.n_threads_batch = cp.n_threads;
    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) { llama_model_free(model); if (g_last_error.empty()) g_last_error = "not enough memory for this model"; return 0; }
    auto *s = new Session();
    s->model = model; s->ctx = ctx; s->vocab = llama_model_get_vocab(model); s->n_ctx = (int) llama_n_ctx(ctx);
    return (jlong) s;
}

JNIEXPORT void JNICALL Java_com_mazharansari_axion_local_LlamaNative_free(JNIEnv *, jclass, jlong h) {
    auto *s = (Session *) h; if (!s) return;
    s->stop = true;
    std::lock_guard<std::mutex> g(s->lock);
    llama_free(s->ctx); llama_model_free(s->model);
    delete s;
}

JNIEXPORT void JNICALL Java_com_mazharansari_axion_local_LlamaNative_stop(JNIEnv *, jclass, jlong h) {
    auto *s = (Session *) h; if (s) s->stop = true;
}

JNIEXPORT jint JNICALL Java_com_mazharansari_axion_local_LlamaNative_contextSize(JNIEnv *, jclass, jlong h) {
    auto *s = (Session *) h; return s ? s->n_ctx : 0;
}

/** The model's own chat template applied to the messages; ChatML if the model has none (or it can't be applied). */
JNIEXPORT jstring JNICALL Java_com_mazharansari_axion_local_LlamaNative_applyTemplate(JNIEnv *env, jclass, jlong h, jobjectArray roles, jobjectArray contents) {
    auto *s = (Session *) h;
    jsize n = env->GetArrayLength(roles);
    std::vector<std::string> r(n), c(n);
    std::vector<llama_chat_message> msgs(n);
    size_t total = 0;
    for (jsize i = 0; i < n; i++) {
        r[i] = jstr(env, (jstring) env->GetObjectArrayElement(roles, i));
        c[i] = jstr(env, (jstring) env->GetObjectArrayElement(contents, i));
        total += c[i].size() + 32;
    }
    for (jsize i = 0; i < n; i++) msgs[i] = {r[i].c_str(), c[i].c_str()};
    const char *tmpl = s ? llama_model_chat_template(s->model, nullptr) : nullptr;
    std::string out;
    if (tmpl) {
        std::vector<char> buf(total * 2 + 1024);
        int len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int32_t) buf.size());
        if (len > (int) buf.size()) { buf.resize(len + 1); len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int32_t) buf.size()); }
        if (len > 0) out.assign(buf.data(), len);
    }
    if (out.empty()) {
        for (jsize i = 0; i < n; i++) out += "<|im_start|>" + r[i] + "\n" + c[i] + "<|im_end|>\n";
        out += "<|im_start|>assistant\n";
    }
    return utf8ToJ(env, out);
}

/**
 * Generates up to maxTokens after [prompt], calling callback.onToken(String) with each complete UTF-8 piece; returns the
 * number of tokens generated, or a negative code: -1 prompt too long for the context, -2 decode failed.
 */
JNIEXPORT jint JNICALL Java_com_mazharansari_axion_local_LlamaNative_generate(JNIEnv *env, jclass, jlong h, jstring jprompt, jint maxTokens, jfloat temp, jobject cb) {
    auto *s = (Session *) h; if (!s) return -2;
    std::lock_guard<std::mutex> g(s->lock);
    s->stop = false;
    std::string prompt = jstr(env, jprompt);
    jclass cbc = env->GetObjectClass(cb);
    jmethodID onToken = env->GetMethodID(cbc, "onToken", "(Ljava/lang/String;)Z");

    llama_memory_clear(llama_get_memory(s->ctx), true);
    int n = -llama_tokenize(s->vocab, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, true, true);
    std::vector<llama_token> toks(n);
    if (llama_tokenize(s->vocab, prompt.c_str(), (int32_t) prompt.size(), toks.data(), n, true, true) < 0) return -2;
    if ((int) toks.size() + 8 >= s->n_ctx) return -1;

    for (size_t i = 0; i < toks.size(); i += 512) {
        if (s->stop) return 0;
        int chunk = (int) std::min<size_t>(512, toks.size() - i);
        if (llama_decode(s->ctx, llama_batch_get_one(toks.data() + i, chunk)) != 0) return -2;
    }

    auto sp = llama_sampler_chain_default_params();
    llama_sampler *smpl = llama_sampler_chain_init(sp);
    if (temp <= 0.0f) llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));   // top-k first: penalties scan the candidates
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(s->vocab), 64, 1.1f, 0.0f, 0.0f));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }
    std::string pending;
    int produced = 0, used = (int) toks.size();
    char piece[256];
    while (produced < maxTokens && !s->stop && used < s->n_ctx - 1) {
        llama_token t = llama_sampler_sample(smpl, s->ctx, -1);
        if (llama_vocab_is_eog(s->vocab, t)) break;
        int len = llama_token_to_piece(s->vocab, t, piece, sizeof(piece), 0, false);
        if (len > 0) pending.append(piece, len);
        size_t keep = incompleteTail(pending);
        if (pending.size() > keep) {
            std::string ready = pending.substr(0, pending.size() - keep);
            pending.erase(0, pending.size() - keep);
            jstring js = utf8ToJ(env, ready);
            jboolean go = env->CallBooleanMethod(cb, onToken, js);
            env->DeleteLocalRef(js);
            if (!go) break;
        }
        produced++;
        if (llama_decode(s->ctx, llama_batch_get_one(&t, 1)) != 0) { llama_sampler_free(smpl); return -2; }
        used++;
    }
    llama_sampler_free(smpl);
    return produced;
}
}
