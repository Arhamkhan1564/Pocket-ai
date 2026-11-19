from pathlib import Path
import hashlib, json, importlib.util, time, wave, resource
import numpy as np
import torch
import onnxruntime as ort

root = Path(__file__).resolve().parent
torch.set_num_threads(1)
torch.set_num_interop_threads(1)
source = root / 'source/utils_vad.py'
spec = importlib.util.spec_from_file_location('pinned_official_silero_utils', source)
utils = importlib.util.module_from_spec(spec)
spec.loader.exec_module(utils)
model = utils.OnnxWrapper(str(root / 'source/silero_vad.onnx'), force_onnx_cpu=True)

class Capture:
    def reset_states(self):
        self.probabilities = []
        model.reset_states()
    def __call__(self, chunk, sr):
        result = model(chunk, sr)
        self.probabilities.append(float(result.item()))
        return result

capture = Capture()
audio_root = Path('/workspace/validation/offline-voice-improve-v12')
original = json.loads((audio_root / 'restored-fixtures.json').read_text())['fixtures']
heldout = json.loads((audio_root / 'heldout/fixtures.json').read_text())['fixtures']
cases = []
for base, fixtures in [(audio_root, original), (audio_root / 'heldout', heldout)]:
    for item in fixtures:
        if item['asr_language_code'] in ['en', 'ur']:
            cases.append({'id': item['fixture'], 'path': str(base / item['pcm16_relative_path']), 'expected_speech': True, 'payload_sha256': item['pcm16_sample_sha256']})
controls = Path('/workspace/validation/offline-voice-decoder')
for item in json.loads((controls / 'control-manifest.json').read_text())['controls']:
    if item['id'] in ['silence-6s', 'higher-noise-6s', 'tone-440hz-6s']:
        cases.append({'id': item['id'], 'path': str(controls / item['wav_file']), 'expected_speech': False, 'payload_sha256': item['pcm16_payload_sha256']})
assert len(cases) == 9
results = []
for item in cases:
    with wave.open(item['path']) as stream:
        assert stream.getframerate() == 16000 and stream.getnchannels() == 1 and stream.getsampwidth() == 2
        payload = stream.readframes(stream.getnframes())
    assert hashlib.sha256(payload).hexdigest() == item['payload_sha256']
    pcm = np.frombuffer(payload, dtype='<i2').astype(np.float32) / np.float32(32768.0)
    begin = time.perf_counter()
    # All fixed official defaults, with only the mandatory sample rate explicit.
    segments = utils.get_speech_timestamps(torch.from_numpy(pcm), capture, sampling_rate=16000)
    elapsed = time.perf_counter() - begin
    speech = bool(segments)
    result = {**item, 'audio_samples': len(pcm), 'audio_seconds': len(pcm)/16000, 'detected_speech': speech, 'expected_decision_matches': speech == item['expected_speech'], 'speech_segments_samples': segments, 'padded_speech_seconds': sum(x['end']-x['start'] for x in segments)/16000, 'probabilities': capture.probabilities, 'max_probability': max(capture.probabilities), 'frames_above_0_5': sum(p >= 0.5 for p in capture.probabilities), 'frame_count': len(capture.probabilities), 'wall_seconds': elapsed}
    results.append(result)
    print(json.dumps({k: result[k] for k in ['id', 'detected_speech', 'expected_decision_matches', 'padded_speech_seconds', 'max_probability', 'wall_seconds']}, ensure_ascii=False), flush=True)
    report = {'model': json.loads((root/'model-provenance.json').read_text()), 'implementation': 'Unmodified pinned official utils_vad.py OnnxWrapper + get_speech_timestamps; PCM16 converted exactly to float32/32768; no filtering/normalization or reference-based tuning.', 'parameters': {'sampling_rate': 16000, 'frame_samples': 512, 'context_samples': 64, 'threshold': 0.5, 'negative_threshold': 0.35, 'minimum_speech_duration_ms': 250, 'minimum_silence_duration_ms': 100, 'speech_pad_ms': 30, 'maximum_speech_duration_seconds': 'infinity', 'state_reset_per_recording': True, 'padding_last_frame_to_512': True}, 'runtime': {'onnxruntime': ort.__version__, 'torch': torch.__version__, 'numpy': np.__version__, 'provider': 'CPUExecutionProvider', 'intra_threads': 1, 'inter_threads': 1, 'peak_process_rss_bytes_including_python_torch_ort': resource.getrusage(resource.RUSAGE_SELF).ru_maxrss*1024}, 'results': results, 'all_expected_decisions_match': all(r['expected_decision_matches'] for r in results), 'limits': ['Six public English/Urdu read-speech recordings plus three synthetic non-speech controls only; not target-phone or general acoustic acceptance.', 'Positive VAD means speech detected; it does not validate ASR words, numbers, language or clinical content.', 'Speech segmentation is used only as a presence gate; original audio remains the ASR input.', 'No speaker identification, microphone, Android runtime, TTS or physical2GB device is exercised.']}
    (root/'results.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
del model
assert all(r['expected_decision_matches'] for r in results)
