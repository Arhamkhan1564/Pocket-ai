The Urdu fine-tuned Whisper Base Q5_1 candidate reduces basic word errors on four exploratory Urdu recordings compared with generic Whisper Base. It still makes substantial word and spoken-number errors. It is suitable only as a prerelease candidate with a validated speech-presence gate and transcript review; these results do not establish target-phone, microphone, medical, or general Urdu acceptance.

The source is [kingabzpro/whisper-base-urdu-full](https://huggingface.co/kingabzpro/whisper-base-urdu-full/tree/6bfaec492d42f4b76f117b2b34cd9672509cecdc), pinned at `6bfaec492d42f4b76f117b2b34cd9672509cecdc`. Its `model.safetensors` is 290,403,936 bytes, SHA-256 `0819a35b96bc52ba5c2448f67f6beed9ca3e7d0d57bcf913eb21feb096bdfa3f`. The card declares Apache-2.0; no separate LICENSE or NOTICE is present in that pinned repository. The underlying OpenAI Whisper MIT notice is retained alongside the [Apache license and attribution](../app/src/main/assets/licenses/URDU_WHISPER_MODEL_NOTICE.txt).

The derived `ggml-urdu-base-q5_1.bin` is **59,707,642 bytes**, SHA-256 **`701f3ae011e0964e45894ad884da1afd5c1a0a7a846b054d9765e75d563ee7b9`**. It is the standard six-layer encoder/six-layer decoder Base architecture, width 512, 80 mel bands, and 51,865 vocabulary entries. It uses no new speech runtime. Its source config has a stale English forced-token setting; the tested native decoder explicitly selects Urdu (`ur`) and does not use that generation setting. English and Punjabi quality of these fine-tuned weights was not evaluated; do not assume they replace a general multilingual model for every language.

All results below use the same actual canonical JNI decoder, explicit Urdu, greedy `best_of=1`, no initial prompt, two threads, and exact hash-matched PCM recordings from the pinned FLEURS dataset. The last two rows were selected as the first distinct rows after the originals before their model results were known. They are held out from this exploratory comparison, with no assertion about exclusion from the model's training data.

| Urdu test row | Generic Base WER | Urdu fine-tuned Base WER | Generic Base CER | Urdu fine-tuned Base CER |
| --- | ---: | ---: | ---: | ---: |
| 0 | 36.36% | 29.55% | 14.48% | 11.03% |
| 1 | 70.00% | 65.00% | 23.46% | 16.05% |
| 2, held out | 44.90% | 38.78% | 29.80% | 23.84% |
| 3, held out | 57.14% | 28.57% | 22.58% | 11.29% |

These NFKC/casefold/punctuation-space edit rates are basic comparisons, not fluent-speaker quality scores. All four hypotheses use Arabic script. The model still loses `چھے` (six) in row 1. In row 2, generic Base retains `ستر` (seventy), while the fine-tuned model produces `سطح` (surface). Improved aggregate error rates do not make quantities safe. The source card's Common Voice evaluation claims are author-reported and were not independently reproduced here.

Before a speech-presence gate, the fine-tuned model hallucinates words on all three synthetic six-second controls:

| Audio with no human speech | Raw fine-tuned Whisper output |
| --- | --- |
| Digital silence | `یہاں ایسا ہوں۔` |
| Deterministic uniform noise | `یگر ہوا تھا۔` |
| 440 Hz tone | `جو رہے تو دیگہ کوئی خوب م marsh` |

Android's existing energy gate rejects the silence fixture, but the louder noise and tone pass it. The model must not become a default behind that energy gate alone. Generic model non-speech behavior was not compared in this control batch.

The separate offline speech-presence prototype uses official [Silero VAD v6.2.3](https://github.com/snakers4/silero-vad/tree/5cd7945676eb32225748052e2e6a0580e4686a08), MIT licensed, pinned at `5cd7945676eb32225748052e2e6a0580e4686a08`. Its ONNX file is 2,327,524 bytes, SHA-256 `1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3`. The unmodified official Python `OnnxWrapper` and `get_speech_timestamps` detect all four Urdu and both English positive recordings and reject all three non-speech controls, with their fixed defaults: threshold 0.5, minimum speech 250 ms, minimum silence 100 ms, 30 ms padding, 512-sample frames, 64-sample context, and fresh recurrent state per recording. No threshold was fitted to these examples. The actual canonical Kotlin speech-presence kernel then matched every reference probability exactly on all nine fixtures, repeated each fixture to verify state reset, and made the same nine decisions; see the [Kotlin parity report](offline-voice-1.3/speech-gate/jvm-parity-results.json). Eleven focused kernel tests passed. These are bounded host checks; actual-phone acoustic and runtime behavior remain unverified.

Conversion is reproducible using [scripts/voice-models](../scripts/voice-models/README.md). Only safetensors is loaded, with local files and no remote code or pickle. All source tensors load without missing, unexpected, or mismatched keys. The output projection is checked for identical embedding storage and values before the converter omits it. Every converted tensor is checked against its intended F16/F32 source values; all normal, special, language, and timestamp token mappings are checked. The small precision correction to the pinned upstream converter is retained as an explicit diff. Q5_1 quantization is intentionally lossy.

The fine-tuned model used approximately 247–250 MiB sampled child-process RSS and 23.6–26.6 seconds per recording on the generic Linux host. Some speech comparisons share the host with another two-thread benchmark. These are observations, not latency comparisons or Android/NEON predictions. The host has 16 GiB memory and a four-CPU quota; no attached 2 GiB phone was tested. The model fits the existing 180 MiB file-size limit, but the application's available-memory guard remains necessary.

Raw transcripts, source hashes, pre-gate failures, VAD probabilities, and implementation limits are preserved in the [voice validation evidence](VOICE-VALIDATION-1.3.md). No medical or speaker acceptance, TTS quality, Android audio routing, or physical-phone result is implied.
