# Offline voice validation for 1.3

The Urdu fine-tuned Whisper Base candidate lowered basic word error on all four checked Urdu clips compared with generic Base, but invented speech for all three non-speech controls. It still changed important words and numbers. A speech detector is required to address those observed false transcripts; a separately checked local detector rejects them, with canonical Kotlin parity recorded below. Punjabi CTC produced Gurmukhi on four checked clips, where generic Whisper produced romanization on the original pair, but CTC still made substantial recognition errors. These are small host observations, not fluent-speaker, clinical or phone acceptance. Bundling decisions and Android integration are separate from these checks.

The original set contains two English, two Urdu and two Punjabi FLEURS human-recorded read-speech clips. The two new Urdu and two new Punjabi clips were selected in row order before candidate inference, excluding the original utterance IDs and exact references. They are held out from this project's comparisons; absence from model pretraining is unverified. All inputs are mono 16 kHz PCM16. Original source and sample hashes match the [published tiny fixtures](offline-voice-1.2.1/fixtures.json); the existing [1.2.1 observations](OFFLINE-VOICE-VALIDATION.md) remain unchanged.

Basic WER/CER use Unicode NFKC, case folding, punctuation converted to spaces, and whitespace tokens; CER removes spaces. Diacritics and combining marks remain. These are not the official FLEURS metrics, and four clips do not estimate population accuracy. Cross-script Punjabi edit rates cannot measure fluency. Lower rates here establish only fewer text edits on these exact recordings.

| Original fixture | Tiny Q5_1 basic WER | Generic Base Q5_1 | Generic Small Q5_1 | Observation |
| --- | ---: | ---: | ---: | --- |
| English validation 0 | 6.67% | 0.00% | Not run | Base reproduced the reference. |
| English validation 1 | 24.24% | 15.15% | Not run | Base still changed Javanese to Japanese. |
| Urdu test 0 | 59.09% | 36.36% | 25.00% | Larger models reduced edits on this clip. |
| Urdu test 1 | 60.00% | 70.00% | 60.00% | Tiny/Base lost the number six; Small retained `چھے` but made many other errors. |
| Punjabi validation 0 | 110.34% | 100.00% | 100.00% | All three produced Latin romanization rather than Gurmukhi. |
| Punjabi validation 1 | 100.00% | 100.00% | 100.00% | All failed the requested script; Small also produced unrelated mixed output. |

Tiny's six results are from the earlier published host. One fresh-host Tiny Punjabi control reproduced the old transcript exactly; this is not a rerun of all six Tiny cases. Generic Base reran all six locally, and Small ran the four Urdu/Punjabi clips only. Small was rejected as the general upgrade: it did not fix Punjabi script, took 97–195 seconds per request in this host session, and sampled about 495–501 MB child RSS. Its 190,085,487-byte file also exceeded the benchmarked 180 MiB Whisper limit by 1,341,807 bytes. The direct JNI harness bypassed that Android guard. No Small English controls were run.

Beam search and generic Punjabi script hints did not produce a useful Base alternative. Beam width three left both Urdu WER values unchanged; Punjabi remained Latin. The longer generic Gurmukhi seed produced unrelated English. These rejected profiles were not promoted, and their canceled non-speech matrix is not a passed control test.

| Urdu test row | Group | Generic Base WER / CER | Urdu fine-tuned Base WER / CER |
| --- | --- | ---: | ---: |
| 0, ID 1806 | Original | 36.36% / 14.48% | 29.55% / 11.03% |
| 1, ID 1930 | Original | 70.00% / 23.46% | 65.00% / 16.05% |
| 2, ID 1869 | Development held-out | 44.90% / 29.80% | 38.78% / 23.84% |
| 3, ID 1966 | Development held-out | 57.14% / 22.58% | 28.57% / 11.29% |

Every Urdu result used the same canonical JNI, explicit `ur`, greedy decoding, two threads, no prompt, `translate=false` and `no_context=true`, with a fresh model/context per request. Fine-tuning reduced basic WER on all four, but the original row 1 still substituted `سے` for the reference's `چھے` (six). On new row 2, generic Base emitted `ستر` (seventy) for reference `70`, while the fine-tuned candidate emitted `سطح` (surface). A decimal-digit-only test would miss that distinction. These non-medical quantity errors rule out treating the results as reliable medical dictation or dose capture. Names, spelling, word boundaries and other content also remain wrong.

The fine-tuned candidate's four requests took 23.6–26.6 seconds wall time and sampled 259–262 MB child RSS. The two generic held-out requests ran without another inference process; earlier runs carry shared-host timing labels. Native and wall-clock timings differ, so they are not a speedup benchmark. Three direct JNI controls failed: silence produced `یہاں ایسا ہوں۔`, higher noise produced `یگر ہوا تھا۔`, and a 440 Hz tone produced `جو رہے تو دیگہ کوئی خوب م marsh`. Each contained no human speech and returned success with words. The energy-only check evaluated before adding Silero rejected silence but allowed the tested noise and tone. These direct JNI requests bypassed the later speech-presence gate. These pre-gate failures remain part of the evidence even though the four speech clips have lower WER. A separate local Silero v6.2.3 reference gate, using its unmodified official wrapper, threshold 0.5 and 250 ms minimum speech, accepted the four Urdu/two English recordings and rejected these three controls (9/9 decisions). This small check does not measure general speech-detector accuracy or repair wrong words/numbers. The [canonical Kotlin gate](offline-voice-1.3/speech-gate/jvm-parity-results.json) matched all nine decisions and every frame probability exactly on the host, reset state successfully on repeated requests, and passed 11 unit tests. Its checked source SHA-256 is `42b627979d4d7f64d1a7f4fe2e980ceaa4a024062c64e40c3f6579970c3c0506`. The [unmodified official reference](offline-voice-1.3/speech-gate/results.json) remains archived separately. A [separate gate probe](offline-voice-1.3/speech-gate/jvm-cancellation-results.json) checked pre-terminated RunOptions, callback cancellation, recovery after reset and rejection after close; it did not test concurrent mid-frame UI cancellation. This verifies the gate kernel on these inputs; Android microphone/service integration is outside this host check.

| Punjabi validation row | Group | Canonical bounded CTC WER / CER |
| --- | --- | ---: |
| 0, ID 1539 | Original | 55.17% / 15.57% |
| 1, ID 1546 | Original | 45.28% / 14.36% |
| 2, ID 1543 | Development held-out | 76.92% / 26.92% |
| 3, ID 1577 | Development held-out | 36.36% / 8.82% |

The canonical Kotlin CTC kernel emitted Gurmukhi on all four clips. The medical-news held-out row 2 still corrupted `ਨਾਈਜੀਰੀਆ` (Nigeria) and `ਈਬੋਲਾ` (Ebola), among other words. This is not reliable medical dictation. No Shahmukhi recognition was evaluated. It uses ONNX Runtime 1.20 CPU, two threads, whole-utterance normalization, 12-second windows with 0.8-second overlap, frame-center merging, and one global greedy CTC collapse. CPU arena and memory pattern are disabled.

Canonical JVM requests took 0.77–3.20 seconds wall time including model setup and sampled 316–495 MB RSS. Four synthetic controls—silence, quiet noise, higher noise and tone—returned empty text. Repeated/truncated 30/60-second audio checks used 3/6 windows and peaked at 450/501 MB; these test resource bounds, not new speech accuracy. Caller cancellation, native RunOptions termination, session reuse after reset, and nine kernel unit tests passed on the host.

The first NumPy prototype had different transcripts from Kotlin because Float32 normalization statistics differed from Kotlin's Double statistics followed by Float32 output. A controlled comparison with identical normalized input bytes reproduced every raw frame ID and transcript on all four clips. The table uses the actual final Kotlin results, including the worse medical-news result, rather than selecting the lower NumPy edit rates. The [final kernel report](offline-voice-1.3/punjabi/final-kernel-results.json) records source SHA-256 `769cff8b2cb7fdebdede220cbc594e7b3f4f16be02deceed71769e1c413d26b2`, which matched the reviewed app kernel when archived.

Model identity and licensing:

| Candidate | Bytes | SHA-256 | Source and license |
| --- | ---: | --- | --- |
| Tiny Q5_1 | 32,152,673 | `818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7` | `ggerganov/whisper.cpp`, MIT |
| Generic Base Q5_1 | 59,707,625 | `422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898` | Same pinned repository, MIT |
| Generic Small Q5_1 | 190,085,487 | `ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb` | Same pinned repository, MIT |
| Urdu fine-tuned Base Q5_1 | 59,707,642 | `701f3ae011e0964e45894ad884da1afd5c1a0a7a846b054d9765e75d563ee7b9` | Derived from `kingabzpro/whisper-base-urdu-full`, Apache-2.0 declared in model card; underlying OpenAI Whisper MIT |
| Silero speech-presence gate | 2,327,524 | `1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3` | `snakers4/silero-vad` v6.2.3, MIT |
| Punjabi int8 CTC | 121,951,036 | `06cd96375424bc23e148191a3cdb7ee03691ba4c4438cdc8c25e53472bdffab5` | `XDcobra/wav2vec2-large-xlsr-53-punjabi-ONNX`, Apache-2.0; upstream Vakyansh MIT attribution retained |

Generic Whisper files are pinned to revision `5359861c739e955e79d9a303bcbc70fb988958b1`. Urdu source revision is `6bfaec492d42f4b76f117b2b34cd9672509cecdc`; its card describes Common Voice 17 Urdu fine-tuning. The source has no standalone LICENSE/NOTICE. Conversion verified all 245 tensors, tied output projection, mel filters, 51,865 normal/special vocabulary IDs and 50,000 merge ranks before intentionally lossy Q5_1 quantization. The [portable recipe reproduction](offline-voice-1.3/urdu/recipe-reproduction-record.json) also rebuilt the exact recorded Q5_1 hash. The Punjabi export is pinned to `de54b60baa1879a4db93596cf1b5b3215a291b7a`, with a documented Common Voice 8 Punjabi fine-tune and Vakyansh/CLSRIL-23 ancestry. Silero is pinned to revision `5cd7945676eb32225748052e2e6a0580e4686a08`. Model-card benchmark numbers were not substituted for these actual outputs.

The 2026-11-19 host had a four-CPU quota, 16 GiB RAM, Linux x86_64, JDK 21.0.2 and GCC 14.2. The fresh canonical JNI library SHA-256 is `0855a868533fcefb0bf909346ab228836de87a8bd22f3096e2f6e0dc07e480a1`, built from C++ source SHA-256 `9ad280a212bb9245db15f42ef44dee38b9884150d11857476823cd8e8e7fe079`. Generic host SIMD flags were disabled. Requests used two inference threads; some exploratory runs overlapped another two-thread case. RSS includes host runtime allocations and sampling can miss peaks. These measurements do not predict latency or memory on a 2 GB ARMv7 phone.

The checks bypass Android `AudioRecord`, microphone permissions, mobile noise/VAD, hardware routing, offline TTS, service lifecycle and screen-off behavior. There was no fluent-speaker listening review, broad accent/noise evaluation, clinical evaluation or physical-phone test. Recognition must be reviewed before use; the model's language name and output script do not establish accurate content.

All fixtures are FLEURS, Google dataset `google/fleurs`, pinned revision `70bb2e84b976b7e960aa89f1c648e09c59f894dd`, under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Attribution: Conneau et al., *FLEURS* (2022), [paper](https://arxiv.org/abs/2205.12446), [pinned dataset card](https://huggingface.co/datasets/google/fleurs/blob/70bb2e84b976b7e960aa89f1c648e09c59f894dd/README.md). Additional rows were Urdu `ur_pk/test` 2–3 and Punjabi `pa_in/validation` 2–3, IDs 1869/1966 and 1543/1577. Audio conversion used FFmpeg without denoising or synthetic speech. Public development fixture downloads were separate from local inference; no recognition provider API was used.

The [evidence index](offline-voice-1.3/index-manifest.json) records every archived file's bytes and SHA-256, source identities and host-only scope. The [reproduction notes](offline-voice-1.3/README.md) distinguish exact runner snapshots from portable wrappers. No raw audio, weights, compiled classes, native libraries or signed audio URLs are included.

- [Whisper comparison](offline-voice-1.3/whisper/comparison.json), [paired Urdu results](offline-voice-1.3/whisper/urdu-paired-comparison.json), and [rejected decoder profiles](offline-voice-1.3/whisper/decoder-comparison.json) retain references, raw outputs and metrics.
- [Urdu controls](offline-voice-1.3/urdu/nonspeech-controls.json), [conversion](offline-voice-1.3/urdu/conversion-verification.json), [vocabulary](offline-voice-1.3/urdu/vocabulary-verification.json), and [derived identity](offline-voice-1.3/urdu/derived-model-manifest.json) preserve failures and provenance.
- [Final CTC results](offline-voice-1.3/punjabi/final-kernel-results.json), [matched-input proof](offline-voice-1.3/punjabi/matching-input-results.json), and [model provenance](offline-voice-1.3/punjabi/manifest.json) preserve the canonical metrics and checks.
- [Restored original fixtures](offline-voice-1.3/fixtures/original-restored.json), [fixed held-outs](offline-voice-1.3/fixtures/heldout.json), [fixture audit](offline-voice-1.3/fixtures/heldout-audit.json), and [synthetic controls](offline-voice-1.3/fixtures/controls.json) record sources, licenses and hashes.
