#include <jni.h>
#include <sentencepiece_processor.h>
#include <cstdint>
#include <fstream>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

namespace {
constexpr size_t kMaxInput = 64 * 1024;
constexpr size_t kMaxOutput = 1024 * 1024;
constexpr size_t kMaxModel = 16 * 1024 * 1024;
constexpr size_t kMaxTokens = 16 * 1024;
constexpr size_t kMaxHandles = 4;
struct Model {
    sentencepiece::SentencePieceProcessor processor;
    std::mutex mutex;
};
std::mutex models_mutex;
std::unordered_map<jlong, std::shared_ptr<Model>> models;
jlong next_handle = 1;

void raise(JNIEnv *env, const char *kind, const std::string &message) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass(kind), message.c_str());
}
template <typename Result, typename Function>
Result guarded(JNIEnv *env, Result fallback, Function block) {
    try { return block(); }
    catch (const std::invalid_argument &error) { raise(env, "java/lang/IllegalArgumentException", error.what()); }
    catch (const std::bad_alloc &) { raise(env, "java/lang/IllegalStateException", "Not enough memory for the tokenizer."); }
    catch (const std::exception &error) { raise(env, "java/lang/IllegalStateException", error.what()); }
    catch (...) { raise(env, "java/lang/IllegalStateException", "Unexpected tokenizer failure."); }
    return fallback;
}
bool valid_utf8(const std::string &text) {
    size_t index = 0;
    while (index < text.size()) {
        const auto first = static_cast<unsigned char>(text[index++]);
        if (first < 0x80) continue;
        unsigned count; uint32_t value; uint32_t minimum;
        if ((first & 0xe0) == 0xc0) { count = 1; value = first & 0x1f; minimum = 0x80; }
        else if ((first & 0xf0) == 0xe0) { count = 2; value = first & 0x0f; minimum = 0x800; }
        else if ((first & 0xf8) == 0xf0) { count = 3; value = first & 7; minimum = 0x10000; }
        else return false;
        if (index + count > text.size()) return false;
        for (unsigned part = 0; part < count; ++part) {
            const auto byte = static_cast<unsigned char>(text[index++]);
            if ((byte & 0xc0) != 0x80) return false;
            value = (value << 6) | (byte & 0x3f);
        }
        if (value < minimum || value > 0x10ffff || (value >= 0xd800 && value <= 0xdfff)) return false;
    }
    return true;
}
std::string read_bytes(JNIEnv *env, jbyteArray input, size_t maximum = kMaxInput) {
    if (!input) throw std::invalid_argument("Tokenizer input must not be null.");
    const auto count = env->GetArrayLength(input);
    if (static_cast<size_t>(count) > maximum) throw std::invalid_argument("Tokenizer UTF-8 input exceeds its size limit.");
    std::string result(static_cast<size_t>(count), '\0');
    if (count) env->GetByteArrayRegion(input, 0, count, reinterpret_cast<jbyte *>(result.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("Unable to read tokenizer input.");
    if (!valid_utf8(result)) throw std::invalid_argument("Tokenizer input is not valid UTF-8.");
    return result;
}
jbyteArray bytes(JNIEnv *env, const std::string &value) {
    if (value.size() > kMaxOutput) throw std::invalid_argument("Tokenizer output exceeds 1 MiB.");
    auto result = env->NewByteArray(static_cast<jsize>(value.size()));
    if (!result) throw std::bad_alloc();
    if (!value.empty()) env->SetByteArrayRegion(result, 0, static_cast<jsize>(value.size()), reinterpret_cast<const jbyte *>(value.data()));
    return result;
}
std::shared_ptr<Model> acquire(jlong handle) {
    std::lock_guard<std::mutex> lock(models_mutex);
    const auto item = models.find(handle);
    if (item == models.end()) throw std::runtime_error("This tokenizer handle is closed or invalid.");
    return item->second;
}
void ok(const sentencepiece::util::Status &status) {
    if (!status.ok()) throw std::runtime_error("SentencePiece could not process this input: " + status.ToString());
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_pocketai_engine_NativeTokenizerBridge_openNative(JNIEnv *env, jobject, jbyteArray path_input) {
    return guarded<jlong>(env, 0, [&] {
        const auto path = read_bytes(env, path_input);
        if (path.empty() || path.find('\0') != std::string::npos) throw std::invalid_argument("Invalid local tokenizer path.");
        {
            std::lock_guard<std::mutex> lock(models_mutex);
            if (models.size() >= kMaxHandles) throw std::runtime_error("Close an unused tokenizer before opening another (maximum four).");
        }
        std::ifstream file(path, std::ios::binary | std::ios::ate);
        const auto size = file ? file.tellg() : std::streampos(-1);
        if (size <= 0 || size > static_cast<std::streamoff>(kMaxModel)) throw std::invalid_argument("Tokenizer model must be a readable file below 16 MiB.");
        auto model = std::make_shared<Model>();
        ok(model->processor.Load(path));
        if (model->processor.GetPieceSize() <= 0 || model->processor.GetPieceSize() > 500000) throw std::invalid_argument("Unsupported tokenizer vocabulary size.");
        std::lock_guard<std::mutex> lock(models_mutex);
        if (models.size() >= kMaxHandles) throw std::runtime_error("Tokenizer handle limit reached.");
        if (next_handle == std::numeric_limits<jlong>::max()) throw std::runtime_error("Tokenizer handle counter exhausted.");
        const auto handle = next_handle++;
        models.emplace(handle, std::move(model));
        return handle;
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_pocketai_engine_NativeTokenizerBridge_closeNative(JNIEnv *env, jobject, jlong handle) {
    guarded<int>(env, 0, [&] { std::lock_guard<std::mutex> lock(models_mutex); models.erase(handle); return 0; });
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_pocketai_engine_NativeTokenizerBridge_infoNative(JNIEnv *env, jobject, jlong handle) {
    return guarded<jintArray>(env, nullptr, [&] {
        auto model = acquire(handle); std::lock_guard<std::mutex> lock(model->mutex);
        const jint values[] = {model->processor.GetPieceSize(), model->processor.unk_id(), model->processor.bos_id(), model->processor.eos_id(), model->processor.pad_id()};
        auto result = env->NewIntArray(5); if (!result) throw std::bad_alloc();
        env->SetIntArrayRegion(result, 0, 5, values); return result;
    });
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_pocketai_engine_NativeTokenizerBridge_encodeIdsNative(JNIEnv *env, jobject, jlong handle, jbyteArray input) {
    return guarded<jintArray>(env, nullptr, [&] {
        auto text = read_bytes(env, input); auto model = acquire(handle); std::lock_guard<std::mutex> lock(model->mutex);
        std::vector<int> ids; ok(model->processor.Encode(text, &ids));
        if (ids.size() > kMaxTokens) throw std::invalid_argument("Tokenizer produced too many tokens.");
        auto result = env->NewIntArray(static_cast<jsize>(ids.size())); if (!result) throw std::bad_alloc();
        if (!ids.empty()) env->SetIntArrayRegion(result, 0, static_cast<jsize>(ids.size()), ids.data());
        return result;
    });
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_pocketai_engine_NativeTokenizerBridge_decodeIdsNative(JNIEnv *env, jobject, jlong handle, jintArray input) {
    return guarded<jbyteArray>(env, nullptr, [&] {
        if (!input || env->GetArrayLength(input) > static_cast<jsize>(kMaxTokens)) throw std::invalid_argument("Invalid raw tokenizer ID array.");
        auto model = acquire(handle); std::lock_guard<std::mutex> lock(model->mutex);
        std::vector<int> ids(static_cast<size_t>(env->GetArrayLength(input)));
        if (!ids.empty()) env->GetIntArrayRegion(input, 0, static_cast<jsize>(ids.size()), ids.data());
        size_t size = 0;
        for (const auto id : ids) {
            if (id < 0 || id >= model->processor.GetPieceSize()) throw std::invalid_argument("Expected raw SentencePiece IDs, not offset/model-vocabulary IDs.");
            size += model->processor.IdToPiece(id).size();
            if (size > kMaxOutput) throw std::invalid_argument("Decoded tokenizer pieces exceed 1 MiB.");
        }
        std::string result; ok(model->processor.Decode(ids, &result)); return bytes(env, result);
    });
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_pocketai_engine_NativeTokenizerBridge_encodePiecesNative(JNIEnv *env, jobject, jlong handle, jbyteArray input) {
    return guarded<jobjectArray>(env, nullptr, [&] {
        auto text = read_bytes(env, input); auto model = acquire(handle); std::lock_guard<std::mutex> lock(model->mutex);
        std::vector<std::string> pieces; ok(model->processor.Encode(text, &pieces));
        if (pieces.size() > kMaxTokens) throw std::invalid_argument("Tokenizer produced too many pieces.");
        auto byte_array_class = env->FindClass("[B"); if (!byte_array_class) throw std::runtime_error("Unable to create tokenizer output.");
        auto result = env->NewObjectArray(static_cast<jsize>(pieces.size()), byte_array_class, nullptr);
        env->DeleteLocalRef(byte_array_class); if (!result) throw std::bad_alloc();
        size_t size = 0;
        for (size_t i = 0; i < pieces.size(); ++i) {
            size += pieces[i].size(); if (size > kMaxOutput) throw std::invalid_argument("Tokenizer pieces exceed 1 MiB.");
            auto value = bytes(env, pieces[i]); env->SetObjectArrayElement(result, static_cast<jsize>(i), value); env->DeleteLocalRef(value);
            if (env->ExceptionCheck()) throw std::runtime_error("Unable to return tokenizer pieces.");
        }
        return result;
    });
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_pocketai_engine_NativeTokenizerBridge_decodePiecesNative(JNIEnv *env, jobject, jlong handle, jobjectArray input) {
    return guarded<jbyteArray>(env, nullptr, [&] {
        if (!input || env->GetArrayLength(input) > static_cast<jsize>(kMaxTokens)) throw std::invalid_argument("Invalid tokenizer piece array.");
        std::vector<std::string> pieces; size_t size = 0;
        for (jsize index = 0; index < env->GetArrayLength(input); ++index) {
            auto item = static_cast<jbyteArray>(env->GetObjectArrayElement(input, index));
            auto value = read_bytes(env, item); env->DeleteLocalRef(item);
            size += value.size(); if (size > kMaxOutput) throw std::invalid_argument("Tokenizer pieces exceed 1 MiB.");
            pieces.push_back(std::move(value));
        }
        auto model = acquire(handle); std::lock_guard<std::mutex> lock(model->mutex);
        std::string result; ok(model->processor.Decode(pieces, &result)); return bytes(env, result);
    });
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_pocketai_engine_NativeTokenizerBridge_normalizeNative(JNIEnv *env, jobject, jlong handle, jbyteArray input) {
    return guarded<jbyteArray>(env, nullptr, [&] {
        auto text = read_bytes(env, input); auto model = acquire(handle); std::lock_guard<std::mutex> lock(model->mutex);
        std::string result; ok(model->processor.Normalize(text, &result)); return bytes(env, result);
    });
}
