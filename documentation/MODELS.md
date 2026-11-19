# Included models and compatibility · 1.3 voice preview

The 1.3 preview APK contains all model/tokenizer files needed for local saved-document search, English-to-Urdu/Punjabi reference translation, optional English synthesis and voice recognition. Normal use does not download a model or call a cloud service. First-run preparation copies and verifies files already inside the APK.

The bundle has **17 files across seven packs, totaling 899,719,726 bytes (about 858.0 MiB)**. This is the model-asset total, not the complete APK size or installed-storage requirement. The installed APK and private prepared copies consume separate storage; documents, derived indexes, Android overhead and an optionally retained installer add more.

The 1.3 preview retains the saved-document workflow and schema 2, and adds language-specific recognition resources plus an offline speech-presence detector. Importing or teaching still does not retrain model weights. The preview installs separately from the original app; see [installation](INSTALLATION.md). Earlier validation reports describe their own releases.

## Seven packs for separate tasks

| Resource | Included files / bytes | Role and limits |
| --- | --- | --- |
| Multilingual E5 small, int8 ONNX | 2 files; 123,377,236 bytes | Embeds English/Urdu/Punjabi questions and saved passages for semantic retrieval. It does not translate or generate answers. |
| IndicTrans2 English–Indic distilled 200M, int8 ONNX | 9 files; 289,611,894 bytes | Dedicated English → Urdu and English → Gurmukhi Punjabi translation. It has no direct Shahmukhi target. |
| SmolLM2 360M Instruct, Q4_K_M GGUF | 1 file; 270,590,880 bytes | Optional English-focused, source-constrained synthesis. Cited excerpts remain the default. It is not the Urdu/Punjabi translator. |
| Multilingual Whisper tiny, Q5_1 GGML | 1 file; 32,152,673 bytes | Included English recognition and the included automatic-selection entry. Urdu and Punjabi use the dedicated packs below. |
| Urdu-tuned Whisper base, Q5_1 GGML | 1 file; 59,707,642 bytes | Experimental Urdu recognition, after the speech-presence check. Spoken-word and number errors remain; manual results require editable review. |
| Punjabi wav2vec2 CTC, int8 ONNX | 2 files; 121,951,877 bytes | Native Gurmukhi recognition with a 65-token vocabulary and bounded 12-second windows. Word and name errors remain; manual results require editable review. |
| Silero VAD 6.2.3, ONNX | 1 file; 2,327,524 bytes | Detects likely speech before every Whisper model. It does not verify language, words, numbers or meaning. |

The original `PunjabiShahmukhi` component converts Punjabi Gurmukhi output to Shahmukhi after Punjabi translation. It is code with a small spelling glossary, not another weight file, an Urdu-to-Punjabi translator or a claim of fluent spelling quality. Unsupported signs are reported instead of silently discarded. Loanword spelling, short vowels, tone, dialect and some letter distinctions remain limitations.

No TTS voice pack, OCR engine, wake-word model, curated drug database or validated clinical prescribing system is bundled.

## Pinned provenance and licenses

Complete per-file URLs, byte counts and SHA-256 checksums are authoritative in [offline-model-manifest.json](../app/src/main/assets/offline-model-manifest.json). The fetch helper and APK-build checks use that manifest; local preparation verifies integrity before marking a pack ready.

| Pack | Pinned source | License |
| --- | --- | --- |
| Search | `Xenova/multilingual-e5-small`, revision `761b726dd34fb83930e26aab4e9ac3899aa1fa78` | MIT |
| Translation | `hari31416/indictrans2-en-indic-dist-200M-ONNX-int8`, revision `1eaa43a3fb76f382f1b22c76e56f6ab4926ad68b` | MIT |
| English synthesis | `bartowski/SmolLM2-360M-Instruct-GGUF`, revision `7be6f65f1db715fe5dc5a4634c0d459b4eed42ec` | Apache 2.0 |
| English speech | `ggerganov/whisper.cpp`, revision `5359861c739e955e79d9a303bcbc70fb988958b1` | MIT |
| Urdu speech | `kingabzpro/whisper-base-urdu-full`, revision `6bfaec492d42f4b76f117b2b34cd9672509cecdc`; locally converted and quantized as [documented](URDU-SPEECH-MODEL.md) | Apache-2.0 and underlying Whisper MIT |
| Punjabi speech | `XDcobra/wav2vec2-large-xlsr-53-punjabi-ONNX`, revision `de54b60baa1879a4db93596cf1b5b3215a291b7a` | Apache-2.0 and upstream Vakyansh MIT |
| Speech presence | `snakers4/silero-vad` 6.2.3, revision `5cd7945676eb32225748052e2e6a0580e4686a08` | MIT |

Notices and license texts are under [app/src/main/assets](../app/src/main/assets/). Native source revisions remain in [vendor/REVISIONS.txt](../vendor/REVISIONS.txt). Retain model, ONNX Runtime, SentencePiece, PDFBox, font and other applicable dependency notices when redistributing.

Key identities include:

- E5 `model.onnx`: SHA-256 `f80102d3f2a1229f387d3c81909990d8945513e347b0eab049f7de3c6f98c193`.
- SmolLM2 `model.gguf`: SHA-256 `2fa3f013dcdd7b99f9b237717fa0b12d75bbb89984cc1274be1471a465bac9c2`.
- Whisper `ggml-tiny-q5_1.bin`: SHA-256 `818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7`.
- Urdu `ggml-urdu-base-q5_1.bin`: SHA-256 `701f3ae011e0964e45894ad884da1afd5c1a0a7a846b054d9765e75d563ee7b9`.
- Punjabi `model.onnx`: SHA-256 `06cd96375424bc23e148191a3cdb7ee03691ba4c4438cdc8c25e53472bdffab5`.
- Silero `silero-vad.onnx`: SHA-256 `1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3`.

The translation pack has nine matching graph/data/tokenizer/dictionary files. Do not mix individual files from different revisions.

## Prepare and use locally

First-run setup and Resources → **Offline tools** copy included files into app-private storage and expose per-tool readiness/progress. No network, browser or manual import is needed for the baseline. Keep space for private copies as well as the APK. A verified pack is an integrity result, not proof that output is correct or inference fits current free RAM.

Search, translation and English explanation readiness are separate. Reference indexing has progress and pause/resume controls. New or edited passages need derived vectors before complete semantic matching; keyword search and source reading remain available while indexing is incomplete. Restoring knowledge clears derived vectors for local rebuilding.

The encoder accepts saved records up to 20,000 characters with bounded windows. An oversized or otherwise unsupported saved note is skipped with a reason for that indexing pass, remains pending/keyword-only, and does not abort later supported records. Editing the source makes it eligible for another attempt; split large notes or use the document importer for bounded chunks. No content is silently truncated or marked indexed by this skip path. This is distinct from the importer's one-million-character input-text limit.

Default answers are cited excerpts. Urdu/Punjabi display uses dedicated English-source translation, retaining the intact original. Numeric values, identifiers, warning language and script have preservation checks, but those cannot establish full semantic equivalence. A failed or unavailable translation leaves the original available.

Translation works on bounded passages, not an entire imported book in one operation. The service accepts up to 20,000 source characters and 200 sentences; each model segment is bounded to 4,000 characters, 256 source tokens and 256 output tokens. An unusually long sentence or incomplete decoding can be refused even when the document itself passed import limits. The saved original remains readable.

Optional **English explanation · slower** mode can summarize retrieved evidence using included SmolLM2. A host prototype showed that a small generator can fail to abstain when a fact is missing. The app therefore controls source availability, validates output and retains excerpt fallback. Medical-reference answers use sourced excerpts. Optional synthesis is not a replacement for the reference or a medically validated reasoning engine.

Heavy inference is serialized rather than keeping speech, embedding, translation and generation models active together. Context and output are bounded. The actual phone still needs latency, memory and thermal testing.

## Language directions

| Capability | English | Urdu | Punjabi Gurmukhi | Punjabi Shahmukhi |
| --- | --- | --- | --- | --- |
| Save/read original Unicode text | Yes | Yes | Yes | Yes |
| Semantic query against indexed references | Included E5 path | Included E5 path | Included E5 path | Included E5 path |
| Display English evidence | Original excerpt | Dedicated local translation | Dedicated local translation | Punjabi translation, then labeled script conversion |
| Optional synthesis | English-focused model | Translation of accepted English explanation | Translation of accepted English explanation | Punjabi explanation translation, then script conversion |
| Offline recognition | Included Whisper tiny after speech-presence check; review transcript | Included Urdu-tuned base after speech-presence check; editable review required | Included CTC outputs Gurmukhi; editable review required | Gurmukhi recognition, then labeled display conversion and editable review |
| Spoken output | Installed offline Android voice | Installed compatible voice | Installed compatible voice | Installed compatible voice |

English, Urdu and Punjabi remain selectable alongside any optional configured text language. Extra-language selection does not add a translation direction or voice. The included translator reads English; arbitrary Urdu/Punjabi → English translation or arbitrary language pairs are not promised. Cross-language retrieval uses E5 embeddings rather than assuming forward translation can reverse a question.

Arabic-script Urdu/Punjabi identification is ambiguous; manual selection remains important. The included Punjabi CTC path produces Gurmukhi; Shahmukhi is a separately labeled display conversion with spelling limits. A custom Whisper model may still return a different script. Text, retrieval, translation, ASR and TTS are separate capabilities.

## Hardware and memory limits

The application targets Android 6.0/API 23+ and packages ARM64, ARMv7 with NEON, and x86_64 runtimes. ONNX Runtime uses the prepared API-23-compatible JNI wrapper described in [ONNXRUNTIME-ANDROID.md](ONNXRUNTIME-ANDROID.md). Supported targets do not guarantee usable inference on every device. The itel A25 Pro/2 GB phone was not attached for acceptance testing.

Available memory matters more than total RAM alone. Embedding checks for at least 384 MiB available; dedicated translation checks for at least 448 MiB available and no reported low-memory condition. These conservative gates are estimates, not measured peak-memory guarantees. Generation has its own model-size/free-memory guard. Recognition budgets at least 512 MiB for Punjabi CTC and `max(modelBytes × 4 + 128 MiB, 256 MiB)` for Whisper; the budget must fit within 75% of currently available memory and Android must not report low memory. The speech-presence detector closes before Whisper loads. These are conservative guards, not phone peak-memory measurements. Android can still stop a process under pressure, and 32-bit address space adds restrictions.

Start with excerpts, close other apps and inspect Device & storage. Storage cannot fix insufficient RAM. A host prototype or another phone's result does not prove speed, thermals, multilingual accuracy, USB routing or screen-off reliability on the target phone.

## Optional advanced custom models

Baseline use needs no custom import. **Advanced · custom models** allows an administrator to import a compatible GGUF or Whisper GGML file already available through Android's picker, including mounted USB providers where permitted. Custom files are privately copied with bounded buffers and preliminary format/checksum checks. Their external originals are not deleted when a private copy is removed.

A valid header does not establish full tensor compatibility, memory fit or language quality. Custom GGUF context sizes are bounded; smaller contexts generally need less memory. Custom Whisper execution is limited to 180 MiB files and 60-second recordings. English-only `.en` Whisper models cannot recognize Urdu/Punjabi. An explicit custom speech choice is preserved; automatic local preparation does not replace it. Disabling recognition stays disabled after reopening. Choose the included automatic entry to use the bundled language-specific recognizers.

Android TextToSpeech supplies output independently. Pocket AI selects installed voices marked as not requiring a network connection. Missing or unsuitable voices leave text available. Embedding, translation, GGUF and Whisper resources do not install TTS voices.

## Recognition evidence and limits

The included Urdu-tuned base reduced basic word errors on four exploratory Urdu recordings compared with generic base, but still lost or changed spoken quantities. Used alone, it hallucinated words on all three tested non-speech controls. The app therefore runs the included speech-presence detector before every Whisper model, including custom Whisper selections, and closes that detector before loading recognition weights.

The unmodified official Silero 6.2.3 reference pipeline, with threshold 0.5 and minimum speech 250 ms, accepted four Urdu and two English clips and rejected silence, louder noise and a tone: nine reference decisions. This small host check does not establish general detector accuracy or correct transcripts. Canonical Kotlin gate parity is pending in this documentation snapshot and is tracked in the [1.3 voice evidence](VOICE-VALIDATION-1.3.md); a pending check is not a passed result.

The final Punjabi JVM kernel produced Gurmukhi on all four checked clips, where generic Whisper returned romanization on the original pair. Substantial word and name errors remain, including errors in a medical-news sentence. Four synthetic non-speech controls returned empty text. Bounded 30/60-second repeated-audio checks establish host resource behavior only. Identical normalized model input reproduced every Python/JVM frame ID; the earlier NumPy prototype used different floating-point normalization statistics. See [the complete 1.3 evidence](VOICE-VALIDATION-1.3.md) for actual transcripts, hashes, scoring, cancellation, resource checks and remaining limits.

Each capture fixes its recognizer, language tag, Punjabi display script and question/training destination before recording starts. Changing a setting later does not reinterpret that recording. Every manual Urdu/Punjabi result opens editable review before filling its captured draft; sending or saving still requires confirmation. Shahmukhi conversion is visibly labeled and keeps the raw recognition available. Hands-free turns use unreviewed transcripts and have an additional limitation.

Review and correct recognition in every language, particularly names, identifiers, medicine terms and numbers. Typed input is preferable when exact wording matters. No phone microphone, 2 GB phone, fluent-speaker, clinical, TTS or long-running hands-free acceptance is established. Speech identifiers, resource readiness and the presence detector are not recognition-quality guarantees.

The [original six-clip tiny report](OFFLINE-VOICE-VALIDATION.md) remains historical evidence for the 1.2.1 recognition path, including its Urdu omissions and Punjabi romanization. Those transcripts have not been relabeled as results for the new resources.

## Source and build inputs

Project source/source archives exclude standalone weights. Developers reconstruct the bundle with `scripts/fetch-offline-models.py`; verified local-cache-only preparation is supported. The prepared Android ONNX Runtime AAR is another external build input reconstructed by `scripts/prepare-onnxruntime-android.py`, with pinned provenance/checks. See [DEVELOPMENT.md](DEVELOPMENT.md) for commands and paths.

Development preparation may download files when local caches are absent. This adds no network path to the installed app. Public source/downloads exclude private signing keys/passwords. Knowledge backups exclude models and derived embeddings; those are locally re-prepared rather than treated as owner-approved knowledge.

## Evidence and clinical limits

Read [VERIFICATION.md](VERIFICATION.md) and [1.3 voice validation](VOICE-VALIDATION-1.3.md) for the scope of completed checks. The 1.2.1 [build report](OFFLINE-1.2.1-BUILD-REPORT.json) and [acceptance record](OFFLINE-ACCEPTANCE-1.2.1.md) remain historical records for that release. A pending harness or planned suite is not a passed check. Synthetic examples, literal checks and host execution are limited evidence, not medical validity or a completed phone UI/audio acceptance run.

Medicine documents are unverified references. Check clinical source quality, drug names, numbers, units, negation and context against the retained English original. The assistant does not automatically diagnose or prescribe; limited urgent-message rules are not universal emergency triage.

Earlier [MODEL-VALIDATION.txt](MODEL-VALIDATION.txt) records failed Urdu/Punjabi checks for the **1.1 Qwen experiment**, a different model/path. It remains historical evidence, not a requirement to download Qwen and not a quality result for the 1.2 translator. The initial Qwen2.5 host result is historical as well.
