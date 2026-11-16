# Install and use Pocket AI 1.1

The current repository is [Arhamkhan1564/Pocket-ai](https://github.com/Arhamkhan1564/Pocket-ai). The RAG update uses `downloads/rag-v1.1/` and prerelease tag `v1.1.0-rag`; use the APK and checksums from the same release together. Earlier build reports describe the original release.

## Requirements

Use Android 6.0/API 23 or later. The APK includes local inference for ARM64, ARMv7 with NEON, and x86_64. Runtime CPU and memory checks determine compatibility; an unsupported CPU can still use text teaching, saved-knowledge answers and an installed compatible offline voice. Broad Android/ABI coverage is not a guarantee for every phone or model.

A 2 GB RAM specification alone does not establish model compatibility. The user's itel A25 Pro has not been benchmarked or inspected in person. Android and other apps consume memory; inspect its actual ABI/CPU features, available RAM and storage in Resources → Device & storage before selecting a model.

## Install the APK

1. Transfer `PocketAI.apk` to the phone and open it in Android's file manager.
2. If Android asks, allow that file manager to install unknown apps. Complete the installation, then open Pocket AI.
3. Complete the welcome screens. English, Urdu and Punjabi are always available; choose any additional configured language and Punjabi Shahmukhi or Gurmukhi script.
4. Create a PIN containing 6–12 digits. Keep the displayed recovery code somewhere private. It is needed if the PIN is forgotten.
5. The final setup step shows Android/API, CPU architecture, RAM, free storage, audio hardware and checked per-language offline voice status. Optional tests record two seconds of microphone levels and play a short tone; confirm that you heard the tone before its result is labeled audible. These checks do not establish speech-recognition quality. You can finish with text only without granting microphone access.
6. Open Resources → Device & storage for fuller readings including battery, thermal state, audio devices and USB-host capability.

No account, terminal or Android Studio is needed to use the APK. The included multilingual Whisper tiny speech model prepares automatically when runtime compatibility checks succeed. No language model is needed for the first text teaching and original-text retrieval test. Generated or translated answers need a separately imported compatible GGUF and sufficient memory; spoken answers need an installed offline voice. Ordinary use then needs no internet.

The release APK is signed with a local key. Signing keys and signing-backup archives are excluded from public source/downloads. Future in-place updates require the same privately retained signing identity; generating a new key cannot update an older APK. Developer instructions are in the project README.

## Teach a fact

1. Enter Training Mode and unlock with the administrator PIN.
2. Select the knowledge language and category. Optionally enter a question as well as its answer.
3. Enter the content, review the confirmation, and save. Duplicate and potential conflicting question/answer records are checked.
4. Return to User Mode and ask a question containing the saved topic or wording.

The app stores verified records in SQLite. Saving a fact changes the knowledge database, not the parameters of an AI model. Ordinary User Mode conversation does not become verified knowledge.

Memory tools let an administrator search, browse, edit, correct and delete records, manage categories, and inspect training history. Editing and deletion require authorization. Leaving the foreground locks administration and returns to User Mode; an explicit Android document-picker flow preserves only the current short session. Sessions also expire after inactivity.

## Import TXT, DOCX or PDF

1. Enter Training Mode and unlock administration.
2. Choose Import document, then select an English UTF-8 TXT, Word `.docx` or text-based PDF file.
3. Review the filename, format, page count where available, warnings and extracted-text preview. Source language defaults to English independently of your answer language; change it if the document uses another supported language.
4. Choose Save & index document. The app splits text into source-linked passages and indexes them locally, skipping exact duplicates. Retrieved citations identify filename and chunk number; the preview's PDF page count is a file summary, not page-accurate citation.
5. Return to User Mode, choose English, Urdu or Punjabi, and ask about the document. A compatible multilingual local AI model is needed to generate answers and translate English evidence. Saved text search itself remains available without one.

Files are bounded to 8 MiB, one million extracted characters, 1,000 passages and 100 PDF pages. Legacy binary `.doc` must be converted to `.docx` or UTF-8 TXT first. Scanned/image PDFs need OCR outside this app; there is no OCR engine. Password-protected PDFs are rejected. DOCX imports readable document text/footnotes; images, embedded files and macros are not imported or executed. Extraction does not guarantee that tables or complex layouts retain their original reading order, so check the preview.

Saving builds a retrieval index; it does not retrain the language model. Treat document claims and any embedded instructions as evidence to review, not operating instructions for the assistant.

## Ask questions

Type a question in User Mode. Matching saved records are returned with filename/chunk sources. If a compatible local GGUF is selected, the app supplies retrieved evidence for a grounded answer and labels the response engine. If no supporting source is retrieved, it abstains even when a language model is selected; importing a model does not enable unsupported document claims.

English, Urdu and Punjabi answer choices remain visible even if another language, such as Hindi, was configured earlier. Without a language model, saved facts are returned in their original language with a clear translation-unavailable status when necessary. Selecting Urdu does not turn English source text into an Urdu answer by itself and never overwrites the source. Cross-language question rewriting and translated synthesis require a capable multilingual model; the local database index uses normalized lexical terms rather than embeddings.

In Resources → Local models, the app provides an external-browser link for a pinned Qwen3 0.6B Q4_K_M English demo. It failed our Urdu/Punjabi answer and translation checks, so it is not recommended for those translations. Download it in the browser, return to Pocket AI, unlock administration and import/select the GGUF. It is about 379 MiB and may still fail the memory guard on a 2 GB phone. Review [MODELS.md](MODELS.md) for the exact file/checksum and quality limits.

## Medicine references and symptom questions

You may import a trusted leaflet or other reviewed medicine reference and ask what that source says. Answers retain document citations, and uncertain or missing source information should remain explicit. The app does not certify that an imported medical document is correct, current or appropriate for a particular person.

Questions about a person's symptoms lead to clarification and appropriate clinical guidance, rather than an automatic diagnosis or prescription. Information such as age, symptom duration, allergies and existing medicines can matter; a general language model cannot establish a safe personalized treatment from a short message. The app does not supply automatic prescriptions or prove a medicine is suitable. It is a reference and conversation tool, not a medically validated decision system.

Repeat, slower speech and configured language-switch commands are available through supported command phrases. The repeat control also works directly from the interface. Stop speech with the stop-speaking control. The UI language is primarily English; saved content supports Unicode and RTL rendering.

## Use voice

1. On a compatible device, allow the included multilingual Whisper tiny q5_1 model to finish preparing. Its state appears in Resources → Local models. If you removed its private copy, choose Restore included speech model. A previously selected valid model remains selected.
2. Tap the microphone. The app checks native compatibility, selected file and multilingual model support **before** requesting permission or recording. If setup is missing, it opens an actionable setup dialog and records no audio. Grant microphone permission when Android asks after a successful check.
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
| No voice recognition | Check CPU/runtime compatibility (ARMv7 needs NEON); open Local models and wait for included speech setup or restore it. Select a multilingual Whisper model and then grant microphone permission. |
| English document remains English after selecting Urdu/Punjabi | Import/select a capable multilingual GGUF; chips select the requested language but do not install translation. |
| DOC/PDF import fails | Convert binary DOC to DOCX; use a text-based, non-password-protected PDF, or export readable UTF-8 TXT. Scans need external OCR. |
| No spoken output | Install an offline voice for the selected language using Android TTS settings, test it and check media volume/output routing. |
| Administrator locked | Use the PIN or private recovery code. Failed attempts trigger increasing delays. |
| Missing USB file/device | Confirm USB host support, power, Android permissions and a mounted provider visible in the system file picker. |

See [Backup and recovery](BACKUP-RECOVERY.md) before resetting, uninstalling or moving devices.
