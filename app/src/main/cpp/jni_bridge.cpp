#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <exception>
#include <memory>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "OfflineAI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

llama_model *g_model = nullptr;
int g_n_ctx = 2048;
int g_n_threads = 4;
bool g_backend_ready = false;
std::atomic<bool> g_stop{false};

// Timing of the most recent reply, read by Kotlin through lastStats().
struct Stats {
    int n_prompt = 0;
    int n_gen = 0;
    double prompt_ms = 0.0;
    double gen_ms = 0.0;
};
Stats g_stats;

using Clock = std::chrono::steady_clock;

double ms_since(Clock::time_point t) {
    return std::chrono::duration<double, std::milli>(Clock::now() - t).count();
}

// Records the stats on every exit path of run_generate.
struct StatsGuard {
    Stats st;
    Clock::time_point gen_start;
    bool gen_started = false;
    ~StatsGuard() {
        if (gen_started) st.gen_ms = ms_since(gen_start);
        g_stats = st;
    }
};

struct CtxDeleter {
    void operator()(llama_context *c) const { if (c) llama_free(c); }
};
struct SamplerDeleter {
    void operator()(llama_sampler *s) const { if (s) llama_sampler_free(s); }
};

// Keep error text plain ASCII so NewStringUTF can never choke on it.
std::string ascii_only(const std::string &s) {
    std::string out = s;
    for (char &c : out) {
        if (static_cast<unsigned char>(c) >= 0x80) c = '?';
    }
    return out;
}

jstring to_jstring(JNIEnv *env, const std::string &s) {
    return env->NewStringUTF(ascii_only(s).c_str());
}

std::string bytes_to_string(JNIEnv *env, jbyteArray arr) {
    if (arr == nullptr) return std::string();
    const jsize len = env->GetArrayLength(arr);
    std::string out(static_cast<size_t>(len), '\0');
    if (len > 0) {
        env->GetByteArrayRegion(arr, 0, len, reinterpret_cast<jbyte *>(&out[0]));
    }
    return out;
}

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text) {
    int cap = static_cast<int>(text.size()) + 16;
    std::vector<llama_token> toks(static_cast<size_t>(cap));
    int n = llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()),
                           toks.data(), cap, /*add_special=*/true, /*parse_special=*/true);
    if (n < 0) {
        cap = -n;
        toks.assign(static_cast<size_t>(cap), 0);
        n = llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()),
                           toks.data(), cap, true, true);
        if (n < 0) n = 0;
    }
    toks.resize(static_cast<size_t>(n));
    return toks;
}

// Length of the longest prefix of s that does not end in the middle of a UTF-8 character.
size_t utf8_complete_len(const std::string &s) {
    const size_t n = s.size();
    size_t i = n;
    for (int back = 0; back < 4 && i > 0; back++) {
        const unsigned char c = static_cast<unsigned char>(s[i - 1]);
        if ((c & 0xC0) == 0x80) {  // continuation byte: look further back
            i--;
            continue;
        }
        const size_t need = c >= 0xF0 ? 4 : c >= 0xE0 ? 3 : c >= 0xC0 ? 2 : 1;
        const size_t have = n - (i - 1);
        return have < need ? i - 1 : n;
    }
    return n;
}

// Sends raw UTF-8 bytes to Kotlin. Returns false if Kotlin threw an exception.
bool emit(JNIEnv *env, jobject listener, jmethodID mid, const char *data, size_t len) {
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(len));
    if (arr == nullptr) return false;
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(len), reinterpret_cast<const jbyte *>(data));
    env->CallVoidMethod(listener, mid, arr);
    env->DeleteLocalRef(arr);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    return true;
}

std::string run_generate(JNIEnv *env, jbyteArray jprompt, jint max_new, jfloat temperature,
                         jobject listener) {
    if (g_model == nullptr) return "No model is loaded.";
    g_stop = false;
    StatsGuard sg;

    const llama_vocab *vocab = llama_model_get_vocab(g_model);
    const std::string prompt = bytes_to_string(env, jprompt);
    std::vector<llama_token> toks = tokenize(vocab, prompt);
    const int n_prompt = static_cast<int>(toks.size());
    sg.st.n_prompt = n_prompt;
    if (n_prompt == 0) return "Empty prompt.";
    if (n_prompt >= g_n_ctx - 8) return "This message is too long for the context window.";
    const int n_max = std::min(static_cast<int>(max_new), g_n_ctx - n_prompt - 1);

    // A fresh context per reply keeps this simple: no KV-cache bookkeeping.
    // The Kotlin side resends the whole conversation each turn.
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(g_n_ctx);
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = g_n_threads;
    cp.n_threads_batch = g_n_threads;
    std::unique_ptr<llama_context, CtxDeleter> ctx(llama_init_from_model(g_model, cp));
    if (!ctx) return "Could not create the context (not enough memory?).";

    llama_sampler *raw = llama_sampler_chain_init(llama_sampler_chain_default_params());
    std::unique_ptr<llama_sampler, SamplerDeleter> smpl(raw);
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(raw, llama_sampler_init_greedy());
    } else {
        const uint32_t seed = static_cast<uint32_t>(
            std::chrono::steady_clock::now().time_since_epoch().count());
        llama_sampler_chain_add(raw, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(raw, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(raw, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(raw, llama_sampler_init_dist(seed));
    }

    // Read the prompt in chunks no bigger than the batch size.
    const int chunk = static_cast<int>(cp.n_batch);
    const Clock::time_point t_prefill = Clock::now();
    for (int i = 0; i < n_prompt; i += chunk) {
        if (g_stop) return std::string();
        const int n = std::min(chunk, n_prompt - i);
        if (llama_decode(ctx.get(), llama_batch_get_one(toks.data() + i, n)) != 0) {
            return "Failed while reading the prompt.";
        }
    }
    sg.st.prompt_ms = ms_since(t_prefill);
    sg.gen_start = Clock::now();
    sg.gen_started = true;

    jclass cls = env->GetObjectClass(listener);
    jmethodID mid = env->GetMethodID(cls, "onToken", "([B)V");
    if (mid == nullptr) {
        env->ExceptionClear();
        return "Internal error: listener method not found.";
    }

    std::string pending;
    for (int i = 0; i < n_max && !g_stop; i++) {
        llama_token tok = llama_sampler_sample(raw, ctx.get(), -1);
        if (llama_vocab_is_eog(vocab, tok)) break;
        sg.st.n_gen++;

        char buf[256];
        const int len = llama_token_to_piece(vocab, tok, buf, static_cast<int32_t>(sizeof(buf)),
                                             0, /*special=*/false);
        if (len > 0) pending.append(buf, static_cast<size_t>(len));

        const size_t ready = utf8_complete_len(pending);
        if (ready > 0) {
            if (!emit(env, listener, mid, pending.data(), ready)) return std::string();
            pending.erase(0, ready);
        }

        if (llama_decode(ctx.get(), llama_batch_get_one(&tok, 1)) != 0) {
            return "Generation failed.";
        }
    }
    if (!pending.empty()) emit(env, listener, mid, pending.data(), pending.size());
    return std::string();
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_offlineai_assistant_NativeBridge_systemInfo(JNIEnv *env, jobject /* this */) {
    const char *info = llama_print_system_info();
    return env->NewStringUTF(info != nullptr ? info : "llama.cpp returned no info");
}

// Returns "" on success, otherwise a short error message.
JNIEXPORT jstring JNICALL
Java_com_offlineai_assistant_NativeBridge_loadModel(JNIEnv *env, jobject /* this */,
                                                    jstring jpath, jint n_ctx, jint n_threads) {
    try {
        if (!g_backend_ready) {
            llama_backend_init();
            g_backend_ready = true;
        }
        if (g_model != nullptr) {
            llama_model_free(g_model);
            g_model = nullptr;
        }

        const char *path = env->GetStringUTFChars(jpath, nullptr);
        llama_model_params mp = llama_model_default_params();
        mp.use_mmap = true;
        g_model = llama_model_load_from_file(path, mp);
        env->ReleaseStringUTFChars(jpath, path);

        if (g_model == nullptr) {
            return to_jstring(env, "Could not load the model. The file may be damaged, "
                                   "not a GGUF model, or too big for this phone's memory.");
        }
        g_n_ctx = n_ctx;
        g_n_threads = n_threads;
        return to_jstring(env, "");
    } catch (const std::exception &e) {
        LOGE("loadModel: %s", e.what());
        return to_jstring(env, std::string("Native error while loading: ") + e.what());
    } catch (...) {
        return to_jstring(env, "Unknown native error while loading.");
    }
}

JNIEXPORT void JNICALL
Java_com_offlineai_assistant_NativeBridge_unloadModel(JNIEnv * /* env */, jobject /* this */) {
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
}

JNIEXPORT jint JNICALL
Java_com_offlineai_assistant_NativeBridge_tokenCount(JNIEnv *env, jobject /* this */,
                                                     jbyteArray jtext) {
    try {
        const std::string text = bytes_to_string(env, jtext);
        if (g_model == nullptr) return static_cast<jint>(text.size() / 3);
        return static_cast<jint>(tokenize(llama_model_get_vocab(g_model), text).size());
    } catch (...) {
        return 0;
    }
}

// Returns "" on success (including when the user pressed Stop), otherwise an error message.
JNIEXPORT jstring JNICALL
Java_com_offlineai_assistant_NativeBridge_generate(JNIEnv *env, jobject /* this */,
                                                   jbyteArray jprompt, jint max_new,
                                                   jfloat temperature, jobject listener) {
    std::string err;
    try {
        err = run_generate(env, jprompt, max_new, temperature, listener);
    } catch (const std::exception &e) {
        LOGE("generate: %s", e.what());
        err = std::string("Native error: ") + e.what();
    } catch (...) {
        err = "Unknown native error.";
    }
    return to_jstring(env, err);
}

// "promptTokens;promptMs;generatedTokens;generateMs" for the last reply.
JNIEXPORT jstring JNICALL
Java_com_offlineai_assistant_NativeBridge_lastStats(JNIEnv *env, jobject /* this */) {
    char buf[128];
    std::snprintf(buf, sizeof(buf), "%d;%.0f;%d;%.0f",
                  g_stats.n_prompt, g_stats.prompt_ms, g_stats.n_gen, g_stats.gen_ms);
    return env->NewStringUTF(buf);
}

JNIEXPORT void JNICALL
Java_com_offlineai_assistant_NativeBridge_stop(JNIEnv * /* env */, jobject /* this */) {
    g_stop = true;
}

}  // extern "C"
