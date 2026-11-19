# Voice evidence collection for 1.3

This collection contains host-only observations, including failed recognition,
failed non-speech controls, and rejected decoder profiles. Read
[VOICE-VALIDATION-1.3.md](../VOICE-VALIDATION-1.3.md) for the scope and interpretation.
It does not establish fluent-speaker, clinical, microphone, Android or 2 GB phone
acceptance. All speech recordings were public FLEURS fixtures; no user recordings
were used.

[index-manifest.json](index-manifest.json) lists every file except itself, with
bytes/SHA-256, source identities and inclusion limits. Verify it without network,
models or Java:

```sh
python3 documentation/offline-voice-1.3/harness/verify-evidence.py
```

The original benchmark JSONs and source snapshots were copied byte-for-byte.
Absolute `/workspace/validation/...` paths in those historical reports identify
the development run and are not required install locations. Reports that say
`inference_performed: false` describe model-manifest creation, before the separate
inference reports. An unsuccessful control does not count as a successful
non-speech rejection.

The canonical Punjabi result is [punjabi/final-kernel-results.json](punjabi/final-kernel-results.json).
Earlier Python prototype results used Float32 normalization statistics. Kotlin
uses Double statistics followed by Float32 model input. The
[matched-input experiment](punjabi/matching-input-results.json) reproduced every
raw frame ID with identical inputs. Retaining earlier mismatches documents the
investigation; their lower edit rates are not substituted for canonical Kotlin
metrics.

The Urdu candidate's [three invented transcripts](urdu/nonspeech-controls.json)
remain failures of direct Whisper inference. The separate
[official reference speech-gate probe](speech-gate/results.json) did reject those
controls. The [canonical Kotlin gate parity](speech-gate/jvm-parity-results.json)
matched all nine decisions and per-frame probabilities, including repeated state
reset; this does not fix the [number and word errors](whisper/urdu-paired-comparison.json).

Reproduction requires a compatible local host JNI library, a matching pinned
model and public fixtures downloaded outside the checkout. No audio, model
weights, compiled classes, native libraries or signed audio URLs are included.
The model manifests record pinned repositories, revisions, licenses, byte sizes
and SHA-256; the Urdu model conversion recipe is under
[scripts/voice-models](../../scripts/voice-models).

The portable fixture wrapper fetches only the rows named in a manifest. It
obtains fresh public audio links in memory, validates their pinned revision and
source SHA-256, and verifies the decoded sample-payload SHA-256. FLEURS is
CC BY 4.0; attribution, dataset card, paper, sources and hashes are in each
fixture manifest. Python 3.10+ and FFmpeg are needed. Put outputs outside source:

```sh
python3 documentation/offline-voice-1.3/harness/fetch-fixtures.py \
  --manifest documentation/offline-voice-1.3/fixtures/heldout.json \
  --output /path/to/external/heldout-audio
```

The portable Whisper wrapper compiles the archived, byte-identical Java harness
with a local JDK 17+ and runs the unchanged JNI entry point in a fresh JVM for
each fixture. It performs no network operation, verifies local model/library and
PCM hashes, forces no experimental profile, and retains logs plus basic metrics.
Example for the fixed Urdu held-outs and generic Base:

```sh
python3 documentation/offline-voice-1.3/harness/run-whisper.py \
  --library /path/to/libpocketai.so \
  --library-sha256 0855a868533fcefb0bf909346ab228836de87a8bd22f3096e2f6e0dc07e480a1 \
  --model /path/to/ggml-base-q5_1.bin \
  --model-sha256 422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898 \
  --manifest documentation/offline-voice-1.3/fixtures/heldout.json \
  --audio-root /path/to/external/heldout-audio \
  --fixtures fleurs-ur_pk-test-2,fleurs-ur_pk-test-3 \
  --output /path/to/external/new-results
```

`harness/snapshots/` preserves the actual experimental runners, control
generation, decoder diffs, and CTC JVM/Python probes. These snapshots retain their
original development paths and require path/dependency adjustment on another
host. The two portable wrappers were added after the observations and were
syntax/Java-compile checked; they did not generate the archived results. Neither
wrapper claims byte-identical native rebuilds or timings across hosts. Set CPU
budgets explicitly and avoid competing inference if comparing latency.

Silero's copied official Python utility is covered by
[LICENSE-SILERO.txt](speech-gate/LICENSE-SILERO.txt); it is unmodified. Model
licenses and upstream provenance are recorded in the model manifests. This
collection does not redistribute model weights or raw FLEURS audio.
