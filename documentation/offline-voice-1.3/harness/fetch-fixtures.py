#!/usr/bin/env python3
"""Fetch only manifest-listed public FLEURS clips; verify pinned bytes/samples."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
from urllib.parse import urlencode, urlsplit
from urllib.request import urlopen
import wave


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text())
    revision = manifest["revision"]
    assert manifest["dataset"] == "google/fleurs" and manifest["license"] == "CC-BY-4.0"
    cache = {}
    for fixture in manifest["fixtures"]:
        key = (fixture["config"], fixture["split"])
        if key not in cache:
            query = urlencode({"dataset": "google/fleurs", "config": key[0],
                               "split": key[1], "offset": 0, "length": 10})
            with urlopen("https://datasets-server.huggingface.co/rows?" + query, timeout=45) as response:
                payload = response.read(1_000_001)
            if len(payload) > 1_000_000:
                raise ValueError("Oversized public row metadata")
            cache[key] = json.loads(payload)["rows"]
        row = next(item["row"] for item in cache[key] if item["row_idx"] == fixture["row_index"])
        assert row["id"] == fixture["utterance_id"]
        assert row["raw_transcription"] == fixture["raw_reference"]
        audio = row["audio"][0] if isinstance(row["audio"], list) else row["audio"]
        address = urlsplit(audio["src"])
        assert address.scheme == "https" and address.hostname == "datasets-server.huggingface.co"
        assert revision in address.path
        relative = Path(fixture["pcm16_relative_path"])
        if relative.is_absolute() or ".." in relative.parts:
            raise ValueError("Fixture must stay inside the external output directory")
        pcm = args.output / relative
        pcm.parent.mkdir(parents=True, exist_ok=True)
        source = pcm.with_name(pcm.name.replace("-pcm16.wav", "-source.wav"))
        temporary = source.with_suffix(".part")
        try:
            if not source.exists() or digest(source) != fixture["source_audio_sha256"]:
                # A fresh short-lived audio URL is used only in memory; never log it.
                with urlopen(audio["src"], timeout=60) as response, temporary.open("wb") as stream:
                    count = 0
                    while chunk := response.read(65536):
                        count += len(chunk)
                        if count > 5 * 1024 * 1024:
                            raise ValueError("Audio exceeds the fixture download bound")
                        stream.write(chunk)
                assert temporary.stat().st_size == fixture["source_audio_bytes"]
                assert digest(temporary) == fixture["source_audio_sha256"]
                temporary.replace(source)
        except Exception:
            raise RuntimeError("Fixture download/verification failed; signed URLs are not logged") from None
        finally:
            temporary.unlink(missing_ok=True)
        subprocess.run(["ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                        "-i", str(source), "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", str(pcm)], check=True)
        with wave.open(str(pcm), "rb") as wav:
            assert (wav.getnchannels(), wav.getsampwidth(), wav.getframerate()) == (1, 2, 16000)
            assert wav.getnframes() == row["num_samples"]
            samples = wav.readframes(wav.getnframes())
        assert hashlib.sha256(samples).hexdigest() == fixture["pcm16_sample_sha256"]
        print(fixture["fixture"], "verified source and PCM sample hashes")


if __name__ == "__main__":
    main()
