#!/usr/bin/env python3
"""Prepare Pocket AI's pinned offline model assets outside the source project.

Run before Gradle builds. Each file is streamed, size/SHA-256 checked, fsynced and
atomically renamed. Existing final files survive failed fetches. Downloads never
run inside the APK. --offline permits verified local-cache copies only.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import sys
import tempfile
from urllib.parse import unquote, urlparse
import urllib.request


def pinned_model_url(value):
    """Only immutable paths on the declared model hosts; every body is hash checked."""
    url = urlparse(value)
    if url.scheme != "https" or url.query or url.fragment:
        return False
    if url.netloc == "huggingface.co":
        return bool(re.fullmatch(r"/[^/]+/[^/]+/resolve/[a-f0-9]{40}/.+", url.path))
    if url.netloc == "raw.githubusercontent.com":
        return bool(re.fullmatch(r"/snakers4/silero-vad/[a-f0-9]{40}/src/silero_vad/data/silero_vad.onnx", url.path))
    if url.netloc == "media.githubusercontent.com":
        return bool(re.fullmatch(r"/media/Arhamkhan1564/Pocket-ai/[a-f0-9]{40}/model-resources/urdu-whisper-base-q5_1/ggml-urdu-base-q5_1.bin", url.path))
    return False


def load_manifest(path):
    manifest = json.loads(path.read_text(encoding="utf-8"))
    if manifest.get("schemaVersion") != 1:
        raise ValueError("Unsupported offline model manifest version.")
    seen = set()
    total = 0
    for pack in manifest["packs"]:
        pack_total = 0
        for item in pack["files"]:
            asset = item["assetPath"]
            relative = PurePosixPath(asset)
            if relative.is_absolute() or ".." in relative.parts or "\\" in asset or not re.fullmatch(r"[A-Za-z0-9_./-]+", asset):
                raise ValueError(f"Invalid asset path: {asset!r}")
            if asset in seen:
                raise ValueError(f"Duplicate asset path: {asset}")
            seen.add(asset)
            if not isinstance(item["sizeBytes"], int) or item["sizeBytes"] <= 0:
                raise ValueError(f"Invalid model size: {asset}")
            if not re.fullmatch(r"[a-f0-9]{64}", item["sha256"]):
                raise ValueError(f"Invalid model digest: {asset}")
            if not pinned_model_url(item["url"]):
                raise ValueError(f"Model URL must use an allowed immutable HTTPS model revision: {asset}")
            pack_total += item["sizeBytes"]
        if pack_total != pack["totalSizeBytes"]:
            raise ValueError(f"Incorrect pack byte total: {pack['id']}")
        total += pack_total
    if total != manifest["totalSizeBytes"]:
        raise ValueError("Incorrect bundle byte total.")
    return manifest


def valid_file(path, item):
    if not path.is_file() or path.stat().st_size != item["sizeBytes"]:
        return False
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(256 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest() == item["sha256"]


def cache_candidates(cache_roots, item):
    filename = Path(unquote(urlparse(item["url"]).path)).name
    seen = set()
    for root in cache_roots:
        root = root.resolve()
        if root.is_file():
            candidates = [root]
        else:
            direct = [root / item["assetPath"], root / filename,
                      root / "model" / filename, root / "onnx" / filename,
                      root / "model" / "onnx" / filename]
            candidates = iter(direct)
        for candidate in candidates:
            if candidate not in seen:
                seen.add(candidate)
                yield candidate
        if root.is_dir():
            for candidate in root.rglob(filename):
                if candidate not in seen:
                    seen.add(candidate)
                    yield candidate


def write_verified(source, destination, item, staging):
    """Only replace the final file after the complete stream passes integrity checks."""
    staging.mkdir(parents=True, exist_ok=True)
    destination.parent.mkdir(parents=True, exist_ok=True)
    descriptor, name = tempfile.mkstemp(prefix="pocketai-model-", suffix=".part", dir=staging)
    temporary = Path(name)
    try:
        digest = hashlib.sha256()
        copied = 0
        with os.fdopen(descriptor, "wb") as output:
            for chunk in iter(lambda: source.read(256 * 1024), b""):
                copied += len(chunk)
                if copied > item["sizeBytes"]:
                    raise ValueError(f"Response exceeds pinned size: {item['assetPath']}")
                output.write(chunk)
                digest.update(chunk)
            output.flush()
            os.fsync(output.fileno())
        if copied != item["sizeBytes"] or digest.hexdigest() != item["sha256"]:
            raise ValueError(f"Size or SHA-256 mismatch: {item['assetPath']}")
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)


def prepare_file(item, assets, cache_roots, staging, offline):
    destination = (assets / item["assetPath"]).resolve()
    if not destination.is_relative_to(assets):
        raise ValueError(f"Asset path escapes destination: {item['assetPath']}")
    if valid_file(destination, item):
        return f"Verified {item['assetPath']} ({item['sizeBytes']:,} bytes)"
    for candidate in cache_candidates(cache_roots, item):
        if valid_file(candidate, item):
            with candidate.open("rb") as source:
                write_verified(source, destination, item, staging)
            return f"Copied verified cache {item['assetPath']} ({item['sizeBytes']:,} bytes)"
    if offline:
        raise ValueError(f"No verified local copy found for {item['assetPath']}; omit --offline to download the pinned resource.")
    request = urllib.request.Request(item["url"], headers={"User-Agent": "PocketAI-build/1.2"})
    with urllib.request.urlopen(request, timeout=180) as source:
        write_verified(source, destination, item, staging)
    return f"Downloaded and verified {item['assetPath']} ({item['sizeBytes']:,} bytes)"


def main():
    project = Path(__file__).resolve().parent.parent
    manifest_path = project / "app/src/main/assets/offline-model-manifest.json"
    manifest = load_manifest(manifest_path)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets-dir", type=Path,
                        default=Path(os.environ.get("POCKETAI_MODEL_ASSETS_DIR") or project.parent / "model-assets"),
                        help="Same path as Gradle -PpocketAiModelAssetsDir or POCKETAI_MODEL_ASSETS_DIR")
    parser.add_argument("--source-cache", action="append", type=Path, default=[],
                        help="Reuse matching SHA-256 files beneath this directory; may be repeated")
    parser.add_argument("--offline", action="store_true", help="Use existing assets/local caches only; never make a network request")
    parser.add_argument("--check-only", action="store_true", help="Verify resources without copying/downloading")
    parser.add_argument("--packs", nargs="+", choices=[p["id"] for p in manifest["packs"]],
                        help="Prepare only named packs; default is the complete baseline")
    parser.add_argument("--workers", type=int, choices=range(1, 5), default=2)
    args = parser.parse_args()
    assets = args.assets_dir.resolve()
    files = [f for p in manifest["packs"] if not args.packs or p["id"] in args.packs for f in p["files"]]
    if args.check_only:
        bad = [f["assetPath"] for f in files if not valid_file(assets / f["assetPath"], f)]
        if bad:
            raise ValueError("Missing or invalid pinned assets: " + ", ".join(bad))
    else:
        # Partial files live outside the asset tree, so interruption cannot bundle a
        # stale partial download in an APK. Every final file is replaced atomically.
        staging = assets.parent / ".pocketai-model-staging"
        with ThreadPoolExecutor(max_workers=args.workers) as executor:
            jobs = [executor.submit(prepare_file, f, assets, args.source_cache, staging, args.offline) for f in files]
            for job in jobs:
                print(job.result(), flush=True)
    print(f"Ready: {len(files)} pinned files, {sum(f['sizeBytes'] for f in files):,} bytes in {assets}")
    print("Model licenses/provenance are included under app/src/main/assets/. Weights stay outside the source checkout.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, KeyError) as failure:
        print(f"Could not prepare offline model assets: {failure}", file=sys.stderr)
        raise SystemExit(1)
