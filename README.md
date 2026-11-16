# Pocket AI 1.2 · offline Android RAG

Import English **TXT, DOCX or text PDF** files, index them on the device, and ask about them in **English, Urdu or Punjabi**. Pocket AI retrieves saved passages, preserves the originals, and links answers to their sources. Teaching and importing create a local retrieval index; they do not retrain the model weights.

The full APK contains its baseline AI resources. First setup copies and checks them locally. No account, API key, cloud service or in-app model download is needed, and the app declares no Internet permission.

Download the [full offline APK](https://media.githubusercontent.com/media/Arhamkhan1564/Pocket-ai/main/downloads/offline-v1.2/PocketAI.apk), [source ZIP](downloads/offline-v1.2/PocketAI-source.zip?raw=true), and [installation guide](downloads/offline-v1.2/READ-ME.txt). The APK is stored with Git LFS; use **Download raw file** on GitHub when downloading from the file page. The [download folder](https://github.com/Arhamkhan1564/Pocket-ai/tree/main/downloads/offline-v1.2) contains checksums and build details. Earlier 1.0/1.1 downloads and reports describe historical builds.

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
| Voice input | Multilingual Whisper tiny Q5_1 | Included speech recognition model; recording begins after local readiness checks. |

The 13 pinned model files total **715,732,683 bytes (682.6 MiB)** before app code and packaging. Heavy inference runs one stage at a time to limit peak memory. Read [actual model validation](documentation/OFFLINE-MODEL-VALIDATION.md), [model notices](app/src/main/assets/OFFLINE_MODEL_NOTICES.txt) and the [pinned manifest](app/src/main/assets/offline-model-manifest.json).

## Compatibility and limits

- **Android 6.0/API 23+**, ARM64, ARMv7 **with NEON**, and x86_64. The 64-bit native libraries support 16 KiB page alignment. ARMv7 retains the stock 4 KiB Android C++ runtime for 32-bit devices. Older Android releases, unsupported processors and every possible device are not covered.
- Free memory matters more than advertised RAM. Search and translation check available memory; close other apps when prompted. A 2 GB phone may be slow or unable to run a stage under memory pressure. The itel A25 Pro was not connected for testing.
- Actual host inference found the correct reference for 10/10 known multilingual fixture queries and rejected one unrelated query by threshold. Canonical Kotlin translation matched eight prototype outputs. These small synthetic checks do not establish broad accuracy, phone performance or clinical reliability.
- English is the included document translation source. Other saved languages retain their originals; the included pack does not provide arbitrary reverse translation. Punjabi Shahmukhi is a separate script conversion, not a direct trained target.
- Offline speech output uses Android's installed offline TTS voices. Voice availability, Urdu/Punjabi recognition and pronunciation, microphone behavior, and actual phone latency still need device testing.
- TXT, DOCX and text PDFs are supported. Convert legacy `.doc` files to DOCX. Scanned PDFs require OCR outside this app; password-protected PDFs are rejected. Imports are bounded to 8 MiB and one million extracted characters.
- Medicine documents are reference material. The app can explain sourced information and request context for personal symptom questions; it cannot establish a diagnosis or choose a safe medicine for an individual. It has not been clinically validated. Machine translation can be wrong even when preservation checks pass; inspect the original and consult a qualified clinician for treatment decisions.
- Foreground appliance sessions require a visible user start. Wake-word detection, automatic boot startup, external LEDs and a remote administration dashboard are not included.

See [verification scope](documentation/VERIFICATION.md) and the [1.2 build report](documentation/OFFLINE-BUILD-REPORT.json).

## Build from source

Use JDK 17 or 21, Android SDK Platform 35, Build Tools 35.0.0, NDK `27.2.12479018`, CMake `3.22.1`, and the Gradle 8.9 wrapper. Initial development setup downloads pinned dependencies and model assets; the installed app works offline.

Set `sdk.dir` in your private `local.properties`, install those SDK components, then prepare the external resources before Gradle sync:

```sh
python3 scripts/fetch-offline-models.py
python3 scripts/prepare-onnxruntime-android.py --ndk /absolute/path/to/Android/Sdk/ndk/27.2.12479018
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Weights are kept in `../model-assets`; the prepared Android ONNX dependency is kept in `../runtime-assets`. Neither belongs in the source ZIP. The scripts verify pinned checksums, and Gradle refuses an incomplete resource pack. See [development instructions](documentation/DEVELOPMENT.md) and [API-23 ONNX Runtime preparation](documentation/ONNXRUNTIME-ANDROID.md).

The debug APK uses `com.pocketai.debug`, separately from release `com.pocketai`. To build your own release identity:

```sh
python3 scripts/create-local-signing.py
./gradlew assembleRelease
```

Keep `signing/`, `signing.properties` and signing backups private. Updates require the same signing key as the installed app; a newly generated key cannot update someone else's signed installation. The supplied release is signed with the retained release key and is not an app-store publication.

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
