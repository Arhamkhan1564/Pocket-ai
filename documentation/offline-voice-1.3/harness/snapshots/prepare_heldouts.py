from pathlib import Path
import json,urllib.request,urllib.parse,hashlib,subprocess,wave,datetime
ROOT=Path(__file__).resolve().parent;OUT=ROOT/'heldout';(OUT/'fixtures').mkdir(parents=True,exist_ok=True)
PUBLISHED=Path('/workspace/PocketAI/documentation/offline-voice-1.2.1/fixtures.json')
published=json.loads(PUBLISHED.read_text());revision=published['revision']
with urllib.request.urlopen('https://huggingface.co/api/datasets/google/fleurs',timeout=30)as response:dataset=json.load(response)
assert dataset['sha']==revision and 'cc-by-4.0'in dataset['cardData']['license']
records=[];selection=[]
for config,split,code in [('ur_pk','test','ur'),('pa_in','validation','pa')]:
 original=[x for x in published['fixtures']if x['config']==config]
 excluded_ids={x['utterance_id']for x in original};excluded_refs={x['raw_reference']for x in original}
 query=urllib.parse.urlencode({'dataset':'google/fleurs','config':config,'split':split,'offset':0,'length':10})
 endpoint='https://datasets-server.huggingface.co/rows?'+query
 request=urllib.request.Request(endpoint,headers={'User-Agent':'PocketAI-voice-evaluation/1.2.1'})
 with urllib.request.urlopen(request,timeout=45)as response:payload=json.load(response)
 chosen=[]
 for entry in payload['rows']:
  row=entry['row'];idx=entry['row_idx'];reason=None
  if idx<=1:reason='original row'
  elif row['id']in excluded_ids:reason='duplicate original/chosen utterance ID'
  elif row['raw_transcription']in excluded_refs:reason='duplicate original/chosen reference text'
  elif not(1600<=row['num_samples']<=30*16000):reason='outside predeclared0.1–30second fixture bound'
  if reason:
   selection.append({'config':config,'split':split,'row':idx,'id':row['id'],'selected':False,'reason':reason});continue
  excluded_ids.add(row['id']);excluded_refs.add(row['raw_transcription']);chosen.append((entry,row));selection.append({'config':config,'split':split,'row':idx,'id':row['id'],'selected':True,'reason':'first eligible distinct utterance/reference in row order'})
  if len(chosen)==2:break
 assert len(chosen)==2,(config,'not enough eligible rows')
 for entry,row in chosen:
  index=entry['row_idx'];name=f'fleurs-{config}-{split}-{index}';source=OUT/'fixtures'/(name+'-source.wav');pcm=OUT/'fixtures'/(name+'-pcm16.wav')
  audio=row['audio'][0]if isinstance(row['audio'],list)else row['audio']
  with urllib.request.urlopen(audio['src'],timeout=60)as response,source.open('wb')as target:
   count=0
   while chunk:=response.read(64*1024):
    count+=len(chunk)
    if count>5*1024*1024:raise ValueError('fixture exceeds bounded download')
    target.write(chunk)
  subprocess.run(['ffmpeg','-nostdin','-hide_banner','-loglevel','error','-y','-i',str(source),'-ac','1','-ar','16000','-c:a','pcm_s16le',str(pcm)],check=True)
  with wave.open(str(pcm),'rb')as wav:
   assert wav.getframerate()==16000 and wav.getnchannels()==1 and wav.getsampwidth()==2
   frames=wav.getnframes();samples=wav.readframes(frames)
  assert frames==row['num_samples']
  url=urllib.parse.urlsplit(audio['src']);unsigned=urllib.parse.urlunsplit((url.scheme,url.netloc,url.path,'',''))
  record={'fixture':name,'dataset':'google/fleurs','dataset_revision':revision,'config':config,'split':split,'row_index':index,'utterance_id':row['id'],'asr_language_code':code,'raw_reference':row['raw_transcription'],'normalized_dataset_reference':row['transcription'],'num_samples':frames,'seconds':frames/16000,'sample_rate':16000,'channels':1,'bits_per_sample':16,'source_audio_bytes':source.stat().st_size,'source_audio_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'pcm16_bytes':pcm.stat().st_size,'pcm16_sha256':hashlib.sha256(pcm.read_bytes()).hexdigest(),'pcm16_sample_sha256':hashlib.sha256(samples).hexdigest(),'pcm16_relative_path':str(pcm.relative_to(OUT)),'pcm16_absolute_path':str(pcm),'source_audio_locator_without_ephemeral_signature':unsigned,'license':'CC-BY-4.0','license_url':'https://creativecommons.org/licenses/by/4.0/','attribution':'FLEURS, Conneau et al.,2022; Google dataset google/fleurs','paper':'https://arxiv.org/abs/2205.12446','processing':'FFmpeg mono16kHz PCM16; no denoising or synthetic speech'}
  records.append(record);print(name,'id',row['id'],'seconds',record['seconds'],'sample_sha256',record['pcm16_sample_sha256'],flush=True)
manifest={'dataset':'google/fleurs','revision':revision,'license':'CC-BY-4.0','prepared_at_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'protocol':'First two eligible rows after original pair, excluding original/chosen utterance IDs and exact reference texts, predeclared0.1–30s bound. No ASR outputs used in selection. Held out from this project comparison, not proven absent from model pretraining.','excluded_original_manifest_sha256':hashlib.sha256(PUBLISHED.read_bytes()).hexdigest(),'selection':selection,'fixtures':records,'publication':'Small metadata/text/hash reports only; no raw audio publication.'}
(OUT/'fixtures.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
print('COMPLETE',len(records),'clips',sum(x['source_audio_bytes']+x['pcm16_bytes']for x in records),'bytes',flush=True)
