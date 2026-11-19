# Pocket AI 1.3 · offline Android RAG and voice preview

Import English **TXT, DOCX or text PDF** files, index them on the device, and ask about them in **English, Urdu or Punjabi**. Pocket AI retrieves saved passages, preserves the originals, and links answers to their sources. Teaching and importing create a local retrieval index; they do not retrain the model weights.

The full APK contains its baseline AI resources. First setup copies and checks them locally. No account, API key, cloud service or in-app model download is needed, and the app declares no Internet permission.

Download the [Pocket AI Voice Preview APK](https://media.githubusercontent.com/media/Arhamkhan1564/Pocket-ai/main/downloads/voice-v1.3/PocketAI-Voice-Preview.apk), [source ZIP](https://raw.githubusercontent.com/Arhamkhan1564/Pocket-ai/main/downloads/voice-v1.3/PocketAI-source.zip), and [installation guide](downloads/voice-v1.3/READ-ME.txt). The full APK includes all offline model resources and is stored with Git LFS.

**This preview installs separately as `com.pocketai.voicepreview`.** The original private signing key became unavailable after the build workspace reset. Keep your existing Pocket AI installation. Export its knowledge backup and import that backup into the preview after setting up its own PIN. A different signing key cannot update the original installation. The previous [1.2.1 APK](https://media.githubusercontent.com/media/Arhamkhan1564/Pocket-ai/main/downloads/offline-v1.2.1/PocketAI.apk) remains available.

## Voice changes in 1.3

Included automatic recognition uses English Whisper tiny, Urdu-tuned Whisper base and a Punjabi CTC recognizer that outputs Gurmukhi directly. Punjabi Shahmukhi is a labelled display conversion. A small offline speech detector runs before Whisper; its session closes before the recognizer loads. Custom Whisper selections are preserved, and disabling recognition stays disabled after reopening.

Every manual Urdu/Punjabi result opens an editable review before it can fill a question or training draft. Check names and quantities: the four Urdu host clips improved word-error rates overall but still lost or changed spoken numbers. Punjabi output uses the intended script but still contains substantial word errors. The detector rejects the tested noise/tone controls; it does not establish that recognized words are correct. Hands-free uses unreviewed transcripts. Read the [complete voice evidence and limits](documentation/VOICE-VALIDATION-1.3.md). Phone and fluent-speaker acceptance remain pending.

The signed preview passed 215 unit tests with one existing UI test skipped. Debug and preview lint passed with zero errors and 19 warnings each. The final APK audit verified all 17 model checksums, the three ABIs, alignment, preview identity and absence of Internet permission. See the [APK audit](documentation/VOICE-1.3-APK-AUDIT.json) and [build report](documentation/VOICE-1.3-BUILD-REPORT.json).

## Checks and fixes in 1.2.1

Keyboard Send now obeys the active/pending recording guard. An oversized saved note no longer prevents later references from being indexed: its original remains readable and keyword-searchable, its pending status stays visible, and Offline tools explains how to split it. Editing it into a supported passage allows indexing again.

Additional checks passed 171 unit tests with one explicit skip and all 11 model-backed host workflow phases. In a small synthetic probe, the expected reference was present for 14 of 16 known questions and none of 32 negative questions returned a false match. Two known questions still abstained, and some answers included extra references, including unrelated medicine passages; see the full records rather than treating these counts as universal accuracy. The reports separately retain observed speech-recognition failures and checks requiring a real Android phone. The APK remains a prerelease while device acceptance is pending.

## Start here

1. Install the full APK on a supported Android 6.0/API 23 or newer device. Allow several GB of free storage for installation, local resources and documents.
2. Complete setup, choose Punjabi Shahmukhi or Gurmukhi, set an administrator PIN, and save the recovery code. Let Offline tools finish local preparation.
3. Open Training Mode → Import document. Choose an English file, review its extracted text and save it. Let local indexing finish.
4. Return to User Mode and choose English, Urdu or Punjabi. Ask about the saved reference. Open a citation to inspect the original passage or request its translation.
5. Use **Fast cited passages** by default. Optional English explanations use a small, slower local generator and fall back to saved evidence when checks fail.

Read [Installation](documentation/INSTALLATION.md), [Models](documentation/MODELS.md), and [Backup and recovery](documentation/BACKUP-RECOVERY.md).

## Included offline resources

| Role | Included resource | Behavior |
| --- | --- | --- |
| Multilingual retrieval | E5-small int8 ONNX | English/Urdu/Punjabi questions retrieve saved English passages; cosine scores and source checks limit weak matches. |
| Translation | IndicTrans2 dist-200M int8 ONNX | English references → Urdu or Gurmukhi Punjabi. Identifiers are protected and restored; quantities, units and prohibitions are checked. |
| Shahmukhi display | Original local Punjabi script converter | Converts the Punjabi translation to Shahmukhi, with a visible conversion label. Spelling and dialect coverage are limited. |
| Optional English synthesis | SmolLM2-360M Q4_K_M | Conditional source-based explanations; fast excerpts remain the default. |
| Voice input | English tiny / Urdu base Q5_1; Punjabi CTC int8; Silero VAD | Included language-specific recognition, speech-presence gate before Whisper, editable Urdu/Punjabi review. |

The 17 pinned model files total **899,719,726 bytes (858.0 MiB)** before app code and packaging. Heavy inference runs one stage at a time to limit peak memory. Read [actual model validation](documentation/OFFLINE-MODEL-VALIDATION.md), [model notices](app/src/main/assets/OFFLINE_MODEL_NOTICES.txt) and the [pinned manifest](app/src/main/assets/offline-model-manifest.json).

## Compatibility and limits

- **Android 6.0/API 23+**, ARM64, ARMv7 **with NEON**, and x86_64. The 64-bit native libraries support 16 KiB page alignment. ARMv7 retains the stock 4 KiB Android C++ runtime for 32-bit devices. Older Android releases, unsupported processors and every possible device are not covered.
- Free memory matters more than advertised RAM. Search and translation check available memory; close other apps when prompted. A 2 GB phone may be slow or unable to run a stage under memory pressure. The itel A25 Pro was not connected for testing.
- Actual host inference found the correct reference for 10/10 known multilingual fixture queries and rejected one unrelated query by threshold. Canonical Kotlin translation matched eight prototype outputs. These small synthetic checks do not establish broad accuracy, phone performance or clinical reliability.
- English is the included document translation source. Other saved languages retain their originals; the included pack does not provide arbitrary reverse translation. Punjabi Shahmukhi is a separate script conversion, not a direct trained target.
- Offline speech output uses Android's installed offline TTS voices. [Host voice checks](documentation/VOICE-VALIDATION-1.3.md) show native-script Punjabi and relative Urdu improvements, with substantial remaining errors; recognition remains experimental. Review and correct transcripts, especially names and numbers. Voice availability, microphone behavior, pronunciation and phone latency still need device testing.
- TXT, DOCX and text PDFs are supported. Convert legacy `.doc` files to DOCX. Scanned PDFs require OCR outside this app; password-protected PDFs are rejected. Imports are bounded to 8 MiB and one million extracted characters.
- Medicine documents are reference material. The app can explain sourced information and request context for personal symptom questions; it cannot establish a diagnosis or choose a safe medicine for an individual. It has not been clinically validated. Machine translation can be wrong even when preservation checks pass; inspect the original and consult a qualified clinician for treatment decisions.
- Foreground appliance sessions require a visible user start. Wake-word detection, automatic boot startup, external LEDs and a remote administration dashboard are not included.

See [verification scope](documentation/VERIFICATION.md), the [1.2.1 build report](documentation/OFFLINE-1.2.1-BUILD-REPORT.json), and [additional offline acceptance checks](documentation/OFFLINE-ACCEPTANCE-1.2.1.md).

## Build from source

Use JDK 17 or 21, Android SDK Platform 35, Build Tools 35.0.0, NDK `27.2.12479018`, CMake `3.22.1`, and the Gradle 8.9 wrapper. Initial development setup downloads pinned dependencies and model assets; the installed app works offline.

Set `sdk.dir` in your private `local.properties`, install those SDK components, then prepare the external resources before Gradle sync:

```sh
python3 scripts/fetch-offline-models.py
python3 scripts/prepare-onnxruntime-android.py --ndk /absolute/path/to/Android/Sdk/ndk/27.2.12479018
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Weights are kept in `../model-assets`; the prepared Android ONNX dependency is kept in `../runtime-assets`. Neither belongs in the source ZIP. The scripts verify pinned checksums, and Gradle refuses an incomplete resource pack. See [development instructions](documentation/DEVELOPMENT.md) and [API-23 ONNX Runtime preparation](documentation/ONNXRUNTIME-ANDROID.md).

The debug APK uses `com.pocketai.debug`. The signed voice preview uses `com.pocketai.voicepreview` and a separate private preview key. See [preview build instructions](documentation/VOICE-PREVIEW-BUILD.md). Standard `com.pocketai` release assembly requires the original signing certificate and refuses replacement keys. For a separately named derivative, configure a distinct application ID and retain its signing identity privately.

Keep keystores, signing properties and signing backups private. Updates require the same key as the installed app. Neither the signing credentials nor model binary bodies belong in the source ZIP; the build fetcher retrieves and verifies the separately published model resource.

## Project layout

| Directory under `app/src/main/java/com/pocketai` | Responsibility |
| --- | --- |
| `ui` | Setup, conversations, source library, training and resource controls |
| `data`, `security` | SQLite documents, versioned embeddings, history, backup and PIN authorization |
| `rag`, `retrieval`, `translation` | Offline orchestration, E5 semantic search, NMT and script conversion |
| `assistant`, `engine` | Source grounding, response checks, serialized native inference and tokenizers |
| `voice`, `hardware`, `models` | Recording, Android offline TTS, diagnostics, bundled pack preparation and model imports |

Native code is in `app/src/main/cpp`; pinned llama.cpp, whisper.cpp and inference-only SentencePiece sources are in `vendor`. Revisions and licenses accompany the source.

## Privacy

Pocket AI has no Internet permission and makes no cloud inference or translation calls. Baseline files are copied from the APK into private storage with integrity checks. Knowledge, embeddings and conversations live in Android's private app sandbox; the database is **not encrypted**. Exported backups are plaintext and should be kept privately. PINs and recovery codes are salted and hashed. Recording is explicit and temporary. Android system services, document providers and optional external browser links operate under their own permissions.
