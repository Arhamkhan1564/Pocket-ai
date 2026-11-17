# Verification · 1.2.1 offline RAG

## Patch evidence and scope

Use the patch-specific records for completed 1.2.1 results:

- [OFFLINE-1.2.1-BUILD-REPORT.json](OFFLINE-1.2.1-BUILD-REPORT.json): actual final build/check commands, test totals, skips, artifact identities and lint results.
- [OFFLINE-ACCEPTANCE-1.2.1.md](OFFLINE-ACCEPTANCE-1.2.1.md): focused component acceptance and the canonical model-backed service harness, with explicit execution status and limits.
- [Small acceptance records](offline-acceptance-1.2.1/) and [actual voice observations](OFFLINE-VOICE-VALIDATION.md).

A focused component acceptance run has passed the real document-parser → SQLite/catalog → typed-citation → reopen → clean-restore path. This exercises actual extraction and persistent source relationships; it is not a completed Compose UI or physical-phone test.

This patch fixes two concrete source behaviors: keyboard/IME submission cannot proceed during microphone capture or pending recorded audio, and an oversized unsupported saved note no longer aborts indexing of later records. Such a note is retained, stays pending/keyword-only and has an explicit skip reason. A supported edit permits retry. The schema remains 2 and the 13-file resource bundle is unchanged.

The patch includes source-translation stale/edit, deleted-source and same-language passthrough checks plus the skipped-record database case. Test source presence is not a passed suite. The final test count and the full canonical real-model service harness are established only by explicit completed results in the patch reports. A started or pending harness must not be counted as successful integration.

There is still no completed physical itel, Android microphone/TTS, or full current UI acceptance claim. The available development environment has no KVM/attached phone runtime for those checks. Host/Robolectric execution, including a harness using real model files, remains distinct from executing the APK on the target phone.

## Baseline 1.2 architecture and model evidence

These records describe the 1.2.0 baseline retained by this patch:

- [OFFLINE-BUILD-REPORT.json](OFFLINE-BUILD-REPORT.json): historical 1.2.0 build/check commands, counts and artifact verification.
- [OFFLINE-MODEL-VALIDATION.md](OFFLINE-MODEL-VALIDATION.md): actual model/processor outcomes, including unsuccessful cases and their limits.
- [Detailed model reports](offline-v1.2-model-results/) and [test-result XML](offline-v1.2-test-results/).

A model file being present, a successful build, a mocked unit test and a real native inference are different evidence. A compiled or skipped instrumentation test is not a successful Android UI run. The 1.2.0 baseline recorded 148 discovered tests, 147 passed and one skipped; these and the older 45/44/1 and 95/94/1 totals must not be reused as fresh 1.2.1 results.

The intended distribution is one APK containing the **13 pinned baseline resource files totaling 715,732,683 bytes (682.6 MiB)**. This is the resource payload, not an APK-size or RAM measurement. The manifest fixes each file's identity and license. Gradle rejects a missing, changed or extra external asset. APK verification must establish that the packaged files match those identities; source declarations alone cannot establish that.

## Baseline behavior retained in 1.2.1

| Area | Implemented behavior | Evidence still required on the target phone |
| --- | --- | --- |
| Included resources | Local copy/check of E5, IndicTrans2, optional English GGUF and Whisper from the APK; no app network path | Installation/preparation storage, interruption recovery and adequate free RAM |
| Retrieval | English, Urdu and both Punjabi scripts can query multilingual E5; lexical fallback, coverage and abstention guards remain | Representative corpus relevance, unsupported questions and latency |
| Translation | Dedicated English-to-Urdu/Gurmukhi NMT; Shahmukhi is separately labelled Punjabi script conversion | Fluent-speaker review of meaning, dialect, names, quantities and warnings |
| Answer behavior | Default cited excerpts; optional English synthesis with source checks and fallback | End-to-end interactions, useful abstention and misleading-source cases |
| Sources | Typed citation IDs open saved passages and linked document passages; originals remain visible | UI behavior, long text/RTL and changed/deleted sources |
| Documents | Bounded TXT/DOCX/selectable PDF preview, indexing, catalog and administrator deletion | Real provider/files, extraction order and small-screen operation |
| Persistence | Schema 1→2 migration; format 2 backup with catalog/mappings/typed IDs, accepting format 1 | Update/restart and restore on a second installation |
| Derived state | Bounded 384-dimensional vectors; stale-write protection and invalidation; unsupported notes remain pending/keyword-only while later records continue; revision-bound translation cache | Larger corpus growth, cancellation and low-storage conditions |
| Runtime | CPU libraries for ARMv7 NEON, ARM64 and x86_64; prepared API-23/16-KiB ONNX JNI wrapper | Actual API/CPU/font compatibility and sustained memory/thermal behavior |
| Voice | Included multilingual Whisper tiny Q5_1 and installed offline Android TTS selection | Actual microphone, each requested language and speaker/media route |

## Model evidence and interpretation

Development-host E5 probes retrieved the intended English passage for a small synthetic set of English, Urdu, Gurmukhi and Shahmukhi questions. This is a limited relevance demonstration. Similarities and margins from that set do not certify broad retrieval accuracy or medical suitability. Missing requested fields or unsupported questions must still lead to abstention rather than a plausible unrelated answer.

The dedicated translator is **IndicTrans2 distilled 200M int8**, not a relabelled Urdu output or a claim that the small English GGUF learned Urdu/Punjabi. Native SentencePiece, the pinned dictionary mapping, Indic text processing and autoregressive tensor/cache behavior need parity checks as well as actual translation probes. Quantities and literal identifiers use strict restoration/preservation checks. A passing synthetic sentence does not establish reliable translation of every medicine leaflet.

`PunjabiShahmukhi` is an original Unicode/alphabet implementation with a small whole-word spelling glossary, applied after actual Punjabi Gurmukhi translation. Component checks cover the accepted fixture, Latin/code/digit/punctuation preservation, common nasal/gemination/conjunct behavior and explicit unsupported-sign errors. It does not resolve every loanword spelling, tone or dialect. It has not received fluent-speaker clinical review.

SmolLM2 360M is an **optional English-focused synthesizer**. Earlier host examples copied a literal and summarized two facts, but a missing-fact test did not abstain correctly. That failure remains a reason for default excerpts and source/coverage checks; it must not be counted as a successful no-answer test. A generated explanation is not proof that the cited source entails every sentence.

The current production Whisper JNI path was exercised on **six FLEURS read-speech clips: two English, two Urdu and two Punjabi**. The [voice report](OFFLINE-VOICE-VALIDATION.md) retains references, actual outputs, hashes, scoring caveats and host timings. Urdu outputs had substantial substitutions/omissions, including an omitted spoken number word; both Punjabi outputs were Latin romanization/mixed English rather than the reference Gurmukhi script. English outputs also contained word errors. These checks do not establish acceptable Urdu/Punjabi recognition.

The fixtures did not contain decimal-digit strings, so digit-quantity preservation was not tested. Punjabi edit rates across different scripts are not a fluency measure, and the small sample is not a representative language benchmark. The host checks bypassed Android AudioRecord/permissions, phone noise, TTS and speakers. Recognition, reference translation and spoken output remain separate capabilities. UI guidance now marks Urdu/Punjabi recognition experimental and requires transcript review; hands-free turns use unreviewed text and are unsuitable when exact wording is essential.

## Target-device status

The physical itel A25 Pro/2 GB target phone was **not attached**. There is no completed acceptance claim for its Android version, ABI, available RAM, microphone/speaker, USB hardware, installed voices, speed, thermals or screen-off operation. Android 6/API 23 and three packaged ABIs do not make every model usable on every phone. Runtime guards are estimates and cannot guarantee that Android will never terminate an app under memory pressure.

Earlier software-emulator attempts rendered candidate UI but encountered framework/SurfaceFlinger/ADB failures and did not finish the full teaching/retrieval/restart instrumentation flow. Those records remain historical below. They do not prove either a passing current UI flow or a current application defect. A stable physical/accelerated Android runtime is still needed for complete UI/native acceptance.

No source or generated medicine response has been clinically validated. Imported references are owner-approved data, not a curated prescribing database. Numeric, negation, literal and citation checks are incomplete safeguards; passing them cannot prove semantic equivalence or safe individual treatment.

## Reproduce and record checks

Prepare pinned model resources and the ONNX wrapper as described in [DEVELOPMENT.md](DEVELOPMENT.md), then run:

```sh
./gradlew testDebugUnitTest lintDebug lintVitalRelease assembleDebug assembleRelease assembleDebugAndroidTest
./gradlew connectedDebugAndroidTest
```

Record the actual exit/results, not merely the command. Verify release identity, all required ABI libraries, ELF/ZIP alignment, model-resource checksums, licenses and the merged manifest's lack of Internet permission. Keep skipped tests explicit. Model fixtures must execute real runtimes to establish inference; unit tests that substitute a backend establish orchestration only.

For a target phone, complete this acceptance sequence with networking disabled:

1. Inspect Android/API/ABI/CPU, free RAM/storage and battery/thermal status. Install and prepare included resources locally; pause/retry setup and confirm that originals remain readable if a component cannot load.
2. Create/recover administrator access. Import a short English TXT, DOCX and selectable PDF; check preview, source language, ordering and confirmation. Reject malformed, encrypted, legacy DOC and image-only inputs with clear messages.
3. Let the index finish. Ask known and missing-fact questions in English, Urdu, Gurmukhi and Shahmukhi; compare sources and abstentions. Open each source chip and full saved document. Edit/delete a source and ensure stale vectors/translations cannot be served as current evidence.
4. Compare translated passages with the English original, including names, identifiers, quantities, units and prohibitions. Ask for clarification when needed; do not interpret a general source as personalized prescribing.
5. Restart the app/phone. Export/restore format 2, try an old format 1 backup and reject invalid mappings. Check catalog, original content and trusted citation IDs; observe local reindexing after restore.
6. Test microphone/recognition and installed offline TTS separately for every intended language/script. Check permission denial and text fallback, cancellation and actual media routing. Try keyboard/IME Send while capturing and after automatic stop leaves pending audio; finish/cancel and review before submitting.
7. Compare default excerpts with optional synthesis. Measure first/repeated latency, peak process/native memory, storage growth and thermal behavior. Include an oversized legacy note: verify that its original survives, the skip reason and pending count remain visible, later supported records index, and a supported edit can be retried. Test low-memory/low-storage recovery.
8. Start/stop the optional foreground appliance session visibly; check notification stop, interruption, duration limits and resource release. Test external USB equipment only when attached.

## Historical releases

The 1.2.0 [build report](OFFLINE-BUILD-REPORT.json), [model validation](OFFLINE-MODEL-VALIDATION.md) and [test XML](offline-v1.2-test-results/) remain historical baseline evidence. They do not establish that the two patch fixes, new source-translation checks or patch service harness passed. Refer to the patch reports above for those outcomes.

The [1.1 RAG build report](RAG-BUILD-REPORT.json), [1.1 XML](rag-test-results/) and [Qwen model findings](MODEL-VALIDATION.txt) describe the previous release. Its 95 discovered/94 passed/one skipped test count is historical. Its small Qwen candidate failed Urdu/Punjabi generation/translation checks; that is not a result for the new dedicated IndicTrans2 pipeline. Version 1.1 bundled recognition but required a separate language model and had no E5 index.

The [initial build report](BUILD-REPORT.json), [initial XML](test-results/), [Android runtime attempt](android-final-status.txt) and [Compose test status](compose-ui-test-status.txt) describe 1.0-era work. Its 45 discovered/44 passed/one skipped count, missing bundled weights, 32-bit inference stub and TXT-only limits have been superseded. Native host results in `tests/native/RESULTS.txt` likewise identify their original fixture/runtime context; they are not a phone benchmark for every current component.
