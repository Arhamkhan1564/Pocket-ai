# Models and language compatibility · 1.1

## Experimental English model and failed multilingual checks

The tested small candidate passed a synthetic English RAG fixture but **failed Urdu and Punjabi answer/translation checks**. It is not a validated multilingual or clinical translation option. See [MODEL-VALIDATION.txt](MODEL-VALIDATION.txt) for actual results. Resources → Local models offers its browser download for English experiments only:

- File: `Qwen3-0.6B-Q4_K_M.gguf`.
- URL: https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/50968a4468ef4233ed78cd7c3de230dd1d61a56b/Qwen3-0.6B-Q4_K_M.gguf
- Size: **396,705,472 bytes** (about 379 MiB).
- SHA-256: `ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a`.

Download in the browser, return to the app, unlock administration, import the GGUF and select it. The APK does not include an LLM and does not download files itself. A model of this size can still exceed the available memory of a 2 GB phone. This general model is not medically validated; verify source names, medicine names, quantities and units against the cited original. Do not use its language metadata as evidence of usable Urdu/Punjabi translation. A different, more capable model needs its own real tests and may not fit the itel A25 Pro.

## Historical host candidate from the initial release

The official Qwen `Qwen2.5-0.5B-Instruct-GGUF` Q4_K_M file was tested through the exact JNI adapter on a Linux x86_64 host. It answered a question using the supplied saved access phrase correctly. This establishes one real instruct-model/chat-template path; it does not establish performance or language quality on the target phone.

- Official file: https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/9217f5db79a29953eb74d5343926648285ec7e67/qwen2.5-0.5b-instruct-q4_k_m.gguf
- Size: 491,400,032 bytes. License: Apache 2.0, as recorded by the official model card; review its current terms before use.
- SHA-256: `74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db`.
- Host test: context 1024, four threads, 31.528 seconds. The test was English. The model omitted the requested inline citation, so the app's retrieved-source labels remain necessary.
- A 2 GB phone may still lack enough free RAM. Run diagnostics first; the app can refuse loading when available RAM is insufficient. Urdu/Punjabi generation quality remains unverified.

This historical test is retained as evidence for the original runtime path. It is not a test result for the 1.1 RAG changes or the Qwen3 experiment above. The file is an optional, separately obtained candidate, not part of the APK or source archive.

## What is included

The 1.1 APK includes CPU inference code for llama.cpp and whisper.cpp and one **multilingual Whisper tiny q5_1 speech-recognition model**. It includes no LLM/GGUF, dedicated translation pack, wake-word model or TTS voice data. Pocket AI has no Internet permission; optional download links open a separate browser.

Included speech provenance:

- Filename: `ggml-tiny-q5_1.bin`.
- Official pinned URL: https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-tiny-q5_1.bin
- Size: **32,152,673 bytes** (30.7 MiB).
- SHA-256: `818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7`.
- License: MIT, with the model license and provenance included under `app/src/main/assets/licenses/`.

When runtime compatibility checks succeed, startup verifies and copies the asset into private model storage. ARM64, ARMv7 with NEON, and x86_64 inference libraries are included. It selects the included model only when there is no valid user selection. Removing its private copy is respected on later starts; use Restore included speech model in Local models to copy it back. This frees/recreates the private copy; the APK still contains the bundled asset. Unsupported CPU features produce a clear compatibility result and keep text features usable.

Project source files and source archives exclude standalone weight binaries. APK distribution files include the speech asset. Developers fetch this exact pinned speech asset with `scripts/fetch-bundled-speech.py` before building; see [DEVELOPMENT.md](DEVELOPMENT.md). The build verifies the asset before packaging.

The pinned native source and licenses are recorded in `vendor/REVISIONS.txt`:

- llama.cpp: `ae93877c8f3bf9f39f0ee4ffb3ef1697c416a7c3`, MIT.
- whisper.cpp: `5922450fbce1ed9d1648babd0d5ac4520055b436`, MIT.

Whisper is linked against the pinned shared GGML implementation from llama.cpp. Model weights have separate licenses and terms; review the source model's card before using or redistributing them.

## Import workflow

1. For a language model or optional replacement speech model, download the file with a computer/separate browser and transfer it to the phone or mounted USB storage. The included speech model normally needs no manual import.
2. Unlock administrator access in Resources → Local models.
3. Choose the correct import kind and select the actual model file using Android's file picker.
4. Wait for the copy and validation to finish. Imported files are copied into private internal storage and indexed with their SHA-256 checksum.
5. Select the installed model. Use Device & storage to check available memory before testing.

GGUF metadata/tensor header checks and Whisper header checks reject clearly incompatible or truncated files. They do not guarantee that all tensors are valid, that a model fits in memory, or that its answers are useful. Final validation occurs when the native runtime loads the file. Removing an unused model frees its private copy; the original selected source file is not deleted.

## Language models

Use a small quantized **GGUF** supported by the pinned llama.cpp revision. A tokenizer-only file, ZIP, `.safetensors` file or a Hugging Face repository folder is not a loadable GGUF.

For exploration, the official [Qwen2.5-0.5B-Instruct GGUF repository](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF) provides small quantized variants. This is a candidate for testing, not a guarantee that a 2 GB phone has enough available RAM or that Urdu/Punjabi generation is adequate. A smaller model can have poor multilingual quality. Do not use a large model simply because there is enough storage to import it.

Choose a 256- or 512-token context initially. Larger contexts require more memory. The app bounds answers, limits retained conversation context, executes inference on a background dispatcher, serializes speech and text inference, and unloads the native model/context after each request. Reloading reduces idle memory at the cost of latency.

Generation is refused if current available RAM fails the app's conservative guard. That guard is an estimate; Android's memory killer can still terminate an app under pressure. There is no proven performance claim for the unattached 2 GB target device.

## Speech recognition

The included multilingual tiny q5_1 model is prepared automatically on a compatible device. Its actual memory use, recognition quality and latency still need measurement on the itel A25 Pro. Optionally replace it with a compatible whisper.cpp **multilingual** `ggml-*.bin` model. Official upstream sources are:

- [Model documentation and checksums](https://github.com/ggml-org/whisper.cpp/tree/master/models)
- [Multilingual tiny](https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin)
- [Multilingual base](https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin)

Do **not** choose `tiny.en` or `base.en` for Urdu or Punjabi: `.en` variants are English-only. An explicitly selected English-only file remains usable for English, but Urdu/Punjabi microphone preflight requests a multilingual selection before recording. Keep filenames/model provenance available for diagnosis. Runtime execution limits speech files to at most 180 MiB, intended for tiny/base profiles, and recordings to 60 seconds. A model accepted by the generic import registry may still exceed the speech execution limit.

The pinned Whisper source contains `ur` and `pa` language identifiers. That establishes recognized language identifiers, not sufficient accuracy, script control or real-device language acceptance. Urdu and Punjabi must each be tested with representative speakers and noise conditions. Punjabi output script may require manual correction; changing the app's Punjabi script does not retrain the recognizer or guarantee transliteration. The app uses explicit selected language when transcribing rather than a guaranteed automatic language detector.

## Speech output

Android TextToSpeech supplies output. Pocket AI selects only installed voices marked as not requiring a network connection. Availability, dialect, pronunciation and script support are checked independently of text and ASR. A language model or Whisper model does not add a TTS voice.

Install desired offline voices using the phone's Android TTS settings, then use the per-language status and speaker test in Voice & audio. If no compatible offline voice is installed, responses remain visible as text. Punjabi Shahmukhi and Gurmukhi voice tags may have different availability; neither is assumed to work.

## Compatibility matrix

| Capability | Urdu | Punjabi Shahmukhi | Punjabi Gurmukhi | Configurable extra language |
| --- | --- | --- | --- | --- |
| Unicode storage and original text | Implemented | Implemented | Implemented | Implemented |
| Script metadata and appropriate text direction | Implemented | Implemented | Implemented | Script-dependent |
| Lexical knowledge search | Implemented; test actual content | Implemented; manual language selection | Implemented | Implemented |
| Local generated answer | Requires a capable GGUF; quality unverified | Requires a capable GGUF; quality unverified | Requires a capable GGUF; quality unverified | Depends on model |
| Offline recognition | Multilingual Whisper path; device accuracy unverified | `pa` path; accuracy/script unverified | `pa` path; accuracy/script unverified | Depends on Whisper language support |
| Offline spoken output | Requires installed compatible voice | Requires installed compatible voice | Requires installed compatible voice | Requires installed compatible voice |
| Translation/transliteration | No dedicated engine | No dedicated engine | No dedicated engine | No dedicated engine |

English, Urdu and Punjabi remain directly selectable even when an extra language was configured. Arabic-script language detection is ambiguous; select Urdu/Punjabi manually when necessary. The database uses lexical ranking rather than embeddings. A capable local language model can help rewrite a question for English-document retrieval and synthesize a cited answer in the requested language, but poor translation or retrieval can still omit relevant evidence. Without that model, original-text results and an explicit translation-unavailable status remain available. If no supporting passage is retrieved, strict RAG abstains even with a model selected. Citations identify filenames/chunks, not exact PDF pages.

## Medicine-reference limits

An imported medicine leaflet becomes searchable source text, not a medically verified model update. The app supports sourced explanations and clarification of symptom questions; it does not automatically diagnose or prescribe. A general model can mistranslate medicine names, numbers and units. Compare translated answers with the cited original and use qualified clinical judgment for an individual's care. No curated drug database or validated prescribing rules are bundled.

## Target-device benchmark

Record Android/API/ABI, total and available RAM, model name/checksum/quantization, context size, storage usage, startup/load time, first and repeated answer latency, speech latency, peak memory, thermal state and failures. Test with internet disabled and after restarting. Run English, Urdu, Punjabi in each intended script and any extra language separately. Results for another model, emulator or language do not establish target-phone acceptance.
