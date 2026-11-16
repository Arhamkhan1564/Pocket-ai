package com.pocketai.rag

import android.content.Context
import com.pocketai.AppSettings
import com.pocketai.data.KnowledgeVectorPart
import com.pocketai.data.PocketDatabase
import com.pocketai.engine.NativeBridge
import com.pocketai.engine.NativeExecution
import com.pocketai.engine.NativeTokenizer
import com.pocketai.models.BundledSpeechModel
import com.pocketai.models.ModelManager
import com.pocketai.models.OfflineModelAssets
import com.pocketai.models.OfflinePackSpec
import com.pocketai.retrieval.OfflineEmbeddingService
import com.pocketai.retrieval.OnnxEmbeddingKernel
import com.pocketai.retrieval.RagSearchResult
import com.pocketai.retrieval.SemanticIndexState
import com.pocketai.retrieval.SemanticRetriever
import com.pocketai.translation.PunjabiShahmukhi
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class OfflineRagState(
    val preparing: Boolean = false, val searchReady: Boolean = false, val translationReady: Boolean = false,
    val englishGeneratorReady: Boolean = false, val message: String = "Preparing local resources has not started.", val error: String? = null,
)

/** Baseline tools use only licensed files already inside this APK. All heavyweight inference is serialized. */
open class OfflineRagServices(
    context: Context, private val db: PocketDatabase, private val settings: AppSettings, private val models: ModelManager,
) : Closeable {
    private val appContext = context.applicationContext
    private val specifications = OfflineModelAssets.specs(appContext).associateBy { it.id }
    private val embedding = OfflineEmbeddingService(appContext)
    private val retriever = SemanticRetriever(db, embedding)
    private val translator = OfflineTranslationService(appContext, specifications.getValue("translation"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var indexJob: Job? = null
    private val preparationLock = Any()
    private var preparationJob: Job? = null
    @Volatile private var paused = false
    private val mutableState = MutableStateFlow(OfflineRagState())
    private val mutableIndex = MutableStateFlow(SemanticIndexState())
    open val state: StateFlow<OfflineRagState> = mutableState.asStateFlow()
    val indexState: StateFlow<SemanticIndexState> = mutableIndex.asStateFlow()
    val packStates = OfflineModelAssets.states

    suspend fun prepare() = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()[Job] ?: error("Offline setup requires a coroutine.")
        val started = synchronized(preparationLock) {
            if (preparationJob != null) false else { preparationJob = job; true }
        }
        if (!started) return@withContext
        mutableState.value = mutableState.value.copy(preparing = true, message = "Preparing tools from the installed APK · no connection needed", error = null)
        try {
            NativeBridge.availabilityMessage()?.let { error(it) }
            NativeTokenizer.availabilityMessage()?.let { error(it) }
            BundledSpeechModel.ensureInstalled(appContext, models, settings)
            val failures = ArrayList<String>()
            for (id in listOf("search", "translation", "english")) {
                try { OfflineModelAssets.prepare(appContext, specifications.getValue(id)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { failures += failure.message ?: "Could not prepare $id." }
                refreshReadiness(preparing = true, error = failures.firstOrNull())
            }
            translator.invalidateForRevision(db.knowledgeRevision())
            refreshReadiness(false, failures.firstOrNull())
        } catch (cancelled: CancellationException) {
            mutableState.value = mutableState.value.copy(preparing = false, message = "Offline setup paused. Open Offline tools to resume.")
            throw cancelled
        } catch (failure: Exception) {
            mutableState.value = mutableState.value.copy(preparing = false, message = "Saved text search remains available.", error = failure.message ?: "Offline setup could not finish.")
        } finally {
            synchronized(preparationLock) { if (preparationJob === job) preparationJob = null }
        }
    }

    /** Cancels setup regardless of which screen started it, without interrupting unrelated inference. */
    fun pausePreparation() = synchronized(preparationLock) { preparationJob?.cancel() }

    private fun refreshReadiness(preparing: Boolean, error: String?) {
        val search = ready("search")
        val translation = ready("translation")
        val english = ready("english")
        mutableState.value = OfflineRagState(preparing, search, translation, english,
            when {
                preparing -> "Preparing local tools from the APK…"
                search && translation -> "Offline document search and translation ready. Originals and citations stay visible."
                search -> "Offline semantic search ready. Finish translation setup for Urdu/Punjabi answers."
                else -> "Keyword reference search is available. Prepare offline tools for semantic search and translation."
            }, error)
    }

    private fun ready(id: String) = OfflineModelAssets.installed(appContext, specifications.getValue(id)) != null

    @Synchronized fun startIndexing() {
        if (paused || !state.value.searchReady || indexJob?.isActive == true) return
        indexJob = scope.launch {
            try {
                translator.invalidateForRevision(db.knowledgeRevision())
                while (!paused) {
                    ensureActive()
                    val total = db.countKnowledge()
                    val remaining = db.countPendingEmbeddingKnowledge(OnnxEmbeddingKernel.MODEL_SHA256)
                    mutableIndex.value = SemanticIndexState(remaining > 0, false, (total - remaining).coerceAtLeast(0), total,
                        if (remaining == 0) "All saved passages are indexed for offline search." else "Indexing saved passages locally · $remaining remaining")
                    if (remaining == 0) break
                    val records = db.pendingEmbeddingKnowledge(OnnxEmbeddingKernel.MODEL_SHA256, 5)
                    if (records.isEmpty()) break
                    embedding.index(records) { record, windows ->
                        ensureActive()
                        db.storeKnowledgeVectors(record.id, OnnxEmbeddingKernel.MODEL_SHA256, record.updatedAt, record.content,
                            windows.mapIndexed { index, window -> KnowledgeVectorPart(index, window.startOffset, window.endOffset, window.vector) })
                    }
                    delay(100) // A queued user question or voice request can use the shared runtime between batches.
                }
            } catch (cancelled: CancellationException) {
                mutableIndex.value = mutableIndex.value.copy(running = false, paused = paused,
                    message = if (paused) "Indexing paused. Existing search results remain available." else "Indexing stopped; saved documents are unchanged.")
                throw cancelled
            } catch (failure: Exception) {
                mutableIndex.value = mutableIndex.value.copy(running = false, error = failure.message ?: "Indexing could not finish.",
                    message = "Keyword search works while semantic indexing is incomplete.")
            }
        }
    }

    @Synchronized fun pauseIndexing() {
        paused = true
        indexJob?.cancel()
        mutableIndex.value = mutableIndex.value.copy(paused = true, message = "Pausing indexing after the current passage…")
    }
    @Synchronized fun resumeIndexing() {
        paused = false
        if (indexJob?.isActive == true) {
            indexJob?.invokeOnCompletion { startIndexing() }
        } else startIndexing()
    }

    open suspend fun retrieve(question: String, language: String, limit: Int = 3): RagSearchResult =
        retriever.retrieve(question, language, limit, state.value.searchReady)

    open suspend fun translateText(source: String, targetLanguage: String, script: String = "Arab"): OfflineTranslation {
        val tag = targetTag(targetLanguage, script)
        val revision = withContext(Dispatchers.IO) { db.knowledgeRevision() }
        val result = translator.translate(source, tag, revision)
        withContext(Dispatchers.IO) {
            if (db.knowledgeRevision() != revision) {
                translator.invalidateForRevision(db.knowledgeRevision())
                error("Saved references changed during translation. Ask again using the current source.")
            }
        }
        return result
    }

    suspend fun translateSource(sourceId: Long, targetLanguage: String, script: String = "Arab"): OfflineTranslation = withContext(Dispatchers.IO) {
        val source = db.getKnowledge(sourceId)?.takeIf { it.verified } ?: error("This source was removed.")
        val tag = targetTag(targetLanguage, script)
        val result = when {
            source.language == "en" -> translateText(source.content, targetLanguage, script)
            source.language == "pa" && source.script == "Guru" && tag == "pa-Arab" -> OfflineTranslation(
                PunjabiShahmukhi.convert(source.content), source.content, scriptConverted = true, description = "Local Punjabi script conversion · check the original")
            source.language == tag.substringBefore('-') && (source.language != "pa" || source.script == if (tag.contains("Guru")) "Guru" else "Arab") ->
                OfflineTranslation(source.content, source.content, description = "Original saved source")
            else -> error("This offline pack translates English references into Urdu/Punjabi. The original remains available for other directions.")
        }
        val current = db.getKnowledge(sourceId)
        require(current != null && current.updatedAt == source.updatedAt && current.content == source.content) { "This source changed while translating. Reopen the current passage." }
        result
    }

    open fun defaultGeneratorFile(): File? = OfflineModelAssets.installed(appContext, specifications.getValue("english"))
        ?.let { OfflineModelAssets.file(it, "model.gguf") }

    fun cancel() { NativeExecution.cancel() }
    override fun close() { paused = true; pausePreparation(); scope.cancel() }

    private fun targetTag(language: String, script: String) = when {
        language.startsWith("en") -> "en"
        language.startsWith("ur") -> "ur"
        language.startsWith("pa") -> if (language.contains("Guru", true) || (language == "pa" && script == "Guru")) "pa-Guru" else "pa-Arab"
        else -> throw IllegalArgumentException("Choose English, Urdu or Punjabi for this offline translation pack.")
    }
}
