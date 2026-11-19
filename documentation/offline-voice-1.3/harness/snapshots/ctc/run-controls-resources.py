from pathlib import Path
import json,subprocess,os,time,re,hashlib,wave
root=Path('/workspace/validation/punjabi-ctc-probe');jvm=root/'jvm';records=[];cp=(jvm/'runtime-classpath.txt').read_text();plan=json.loads((root/'controls-resource-plan.json').read_text())
for f in plan:
 with wave.open(f['path'],'rb') as w:raw=w.readframes(w.getnframes())
 assert hashlib.sha256(raw).hexdigest()==f['pcm16_sample_sha256']
 raw*=f.get('repeat',1)
 if f.get('max_samples'):raw=raw[:2*f['max_samples']]
 source=jvm/(f['id']+'.pcm16le');source.write_bytes(raw);out=jvm/f['id'];out.mkdir(exist_ok=True);began=time.perf_counter();peak=0
 with (out/'run.log').open('w') as log:
  process=subprocess.Popen(['/workspace/toolchains/jdk-21.0.2/bin/java','-Xms16m','-Xmx128m','-Djava.io.tmpdir='+str(jvm),'-cp',cp,'PunjabiCtcHostProbeKt',str(root/'model_int8.onnx'),str(jvm/'vocabulary.txt'),str(source),str(out)],stdout=log,stderr=subprocess.STDOUT,env={**os.environ,'OMP_NUM_THREADS':'2'})
  while process.poll() is None:
   try:
    m=re.search(r'^VmRSS:\s+(\d+)\s+kB',Path(f'/proc/{process.pid}/status').read_text(),re.M)
    if m:peak=max(peak,int(m[1])*1024)
   except FileNotFoundError:pass
   if time.perf_counter()-began>300:process.kill();process.wait();break
   time.sleep(.01)
 assert process.returncode==0,(f['id'],(out/'run.log').read_text()[-3000:])
 hyp=(out/'transcript.txt').read_text();record={'id':f['id'],'purpose':f.get('purpose','Synthetic non-speech control'),'text':hyp,'seconds':len(raw)/32000,'wall_seconds':time.perf_counter()-began,'peak_rss_bytes_sampled':peak,'stats':(out/'stats.txt').read_text(),'frame_id_sha256':hashlib.sha256((out/'frames.i32le').read_bytes()).hexdigest()}
 if f.get('expected_transcript')=='':record['non_speech_false_positive']=hyp!=''
 records.append(record);print(json.dumps(record,ensure_ascii=False),flush=True)
(jvm/'controls-resource-results.json').write_text(json.dumps({'runtime':'Canonical production Kotlin withDouble-statFloat32normalization, hostORT1.20.0,2threads, noarena/memorypattern','production_source_sha256':hashlib.sha256(Path('/workspace/PocketAI/app/src/main/java/com/pocketai/voice/PunjabiCtcKernel.kt').read_bytes()).hexdigest(),'records':records,'limits':['Synthetic controls and repeated audio resources only; not phone/microphone/clinical acceptance.','PeakRSS includes JVM and session initialization; hostCPUwallsecondsinclude model load.']},ensure_ascii=False,indent=2)+'\n')
