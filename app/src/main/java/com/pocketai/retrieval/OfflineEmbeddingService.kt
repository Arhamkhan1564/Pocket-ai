package com.pocketai.retrieval

import android.app.ActivityManager
import android.content.Context
import ai.onnxruntime.OrtSession
import com.pocketai.data.KnowledgeRecord
import com.pocketai.engine.NativeExecution
import com.pocketai.engine.NativeTokenizer
import com.pocketai.models.OfflineModelAssets
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class OfflineEmbeddingService(context: Context) {
    private val appContext = context.applicationContext
    suspend fun query(text: String): FloatArray = withKernel { it.query(text) }
    suspend fun index(records: List<KnowledgeRecord>,
                      onSkipped: (KnowledgeRecord, String) -> Unit = { _, reason -> throw IllegalArgumentException(reason) },
                      consume: (KnowledgeRecord, List<EmbeddedWindow>) -> Unit) = withKernel { kernel ->
        records.forEach { record ->
            val windows = try { kernel.document(record.content) }
            catch (unsupported: UnsupportedEmbeddingTextException) {
                onSkipped(record, unsupported.message ?: "Split this saved passage into shorter notes.")
                return@forEach
            }
            consume(record, windows)
        }
    }

    private suspend fun <T> withKernel(block: (OnnxEmbeddingKernel) -> T): T {
        val caller = currentCoroutineContext()
        return NativeExecution.runExclusive {
            caller.ensureActive()
            val memory = ActivityManager.MemoryInfo()
            (appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
            require(!memory.lowMemory && memory.availMem >= 384L * 1024 * 1024) {
                "More free memory is needed for semantic search. Close other apps; keyword search still works."
            }
            val directory = OfflineModelAssets.installed(appContext, OfflineModelAssets.embedding)
                ?: error("Prepare offline search in Offline tools first.")
            NativeTokenizer(OfflineModelAssets.file(directory, "tokenizer.spm")).use { native ->
                val tokenizer = object : EmbeddingTokenizer {
                    override val unknownId = native.unknownId()
                    override fun encode(text: String) = native.encodeIds(text)
                }
                OrtSession.RunOptions().use { options ->
                    NativeExecution.installCancellationHook { options.setTerminate(true) }
                    try {
                        OnnxEmbeddingKernel(OfflineModelAssets.file(directory, "model.onnx"), tokenizer, options) { caller.ensureActive() }
                            .use { block(it) }
                    } finally { NativeExecution.clearCancellationHook() }
                }
            }
        }
    }
}
