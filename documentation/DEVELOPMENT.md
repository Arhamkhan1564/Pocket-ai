# Architecture and development

## Build environment

The project uses Android Gradle Plugin 8.7.3, Kotlin/Compose compiler plugin 2.0.21, Gradle 8.9, Compose BOM 2024.12.01 and Material 3. Use a full JDK 17 or 21, compile/target SDK 35, Build Tools 35.0.0, NDK `27.2.12479018`, and CMake `3.22.1`. Minimum SDK is 23.

Follow the project README for SDK installation, `local.properties`, debug/release builds and the private signing identity. No Python package or Node build is needed. Python 3 is used only by the optional local signing-key helper. Native source is included under `vendor`; a normal build does not need a Git clone of those dependencies.

Development builds require Maven/Gradle/Android downloads until caches are populated. Runtime model imports are local and the manifest has no `INTERNET` permission. Preserve the environment's normal proxy/CA trust if building behind a managed proxy; do not disable TLS verification.

## Main data flow

1. Compose accepts typed input or the `SpeechController` records bounded 16 kHz mono PCM and transcribes with local Whisper.
2. Language/script helpers preserve originals and derive search-normalized text. Arabic-script detection is explicitly ambiguous.
3. In Training Mode, `AuthManager` checks an expiring administrator session. A reviewed record is validated, written and indexed in a SQLite transaction; changes produce training/correction history.
4. In User Mode, `AssistantService` retrieves bounded relevant records using normalized lexical search. Conversation text never automatically enters permanent verified knowledge.
5. With a selected GGUF, `LocalAiEngine` constructs a prompt containing numbered saved context, bounded recent conversation and the requested language. Source text is supplied as evidence rather than trusted operating instructions.
6. Without a compatible model, the assistant returns matching saved originals or an explicit no-matching-knowledge message. It does not simulate a generative model with preset factual answers.
7. Output optionally passes to a verified installed offline Android TTS voice. Text survives missing-model or missing-voice conditions.

## Database

`PocketDatabase` is a versioned `SQLiteOpenHelper`; no Room dependency is required. Its schema includes knowledge, FTS indexes, categories, corrections, conversations, documents/chunks and training events. User/settings and model registry metadata live separately in small preferences/JSON stores.

Knowledge has IDs, original content, optional question, language, script, category, source/reference, timestamps, verification and optional confidence. Normalization affects indexes rather than original content. Search/browsing is bounded and paginated; retrieved contexts are small. FTS is a lexical index, not a semantic embedding system. SQLite portability uses the implemented FTS features rather than assuming every old phone ships FTS5.

Mutating verified knowledge, category management, document import and portable backup/restore require the same active `AuthManager` instance. Conversation append/clear belongs to ordinary User Mode. All expensive I/O, PBKDF2 work, database operations and inference are dispatched away from the UI thread by callers.

Portable JSON backup is deliberately bounded; it does materialize the bounded export rather than provide an unbounded streaming database copy. Schema migration scaffolding exists, but migration from a prior shipped Pocket AI version has not been exercised because this is the first schema release.

## Administrator security

`AuthManager` uses random salts, PBKDF2-HMAC-SHA256 on API 26+, PBKDF2-HMAC-SHA1 on older supported Android, 120,000 iterations and constant-time key comparison. PINs are 6–12 digits. Recovery is a separate random code with its own salt/hash. Failed attempts back off; sessions expire after ten minutes of inactivity.

The UI locks authorization on backgrounding. Explicit Storage Access Framework picker flows temporarily preserve the current expiring session via `ExternalActivityFlow`; they do not create new authorization. Data-layer mutators recheck authorization. Saved conversations and knowledge use the Android private sandbox, not SQLCipher/database encryption. Android automatic backup is disabled. There is no remote administration or account recovery.

## Local inference and memory

`InferenceEngine` separates the assistant/database from `LocalAiEngine`. `NativeBridge` wraps JNI; a coroutine mutex in `NativeExecution` serializes llama.cpp and Whisper use. Native ownership frees model/context resources after each request, trading reload latency for low idle memory.

The CPU build includes full inference for arm64-v8a and x86_64. armeabi-v7a gets a lightweight JNI compatibility stub and a clear unavailable capability result. CPU-host instruction assumptions and optional GPU/network backends are disabled. Native page alignment targets Android's 16 KiB page compatibility while retaining 4 KiB compatibility.

Model importing copies through SAF into private storage with bounded buffers, checks storage reserves, validates bounded headers, computes SHA-256 and finalizes files atomically. Metadata checks are preliminary. Actual tensor loading and representative model outputs must be tested; they can fail even when an import succeeds. No model is chosen on the assumption that 2 GB total RAM is enough.

## Voice and appliance lifecycle

`SpeechController` owns bounded AudioRecord capture, basic silence trimming, local Whisper calls and offline Android TTS selection. Tap-to-talk samples remain temporary and are discarded. A live `AudioDeviceManager` discovers device IDs and supports microphone selection; output follows Android media routing. USB hardware is optional.

Optional appliance sessions are started from the visible app with microphone permission and run as a microphone foreground service with a persistent notification. The session uses about six-second input windows, sequential answering/speech, a bounded wake lock and a maximum duration of 30 minutes. Stop/cancel releases active resources. Android version/vendor restrictions can still interrupt it. Wake-word recognition, unattended boot launch and indefinite background microphone operation are deferred.

## Tests

Run JVM/Robolectric tests:

```sh
./gradlew testDebugUnitTest
```

Reports appear under `app/build/reports/tests/testDebugUnitTest/`. Tests cover SQLite CRUD/transactions/backup, authorization, normalization and scripts, assistant retrieval, model-header/registry behavior and voice signal processing. Run lint/build validation when preparing an artifact:

```sh
./gradlew lintDebug assembleDebug assembleRelease
```

Release assembly needs the local signing configuration described in the README. Record actual successful commands and results in [VERIFICATION.md](VERIFICATION.md); source presence alone does not establish a passed test.

### Optional real-model instrumentation

`NativeRuntimeTest` runs on a supported 64-bit device/emulator with the debug APK. It deliberately skips if the three external fixtures are absent. A skipped test is not a native inference pass.

Provide these files in the debug app's private `cache/native-fixtures/` directory:

- `stories260K.gguf`: tiny test-only language model from [ggml-org models](https://huggingface.co/ggml-org/models/resolve/main/tinyllamas/stories260K.gguf).
- `ggml-tiny.bin`: official multilingual Whisper tiny model linked in [MODELS.md](MODELS.md).
- `jfk.wav`: the upstream whisper.cpp English sample, `vendor/whisper.cpp/samples/jfk.wav`.

For example, after installing the debug APK and staging fixtures on the development computer:

```sh
adb shell mkdir -p /data/local/tmp/pocketai-fixtures
adb push stories260K.gguf /data/local/tmp/pocketai-fixtures/
adb push ggml-tiny.bin /data/local/tmp/pocketai-fixtures/
adb push vendor/whisper.cpp/samples/jfk.wav /data/local/tmp/pocketai-fixtures/
adb shell run-as com.pocketai.debug mkdir -p cache/native-fixtures
adb shell run-as com.pocketai.debug cp /data/local/tmp/pocketai-fixtures/stories260K.gguf cache/native-fixtures/
adb shell run-as com.pocketai.debug cp /data/local/tmp/pocketai-fixtures/ggml-tiny.bin cache/native-fixtures/
adb shell run-as com.pocketai.debug cp /data/local/tmp/pocketai-fixtures/jfk.wav cache/native-fixtures/
./gradlew connectedDebugAndroidTest
```

The debug app must already exist before using `run-as`; `./gradlew installDebug` installs it. Some runners reinstall/clear target data before tests, so verify fixture presence or stage it after installation for a targeted instrumentation invocation. English sample recognition and toy text generation only validate the runtime path. They do not validate a real microphone, meaningful multilingual answers or performance on the 2 GB target phone.

## Dependencies and distribution

Retain the upstream MIT licenses and `vendor/REVISIONS.txt` when distributing source. AndroidX and other Gradle dependencies retain their respective licenses. Model weights are separately acquired and must satisfy their own terms. The local signing archive is intentionally separate from the source archive and must not be shared with a public repository.
