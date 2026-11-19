#!/usr/bin/env python3
"""Reproduce canonical local JNI requests; no network or model downloads.

This portable wrapper was added after the observations. Archived snapshots record
the original runners. It uses the byte-identical NativeBridge.java harness and
the same basic metric calculations, not a provider recognition API.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time
import unicodedata
import wave


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def normalize(text):
    return " ".join("".join(
        " " if unicodedata.category(char).startswith("P") else char
        for char in unicodedata.normalize("NFKC", text).casefold()
    ).split())


def distance(left, right):
    previous = list(range(len(right) + 1))
    for i, value in enumerate(left, 1):
        current = [i]
        for j, other in enumerate(right, 1):
            current.append(min(current[-1] + 1, previous[j] + 1,
                               previous[j - 1] + (value != other)))
        previous = current
    return previous[-1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--library", type=Path, required=True)
    parser.add_argument("--library-sha256", required=True)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--model-sha256", required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--audio-root", type=Path, required=True)
    parser.add_argument("--fixtures", required=True, help="Comma-separated fixture IDs")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--javac", default="javac")
    parser.add_argument("--timeout-seconds", type=int, default=300)
    args = parser.parse_args()
    if args.output.exists() and any(args.output.iterdir()):
        parser.error("Use a new, empty output directory to preserve prior results.")
    if sha(args.library) != args.library_sha256 or sha(args.model) != args.model_sha256:
        parser.error("Local library/model SHA-256 differs from the declared identity.")
    args.output.mkdir(parents=True, exist_ok=True)
    classes = args.output / "classes"
    classes.mkdir()
    source = Path(__file__).with_name("NativeBridge.java")
    subprocess.run([args.javac, "--release", "17", "-d", str(classes), str(source)], check=True)
    wanted = set(args.fixtures.split(","))
    fixtures = [row for row in json.loads(args.manifest.read_text())["fixtures"]
                if row["fixture"] in wanted]
    if {row["fixture"] for row in fixtures} != wanted:
        parser.error("A requested fixture is absent from the manifest.")
    records = []
    for fixture in fixtures:
        audio = args.audio_root / fixture["pcm16_relative_path"]
        with wave.open(str(audio), "rb") as wav:
            if (wav.getnchannels(), wav.getsampwidth(), wav.getframerate()) != (1, 2, 16000):
                raise ValueError("Expected mono 16 kHz PCM16")
            payload = wav.readframes(wav.getnframes())
        if hashlib.sha256(payload).hexdigest() != fixture["pcm16_sample_sha256"]:
            raise ValueError("Fixture sample-payload hash mismatch")
        out = args.output / (fixture["fixture"] + ".json")
        log = args.output / (fixture["fixture"] + ".log")
        command = [args.java, "-Xms16m", "-Xmx128m", "-cp", str(classes),
                   "com.pocketai.engine.NativeBridge", str(args.library.resolve()),
                   str(args.model.resolve()), fixture["asr_language_code"],
                   str(audio.resolve()), str(out.resolve())]
        began, peak = time.monotonic(), 0
        with log.open("w") as stream:
            environment = dict(os.environ)
            environment.pop("POCKETAI_ASR_EXPERIMENT", None)
            process = subprocess.Popen(command, env=environment, stdout=stream, stderr=subprocess.STDOUT)
            while process.poll() is None:
                try:
                    status = Path("/proc") / str(process.pid) / "status"
                    match = re.search(r"^VmHWM:\s*(\d+) kB", status.read_text(), re.M)
                    if match:
                        peak = max(peak, int(match.group(1)) * 1024)
                except FileNotFoundError:
                    pass
                if time.monotonic() - began > args.timeout_seconds:
                    process.kill()
                    process.wait()
                    raise TimeoutError(f"Timed out: {fixture['fixture']}; see {log}")
                time.sleep(0.025)
        if process.returncode:
            raise RuntimeError(f"JNI request failed ({process.returncode}); see {log}")
        native = json.loads(out.read_text())
        reference = fixture.get("normalized_dataset_reference", fixture["raw_reference"])
        expected, actual = normalize(reference), normalize(native["transcript"])
        records.append({"fixture": fixture["fixture"], "reference": reference,
                        "native_result": native, "wall_seconds": time.monotonic() - began,
                        "sampled_peak_rss_bytes": peak,
                        "basic_wer": distance(expected.split(), actual.split()) / max(1, len(expected.split())),
                        "basic_cer": distance(expected.replace(" ", ""), actual.replace(" ", "")) / max(1, len(expected.replace(" ", ""))),
                        "payload_sha256": fixture["pcm16_sample_sha256"]})
        (args.output / "results.json").write_text(json.dumps({
            "scope": "Host only; basic text edit metrics, no phone/fluency/clinical acceptance.",
            "threads": 2, "profile": "canonical greedy, explicit language, no hints",
            "model_sha256": sha(args.model), "library_sha256": sha(args.library),
            "java_harness_sha256": sha(source), "results": records,
        }, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
