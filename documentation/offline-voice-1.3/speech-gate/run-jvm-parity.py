import hashlib
import json
from pathlib import Path
import struct
import subprocess
import wave

base = Path('/workspace/validation/silero-vad-probe')
jvm = base / 'jvm'
reference = json.loads((base / 'results.json').read_text())
cp = (jvm / 'runtime-classpath.txt').read_text()
model = base / 'source/silero_vad.onnx'
records = []
for fixture in reference['results']:
    folder = jvm / fixture['id']
    folder.mkdir(exist_ok=True)
    pcm = folder / 'input.pcm16le'
    with wave.open(fixture['path'], 'rb') as audio:
        assert audio.getnchannels() == 1 and audio.getsampwidth() == 2 and audio.getframerate() == 16000
        payload = audio.readframes(audio.getnframes())
    assert hashlib.sha256(payload).hexdigest() == fixture['payload_sha256']
    pcm.write_bytes(payload)
    command = ['/workspace/toolchains/jdk-21.0.2/bin/java', '-Djava.io.tmpdir=' + str(jvm), '-cp', cp,
               'SpeechPresenceHostProbeKt', str(model), str(pcm), str(folder)]
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=120)
    (folder / 'run.log').write_text(result.stdout)
    assert result.returncode == 0, result.stdout
    actual = json.loads((folder / 'result.json').read_text())
    raw = (folder / 'probabilities.f32le').read_bytes()
    probabilities = struct.unpack('<' + str(len(raw) // 4) + 'f', raw)
    assert len(probabilities) == len(fixture['probabilities'])
    error = max(abs(a - b) for a, b in zip(probabilities, fixture['probabilities']))
    assert error <= 1e-5, (fixture['id'], error)
    assert actual['has_speech'] == fixture['detected_speech'] == fixture['expected_speech']
    records.append({'id': fixture['id'], 'payload_sha256': fixture['payload_sha256'], **actual,
                    'python_has_speech': fixture['detected_speech'], 'probability_max_absolute_error': error,
                    'probability_tolerance': 1e-5, 'passed': True})
    print(fixture['id'], actual['has_speech'], error, flush=True)
output = {
    'implementation': 'Exact production Kotlin SpeechPresenceKernel compiled with Kotlin1.9.23/JDK21 target17, official host ORT1.20.0, CPU1thread; compared with pinned official Silero v6.2.3 Python reference defaults.',
    'production_source_sha256': hashlib.sha256(Path('/workspace/PocketAI/app/src/main/java/com/pocketai/voice/SpeechPresenceKernel.kt').read_bytes()).hexdigest(),
    'model_sha256': hashlib.sha256(model.read_bytes()).hexdigest(),
    'parameters': reference['parameters'],
    'fixtures': records,
    'all_passed': all(record['passed'] for record in records),
    'limits': 'Presence gating only; this does not establish ASR accuracy or real-phone audio/memory/performance acceptance.'
}
(jvm / 'parity-results.json').write_text(json.dumps(output, indent=2) + '\n')
