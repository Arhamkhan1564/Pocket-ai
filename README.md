# Pocket AI 1.1 · offline document assistant

An offline Android knowledge assistant with two primary modes: **User Mode** for questions and **Training Mode** for administrator-approved knowledge. The application uses Kotlin, Jetpack Compose Material 3, SQLite, local llama.cpp generation and local whisper.cpp transcription.

Import English TXT, Word DOCX or text-based PDF documents, review the extracted text and save a local retrieval index. Ask questions in **English, Urdu or Punjabi**; these choices remain available alongside the optional language. Document language is separate from answer language, and original text is preserved. Saved passages cite their filename/source. A capable multilingual local GGUF is required for generated or translated answers; without it, the app returns matching original text and reports that translation is unavailable.

**Translation limitation:** the small Qwen3 model tested for this build failed Urdu/Punjabi answer and translation checks. Reliable translation on the 2 GB target phone is not established; the app keeps the original evidence when checks fail. See [actual model results](documentation/MODEL-VALIDATION.txt).

The APK includes the **multilingual Whisper tiny q5_1 speech model** (30.7 MiB), prepared automatically when runtime compatibility checks succeed. No language model/LLM or Android TTS voice data is bundled. Text teaching, retrieval, editing, search and backup work without a GGUF. Offline speech output depends on installed Android voices.

Repository: [Arhamkhan1564/Pocket-ai](https://github.com/Arhamkhan1564/Pocket-ai). Download [the APK](downloads/rag-v1.1/PocketAI.apk?raw=true), [the source ZIP](downloads/rag-v1.1/PocketAI-source.zip?raw=true), or all files in [`downloads/rag-v1.1/`](https://github.com/Arhamkhan1564/Pocket-ai/tree/main/downloads/rag-v1.1) and the [`v1.1.0-rag` prerelease](https://github.com/Arhamkhan1564/Pocket-ai/releases/tag/v1.1.0-rag). The [1.1 build report](documentation/RAG-BUILD-REPORT.json) records 94 passing tests and one skipped test. Physical-phone and reliable medical translation acceptance remain outstanding; initial-release reports are historical.

## Start here

1. Install `PocketAI.apk` on Android 6.0/API 23 or later.
2. Complete setup, choose any extra language and Punjabi script, and create an administrator PIN. Save the displayed recovery code.
3. Open Training Mode → Import document. Pick TXT, DOCX or a text PDF, check the preview/source language (English by default), then Save & index document.
4. Return to User Mode and choose English, Urdu or Punjabi. Ask about the document; use a compatible local language model for generated/translated answers.
5. In Resources → Local models, check included speech setup and optionally import a GGUF. The browser link offers a pinned Qwen3 0.6B English demo. It failed Urdu/Punjabi checks and is not recommended for those translations.

Read [Installation and everyday use](documentation/INSTALLATION.md), [Models and language compatibility](documentation/MODELS.md), and [Backup and recovery](documentation/BACKUP-RECOVERY.md).

## Compatibility and current limits

- The interface, SQLite knowledge assistant and compatible Android offline TTS support `arm64-v8a`, `armeabi-v7a` and `x86_64`.
- Local GGUF/Whisper libraries are included for ARM64, ARMv7 with NEON, and x86_64. Runtime CPU and memory checks determine which models can be used. Unsupported CPUs keep text knowledge retrieval and compatible offline TTS usable.
- Urdu and Punjabi text, Shahmukhi/Gurmukhi metadata and RTL display are implemented. Urdu/Punjabi recognition quality, model answer quality and installed voice availability require testing on the actual phone. A recognized language code is not evidence of usable recognition or pronunciation.
- The identified itel A25 Pro/2 GB target phone was not attached to the development environment. Its actual ABI/CPU features, available memory, performance and audio behavior need on-device checks. Android 6+/supported ABI coverage does not guarantee that every phone can run every model.
- Optional foreground appliance sessions require a visible user start. Wake-word detection, automatic boot startup, external LEDs and a remote administration dashboard are not shipped.
- Legacy binary `.doc` files must be converted to `.docx`. Scanned/image PDFs need OCR outside this app; password-protected PDFs are rejected. Imported documents are bounded to 8 MiB and one million extracted characters.
- Medicine documents are reference material, not verified clinical training. The assistant supports sourced explanations and asks for context when a question describes personal symptoms; it does not automatically diagnose, prescribe, or validate a treatment. General models have not been medically validated.

See the [implementation and verification checklist](documentation/VERIFICATION.md) for the distinction between implemented code, device-dependent acceptance checks and deferred scope.

## Build the Android Studio project

Use Android Studio with a full JDK 17 or 21, Android SDK Platform 35, Build Tools 35.0.0, NDK `27.2.12479018`, CMake `3.22.1`, and the included Gradle 8.9 wrapper. Dependencies and toolchains require internet access during the initial development build; the installed app does not request Internet permission.

Open this directory as a project, set the SDK location in Android Studio, install the SDK/NDK/CMake versions in SDK Manager, then run the speech-model fetch command below before Gradle sync. Or use the command line after installing Android command-line tools:

```sh
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0" "ndk;27.2.12479018" "cmake;3.22.1"
sdkmanager --licenses
```

Create `local.properties` at the project root with your own absolute SDK location:

```properties
sdk.dir=/absolute/path/to/Android/Sdk
```

Project source files and source archives exclude standalone model weights; the distributed APK includes its speech asset. Fetch and verify the included speech asset before building the same APK configuration (see the exact fetch command in [DEVELOPMENT.md](documentation/DEVELOPMENT.md)). Then build and run the JVM suite:

```sh
python3 scripts/fetch-bundled-speech.py
./gradlew testDebugUnitTest assembleDebug
```

On Windows use `gradlew.bat`. The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Its application ID is `com.pocketai.debug`; release uses `com.pocketai`, so the two installations keep separate data.

Public source/downloads exclude signing keys and signing-backup archives. To preserve an existing APK's update identity, use the same privately retained signing key and local signing configuration. Otherwise create a new local identity:

```sh
python3 scripts/create-local-signing.py
./gradlew assembleRelease
```

The release APK is `app/build/outputs/apk/release/app-release.apk`. The signing helper refuses to overwrite existing `signing.properties`. Keep `signing/`, `signing.properties` and any private backup of them outside public distributions. A newly generated key cannot update an APK signed with a different key. Back up app data before uninstalling anything. The supplied release is signed with a local self-signed key; it has not been published to an app store.

## Project layout

| Directory | Responsibility |
| --- | --- |
| `app/src/main/java/com/pocketai/ui` | Compose navigation, setup, conversation, training and resource tools |
| `data`, `security` | SQLite, retrieval indexes, history, backup and administrator authorization |
| `assistant`, `engine` | Language helpers, retrieval orchestration, prompt construction and replaceable inference interface |
| `voice`, `hardware`, `models` | Bounded recording, offline TTS, live audio routes, diagnostics and model imports |
| `app/src/main/cpp` | JNI bridge and CPU runtime integration |
| `vendor` | Pinned llama.cpp/whisper.cpp source and their licenses |
| `app/src/test`, `app/src/androidTest` | JVM tests and optional real-model device tests |

Read [Architecture and development](documentation/DEVELOPMENT.md) for document extraction, retrieval, security, native integration and test commands. Native dependency revisions are recorded in `vendor/REVISIONS.txt`.

## Privacy

Pocket AI requests no Internet permission and makes no in-app model downloads or cloud inference calls. Optional model links open your external browser. Included speech is copied from the APK into private storage with checksum verification. Knowledge and conversations are stored in SQLite inside Android's private app sandbox; they are **not database-encrypted**. PINs and recovery codes are salted and hashed. Exported knowledge backups are plaintext and should be kept privately. Signing credentials are excluded from public source/downloads. Android's system services and document providers operate under their own permissions. Microphone recording is explicit and temporary; voice setup is checked before a conversation recording starts.
