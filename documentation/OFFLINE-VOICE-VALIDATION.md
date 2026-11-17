# Offline voice observations for 1.2.1

The bundled Whisper tiny Q5_1 model returned text for six public read-speech clips through the freshly rebuilt production JNI transcription entry point. **These results do not establish acceptable Urdu or Punjabi recognition.** Both Punjabi clips produced Latin romanization/mixed English rather than the Gurmukhi reference script; Urdu had substantial substitutions and omissions.

| Language and fixture | Audio duration | Native request | Basic WER | Observation |
| --- | ---: | ---: | ---: | --- |
| English, validation row 0 | 6.54 s | 13.78 s | 6.7% | Sentence meaning mostly retained; contraction differed. |
| English, validation row 1 | 16.38 s | 10.84 s | 24.2% | Javanese became Japanese; several food terms changed. |
| Urdu, test row 0 | 12.24 s | 15.11 s | 59.1% | Arabic script, with many word substitutions and omissions. |
| Urdu, test row 1 | 6.24 s | 11.44 s | 60.0% | Several words changed; the reference's `چھے` (six) was absent. |
| Punjabi, validation row 0 | 17.64 s | 13.21 s | 110.3% | Latin romanization/mixed English; script differs from the reference. |
| Punjabi, validation row 1 | 24.54 s | 17.77 s | 100.0% | Latin romanization; script differs from the reference. |

The Punjabi edit rates compare different scripts and therefore are not meaningful fluency estimates. The table records observations rather than a representative language benchmark. Basic WER uses Unicode NFKC, case folding, punctuation-to-space conversion and whitespace tokens; it retains vowel/combining marks. This is not the official FLEURS scoring recipe. Urdu spelling, diacritics and spacing also affect the measured rates. None of these references contains decimal digit strings, so digit-quantity preservation was not tested.

The [full JSON record](offline-voice-1.2.1/voice-results.json) contains every reference and actual transcript, script counts, timings, sampled memory and hashes. The [text report](offline-voice-1.2.1/RESULTS.txt) and [scratch Java harness listing](offline-voice-1.2.1/voice-harness-source.txt) describe reproduction. Source and derived WAV hashes identify the exact inputs; sample-payload hashes allow comparison when FFmpeg's WAV metadata differs between versions.

Execution used Linux x86_64, JDK 21, two native threads and explicit `en`, `ur` or `pa` language selection. The canonical JNI used greedy decoding, `translate=false`, `no_context=true` and a fresh model/context per request. The model was `ggml-tiny-q5_1.bin`, 32,152,673 bytes, SHA-256 `818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7`. The [build-identity record](offline-voice-1.2.1/native-build-identity.json) identifies the current C++ source and freshly rebuilt host library. Peak sampled child RSS ranged from about 193.6 to 199.7 MB; sampling can miss short peaks. Timings were gathered during a shared development-host session, with separate unit/compile activity coordinated around the run. They are not controlled phone benchmarks.

These checks bypassed Android `AudioRecord`, microphone permission, phone noise/VAD, installed TTS voices, speakers and the foreground service. There was no human listening or fluent-speaker acceptance. They cannot establish performance on a 2 GB ARMv7 phone or reliable screen-off conversation. Recognized text needs review before saving or sending; the presence of a language identifier in Whisper is insufficient evidence of accurate recognition or the desired script.

The fixtures are from **FLEURS**, Google dataset `google/fleurs`, revision `70bb2e84b976b7e960aa89f1c648e09c59f894dd`, under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Attribution: Alexis Conneau, Min Ma, Simran Khanuja, Yu Zhang, Vera Axelrod, Siddharth Dalmia, Jason Riesa, Clara Rivera and Ankur Bapna, *FLEURS: Few-shot Learning Evaluation of Universal Representations of Speech* (2022), [paper](https://arxiv.org/abs/2205.12446) and [pinned dataset card](https://huggingface.co/datasets/google/fleurs/blob/70bb2e84b976b7e960aa89f1c648e09c59f894dd/README.md). Two English `en_us` validation clips, two Urdu `ur_pk` test clips and two Punjabi `pa_in` validation clips were fetched individually; viewer scan limits prevented the English/Punjabi test-row request. WAVs were converted to mono 16 kHz PCM16 without denoising or synthetic speech.

The [fixture manifest](offline-voice-1.2.1/fixtures.json) records IDs, sources and hashes. Only small JSON/TXT evidence is published here. Raw audio, model weights, compiled classes, native libraries and user data remain outside this documentation collection. Development fixture downloads did not use a provider recognition API; inference read local audio and model files.
