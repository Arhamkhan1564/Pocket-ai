# Voice preview build identity

Version 1.3.0 uses version code 5. Build the separately installable preview with:

```sh
./gradlew assembleVoicePreviewRelease
```

The preview package is `com.pocketai.voicepreview`, its launcher name is
**Pocket AI Voice Preview**, and `BuildConfig.IS_VOICE_PREVIEW` is true. It uses
release settings and dependencies. The existing debug package remains
`com.pocketai.debug` with its `-debug` version-name suffix and a false preview
flag. Standard release remains `com.pocketai` with a false preview flag.

## Private preview signing

The original release signing key is not included in Git. A new preview key is a
separate identity and cannot update the original installed app. Keep the
original app and its saved documents while evaluating the preview.

Set `POCKETAI_PREVIEW_SIGNING_PROPERTIES` or Gradle property
`-PpocketAiPreviewSigningProperties` to a private properties file. The default is
the sibling file `../private-signing/voice-preview-signing.properties`. Use the
usual `storeFile`, `storeType`, `storePassword`, `keyAlias`, and `keyPassword`
properties; `storeFile` should be an absolute path and `storeType` can be
`PKCS12`. Keep the file and keystore outside the checkout with private filesystem
permissions. Never commit or publish either file. Gradle does not generate a
preview key automatically.

The current preview certificate SHA-256 is:

```text
ab1c8fc2c6dc7f58517337050ec35dc4e236312f4547504e1e999c05ad6f8a3e
```

## Original release signing

For an original-app update, supply the original external key through
`POCKETAI_RELEASE_SIGNING_PROPERTIES` or
`-PpocketAiReleaseSigningProperties`. The legacy root `signing.properties` is
still supported. `validateSigningRelease` first runs
`verifyOriginalReleaseSigning`, which requires the original certificate:

```text
8ac67ade5eaf37abb50dc8d89e32317584f6baa194f4d97246209b27743f4e42
```

A missing key, a certificate-only store, or a different certificate fails rather
than silently signing the original package with the preview identity. The
preview has its own signing configuration and does not use this key.

Before any application build, prepare the checked-in manifest's complete local
model bundle and the verified ONNX Runtime AAR. Gradle validates each declared
file's size and SHA-256, pack IDs and totals. No fixed model file count is used.
