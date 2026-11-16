# Native runtime smoke test

This test compiles the same JNI adapter on a Linux x86_64 host, exercises actual
GGUF token generation and known-speech Whisper transcription, and verifies
invalid-input recovery. It does not benchmark the user's Android phone or prove
Urdu/Punjabi recognition quality. Models are explicit test inputs. Version 1.1
ships a multilingual Whisper tiny q5_1 speech model in the APK; model weights
remain external to the source project and language models are not bundled.

Requirements: CMake 3.22.1+, Ninja, C++17 compiler, JDK 17+ with JNI headers.
Use a tiny valid GGUF, a multilingual Whisper tiny `ggml` model, and a mono
16-bit 16 kHz PCM WAV containing speech. The bundled upstream sample is
`vendor/whisper.cpp/samples/jfk.wav` and the harness checks for "ask" and "country".

```sh
cmake -S app/src/main/cpp -B build/native-host -G Ninja \
  -DCMAKE_BUILD_TYPE=Release -DGGML_AVX=OFF -DGGML_AVX2=OFF \
  -DGGML_FMA=OFF -DGGML_F16C=OFF -DGGML_BMI2=OFF -DGGML_SSE42=OFF
cmake --build build/native-host --target pocketai -j 4
javac -d build/native-test tests/native/NativeBridge.java
java -cp build/native-test com.pocketai.engine.NativeBridge \
  build/native-host/libpocketai.so /path/to/stories260K.gguf \
  /path/to/ggml-tiny.bin vendor/whisper.cpp/samples/jfk.wav
```

Reference smoke-test files:

- `https://huggingface.co/ggml-org/models/resolve/main/tinyllamas/stories260K.gguf`
  (~1.2 MB). This tiny model is only for validating inference; it is unsuitable
  as an assistant and must not be recommended as a product model.
- `https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin`
  (~75 MiB). Verify origin, license, size and checksum before using any model.

Actual downloaded checksums and results for this build are recorded in the
delivery validation report.

## Android JNI instrumentation

`NativeRuntimeTest` runs the same two real inference operations inside the
debug Android application. It is skipped unless explicit fixture files are
present; these additional test fixtures are not packaged into the APK. With a
connected compatible ARM or x86_64 test device and the debug app installed:

```sh
adb shell mkdir -p /data/local/tmp/pocket-native-fixtures
adb push /path/to/stories260K.gguf /data/local/tmp/pocket-native-fixtures/
adb push /path/to/ggml-tiny.bin /data/local/tmp/pocket-native-fixtures/
adb push vendor/whisper.cpp/samples/jfk.wav /data/local/tmp/pocket-native-fixtures/
adb shell run-as com.pocketai.debug mkdir -p cache/native-fixtures
adb shell run-as com.pocketai.debug cp /data/local/tmp/pocket-native-fixtures/stories260K.gguf cache/native-fixtures/
adb shell run-as com.pocketai.debug cp /data/local/tmp/pocket-native-fixtures/ggml-tiny.bin cache/native-fixtures/
adb shell run-as com.pocketai.debug cp /data/local/tmp/pocket-native-fixtures/jfk.wav cache/native-fixtures/
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.pocketai.NativeRuntimeTest
```

The test checks ASR before any LLM initialization, generation after speech
unloading, and recoverable invalid-model/audio errors. It does not record the
device microphone or validate its TTS voices. Remove fixture directories after
testing, or clear the debug application's storage.
