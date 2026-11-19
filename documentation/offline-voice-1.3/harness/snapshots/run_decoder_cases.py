from pathlib import Path
import argparse,subprocess,json,hashlib,os,time,re,unicodedata,wave
p=argparse.ArgumentParser();p.add_argument('--model',type=Path,required=True);p.add_argument('--model-sha',required=True);p.add_argument('--profile',choices=['baseline','beam3','beam5','hint','beam3_hint','beam5_hint','script_hint','beam3_script_hint'],required=True);p.add_argument('--library',type=Path,default=Path('/workspace/validation/offline-voice-decoder/experimental/libpocketai_asr_experimental.so'));p.add_argument('--fixture-manifest',type=Path,default=Path('/workspace/validation/offline-voice-improve-v12/restored-fixtures.json'));p.add_argument('--fixture-root',type=Path,default=Path('/workspace/validation/offline-voice-improve-v12'));p.add_argument('--fixtures',default='');p.add_argument('--controls',default='');p.add_argument('--languages',default='ur,pa');p.add_argument('--output',type=Path,required=True);p.add_argument('--shared-host',action='store_true');p.add_argument('--timeout',type=int,default=240);a=p.parse_args()
base=Path(__file__).resolve().parent;a.output.mkdir(parents=True,exist_ok=True)
def sha(path):
 h=hashlib.sha256()
 with Path(path).open('rb') as stream:
  for block in iter(lambda:stream.read(1024*1024),b''):h.update(block)
 return h.hexdigest()
assert sha(a.model)==a.model_sha
langs=a.languages.split(',');cases=[]
if a.fixtures:
 wanted=set(a.fixtures.split(','))
 for f in json.loads(a.fixture_manifest.read_text())['fixtures']:
  if f['fixture'] in wanted:
   path=a.fixture_root/f['pcm16_relative_path']
   with wave.open(str(path),'rb') as wav:payload=wav.readframes(wav.getnframes())
   assert hashlib.sha256(payload).hexdigest()==f['pcm16_sample_sha256']
   cases.append({'id':f['fixture'],'language':f['asr_language_code'],'wave':str(path),'reference':f['normalized_dataset_reference'],'expected_speech':True,'payload_sha256':hashlib.sha256(payload).hexdigest()})
 assert {x['id'] for x in cases}==wanted
if a.controls:
 wanted=set(a.controls.split(','));manifest=json.loads((base/'control-manifest.json').read_text())
 for c in manifest['controls']:
  if c['id'] in wanted:
   path=base/c['wav_file'];assert sha(path)==c['wav_sha256']
   for lang in langs:cases.append({'id':c['id'],'language':lang,'wave':str(path),'reference':'','expected_speech':False,'energy_gate':c['expected_current_energy_gate'],'payload_sha256':c['pcm16_payload_sha256']})
assert cases

def norm(s):return ' '.join(''.join(' ' if unicodedata.category(c).startswith('P') else c for c in unicodedata.normalize('NFKC',s).casefold()).split())
def edit(a,b):
 previous=list(range(len(b)+1))
 for i,x in enumerate(a,1):
  current=[i]
  for j,y in enumerate(b,1):current.append(min(current[-1]+1,previous[j]+1,previous[j-1]+(x!=y)))
  previous=current
 return previous[-1]
def scripts(s):
 d={}
 for c in s:
  if not c.isalpha():continue
  k='Gurmukhi' if '\u0a00'<=c<='\u0a7f' else 'Arabic' if any(lo<=ord(c)<=hi for lo,hi in [(0x0600,0x06ff),(0x0750,0x077f),(0x08a0,0x08ff)]) else 'Devanagari' if '\u0900'<=c<='\u097f' else 'Latin' if 'LATIN' in unicodedata.name(c,'') else 'Other'
  d[k]=d.get(k,0)+1
 return d
def digits(s):return re.findall(r'\d+(?:[.,]\d+)*',''.join(str(unicodedata.decimal(c)) if c.isdecimal() else c for c in s))
results=[]
for c in cases:
 name=a.profile+'-'+c['language']+'-'+c['id'];out=a.output/(name+'.json');log=a.output/(name+'.log');out.unlink(missing_ok=True)
 command=['/workspace/toolchains/jdk-21.0.2/bin/java','-Xms16m','-Xmx128m','-cp',str(base/'classes'),'com.pocketai.engine.NativeBridge',str(a.library.resolve()),str(a.model.resolve()),c['language'],c['wave'],str(out)]
 env=os.environ.copy();env['POCKETAI_ASR_EXPERIMENT']=a.profile
 began=time.monotonic();peak=0;timedout=False
 with log.open('w') as stream:
  proc=subprocess.Popen(command,env=env,stdout=stream,stderr=subprocess.STDOUT)
  while proc.poll() is None:
   try:
    status=Path('/proc')/str(proc.pid)/'status';text=status.read_text();m=re.search(r'^VmHWM:\s*(\d+) kB',text,re.M)
    if m:peak=max(peak,int(m.group(1))*1024)
   except FileNotFoundError:pass
   if time.monotonic()-began>a.timeout:timedout=True;proc.kill();proc.wait();break
   time.sleep(.025)
 elapsed=time.monotonic()-began;raw=json.loads(out.read_text()) if out.exists() else {};transcript=raw.get('transcript','');logtext=log.read_text()
 diagnostics=[]
 for line in logtext.splitlines():
  if line.startswith('POCKETAI_ASR_SEGMENT '):diagnostics.append(json.loads(line.split(' ',1)[1]))
 ref=norm(c['reference']);hyp=norm(transcript);hint=('ਇਹ ਪੰਜਾਬੀ ਗੁਰਮੁਖੀ ਲਿਪੀ ਵਿੱਚ ਲਿਖੀ ਹੈ।' if 'script_hint' in a.profile else 'ਪੰਜਾਬੀ') if c['language']=='pa' else 'اردو' if c['language']=='ur' else ''
 hint=hint if 'hint' in a.profile else ''
 marker=transcript.strip().casefold() in {'[blank_audio]','[silence]','(silence)','[music]','(music)'}
 row={**c,'profile':a.profile,'transcript':transcript,'native_result':raw,'exit_code':proc.returncode,'timeout':timedout,'wall_seconds':elapsed,'peak_child_rss_bytes':peak,'scripts':scripts(transcript),'reference_scripts':scripts(c['reference']),'segment_diagnostics':diagnostics,'basic_wer':edit(ref.split(),hyp.split())/max(1,len(ref.split())) if c['expected_speech'] else None,'basic_cer':edit(list(ref.replace(' ','')),list(hyp.replace(' ','')))/max(1,len(ref.replace(' ',''))) if c['expected_speech'] else None,'reference_decimal_literals':digits(c['reference']),'hypothesis_decimal_literals':digits(transcript),'whole_prompt_output_absent_from_reference':bool(hint and norm(hint) in hyp and norm(hint) not in ref),'unexpected_hint_word':any(w in hyp.split() and w not in ref.split() for w in (['ਪੰਜਾਬੀ','ਗੁਰਮੁਖੀ','ਲਿਪੀ'] if c['language']=='pa' else ['اردو'] if c['language']=='ur' else [])) if hint else False,'nonspeech_lexical_output':not c['expected_speech'] and not marker and any(ch.isalnum() for ch in transcript),'no_speech_exception':'No speech was recognized' in logtext,'log_tail':'\n'.join(logtext.splitlines()[-6:]) if proc.returncode else None}
 if not c['expected_speech']:
  row['control_outcome']='timeout' if timedout else 'expected_no_speech_exception' if row['no_speech_exception'] else 'execution_error' if proc.returncode!=0 else 'marker_only' if marker else 'lexical_hallucination' if row['nonspeech_lexical_output'] else 'successful_empty_output' if not transcript.strip() else 'nonlexical_output'
 results.append(row);print(json.dumps({k:row[k] for k in ['id','language','profile','transcript','wall_seconds','peak_child_rss_bytes','basic_wer','scripts','unexpected_hint_word','nonspeech_lexical_output','no_speech_exception','exit_code']+(['control_outcome'] if 'control_outcome' in row else [])},ensure_ascii=False),flush=True)
 report={'model_sha256':a.model_sha,'model_bytes':a.model.stat().st_size,'library_sha256':sha(a.library),'profile':a.profile,'hints':({'pa':'ਇਹ ਪੰਜਾਬੀ ਗੁਰਮੁਖੀ ਲਿਪੀ ਵਿੱਚ ਲਿਖੀ ਹੈ।'} if 'script_hint' in a.profile else {'pa':'ਪੰਜਾਬੀ','ur':'اردو'}) if 'hint' in a.profile else {},'threads':2,'shared_host_timings':a.shared_host,'settings':'Current canonical decoder/callbacks except selected search/initial-prompt profile. Explicit pa/ur/en, translate=false, no_context=true, carry_initial_prompt=false, best_of1. Existing fallback temperatures and thresholds unchanged.','metrics':'Basic NFKC/casefold/punctuation-space WER/CER only; no clinical/fluent-speaker or phone accuracy validation. Pa reference is Gurmukhi, so cross-script word error is not a fair linguistic accuracy score; scripts/transcripts are also explicit.','results':results}
 (a.output/'results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
