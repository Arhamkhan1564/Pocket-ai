# Install and use Pocket AI

## Requirements

Use an Android 6.0/API 23 or later device. The APK supports 64-bit ARM, 32-bit ARM and x86_64. Local language-model generation and Whisper recognition require a 64-bit ARM or x86_64 device in this build. A 32-bit phone can still use text teaching, saved-knowledge answers and an installed compatible offline voice.

A 2 GB RAM specification alone does not establish model compatibility. Android and other apps consume memory; inspect available RAM and storage in Resources → Device & storage before selecting a model.

## Install the APK

1. Transfer `PocketAI.apk` to the phone and open it in Android's file manager.
2. If Android asks, allow that file manager to install unknown apps. Complete the installation, then open Pocket AI.
3. Complete the welcome screens. Urdu and Punjabi are configured first; choose the third language and Punjabi Shahmukhi or Gurmukhi script.
4. Create a PIN containing 6–12 digits. Keep the displayed recovery code somewhere private. It is needed if the PIN is forgotten.
5. The final setup step shows Android/API, CPU architecture, RAM, free storage, audio hardware and checked per-language offline voice status. Optional tests record two seconds of microphone levels and play a short tone; confirm that you heard the tone before its result is labeled audible. These checks do not establish speech-recognition quality. You can finish with text only without granting microphone access.
6. Open Resources → Device & storage for fuller readings including battery, thermal state, audio devices and USB-host capability.

No account, terminal or Android Studio is needed to use the APK. No model is needed for the first text teaching and retrieval test. Ordinary use needs no internet once any desired model files and offline voices have been installed.

The release APK is signed with a local key. Keep the separate `PocketAI-signing-backup.zip` privately if you want future builds to update the same release installation. Developer instructions are in the project README.

## Teach a fact

1. Enter Training Mode and unlock with the administrator PIN.
2. Select the knowledge language and category. Optionally enter a question as well as its answer.
3. Enter the content, review the confirmation, and save. Duplicate and potential conflicting question/answer records are checked.
4. Return to User Mode and ask a question containing the saved topic or wording.

The app stores verified records in SQLite. Saving a fact changes the knowledge database, not the parameters of an AI model. Ordinary User Mode conversation does not become verified knowledge.

Memory tools let an administrator search, browse, edit, correct and delete records, manage categories, and inspect training history. Editing and deletion require authorization. Leaving the foreground locks administration and returns to User Mode; an explicit Android document-picker flow preserves only the current short session. Sessions also expire after inactivity.

## Import text

In Training Mode, choose Import TXT and select a UTF-8 plain-text file. Review the import before confirming. Paragraphs are split into editable source-linked knowledge chunks, and exact duplicates are skipped. The current import is limited to approximately 1 MB and 500 chunks.

PDF, Word, image OCR and arbitrary binary documents are not supported. Convert a document to UTF-8 text first. Treat imported content as material to review, especially if it includes instructions or claims from another author.

## Ask questions

Type a question in User Mode. Matching saved records are returned with their sources. If a compatible local GGUF is imported and selected, the app supplies retrieved records as context to that model and labels the response engine. Model-only claims are unverified. With no matching saved knowledge and no model, the app tells you that it has no matching knowledge.

Without a language model, a saved fact is returned in its original language. Selecting another response language does not translate or overwrite your records. Cross-language semantic retrieval and translation depend on a suitable model; the database search itself uses normalized lexical terms.

Repeat, slower speech and configured language-switch commands are available through supported command phrases. The repeat control also works directly from the interface. Stop speech with the stop-speaking control. The UI language is primarily English; saved content supports Unicode and RTL rendering.

## Use voice

1. On a supported 64-bit device, import and select a multilingual Whisper tiny/base model in Resources → Local models.
2. Tap the microphone and grant microphone permission when Android asks.
3. Speak, then tap again to stop and transcribe. Ordinary tap-to-talk recording is bounded to 60 seconds.
4. Review transcribed training content before saving it. In User Mode, review the recognized question and tap Send to use the same local assistant flow as typed text.

Recording uses 16 kHz mono PCM. Audio is temporarily buffered and discarded after transcription or cancellation; recordings are not retained as files. Silent or very short recordings are rejected. Recognition can be slow on an old processor. Do not infer Punjabi/Urdu quality from English test results.

For output, Resources → Voice & audio checks installed offline voices separately for each configured language. Test the speaker, adjust speed and volume, and choose an available microphone route. Android controls the current media output route. If the selected language has no offline voice, the app keeps the text response and reports the missing capability. Model imports do not install TTS voices.

## Optional appliance session

Start an appliance session while the app is visible and microphone permission is granted. The foreground notification makes microphone use visible and provides a way to stop the session. The implementation uses short recording windows, sequential local transcription/answering and a bounded wake lock; it does not provide a wake word.

The session is limited to 30 minutes, with recording windows of about six seconds. Android can still stop the app or restrict microphone access when the display turns off. Verify behavior on the target phone before relying on it without a screen. Start-on-boot, indefinite unattended listening and crash-proof appliance operation are not provided.

## Troubleshooting

| Symptom | Action |
| --- | --- |
| APK will not install | Confirm Android API 23+, supported architecture, sufficient storage and permission to install from the chosen file manager. |
| Update is rejected | Use the APK signed by the same key. Export a backup before any uninstall/reinstall; uninstalling deletes private app data. |
| No saved answer | Check the stored wording, language and category. Try lexical terms from the fact or enter a matching question/answer. |
| Model will not load | Check the reported ABI, available RAM and selected format. Try a smaller model. A valid header does not establish tensor/runtime compatibility. |
| No voice recognition | Use a supported 64-bit device, grant microphone permission and select a multilingual Whisper tiny/base file. |
| No spoken output | Install an offline voice for the selected language using Android TTS settings, test it and check media volume/output routing. |
| Administrator locked | Use the PIN or private recovery code. Failed attempts trigger increasing delays. |
| Missing USB file/device | Confirm USB host support, power, Android permissions and a mounted provider visible in the system file picker. |

See [Backup and recovery](BACKUP-RECOVERY.md) before resetting, uninstalling or moving devices.
