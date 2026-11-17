package com.pocketai.acceptance

import android.app.ActivityManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pocketai.BuildConfig
import com.pocketai.AppSettings
import com.pocketai.assistant.MedicalGuidance
import com.pocketai.assistant.AssistantService
import com.pocketai.data.KnowledgeDraft
import com.pocketai.data.PocketDatabase
import com.pocketai.engine.LocalAiEngine
import com.pocketai.engine.NativeBridge
import com.pocketai.engine.NativeExecution
import com.pocketai.engine.NativeTokenizer
import com.pocketai.models.ModelManager
import com.pocketai.models.OfflineModelAssets
import com.pocketai.models.OfflinePackStatus
import com.pocketai.rag.OfflineRagServices
import com.pocketai.retrieval.QuestionAnchors
import com.pocketai.retrieval.OfflineEmbeddingService
import com.pocketai.retrieval.EvidenceCoverage
import com.pocketai.retrieval.OnnxEmbeddingKernel
import com.pocketai.security.AuthManager
import java.io.File
import java.util.concurrent.CancellationException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowStatFs

/** Real canonical services, real pinned model files, actual host JNI/ORT; no backend or vectors mocked. */
@RunWith(HostNativeRobolectricRunner::class)
@Config(sdk = [28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineRagAcceptanceTest {
    private val output = File(System.getProperty("pocketai.acceptance.outputDir")).apply { mkdirs() }
    private val phases = JSONArray()
    private val report = JSONObject().put("status", "RUNNING").put("phases", phases)
    private fun writeReport() { File(output, "integration-results.json").writeText(report.toString(2)) }
    private suspend fun phase(name: String, action: suspend () -> JSONObject) {
        val started = System.nanoTime()
        println("PHASE START $name")
        try {
            val evidence = action().put("name", name).put("status", "PASS").put("seconds", (System.nanoTime()-started)/1e9)
            phases.put(evidence); writeReport(); println("PHASE PASS $name")
        } catch (failure: Throwable) {
            phases.put(JSONObject().put("name", name).put("status", "FAIL").put("failure", failure.toString()).put("seconds", (System.nanoTime()-started)/1e9))
            File(output, "integration-failure.txt").writeText(failure.stackTraceToString())
            report.put("status", "FAIL"); writeReport(); throw failure
        }
    }

    @Test fun completeOfflineReferenceFlow() { runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testRoot = File(System.getProperty("java.io.tmpdir")).canonicalFile
        check(context.filesDir.canonicalPath.startsWith(testRoot.path)) { "Acceptance data must remain inside the isolated test directory." }
        var db: PocketDatabase? = null
        var rag: OfflineRagServices? = null
        var local: LocalAiEngine? = null
        try {
            context.deleteDatabase("pocket_ai.db")
            context.getSharedPreferences("pocket_ai_auth", Context.MODE_PRIVATE).edit().clear().commit()
            context.getSharedPreferences("pocket_settings", Context.MODE_PRIVATE).edit().clear().commit()
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            shadowOf(manager).setMemoryInfo(ActivityManager.MemoryInfo().apply {
                totalMem = 2L*1024*1024*1024; availMem = 1536L*1024*1024; lowMemory = false
            })
            for (name in listOf("search", "translation", "english", "speech"))
                ShadowStatFs.registerStats(File(context.filesDir, "offline-models/$name").absolutePath, 524_288, 393_216, 393_216)
            ShadowStatFs.registerStats(File(context.filesDir, "models").absolutePath, 524_288, 393_216, 393_216)
            var auth = AuthManager(context).also { it.configure("123456") }
            var database = PocketDatabase(context, auth).also { db = it }
            val settings = AppSettings(context).apply { ragAnswerStyle = "excerpt"; selectedLlmId = null; speechEnabled = false }
            var models = ModelManager(context)
            var engine = LocalAiEngine(context).also { local = it }
            var service = OfflineRagServices(context, database, settings, models).also { rag = it }
            var assistant = AssistantService(database, models, engine, settings, service)
            var workshopId = 0L
            var leafletId = 0L
            var longId = 0L
            var longOriginal = ""
            phase("native_environment_and_authenticated_document_import") {
                assertNull(NativeBridge.availabilityMessage()); assertNull(NativeTokenizer.availabilityMessage())
                assertTrue(auth.isUnlocked)
                report.put("compiled_app_version",BuildConfig.VERSION_NAME).put("compiled_version_code",BuildConfig.VERSION_CODE)
                report.put("host_environment",JSONObject().put("cgroup_memory_max",runCatching{File("/sys/fs/cgroup/memory.max").readText().trim()}.getOrDefault("unavailable")).put("cgroup_cpu_max",runCatching{File("/sys/fs/cgroup/cpu.max").readText().trim()}.getOrDefault("unavailable")).put("activity_manager_shadow_total_bytes",2L*1024*1024*1024).put("activity_manager_shadow_available_bytes",1536L*1024*1024).put("statfs_shadow","4096-byte blocks:524288total,393216free/available; supplied test values only").put("main_dispatcher","UnconfinedTestDispatcher to avoid paused Robolectric main-loop deadlock"))
                longOriginal = "This archival note is deliberately long and requires splitting before semantic indexing. ".repeat(270).trim()
                assertTrue(longOriginal.length > 20_000)
                longId = database.saveKnowledge(KnowledgeDraft(longOriginal, "Long archived note", category="Archive"))
                val corpus = JSONObject(File(System.getProperty("pocketai.acceptance.embeddingFixtures")).readText()).getJSONArray("corpus")
                for (i in 0 until corpus.length()) {
                    val row = corpus.getJSONArray(i)
                    val name = if(row.getString(0)=="workshop-code") "workshop #999.txt" else row.getString(0)+".txt"
                    assertEquals(1, database.importText(row.getString(1), name))
                }
                assertEquals(1, database.importText("The tablet contains 250 mg of paracetamol. Do not use during pregnancy.", "medicine-leaflet.txt"))
                workshopId = database.getDocumentChunks(database.listDocuments("workshop").single().id).single().id
                leafletId = database.getDocumentChunks(database.listDocuments("medicine-leaflet").single().id).single().id
                assertTrue(workshopId != 999L)
                JSONObject().put("records",database.countKnowledge()).put("documents",database.listDocuments().size).put("long_record_chars",longOriginal.length)
                    .put("workshop_source_id",workshopId).put("leaflet_source_id",leafletId).put("private_data",context.filesDir.absolutePath)
            }
            phase("actual_pack_preparation_cancel_resume_and_integrity") {
                val initial = async(Dispatchers.IO) { service.prepare() }
                withTimeout(30_000) { OfflineModelAssets.states.first { it["search"]?.let { p -> p.status==OfflinePackStatus.PREPARING && p.copiedBytes>0 } == true } }
                service.pausePreparation()
                var cancelled = false
                try { initial.await() } catch (_: CancellationException) { cancelled = true }
                assertTrue("Preparation pause must cancel its actual caller job",cancelled)
                assertFalse(service.state.value.preparing)
                assertFalse(File(context.filesDir,"offline-models").walkTopDown().any { it.isFile && it.name.endsWith(".part") })
                withTimeout(120_000) { service.prepare() }
                assertTrue(service.state.value.searchReady); assertTrue(service.state.value.translationReady); assertTrue(service.state.value.englishGeneratorReady)
                assertNull(service.state.value.error)
                assertTrue(models.list().any { it.metadata["bundled"]=="true" })
                assertEquals(longOriginal.trim(),database.getKnowledge(longId)!!.content)
                JSONObject().put("pause_cancelled",cancelled).put("ready",service.state.value.toString()).put("speech_models",models.list().size)
            }
            phase("actual_index_skips_long_record_without_starving_later_documents") {
                service.startIndexing()
                val indexed = withTimeout(90_000) { service.indexState.first { !it.running && it.totalRecords==database.countKnowledge() && it.indexedRecords==database.countKnowledge()-1 && it.error!=null } }
                assertEquals(1,database.countPendingEmbeddingKnowledge(OnnxEmbeddingKernel.MODEL_SHA256))
                assertTrue(indexed.error!!.contains("split",true))
                assertEquals(listOf(longId),database.pendingEmbeddingKnowledge(OnnxEmbeddingKernel.MODEL_SHA256).map { it.id })
                assertEquals(longOriginal.trim(),database.getKnowledge(longId)!!.content)
                val found = service.retrieve("ورکشاپ کا کوڈ کیا ہے؟","ur")
                assertTrue(found.records.any { it.id==workshopId })
                JSONObject().put("index_state",indexed.toString()).put("retrieved_source_ids",JSONArray(found.records.map{it.id})).put("semantic",found.semantic)
            }
            phase("editing_long_record_allows_reindex_and_clears_warning") {
                database.saveKnowledge(database.getKnowledge(longId)!!.toDraft().copy(content="The archived note is now a short searchable reference."),longId)
                delay(200)
                service.startIndexing()
                val indexed = withTimeout(90_000) { service.indexState.first { !it.running && it.totalRecords==database.countKnowledge() && it.indexedRecords==database.countKnowledge() && it.error==null && database.countPendingEmbeddingKnowledge(OnnxEmbeddingKernel.MODEL_SHA256)==0 } }
                assertEquals(0,database.countPendingEmbeddingKnowledge(OnnxEmbeddingKernel.MODEL_SHA256))
                JSONObject().put("index_state",indexed.toString()).put("pending",0)
            }
            phase("actual_revised_canonical_retrieval_probe_48_cases") {
                val rows=JSONArray(File(output,"retrieval-probe-queries.json").readText())
                val attempts=JSONArray()
                var known=0;var matched=0;var negatives=0;var falseMatches=0;var codeKnown=0;var codeMatched=0
                fun label(id:Long):String=when(id){workshopId->"workshop-code";leafletId->"medicine-leaflet";longId->"short-archive";else->database.getKnowledge(id)!!.sourceReference.substringBefore(".txt")}
                for(i in 0 until rows.length()) {
                    val row=rows.getJSONObject(i);val question=row.getString("question");val language=row.getString("language")
                    val expected=row.getJSONArray("expected").let{a->(0 until a.length()).map{a.getString(it)}.toSet()}
                    val found=withTimeout(90_000){service.retrieve(question,language.substringBefore('-'),3)}
                    val actual=found.records.map{label(it.id)};val positive=expected.isNotEmpty();val correct=positive&&actual.any{it in expected}
                    if(positive){known++;if(correct)matched++}else{negatives++;if(actual.isNotEmpty())falseMatches++}
                    if(positive&&QuestionAnchors.isCodeQuestion(question)){codeKnown++;if(correct)codeMatched++}
                    attempts.put(JSONObject().put("id",row.getString("id")).put("question",question).put("language",language).put("expected",JSONArray(expected)).put("actual",JSONArray(actual)).put("semantic",found.semantic).put("notice",found.notice)
                        .put("topic_words",JSONArray(QuestionAnchors.topicWords(question))).put("code_question",QuestionAnchors.isCodeQuestion(question)).put("known_match",correct).put("false_match",!positive&&actual.isNotEmpty()))
                    File(output,"canonical-retrieval-probe-results.json").writeText(JSONObject().put("status","RUNNING").put("results",attempts).toString(2))
                    println("CANONICAL PROBE ${row.getString("id")} expected=$expected actual=$actual")
                }
                val summary=JSONObject().put("known_questions",known).put("known_matches",matched).put("negative_questions",negatives).put("false_matches",falseMatches).put("known_code_questions",codeKnown).put("known_code_matches",codeMatched)
                File(output,"canonical-retrieval-probe-results.json").writeText(JSONObject().put("status",if(falseMatches==0&&codeKnown==codeMatched)"PASS_TARGETED_POLICY" else "FAIL").put("summary",summary).put("results",attempts)
                    .put("scope","Actual revised OfflineRagServices.retrieve with real native E5, SQLite and local forward NMT callback, same48queryfixture. Unmatched known cases are reported as conservative abstentions, not hidden. No score simulation or mocked callback.").toString(2))
                assertEquals("Negative fixture queries must not acquire unrelated sources",0,falseMatches)
                assertEquals("All unchanged known code paraphrases require verified topic matches",codeKnown,codeMatched)
                summary
            }
            phase("assistant_actual_english_urdu_gurmukhi_shahmukhi_answers_and_typed_citations") {
                val answers=JSONArray()
                report.put("answer_attempts",answers)
                val requests=listOf(
                    Triple("en","Arab","What is the workshop code?"),
                    Triple("ur","Arab","ورکشاپ کا کوڈ کیا ہے؟"),
                    Triple("pa","Guru","ਵਰਕਸ਼ਾਪ ਦਾ ਕੋਡ ਕੀ ਹੈ?"),
                    Triple("pa","Arab","ورکشاپ دا کوڈ کی اے؟"),
                )
                for ((language,script,question) in requests) {
                    settings.responseLanguage=language;settings.punjabiScript=script
                    val raw=service.retrieve(question,language)
                    val lexical=database.retrieve(question,language,3)
                    val answer=withTimeout(90_000){ assistant.answer(question) }
                    val diagnostic=JSONObject().put("language",language).put("script",script).put("question",question).put("medical_intent",MedicalGuidance.assess(question).intent.name)
                        .put("engine",answer.engine).put("source_ids",JSONArray(answer.sourceIds)).put("sources",answer.sources).put("answer",answer.text)
                        .put("raw_retrieved_ids",JSONArray(raw.records.map{it.id})).put("raw_semantic",raw.semantic).put("raw_notice",raw.notice).put("lexical_ids",JSONArray(lexical.map{it.id}))
                    answers.put(diagnostic);writeReport()
                    if(!answer.sourceIds.contains(workshopId)) {
                        val vector=OfflineEmbeddingService(context).query(question)
                        val scores=database.nearestKnowledgeVectors(vector,OnnxEmbeddingKernel.MODEL_SHA256,20)
                        diagnostic.put("diagnostic_nearest_scores",JSONArray(scores.map{hit -> JSONObject().put("id",hit.knowledgeId).put("score",hit.score).put("source",database.getKnowledge(hit.knowledgeId)?.sourceReference).put("coverage_allows",database.getKnowledge(hit.knowledgeId)?.let{EvidenceCoverage.allows(question,it)})}))
                        writeReport()
                    }
                    assertTrue("Missing workshop citation language=$language script=$script engine=${answer.engine} ids=${answer.sourceIds}",answer.sourceIds.contains(workshopId));assertFalse(answer.sourceIds.contains(999L))
                    assertTrue(answer.sources.contains("workshop #999.txt"));assertTrue(answer.text.contains("cedar-47"));assertTrue(answer.text.contains("250"))
                    assertFalse(answer.engine.contains("unavailable",true))
                    if(language!="en") {
                        assertTrue(answer.engine.contains("offline excerpt translation"))
                        assertTrue(if(script=="Guru") answer.text.any{it in '\u0A00'..'\u0A7F'} else answer.text.any{it in '\u0600'..'\u06FF'})
                    }
                    database.addConversation("user",question,language)
                    database.addConversation("assistant",answer.text,language,answer.storedSources,answer.sourceIds)
                    
                }
                settings.responseLanguage="ur"
                val medical=withTimeout(90_000){assistant.answer("What does the paracetamol leaflet say?")}
                report.put("medical_answer_attempt",JSONObject().put("engine",medical.engine).put("source_ids",JSONArray(medical.sourceIds)).put("sources",medical.sources).put("answer",medical.text));writeReport()
                assertTrue(medical.sourceIds.contains(leafletId));assertTrue(medical.text.contains("250"));assertFalse(medical.engine.contains("unavailable",true))
                assertTrue(medical.text.contains("Do not use during pregnancy."))
                answers.put(JSONObject().put("type","medicine_reference").put("engine",medical.engine).put("source_ids",JSONArray(medical.sourceIds)).put("answer",medical.text))
                JSONObject().put("answers",answers)
            }
            phase("actual_translation_cache_reuses_verified_source") {
                val first=service.translateSource(workshopId,"ur")
                val second=service.translateSource(workshopId,"ur")
                assertTrue(second.cacheHit);assertEquals(first.text,second.text);assertEquals(database.getKnowledge(workshopId)!!.content,second.sourceText)
                JSONObject().put("first_cache_hit",first.cacheHit).put("second_cache_hit",second.cacheHit).put("translated",second.text)
            }
            phase("real_database_service_reopen_preserves_vectors_and_trusted_history") {
                val oldMarkers=OfflineModelAssets.specs(context).filter{it.id!="speech"}.associate { spec -> spec.id to OfflineModelAssets.installed(context,spec)!!.lastModified() }
                service.close();database.close()
                auth=AuthManager(context);assertFalse(auth.isUnlocked)
                database=PocketDatabase(context,auth).also{db=it};models=ModelManager(context);engine=LocalAiEngine(context).also{local=it}
                service=OfflineRagServices(context,database,settings,models).also{rag=it};assistant=AssistantService(database,models,engine,settings,service)
                withTimeout(120_000){service.prepare()}
                assertEquals(0,database.countPendingEmbeddingKnowledge(OnnxEmbeddingKernel.MODEL_SHA256))
                val found=service.retrieve("ورکشاپ کا کوڈ کیا ہے؟","ur")
                assertTrue(found.records.any{it.id==workshopId})
                val repeated=assistant.answer("repeat")
                assertTrue(repeated.sourceIds.contains(workshopId));assertFalse(repeated.sourceIds.contains(999L))
                for(spec in OfflineModelAssets.specs(context).filter{it.id!="speech"})assertEquals(oldMarkers[spec.id],OfflineModelAssets.installed(context,spec)!!.lastModified())
                assertTrue(auth.unlock("123456"))
                JSONObject().put("vectors_still_current",true).put("retrieved_source_ids",JSONArray(found.records.map{it.id})).put("history_source_ids",JSONArray(repeated.sourceIds)).put("packs_reused",true)
            }
            phase("backup_restore_preserves_catalog_citations_and_rebuilds_vectors") {
                val backup=database.exportBackup();File(output,"synthetic-backup.json").writeText(backup)
                val count=database.countKnowledge();val savedSources=database.conversations().last().sourceIds
                database.saveKnowledge(KnowledgeDraft("A temporary synthetic record to be replaced by the backup."))
                assertEquals(count,database.restoreBackup(backup))
                assertEquals(savedSources,database.conversations().last().sourceIds)
                assertEquals(count,database.countPendingEmbeddingKnowledge(OnnxEmbeddingKernel.MODEL_SHA256))
                service.startIndexing()
                withTimeout(90_000){service.indexState.first{!it.running && it.indexedRecords==count && it.totalRecords==count && it.error==null && database.countPendingEmbeddingKnowledge(OnnxEmbeddingKernel.MODEL_SHA256)==0}}
                val first=service.translateSource(workshopId,"ur");assertFalse(first.cacheHit)
                assertTrue(service.translateSource(workshopId,"ur").cacheHit)
                JSONObject().put("restored_records",count).put("catalog_documents",database.listDocuments().size).put("typed_source_ids",JSONArray(savedSources)).put("vectors_rebuilt",true).put("revision_cache_invalidated",true)
            }
            phase("source_edit_delete_invalidates_vectors_translation_and_citations") {
                val original=database.getKnowledge(workshopId)!!
                val edited=original.content.replace("cedar-47","cedar-48").replace("250","251")
                database.saveKnowledge(original.toDraft().copy(content=edited),workshopId)
                assertTrue(database.pendingEmbeddingKnowledge(OnnxEmbeddingKernel.MODEL_SHA256).any{it.id==workshopId})
                val translated=service.translateSource(workshopId,"ur")
                assertFalse(translated.cacheHit);assertTrue(translated.text.contains("cedar-48"));assertTrue(translated.text.contains("251"));assertFalse(translated.text.contains("cedar-47"))
                service.startIndexing()
                withTimeout(90_000){service.indexState.first{!it.running && it.indexedRecords==database.countKnowledge() && it.error==null && database.countPendingEmbeddingKnowledge(OnnxEmbeddingKernel.MODEL_SHA256)==0}}
                val document=database.getDocumentForKnowledge(workshopId)!!
                database.deleteDocument(document.id)
                assertNull(database.getKnowledge(workshopId))
                var rejected=false
                try{service.translateSource(workshopId,"ur")}catch(_:IllegalStateException){rejected=true}
                assertTrue(rejected)
                val found=service.retrieve("ورکشاپ کا کوڈ کیا ہے؟","ur")
                assertFalse(found.records.any{it.id==workshopId})
                JSONObject().put("edited_translation",translated.text).put("deleted_source_rejected",true).put("retrieved_ids_after_delete",JSONArray(found.records.map{it.id}))
            }
            phase("actual_ort_cancellation_releases_runtime_and_next_translation_recovers") {
                val hook=NativeExecution::class.java.getDeclaredField("cancellationHook").apply{isAccessible=true}
                val task=async(Dispatchers.IO){runCatching{service.translateText("The tablet contains 251 mg of paracetamol. Do not use during pregnancy.","ur")}}
                withTimeout(30_000){while(hook.get(null)==null)delay(5)}
                service.cancel()
                val cancelled=withTimeout(30_000){task.await()}
                assertTrue("Active ORT cancellation must fail the interrupted request",cancelled.isFailure)
                val recovered=withTimeout(90_000){service.translateText("The tablet contains 252 mg of paracetamol. Do not use during pregnancy.","ur")}
                assertFalse(recovered.cacheHit)
                assertTrue(recovered.text.contains("252"));assertTrue(recovered.sourceText.contains("pregnancy"))
                assertEquals("ok",database.integrityCheck())
                JSONObject().put("interrupted_failure",cancelled.exceptionOrNull().toString()).put("next_translation",recovered.text).put("database_integrity","ok")
            }
            report.put("status","PASS").put("limits","Host Linux x86_64 (actual host quotas recorded separately): real canonical JNI, SentencePiece, ORT and pinned models; native SQLite with synthetic authenticated data. ActivityManager/StatFs use supplied2GiBtotal/1.5GiBavailable shadow values; this exercises guards and is not physical2GiB-phone proof. Main dispatcher is supplied by host test. Baseline excerpt answers, not optional Smol LLM quality. No audio hardware, TTS, UI touch, Android device acceptance, fluency or clinical validation claim.")
        } finally {
            runCatching{rag?.cancel()};runCatching{rag?.close()};runCatching{local?.unload()};runCatching{db?.close()}
            report.put("vm_hwm",File("/proc/self/status").readLines().firstOrNull{it.startsWith("VmHWM:")})
            writeReport()
            context.filesDir.deleteRecursively();context.deleteDatabase("pocket_ai.db")
            Dispatchers.resetMain()
        }
    } }
}
