"""Task-authored deterministic non-speech controls. Python standard library only."""
from pathlib import Path
import array, hashlib, json, math, random, shutil, sys, wave
ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'controls'
SOURCE = Path('/workspace/PocketAI/app/src/main/java/com/pocketai/voice/SpeechController.kt')
RATE = 16000
SECONDS = 6
COUNT = RATE * SECONDS
FRAME = RATE // 10

def noise(seed, amplitude):
    generator = random.Random(seed)
    return [generator.uniform(-amplitude, amplitude) for _ in range(COUNT)]

cases = [
    ('silence-6s', [0.0] * COUNT, {'generator': 'digital zero', 'seed': None, 'amplitude': 0.0}),
    ('quiet-noise-6s', noise(2026111701, 0.0015), {'generator': 'Python random.Random MT19937 uniform(-amplitude,+amplitude)', 'seed': 2026111701, 'amplitude': 0.0015}),
    ('higher-noise-6s', noise(2026111702, 0.02), {'generator': 'Python random.Random MT19937 uniform(-amplitude,+amplitude)', 'seed': 2026111702, 'amplitude': 0.02}),
    ('tone-440hz-6s', [0.025 * math.sin(2 * math.pi * 440 * i / RATE) for i in range(COUNT)], {'generator': 'deterministic sine', 'frequency_hz': 440, 'amplitude': 0.025}),
]
results = []
for name, values, generation in cases:
    samples = [max(-32768, min(32767, round(value * 32768))) for value in values]
    pcm = array.array('h', samples)
    if sys.byteorder != 'little':
        pcm.byteswap()
    payload = pcm.tobytes()
    path = OUT / (name + '.wav')
    with wave.open(str(path), 'wb') as audio:
        audio.setnchannels(1)
        audio.setsampwidth(2)
        audio.setframerate(RATE)
        audio.writeframes(payload)
    normalized = [sample / 32768.0 for sample in samples]
    rms = math.sqrt(sum(value * value for value in normalized) / COUNT)
    peak = max(abs(value) for value in normalized)
    frame_rms = [math.sqrt(sum(value * value for value in normalized[offset:offset + FRAME]) / FRAME) for offset in range(0, COUNT, FRAME)]
    active = sum(value * value > 0.000004 for value in frame_rms)
    with wave.open(str(path), 'rb') as check:
        assert (check.getnchannels(), check.getsampwidth(), check.getframerate(), check.getnframes()) == (1, 2, RATE, COUNT)
        assert check.readframes(COUNT) == payload
    results.append({
        'id': name, 'wav_file': 'controls/' + path.name,
        'format': {'container': 'RIFF/WAVE', 'encoding': 'PCM signed16 little-endian', 'sample_rate_hz': RATE, 'channels': 1, 'bits_per_sample': 16, 'samples': COUNT, 'duration_seconds': SECONDS},
        'generation': generation, 'expected_speech': False, 'human_speech_present': False, 'expected_transcript': '',
        'rms_normalized': rms, 'peak_normalized': peak,
        'rms_dbfs': 20 * math.log10(rms) if rms else None,
        'peak_dbfs': 20 * math.log10(peak) if peak else None,
        'dc_mean_normalized': sum(normalized) / COUNT,
        'peak_abs_pcm16': max(abs(value) for value in samples),
        'frame_rms_min': min(frame_rms), 'frame_rms_max': max(frame_rms),
        'energy_gate_frame_count': len(frame_rms), 'energy_gate_active_frame_count': active,
        'expected_current_energy_gate': 'reject' if active == 0 else 'pass despite non-speech',
        'pcm16_payload_bytes': len(payload), 'pcm16_payload_sha256': hashlib.sha256(payload).hexdigest(),
        'wav_bytes': path.stat().st_size, 'wav_sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
    })
assert results[0]['rms_normalized'] == 0
assert results[1]['frame_rms_max'] < 0.002 and results[1]['energy_gate_active_frame_count'] == 0
assert results[2]['frame_rms_min'] > 0.002 and results[2]['energy_gate_active_frame_count'] == 60
assert results[3]['energy_gate_active_frame_count'] == 60
local_tts = {name: shutil.which(name) for name in ('espeak', 'espeak-ng', 'festival', 'flite', 'pico2wave')}
manifest = {
    'purpose': 'Deterministic non-speech controls for baseline-versus-experimental offline speech decoder evaluation. No model inference was performed during fixture generation.',
    'audio_origin': 'New project-authored mathematical/synthetic fixtures. No human recording, downloaded voice, cloud synthesis or external audio was used.',
    'license': 'Project-authored synthetic test fixtures; no third-party audio or voice license applies.',
    'controls': results,
    'current_energy_gate_reference': {
        'source': 'app/src/main/java/com/pocketai/voice/SpeechController.kt',
        'source_sha256': hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
        'sample_rate_hz': RATE, 'frame_samples': FRAME,
        'criterion': 'mean(normalized_pcm16_sample^2)>0.000004 in at least one frame (frame RMS>0.002)',
        'meaning': 'This is an energy gate, not a human-speech detector. Higher noise and tone deliberately pass it while expected_speech remains false.'},
    'optional_english_synthetic_speech': {'generated': False, 'local_executables': local_tts, 'reason': 'Optional local speech synthesis was not used; no voice or audio was downloaded.'},
    'evaluation_limits': [
        'All four fixtures contain no human speech; any words from the decoder are false-positive output or prompt contamination.',
        'Quiet/silence fixtures should also be decoded directly in the external harness, because the production energy gate rejects them before inference.',
        'These synthetic host controls do not validate target-phone microphones, acoustic conditions, Punjabi/Urdu accuracy, pronunciation or clinical use.',
        'Use the same exact audio bytes for every baseline/variant/language comparison.'],
}
path = ROOT / 'control-manifest.json'
path.write_text(json.dumps(manifest, indent=2) + '\n')
for result in results:
    print(result['id'], 'RMS', result['rms_normalized'], 'peak', result['peak_normalized'], 'active_frames', result['energy_gate_active_frame_count'], 'wav_sha256', result['wav_sha256'])
print('manifest_sha256', hashlib.sha256(path.read_bytes()).hexdigest())
print('local_tts', local_tts)
