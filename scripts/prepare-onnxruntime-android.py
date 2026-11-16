#!/usr/bin/env python3
"""Prepare the API-23/16-KiB ONNX Runtime Android dependency outside Git.

The original 1.20.0 CPU libraries support API 21 and 16-KiB pages, but its JNI
wrapper was linked with 4-KiB alignment. Later official AARs require API 24.
Only the unchanged official JNI sources are rebuilt here; model weights and the
ONNX Runtime CPU implementation are not modified. Every download is pinned by
commit/version, byte count and SHA-256 in onnxruntime-android-inputs.json.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import struct
import subprocess
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent.parent
INPUTS = ROOT / 'scripts/onnxruntime-android-inputs.json'
NAME = 'onnxruntime-android-1.20.0-pocketai16k.aar'
NATIVE_NAMES = ('libonnxruntime.so', 'libonnxruntime4j_jni.so')


def sha(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def elf_info(data: bytes, abi: str) -> dict:
    if data[:6] not in (b'\x7fELF\x01\x01', b'\x7fELF\x02\x01'):
        raise ValueError(f'{abi}: expected a little-endian ELF library')
    bits = data[4]
    machine = struct.unpack_from('<H', data, 18)[0]
    expected = {'armeabi-v7a': (1, 40), 'arm64-v8a': (2, 183), 'x86_64': (2, 62)}[abi]
    if (bits, machine) != expected:
        raise ValueError(f'{abi}: wrong ELF class/machine {(bits, machine)}')
    phoff = struct.unpack_from('<I' if bits == 1 else '<Q', data, 28 if bits == 1 else 32)[0]
    phsize, phnum = struct.unpack_from('<HH', data, 42 if bits == 1 else 54)
    alignments = []
    for index in range(phnum):
        position = phoff + index * phsize
        if struct.unpack_from('<I', data, position)[0] == 1:  # PT_LOAD
            alignment = struct.unpack_from('<I' if bits == 1 else '<Q', data,
                                           position + (28 if bits == 1 else 48))[0]
            if alignment < 16384:
                raise ValueError(f'{abi}: PT_LOAD alignment {alignment} is below 16 KiB')
            alignments.append(alignment)
    if not alignments:
        raise ValueError(f'{abi}: no PT_LOAD segments')
    return dict(elf_class=32 if bits == 1 else 64, machine=machine,
                load_segment_alignments=alignments, bytes=len(data),
                sha256=hashlib.sha256(data).hexdigest())


def verify(aar: Path, inputs: dict) -> dict:
    metadata = json.loads(aar.with_suffix('.json').read_text())
    if metadata['source_commit'] != inputs['source_commit']:
        raise ValueError('ONNX Runtime source revision differs from the pinned manifest')
    if metadata['inputs_manifest_sha256'] != sha(INPUTS):
        raise ValueError('Preparation input manifest changed; prepare the dependency again')
    if metadata['artifact_sha256'] != sha(aar):
        raise ValueError('Prepared AAR checksum differs from its build record')
    with zipfile.ZipFile(aar) as archive:
        if archive.testzip() is not None:
            raise ValueError('Prepared AAR ZIP CRC check failed')
        names = set(archive.namelist())
        allowed = {f'jni/{abi}/{name}' for abi in inputs['abis'] for name in NATIVE_NAMES}
        actual = {name for name in names if name.startswith('jni/') and name.endswith('.so')}
        if actual != allowed:
            raise ValueError(f'Unexpected/missing native libraries: {actual ^ allowed}')
        if b'android:minSdkVersion="21"' not in archive.read('AndroidManifest.xml'):
            raise ValueError('Unexpected upstream Android minimum SDK')
        if b'android.permission.INTERNET' in archive.read('AndroidManifest.xml'):
            raise ValueError('The offline runtime must not declare INTERNET permission')
        for name in sorted(allowed):
            abi = name.split('/')[1]
            actual_info = elf_info(archive.read(name), abi)
            if metadata['libraries'][name] != actual_info:
                raise ValueError(f'Native-library build record mismatch: {name}')
    return metadata


def fetch(entry: dict, cache: Path) -> Path:
    destination = cache / entry['path']
    if destination.is_file() and destination.stat().st_size == entry['bytes'] and sha(destination) == entry['sha256']:
        return destination
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(destination.suffix + '.download')
    print('Downloading', entry['path'], flush=True)
    try:
        with urllib.request.urlopen(entry['url'], timeout=120) as response, temporary.open('wb') as output:
            shutil.copyfileobj(response, output, 128 * 1024)
        if temporary.stat().st_size != entry['bytes'] or sha(temporary) != entry['sha256']:
            raise ValueError(f'Download integrity check failed: {entry["path"]}')
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)
    return destination


def ndk_path(argument: str | None, revision: str) -> Path:
    candidates = [argument, os.environ.get('ANDROID_NDK_HOME'), os.environ.get('ANDROID_NDK_ROOT')]
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if sdk:
        candidates.append(str(Path(sdk) / 'ndk' / revision))
    for candidate in candidates:
        if candidate and (Path(candidate) / 'source.properties').is_file():
            path = Path(candidate).resolve()
            if f'Pkg.Revision = {revision}' not in (path / 'source.properties').read_text():
                raise ValueError(f'Install the pinned Android NDK {revision}; found {path}')
            return path
    raise ValueError(f'Cannot find Android NDK {revision}; set ANDROID_NDK_HOME or use --ndk')


def run(command: list[str], log: Path) -> None:
    result = subprocess.run(command, text=True, capture_output=True)
    log.write_text(result.stdout + result.stderr)
    if result.returncode:
        raise RuntimeError(f'Command failed; inspect {log}\n{result.stderr[-2500:]}')


def exports(nm: Path, library: Path) -> set[str]:
    result = subprocess.run([str(nm), '-D', '--defined-only', str(library)],
                            check=True, text=True, capture_output=True)
    return {line.split()[-1] for line in result.stdout.splitlines()
            if line.split() and line.split()[-1].startswith('Java_')}


def build(aar: Path, cache: Path, ndk: Path, inputs: dict) -> None:
    for entry in inputs['files']:
        fetch(entry, cache)
    prebuilt = {'Linux': 'linux-x86_64', 'Darwin': 'darwin-x86_64', 'Windows': 'windows-x86_64'}[platform.system()]
    compiler_bin = ndk / 'toolchains/llvm/prebuilt' / prebuilt / 'bin'
    suffix = '.cmd' if platform.system() == 'Windows' else ''
    tool_suffix = '.exe' if platform.system() == 'Windows' else ''
    javac = shutil.which('javac')
    if not javac:
        raise ValueError('JDK 17 or newer is required; javac was not found')
    work = cache / 'build'
    java, headers, classes = (work / name for name in ('java', 'headers', 'classes'))
    for directory in (java, headers, classes):
        directory.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(cache / 'downloads/onnxruntime-1.20.0-sources.jar') as source_jar:
        for name in source_jar.namelist():
            if name.endswith('.java'):
                destination = java / name
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(source_jar.read(name))
    run([javac, '--release', '8', '-h', str(headers), '-d', str(classes)] +
        [str(path) for path in sorted(java.rglob('*.java'))], work / 'javac.log')
    source = cache / 'src'
    (source / 'onnxruntime_config.h').write_text('#define ORT_VERSION "1.20.0"\n')
    (source / 'onnxruntime_c_api.h').write_text('#include "onnxruntime/core/session/onnxruntime_c_api.h"\n')
    upstream_aar = cache / 'downloads/onnxruntime-android-1.20.0.aar'
    replacements = {}
    libraries = {}
    with zipfile.ZipFile(upstream_aar) as upstream:
        for abi, target in inputs['abis'].items():
            print('Compiling official JNI wrapper for', abi, flush=True)
            output = work / abi
            output.mkdir(parents=True, exist_ok=True)
            core = output / 'libonnxruntime.so'
            original = output / 'original-jni.so'
            core.write_bytes(upstream.read(f'jni/{abi}/libonnxruntime.so'))
            original.write_bytes(upstream.read(f'jni/{abi}/libonnxruntime4j_jni.so'))
            wrapper = output / 'libonnxruntime4j_jni.so'
            command = [str(compiler_bin / f'{target}{inputs["android_api"]}-clang{suffix}'),
                       '-std=c11', '-O2', '-fPIC', '-fvisibility=hidden', '-shared',
                       '-Wl,-z,max-page-size=16384', '-Wl,--no-undefined',
                       '-Wl,-soname,libonnxruntime4j_jni.so', '-I' + str(source), '-I' + str(headers)]
            command += [str(path) for path in sorted(source.glob('*.c'))]
            command += [str(core), '-lm', '-llog', '-ldl', '-o', str(wrapper)]
            run(command, output / 'compile.log')
            nm = compiler_bin / ('llvm-nm' + tool_suffix)
            if exports(nm, original) != exports(nm, wrapper):
                raise ValueError(f'{abi}: original/rebuilt JNI export sets differ')
            for name in NATIVE_NAMES:
                path = output / name
                archive_name = f'jni/{abi}/{name}'
                libraries[archive_name] = elf_info(path.read_bytes(), abi)
            replacements[f'jni/{abi}/libonnxruntime4j_jni.so'] = wrapper.read_bytes()
        aar.parent.mkdir(parents=True, exist_ok=True)
        temporary = aar.with_suffix('.aar.new')
        with zipfile.ZipFile(temporary, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as prepared:
            for name in sorted(upstream.namelist()):
                if name.endswith('/'):
                    continue
                if name.startswith('jni/') and name.split('/')[1] not in inputs['abis']:
                    continue
                info = zipfile.ZipInfo(name, (2024, 11, 19, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o100644 << 16
                prepared.writestr(info, replacements.get(name, upstream.read(name)))
        temporary.replace(aar)
    metadata = dict(version=inputs['version'], source_commit=inputs['source_commit'],
                    ndk_revision=inputs['ndk_revision'], android_api=inputs['android_api'],
                    inputs_manifest_sha256=sha(INPUTS), artifact_sha256=sha(aar), libraries=libraries,
                    modifications='Unchanged official JNI C sources rebuilt at API 23 with 16-KiB PT_LOAD alignment; unused x86 ABI omitted. Official classes and CPU libraries unchanged.')
    aar.with_suffix('.json').write_text(json.dumps(metadata, indent=2) + '\n')


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--assets-dir', type=Path, default=Path(os.environ.get('POCKETAI_ORT_ASSETS_DIR', ROOT.parent / 'runtime-assets')))
    parser.add_argument('--cache-dir', type=Path)
    parser.add_argument('--ndk')
    parser.add_argument('--check-only', action='store_true')
    arguments = parser.parse_args()
    inputs = json.loads(INPUTS.read_text())
    assets = arguments.assets_dir.resolve()
    aar = assets / NAME
    if not arguments.check_only:
        cache = (arguments.cache_dir or assets / 'ort-1.20.0-preparation').resolve()
        build(aar, cache, ndk_path(arguments.ndk, inputs['ndk_revision']), inputs)
    record = verify(aar, inputs)
    print(json.dumps(dict(artifact=str(aar), bytes=aar.stat().st_size,
                          sha256=record['artifact_sha256'], abis=list(inputs['abis']),
                          api=23, native_alignment='16 KiB verified'), indent=2))


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError, zipfile.BadZipFile) as error:
        raise SystemExit(f'ONNX Runtime preparation failed: {error}')
