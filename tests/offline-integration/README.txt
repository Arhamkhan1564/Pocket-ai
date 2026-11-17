Offline orchestration acceptance — opt-in host test

This runs the canonical AssistantService / OfflineRagServices with actual pinned
SentencePiece, E5 and IndicTrans2 models, native SQLite, and synthetic authenticated
data. It is deliberately outside app/src/test: normal unit tests do not copy or
run hundreds of MiB of models. No production source is patched and no backend,
embedding vector, translation result, or citation is mocked.

The recorded run used Linux x86_64, JDK 21, Kotlin 2.0.21, ONNX Runtime 1.20.0 CPU,
Robolectric 4.14.1 SDK 28, and a host with a 16 GiB memory / 4 CPU quota. The
ActivityManager and StatFs values are Robolectric shadows supplied as 2 GiB total /
1.5 GiB available. The Main dispatcher is a test dispatcher to avoid a paused-main
setup deadlock. These values exercise guards; they do NOT prove operation on a
physical 2 GiB phone. This test does not validate a phone, UI touch flow, microphone,
Bluetooth routing, TTS, language fluency, or clinical accuracy. Answers use the
baseline cited-excerpt mode, not optional SmolLM2 synthesis.

Recorded evidence is in documentation/offline-acceptance-1.2.1/. The 48-question
result means the expected source was present for 14/16 known questions; it is NOT
perfect precision. Two Punjabi reference-number paraphrases abstained. The English
insulin-cold question also returned ibuprofen/paracetamol references beside the
correct insulin source; other paracetamol queries included an overlapping leaflet.
All 32 negative questions returned no sources. See the JSON for exact questions,
all additional sources and the before/after failure reports.

Requirements and setup (choose your own local paths)

1. Prepare the pinned model/runtime assets and build Debug classes/resources using
   the repository's DEVELOPMENT / ONNXRUNTIME-ANDROID instructions. Keep models
   outside source control. Run the ordinary unit task first:

     ./gradlew :app:testDebugUnitTest

2. Capture the already-built unit runtime and Kotlin compiler classpaths using the
   included EXTERNAL init script. This does not change build.gradle or app code:

     ./gradlew --offline --init-script tests/offline-integration/classpaths.init.gradle \
       -Dpocketai.integration.classpathDir=/ABSOLUTE/SCRATCH/classpaths \
       :app:writeOfflineIntegrationClasspaths

   The runtime.txt file is required. Use compiler.txt if generated; otherwise
   provide --kotlinc /path/to/Kotlin 2.0.21/bin/kotlinc. The app working directory
   remains PROJECT/app because AGP's test_config.properties uses relative build/
   assets/resource paths. Do not delete the generated apk-for-local-test resources
   before running this harness; their assets contain the real model bundle.

3. Build BOTH canonical JNI libraries for the Linux HOST, not an Android ABI.
   A JDK, C++17 compiler, CMake 3.22.1+ and Ninja are needed. Set JAVA_HOME to your
   JDK installation (do not substitute an Android libc library):

     cmake -S app/src/main/cpp -B /ABSOLUTE/SCRATCH/native-host -G Ninja \
       -DCMAKE_BUILD_TYPE=Release
     cmake --build /ABSOLUTE/SCRATCH/native-host \
       --target pocketai pocketai_tokenizer -j2

4. Obtain the official desktop CPU onnxruntime-1.20.0.jar from Maven Central
   (com.microsoft.onnxruntime:onnxruntime:1.20.0). The recorded JAR SHA256 is
   c46608681692b3693914defb42fa3119ad7cb6146870581ece247e0ed5793fe8.
   The host JAR goes first on the classpath and includes Linux core/JNI resources.
   Android .so files cannot execute on a glibc Linux host. This is test setup,
   not an application runtime download.

5. Supply a flat directory of ALREADY CACHED Robolectric instrumented framework
   JARs. The recorded SDK 28 filename is
   android-all-instrumented-9-robolectric-4913185-2-i7.jar. Keep other framework
   JARs required by the built resource configuration in the same directory;
   symlinks to your cache are sufficient. Robolectric offline mode is forced,
   so an absent framework fails instead of downloading during the test.

Run from the repository root, using an external or ignored build scratch directory:

  python3 tests/offline-integration/run.py \
    --java-home /PATH/TO/JDK 21 \
    --runtime-classpath-file /ABSOLUTE/SCRATCH/classpaths/runtime.txt \
    --compiler-classpath-file /ABSOLUTE/SCRATCH/classpaths/compiler.txt \
    --host-ort-jar /PATH/TO/onnxruntime-1.20.0.jar \
    --native-library-dir /ABSOLUTE/SCRATCH/native-host \
    --framework-jars /PATH/TO/FLAT/CACHED/FRAMEWORK/JARS \
    --output-dir /ABSOLUTE/SCRATCH/acceptance \
    --network-trace

--native-library-dir may repeat if the two host JNI libraries live in separate
directories. --project-root and --app-classes-jar can override default local
project/build locations. --compile-only verifies harness compilation without
starting a model session. The script never invokes Gradle or downloads files.

The custom runner keeps only JNI bridge objects and ORT in the parent classloader;
canonical Android-dependent services remain inside Robolectric's sandbox. One
fresh JVM and SDK avoid native-library ownership/reinitialization conflicts.
-Xcheck:jni is enabled. The recorded run includes nonfatal upstream Robolectric
native-SQLite initialization warnings in its full local log; the small published
log retains phase/results without duplicating those framework stack traces.

The one test covers: authenticated text-document import; actual asset integrity,
prepare pause/resume; oversized-record skip without starving later indexing;
edit recovery; real 48-case retrieval with cached forward NMT topic verification;
unchanged English/Urdu/Gurmukhi/Shahmukhi answers and typed citations; a medicine
reference; translation cache; database/service reopen; portable backup/restore
and vector rebuild; edit/delete invalidation; active ORT cancellation and a fresh
UNCACHED successful translation afterward. Every failure preserves diagnostics.

Around 683 MiB of private model copies plus JVM/native scratch is needed in addition
to the existing model bundle and built test resources. All model/database copies
use an isolated Robolectric directory under OUTPUT/tmp and are deleted in the
test's finally block. Never use a real user's application data directory. No model
weights, compiled classes/libraries, audio, database files, raw network traces or
machine-local classpath/commands files belong in source distributions.

With --network-trace, strace observes ONLY the external JUnit JVM and its child
threads. The recorded run had zero outbound IP connect/send operations, two failed
local AF_UNIX nscd lookups, and JVM socket capability probes. This is not Android
airplane mode or a physical-device network audit. Without strace, make no network
observation claim. Keep raw traces and commands.local.json in local scratch.

Published harness portability changes are recorded in RECORDED-RUN-PROVENANCE.json:
only host metadata reporting became portable; model operations/questions/assertions
are unchanged from the test identified by the recorded run's source hash.
