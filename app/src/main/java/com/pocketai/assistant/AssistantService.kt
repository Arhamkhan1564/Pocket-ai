package com.pocketai.assistant

import com.pocketai.AppSettings
import com.pocketai.data.PocketDatabase
import com.pocketai.engine.LocalAiEngine
import com.pocketai.models.ModelManager
import com.pocketai.models.ModelKind

data class AssistantAnswer(val text: String, val sources: String, val engine: String) {
    /** Keep provenance visible after restart and when exported with conversation history. */
    val storedSources: String get() = if (sources.startsWith("@engine=")) sources else "@engine=$engine\n$sources"
}

/** Retrieval and model generation are distinct; a saved fact never changes model parameters. */
class AssistantService(
    private val db: PocketDatabase,
    private val models: ModelManager,
    private val engine: LocalAiEngine,
    private val settings: AppSettings
) {
    suspend fun answer(question: String): AssistantAnswer {
        val language = settings.responseLanguage
        val command = question.trim().lowercase().trimEnd('.', '!', '?')
        if (command in setOf("repeat", "repeat your previous answer", "repeat that", "دوبارہ بتائیں")) {
            val previous = db.conversations(40).lastOrNull { it.role == "assistant" }
            return AssistantAnswer(previous?.content ?: "There isn’t a previous answer yet.", previous?.sources ?: "", "Command")
        }
        if (command in setOf("speak more slowly", "speak slowly", "slower")) {
            settings.speechRate = (settings.speechRate - .15f).coerceAtLeast(.5f)
            return AssistantAnswer("Speech speed is now ${"%.2f".format(settings.speechRate)}×.", "", "Command")
        }
        LanguageTools.configured(settings.thirdLanguage).firstOrNull {
            command in setOf("change the response language to ${it.name.lowercase()}", "speak ${it.name.lowercase()}", "respond in ${it.name.lowercase()}")
        }?.let {
            settings.responseLanguage = it.tag
            return AssistantAnswer("Response language set to ${it.name}. Saved text keeps its original language; translation requires a capable local model.", "", "Command")
        }
        val records = db.retrieve(question, language, 3)
        val sources = records.joinToString(" · ") { "#${it.id} ${it.category}" }
        val selected = models.list().firstOrNull { it.id == settings.selectedLlmId && it.kind == ModelKind.LLM }
        if (selected != null) {
            try {
                val recent = db.conversations(12)
                val previous = if (recent.lastOrNull()?.let { it.role == "user" && it.content == question } == true) recent.dropLast(1) else recent
                val history = previous.takeLast(6).map { it.role to it.content.take(700) }
                val answer = engine.generate(
                    models.fileFor(selected), question,
                    records.map { "[Saved #${it.id}; ${it.language}; ${it.category}] ${it.question}\n${it.content}" },
                    if (language == "pa") "pa-${settings.punjabiScript}" else language, history, settings.contextSize
                )
                require(answer.isNotBlank()) { "The local model returned no text." }
                return AssistantAnswer(answer, sources, if (records.isEmpty()) "Local model · unverified" else "Local model · saved context")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                if (records.isNotEmpty()) return AssistantAnswer(records.joinToString("\n\n") { it.content }, sources, "Saved knowledge · model unavailable")
                return AssistantAnswer("Local model unavailable: ${e.message?.take(240) ?: "inference failed"}. You can still ask about knowledge saved in Training Mode.", "", "Model error")
            }
        }
        if (records.isNotEmpty()) return AssistantAnswer(records.joinToString("\n\n") { it.content }, sources, "Saved knowledge · original text")
        return AssistantAnswer(LanguageTools.unknown(language, settings.punjabiScript), "", "No matching knowledge")
    }
}
