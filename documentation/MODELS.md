# Included models and compatibility · 1.2

The baseline APK contains all model/tokenizer files needed for local saved-document search, English-to-Urdu/Punjabi reference translation, optional English synthesis and voice recognition. Normal use does not download a model or call a cloud service. First-run preparation copies and verifies files already inside the APK.

The bundle has **13 files totaling 715,732,683 bytes (about 682.6 MiB)**. This is the model-asset total, not the complete APK size or installed-storage requirement. The installed APK and private prepared copies consume separate storage; documents, derived indexes, Android overhead and an optionally retained installer add more.

## Four separate roles

| Resource | Included files / bytes | Role and limits |
| --- | --- | --- |
| Multilingual E5 small, int8 ONNX | 2 files; 123,377,236 bytes | Embeds English/Urdu/Punjabi questions and saved passages for semantic retrieval. It does not translate or generate answers. |
| IndicTrans2 English–Indic distilled 200M, int8 ONNX | 9 files; 289,611,894 bytes | Dedicated English → Urdu and English → Gurmukhi Punjabi translation. It has no direct Shahmukhi target. |
| SmolLM2 360M Instruct, Q4_K_M GGUF | 1 file; 270,590,880 bytes | Optional English-focused, source-constrained synthesis. Cited excerpts remain the default. It is not the Urdu/Punjabi translator. |
| Multilingual Whisper tiny, Q5_1 GGML | 1 file; 32,152,673 bytes | Offline speech recognition. It adds no TTS voice and does not translate saved evidence. |

The original `PunjabiShahmukhi` component converts Punjabi Gurmukhi output to Shahmukhi after Punjabi translation. It is code with a small spelling glossary, not another weight file, an Urdu-to-Punjabi translator or a claim of fluent spelling quality. Unsupported signs are reported instead of silently discarded. Loanword spelling, short vowels, tone, dialect and some letter distinctions remain limitations.

No TTS voice pack, OCR engine, wake-word model, curated drug database or validated clinical prescribing system is bundled.

## Pinned provenance and licenses

Complete per-file URLs, byte counts and SHA-256 checksums are authoritative in [offline-model-manifest.json](../app/src/main/assets/offline-model-manifest.json). The fetch helper and APK-build checks use that manifest; local preparation verifies integrity before marking a pack ready.

| Pack | Pinned source | License |
| --- | --- | --- |
| Search | `Xenova/multilingual-e5-small`, revision `761b726dd34fb83930e26aab4e9ac3899aa1fa78` | MIT |
| Translation | `hari31416/indictrans2-en-indic-dist-200M-ONNX-int8`, revision `1eaa43a3fb76f382f1b22c76e56f6ab4926ad68b` | MIT |
| English synthesis | `bartowski/SmolLM2-360M-Instruct-GGUF`, revision `7be6f65f1db715fe5dc5a4634c0d459b4eed42ec` | Apache 2.0 |
| Speech | `ggerganov/whisper.cpp`, revision `5359861c739e955e79d9a303bcbc70fb988958b1` | MIT |

Notices and license texts are under [app/src/main/assets](../app/src/main/assets/). Native source revisions remain in [vendor/REVISIONS.txt](../vendor/REVISIONS.txt). Retain model, ONNX Runtime, SentencePiece, PDFBox, font and other applicable dependency notices when redistributing.

Key identities include:

- E5 `model.onnx`: SHA-256 `f80102d3f2a1229f387d3c81909990d8945513e347b0eab049f7de3c6f98c193`.
- SmolLM2 `model.gguf`: SHA-256 `2fa3f013dcdd7b99f9b237717fa0b12d75bbb89984cc1274be1471a465bac9c2`.
- Whisper `ggml-tiny-q5_1.bin`: SHA-256 `818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7`.

The translation pack has nine matching graph/data/tokenizer/dictionary files. Do not mix individual files from different revisions.

## Prepare and use locally

First-run setup and Resources → **Offline tools** copy included files into app-private storage and expose per-tool readiness/progress. No network, browser or manual import is needed for the baseline. Keep space for private copies as well as the APK. A verified pack is an integrity result, not proof that output is correct or inference fits current free RAM.

Search, translation and English explanation readiness are separate. Reference indexing has progress and pause/resume controls. New or edited passages need derived vectors before complete semantic matching; keyword search and source reading remain available while indexing is incomplete. Restoring knowledge clears derived vectors for local rebuilding.

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
| Offline recognition | Multilingual Whisper path | Same model; accuracy needs testing | `pa` path; accuracy/script needs testing | `pa` path; accuracy/script needs testing |
| Spoken output | Installed offline Android voice | Installed compatible voice | Installed compatible voice | Installed compatible voice |

English, Urdu and Punjabi remain selectable alongside any optional configured text language. Extra-language selection does not add a translation direction or voice. The included translator reads English; arbitrary Urdu/Punjabi → English translation or arbitrary language pairs are not promised. Cross-language retrieval uses E5 embeddings rather than assuming forward translation can reverse a question.

Arabic-script Urdu/Punjabi identification is ambiguous; manual selection remains important. Whisper recognizes language identifiers but does not guarantee the desired Punjabi script. Text, retrieval, translation, ASR and TTS are separate capabilities.

## Hardware and memory limits

The application targets Android 6.0/API 23+ and packages ARM64, ARMv7 with NEON, and x86_64 runtimes. ONNX Runtime uses the prepared API-23-compatible JNI wrapper described in [ONNXRUNTIME-ANDROID.md](ONNXRUNTIME-ANDROID.md). Supported targets do not guarantee usable inference on every device. The itel A25 Pro/2 GB phone was not attached for acceptance testing.

Available memory matters more than total RAM alone. Embedding checks for at least 384 MiB available; dedicated translation checks for at least 448 MiB available and no reported low-memory condition. These conservative gates are estimates, not measured peak-memory guarantees. Generation has its own model-size/free-memory guard. Android can still stop a process under pressure, and 32-bit address space adds restrictions.

Start with excerpts, close other apps and inspect Device & storage. Storage cannot fix insufficient RAM. A host prototype or another phone's result does not prove speed, thermals, multilingual accuracy, USB routing or screen-off reliability on the target phone.

## Optional advanced custom models

Baseline use needs no custom import. **Advanced · custom models** allows an administrator to import a compatible GGUF or Whisper GGML file already available through Android's picker, including mounted USB providers where permitted. Custom files are privately copied with bounded buffers and preliminary format/checksum checks. Their external originals are not deleted when a private copy is removed.

A valid header does not establish full tensor compatibility, memory fit or language quality. Custom GGUF context sizes are bounded; smaller contexts generally need less memory. Optional speech execution is limited to 180 MiB files and 60-second recordings. English-only `.en` Whisper models cannot recognize Urdu/Punjabi.

Android TextToSpeech supplies output independently. Pocket AI selects installed voices marked as not requiring a network connection. Missing or unsuitable voices leave text available. Embedding, translation, GGUF and Whisper resources do not install TTS voices.

## Source and build inputs

Project source/source archives exclude standalone weights. Developers reconstruct the bundle with `scripts/fetch-offline-models.py`; verified local-cache-only preparation is supported. The prepared Android ONNX Runtime AAR is another external build input reconstructed by `scripts/prepare-onnxruntime-android.py`, with pinned provenance/checks. See [DEVELOPMENT.md](DEVELOPMENT.md) for commands and paths.

Development preparation may download files when local caches are absent. This adds no network path to the installed app. Public source/downloads exclude private signing keys/passwords. Knowledge backups exclude models and derived embeddings; those are locally re-prepared rather than treated as owner-approved knowledge.

## Evidence and clinical limits

Read [VERIFICATION.md](VERIFICATION.md) for actual build, component and model tests. Synthetic examples, literal checks and host execution are limited evidence, not medical validity or a completed phone UI/audio acceptance run.

Medicine documents are unverified references. Check clinical source quality, drug names, numbers, units, negation and context against the retained English original. The assistant does not automatically diagnose or prescribe; limited urgent-message rules are not universal emergency triage.

Earlier [MODEL-VALIDATION.txt](MODEL-VALIDATION.txt) records failed Urdu/Punjabi checks for the **1.1 Qwen experiment**, a different model/path. It remains historical evidence, not a requirement to download Qwen and not a quality result for the 1.2 translator. The initial Qwen2.5 host result is historical as well.
