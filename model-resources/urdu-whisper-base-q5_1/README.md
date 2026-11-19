# Urdu Whisper Base Q5_1 resource

This is a locally converted and quantized derivative of
[`kingabzpro/whisper-base-urdu-full`](https://huggingface.co/kingabzpro/whisper-base-urdu-full/tree/6bfaec492d42f4b76f117b2b34cd9672509cecdc).
It was not trained by Pocket AI. `derived-model-manifest.json` records the exact
input and output checksums. Reconstruction scripts are in `scripts/voice-models/`
in the app source.

The file has 59,707,642 bytes and SHA-256
`701f3ae011e0964e45894ad884da1afd5c1a0a7a846b054d9765e75d563ee7b9`.
Git LFS stores its binary body; the app build fetcher verifies the complete body
against this size and checksum.

The model still misrecognizes words and quantities. Used alone it produced
hallucinated transcripts on silence, noise and tone controls. Pocket AI must
check for speech before invoking it and require review of Urdu input. This is
an experimental voice resource without phone, fluent-speaker or medical acceptance.

The fine-tune declares Apache-2.0; underlying OpenAI Whisper is MIT. The included
license files retain both notices. No private signing material or user data is
part of this resource.
