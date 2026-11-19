from pathlib import Path
import json,hashlib,urllib.request,urllib.error,wave,subprocess,datetime,sys
from urllib.parse import urlsplit,urlunsplit,urlencode
ROOT=Path(__file__).resolve().parent
PUBLISHED=Path('/workspace/PocketAI/documentation/offline-voice-1.2.1/fixtures.json')
manifest=json.loads(PUBLISHED.read_text())
metadata=json.loads((ROOT/'metadata/fleurs.json').read_text())
assert metadata['sha']==manifest['revision']=='70bb2e84b976b7e960aa89f1c648e09c59f894dd'
assert 'cc-by-4.0' in metadata['cardData']['license']
(ROOT/'fixtures').mkdir(exist_ok=True)
records=[]
metadata_cache={}
def sha(path):
 h=hashlib.sha256()
 with path.open('rb') as f:
  while chunk:=f.read(65536):h.update(chunk)
 return h.hexdigest()
for expected in manifest['fixtures']:
 name=expected['fixture']
 source=ROOT/'fixtures'/f'{name}-source.wav'
 pcm=ROOT/'fixtures'/f'{name}-pcm16.wav'
 try:
  group=(expected['config'],expected['split'])
  if group not in metadata_cache:
   url='https://datasets-server.huggingface.co/rows?'+urlencode({'dataset':'google/fleurs','config':group[0],'split':group[1],'offset':0,'length':2})
   # The batch cached endpoint is reliable here; the individual length=1 query
   # returned a transient502. Curl uses the inherited proxy and verified TLS.
   response=subprocess.run(['curl','--fail','--silent','--show-error','--location','--max-time','45',url],check=True,capture_output=True)
   body=response.stdout
   assert len(body)<=1_000_000,'oversized row metadata'
   metadata_cache[group]=json.loads(body)
  rowdata=metadata_cache[group]
  assert 'rows' in rowdata, str(rowdata.get('error','missing rows'))
  selected=next(x for x in rowdata['rows'] if x['row_idx']==expected['row_index'])
  row=selected['row']
  assert row['id']==expected['utterance_id'], 'utterance ID changed'
  assert row['raw_transcription']==expected['raw_reference'], 'reference text changed'
  assert row['num_samples']==expected['expected_num_samples'], 'sample count changed'
  audio=row['audio'][0] if isinstance(row['audio'],list) else row['audio']
  locator=urlsplit(audio['src'])
  assert locator.scheme=='https' and locator.hostname=='datasets-server.huggingface.co'
  assert manifest['revision'] in locator.path,'audio revision differs'
  if not source.exists() or sha(source)!=expected['source_audio_sha256']:
   temporary=source.with_suffix('.wav.part')
   try:
    with urllib.request.urlopen(audio['src'],timeout=60) as response,temporary.open('wb') as out:
     copied=0
     while chunk:=response.read(65536):
      copied+=len(chunk)
      assert copied<=5*1024*1024,'fixture exceeded download bound'
      out.write(chunk)
    actual_sha=sha(temporary)
    assert temporary.stat().st_size==expected['source_audio_bytes'], 'source WAV size differs'
    assert actual_sha==expected['source_audio_sha256'], f'source WAV hash mismatch actual={actual_sha}'
    temporary.replace(source)
   finally:temporary.unlink(missing_ok=True)
  subprocess.run(['ffmpeg','-nostdin','-hide_banner','-loglevel','error','-y','-i',str(source),'-ac','1','-ar','16000','-c:a','pcm_s16le',str(pcm)],check=True)
  with wave.open(str(pcm),'rb') as wav:
   rate=wav.getframerate();channels=wav.getnchannels();width=wav.getsampwidth();frames=wav.getnframes();payload=wav.readframes(frames)
  assert rate==16000 and channels==1 and width==2 and frames==expected['expected_num_samples']
  payload_sha=hashlib.sha256(payload).hexdigest()
  assert payload_sha==expected['pcm16_sample_sha256'],f'PCM payload hash mismatch actual={payload_sha}'
  actual_container=sha(pcm)
  record=dict(expected)
  record.update({'source_audio_hash_match':True,'source_audio_current_sha256':sha(source),'pcm16_sample_hash_match':True,'pcm16_current_sample_sha256':payload_sha,'pcm16_current_container_sha256':actual_container,'pcm16_container_matches_previous':actual_container==expected['pcm16_sha256'],'pcm16_current_bytes':pcm.stat().st_size,'source_relative_path':str(source.relative_to(ROOT)),'pcm16_relative_path':str(pcm.relative_to(ROOT)),'row_metadata_verified':True,'public_audio_locator_without_signature':urlunsplit((locator.scheme,locator.netloc,locator.path,'',''))})
  records.append(record)
  print(name,'PASS','duration',frames/rate,'source_sha',record['source_audio_current_sha256'],'sample_sha',payload_sha,'container_matches_old',record['pcm16_container_matches_previous'],flush=True)
 except Exception as e:
  failure={'fixture':name,'exception_type':type(e).__name__,'message':str(e) if not isinstance(e,urllib.error.URLError) else 'HTTP operation failed (signed audio URL not logged)','completed_fixtures':len(records)}
  (ROOT/'restore-failure.json').write_text(json.dumps(failure,ensure_ascii=False,indent=2)+'\n')
  print('RESTORE FAILED',json.dumps(failure,ensure_ascii=False),flush=True)
  raise
report={'dataset':manifest['dataset'],'revision':manifest['revision'],'license':manifest['license'],'restored_at_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'published_manifest_sha256':hashlib.sha256(PUBLISHED.read_bytes()).hexdigest(),'fixture_count':len(records),'source_audio_hashes_match_published':all(x['source_audio_hash_match'] for x in records),'pcm_sample_hashes_match_published':all(x['pcm16_sample_hash_match'] for x in records),'fixtures':records,'notes':['No inference executed; no production source edits.','Full WAV container bytes may differ across FFmpeg versions; exact decoded PCM16 sample payloads must match the published prior experiment.','Signed audio URL query strings are not saved or logged.','Raw audio remains scratch-only and must not be published.']}
(ROOT/'restored-fixtures.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print('COMPLETE',len(records),'source bytes',sum(x['source_audio_bytes'] for x in records),'PCM bytes',sum(x['pcm16_current_bytes'] for x in records),flush=True)
