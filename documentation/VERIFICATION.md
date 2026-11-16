# Verification · 1.2 offline RAG

## Evidence for this version

Use these version-specific records together:

- [OFFLINE-BUILD-REPORT.json](OFFLINE-BUILD-REPORT.json): actual completed build/check commands, test counts, skipped cases, APK details and package verification.
- [OFFLINE-MODEL-VALIDATION.md](OFFLINE-MODEL-VALIDATION.md): actual model/processor outcomes, including unsuccessful cases and their limits.
- [Detailed model reports](offline-v1.2-model-results/) and [test-result XML](offline-v1.2-test-results/).

A model file being present, a successful build, a mocked unit test and a real native inference are different evidence. A compiled or skipped instrumentation test is not a successful Android UI run. The older 45/44/1 and 95/94/1 test totals belong to earlier releases; they must not be reused as 1.2 results. The current build report is authoritative for the new count.

The intended distribution is one APK containing the **13 pinned baseline resource files totaling 715,732,683 bytes (682.6 MiB)**. This is the resource payload, not an APK-size or RAM measurement. The manifest fixes each file's identity and license. Gradle rejects a missing, changed or extra external asset. APK verification must establish that the packaged files match those identities; source declarations alone cannot establish that.

## What changed in 1.2

| Area | Implemented behavior | Evidence still required on the target phone |
| --- | --- | --- |
| Included resources | Local copy/check of E5, IndicTrans2, optional English GGUF and Whisper from the APK; no app network path | Installation/preparation storage, interruption recovery and adequate free RAM |
| Retrieval | English, Urdu and both Punjabi scripts can query multilingual E5; lexical fallback, coverage and abstention guards remain | Representative corpus relevance, unsupported questions and latency |
| Translation | Dedicated English-to-Urdu/Gurmukhi NMT; Shahmukhi is separately labelled Punjabi script conversion | Fluent-speaker review of meaning, dialect, names, quantities and warnings |
| Answer behavior | Default cited excerpts; optional English synthesis with source checks and fallback | End-to-end interactions, useful abstention and misleading-source cases |
| Sources | Typed citation IDs open saved passages and linked document passages; originals remain visible | UI behavior, long text/RTL and changed/deleted sources |
| Documents | Bounded TXT/DOCX/selectable PDF preview, indexing, catalog and administrator deletion | Real provider/files, extraction order and small-screen operation |
| Persistence | Schema 1→2 migration; format 2 backup with catalog/mappings/typed IDs, accepting format 1 | Update/restart and restore on a second installation |
| Derived state | Bounded 384-dimensional vectors; stale-write protection and invalidation; revision-bound translation cache | Larger corpus growth, cancellation and low-storage conditions |
| Runtime | CPU libraries for ARMv7 NEON, ARM64 and x86_64; prepared API-23/16-KiB ONNX JNI wrapper | Actual API/CPU/font compatibility and sustained memory/thermal behavior |
| Voice | Included multilingual Whisper tiny Q5_1 and installed offline Android TTS selection | Actual microphone, each requested language and speaker/media route |

## Model evidence and interpretation

Development-host E5 probes retrieved the intended English passage for a small synthetic set of English, Urdu, Gurmukhi and Shahmukhi questions. This is a limited relevance demonstration. Similarities and margins from that set do not certify broad retrieval accuracy or medical suitability. Missing requested fields or unsupported questions must still lead to abstention rather than a plausible unrelated answer.

The dedicated translator is **IndicTrans2 distilled 200M int8**, not a relabelled Urdu output or a claim that the small English GGUF learned Urdu/Punjabi. Native SentencePiece, the pinned dictionary mapping, Indic text processing and autoregressive tensor/cache behavior need parity checks as well as actual translation probes. Quantities and literal identifiers use strict restoration/preservation checks. A passing synthetic sentence does not establish reliable translation of every medicine leaflet.

`PunjabiShahmukhi` is an original Unicode/alphabet implementation with a small whole-word spelling glossary, applied after actual Punjabi Gurmukhi translation. Component checks cover the accepted fixture, Latin/code/digit/punctuation preservation, common nasal/gemination/conjunct behavior and explicit unsupported-sign errors. It does not resolve every loanword spelling, tone or dialect. It has not received fluent-speaker clinical review.

SmolLM2 360M is an **optional English-focused synthesizer**. Earlier host examples copied a literal and summarized two facts, but a missing-fact test did not abstain correctly. That failure remains a reason for default excerpts and source/coverage checks; it must not be counted as a successful no-answer test. A generated explanation is not proof that the cited source entails every sentence.

Whisper host transcription checks demonstrate a narrow recognition path. They do not establish Urdu/Punjabi accuracy, script output, Android microphone routing or offline TTS quality. Recognition, text translation and spoken output are separate capabilities.

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
6. Test microphone/recognition and installed offline TTS separately for every intended language/script. Check permission denial and text fallback, cancellation and actual media routing.
7. Compare default excerpts with optional synthesis. Measure first/repeated latency, peak process/native memory, storage growth and thermal behavior. Test larger collections and low-memory/low-storage recovery.
8. Start/stop the optional foreground appliance session visibly; check notification stop, interruption, duration limits and resource release. Test external USB equipment only when attached.

## Historical releases

The [1.1 RAG build report](RAG-BUILD-REPORT.json), [1.1 XML](rag-test-results/) and [Qwen model findings](MODEL-VALIDATION.txt) describe the previous release. Its 95 discovered/94 passed/one skipped test count is historical. Its small Qwen candidate failed Urdu/Punjabi generation/translation checks; that is not a result for the new dedicated IndicTrans2 pipeline. Version 1.1 bundled recognition but required a separate language model and had no E5 index.

The [initial build report](BUILD-REPORT.json), [initial XML](test-results/), [Android runtime attempt](android-final-status.txt) and [Compose test status](compose-ui-test-status.txt) describe 1.0-era work. Its 45 discovered/44 passed/one skipped count, missing bundled weights, 32-bit inference stub and TXT-only limits have been superseded. Native host results in `tests/native/RESULTS.txt` likewise identify their original fixture/runtime context; they are not a phone benchmark for every current component.
