#include <jni.h>
#include <algorithm>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

#ifndef POCKETAI_LIGHTWEIGHT32
#include "llama.h"
#include "whisper.h"
#endif

namespace {
std::atomic_bool cancelled{false};
std::mutex inference_mutex;
void fail(JNIEnv *env, const std::string &message) {
    if (!env->ExceptionCheck()) {
        auto error = env->FindClass("java/lang/IllegalStateException");
        env->ThrowNew(error, message.c_str());
    }
}
std::string path_string(JNIEnv *env, jstring input) {
    if (!input) throw std::runtime_error("No model selected.");
    const char *utf = env->GetStringUTFChars(input, nullptr);
    if (!utf) throw std::runtime_error("Unable to read model path.");
    std::string result(utf);
    env->ReleaseStringUTFChars(input, utf);
    return result;
}
std::string utf8_string(JNIEnv *env, jbyteArray input) {
    if (!input) return {};
    const jsize length = env->GetArrayLength(input);
    if (length > 65536) throw std::runtime_error("Input is too long.");
    std::string result(static_cast<size_t>(length), '\0');
    env->GetByteArrayRegion(input, 0, length, reinterpret_cast<jbyte *>(result.data()));
    return result;
}
jbyteArray output(JNIEnv *env, const std::string &text) {
    auto bytes = env->NewByteArray(static_cast<jsize>(text.size()));
    if (bytes) env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(text.size()),
                                      reinterpret_cast<const jbyte *>(text.data()));
    return bytes;
}
#ifndef POCKETAI_LIGHTWEIGHT32
bool should_abort(void *) { return cancelled.load(); }
bool load_progress(float, void *) { return !cancelled.load(); }
void quiet_log(enum ggml_log_level, const char *, void *) {
    // Models can print prompts and local paths; production builds retain no log.
}

std::string chat_prompt(llama_model *model, const std::string &system, const std::string &user) {
    const char *chat_template = llama_model_chat_template(model, nullptr);
    if (!chat_template) {
        return system + "\n\nUser: " + user + "\nAssistant:";
    }
    const llama_chat_message messages[] = {{"system", system.c_str()}, {"user", user.c_str()}};
    std::vector<char> buffer((system.size() + user.size()) * 2 + 1024);
    int length = llama_chat_apply_template(chat_template, messages, 2, true,
                                          buffer.data(), static_cast<int>(buffer.size()));
    if (length < 0) {
        // Some instruction models have no system role. Preserve instructions
        // as ordinary user input while still applying the model's template.
        std::string combined = system + "\n\n" + user;
        const llama_chat_message single[] = {{"user", combined.c_str()}};
        length = llama_chat_apply_template(chat_template, single, 1, true,
                                           buffer.data(), static_cast<int>(buffer.size()));
        if (length < 0) throw std::runtime_error("This model's chat template is unsupported. Import a compatible GGUF instruction model.");
        if (static_cast<size_t>(length) > buffer.size()) {
            buffer.resize(length + 1);
            length = llama_chat_apply_template(chat_template, single, 1, true,
                                               buffer.data(), static_cast<int>(buffer.size()));
        }
    } else if (static_cast<size_t>(length) > buffer.size()) {
        buffer.resize(length + 1);
        length = llama_chat_apply_template(chat_template, messages, 2, true,
                                           buffer.data(), static_cast<int>(buffer.size()));
    }
    if (length < 0) throw std::runtime_error("Cannot prepare model prompt.");
    return std::string(buffer.data(), length);
}
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_pocketai_engine_NativeBridge_cancelNative(JNIEnv *, jobject) {
    cancelled.store(true);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pocketai_engine_NativeBridge_supportsInferenceNative(JNIEnv *, jobject) {
#ifdef POCKETAI_LIGHTWEIGHT32
    return JNI_FALSE;
#else
    return JNI_TRUE;
#endif
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_pocketai_engine_NativeBridge_generateNative(JNIEnv *env, jobject,
    jstring model_path, jbyteArray system_input, jbyteArray user_input,
    jint context_size, jint max_tokens, jint thread_count) {
#ifdef POCKETAI_LIGHTWEIGHT32
    fail(env, "Local model inference requires a 64-bit Android device. Saved-knowledge answers remain available.");
    return nullptr;
#else
    try {
        std::lock_guard<std::mutex> lock(inference_mutex);
        cancelled.store(false);
        static std::once_flag initialization;
        std::call_once(initialization, [] { llama_backend_init(); llama_log_set(quiet_log, nullptr); whisper_log_set(quiet_log, nullptr); });
        const auto model_file = path_string(env, model_path);
        auto model_parameters = llama_model_default_params();
        model_parameters.n_gpu_layers = 0;
        model_parameters.load_mode = LLAMA_LOAD_MODE_MMAP;
        model_parameters.progress_callback = load_progress;
        std::unique_ptr<llama_model, decltype(&llama_model_free)> model(
            llama_model_load_from_file(model_file.c_str(), model_parameters), llama_model_free);
        if (!model) throw std::runtime_error(cancelled ? "Generation cancelled." : "The GGUF model could not be loaded. Check its format, architecture, and free memory.");
        auto context_parameters = llama_context_default_params();
        context_parameters.n_ctx = std::clamp(context_size, 256, 2048);
        context_parameters.n_batch = std::min<uint32_t>(128, context_parameters.n_ctx);
        context_parameters.n_ubatch = std::min<uint32_t>(64, context_parameters.n_batch);
        context_parameters.n_threads = std::clamp(thread_count, 1, 4);
        context_parameters.n_threads_batch = context_parameters.n_threads;
        context_parameters.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
        std::unique_ptr<llama_context, decltype(&llama_free)> context(
            llama_init_from_model(model.get(), context_parameters), llama_free);
        if (!context) throw std::runtime_error("The model needs more memory or is unsupported by this runtime. Try a smaller model and context.");
        llama_set_abort_callback(context.get(), should_abort, nullptr);
        const auto prompt = chat_prompt(model.get(), utf8_string(env, system_input), utf8_string(env, user_input));
        const llama_vocab *vocabulary = llama_model_get_vocab(model.get());
        int needed = llama_tokenize(vocabulary, prompt.c_str(), static_cast<int>(prompt.size()), nullptr, 0, true, true);
        if (needed >= 0 || needed == INT32_MIN) throw std::runtime_error("The model tokenizer rejected the question.");
        needed = -needed;
        const int generation_budget = std::clamp(max_tokens, 16, 256);
        if (needed + 16 >= static_cast<int>(context_parameters.n_ctx)) {
            throw std::runtime_error("The question and retrieved text exceed the selected context window. Use a shorter question or increase context in Settings.");
        }
        std::vector<llama_token> tokens(needed);
        const int count = llama_tokenize(vocabulary, prompt.c_str(), static_cast<int>(prompt.size()), tokens.data(), needed, true, true);
        if (count < 0) throw std::runtime_error("Unable to tokenize model prompt.");
        tokens.resize(count);
        for (size_t offset = 0; offset < tokens.size(); offset += context_parameters.n_batch) {
            if (cancelled.load()) throw std::runtime_error("Generation cancelled.");
            const int batch_count = static_cast<int>(std::min<size_t>(context_parameters.n_batch, tokens.size() - offset));
            auto batch = llama_batch_get_one(tokens.data() + offset, batch_count);
            if (llama_decode(context.get(), batch) != 0) throw std::runtime_error("Model prompt evaluation failed or was cancelled.");
        }
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_init_greedy(), llama_sampler_free);
        std::string response;
        const int budget = std::min<int>(generation_budget, context_parameters.n_ctx - count - 1);
        for (int i = 0; i < budget && !cancelled.load(); ++i) {
            llama_token token = llama_sampler_sample(sampler.get(), context.get(), -1);
            if (llama_vocab_is_eog(vocabulary, token)) break;
            std::vector<char> piece(512);
            int piece_count = llama_token_to_piece(vocabulary, token, piece.data(), piece.size(), 0, false);
            if (piece_count < 0) {
                piece.resize(-piece_count);
                piece_count = llama_token_to_piece(vocabulary, token, piece.data(), piece.size(), 0, false);
            }
            if (piece_count > 0) response.append(piece.data(), piece_count);
            if (llama_decode(context.get(), llama_batch_get_one(&token, 1)) != 0)
                throw std::runtime_error("Model generation failed or was cancelled.");
        }
        if (cancelled.load()) throw std::runtime_error("Generation cancelled.");
        if (response.empty()) throw std::runtime_error("The local model produced no answer. Try a compatible instruction model.");
        return output(env, response);
    } catch (const std::bad_alloc &) { fail(env, "Not enough memory for this model. Use a smaller quantized model."); }
      catch (const std::exception &error) { fail(env, error.what()); }
      catch (...) { fail(env, "Unexpected local inference error."); }
    return nullptr;
#endif
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_pocketai_engine_NativeBridge_transcribeNative(JNIEnv *env, jobject,
    jstring model_path, jfloatArray audio, jstring language_input, jint thread_count) {
#ifdef POCKETAI_LIGHTWEIGHT32
    fail(env, "Whisper speech recognition requires a 64-bit device in this build. Text input remains available.");
    return nullptr;
#else
    try {
        std::lock_guard<std::mutex> lock(inference_mutex);
        cancelled.store(false);
        whisper_log_set(quiet_log, nullptr);
        const auto model_file = path_string(env, model_path);
        auto language = path_string(env, language_input);
        if (language.empty() || language == "auto") language = "auto";
        else if (whisper_lang_id(language.c_str()) < 0) throw std::runtime_error("Whisper does not support the selected language code.");
        if (!audio) throw std::runtime_error("No recorded audio.");
        const auto count = env->GetArrayLength(audio);
        if (count < 1600 || count > 16000 * 60) throw std::runtime_error("Record between 0.1 and 60 seconds of audio.");
        std::vector<float> samples(static_cast<size_t>(count));
        env->GetFloatArrayRegion(audio, 0, count, samples.data());
        auto parameters = whisper_context_default_params();
        parameters.use_gpu = false;
        parameters.flash_attn = false;
        std::unique_ptr<whisper_context, decltype(&whisper_free)> context(
            whisper_init_from_file_with_params(model_file.c_str(), parameters), whisper_free);
        if (!context) throw std::runtime_error("The Whisper model could not be loaded. Verify the model and available memory.");
        if (language != "en" && !whisper_is_multilingual(context.get()))
            throw std::runtime_error("This English-only Whisper model cannot transcribe the selected language. Import a multilingual model without .en in its name.");
        if (cancelled.load()) throw std::runtime_error("Transcription cancelled.");
        auto settings = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        settings.n_threads = std::clamp(thread_count, 1, 4);
        settings.language = language.c_str();
        settings.translate = false;
        settings.no_context = true;
        settings.no_timestamps = true;
        settings.print_progress = false;
        settings.print_realtime = false;
        settings.print_timestamps = false;
        settings.print_special = false;
        settings.greedy.best_of = 1;
        settings.abort_callback = should_abort;
        settings.abort_callback_user_data = nullptr;
        settings.encoder_begin_callback = [](whisper_context *, whisper_state *, void *) { return !cancelled.load(); };
        const int result = whisper_full(context.get(), settings, samples.data(), count);
        if (cancelled.load()) throw std::runtime_error("Transcription cancelled.");
        if (result != 0) throw std::runtime_error("Offline speech recognition failed. Try a shorter recording or a smaller model.");
        std::string transcript;
        for (int i = 0; i < whisper_full_n_segments(context.get()); ++i) {
            const char *segment = whisper_full_get_segment_text(context.get(), i);
            if (segment) transcript += segment;
        }
        if (transcript.empty()) throw std::runtime_error("No speech was recognized. Try moving closer to the microphone.");
        return output(env, transcript);
    } catch (const std::bad_alloc &) { fail(env, "Not enough memory for speech recognition. Import a smaller Whisper model."); }
      catch (const std::exception &error) { fail(env, error.what()); }
      catch (...) { fail(env, "Unexpected offline transcription error."); }
    return nullptr;
#endif
}
