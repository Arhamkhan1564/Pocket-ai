# Architecture and development · 1.1 RAG update

Canonical repository: https://github.com/Arhamkhan1564/Pocket-ai. The update targets `downloads/rag-v1.1/` and prerelease `v1.1.0-rag`. Existing original-build reports and native host results are historical evidence; do not reuse their pass counts for changed 1.1 code without a fresh run.

## Build environment

The project uses Android Gradle Plugin 8.7.3, Kotlin/Compose compiler plugin 2.0.21, Gradle 8.9, Compose BOM 2024.12.01 and Material 3. Use a full JDK 17 or 21, compile/target SDK 35, Build Tools 35.0.0, NDK `27.2.12479018`, and CMake `3.22.1`. Minimum SDK is 23.

Follow the project README for SDK installation, `local.properties`, debug/release builds and the private signing identity. No Python package or Node build is needed. Python 3 is used by the speech-asset fetcher and optional local signing helper. Native source is included under `vendor`; a normal build does not need a Git clone of those dependencies.

The source archive/repository excludes model weights. Before building the APK with included recognition, fetch the pinned MIT-licensed Whisper tiny q5_1 asset:

```sh
python3 scripts/fetch-bundled-speech.py
python3 scripts/fetch-bundled-speech.py --check-only
./gradlew testDebugUnitTest assembleDebug
```

The default asset directory is `../model-assets` relative to the project; weights stay outside the source checkout. Both the fetcher and Gradle honor `POCKETAI_MODEL_ASSETS_DIR`. A custom path can be explicit:

```sh
python3 scripts/fetch-bundled-speech.py --assets-dir /absolute/path/to/model-assets
./gradlew -PpocketAiModelAssetsDir=/absolute/path/to/model-assets assembleDebug
```

The asset is `models/ggml-tiny-q5_1.bin`, 32,152,673 bytes, SHA-256 `818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7`. The fetcher and Gradle check its pinned size/checksum. This development-time download does not add network access to the APK. No LLM is fetched or bundled by this script. Model provenance/license files remain in source under `app/src/main/assets/licenses/`.

Development builds require Maven/Gradle/Android downloads until caches are populated. Runtime model imports are local and the manifest has no `INTERNET` permission. Preserve the environment's normal proxy/CA trust if building behind a managed proxy; do not disable TLS verification.

## Main data flow

1. Compose accepts typed input. Microphone preflight checks native ABI support, the selected speech file/header and language compatibility before permission/recording; `SpeechController` then records bounded 16 kHz mono PCM and transcribes with local Whisper. An English-only selected speech file is allowed for English and rejected before recording for Urdu/Punjabi.
2. Language/script helpers preserve originals and derive search-normalized text. Arabic-script detection is explicitly ambiguous.
3. In Training Mode, `AuthManager` checks an expiring administrator session. Text/QA entry or `DocumentTextExtractor` output is previewed and confirmed, then validated, chunked and indexed in a SQLite transaction. Document source language defaults to English independently of the answer language; originals and filename/passage references are retained.
4. In User Mode, `AssistantService` retrieves bounded relevant records using normalized lexical search, with a capable local model able to help convert a cross-language question into English search terms. Conversation text never automatically enters permanent verified knowledge.
5. With a selected GGUF and retrieved support, `LocalAiEngine` constructs a prompt containing numbered saved context, bounded recent conversation and the requested language. Source text is supplied as evidence rather than trusted operating instructions. Strict RAG abstains when no supporting source is retrieved, even if a model is selected.
6. Without a compatible model, the assistant returns matching saved originals or an explicit no-matching-knowledge message. A requested translation is marked unavailable rather than pretending original English text has been translated. Model selection does not establish multilingual quality or medical validity.
7. Output optionally passes to a verified installed offline Android TTS voice. Text survives missing-model or missing-voice conditions.

## Database

`PocketDatabase` is a versioned `SQLiteOpenHelper`; no Room dependency is required. Its schema includes knowledge, FTS indexes, categories, corrections, conversations, documents/chunks and training events. User/settings and model registry metadata live separately in small preferences/JSON stores.

Knowledge has IDs, original content, optional question, language, script, category, source/reference, timestamps, verification and optional confidence. Normalization affects indexes rather than original content. Search/browsing is bounded and paginated; retrieved contexts are small. FTS is a lexical index, not a semantic embedding system. SQLite portability uses the implemented FTS features rather than assuming every old phone ships FTS5.

Mutating verified knowledge, category management, document import and portable backup/restore require the same active `AuthManager` instance. Conversation append/clear belongs to ordinary User Mode. All expensive I/O, PBKDF2 work, database operations and inference are dispatched away from the UI thread by callers.

Portable JSON backup is deliberately bounded; it does materialize the bounded export rather than provide an unbounded streaming database copy. Schema migration scaffolding exists, but migration from a prior shipped Pocket AI version has not been exercised because this is the first schema release.

## Document extraction and retrieval

`DocumentTextExtractor` accepts UTF-8 TXT, DOCX and text-based PDF through a caller-owned stream. PDF extraction uses PDFBox Android 2.0.27.0. DOCX reads bounded ZIP/XML text and footnotes; it does not run macros, links, fields or embedded files. Temporary extraction files use app cache and are deleted in `finally` paths. Extraction runs on `Dispatchers.IO`.

Input files are capped at 8 MiB, extracted text at one million characters and PDFs at 100 pages. DOCX also limits ZIP entries/expanded sizes/compression ratio/XML complexity. Legacy OLE/binary DOC returns a conversion message. Scanned PDFs need external OCR; encrypted PDFs and empty/unreadable extraction are rejected. The UI shows filename/format/page summary, warnings, source-language controls and a bounded preview before `db.importText` changes the database.

`DocumentChunker` keeps passages sentence-aware with overlap, while `importText` stores filename and chunk references and indexes the original text. Citations are filename/chunk references; PDF page count is a preview summary and does not establish page-accurate citation. Imports require administrator authorization and use one transaction. Retrieval is lexical, not an embedding model. Model-assisted rewriting is an additional opportunity for cross-language matching, not a guarantee that every differently phrased question will retrieve correctly. Extraction/chunk preview runs through `runInterruptible(Dispatchers.IO)` so cancellation can interrupt parser work; database transactions retain their existing authorization/rollback behavior.

Medicine references remain unverified source documents. The assistant separates general source explanations from personal symptom questions, asks relevant clarifying context, and does not automatically diagnose or prescribe. Clinical source selection, translation/quantity checking and real-world suitability need independent review; there is no bundled drug database or validated clinical inference engine.

## Administrator security

`AuthManager` uses random salts, PBKDF2-HMAC-SHA256 on API 26+, PBKDF2-HMAC-SHA1 on older supported Android, 120,000 iterations and constant-time key comparison. PINs are 6–12 digits. Recovery is a separate random code with its own salt/hash. Failed attempts back off; sessions expire after ten minutes of inactivity.

The UI locks authorization on backgrounding. Explicit Storage Access Framework picker flows temporarily preserve the current expiring session via `ExternalActivityFlow`; they do not create new authorization. Data-layer mutators recheck authorization. Saved conversations and knowledge use the Android private sandbox, not SQLCipher/database encryption. Android automatic backup is disabled. There is no remote administration or account recovery.

## Local inference and memory

`InferenceEngine` separates the assistant/database from `LocalAiEngine`. `NativeBridge` wraps JNI; a coroutine mutex in `NativeExecution` serializes llama.cpp and Whisper use. Native ownership frees model/context resources after each request, trading reload latency for low idle memory.

The CPU build includes full inference for arm64-v8a, armeabi-v7a with NEON, and x86_64. The native ARMv7 build was compiled successfully; actual ARMv7 phone performance remains unbenchmarked. Runtime CPU-feature and memory checks decide availability; this does not guarantee support for all phones or models. CPU-host instruction assumptions and optional GPU/network backends are disabled. Native page alignment targets Android's 16 KiB page compatibility while retaining 4 KiB compatibility.

Model importing copies through SAF into private storage with bounded buffers, checks storage reserves, validates bounded headers, computes SHA-256 and finalizes files atomically. Metadata checks are preliminary. Actual tensor loading and representative model outputs must be tested; they can fail even when an import succeeds. No model is chosen on the assumption that 2 GB total RAM is enough.

`BundledSpeechModel.ensureInstalled` verifies and atomically installs the included speech asset when native compatibility checks succeed. It exposes progress/readiness through StateFlow, preserves a valid user selection and remembers intentional removal. The explicit restore action can recreate the private copy from the APK. No runtime download is involved. Generation/translation still needs a separate GGUF and adequate available memory; Settings labels the pinned Qwen3 link as an English experiment and explicitly reports its failed Urdu/Punjabi checks; see MODEL-VALIDATION.txt. The identified 2 GB itel A25 Pro has not been benchmarked; inspect its actual ABI/CPU features in diagnostics before assuming model compatibility.

## Voice and appliance lifecycle

`SpeechController` owns bounded AudioRecord capture, basic silence trimming, local Whisper calls and offline Android TTS selection. Tap-to-talk samples remain temporary and are discarded. A live `AudioDeviceManager` discovers device IDs and supports microphone selection; output follows Android media routing. USB hardware is optional.

Optional appliance sessions are started from the visible app with microphone permission and run as a microphone foreground service with a persistent notification. The session uses about six-second input windows, sequential answering/speech, a bounded wake lock and a maximum duration of 30 minutes. Stop/cancel releases active resources. Android version/vendor restrictions can still interrupt it. Wake-word recognition, unattended boot launch and indefinite background microphone operation are deferred.

## Tests

Run JVM/Robolectric tests:

```sh
./gradlew testDebugUnitTest
```

Reports appear under `app/build/reports/tests/testDebugUnitTest/`. Coverage includes SQLite CRUD/transactions/backup, authorization, normalization and scripts, assistant retrieval, model-header/registry behavior and voice signal processing. The RAG update adds document parsing/chunking, included-model installation and cross-language/reference behavior tests. Their presence is not a passed result; record the actual new run separately. Run lint/build validation when preparing an artifact:

```sh
./gradlew lintDebug assembleDebug assembleRelease
```

Release assembly needs the local signing configuration described in the README. Record actual successful commands and results in [VERIFICATION.md](VERIFICATION.md); source presence alone does not establish a passed test.

### Optional real-model instrumentation

`NativeRuntimeTest` runs on a natively supported device/emulator with the debug APK and adequate memory. It deliberately skips if the three external fixtures are absent. A skipped test is not a native inference pass.

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

Retain the upstream MIT licenses, included speech-model license/provenance and `vendor/REVISIONS.txt` when distributing source or APKs. PDFBox Android and other Gradle dependencies retain their respective licenses. The bundled Whisper weight is included in APKs but excluded from source archives/repository history; the fetch script reconstructs its verified build input. Optional GGUF weights are acquired separately under their own terms. Signing keys and backup archives are excluded from public source/downloads. Keep any privately retained signing identity private; a newly generated identity cannot update an APK signed with another key.
