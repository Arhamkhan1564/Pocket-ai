#!/usr/bin/env python3
"""External real-model Robolectric acceptance against already-compiled Pocket AI classes.
No Gradle invocation, model download, application source patch, or device connection.
"""
from pathlib import Path
import argparse, hashlib, json, os, shutil, subprocess, sys, time

HERE = Path(__file__).resolve().parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--project-root', type=Path, default=HERE.parents[1])
parser.add_argument('--runtime-classpath-file', type=Path, required=True)
parser.add_argument('--compiler-classpath-file', type=Path)
parser.add_argument('--kotlinc', help='Kotlin2.0.21 executable, alternative to compiler classpath')
parser.add_argument('--host-ort-jar', type=Path, required=True, help='Official desktop ONNX Runtime1.20.0 JAR')
parser.add_argument('--native-library-dir', type=Path, action='append', required=True, help='Directory containing host JNI libraries; may repeat')
parser.add_argument('--framework-jars', type=Path, required=True, help='Flat directory of cached Robolectric instrumented framework JARs')
parser.add_argument('--app-classes-jar', type=Path)
parser.add_argument('--java-home', type=Path)
parser.add_argument('--output-dir', type=Path, required=True, help='External scratch or ignored build directory; never a real app data directory')
parser.add_argument('--network-trace', action='store_true', help='Trace only this external JVM with strace; not Android airplane mode')
parser.add_argument('--compile-only', action='store_true')
args = parser.parse_args()
project, output = args.project_root.resolve(), args.output_dir.resolve()
output.mkdir(parents=True, exist_ok=True)
if output == HERE or HERE in output.parents:
    parser.error('Keep generated outputs outside tests/offline-integration (use an ignored build/scratch directory).')
java_home = args.java_home or (Path(os.environ['JAVA_HOME']) if os.environ.get('JAVA_HOME') else None)
def executable(name):
    path = str(java_home/'bin'/name) if java_home else shutil.which(name)
    if not path or not Path(path).is_file(): parser.error('Provide a JDK21 using --java-home or JAVA_HOME.')
    return path
java, javac = executable('java'), executable('javac')
app_input = args.app_classes_jar or project/'app/build/intermediates/runtime_app_classes_jar/debug/bundleDebugClassesToRuntimeJar/classes.jar'
if not app_input.is_file(): parser.error('Build Debug production/test classes before running this external harness.')
if not list(args.framework_jars.glob('android-all-instrumented-9-robolectric-4913185-2-i7.jar')):
    parser.error('The flat offline framework directory must contain the cached Robolectric SDK28 instrumented JAR.')
app_snapshot = output/'canonical-app-snapshot.jar'
shutil.copy2(app_input, app_snapshot)
runner_classes, classes = output/'runner-classes', output/'integration-classes'
runner_classes.mkdir(exist_ok=True); classes.mkdir(exist_ok=True)
classpath = os.pathsep.join([str(args.host_ort_jar.resolve()), str(app_snapshot), str(runner_classes), str(classes), args.runtime_classpath_file.read_text().strip()])
compile_java = [javac, '--release', '17', '-cp', classpath, '-d', str(runner_classes), str(HERE/'HostNativeRobolectricRunner.java')]
if args.compiler_classpath_file:
    compile_kotlin = [java, '-cp', args.compiler_classpath_file.read_text().strip(), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', classpath, '-d', str(classes), str(HERE/'OfflineRagAcceptanceTest.kt')]
else:
    kotlinc = args.kotlinc or shutil.which('kotlinc')
    if not kotlinc: parser.error('Supply --compiler-classpath-file or a Kotlin2.0.21 --kotlinc executable.')
    compile_kotlin = [kotlinc, '-jvm-target', '17', '-classpath', classpath, '-d', str(classes), str(HERE/'OfflineRagAcceptanceTest.kt')]
commands = {'compile_java': compile_java, 'compile_kotlin': compile_kotlin, 'cwd': str(project/'app')}
with (output/'compile.log').open('w') as log:
    for command in [compile_java, compile_kotlin]:
        subprocess.run(command, cwd=project/'app', stdout=log, stderr=subprocess.STDOUT, check=True)
(output/'commands.local.json').write_text(json.dumps(commands, indent=2)+'\n')
if args.compile_only:
    print('External host acceptance sources compiled. No model session ran.')
    sys.exit(0)
for name in ['retrieval-probe-queries.json', 'embedding-fixtures.json']:
    shutil.copy2(HERE/name, output/name)
for stale in ['integration-results.json', 'integration-failure.txt', 'canonical-retrieval-probe-results.json']:
    (output/stale).unlink(missing_ok=True)
temporary = output/'tmp'; temporary.mkdir(exist_ok=True)
run = [java, '-Xmx1024m', '-Xcheck:jni', '-Djava.library.path='+os.pathsep.join(str(p.resolve()) for p in args.native_library_dir),
       '-Djava.io.tmpdir='+str(temporary), '-Drobolectric.offline=true', '-Drobolectric.dependency.dir='+str(args.framework_jars.resolve()),
       '-Dpocketai.acceptance.outputDir='+str(output), '-Dpocketai.acceptance.embeddingFixtures='+str(output/'embedding-fixtures.json'),
       '-cp', classpath, 'org.junit.runner.JUnitCore', 'com.pocketai.acceptance.OfflineRagAcceptanceTest']
if args.network_trace:
    strace = shutil.which('strace')
    if not strace: parser.error('--network-trace requires strace; no network result may be claimed without it.')
    run = [strace, '-f', '-e', 'trace=network', '-o', str(output/'network.strace'), *run]
commands['run'] = run
(output/'commands.local.json').write_text(json.dumps(commands, indent=2)+'\n')
source_root = project/'app/src/main/java/com/pocketai'
source_paths = ['assistant/AssistantService.kt','assistant/GroundedEvidence.kt','assistant/MedicalGuidance.kt','data/PocketDatabase.kt','engine/NativeBridge.kt','engine/NativeTokenizer.kt','models/OfflineModelAssets.kt','rag/OfflineRagServices.kt','rag/OfflineTranslationService.kt','retrieval/OfflineEmbeddingService.kt','retrieval/OnnxEmbeddingKernel.kt','retrieval/SemanticRetriever.kt','retrieval/QuestionAnchors.kt','translation/IndicTokenizer.kt','translation/IndicTextProcessor.kt','translation/OnnxTranslationKernel.kt','translation/PunjabiShahmukhi.kt']
def digest(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()
record = {'canonical_source_sha256': {name: digest(source_root/name) for name in source_paths}, 'external_test_sha256': digest(HERE/'OfflineRagAcceptanceTest.kt'),
          'canonical_app_snapshot_sha256': digest(app_snapshot), 'host_ort_jar_sha256': digest(args.host_ort_jar), 'model_manifest_sha256': digest(project/'app/src/main/assets/offline-model-manifest.json')}
started = time.monotonic()
with (output/'run.log').open('w') as log:
    process = subprocess.Popen(run, cwd=project/'app', stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
    for line in process.stdout:
        log.write(line); log.flush()
        if not line.startswith('\t') and not line.startswith('WARNING in native method'): print(line, end='', flush=True)
    code = process.wait()
record.update(exit_code=code, elapsed_seconds=time.monotonic()-started)
(output/'run-record.json').write_text(json.dumps(record, indent=2)+'\n')
if args.network_trace:
    subprocess.run([sys.executable, str(HERE/'summarize-network-trace.py'), str(output/'network.strace')], check=True)
sys.exit(code)
