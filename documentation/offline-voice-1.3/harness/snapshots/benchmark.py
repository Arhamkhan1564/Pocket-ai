from pathlib import Path
import argparse,json,subprocess,time,hashlib,unicodedata,re,platform,wave
ROOT=Path(__file__).resolve().parent
REPO=Path('/workspace/PocketAI')
parser=argparse.ArgumentParser();parser.add_argument('--model',choices=['tiny','base','small'],required=True);parser.add_argument('--library',type=Path,required=True);parser.add_argument('--languages',nargs='+',choices=['en','ur','pa']);parser.add_argument('--fixtures',nargs='+');parser.add_argument('--run-name');args=parser.parse_args()
metadata=json.loads((ROOT/(args.model+'-model-manifest.json')).read_text());assert metadata['download_verified']
MODEL=ROOT/'models'/metadata['filename']
def sha(path):
 h=hashlib.sha256()
 with path.open('rb') as f:
  while data:=f.read(256*1024):h.update(data)
 return h.hexdigest()
assert sha(MODEL)==metadata['sha256']
source=json.loads((REPO/'documentation/offline-voice-1.2.1/fixtures.json').read_text())['fixtures']
fixtures=[f for f in source if (not args.languages or f['asr_language_code']in args.languages)and(not args.fixtures or f['fixture']in args.fixtures)]
assert fixtures
OUT=ROOT/(args.run_name or args.model+'-q5_1');(OUT/'runs').mkdir(parents=True,exist_ok=True)
java=Path('/workspace/toolchains/jdk-21.0.2/bin/java')
def normal(text):
 text=unicodedata.normalize('NFKC',text).casefold()
 return ' '.join(''.join(' 'if unicodedata.category(c).startswith('P')else c for c in text).split())
def distance(a,b):
 p=list(range(len(b)+1))
 for i,x in enumerate(a,1):
  q=[i]
  for j,y in enumerate(b,1):q.append(min(q[-1]+1,p[j]+1,p[j-1]+(x!=y)))
  p=q
 return p[-1]
def scripts(text):
 out={}
 for c in text:
  if not c.isalpha():continue
  if '\u0600'<=c<='\u06ff'or'\u0750'<=c<='\u077f'or'\u08a0'<=c<='\u08ff':kind='Arabic'
  elif'\u0a00'<=c<='\u0a7f':kind='Gurmukhi'
  elif'\u0900'<=c<='\u097f':kind='Devanagari'
  elif'LATIN'in unicodedata.name(c,''):kind='Latin'
  else:kind='Other'
  out[kind]=out.get(kind,0)+1
 return out
def rss(pid):
 try:
  text=Path(f'/proc/{pid}/status').read_text();m=re.search(r'^VmRSS:\s+(\d+)\s+kB',text,re.M);return int(m[1])*1024 if m else 0
 except(FileNotFoundError,ProcessLookupError): return 0
records=[]
for f in fixtures:
 wav=ROOT/f['pcm16_relative_path'];assert wav.exists()
 with wave.open(str(wav),'rb')as audio:
  assert audio.getnchannels()==1 and audio.getframerate()==16000 and audio.getsampwidth()==2
  samples=audio.readframes(audio.getnframes())
 assert hashlib.sha256(samples).hexdigest()==f['pcm16_sample_sha256']
 output=OUT/'runs'/(f['fixture']+'.json');log=OUT/'runs'/(f['fixture']+'.log')
 output.unlink(missing_ok=True)
 began=time.perf_counter();peak=0
 with log.open('w')as stream:
  process=subprocess.Popen([str(java),'-Xms16m','-Xmx128m','-cp',str(ROOT/'classes'),'com.pocketai.engine.NativeBridge',str(args.library.resolve()),str(MODEL),f['asr_language_code'],str(wav),str(output)],stdout=stream,stderr=subprocess.STDOUT)
  while process.poll()is None:
   peak=max(peak,rss(process.pid))
   if time.perf_counter()-began>600:process.kill();process.wait();break
   time.sleep(.02)
 elapsed=time.perf_counter()-began
 raw=json.loads(output.read_text())if output.exists()else{}
 hypothesis=raw.get('transcript','');ref=normal(f['normalized_dataset_reference']);hyp=normal(hypothesis);rw=ref.split();hw=hyp.split()
 result={**f,'transcript':hypothesis,'native_result':raw,'process_exit_code':process.returncode,'process_wall_seconds':elapsed,'peak_child_rss_bytes_sampled':peak,'word_edit_distance':distance(rw,hw),'reference_whitespace_tokens':len(rw),'basic_wer':distance(rw,hw)/max(1,len(rw)),'basic_cer':distance(list(ref.replace(' ','')),list(hyp.replace(' ','')))/max(1,len(ref.replace(' ',''))),'reference_script_letters':scripts(f['normalized_dataset_reference']),'hypothesis_script_letters':scripts(hypothesis),'actual_pcm16_sha256':sha(wav),'actual_pcm16_sample_sha256':hashlib.sha256(samples).hexdigest()}
 if f['fixture']=='fleurs-ur_pk-test-1':result['spoken_number_observation']={'reference_substring':'چھے','hypothesis_contains_exact_reference_substring':'چھے'in hypothesis,'note':'Literal substring check only; absence can reflect spelling or another numeral form. Raw transcript must be reviewed.'}
 if process.returncode!=0:result['error_log_tail']='\n'.join(log.read_text().splitlines()[-8:])
 records.append(result)
 print(json.dumps({'fixture':f['fixture'],'model':args.model,'transcript':hypothesis,'wer':result['basic_wer'],'cer':result['basic_cer'],'seconds':raw.get('elapsed_seconds'),'sampled_rss':peak,'scripts':result['hypothesis_script_letters'],'exit':process.returncode},ensure_ascii=False),flush=True)
 report={'runtime':{'model':metadata,'library_sha256':sha(args.library),'canonical_cpp_sha256':sha(REPO/'app/src/main/cpp/pocketai_jni.cpp'),'harness_sha256':sha(REPO/'tests/offline-voice/com/pocketai/engine/NativeBridge.java'),'host':platform.machine(),'cpu_quota':Path('/sys/fs/cgroup/cpu.max').read_text().strip(),'memory_limit_bytes':Path('/sys/fs/cgroup/memory.max').read_text().strip(),'java':'JDK21.0.2, --release17 harness, Xms16m/Xmx128m','threads':2,'decoding':'Canonical greedy best_of1, explicit en/ur/pa, translate=false,no_context=true,no initial prompt','fixture_payloads_match_published':True,'normalization':'Same basic NFKC/casefold/punctuation-to-space/whitespace-collapse recipe as published tiny report; vowel marks retained','network':'Local audio/model inference, no provider speech API'},'fixtures':records,'limits':['Exploratory public read-speech examples, not representative language or phone acceptance.','Cross-script word/character edit rates are not fluent-speaker accuracy measures.','Fresh compiler/host differs from old tiny report; timings are observational, not model-only controlled comparisons.','No microphone, Android runtime, TTS or screen-off performance is established.']}
 (OUT/'results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print('COMPLETE',len(records),args.model,flush=True)
