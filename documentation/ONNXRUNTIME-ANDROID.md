# Reproducible ONNX Runtime Android dependency

Pocket AI uses the official ONNX Runtime 1.20.0 CPU libraries and Java API. The
official 1.20.0 JNI wrapper has 4 KiB ELF load alignment. Its CPU libraries already
have 16 KiB alignment. The preparation script rebuilds only the unchanged JNI C
sources with 16 KiB alignment and Android API 23, retaining all 148 original JNI
exports for ARMv7, ARM64 and x86_64. It omits the unused x86 ABI. It does not modify
the ONNX execution kernels, Java classes or model weights.

This preserves the app's Android 6/API 23 minimum. Official ONNX Runtime 1.21.0,
1.22.0 and 1.24.3 AARs declare API 24 as their minimum. Version 1.24.3 has 16 KiB
alignment in both native libraries on all three required ABIs, but would raise the
app's minimum to Android 7. The inspected 1.32.0 AAR additionally declares network
permissions and a telemetry initializer; it is not used by this offline app.

## Prepare before Gradle sync

Install JDK 17 or newer and Android NDK `27.2.12479018`. JDK 21 is used for the
verified distribution build. Set `ANDROID_HOME`/`ANDROID_SDK_ROOT` or
`ANDROID_NDK_HOME`, then run from the source root:

```sh
python3 scripts/prepare-onnxruntime-android.py
python3 scripts/prepare-onnxruntime-android.py --check-only
```

The default output is outside the source checkout:

```text
../runtime-assets/onnxruntime-android-1.20.0-pocketai16k.aar
../runtime-assets/onnxruntime-android-1.20.0-pocketai16k.json
```

Use `--assets-dir` or `POCKETAI_ORT_ASSETS_DIR` to change the preparation directory.
When changing it, also set `POCKETAI_ORT_ANDROID_AAR` or Gradle property
`-PpocketAiOrtAndroidAar` to the resulting AAR. Keep its JSON build record beside
it. `--ndk` selects the NDK directory explicitly; `--cache-dir` selects the input
download/build cache. Downloaded inputs and generated AARs are not included in
the source ZIP or Git repository.

Every download has a pinned URL, exact size and SHA-256 in
`scripts/onnxruntime-android-inputs.json`. The official JNI sources are pinned to
ONNX Runtime commit `c4fb724e810bb496165b9015c77f402727392933` (version 1.20.0).
The official input AAR SHA-256 is
`07a8f71ef890afed8c6087a56220e6d558a492804276ee2dd7cb7f6262242027`.

If an official source URL is unavailable, preparation tries only the bounded
fallback URLs declared beside that input. The ROCm/onnxruntime mirror retains
the exact same pinned commit; all 29 source/license files were independently
checked against the original sizes and SHA-256 values. The official URL remains
first, and every fallback must pass those unchanged checks. No branch tip or
unlisted mirror is accepted. The build record includes the URL which supplied
each input, including downloads reused from a provenance-verified cache. An
unattributed cache entry is downloaded again before it can supply a build.

Preparation verifies source/download hashes, linker resolution, JNI export
parity, ELF class/machine, every load segment's 16 KiB alignment and ZIP CRC. Its
sidecar records the generated AAR and native-library hashes. Gradle independently
checks the pinned source/input-manifest hash, generated AAR hash, exact three ABI
entries and ELF alignment before accepting the dependency. Missing or altered
preparation output fails with instructions to regenerate it. Gradle core-library
desugaring supplies Java APIs such as `Optional` on Android API 23.

The upstream MIT license and complete third-party notices are bundled under
`app/src/main/assets/licenses/ONNXRUNTIME_*`. Runtime/model compatibility and
inference quality still require separate checks; successful packaging does not
establish memory headroom or speed on every phone.
