"""Fetch only checksum-pinned public model assets, outside the application tree."""
from pathlib import Path
if not __debug__: raise RuntimeError('Checksum and parity checks require Python without -O/-OO.')
import argparse, hashlib, json, os, urllib.request

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output-dir', type=Path, required=True)
parser.add_argument('--skip-standard-base', action='store_true', help='Use an already verified standard Base GGML file for mel coefficients.')
args = parser.parse_args()
inputs = json.loads((Path(__file__).parent / 'urdu-base-inputs.json').read_text())
root = args.output_dir.resolve()
root.mkdir(parents=True, exist_ok=True)

def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''): value.update(block)
    return value.hexdigest()

def fetch(item, directory):
    assert Path(item['filename']).name == item['filename']
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / item['filename']
    if path.is_file() and path.stat().st_size == item['bytes'] and digest(path) == item['sha256']:
        print('Already verified:', path.name); return
    temporary = path.with_suffix(path.suffix + '.part')
    try:
        count = 0; value = hashlib.sha256()
        with urllib.request.urlopen(item['url'], timeout=120) as source, temporary.open('wb') as target:
            for block in iter(lambda: source.read(1024 * 1024), b''):
                count += len(block)
                if count > item['bytes']: raise ValueError('Pinned file size exceeded: ' + path.name)
                value.update(block); target.write(block)
            target.flush(); os.fsync(target.fileno())
        if count != item['bytes'] or value.hexdigest() != item['sha256']:
            raise ValueError('Pinned size/checksum mismatch: ' + path.name)
        temporary.replace(path)
        print('Downloaded and verified:', path.name)
    finally:
        temporary.unlink(missing_ok=True)

for item in inputs['files']: fetch(item, root / 'hf-model')
if not args.skip_standard_base: fetch(inputs['standard_base_mel_source'], root)
