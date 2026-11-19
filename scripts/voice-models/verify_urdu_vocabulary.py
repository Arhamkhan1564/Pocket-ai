from pathlib import Path
if not __debug__: raise RuntimeError('Checksum and parity checks require Python without -O/-OO.')
import json, ctypes, hashlib, argparse

parser = argparse.ArgumentParser(description='Verify all Urdu Whisper vocabulary/merge/special IDs against the exact local Whisper runtime.')
parser.add_argument('--model-dir', type=Path, required=True)
parser.add_argument('--native-library', type=Path, required=True)
parser.add_argument('--output-dir', type=Path, required=True)
args = parser.parse_args()
root = args.output_dir.resolve(); root.mkdir(parents=True, exist_ok=True)
model = args.model_dir.resolve()
library = args.native_library.resolve()
lib = ctypes.CDLL(str(library))
lib.whisper_lang_str.argtypes = [ctypes.c_int]
lib.whisper_lang_str.restype = ctypes.c_char_p
vocab = json.loads((model / 'vocab.json').read_text())
added = json.loads((model / 'added_tokens.json').read_text())
config = json.loads((model / 'tokenizer_config.json').read_text())
assert len(vocab) == 50258 and set(vocab.values()) == set(range(50258))
assert vocab[''] == 50256 and vocab['<|endoftext|>'] == 50257
expected = {'<|startoftranscript|>': 50258}
for i in range(99): expected['<|' + lib.whisper_lang_str(i).decode('ascii') + '|>'] = 50259 + i
for offset, token in enumerate(['translate', 'transcribe', 'startoflm', 'startofprev', 'nocaptions', 'notimestamps']):
    expected['<|' + token + '|>'] = 50358 + offset
for i in range(1501): expected[f'<|{i * 0.02:.2f}|>'] = 50364 + i
assert len(added) == 1607 and added == expected
complete = {**vocab, **added}
assert len(complete) == 51865 and set(complete.values()) == set(range(51865))
for key, item in config['added_tokens_decoder'].items():
    assert complete[item['content']] == int(key)
merges = [line for line in (model / 'merges.txt').read_text().splitlines() if line and not line.startswith('#')]
assert len(merges) == 50000
for rank, merge in enumerate(merges):
    left, right = merge.split()
    assert vocab[left + right] == rank + 256
report = {
    'vocab_contiguous_count': len(vocab), 'added_tokens_contiguous_count': len(added),
    'all_99_language_ids_match_current_whisper_cpp_api': True,
    'all_task_and_1501_timestamp_ids_match_current_loader_synthesis': True,
    'all_tokenizer_added_decoder_entries_match': True, 'all_50000_merge_ranks_match_vocab': True,
    'ur_language_id': added['<|ur|>'], 'required_full_vocabulary': 51865,
    'canonical_jni_sha256': hashlib.sha256(library.read_bytes()).hexdigest(),
    'asset_sha256': {name: hashlib.sha256((model / name).read_bytes()).hexdigest() for name in ['vocab.json', 'added_tokens.json', 'merges.txt', 'tokenizer_config.json']}
}
(root / 'vocabulary-verification.json').write_text(json.dumps(report, indent=2) + '\n')
print('FULL_VOCABULARY_VERIFIED', len(complete))
