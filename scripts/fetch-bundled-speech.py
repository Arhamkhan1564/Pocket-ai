#!/usr/bin/env python3
"""Fetch the pinned MIT-licensed speech model into an external build-assets directory.

Weights stay outside the Android Studio project. No download code is added to the APK.
Run once before building, or use --check-only to verify an existing model.
"""
import argparse
import hashlib
import os
from pathlib import Path
import sys
import urllib.request

MODEL_NAME = "ggml-tiny-q5_1.bin"
MODEL_SIZE = 32_152_673
MODEL_SHA256 = "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7"
MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-tiny-q5_1.bin"


def valid_model(path):
    if not path.is_file() or path.stat().st_size != MODEL_SIZE:
        return False
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest() == MODEL_SHA256


def main():
    project = Path(__file__).resolve().parent.parent
    default_dir = os.environ.get("POCKETAI_MODEL_ASSETS_DIR") or project.parent / "model-assets"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets-dir", type=Path, default=Path(default_dir),
                        help="Same path as Gradle -PpocketAiModelAssetsDir or POCKETAI_MODEL_ASSETS_DIR")
    parser.add_argument("--check-only", action="store_true")
    args = parser.parse_args()
    destination = args.assets_dir.resolve() / "models" / MODEL_NAME
    if valid_model(destination):
        print(f"Verified bundled speech model: {destination}\n{MODEL_SIZE} bytes; SHA-256 {MODEL_SHA256}")
        return 0
    if args.check_only:
        print(f"Missing or invalid bundled speech model: {destination}", file=sys.stderr)
        return 1
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(".bin.part")
    print(f"Downloading the 30.7 MiB multilingual Whisper tiny Q5_1 model to {destination}")
    try:
        request = urllib.request.Request(MODEL_URL, headers={"User-Agent": "PocketAI-build/1.1"})
        with urllib.request.urlopen(request, timeout=120) as source, temporary.open("wb") as output:
            count = 0
            for chunk in iter(lambda: source.read(256 * 1024), b""):
                count += len(chunk)
                if count > MODEL_SIZE:
                    raise ValueError("The model response exceeds the expected size.")
                output.write(chunk)
            output.flush()
            os.fsync(output.fileno())
        if not valid_model(temporary):
            raise ValueError("The downloaded speech model did not match its pinned size and SHA-256.")
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)
    print(f"Verified {MODEL_SIZE} bytes; SHA-256 {MODEL_SHA256}")
    print("Model license: MIT, Copyright (c) 2022 OpenAI. Included license and provenance are in app/src/main/assets/.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError) as error:
        print(f"Could not prepare the bundled speech model: {error}", file=sys.stderr)
        raise SystemExit(1)
