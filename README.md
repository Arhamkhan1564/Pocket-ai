# Pocket AI

An offline Android knowledge assistant with two primary modes: **User Mode** for questions and **Training Mode** for administrator-approved knowledge. The application uses Kotlin, Jetpack Compose Material 3, SQLite, local llama.cpp generation and local whisper.cpp transcription.

The APK contains the app and native runtimes, but **no AI model weights or speech voices**. Text teaching, saved-knowledge retrieval, editing, search and backup work without importing a model. Generated answers require a compatible GGUF; voice recognition requires a compatible Whisper model. Android offline speech output depends on installed voices.

## Start here

1. Download [PocketAI.apk](downloads/initial/PocketAI.apk?raw=true), then install it on Android 6.0/API 23 or later.
2. Complete setup, choose the third language and Punjabi script, and create an administrator PIN. Save the displayed recovery code.
3. Open Training Mode, enter a fact or question/answer, review it, and save.
4. Return to User Mode and ask about the saved information.
5. Use Resources to inspect the phone, test audio and optionally import models.

Read [Installation and everyday use](documentation/INSTALLATION.md), [Models and language compatibility](documentation/MODELS.md), and [Backup and recovery](documentation/BACKUP-RECOVERY.md).

## Compatibility and current limits

- The interface, SQLite knowledge assistant and compatible Android offline TTS support `arm64-v8a`, `armeabi-v7a` and `x86_64`.
- Local GGUF and Whisper inference in this build require `arm64-v8a` or `x86_64`. A 32-bit ARM phone receives a clear inference-unavailable message and can use text knowledge retrieval and compatible offline TTS.
- Urdu and Punjabi text, Shahmukhi/Gurmukhi metadata and RTL display are implemented. Urdu/Punjabi recognition quality, model answer quality and installed voice availability require testing on the actual phone. A recognized language code is not evidence of usable recognition or pronunciation.
- The specified 2 GB/16 GB target phone was not attached to the development environment. Performance, thermal behavior, USB hardware, screen-off microphone behavior and long-running reliability must be measured on that phone.
- Optional foreground appliance sessions require a visible user start. Wake-word detection, automatic boot startup, external LEDs and a remote administration dashboard are not shipped.

See the [implementation and verification checklist](documentation/VERIFICATION.md) for the distinction between implemented code, device-dependent acceptance checks and deferred scope.

## Build the Android Studio project

Use Android Studio with a full JDK 17 or 21, Android SDK Platform 35, Build Tools 35.0.0, NDK `27.2.12479018`, CMake `3.22.1`, and the included Gradle 8.9 wrapper. Dependencies and toolchains require internet access during the initial development build; the installed app does not request Internet permission.

Open this directory as a project, set the SDK location in Android Studio, install the SDK/NDK/CMake versions in SDK Manager, and let Gradle sync. Or use the command line after installing Android command-line tools:

```sh
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0" "ndk;27.2.12479018" "cmake;3.22.1"
sdkmanager --licenses
```

Create `local.properties` at the project root with your own absolute SDK location:

```properties
sdk.dir=/absolute/path/to/Android/Sdk
```

Then build and run the JVM suite:

```sh
./gradlew testDebugUnitTest assembleDebug
```

On Windows use `gradlew.bat`. The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Its application ID is `com.pocketai.debug`; release uses `com.pocketai`, so the two installations keep separate data.

Signing keys and signing-backup archives are intentionally excluded from the repository and published downloads. To preserve an existing APK's signing identity, use the original key that you retained privately. To create a separate local signing identity:

```sh
python3 scripts/create-local-signing.py
./gradlew assembleRelease
```

The release APK is `app/build/outputs/apk/release/app-release.apk`. The signing helper refuses to overwrite existing `signing.properties`. Keep `signing/` and `signing.properties` private. A newly generated key cannot update an APK signed with a different key. Back up app data before uninstalling anything. The supplied release is signed with a local self-signed key; it has not been published to an app store.

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

Read [Architecture and development](documentation/DEVELOPMENT.md) for storage, security, native integration and test commands. Native dependency revisions are recorded in `vendor/REVISIONS.txt`.

## Privacy

Pocket AI requests no Internet permission and makes no model downloads or cloud inference calls. Knowledge and conversations are stored in SQLite inside Android's private app sandbox; they are **not database-encrypted**. PINs and recovery codes are salted and hashed. Exported data backups and any privately retained signing backups are plaintext/unencrypted files and should be kept privately. Android's system services and document providers operate under their own permissions. Microphone recording is explicit and temporary.

## Download files and verification

The [initial prerelease](https://github.com/Arhamkhan1564/DR-ai-/releases/tag/v1.0.0-initial) describes this build. Its download files are stored in [downloads/initial](downloads/initial):

- [Signed APK](downloads/initial/PocketAI.apk?raw=true)
- [Debug APK](downloads/initial/PocketAI-debug.apk?raw=true)
- [Source ZIP](downloads/initial/PocketAI-source.zip?raw=true), matching source commit `d4b1504`
- [First-use guide](downloads/initial/PocketAI-Start-Here.txt)
- [SHA-256 checksums](downloads/initial/SHA256SUMS.txt)

The complete current source is also available directly in this repository. Model weights and private signing keys are excluded from the published files.

The recorded final build had 45 JVM/Robolectric test cases: **44 passed, one explicitly skipped, zero failures**. The skipped case documents a Robolectric/Compose dialog-idling limitation; the complete final Android device UI flow was not established. See [verification](documentation/VERIFICATION.md) and [the build report](documentation/BUILD-REPORT.json) for the exact limits.
