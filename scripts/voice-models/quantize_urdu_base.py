"""Build the pinned Whisper quantizer, quantize, and require the reference hash."""
from pathlib import Path
if not __debug__: raise RuntimeError('Checksum and parity checks require Python without -O/-OO.')
import argparse, hashlib, json, shutil, subprocess

here = Path(__file__).resolve().parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--project-root', type=Path, default=here.parents[1])
parser.add_argument('--native-build-dir', type=Path, required=True, help='Host build of canonical app/src/main/cpp; build target pocketai first.')
parser.add_argument('--conversion-dir', type=Path, required=True, help='Output directory used with convert_urdu_base.py.')
parser.add_argument('--cxx', default='g++')
args = parser.parse_args()
project = args.project_root.resolve(); build = args.native_build_dir.resolve(); output = args.conversion_dir.resolve()
inputs = json.loads((here / 'urdu-base-inputs.json').read_text())

def sha(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''): value.update(block)
    return value.hexdigest()

sources = [project / 'vendor/whisper.cpp/examples/quantize/quantize.cpp', project / 'vendor/whisper.cpp/examples/common-ggml.cpp']
expected_sources = {'quantize.cpp': 'ab95fe726971332446f6100e18924b181b07b4f0823a2cade685cc8ed9a4b78b', 'common-ggml.cpp': 'ee67639c371f5a52049ffdff91c44e45049029a8dcdfc10bd3193c3bbf82f51f'}
for path in sources:
    if sha(path) != expected_sources[path.name]: raise ValueError('Quantizer source changed; re-audit before use: ' + path.name)
libraries = [build / 'llama-build/ggml/src' / name for name in ['libggml.a', 'libggml-cpu.a', 'libggml-base.a']]
assert all(path.is_file() for path in libraries)
binary = output / 'whisper-quantize'
command = [shutil.which(args.cxx) or args.cxx, '-O2', '-DNDEBUG', '-std=c++17', '-I' + str(project / 'vendor/whisper.cpp/examples'), '-I' + str(project / 'vendor/llama.cpp/ggml/include')]
command += list(map(str, sources + libraries)) + ['-ldl', '-lm', '-pthread', '-o', str(binary)]
subprocess.run(command, check=True)
source = output / 'converted/ggml-model.bin'
assert sha(source) == '0774dbfe80eda83c45ddddcaad3e0e92ce29d07225f11852216e9b950969c94a', 'F16 conversion differs from the recorded reference.'
destination = output / 'converted' / inputs['derived_q5_1']['filename']
quantize = [str(binary), str(source), str(destination), 'q5_1']
subprocess.run(quantize, check=True)
expected = inputs['derived_q5_1']
if destination.stat().st_size != expected['bytes'] or sha(destination) != expected['sha256']:
    raise ValueError('Derived model differs from the recorded Linux x86_64 reference. Retain it for investigation; do not publish under the pinned digest.')
record = {'compile': command, 'quantize': quantize, 'source_sha256': {str(path.relative_to(project)): sha(path) for path in sources}, 'ggml_libraries_sha256': {path.name: sha(path) for path in libraries}, 'quantizer_binary_sha256': sha(binary), 'output_bytes': destination.stat().st_size, 'output_sha256': sha(destination), 'quantization_intentionally_lossy': True}
(output / 'quantization-verification.json').write_text(json.dumps(record, indent=2) + '\n')
print('Reference Q5_1 model reproduced:', destination)
