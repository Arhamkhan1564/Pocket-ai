Offline voice host harness

This standalone Java harness invokes the canonical native transcription entry
point with explicit local model/audio files. It does not use Android, a cloud
recognizer or text-to-speech. It does not evaluate microphone/phone behavior.

Use JDK21 and a host build of the canonical app C++ JNI library. The general host
native build procedure is in ../native/README.md. Build outputs and fixtures
belong outside the source checkout. Do not point this host harness at an Android
ABI library; Linux host and Android libraries are different binaries.

From the repository root, choosing your own external scratch directory:

  javac -d SCRATCH/classes tests/offline-voice/com/pocketai/engine/NativeBridge.java
  java -Xms16m -Xmx128m -cp SCRATCH/classes com.pocketai.engine.NativeBridge LIBRARY MODEL LANGUAGE PCM_WAV RESULT_JSON

LIBRARY: fresh Linux host libpocketai.so
MODEL: local ggml-tiny-q5_1.bin with the digest in the evidence report
LANGUAGE: en, ur or pa, as appropriate
PCM_WAV: mono16kHz signedPCM16 RIFF/WAVE input
RESULT_JSON: external scratch file for the actual transcript and native elapsed time
The runner uses two native CPU threads and creates a model/context for each call.

A zero process exit means the call completed, not that recognition is accurate.
Inspect actual transcripts and references. The six recorded FLEURS observations
included substantial Urdu errors and Latin-script Punjabi output.

See documentation/OFFLINE-VOICE-VALIDATION.md (relative to repository root) and
its offline-voice-1.2.1/fixtures.json for public dataset revision, clip IDs,
CC-BY4 attribution, metadata locators and input hashes. Obtain public audio URLs
from the dataset viewer, verify the source hash, then convert in scratch using:

  ffmpeg -nostdin -hide_banner -loglevel error -i SOURCE.wav -ac 1 -ar 16000 -c:a pcm_s16le PCM.wav

WAV container metadata can differ across FFmpeg versions, so the report includes
both full-file and PCM sample-payload digests. Verify the payload if the container
header differs. Raw audio, model weights and compiled harness outputs must not be
added to source distributions or the small JSON/TXT evidence collection.
