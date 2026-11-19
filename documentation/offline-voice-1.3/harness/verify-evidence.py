#!/usr/bin/env python3
"""Verify the small evidence archive; no network, native model or dependencies."""
import hashlib
import json
from pathlib import Path


def main():
    root = Path(__file__).resolve().parent.parent
    manifest = json.loads((root / "index-manifest.json").read_text())
    assert manifest["scope"] == "host-only"
    assert sum(item["bytes"] for item in manifest["files"]) == manifest["total_bytes"]
    expected = set()
    for item in manifest["files"]:
        relative = Path(item["path"])
        assert not relative.is_absolute() and ".." not in relative.parts
        assert str(relative) not in expected
        expected.add(str(relative))
        path = root / relative
        payload = path.read_bytes()
        assert len(payload) == item["bytes"], f"Size mismatch: {relative}"
        assert hashlib.sha256(payload).hexdigest() == item["sha256"], f"SHA mismatch: {relative}"
        if relative.suffix == ".json":
            json.loads(payload)
    actual = {str(path.relative_to(root)) for path in root.rglob("*") if path.is_file()}
    assert actual == expected | {"index-manifest.json"}, "Extra or missing archive files"
    print(f"PASS: {len(expected)} text/source artifacts, {manifest['total_bytes']} bytes")


if __name__ == "__main__":
    main()
