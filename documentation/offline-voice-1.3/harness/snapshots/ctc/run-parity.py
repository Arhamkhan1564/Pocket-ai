from pathlib import Path
import json,subprocess,os,time,re,hashlib
root=Path('/workspace/validation/punjabi-ctc-probe');jvm=root/'jvm';records=[]
plan=json.loads((root/'bounded-plan.json').read_text())+json.loads((root/'heldout-plan.json').read_text());cp=(jvm/'runtime-classpath.txt').read_text()
for f in plan:
 out=jvm/f['id'];out.mkdir(exist_ok=True);began=time.perf_counter();peak=0
 with (out/'run.log').open('w') as log:
  process=subprocess.Popen(['/workspace/toolchains/jdk-21.0.2/bin/java','-Xms16m','-Xmx128m','-Djava.io.tmpdir='+str(jvm),'-cp',cp,'PunjabiCtcHostProbeKt',str(root/'model_int8.onnx'),str(jvm/'vocabulary.txt'),str(jvm/(f['id']+'.pcm16le')),str(out)],stdout=log,stderr=subprocess.STDOUT,env={**os.environ,'OMP_NUM_THREADS':'2'})
  while process.poll() is None:
   try:
    m=re.search(r'^VmRSS:\s+(\d+)\s+kB',Path(f'/proc/{process.pid}/status').read_text(),re.M)
    if m:peak=max(peak,int(m[1])*1024)
   except FileNotFoundError:pass
   if time.perf_counter()-began>300:process.kill();process.wait();break
   time.sleep(.01)
 assert process.returncode==0,(f['id'],(out/'run.log').read_text()[-3000:])
 expected=json.loads((root/'bounded-runs'/(f['id']+'-bounded-noarena.json')).read_text());hyp=(out/'transcript.txt').read_text();framesha=hashlib.sha256((out/'frames.i32le').read_bytes()).hexdigest()
 record={'fixture':f['id'],'text':hyp,'matches_python_text':hyp==expected['transcript'],'matches_python_frame_ids':framesha==expected['frame_id_sha256'],'frame_id_sha256':framesha,'wall_seconds':time.perf_counter()-began,'peak_rss_bytes_sampled':peak,'stats':(out/'stats.txt').read_text()};records.append(record);print(json.dumps(record,ensure_ascii=False),flush=True)
(jvm/'parity-results.json').write_text(json.dumps({'runtime':'Canonical production Kotlin source compiled independently withKotlin1.9.23/JDK21target17, official hostORT1.20.0,2threads, noarena/memorypattern','production_source_sha256':hashlib.sha256(Path('/workspace/PocketAI/app/src/main/java/com/pocketai/voice/PunjabiCtcKernel.kt').read_bytes()).hexdigest(),'host_ort_jar_sha256':hashlib.sha256((jvm/'onnxruntime-1.20.0.jar').read_bytes()).hexdigest(),'fixtures':records},ensure_ascii=False,indent=2)+'\n')
