package com.pocketai.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.pocketai.security.AuthManager
import org.json.JSONArray
import org.json.JSONObject

/** SQLite remains independent of speech and inference models. Call disk methods on Dispatchers.IO. */
class PocketDatabase(context: Context, private val auth: AuthManager) : SQLiteOpenHelper(context.applicationContext, "pocket_ai.db", null, 1) {
    init { setWriteAheadLoggingEnabled(true) }

    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE knowledge (
            id INTEGER PRIMARY KEY AUTOINCREMENT, content TEXT NOT NULL, question TEXT NOT NULL DEFAULT '',
            language TEXT NOT NULL, script TEXT NOT NULL, category TEXT NOT NULL, source TEXT NOT NULL,
            source_reference TEXT NOT NULL DEFAULT '', confidence REAL, created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL, verified INTEGER NOT NULL DEFAULT 1,
            normalized_content TEXT NOT NULL, normalized_question TEXT NOT NULL)""")
        db.execSQL("CREATE INDEX knowledge_language ON knowledge(language, updated_at DESC)")
        db.execSQL("CREATE INDEX knowledge_category ON knowledge(category, updated_at DESC)")
        db.execSQL("CREATE INDEX knowledge_question ON knowledge(normalized_question, language)")
        db.execSQL("CREATE INDEX knowledge_updated ON knowledge(updated_at DESC, id DESC)")
        db.execSQL("CREATE VIRTUAL TABLE knowledge_fts USING fts4(content, question, tokenize=unicode61)")
        db.execSQL("CREATE TABLE categories (name TEXT PRIMARY KEY NOT NULL)")
        db.execSQL("INSERT INTO categories(name) VALUES ('General')")
        db.execSQL("""CREATE TABLE training_history (id INTEGER PRIMARY KEY AUTOINCREMENT,
            knowledge_id INTEGER, action TEXT NOT NULL, details TEXT NOT NULL, created_at INTEGER NOT NULL)""")
        db.execSQL("CREATE INDEX training_created ON training_history(created_at DESC)")
        db.execSQL("""CREATE TABLE conversations (id INTEGER PRIMARY KEY AUTOINCREMENT,
            role TEXT NOT NULL, content TEXT NOT NULL, language TEXT NOT NULL, sources TEXT NOT NULL DEFAULT '',
            created_at INTEGER NOT NULL)""")
        db.execSQL("CREATE INDEX conversations_created ON conversations(id DESC)")
        db.execSQL("""CREATE TABLE corrections (id INTEGER PRIMARY KEY AUTOINCREMENT, knowledge_id INTEGER NOT NULL,
            previous_json TEXT NOT NULL, replacement_json TEXT NOT NULL, created_at INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE documents (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL,
            language TEXT NOT NULL, imported_at INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE document_chunks (document_id INTEGER NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
            knowledge_id INTEGER NOT NULL REFERENCES knowledge(id) ON DELETE CASCADE, position INTEGER NOT NULL,
            PRIMARY KEY(document_id, position))""")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the initial schema. Future migrations must preserve records and reindex FTS.
        check(oldVersion == newVersion) { "This database was created by an unsupported Pocket AI schema. Export it with the original application first." }
    }

    @Synchronized
    fun saveKnowledge(draft: KnowledgeDraft, id: Long? = null): Long {
        auth.requireAuthorized()
        val clean = validate(draft)
        val db = writableDatabase
        var savedId = 0L
        db.beginTransaction()
        try {
            val old = id?.let { getKnowledge(it) ?: throw IllegalArgumentException("Knowledge record no longer exists.") }
            val duplicate = findDuplicates(clean).firstOrNull { it.id != id }
            require(duplicate == null) { "This knowledge is already saved as record #${duplicate?.id}." }
            val now = System.currentTimeMillis()
            val values = values(clean, old?.createdAt ?: now, now)
            savedId = if (id == null) db.insertOrThrow("knowledge", null, values) else {
                check(db.update("knowledge", values, "id=?", arrayOf(id.toString())) == 1) { "Knowledge update failed." }
                id
            }
            index(db, savedId, clean)
            db.insertWithOnConflict("categories", null, ContentValues().apply { put("name", clean.category) }, SQLiteDatabase.CONFLICT_IGNORE)
            if (old != null) db.insertOrThrow("corrections", null, ContentValues().apply {
                put("knowledge_id", savedId); put("previous_json", recordJson(old).toString())
                put("replacement_json", draftJson(clean).toString()); put("created_at", now)
            })
            event(db, savedId, if (id == null) "created" else "corrected", clean.content.take(500))
            check(getKnowledge(savedId)?.content == clean.content) { "Saved knowledge could not be verified." }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return savedId
    }

    @Synchronized
    fun deleteKnowledge(id: Long) {
        auth.requireAuthorized()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val old = getKnowledge(id) ?: throw IllegalArgumentException("Knowledge record no longer exists.")
            db.delete("knowledge_fts", "rowid=?", arrayOf(id.toString()))
            check(db.delete("knowledge", "id=?", arrayOf(id.toString())) == 1)
            event(db, id, "deleted", old.content.take(500))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun getKnowledge(id: Long): KnowledgeRecord? = readableDatabase.query("knowledge", null, "id=?", arrayOf(id.toString()), null, null, null, "1")
        .use { if (it.moveToFirst()) it.knowledge() else null }

    fun listKnowledge(query: String = "", language: String? = null, category: String? = null, limit: Int = 30, offset: Int = 0): List<KnowledgeRecord> {
        require(limit in 1..100 && offset >= 0) { "Invalid page." }
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        val normalized = SearchText.normalize(query.take(2000))
        if (normalized.isNotEmpty()) {
            clauses += "(normalized_content LIKE ? ESCAPE '\\' OR normalized_question LIKE ? ESCAPE '\\')"
            args += "%${escapeLike(normalized)}%"; args += "%${escapeLike(normalized)}%"
        }
        if (!language.isNullOrBlank()) { clauses += "language=?"; args += language }
        if (!category.isNullOrBlank()) { clauses += "category=?"; args += category }
        return readableDatabase.query("knowledge", null, clauses.takeIf { it.isNotEmpty() }?.joinToString(" AND "), args.toTypedArray(), null, null, "updated_at DESC, id DESC", "$offset,$limit")
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.knowledge()) } }
    }

    /** Bounded lexical retrieval across stored languages. This does not claim machine translation. */
    fun retrieve(query: String, language: String? = null, limit: Int = 5): List<KnowledgeRecord> {
        require(limit in 1..20)
        val plan = SearchText.keywordQuery(query)
        if (plan.terms.isEmpty()) return emptyList()
        // Broad words such as "medicine" must not crowd a specific symptom/drug out of the candidate set.
        val match = plan.primaryTerms.joinToString(" OR ") { "\"${it.replace("\"", "") }\"" }
        val candidates = readableDatabase.rawQuery(
            "SELECT k.* FROM knowledge k INNER JOIN knowledge_fts ON knowledge_fts.rowid=k.id WHERE knowledge_fts MATCH ? AND k.verified=1 ORDER BY k.updated_at DESC LIMIT 100",
            arrayOf(match)
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.knowledge()) } }
        return candidates.map { record ->
            val body = SearchText.normalize(record.content)
            val question = SearchText.normalize(record.question)
            val bodyMatches = plan.literalTerms.count { SearchText.containsTerm(body, it) }
            val questionMatches = plan.literalTerms.count { SearchText.containsTerm(question, it) }
            val conceptScore = plan.concepts.sumOf { concept ->
                val questionHit = concept.terms.any { SearchText.containsTerm(question, it) }
                val bodyHit = concept.terms.any { SearchText.containsTerm(body, it) }
                (if (questionHit) if (concept.generic) 1.0 else 7.0 else 0.0) +
                    (if (bodyHit) if (concept.generic) 0.5 else 4.0 else 0.0)
            }
            val exact = question.isNotEmpty() && question == plan.normalized
            val score = (if (exact) 100.0 else 0.0) + 5.0 * questionMatches + 2.0 * bodyMatches + conceptScore +
                (if (body.contains(plan.normalized)) 4.0 else 0.0) + (if (record.language == language) 0.5 else 0.0)
            record to score
        }.filter { it.second > 0.5 }.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    fun findDuplicates(draft: KnowledgeDraft): List<KnowledgeRecord> = readableDatabase.query(
        "knowledge", null, "normalized_content=? AND normalized_question=? AND language=? AND script=?",
        arrayOf(SearchText.normalize(draft.content), SearchText.normalize(draft.question), draft.language, draft.script), null, null, "id DESC", "10"
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.knowledge()) } }

    fun findConflicts(draft: KnowledgeDraft): List<KnowledgeRecord> {
        // Document titles are shared by many passages; they are not competing Q/A answers.
        if (draft.source == "document") return emptyList()
        val question = SearchText.normalize(draft.question)
        if (question.isBlank()) return emptyList()
        return readableDatabase.query("knowledge", null, "normalized_question=? AND language=? AND normalized_content<>?",
            arrayOf(question, draft.language, SearchText.normalize(draft.content)), null, null, "updated_at DESC", "20")
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.knowledge()) } }
    }

    fun categories(): List<String> = readableDatabase.rawQuery("SELECT name FROM categories ORDER BY name COLLATE NOCASE", null)
        .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    fun addCategory(name: String) {
        auth.requireAuthorized()
        val clean = name.trim()
        require(clean.isNotEmpty() && clean.length <= 100) { "Category must contain 1–100 characters." }
        writableDatabase.insertWithOnConflict("categories", null, ContentValues().apply { put("name", clean) }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun deleteCategory(name: String) {
        auth.requireAuthorized()
        require(name != "General") { "The General category is required." }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.update("knowledge", ContentValues().apply { put("category", "General") }, "category=?", arrayOf(name))
            db.delete("categories", "name=?", arrayOf(name))
            event(db, null, "category_deleted", name)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun countKnowledge(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM knowledge", null).use { it.moveToFirst(); it.getInt(0) }

    fun trainingHistory(limit: Int = 50): List<TrainingEvent> {
        auth.requireAuthorized()
        require(limit in 1..200)
        return readableDatabase.query("training_history", null, null, null, null, null, "id DESC", limit.toString()).use { cursor -> buildList {
            while (cursor.moveToNext()) add(TrainingEvent(cursor.long("id"), cursor.getColumnIndexOrThrow("knowledge_id").let { if (cursor.isNull(it)) null else cursor.getLong(it) }, cursor.string("action"), cursor.string("details"), cursor.long("created_at")))
        } }
    }

    fun addConversation(role: String, content: String, language: String = "en", sources: String = ""): Long {
        require(role in setOf("user", "assistant", "system")) { "Unknown conversation role." }
        require(content.isNotBlank() && content.length <= MAX_CONTENT) { "Conversation is empty or too long." }
        val db = writableDatabase
        val id = db.insertOrThrow("conversations", null, ContentValues().apply {
            put("role", role); put("content", content); put("language", language.take(32)); put("sources", sources.take(5000)); put("created_at", System.currentTimeMillis())
        })
        // Keep a bounded local context. Permanent knowledge is never removed by conversation retention.
        db.execSQL("DELETE FROM conversations WHERE id NOT IN (SELECT id FROM conversations ORDER BY id DESC LIMIT 500)")
        return id
    }

    fun conversations(limit: Int = 50): List<ConversationRecord> {
        require(limit in 1..500)
        return readableDatabase.query("conversations", null, null, null, null, null, "id DESC", limit.toString()).use { cursor -> buildList {
            while (cursor.moveToNext()) add(ConversationRecord(cursor.long("id"), cursor.string("role"), cursor.string("content"), cursor.string("language"), cursor.string("sources"), cursor.long("created_at")))
        } }.reversed()
    }

    fun clearConversations() { writableDatabase.delete("conversations", null, null) }

    /** Indexes already-extracted text after administrator review. No document changes model parameters. */
    @Synchronized
    fun importText(text: String, filename: String, language: String = "en", script: String = "Latn", category: String = "Documents"): Int {
        auth.requireAuthorized()
        val name = filename.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\p{Cntrl}]"), " ").trim().take(200).ifBlank { "Imported document" }
        val chunks = DocumentChunker.chunk(text)
        val drafts = chunks.mapIndexed { position, chunk -> validate(KnowledgeDraft(
            content = chunk, question = name, language = language, script = script, category = category,
            source = "document", sourceReference = "$name · chunk ${position + 1}/${chunks.size}"
        )) }
        auth.requireAuthorized()
        val db = writableDatabase
        var imported = 0
        db.beginTransaction()
        try {
            var documentId: Long? = null
            drafts.forEachIndexed { position, draft ->
                if (findDuplicates(draft).isEmpty()) {
                    if (documentId == null) documentId = db.insertOrThrow("documents", null, ContentValues().apply { put("name", name); put("language", language); put("imported_at", System.currentTimeMillis()) })
                    val id = saveKnowledge(draft)
                    db.insertOrThrow("document_chunks", null, ContentValues().apply { put("document_id", documentId); put("knowledge_id", id); put("position", position) })
                    imported++
                }
            }
            event(db, null, if (imported == 0) "document_duplicate" else "document_imported", "$name: $imported new chunks; ${drafts.size - imported} already indexed")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return imported
    }

    /** Portable plaintext JSON backup. Models, PINs, and recovery codes are deliberately separate. */
    @Synchronized
    fun exportBackup(): String {
        auth.requireAuthorized()
        val db = readableDatabase
        db.beginTransaction()
        try {
            val records = JSONArray()
            var accumulatedBytes = 0L
            db.query("knowledge", null, null, null, null, null, "id ASC").use { cursor -> while (cursor.moveToNext()) {
                val record = recordJson(cursor.knowledge())
                accumulatedBytes += record.toString().toByteArray(Charsets.UTF_8).size
                require(accumulatedBytes <= MAX_BACKUP_BYTES - 1_000_000 && records.length() < 20_000) { "Backup exceeds the portable backup limit. Export a smaller knowledge collection." }
                records.put(record)
            } }
            val chats = JSONArray()
            db.query("conversations", null, null, null, null, null, "id ASC", "500").use { cursor -> while (cursor.moveToNext()) {
                val chat = JSONObject().put("role", cursor.string("role")).put("content", cursor.string("content"))
                    .put("language", cursor.string("language")).put("sources", cursor.string("sources")).put("createdAt", cursor.long("created_at"))
                accumulatedBytes += chat.toString().toByteArray(Charsets.UTF_8).size
                require(accumulatedBytes <= MAX_BACKUP_BYTES - 1_000_000) { "Backup exceeds the portable backup limit. Clear conversation history or export a smaller knowledge collection." }
                chats.put(chat)
            } }
            val backup = JSONObject().put("format", "pocket-ai-backup").put("version", 1).put("createdAt", System.currentTimeMillis())
                .put("knowledge", records).put("categories", JSONArray(categories())).put("conversations", chats)
            val text = backup.toString(2)
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_BACKUP_BYTES) { "Backup exceeds 16 MB. Export a smaller knowledge collection." }
            db.setTransactionSuccessful()
            return text
        } finally { db.endTransaction() }
    }

    /** Replaces knowledge and conversation data only after the entire backup has been validated. */
    @Synchronized
    fun restoreBackup(json: String): Int {
        auth.requireAuthorized()
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BACKUP_BYTES) { "Backup exceeds the 16 MB safety limit." }
        val root = JSONObject(json)
        require(root.optString("format") == "pocket-ai-backup" && root.optInt("version", -1) == 1) { "Unsupported Pocket AI backup format or version." }
        val entries = root.getJSONArray("knowledge")
        require(entries.length() <= 20_000) { "Backup has too many records." }
        // Parse before starting destructive work. Any invalid record leaves the existing database unchanged.
        data class RestoredRecord(val id: Long, val draft: KnowledgeDraft, val createdAt: Long, val updatedAt: Long)
        val restoredIds = HashSet<Long>()
        val parsed = (0 until entries.length()).map { index ->
            val obj = entries.getJSONObject(index)
            val id = obj.getLong("id")
            require(id > 0 && id < Long.MAX_VALUE && restoredIds.add(id)) { "Backup contains an invalid or duplicate record ID." }
            val draft = validate(KnowledgeDraft(obj.getString("content"), obj.optString("question"), obj.getString("language"), obj.optString("script", "Latn"), obj.optString("category", "General"), obj.optString("source", "backup"), obj.optString("sourceReference"), if (obj.has("confidence") && !obj.isNull("confidence")) obj.getDouble("confidence") else null))
            val created = obj.optLong("createdAt", System.currentTimeMillis())
            val updated = obj.optLong("updatedAt", created)
            require(created >= 0 && updated >= 0) { "Backup contains invalid dates." }
            RestoredRecord(id, draft, created, updated)
        }
        val categories = root.optJSONArray("categories") ?: JSONArray()
        require(categories.length() <= 1000) { "Backup has too many categories." }
        val parsedCategories = (0 until categories.length()).map { categories.getString(it).trim().also { name -> require(name.isNotEmpty() && name.length <= 100) { "Invalid backup category." } } }
        val chats = root.optJSONArray("conversations") ?: JSONArray()
        require(chats.length() <= 500) { "Backup contains too many conversations." }
        val parsedChats = (0 until chats.length()).map { i -> chats.getJSONObject(i).also { obj ->
            require(obj.getString("role") in setOf("user", "assistant", "system")) { "Invalid conversation role." }
            require(obj.getString("content").isNotBlank() && obj.getString("content").length <= MAX_CONTENT) { "Invalid conversation text." }
        } }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("document_chunks", null, null); db.delete("documents", null, null)
            db.delete("knowledge_fts", null, null); db.delete("knowledge", null, null)
            db.delete("corrections", null, null); db.delete("training_history", null, null)
            db.delete("categories", null, null); db.delete("conversations", null, null)
            db.execSQL("INSERT INTO categories(name) VALUES ('General')")
            parsedCategories.forEach { db.insertWithOnConflict("categories", null, ContentValues().apply { put("name", it) }, SQLiteDatabase.CONFLICT_IGNORE) }
            parsed.forEach { record ->
                db.insertOrThrow("knowledge", null, values(record.draft, record.createdAt, record.updatedAt).apply { put("id", record.id) })
                index(db, record.id, record.draft)
                db.insertWithOnConflict("categories", null, ContentValues().apply { put("name", record.draft.category) }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            parsedChats.forEach { chat -> db.insertOrThrow("conversations", null, ContentValues().apply {
                put("role", chat.getString("role")); put("content", chat.getString("content")); put("language", chat.optString("language", "en").take(32));
                put("sources", chat.optString("sources").take(5000)); put("created_at", chat.optLong("createdAt", System.currentTimeMillis()).coerceAtLeast(0))
            }) }
            event(db, null, "backup_restored", "${parsed.size} records restored; prior training history replaced")
            check(countKnowledge() == parsed.size) { "Restore verification failed." }
            check(db.rawQuery("PRAGMA quick_check", null).use { it.moveToFirst(); it.getString(0) == "ok" }) { "Database integrity check failed." }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return parsed.size
    }

    fun integrityCheck(): String = readableDatabase.rawQuery("PRAGMA quick_check", null).use { it.moveToFirst(); it.getString(0) }

    private fun validate(draft: KnowledgeDraft): KnowledgeDraft {
        val clean = draft.copy(content = draft.content.trim(), question = draft.question.trim(), category = draft.category.trim().ifBlank { "General" })
        require(clean.content.isNotBlank() && clean.content.length <= MAX_CONTENT) { "Knowledge must contain 1–100,000 characters." }
        require(clean.question.length <= 2000) { "Question must be shorter than 2,000 characters." }
        require(clean.category.length <= 100 && clean.source.length in 1..100 && clean.sourceReference.length <= 1000) { "Knowledge metadata is too long." }
        require(clean.language.matches(Regex("[A-Za-z]{2,8}([_-][A-Za-z0-9]{1,8}){0,3}"))) { "Choose a valid language code." }
        require(clean.script.matches(Regex("[A-Za-z]{4}"))) { "Choose a valid four-letter script code." }
        require(clean.confidence == null || (clean.confidence.isFinite() && clean.confidence in 0.0..1.0)) { "Confidence must be between 0 and 1." }
        return clean
    }

    private fun values(draft: KnowledgeDraft, createdAt: Long, updatedAt: Long) = ContentValues().apply {
        put("content", draft.content); put("question", draft.question); put("language", draft.language); put("script", draft.script)
        put("category", draft.category); put("source", draft.source); put("source_reference", draft.sourceReference)
        if (draft.confidence == null) putNull("confidence") else put("confidence", draft.confidence)
        put("created_at", createdAt); put("updated_at", updatedAt); put("verified", 1)
        put("normalized_content", SearchText.normalize(draft.content)); put("normalized_question", SearchText.normalize(draft.question))
    }

    private fun index(db: SQLiteDatabase, id: Long, draft: KnowledgeDraft) {
        db.delete("knowledge_fts", "rowid=?", arrayOf(id.toString()))
        db.execSQL("INSERT INTO knowledge_fts(rowid, content, question) VALUES (?, ?, ?)", arrayOf(id, SearchText.normalize(draft.content), SearchText.normalize(draft.question)))
    }

    private fun event(db: SQLiteDatabase, id: Long?, action: String, details: String) {
        db.insertOrThrow("training_history", null, ContentValues().apply { if (id == null) putNull("knowledge_id") else put("knowledge_id", id); put("action", action); put("details", details); put("created_at", System.currentTimeMillis()) })
    }

    private fun draftJson(draft: KnowledgeDraft) = JSONObject().put("content", draft.content).put("question", draft.question)
        .put("language", draft.language).put("script", draft.script).put("category", draft.category).put("source", draft.source)
        .put("sourceReference", draft.sourceReference).put("confidence", draft.confidence ?: JSONObject.NULL)

    private fun recordJson(record: KnowledgeRecord) = draftJson(record.toDraft()).put("id", record.id).put("createdAt", record.createdAt).put("updatedAt", record.updatedAt).put("verified", record.verified)

    private fun Cursor.string(name: String) = getString(getColumnIndexOrThrow(name))
    private fun Cursor.long(name: String) = getLong(getColumnIndexOrThrow(name))
    private fun Cursor.knowledge() = KnowledgeRecord(long("id"), string("content"), string("question"), string("language"), string("script"), string("category"), string("source"), string("source_reference"), getColumnIndexOrThrow("confidence").let { if (isNull(it)) null else getDouble(it) }, long("created_at"), long("updated_at"), getInt(getColumnIndexOrThrow("verified")) == 1)
    private fun escapeLike(value: String) = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    companion object {
        const val MAX_BACKUP_BYTES = 16 * 1024 * 1024
        private const val MAX_CONTENT = 100_000
    }
}
