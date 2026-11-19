from pathlib import Path
import argparse,json,time,hashlib,unicodedata,wave,platform,subprocess,os,resource
ROOT=Path(__file__).resolve().parent
parser=argparse.ArgumentParser();parser.add_argument('--child',type=int);args=parser.parse_args()
FIXTURES=Path('/workspace/validation/offline-voice-improve-v12/fixtures')
manifest=json.loads((ROOT/'manifest.json').read_text())
fixtures=[f for f in json.loads(Path('/workspace/PocketAI/documentation/offline-voice-1.2.1/fixtures.json').read_text())['fixtures'] if f['asr_language_code']=='pa']
def normal(text):
 text=unicodedata.normalize('NFKC',text).casefold()
 return ' '.join(''.join(' ' if unicodedata.category(c).startswith('P') else c for c in text).split())
def distance(a,b):
 p=list(range(len(b)+1))
 for i,x in enumerate(a,1):
  q=[i]
  for j,y in enumerate(b,1):q.append(min(q[-1]+1,p[j]+1,p[j-1]+(x!=y)))
  p=q
 return p[-1]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def scripts(text):
 out={}
 for c in text:
  if not c.isalpha():continue
  if '\u0a00'<=c<='\u0a7f':kind='Gurmukhi'
  elif 'LATIN' in unicodedata.name(c,''):kind='Latin'
  else:kind='Other'
  out[kind]=out.get(kind,0)+1
 return out
if args.child is not None:
 import numpy as np
 import onnxruntime as ort
 began=time.perf_counter();f=fixtures[args.child];path=FIXTURES/(f['fixture']+'-pcm16.wav')
 assert sha(path)==f['pcm16_sha256']
 with wave.open(str(path),'rb') as w:
  assert (w.getframerate(),w.getnchannels(),w.getsampwidth())==(16000,1,2)
  samples=w.readframes(w.getnframes())
 assert hashlib.sha256(samples).hexdigest()==f['pcm16_sample_sha256']
 assert sha(ROOT/'model_int8.onnx')==manifest['sha256']
 options=ort.SessionOptions();options.intra_op_num_threads=2;options.inter_op_num_threads=1;options.execution_mode=ort.ExecutionMode.ORT_SEQUENTIAL
 load=time.perf_counter();sess=ort.InferenceSession(str(ROOT/'model_int8.onnx'),sess_options=options,providers=['CPUExecutionProvider']);load=time.perf_counter()-load
 audio=np.frombuffer(samples,dtype='<i2').astype(np.float32)/np.float32(32768.0)
 # Transformers Wav2Vec2FeatureExtractor zero_mean_unit_var_norm, single unpadded utterance.
 audio=(audio-audio.mean())/np.sqrt(audio.var()+np.float32(1e-7));audio=audio[None,:]
 started=time.perf_counter();logits=sess.run(['logits'],{'input_values':audio})[0];inference=time.perf_counter()-started
 token_ids=np.argmax(logits[0],axis=-1).tolist();vocab=json.loads((ROOT/'vocab.json').read_text());labels={int(v):k for k,v in vocab.items()};collapsed=[];prev=None
 for id in token_ids:
  if id!=prev and id!=1:collapsed.append(id)
  prev=id
 raw=''.join(labels[id] for id in collapsed).replace('|',' ').strip()
 ref=normal(f['normalized_dataset_reference']);hyp=normal(raw);rw=ref.split();hw=hyp.split()
 result={**f,'transcript':raw,'inference_seconds':inference,'session_load_seconds':load,'process_seconds':time.perf_counter()-began,'peak_rss_bytes_ru_maxrss':resource.getrusage(resource.RUSAGE_SELF).ru_maxrss*1024,'word_edit_distance':distance(rw,hw),'reference_whitespace_tokens':len(rw),'basic_wer':distance(rw,hw)/max(1,len(rw)),'basic_cer':distance(list(ref.replace(' ','')),list(hyp.replace(' ','')))/max(1,len(ref.replace(' ',''))),'hypothesis_script_letters':scripts(raw),'actual_pcm16_sha256':sha(path),'actual_pcm16_sample_sha256':hashlib.sha256(samples).hexdigest(),'logits_shape':list(logits.shape),'providers':sess.get_providers(),'runtime_version':ort.__version__,'ctc_token_ids':collapsed,'unknown_token_emitted':3 in collapsed,'special_token_emitted':any(id in [0,2] for id in collapsed),'number_observation':{'reference_form':'ਇੱਕ' if args.child==0 else 'ਇਕ','reference_form_present':('ਇੱਕ' if args.child==0 else 'ਇਕ') in raw,'alternate_one_form_present':any(word in raw for word in ['ਇੱਕ','ਇਕ']),'note':'Literal spelling observation only; no number normalization or pronunciation judgement.'}}
 (ROOT/'runs').mkdir(exist_ok=True);(ROOT/'runs'/(f['fixture']+'.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2));print(json.dumps({k:result[k] for k in ['fixture','transcript','inference_seconds','session_load_seconds','peak_rss_bytes_ru_maxrss','basic_wer','basic_cer','hypothesis_script_letters','number_observation']},ensure_ascii=False),flush=True)
else:
 results=[]
 for index,f in enumerate(fixtures):
  started=time.perf_counter();process=subprocess.run([str(ROOT/'.venv/bin/python'),str(Path(__file__)), '--child',str(index)],env={**os.environ,'OPENBLAS_NUM_THREADS':'1','OMP_NUM_THREADS':'2'},timeout=600)
  assert process.returncode==0
  result=json.loads((ROOT/'runs'/(f['fixture']+'.json')).read_text());result['whole_process_wall_seconds']=time.perf_counter()-started;results.append(result)
 report={'runtime':{'model':manifest,'host':platform.machine(),'cpu_quota':Path('/sys/fs/cgroup/cpu.max').read_text().strip(),'threads':2,'inter_op_threads':1,'onnxruntime':'1.20.0','network':'No network in benchmark path; local files only','preprocessing':'Wav2Vec2FeatureExtractor:16k mono PCM16 /32768 → float32 per-utterance(x-mean)/sqrt(var+1e-7), no mask, no padding','decoding':'Raw greedy CTC, blank1, repeatcollapse then |→space; no LM, prompt, correction or translation','normalization':'Basic NFKC/casefold/punctuation-to-space/whitespace-collapse, vowel marks retained; same recipe as Whisper comparator'},'fixtures':results,'limits':['Two exploratory public read-speech examples only, not representative Punjabi acceptance.','Common Voice8 fine-tune and Vakyansh/CLSRIL-23 base provenance; no FLEURS use declared, broader corpus overlap unverified.','This measures Linux x86_64 ORT1.20CPU, not Android ABI/runtime, microphone or target-phone latency/memory.','Runtime peakRSS includes Python/NumPy/ORT and session initialization.','No pronunciation or clinical accuracy review is established.']}
 (ROOT/'results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print('COMPLETE',len(results),flush=True)
