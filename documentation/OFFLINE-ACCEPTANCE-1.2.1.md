# Additional offline acceptance · 1.2.1

The available software and host model checks are complete. The patch fixes keyboard submission during active/pending recording, indexing starvation caused by a long saved note, and observed wrong-topic retrieval cases. The Android APK is still a prerelease: no physical phone, stable Android UI/native installation flow, microphone, installed TTS voice or clinical acceptance pass is claimed.

## Completed checks

| Check | Actual result | Scope |
| --- | --- | --- |
| JVM regression suite | 171 passed, one explicitly skipped, zero failures | Includes real TXT/DOCX/text-PDF extraction through approved import, catalog, citations, reopen and clean-store backup restore; delayed edited/deleted-source checks; queue and query regressions. |
| Android lint | Zero errors, 19 warnings; release vital checks passed | Static Android API and packaging checks, including API 23. |
| Model-backed service acceptance | All 11 phases passed | Real canonical services, authenticated SQLite, pinned E5/IndicTrans2/SPM/ORT and synthetic references in a Linux Robolectric process. |
| Revised retrieval probe | Expected source present for 14 of 16 known questions; zero false matches on 32 negative questions | All four known English/Urdu/Gurmukhi/Shahmukhi code questions matched. This is a small synthetic corpus, not a representative relevance benchmark. |
| Network observation | Zero outbound IP connect/send operations observed | The external host test JVM was traced; local failed `nscd` lookups and JVM socket-capability probes were recorded separately. This is not Android airplane-mode testing. |
| Speech recognition | Six real model calls returned text; language quality did not pass | Urdu substitutions/omissions and Punjabi script mismatch remain documented failures. See the [voice observations](OFFLINE-VOICE-VALIDATION.md). |
| Signed APKs | See the [versioned build report](OFFLINE-1.2.1-BUILD-REPORT.json) | Exact versions, certificates, resource hashes, permissions, backup exclusions, ABI and alignment evidence belong to the completed artifact audit. |

The model-backed run performed local resource preparation, pause/cancel/resume and integrity checks; indexed later references while leaving an oversized note visibly pending; edited and reindexed that note; exercised actual multilingual answers and typed citations; reused the translation cache; reopened services/database; restored a backup and rebuilt vectors; invalidated edited/deleted sources; and cancelled active ORT inference before a successful fresh translation. It did not substitute fake answer backends or synthetic vectors for these model-backed phases.

The host had a 16 GiB memory limit and four CPU cores of quota. Robolectric supplied Android `ActivityManager` values of 2 GiB total and 1.5 GiB available memory, plus storage values, to exercise guards. Those supplied values do **not** prove operation on a physical 2 GB phone. The traced process peak RSS was about 1.43 GiB, including the test framework and actual native runtimes; it is not an Android memory prediction. Android services, UI, audio hardware and lifecycle execution still require device testing.

## Retained failures and limits

The first full run abstained on the Shahmukhi question `ورکشاپ دا کوڈ کی اے؟` despite finding the correct document at the top of the E5 ranking. A proposed lower score cutoff then admitted wrong airport/garage references and was rejected. The revised path verifies code-question subject words against the original source or its actual local forward translation. It uses no workshop/place dictionary, preserves distinct PIN/password/Wi-Fi rules, and requires nonempty topic evidence before accepting a weaker code candidate. The ordinary semantic cutoff remains unchanged. Approved manual Q&A questions supply context for short answers; imported filenames cannot create fields missing from document text.

The final probe still abstained on these two known reference-number questions:

- `ਵਰਕਸ਼ਾਪ ਦਾ ਹਵਾਲਾ ਨੰਬਰ ਕੀ ਹੈ?`
- `ورکشاپ دے حوالے دا نمبر کی اے؟`

“14 known matches” means the expected source was present; it does not mean every returned source was relevant. The English insulin-storage question also returned two unrelated medicine references alongside the correct insulin source. The detailed record lists additional sources and overlapping leaflet references. Similarity, topic, quantity and citation checks cannot establish complete entailment or clinical suitability. Read the intact source and do not treat retrieved medicine passages as personalized prescribing.

The [earlier model component checks](OFFLINE-MODEL-VALIDATION.md), including the optional English generator's recorded missing-fact failure, remain relevant. The new host run uses default cited excerpts and dedicated translation; it does not turn that generator into a validated clinician or a reliable general abstention model. Shahmukhi is labelled script conversion after Punjabi translation and has spelling/dialect limits.

The [evidence directory](offline-acceptance-1.2.1/) includes final results, source hashes and the earlier failures. The [external harness](../tests/offline-integration/) documents reproduction. Raw audio, compiled runtimes, standalone model weights, private signing material and user data are excluded from these reports.

## Checks still requiring a device

This environment exposes no KVM acceleration or USB device bus and has no accessible stable Android runtime. Previous software-emulator runs failed in Android's framework. ADB enumeration here also encountered the read-only preferences directory. No further identical emulator attempt was counted as acceptance.

On a supported phone or accelerated emulator, install the signed patch, disable networking, finish local setup, import a synthetic reference, inspect known/unknown answers and citations, restart, restore a disposable backup, and test each microphone/TTS language and low-memory behavior. Use a disposable debug installation for instrumentation that resets data. Back up real knowledge before uninstalling or resetting an app. The itel A25 Pro, every Android phone and medical use remain unvalidated.
