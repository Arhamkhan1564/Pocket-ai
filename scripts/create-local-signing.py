#!/usr/bin/env python3
"""Create a private local signing identity for a development release APK.

Keep signing/ and signing.properties safe and private. The same identity is
required for updates. This script never prints its generated password.
"""
import os
from pathlib import Path
import secrets
import shutil
import subprocess

root = Path(__file__).resolve().parents[1]
keytool = shutil.which("keytool")
if not keytool:
    raise SystemExit("Install a JDK and make keytool available on PATH.")
if (root / "signing.properties").exists():
    raise SystemExit("signing.properties already exists; keeping the existing identity.")
signing = root / "signing"
signing.mkdir(mode=0o700, exist_ok=True)
password = secrets.token_urlsafe(32)
password_file = signing / ".temporary-password"
password_file.write_text(password, encoding="utf-8")
os.chmod(password_file, 0o600)
try:
    subprocess.run([
        keytool, "-genkeypair", "-keystore", str(signing / "pocketai-local-release.jks"),
        "-storepass:file", str(password_file), "-keypass:file", str(password_file),
        "-alias", "pocketai", "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000",
        "-dname", "CN=Pocket AI Local Build, O=Pocket AI, C=US",
    ], check=True)
    properties = root / "signing.properties"
    properties.write_text(
        "storeFile=signing/pocketai-local-release.jks\n"
        f"storePassword={password}\nkeyAlias=pocketai\nkeyPassword={password}\n",
        encoding="utf-8",
    )
    os.chmod(properties, 0o600)
finally:
    password_file.unlink(missing_ok=True)
print("Created local signing identity. Keep signing/ and signing.properties private.")
