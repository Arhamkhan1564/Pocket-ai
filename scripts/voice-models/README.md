These scripts reconstruct the Urdu Whisper Base Q5_1 candidate from public, checksum-pinned safetensors. They do not train a model or send audio to a provider. Keep all downloaded and derived weights outside the application/source tree.

Run from the repository root on Linux with Python 3.12, a C++ compiler, CMake 3.22.1 or newer, and a discoverable JDK. The recorded reference used x86_64, GCC 14.2.0, JDK 21.0.2, Torch 2.5.1+cpu, Transformers 4.51.3, and the exact vendored sources. The source checks intentionally stop if the converter or quantizer changes.

```sh
task_voice_root=/tmp/pocketai-urdu-model
python3 -m venv "$task_voice_root/venv"
"$task_voice_root/venv/bin/python" -m pip install -r scripts/voice-models/requirements-urdu-base.txt
python3 scripts/voice-models/fetch_urdu_base.py --output-dir "$task_voice_root"

cmake -S app/src/main/cpp -B "$task_voice_root/native" -DCMAKE_BUILD_TYPE=Release -DCMAKE_POLICY_VERSION_MINIMUM=3.5
cmake --build "$task_voice_root/native" --target pocketai -j2

python3 scripts/voice-models/verify_urdu_vocabulary.py --model-dir "$task_voice_root/hf-model" --native-library "$task_voice_root/native/libpocketai.so" --output-dir "$task_voice_root/output"
"$task_voice_root/venv/bin/python" scripts/voice-models/convert_urdu_base.py --model-dir "$task_voice_root/hf-model" --standard-base-ggml "$task_voice_root/ggml-base-q5_1.bin" --output-dir "$task_voice_root/output"
python3 scripts/voice-models/quantize_urdu_base.py --native-build-dir "$task_voice_root/native" --conversion-dir "$task_voice_root/output"
```

The expected result is `output/converted/ggml-urdu-base-q5_1.bin`, **59,707,642 bytes**, SHA-256 `701f3ae011e0964e45894ad884da1afd5c1a0a7a846b054d9765e75d563ee7b9`. The quantization script rejects a different reference hash instead of silently declaring it equivalent. Toolchain/platform differences require investigation before publication.

`urdu-base-inputs.json` pins every source asset and the standard Base GGML file used solely to extract its unchanged OpenAI mel coefficients. The model loader permits only local safetensors, blocks `torch.load`, prohibits remote code, checks loading diagnostics, and verifies that the decoder output projection truly shares the input embedding storage and values. An untied output projection must stop conversion because this native loader cannot represent it separately.

The pinned upstream converter first rounds every tensor to FP16 even when it subsequently writes some tensors as F32. `converter-precision-fix.diff` records the small correction: cast directly to the intended stored precision. The script preserves the original converter, writes the patched copy into the output directory, and checks all 245 converted tensor names, shapes, dtypes, and values. Matrix rounding to FP16 and later Q5_1 quantization are intentional; the F32 normalization, bias, and position tensors retain their original F32 values.

Vocabulary checks cover all 50,258 normal IDs, 50,000 merge ranks, 99 language IDs, task tokens, and 1,501 timestamp IDs. The quantizer is built from the pinned Whisper sources and the same GGML static libraries as the canonical JNI runtime. Its generated model uses GGML quantization version 2, file type 2009.

The source model card declares Apache-2.0 but contains no separate LICENSE/NOTICE file. Preserve that card plus the original OpenAI MIT notice and [the application attribution](../../app/src/main/assets/licenses/URDU_WHISPER_MODEL_NOTICE.txt). See [the measured limitations](../../documentation/URDU-SPEECH-MODEL.md) before selecting this candidate: it still misrecognizes words and spoken quantities, and requires a real speech-presence gate.
