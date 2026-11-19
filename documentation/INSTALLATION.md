# Install and use Pocket AI 1.3 Voice Preview

Pocket AI's baseline document workflow runs on the phone without a cloud service, account, browser download or manual model import. The single APK includes search, translation, optional English explanation and speech-recognition resources. It requests no Internet permission.

Use the APK and checksum file from the same release. The repository [README](../README.md) identifies current downloads. Version 1.3 adds included Urdu/Punjabi recognizers, a local speech-presence check before Whisper and editable Urdu/Punjabi transcript review. It retains database schema 2. Earlier build and acceptance reports describe their own releases.

**Keep your existing Pocket AI installation.** The original private signing key became unavailable after a build workspace reset. This preview installs alongside it as **Pocket AI Voice Preview**, package `com.pocketai.voicepreview`, with its own private signing identity and separate data. It cannot update the original app. Export knowledge from the original app, create a new PIN and recovery code in the preview, then import that backup there. The original [1.2.1 APK](https://media.githubusercontent.com/media/Arhamkhan1564/Pocket-ai/main/downloads/offline-v1.2.1/PocketAI.apk) remains available. No uninstall is required.

## Phone and storage requirements

Android 6.0/API 23 or later is required. Packaged native targets are ARM64, ARMv7 with NEON, and x86_64. CPU features, available RAM, storage and Android's process limits determine which tools can run. This does not establish support or usable speed on every Android phone. The itel A25 Pro/2 GB target phone was not attached for acceptance testing.

The APK contains **17 model/tokenizer assets in seven packs, totaling 899,719,726 bytes, about 858.0 MiB**. Local preparation creates private model copies in addition to the installed APK. Budget space for the APK, those copies, Android's installation overhead, the downloaded installer if retained, and your documents. Keep several gigabytes free rather than relying only on the model-file total. Storage checks can pause setup if space is insufficient.

A 2 GB total-RAM specification is not a guarantee of enough free memory. Check Resources → Device & storage and close other apps before heavy work. A memory check can refuse a request while keeping saved originals available.

## Install and prepare once

1. Transfer `PocketAI-Voice-Preview.apk` to the phone and open it with Android's file manager. Allow that file manager to install unknown apps if Android asks.
2. Complete the welcome screens. English, Urdu and Punjabi remain available. Select Punjabi Shahmukhi or Gurmukhi and any additional configured text language.
3. Create an administrator PIN containing 6–12 digits and save the displayed recovery code privately outside the phone.
4. In **Prepare this phone**, allow the included tools to copy and verify local files. This reads the installed APK; it does not contact a server. Search, Urdu/Punjabi reference translation and English explanation readiness are reported separately.
5. Optional setup checks show device information, record two seconds of microphone signal levels and play a short tone. Confirm the tone only if you hear it. These checks do not establish recognition accuracy or pronunciation.
6. Finish setup. **Continue with originals** is available if heavy tools are not ready; you can still read references, teach text and use available keyword search.

Resources → **Offline tools** provides **Prepare offline tools**, **Retry local setup**, **Pause local setup** and **Check included resources** as appropriate. An interrupted copy can resume from included files without downloading anything. File readiness confirms installation integrity, not translation quality or device performance.

The preview certificate SHA-256 is `ab1c8fc2c6dc7f58517337050ec35dc4e236312f4547504e1e999c05ad6f8a3e`. Future preview updates need that same preview signing identity. Standard `com.pocketai` updates still require the unavailable original key; a new key cannot replace it. The debug APK uses another application ID and data store. Private keys and signing-backup archives are excluded from public downloads. See [preview build identity](VOICE-PREVIEW-BUILD.md).

Knowledge transfer uses an exported backup; the apps do not share their private storage. Keep backups privately because they are plaintext. Imported knowledge does not transfer the old administrator PIN or models: set up the preview's PIN, prepare its included tools and allow its derived reference index to rebuild. See [backup and recovery](BACKUP-RECOVERY.md).

## Teach facts and answers

1. Enter **Training Mode** and unlock with the administrator PIN.
2. Select the knowledge language and category. Enter a fact, note, preference, or optional question and its answer.
3. Review the confirmation and save. Duplicate content and possible conflicting question/answer records are checked.
4. Return to **User Mode** and ask about the saved information.

Saving creates persistent SQLite knowledge and a retrieval index; it does not train or change model weights. Ordinary conversation never becomes approved permanent knowledge automatically. Administrator approval also does not certify a document's clinical accuracy.

Editing, correction, deletion, categories and training history are available through the library and Training Mode. Leaving the foreground locks administration; Android's document-picker flow can preserve the current short session. Sessions also expire after inactivity. Reading references and source citations remains available while editing is locked.

## Import an English reference

1. In Training Mode, choose **Import document**, then select UTF-8 TXT, Word `.docx` or a text-based PDF.
2. Review the filename, extracted text, format, warnings, passage count and PDF page count when available. Keep source language as English for an English reference; it is independent of answer language.
3. Choose **Save & index document**. The app stores sentence-aware, overlapping passages with filename/chunk references. Reimporting identical content from the same filename skips duplicates.
4. In Offline tools, check **Reference search index** progress. Pause, resume or retry reference indexing as appropriate. Original text stays readable while indexing is paused or incomplete.
5. Return to User Mode, choose English, Urdu or Punjabi and ask about the reference.

Imports are bounded to **8 MiB per input file, one million extracted characters, 1,000 saved passages and 100 PDF pages**. Convert legacy binary `.doc` to `.docx` or UTF-8 TXT first. Scanned/image PDFs need OCR outside this app; there is no OCR engine. Password-protected PDFs are rejected. DOCX extraction reads document text and footnotes, not images, embedded files or executable macros. Review tables and complex layouts because extraction can change reading order.

PDF page counts are file summaries. Answer citations identify filenames/chunks, not exact PDF pages. Embedded document instructions are source material, not instructions for the assistant to execute.

A saved note longer than 20,000 characters cannot use the semantic encoder in one record. It remains intact and available to original-text reading and keyword search. Indexing now skips unsupported notes for that pass, reports the reason and continues with later records; skipped notes remain pending rather than being marked indexed. Split a long note into shorter records or import its original text as TXT to obtain bounded document chunks. After a supported edit, refresh/resume indexing to retry it. The one-million-character document-import limit is separate from this per-record encoder limit.

## Ask and check the answer

The default style is **Fast cited passages · recommended**. It retrieves supporting saved excerpts rather than asking a small model to invent an answer. Multilingual semantic search matches English, Urdu and Punjabi questions against indexed saved passages; keyword matching remains a fallback. Retrieval can miss or misrank information. If support is missing, the assistant abstains rather than treating a language model as the document source.

For English evidence, the included dedicated translator can produce Urdu or Gurmukhi Punjabi. Shahmukhi uses that Punjabi translation followed by explicitly labeled script conversion. This is not Urdu relabeled as Punjabi. Translation checks protect selected quantities, identifiers, warnings and script, but cannot prove full semantic or clinical accuracy. If translation or a check fails, the app retains the intact original and reports the limitation.

In Offline tools, **English explanation · slower** optionally uses the included English-focused model for a source-based explanation. It uses more memory and can be less dependable than excerpts; unavailable or rejected generation falls back to passages. Medical-reference answers keep the excerpt workflow. Urdu/Punjabi display uses the dedicated translation path, not a claim that the English generator is multilingual.

Tap a numbered source chip to open its full **current saved original-language passage**, filename/chunk reference and modification time. Choose **Read all saved passages from this reference** to inspect surrounding passages. English source viewers offer **Translate this passage**, with the original kept visible above the result. Source links use stored record IDs, not text guessed from a model answer. Older conversations may have display-only labels; ask again or locate the reference in Library.

Go to **Library → Documents** to search reference names, read saved passages or import another reference. Deletion requires administrator authorization and confirmation. It removes that reference's passages, not the external file originally selected or unrelated manually taught facts. Existing conversation text remains; removed-source links report that the source was deleted.

## Languages and medicine references

The included translation direction is **English → Urdu or Punjabi**. It does not provide arbitrary translation between every configured language. Punjabi Shahmukhi spelling can be approximate outside the converter's limited glossary; uncommon signs can cause conversion to fail rather than silently disappear. Selecting an extra text language does not add a translation model or voice.

Use reviewed, current medicine references and ask what they say. Imported claims are not clinically verified. Compare medicine names, numbers, units, negation and context with the English original. Synthetic or host tests do not medically validate a translation, recommendation or model.

Personal symptom/treatment questions request context and point to qualified clinical help; the app does not automatically diagnose, prescribe or prove a medicine suitable. Its emergency checks are limited, not a universal triage system. Severe symptoms require urgent professional care without waiting for the app.

## Voice input and spoken output

1. Let **Offline tools** prepare the included speech resources. With the included automatic selection, English uses multilingual Whisper tiny, Urdu uses the Urdu-tuned Whisper base, and Punjabi uses the Gurmukhi CTC recognizer. No browser download or manual model import is needed.
2. Select the language and Punjabi display script, then tap the microphone. Compatibility, memory and resource checks run before recording; grant microphone permission when Android asks after a successful check. This capture keeps its chosen recognizer, language, script and question/training destination even if settings change later.
3. Speak and tap again to stop and transcribe. Tap-to-talk recording is bounded to 60 seconds. A local speech-presence detector runs before Whisper and closes before recognition starts; rejected audio does not become a transcript.
4. Every manual Urdu/Punjabi result opens an editable review. Check and correct it, then choose **Use text** to fill the captured question or training draft. Sending the question or confirming saved training content remains a separate action. Shahmukhi display conversion is labeled, and the original recognition remains available for comparison.

An explicit custom Whisper selection is preserved. Disabling recognition stays disabled after reopening; preparation does not silently enable it. Select the included automatic entry when you want the bundled language-specific recognizers.

Text submission, including the keyboard's Send action, waits while recording is active or recorded audio still awaits transcription. Finish or cancel that capture, then review the text before sending.

**Urdu and Punjabi recognition is experimental.** The Urdu-tuned model reduced word errors on four host clips but still omitted or changed spoken numbers. Punjabi CTC returned Gurmukhi on four clips but made substantial word and name errors, including medical terms. The reference speech detector accepted six speech clips and rejected three non-speech controls using fixed settings; that does not prove general detector or recognition accuracy. The canonical Kotlin detector matched all nine decisions and every frame probability exactly against that reference. Android microphone/device acceptance remains pending; see [1.3 voice validation](VOICE-VALIDATION-1.3.md). The [older tiny-model observations](OFFLINE-VOICE-VALIDATION.md) remain available as historical evidence.

Review recognition in every language, especially medicine names, numbers and identifiers. Use typing when exact wording matters. Audio is 16 kHz mono PCM, temporarily buffered and cleared after transcription or cancellation. Silent or very short recordings are rejected. A custom recognizer may return a different script; choosing a display script does not correct recognition errors.

Recognition models do not supply spoken answers. Resources → **Voice & audio** checks installed Android offline TTS voices separately. Test speaker, speed, volume and microphone route. Android controls the media output route. If a compatible voice is absent, text remains available. The APK does not include TTS voice packs; configuring one through Android is optional system setup.

An optional appliance session needs a visible user start and microphone permission. Its foreground notification provides a stop control. It uses **unreviewed transcripts**, so use reviewed tap-to-talk or typing when exact words or numbers matter. Sessions use about six-second recording turns and a 30-minute limit; Android can still stop them or restrict screen-off access. Wake words, automatic boot startup and indefinite unattended listening are not included. The preview has not been accepted on the itel A25 Pro/2 GB phone or every Android device.

## Troubleshooting

| Symptom | Action |
| --- | --- |
| Installation or update fails | Use the separately named voice preview, check API 23+, supported ABI/CPU and free storage. It does not replace the original app; keep the original and transfer knowledge through a backup. |
| Offline setup is incomplete | Open Offline tools, free storage if requested, and retry local setup. No browser/model download is required for the baseline. |
| Semantic matching is incomplete | Check index progress, resume or retry indexing, and try a word from the original. Read references directly while indexing is unavailable. |
| Some notes remain keyword-only | Read the skipped-index reason. Shorten/split an unsupported note or import it as a chunked document, then refresh indexing. Its original is retained and later supported passages can still finish. |
| Translation is unavailable | Check translation readiness, source language and free RAM. The included direction is English to Urdu/Punjabi; rejected output does not replace the original. |
| A source will not open | It may be deleted or come from old display-only history. Check Library or ask again using current references. |
| DOC/PDF import fails | Convert binary DOC, remove PDF password protection, or provide selectable text. Scans need external OCR. Check import limits. |
| Voice input is unavailable | Check whether recognition is disabled, the selected resource, CPU/runtime compatibility, speech readiness, free RAM and microphone permission. ARMv7 needs NEON. |
| Recognized words, numbers or script are wrong | Edit the review before using the text, or type the question. Check whether a custom recognizer is selected. Shahmukhi display conversion cannot repair wrong words. |
| No clear speech is detected | Try a short, clear recording in a quieter setting, or type. The local detector can reject difficult speech and does not validate accepted words. |
| Keyboard Send does nothing during capture | Finish or cancel recording/pending transcription first, then review and send. |
| No spoken answer | Check an installed offline Android voice, media volume and output route. Text works without a TTS voice. |
| A tool reports insufficient RAM | Close other apps and keep excerpt mode. Storage alone does not fix a RAM or 32-bit address-space limit. |
| Administration is locked | Use the PIN or recovery code. Failed attempts impose increasing delays. |

See [Backup and recovery](BACKUP-RECOVERY.md) before resetting, uninstalling or moving devices, and [Models and compatibility](MODELS.md) for included resources and limits.
