from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
import json,hashlib,urllib.request,struct,os
ROOT=Path(__file__).resolve().parent
repository=json.loads((ROOT/'metadata/whisper-models.json').read_text())
revision='5359861c739e955e79d9a303bcbc70fb988958b1'
assert repository['sha']==revision and repository['cardData']['license']=='mit'
expected={'tiny':(32152673,'818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7'),'base':(59707625,'422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898'),'small':(190085487,'ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb')}
license_file=Path('/workspace/PocketAI/app/src/main/assets/licenses/WHISPER_MODEL_LICENSE.txt')
(ROOT/'models').mkdir(exist_ok=True)
def sha(path):
 h=hashlib.sha256()
 with path.open('rb') as f:
  while data:=f.read(256*1024):h.update(data)
 return h.hexdigest()
def fetch(kind):
 filename=f'ggml-{kind}-q5_1.bin';entry=next(x for x in repository['siblings'] if x['rfilename']==filename)
 size,digest=expected[kind];assert entry['size']==size and entry['lfs']['sha256']==digest
 url=f'https://huggingface.co/ggerganov/whisper.cpp/resolve/{revision}/{filename}'
 manifest={'candidate':kind+'-q5_1','filename':filename,'repository':'ggerganov/whisper.cpp','revision':revision,'url':url,'size_bytes':size,'sha256':digest,'license':'MIT','copyright':'Copyright (c)2022 OpenAI','license_source':'https://github.com/openai/whisper/blob/main/LICENSE','license_text_sha256':sha(license_file),'metadata_verified_before_download':True,'download_verified':False}
 out=ROOT/f'{kind}-model-manifest.json';out.write_text(json.dumps(manifest,indent=2)+'\n')
 print('METADATA_VERIFIED',kind,size,digest,flush=True)
 dest=ROOT/'models'/filename
 if not (dest.is_file() and dest.stat().st_size==size and sha(dest)==digest):
  tmp=dest.with_suffix('.bin.part')
  try:
   h=hashlib.sha256();count=0
   with urllib.request.urlopen(url,timeout=180) as src,tmp.open('wb') as target:
    while data:=src.read(256*1024):
     count+=len(data)
     if count>size:raise ValueError('Pinned size exceeded')
     target.write(data);h.update(data)
    target.flush();os.fsync(target.fileno())
   assert count==size and h.hexdigest()==digest
   tmp.replace(dest)
  finally:tmp.unlink(missing_ok=True)
 with dest.open('rb') as f:header=struct.unpack('<12I',f.read(48))
 assert header[0]==0x67676d6c and header[1]>=51865
 manifest['download_verified']=True;manifest['header']={'vocabulary':header[1],'audio_context':header[2],'audio_state':header[3],'audio_layers':header[5],'text_layers':header[9],'file_type':header[11],'multilingual':True}
 out.write_text(json.dumps(manifest,indent=2)+'\n');print('DOWNLOAD_VERIFIED',kind,size,digest,flush=True)
 return manifest
with ThreadPoolExecutor(max_workers=3) as pool:models=list(pool.map(fetch,['tiny','base','small']))
(ROOT/'model-manifests.json').write_text(json.dumps({'models':models},indent=2)+'\n')
