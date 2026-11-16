# Verification

## Current document RAG update · 1.1.0

The [current build report](RAG-BUILD-REPORT.json) records **95 discovered tests: 94 passed, one explicitly skipped, zero failures/errors**. The six Gradle build/check tasks succeeded. Android lint reported zero errors and 18 warnings. Both APK signatures, the original release signing identity, 16 KiB alignment, the included speech asset checksum, PDF classes and license notices were verified. Full ARMv7 NEON, ARM64 and x86_64 runtimes are packaged.

New component coverage includes TXT/DOCX/PDF extraction and malformed-document rejection, source-linked chunking and retrieval, installation integrity/concurrency, source-only answers, treatment clarification, urgent-message rules and translation/rewrite guards. The existing Compose dialog-idling test is still skipped; it is not counted as passed. [Per-suite XML](rag-test-results/) records the actual run with host identifiers removed.

**Language-model acceptance failed for Urdu/Punjabi.** The tested small Qwen3 candidate passed a synthetic English RAG fixture, but corrupted an identifier in Urdu, gave incomplete Punjabi answers, failed all three translation cases and dropped a named drug during query rewriting. See [actual model results](MODEL-VALIDATION.txt). It is labeled an English experiment, not a validated multilingual/clinical translator. The app preserves original evidence when translation checks fail; passing those checks cannot prove medical accuracy.

The bundled Whisper tiny q5_1 passed real host-native English transcription/recovery checks. These results do not validate Urdu/Punjabi ASR, Android microphone/TTS routes or translation quality. The itel A25 Pro was not attached; physical-device execution, available RAM, latency, thermals and complete UI acceptance remain unverified. A single APK cannot establish usable AI performance on every phone.

## Historical initial release · 1.0.0

The records below describe the initial release only. Its old limits concerning bundled speech, document formats and ARMv7 inference have been superseded by the 1.1 implementation above. The original [BUILD-REPORT.json](BUILD-REPORT.json) and [test-results](test-results/) remain historical evidence.

This document distinguishes implemented behavior from completed acceptance testing. The actual target Android phone was not attached. A build, unit test, emulator test or English model test does not establish 2 GB target-device acceptance or Urdu/Punjabi voice quality.

## Build and test record

The build command `./gradlew --no-daemon assembleDebug testDebugUnitTest assembleRelease assembleDebugAndroidTest lintDebug` completed successfully. The JVM/Robolectric suite discovered 45 tests: 44 passed, one Compose dialog-idling test was explicitly skipped, and there were zero failures or errors. The skipped test is documented below and is not counted as passed. These cover persistent knowledge, authorization/recovery/expiry, transactional backup validation, multilingual retrieval and scripts, conversation isolation, model imports/storage safety and voice signal handling.

Debug and release APK signing was verified (v1/v2); ZIP/native alignment checks passed for 16 KiB pages. The release contains arm64-v8a, armeabi-v7a and x86_64 native libraries and MIT runtime notices. Its manifest has no Internet permission. Android lint has zero errors; remaining warnings concern pinned dependency upgrades, synchronous security-preference commits, a legacy launcher attribute and a redundant version check. A final explicit Android 12+ data-extraction exclusion was added to block cloud/device transfers as well as legacy automatic backups.

The exact native JNI adapter passed real GGUF generation, Whisper tiny transcription, missing-model/invalid-audio recovery, and both ASR-first and generation-first sequential loading on a Linux x86_64 host. An official Qwen2.5-0.5B-Instruct Q4_K_M test also returned the supplied saved access phrase correctly using its actual ChatML template. See `tests/native/RESULTS.txt` for outputs, checksums and limits. These were host tests, not phone benchmarks.

Android instrumentation test APK compilation succeeded. Emulator UI/JNI execution has not yet been verified: the available emulator has no hardware acceleration; its first API 30 boot encountered repeated system-server readiness problems. A lighter API 28 fallback installed both APKs and rendered the actual app interface. It exposed small-screen layout/status-bar issues and a PIN-verification monitor that could block UI session checks; these were fixed. Two concurrent regression tests verify immediate session reads/locking and revocation of pending PIN/recovery operations. The initial UI flow hit a 30-second timeout during PIN verification before that fix; a warm retry stalled in emulator transport. System UI ANRs, Binder stalls and SurfaceFlinger throttling prevented a completed instrumentation pass. A preceding initial-build candidate with the same native core was also installed on a 10 Hz API 28 trial. The final delivered build adds training-voice progress feedback and busy-state guards; complete Android UI execution of that final build remains unverified. That guest stayed healthy while idle, then SurfaceFlinger throttling and zygote termination returned during the UI flow; its ADB transport timed out without a final instrumentation result. No emulator test is counted as passed without its execution result. No physical target phone was connected.

## Initial-build additions

First-time setup now displays actual device compatibility readings and offers a bounded two-second microphone signal check, a speaker tone requiring human confirmation, and separate offline-voice checks. Training voice capture uses its selected language; saved Punjabi records use their own script for speech. Plain-text document imports now show a preview and require explicit confirmation before any knowledge is saved.

The compiled model header validator also passed the official 491,400,032-byte Qwen Q4_K_M GGUF and 77,691,713-byte multilingual Whisper tiny files. These external test weights are not bundled in the APK or source archive.

The additional Robolectric UI test was tried on SDK 28/34, including accessibility SetText, and could not get idle after the administrator dialog opened. It is explicitly annotated with `@Ignore`; the on-device `PocketFlowTest` remains enabled. This records a test-runtime limitation, not a passing UI result.

Useful commands:

```sh
./gradlew testDebugUnitTest
./gradlew lintDebug assembleDebug assembleRelease
./gradlew connectedDebugAndroidTest
```

`PocketFlowTest` exercises protected teaching, retrieval and restart through the actual Compose interface on a disposable debug installation. `NativeRuntimeTest` requires separately injected model/audio fixtures and skips when those fixtures are absent. See [DEVELOPMENT.md](DEVELOPMENT.md).

## Implemented in source

| Specification area | Implemented behavior | Remaining acceptance work |
| --- | --- | --- |
| Android foundation | Native Kotlin/Compose project, Material 3 dark theme, setup, navigation, API 23 minimum, ABI checks | Install/start on intended old phone; accessibility and small-screen review |
| Two primary modes | Default User Mode; PIN-protected Training Mode; ordinary chat does not save verified knowledge | Verify lifecycle/expiry on actual Android build |
| Persistent knowledge | SQLite CRUD, source metadata, categories, correction/training history, paginated browsing, indexed lexical search | Reboot persistence; large collection/resource measurements |
| Teaching | Fact and Q/A entry, review/confirmation, duplicates/conflicts, correction/deletion, source-linked plain TXT chunks | Representative user teaching and import data |
| Authorization | Salted PBKDF2 PIN/recovery, backoff, expiring session, guarded permanent mutators | Device behavior, recovery usability, session lifecycle |
| User conversation | Typed questions, recent context, source labels, saved-original fallback, repeat/slower/language commands | Real user language phrases and multilingual UX |
| Multilingual text | Urdu, Punjabi scripts, selectable third language, original preservation, normalization, RTL-aware content | Fonts/layout on old phone; spelling/script search corpus |
| Local generated answers | Pinned llama.cpp JNI, imported GGUF, retrieved context, bounded prompts, memory guard, cleanup/cancellation | Meaningful target model; Urdu/Punjabi quality; device latency/memory |
| Offline ASR | Pinned Whisper JNI, bounded AudioRecord, silence handling, explicit language, temporary PCM | Built-in mic, noise, Urdu/Punjabi/third-language accuracy and latency |
| Offline TTS | Installed voices filtered for offline availability, per-language checks, speed/volume/repeat/stop | Actual installed voices, pronunciation, media route and speaker |
| Model management | SAF/USB-provider import, storage bounds, header checks, metadata/checksum, progress, selection/removal | Full tensor validation using chosen real files; target compatibility |
| Audio devices | Live enumeration, default built-in recording preference, dynamic microphone selection | Actual USB/external microphone and speaker routing |
| Diagnostics | API/device/ABI/CPU/RAM/storage/battery/thermal/audio/USB reports | Verify readings and capabilities on target phone |
| Portable backup | Admin-gated bounded JSON export, validated transactional replacement restore | SAF behavior on target providers, restore to second phone |
| Appliance session | Explicitly started microphone foreground session, notification stop, bounded windows/wake lock/duration | Screen-off behavior, interruption recovery, 30-minute reliability |
| Privacy | No Internet permission, no accounts/cloud inference, private local data, automatic Android backup disabled | Device-level privacy review; external provider/service behavior |
| Resource limits | Sequential/lazy inference, bounded recordings/contexts, pagination, low-memory checks, native cleanup | Peak RSS/heap, thermal throttling, sustained low-memory conditions |

## Deferred or deliberately limited

- No bundled model weights or guaranteed Urdu/Punjabi speech voice.
- No full inference on 32-bit ARM in this build; saved-knowledge text/TTS fallback remains available.
- No automatic model download, dedicated translation/transliteration engine or semantic embedding index.
- No universal automatic language identification; Arabic-script Urdu/Punjabi remains manually selectable.
- No fine-tuning/model-parameter retraining; learning means persistent indexed knowledge.
- No wake word, automatic boot startup, permanent unattended listening, LED hardware integration or remote/local web administration dashboard.
- No PDF/DOCX/OCR ingestion; supported document import is bounded plain UTF-8 text.
- No encrypted SQLite database or encrypted portable/signing backup.
- No unlimited/streaming full-database portable backup; audit history/settings/models/credentials are excluded from JSON backup.
- No prior-release schema migration acceptance result; this is the first schema version.
- No app-store publication or production device certification.

## Target-phone acceptance procedure

1. Record phone model, Android/API/ABI, available RAM/storage and battery/thermal readings before loading models.
2. With internet disabled, set up the app, create/recover credentials, save a fact/Q&A, inspect/edit/correct it, search, delete a separate test record and check audit events.
3. Restart the app and phone. Confirm default User Mode, retained knowledge and protected mutations.
4. Import a UTF-8 Urdu document and Punjabi content in each needed script. Check original text, RTL, pagination and a known lexical question.
5. Export a backup, test malformed-file rejection, then restore a known valid backup after confirming replacement. Check record counts and questions.
6. Import the chosen GGUF/Whisper files, record checksums, run generated answers and compare saved sources. Try missing/corrupt/oversized files and an insufficient-memory case.
7. Test the built-in microphone/speaker in each language separately. Record ASR errors and latency; listen to TTS pronunciation and verify text-only fallback when a voice is unavailable.
8. Repeat interactions while measuring app/native peak memory, response latency, storage growth and thermal behavior. A successful isolated request is not a sustained-use result.
9. Start and stop an appliance session visibly. Check the notification, screen-off behavior, interruptions, duration limit and resource release. Test USB audio/storage only if actual hardware is attached.
10. Document failures and the smallest usable profile before relying on the motherboard without a display.
