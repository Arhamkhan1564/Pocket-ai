from pathlib import Path
import numpy as np,json,hashlib,time
import onnxruntime as ort
root=Path('/workspace/validation/punjabi-ctc-probe');p=root/'jvm';options=ort.SessionOptions();options.intra_op_num_threads=2;options.inter_op_num_threads=1;options.execution_mode=ort.ExecutionMode.ORT_SEQUENTIAL;options.enable_cpu_mem_arena=False;options.enable_mem_pattern=False
sess=ort.InferenceSession(str(root/'model_int8.onnx'),sess_options=options,providers=['CPUExecutionProvider']);results=[];vocab=json.loads((root/'vocab.json').read_text());labels={v:k for k,v in vocab.items()}
plan=json.loads((root/'bounded-plan.json').read_text())+json.loads((root/'heldout-plan.json').read_text())
for f in plan:
 id=f['id'];a=np.frombuffer((p/id/'normalized.f32le').read_bytes(),dtype='<f4');started=time.perf_counter();chunks=[];start=0
 while True:
  end=min(start+192000,len(a));chunks.append((start,end))
  if end==len(a):break
  start+=179200
 ids=[]
 for wi,(start,end) in enumerate(chunks):
  logits=sess.run(['logits'],{'input_values':a[None,start:end]})[0];local=logits[0].argmax(-1).tolist();lower2=0 if wi==0 else chunks[wi-1][1]+start;upper2=2*len(a) if wi==len(chunks)-1 else end+chunks[wi+1][0]
  for frame,token in enumerate(local):
   if lower2<=2*start+399+640*frame<upper2:
    assert start//320+frame==len(ids);ids.append(token)
 ids=np.array(ids,dtype='<i4');j=np.frombuffer((p/id/'frames.i32le').read_bytes(),dtype='<i4');tokens=[];prev=-1
 for token in ids.tolist():
  if token!=prev and token!=1:tokens.append(labels[token])
  prev=token
 text=''.join(tokens).replace('|',' ').strip();result={'fixture':id,'input_sha256':hashlib.sha256(a.tobytes()).hexdigest(),'frames':len(ids),'frame_id_sha256':hashlib.sha256(ids.tobytes()).hexdigest(),'different_frame_ids':int(np.sum(ids!=j)),'frame_id_mismatch_indexes':np.flatnonzero(ids!=j).tolist(),'python_using_java_input_text':text,'java_text':(p/id/'transcript.txt').read_text(),'matches_text':text==(p/id/'transcript.txt').read_text(),'inference_seconds':time.perf_counter()-started,'python_ort':ort.__version__};results.append(result);print(json.dumps(result,ensure_ascii=False),flush=True)
(p/'matching-input-results.json').write_text(json.dumps({'method':'PythonORT1.20 uses EXACT Java-normalized Float32 model input bytes, same windows/options/model/CTC merge.','production_source_sha256':hashlib.sha256(Path('/workspace/PocketAI/app/src/main/java/com/pocketai/voice/PunjabiCtcKernel.kt').read_bytes()).hexdigest(),'fixtures':results},ensure_ascii=False,indent=2)+'\n')
