# Architecture and development · 1.3 offline RAG and voice

Pocket AI performs document extraction, indexing, retrieval, reference translation, optional English synthesis and speech recognition on the phone. The application declares no Internet permission. Preparing included resources means copying and checking files already in the APK; it is not a runtime download or model training.

## Reproduce the build

Use JDK 17 or 21, Gradle 8.9 through the included wrapper, Android SDK/Build Tools 35, NDK `27.2.12479018`, and CMake `3.22.1`. The project uses AGP 8.7.3, Kotlin/Compose compiler 2.0.21, Compose Material 3 and minimum API 23. Python 3 standard-library scripts prepare build inputs; no Python process runs inside the Android app.

The application source ZIP excludes model weights, generated runtime archives, build caches, local SDK paths and signing credentials. The public repository separately retains the converted Urdu resource through Git LFS; its immutable URL and checksum are in the model manifest. Before Gradle sync, prepare both external inputs:

```sh
python3 scripts/fetch-offline-models.py
python3 scripts/fetch-offline-models.py --check-only
python3 scripts/prepare-onnxruntime-android.py --ndk /absolute/path/to/Android/Sdk/ndk/27.2.12479018
python3 scripts/prepare-onnxruntime-android.py --check-only
./gradlew testDebugUnitTest assembleDebug
```

The complete baseline has **17 pinned files totaling 899,719,726 bytes (858.0 MiB)**. The source manifest, `app/src/main/assets/offline-model-manifest.json`, records each URL, revision, size, checksum and license. The default external directory is `../model-assets`; Gradle and the fetcher accept `POCKETAI_MODEL_ASSETS_DIR`. A custom location can also be explicit:

```sh
python3 scripts/fetch-offline-models.py --assets-dir /absolute/path/to/model-assets
./gradlew -PpocketAiModelAssetsDir=/absolute/path/to/model-assets assembleDebug
```

`--source-cache /path` reuses matching local files. `--offline` prohibits the fetcher from making network requests, and `--check-only` verifies without downloading or copying. Preparing only selected `--packs` is useful during development, but the distribution Gradle configuration requires the complete baseline and rejects missing, altered or unlisted files. The old speech-only fetch helper is insufficient for a full build. URLs use immutable Hugging Face, official Silero GitHub, or the published Pocket AI Urdu derivative revisions; all bodies require exact checksums.

The ONNX preparation helper rebuilds the unchanged official 1.20.0 JNI wrapper at API 23 with 16 KiB ELF load alignment. It retains the official CPU kernels/Java classes, verifies JNI exports and produces an AAR plus checksum/provenance sidecar outside source. Default outputs are:

```text
../runtime-assets/onnxruntime-android-1.20.0-pocketai16k.aar
../runtime-assets/onnxruntime-android-1.20.0-pocketai16k.json
```

Use `--assets-dir`/`POCKETAI_ORT_ASSETS_DIR` for preparation output, then point Gradle at the resulting AAR using `POCKETAI_ORT_ANDROID_AAR` or `-PpocketAiOrtAndroidAar=/absolute/path/to/file.aar`. Keep its sidecar beside it. The pinned preparation inputs are in `scripts/onnxruntime-android-inputs.json`; [ONNX Runtime preparation](ONNXRUNTIME-ANDROID.md) explains the API/alignment choice. Substituting an arbitrary stock or newer AAR can break API 23 or alignment requirements.

Initial development preparation and dependency resolution require network access unless all verified inputs and dependencies are already cached. Installed-app operation uses local files. Standard release assembly requires the original private signing identity. The separately named voice preview has its own externally stored key; see [preview builds](VOICE-PREVIEW-BUILD.md). A different key cannot update an existing signed installation.

## Included components

| Role | Included implementation | What it does not establish |
| --- | --- | --- |
| Search | Quantized multilingual E5-small ONNX plus SentencePiece tokenizer | Translation, guaranteed relevance or factual verification |
| Translation | IndicTrans2 distilled 200M int8 English-to-Indic encoder/decoders and tokenizer/dictionaries | Reverse translation, every language, or clinical accuracy |
| Shahmukhi display | Original `PunjabiShahmukhi` converter after actual Punjabi Gurmukhi translation | Direct Urdu-to-Punjabi translation or fluent-speaker validation |
| Optional synthesis | SmolLM2 360M Instruct Q4_K_M through llama.cpp | Reliable abstention or Urdu/Punjabi generation |
| Recognition | English tiny, Urdu base Q5_1; Punjabi CTC int8; Silero speech-presence gate before Whisper | Correct words/numbers, spoken-output voices or measured phone-language accuracy |

Android offline TTS voices remain a separately installed system resource. All three packaged ABIs—ARM64, ARMv7 with NEON and x86_64—are subject to CPU, free-memory and Android restrictions. The identified 2 GB itel phone was not attached. Packaging an ABI is not a successful phone benchmark.

## Reference workflow

1. Compose accepts text or bounded microphone input. Voice preflight checks native compatibility and the selected/prepared speech resource before recording. The captured plan chooses a recognizer for temporary PCM. Whisper runs only after a positive local speech-presence check; the detector is closed first. Punjabi uses bounded 12-second CTC windows. Every manual Urdu/Punjabi result requires editable review before entering its captured destination.
2. The owner unlocks Training Mode. `DocumentTextExtractor` reads TXT/DOCX/selectable-text PDF; the UI previews its output before any knowledge write. Source language defaults to English independently of answer language.
3. `DocumentChunker` splits paragraphs using sentence/word boundaries and bounded overlap. `PocketDatabase.importText` saves originals, document metadata and filename/chunk references transactionally. Repeated imports are deduplicated; saving text does not change model parameters.
4. `OfflineRagServices` prepares local packs and indexes small batches through E5. `SemanticRetriever` combines bounded semantic matches with lexical fallback and coverage checks. It abstains when it has no supporting passages. A cosine score is not proof that a source answers a question.
5. Default answers show cited source excerpts. Urdu/Punjabi answers translate English excerpts using IndicTrans2; Shahmukhi adds an explicitly labelled Punjabi script-conversion step. Original English evidence stays available. Unsupported directions or failed checks retain originals with a clear status.
6. The optional, slower English-explanation mode uses the included English GGUF only after retrieval and coverage checks. Medical reference paths retain excerpt behavior. Unsupported synthesis falls back to excerpts or an unknown answer.
7. Trusted source IDs, separate from display/model text, let the user open a complete saved passage and its document. Optional speech output uses an available installed offline voice.

## Extraction and database

Extraction accepts UTF-8 TXT, DOCX and text-based PDF, capped at 8 MiB input, one million extracted characters and 100 PDF pages. PDFBox Android performs text extraction without OCR. DOCX processing bounds ZIP entries, expanded bytes, compression ratio and XML complexity, rejects declarations/entities, and does not execute fields or macros. Legacy binary DOC must be converted; encrypted or image-only PDFs return an explicit message. Temporary extraction copies are deleted. The UI uses `runInterruptible(Dispatchers.IO)` for extraction/chunk preview.

Schema **2** uses SQLite knowledge/FTS, categories, correction/training history, conversations, document catalog/mappings and a derived `knowledge_vectors` table. Migration from schema 1 retains knowledge and history, adds typed `source_ids` and rebuilds missing document metadata. Source IDs are not extracted from filenames, display labels or model-generated citation text. Legacy history can have no typed IDs.

Catalog/read APIs are available in User Mode; deleting a document requires administrator authorization and a transaction. It removes that document's unshared imported passages and indexes while preserving unrelated manual facts. Citations to removed sources show a missing-source state. The reader displays the current saved original and update information, not the original PDF binary or exact PDF-page coordinates.

Derived E5 vectors are 384 finite, L2-normalized floats per window, keyed by source ID, model SHA and part. Store operations check the expected source content and timestamp inside a transaction; edits/delete/restore invalidate derived results. Search streams one vector at a time and retains at most 20 distinct source hits. It does not load the full vector table into RAM. Indexing uses small batches and can pause/resume; lexical lookup remains available while indexing is incomplete.

Portable backup **format 2** preserves source records, document catalog/mappings, categories and bounded conversations/typed IDs. Format 1 remains readable, with safe catalog reconstruction. Vectors, translation caches, model files, credentials/settings and full audit/correction history are excluded. See [Backup and recovery](BACKUP-RECOVERY.md).

## Translation and resource ownership

`NativeExecution` serializes heavyweight search, NMT, generation and recognition. E5 sessions/tokenizers are request/batch scoped. Its current available-memory guard is 384 MiB; this estimate cannot prevent every Android low-memory termination.

`OnnxTranslationKernel` executes the quantized IndicTrans2 graphs with one heavyweight session open at a time. Native tensor ownership keeps encoder/cross-attention results alive only while required; per-token caches are not copied into Java arrays. The source codec uses the pinned SentencePiece/dictionary mapping and Indic text processor, with request-local entity state. Translation is sentence-bounded, requires completed EOS output and has token/length limits. Its current available-memory guard is 448 MiB, not measured peak Android use.

Numeric sentinels protect literal identifiers/citation labels before translation. Restoration requires the exact sentinel occurrence; quantity/unit, warning/negation and target-script checks follow. These checks are incomplete semantic safeguards, not proof that a medical translation is accurate. Unsupported Gurmukhi marks fail script conversion explicitly instead of disappearing.

Translation cache entries are local and bounded (up to 200 entries/32 MiB). Their keys include source text, target/script, pack fingerprint and knowledge revision. Source changes invalidate cached results; cache failure must not discard a valid source. Cached translations never become verified knowledge or model updates.

`OfflineModelAssets` copies APK files to private storage, checks sizes/checksums and atomically records readiness. Readiness metadata avoids re-copying unchanged valid resources. Setup can be paused/retried without a network connection. APK copies plus private prepared copies, source data, vectors and runtime work buffers need substantial free storage beyond the 682.6 MiB model payload. Resource preparation does not prove that inference fits in available RAM.

## Security, voice and lifecycle

Administrator PINs use salted PBKDF2 with 120,000 iterations and constant-time comparison; API 26+ uses SHA-256 and older supported Android uses SHA-1. Recovery is a separately hashed random code. Failed attempts back off. Atomic session state lets UI reads/locking avoid waiting on crypto and prevents a pending PIN/recovery operation from overriding a newer lock. Sessions expire after inactivity. Explicit document-picker flows preserve only the existing expiring session.

Knowledge, prepared resources and caches live in the private Android sandbox; the database and portable JSON are not encrypted. Android automatic backup/device transfer is disabled. Microphone audio is explicit and temporary. Optional appliance sessions require a visible start, microphone permission and foreground notification; they use bounded turns and a 30-minute limit. Wake words, automatic boot startup and indefinite unattended recording are not included. Device/Android restrictions may interrupt a session.

## Validation and distribution

```sh
./gradlew testDebugUnitTest lintDebug lintVitalRelease assembleDebug assembleRelease assembleDebugAndroidTest
./gradlew connectedDebugAndroidTest
```

Use [Verification](VERIFICATION.md) and `OFFLINE-BUILD-REPORT.json` for the actual completed 1.2 run. Unit tests, host model probes, native packaging checks and complete Android/phone execution are separate evidence. A compiled or skipped instrumentation test is not a UI/native execution pass. Native instrumentation fixtures and staging commands are documented in `tests/native/README.md` and the test sources; run only on a disposable debug installation with adequate memory.

Keep the included model manifest/provenance, component licenses/notices, native revisions and ONNX preparation input manifest with distributions. Source excludes binary weights, generated runtime AARs, credentials and signing backups; the helpers reconstruct pinned build inputs. The baseline APK includes its licensed resources. Do not replace defaults with cloud requests or claim that importing/indexing a document retrains an AI model.

## Voice resource ownership in 1.3

`OfflineSpeechModels` captures backend, full language/script tag and verified resource file metadata before recording. The same plan is rechecked after acquiring `NativeExecution`; changing the selected model affects the next recording. A custom Whisper selection overrides automatic recognition. A persisted explicit null remains disabled across startup, while a fresh unset install may select the included policy once. Removed included resources are restored only by the explicit action.

Punjabi uses a single request-scoped ORT session with CPU arena and memory patterns disabled, full-utterance normalization and overlap-frame merging before global CTC collapse. Its normalized PCM scratch is cleared. Whisper runs after request-scoped Silero VAD using the pinned reference threshold/minimum-speech policy. Cancellation belongs to the controller holding the native section; cancelling queued speech must not interrupt unrelated indexing. All microphone PCM is bounded to 60 seconds and cleared in finally paths.

The conservative recognition memory budget is at least 512 MiB for Punjabi CTC and `max(modelBytes*4+128MiB,256MiB)` for Whisper. Required budget may occupy at most 75% of available memory and Android must not report low memory. These guards are estimates, not phone benchmarks. Typed questions remain available when a model cannot fit. See [voice measurements](VOICE-VALIDATION-1.3.md) and [Urdu reconstruction](URDU-SPEECH-MODEL.md).
