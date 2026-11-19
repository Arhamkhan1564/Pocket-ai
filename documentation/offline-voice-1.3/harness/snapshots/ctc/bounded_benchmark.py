from pathlib import Path
import argparse,json,time,hashlib,unicodedata,wave,platform,subprocess,os,resource
ROOT=Path(__file__).resolve().parent
parser=argparse.ArgumentParser();parser.add_argument('--child');parser.add_argument('--mode',choices=['full','bounded','bounded-noarena'],default='bounded-noarena');parser.add_argument('--plan',type=Path,default=ROOT/'bounded-plan.json');args=parser.parse_args()
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
def windows(n):
 start=0;out=[]
 while True:
  end=min(start+192000,n);out.append((start,end))
  if end==n:break
  start+=179200
 return out

def read_pcm(path):
 with wave.open(str(path),'rb') as w:
  assert (w.getframerate(),w.getnchannels(),w.getsampwidth())==(16000,1,2)
  return w.readframes(w.getnframes())

if args.child is not None:
 import numpy as np
 import onnxruntime as ort
 plan=json.loads(args.plan.read_text());f=next(x for x in plan if x['id']==args.child);began=time.perf_counter()
 raw=read_pcm(Path(f['path']));assert hashlib.sha256(raw).hexdigest()==f['pcm16_sample_sha256']
 pcm=np.frombuffer(raw,dtype='<i2'); pcm=np.tile(pcm,f.get('repeat',1))
 if f.get('max_samples'):pcm=pcm[:f['max_samples']]
 audio=pcm.astype(np.float32)/np.float32(32768.0);samples=len(audio)
 # Normalize once over the complete utterance, never independently per window.
 audio=(audio-audio.mean())/np.sqrt(audio.var()+np.float32(1e-7))
 options=ort.SessionOptions();options.intra_op_num_threads=2;options.inter_op_num_threads=1;options.execution_mode=ort.ExecutionMode.ORT_SEQUENTIAL
 if args.mode=='bounded-noarena':options.enable_cpu_mem_arena=False;options.enable_mem_pattern=False
 load=time.perf_counter();sess=ort.InferenceSession(str(ROOT/'model_int8.onnx'),sess_options=options,providers=['CPUExecutionProvider']);load=time.perf_counter()-load
 starts=[(0,samples)] if args.mode=='full' else windows(samples)
 ids=[];inference=0.;chunk_records=[]
 for wi,(start,end) in enumerate(starts):
  began_chunk=time.perf_counter();logits=sess.run(['logits'],{'input_values':audio[None,start:end]})[0];elapsed=time.perf_counter()-began_chunk;inference+=elapsed
  assert logits.shape==(1,(end-start-400)//320+1,65),logits.shape
  local=logits[0].argmax(-1).tolist();lower2=0 if wi==0 else starts[wi-1][1]+start;upper2=2*samples if wi==len(starts)-1 else end+starts[wi+1][0]
  kept=[]
  for j,id in enumerate(local):
   center2=2*start+399+640*j
   if lower2<=center2<upper2:
    absolute_frame=start//320+j
    assert absolute_frame==len(ids),(wi,j,absolute_frame,len(ids))
    ids.append(id);kept.append(j)
  chunk_records.append({'start_sample':start,'end_sample':end,'logits_frames':len(local),'kept_frames':len(kept),'kept_local_first':kept[0] if kept else None,'kept_local_last':kept[-1] if kept else None,'inference_seconds':elapsed})
  del logits
 assert len(ids)==(samples-400)//320+1,(samples,len(ids))
 vocab=json.loads((ROOT/'vocab.json').read_text());labels={int(v):k for k,v in vocab.items()};collapsed=[];prev=None
 for id in ids:
  if id!=prev and id!=1:collapsed.append(id)
  prev=id
 raw=''.join(labels[id] for id in collapsed).replace('|',' ').strip()
 result={**f,'mode':args.mode,'transcript':raw,'samples':samples,'seconds':samples/16000,'frame_count':len(ids),'frame_id_sha256':hashlib.sha256(np.array(ids,dtype='<i4').tobytes()).hexdigest(),'ctc_token_ids':collapsed,'inference_seconds':inference,'session_load_seconds':load,'process_seconds':time.perf_counter()-began,'peak_rss_bytes_ru_maxrss':resource.getrusage(resource.RUSAGE_SELF).ru_maxrss*1024,'chunks':chunk_records,'options':{'intra_op':2,'inter_op':1,'execution':'SEQUENTIAL','optimization':'ORT_ENABLE_ALL','enable_cpu_mem_arena':args.mode!='bounded-noarena','enable_mem_pattern':args.mode!='bounded-noarena'},'runtime_version':ort.__version__}
 if 'reference' in f:
  ref=normal(f['reference']);hyp=normal(raw);rw=ref.split();hw=hyp.split();result.update({'word_edit_distance':distance(rw,hw),'reference_whitespace_tokens':len(rw),'basic_wer':distance(rw,hw)/max(1,len(rw)),'basic_cer':distance(list(ref.replace(' ','')),list(hyp.replace(' ','')))/max(1,len(ref.replace(' ','')))})
 if f.get('expected_transcript')=='':result['non_speech_false_positive']=raw!=''
 out=ROOT/'bounded-runs';out.mkdir(exist_ok=True);(out/(f['id']+'-'+args.mode+'.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
 print(json.dumps({k:result[k] for k in ['id','mode','transcript','inference_seconds','session_load_seconds','peak_rss_bytes_ru_maxrss','basic_wer','basic_cer','non_speech_false_positive'] if k in result},ensure_ascii=False),flush=True)
else:
 plan=json.loads(args.plan.read_text());out=ROOT/'bounded-runs';out.mkdir(exist_ok=True);records=[]
 for f in plan:
  for mode in f.get('modes',['full','bounded','bounded-noarena']):
   process=subprocess.run([str(ROOT/'.venv/bin/python'),str(Path(__file__)), '--child',f['id'],'--mode',mode,'--plan',str(args.plan)],env={**os.environ,'OPENBLAS_NUM_THREADS':'1','OMP_NUM_THREADS':'2'},timeout=600)
   assert process.returncode==0,(f['id'],mode)
   records.append(json.loads((out/(f['id']+'-'+mode+'.json')).read_text()))
 report={'runtime':{'model':json.loads((ROOT/'manifest.json').read_text()),'host':platform.machine(),'threads':2,'onnxruntime':'1.20.0','network':'Local audio/model only during inference','window_samples':192000,'overlap_samples':12800,'step_samples':179200,'conv_receptive_field_samples':400,'conv_stride_samples':320,'merge':'Align starts to320samples. Select each frame by absolute center (start+199.5+320j), trimming each overlap at its midpoint, assert contiguous absolute indices, then one global CTCcollapse.','preprocessing':'Full-utterance16kPCM float32 /32768; (x-mean)/sqrt(var+1e-7), normalized once before slicing'},'records':records,'limits':['Exploratory CPU host checks; not Android/2GBphone or clinical acceptance.','Chunking changes bidirectional model context; exact equality with full utterance is not expected above12seconds.','Synthetic concatenation tests resources only; no new speech evidence.','CPU peakRSS includes Python/NumPy/ORT and initialization.']}
 (ROOT/(args.plan.stem+'-results.json')).write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print('COMPLETE',len(records),flush=True)
