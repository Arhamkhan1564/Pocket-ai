from pathlib import Path
if not __debug__: raise RuntimeError('Checksum and parity checks require Python without -O/-OO.')
import os, json, hashlib, struct, difflib, runpy, sys, re, argparse

HERE = Path(__file__).resolve().parent
parser = argparse.ArgumentParser(description='Convert verified Urdu Whisper Base safetensors with exact local tensor/vocabulary checks; no model inference/network/pickle.')
parser.add_argument('--model-dir', type=Path, required=True)
parser.add_argument('--standard-base-ggml', type=Path, required=True)
parser.add_argument('--output-dir', type=Path, required=True)
parser.add_argument('--project-root', type=Path, default=HERE.parents[1])
args = parser.parse_args()
ROOT = args.output_dir.resolve()
ROOT.mkdir(parents=True, exist_ok=True)
PROJECT = args.project_root.resolve()
MODEL = args.model_dir.resolve()
OUT = ROOT / 'converted'
OUT.mkdir(exist_ok=True)
os.environ.update(HF_HUB_OFFLINE='1', TRANSFORMERS_OFFLINE='1', TOKENIZERS_PARALLELISM='false', OMP_NUM_THREADS='2', MKL_NUM_THREADS='2')
import numpy as np
import torch
from safetensors.torch import load_file
from transformers import WhisperForConditionalGeneration
torch.set_num_threads(2)
torch.set_num_interop_threads(1)

def sha(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024*1024), b''): h.update(block)
    return h.hexdigest()

def prohibit_pickle(*args, **kwargs):
    raise RuntimeError('Pickle/torch.load is prohibited in this safetensors-only conversion.')
torch.load = prohibit_pickle
inputs = json.loads((HERE / 'urdu-base-inputs.json').read_text())
for item in inputs['files']:
    file = MODEL / item['filename']
    assert file.stat().st_size == item['bytes'] and sha(file) == item['sha256'], item['filename']
assert sha(MODEL / 'model.safetensors') == '0819a35b96bc52ba5c2448f67f6beed9ca3e7d0d57bcf913eb21feb096bdfa3f'
assert not list(MODEL.glob('*.bin')) and not list(MODEL.glob('*.py'))
config = json.loads((MODEL / 'config.json').read_text())
assert not config.get('auto_map') and config['architectures'] == ['WhisperForConditionalGeneration']
raw = load_file(MODEL / 'model.safetensors', device='cpu')
assert len(raw) == 245 and all(t.dtype == torch.float32 and torch.isfinite(t).all().item() for t in raw.values())
model, info = WhisperForConditionalGeneration.from_pretrained(
    MODEL, local_files_only=True, use_safetensors=True, trust_remote_code=False,
    torch_dtype=torch.float32, attn_implementation='eager', output_loading_info=True)
for key in ['missing_keys', 'unexpected_keys', 'mismatched_keys', 'error_msgs']:
    assert not info.get(key), (key, info.get(key))
model.eval()
state = model.state_dict()
assert all(name in state and torch.equal(tensor, state[name]) for name, tensor in raw.items())
embed = model.get_input_embeddings().weight
projection = model.get_output_embeddings().weight
assert model.config.tie_word_embeddings and embed.shape == (51865, 512)
assert embed.data_ptr() == projection.data_ptr() and torch.equal(embed, projection)
assert torch.equal(embed, raw['model.decoder.embed_tokens.weight'])
if 'proj_out.weight' in raw:
    assert torch.equal(raw['proj_out.weight'], embed)

# Reuse the exact pinned, verified standard Base model's OpenAI mel coefficients.
# This is a byte-for-byte filter extraction, with no resampling/recalculation.
baseline = args.standard_base_ggml.resolve()
assert sha(baseline) == '422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898'
with baseline.open('rb') as stream:
    header = struct.unpack('<12i', stream.read(48))
    mel_shape = struct.unpack('<2i', stream.read(8))
    assert mel_shape == (80, 201)
    filter_bytes = stream.read(4 * mel_shape[0] * mel_shape[1])
    base_vocab_size = struct.unpack('<i', stream.read(4))[0]
    baseline_tokens = [stream.read(struct.unpack('<i', stream.read(4))[0]) for _ in range(base_vocab_size)]
assets = ROOT / 'openai-mel-assets' / 'whisper' / 'assets'
assets.mkdir(parents=True, exist_ok=True)
np.savez(assets / 'mel_filters.npz', mel_80=np.frombuffer(filter_bytes, dtype='<f4').reshape(mel_shape))

# Keep pinned upstream source and an explicit minimal precision fix. Upstream
# prematurely rounds every tensor to F16 before choosing its stored dtype.
converter = PROJECT / 'vendor/whisper.cpp/models/convert-h5-to-ggml.py'
assert sha(converter) == inputs['converter_source_sha256'], 'Pinned converter changed; re-audit before converting.'
original = converter.read_text()
patched = original.replace('    data = data.astype(np.float16)\n', '', 1)
needle = '            ftype = 0\n    else:\n'
assert needle in patched
patched = patched.replace(needle, '            ftype = 0\n        else:\n            data = data.astype(np.float16)\n    else:\n', 1)
patched_path = ROOT / 'convert-h5-to-ggml.precision-fixed.py'
patched_path.write_text(patched)
(ROOT / 'converter-precision-fix.diff').write_text(''.join(difflib.unified_diff(original.splitlines(True), patched.splitlines(True), fromfile='pinned-upstream-converter', tofile='scratch-precision-fixed-converter')))

# Run the otherwise unchanged converter over this already validated local model.
# The guard prevents a later converter change from initiating any model download.
def validated_local_model(path, *args, **kwargs):
    assert Path(path).resolve() == MODEL.resolve() and not args and not kwargs
    return model
WhisperForConditionalGeneration.from_pretrained = validated_local_model
sys.argv = [str(patched_path), str(MODEL), str(ROOT / 'openai-mel-assets'), str(OUT)]
namespace = runpy.run_path(str(patched_path), run_name='__main__')

# The verified upstream helper supplies the bijective GPT2 byte alphabet; verify
# every serialized token, not just Urdu language/task special IDs.
decode_byte = {v: k for k, v in namespace['bytes_to_unicode']().items()}
vocab = json.loads((MODEL / 'vocab.json').read_text())
assert len(vocab) == 50258 and set(vocab.values()) == set(range(50258))
expected_tokens = [bytes(decode_byte[c] for c in piece) for piece, _ in sorted(vocab.items(), key=lambda item: item[1])]
assert max(map(len, expected_tokens)) <= 128
assert baseline_tokens[:50257] == expected_tokens[:50257]
added = json.loads((MODEL / 'added_tokens.json').read_text())
assert added['<|ur|>'] == 50290 and added['<|transcribe|>'] == 50359 and added['<|notimestamps|>'] == 50363

def converted_name(name):
    name = name.removeprefix('model.')
    match = re.fullmatch(r'(encoder|decoder)\.layers\.(\d+)\.(.*)\.(weight|bias)', name)
    if match:
        block, index, operation, suffix = match.groups()
        mapped = 'attn.key' if operation == 'encoder_attn.k_proj' and block == 'encoder' else 'cross_attn.key' if operation == 'encoder_attn.k_proj' else namespace['conv_map'][operation]
        return f'{block}.blocks.{index}.{mapped}.{suffix}'
    return namespace['conv_map'].get(name, name)

expected = {}
for source_name, tensor in state.items():
    if source_name == 'proj_out.weight': continue
    name = converted_name(source_name)
    array = tensor.detach().cpu().squeeze().numpy()
    if name in ['encoder.conv1.bias', 'encoder.conv2.bias']: array = array.reshape(array.shape[0], 1)
    f32 = array.ndim < 2 or name in ['encoder.conv1.bias', 'encoder.conv2.bias', 'encoder.positional_embedding', 'decoder.positional_embedding']
    expected[name] = array.astype(np.float32 if f32 else np.float16)
binary = OUT / 'ggml-model.bin'
verified = []
with binary.open('rb') as stream:
    converted_header = struct.unpack('<12i', stream.read(48))
    assert converted_header == (0x67676d6c, 51865, 1500, 512, 8, 6, 448, 512, 8, 6, 80, 1)
    assert struct.unpack('<2i', stream.read(8)) == mel_shape and stream.read(len(filter_bytes)) == filter_bytes
    count = struct.unpack('<i', stream.read(4))[0]
    assert count == len(expected_tokens)
    assert [stream.read(struct.unpack('<i', stream.read(4))[0]) for _ in range(count)] == expected_tokens
    while raw_header := stream.read(12):
        dims, name_bytes, ftype = struct.unpack('<3i', raw_header)
        shape = tuple(reversed(struct.unpack('<' + 'i' * dims, stream.read(4 * dims))))
        name = stream.read(name_bytes).decode('utf-8')
        assert name in expected and name not in verified and ftype in [0, 1]
        want = expected[name]
        assert shape == want.shape and ftype == (0 if want.dtype == np.float32 else 1)
        actual = np.frombuffer(stream.read(want.nbytes), dtype=want.dtype).reshape(shape)
        assert np.array_equal(actual, want), name
        verified.append(name)
assert set(verified) == set(expected)
record = {
    'source_weights_sha256': sha(MODEL / 'model.safetensors'), 'raw_f32_tensor_count': len(raw),
    'hf_loaded_state_dict_tensor_count': len(state), 'all_source_tensors_loaded_exactly': True,
    'hf_loading_info': info, 'output_projection_tied_by_config_storage_and_exact_values': True,
    'pickle_loader_prohibited': True, 'remote_code_prohibited': True, 'offline_local_files_only': True,
    'converter_pinned_source_sha256': sha(converter), 'converter_patched_source_sha256': sha(patched_path),
    'converter_patch': 'Delay F16 cast until correct stored-dtype branch. F32 norm/bias/position tensors retain original F32 values.',
    'converter_args': sys.argv, 'torch': torch.__version__, 'numpy': np.__version__,
    'transformers': __import__('transformers').__version__, 'safetensors': __import__('safetensors').__version__,
    'standard_filter_source_model_sha256': sha(baseline), 'mel_coefficients_sha256': hashlib.sha256(filter_bytes).hexdigest(),
    'vocab_normal_token_count': len(expected_tokens), 'all_normal_token_bytes_verified': True,
    'first_50257_token_bytes_equal_canonical_multilingual_base': True,
    'required_special_ids': {key: added[key] for key in ['<|startoftranscript|>', '<|ur|>', '<|transcribe|>', '<|notimestamps|>']},
    'output_header': converted_header, 'output_tensor_count': len(verified), 'all_output_tensors_shapes_dtypes_and_values_verified': True,
    'output_fp16_matrix_rounding_intentional': True, 'output_sha256': sha(binary), 'output_bytes': binary.stat().st_size,
    'production_or_vendor_changed': False
}
(ROOT / 'conversion-verification.json').write_text(json.dumps(record, ensure_ascii=False, indent=2) + '\n')
print('VERIFIED_CONVERSION', record['output_bytes'], record['output_sha256'])
