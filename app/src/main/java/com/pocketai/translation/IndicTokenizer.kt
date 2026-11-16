package com.pocketai.translation

import com.pocketai.engine.NativeTokenizer
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Token contract of the pinned IndicTrans2 English-to-Indic export.
 *
 * SentencePiece produces piece strings; dict.SRC and dict.TGT supply the model
 * IDs. These IDs are intentionally different from raw SentencePiece IDs.
 * Own one codec per kernel and serialize its full encode/infer/decode request.
 */
class IndicTokenizer private constructor(private val resources: Resources) : TranslationTextCodec, AutoCloseable {
    constructor(sourceSpm: File, sourceDictionary: File, targetDictionary: File) :
        this(load(sourceSpm, sourceDictionary, targetDictionary))

    private var closed = false
    private var prepared: Pair<String, IndicTextProcessor.PreparedSource>? = null

    @Synchronized
    override fun encode(source: String, targetLanguage: String): LongArray {
        check(!closed) { "This translation tokenizer is closed." }
        // A cancelled/failed request cannot donate its entities to a later one.
        prepared = null
        val tag = targetTag(targetLanguage)
        val input = IndicTextProcessor.preprocessEnglish(source)
        val pieces = resources.pieces(input.text)
        require(pieces.size + 3 <= MAX_SOURCE_TOKENS) {
            "This source segment exceeds the translation model's 256-token limit. Split it into shorter sentences."
        }
        val ids = LongArray(pieces.size + 3)
        ids[0] = SOURCE_TAG_ID
        ids[1] = resources.source.getValue(tag).toLong()
        pieces.forEachIndexed { index, piece -> ids[index + 2] = (resources.source[piece] ?: UNKNOWN).toLong() }
        ids[ids.lastIndex] = EOS.toLong()
        prepared = tag to input
        return ids
    }

    @Synchronized
    override fun decode(tokenIds: LongArray, targetLanguage: String): String {
        check(!closed) { "This translation tokenizer is closed." }
        val request = prepared
        prepared = null
        val tag = targetTag(targetLanguage)
        require(request == null || request.first == tag) { "Translation output target differs from its source request." }
        require(tokenIds.size <= MAX_TARGET_TOKENS) { "Translation output exceeds 256 tokens." }
        val text = StringBuilder()
        for (id in tokenIds) {
            require(id in resources.target.indices.first.toLong()..resources.target.indices.last.toLong()) {
                "Translation output contains an invalid dictionary ID."
            }
            if (id > UNKNOWN) text.append(resources.target[id.toInt()])
        }
        // Official convert_tokens_to_string joins dict.TGT pieces directly;
        // target SentencePiece decode is a different operation.
        val decoded = text.toString().replace('\u2581', ' ').trim()
        return IndicTextProcessor.postprocess(decoded, tag, request?.second?.entities.orEmpty())
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            prepared = null
            resources.close()
        }
    }

    private data class Resources(
        val source: Map<String, Int>,
        val target: Array<String>,
        val pieces: (String) -> List<String>,
        val close: () -> Unit,
    )

    companion object {
        const val MAX_SOURCE_TOKENS = 256
        const val MAX_TARGET_TOKENS = 256
        private const val SOURCE_VOCABULARY = 32322
        private const val TARGET_VOCABULARY = 122672
        private const val SOURCE_TAG_ID = 4L
        private const val EOS = 2
        private const val UNKNOWN = 3

        /** No unsupported language is silently substituted for another target. */
        fun targetTag(language: String): String = when (language.replace('_', '-').lowercase(Locale.ROOT)) {
            "ur", "ur-pk", "ur-in", "urd-arab" -> "urd_Arab"
            "pa-guru", "pa-guru-in", "pan-guru" -> "pan_Guru"
            else -> throw IllegalArgumentException("This local translator supports Urdu and Punjabi in Gurmukhi. Shahmukhi conversion is a separate step.")
        }

        private fun load(sourceSpm: File, sourceDictionary: File, targetDictionary: File): Resources {
            val source = readDictionary(sourceDictionary, SOURCE_VOCABULARY)
            val target = readDictionary(targetDictionary, TARGET_VOCABULARY)
            require(source["eng_Latn"] == 4 && source["urd_Arab"] == 68 && source["pan_Guru"] == 46) {
                "The translation dictionary does not match the supported English-to-Indic model."
            }
            val reversed = Array(target.size) { "" }
            target.forEach { (piece, id) -> reversed[id] = piece }
            val tokenizer = NativeTokenizer(sourceSpm)
            return Resources(source, reversed, tokenizer::encodePieces, tokenizer::close)
        }

        private fun readDictionary(file: File, expectedSize: Int): Map<String, Int> {
            val local = file.canonicalFile
            require(local.isFile && local.canRead() && local.length() in 1..MAX_DICTIONARY_BYTES) {
                "A readable local translation dictionary below 8 MiB is required."
            }
            val json = JSONObject(local.readText(Charsets.UTF_8))
            require(json.length() == expectedSize) { "The local translation dictionary has an unsupported vocabulary size." }
            val seen = BooleanArray(expectedSize)
            val result = HashMap<String, Int>(expectedSize)
            val names = json.keys()
            while (names.hasNext()) {
                val piece = names.next()
                val number = json.get(piece)
                require(piece.isNotEmpty() && piece.length <= MAX_PIECE_CHARACTERS && number is Number) {
                    "The translation dictionary contains an invalid entry."
                }
                val id = number.toInt()
                require(number.toDouble() == id.toDouble() && id in seen.indices && !seen[id]) {
                    "Translation dictionary IDs must be unique, contiguous integers."
                }
                seen[id] = true
                result[piece] = id
            }
            require(result["<s>"] == 0 && result["<pad>"] == 1 && result["</s>"] == EOS && result["<unk>"] == UNKNOWN) {
                "The translation dictionary's special tokens do not match this model."
            }
            return result
        }

        /** Test seam exercises dictionary mapping without loading an Android native library. */
        internal fun forTests(source: Map<String, Int>, target: Array<String>, pieces: (String) -> List<String>, onClose: () -> Unit = {}): IndicTokenizer =
            IndicTokenizer(Resources(source, target, pieces, onClose))

        private const val MAX_DICTIONARY_BYTES = 8L * 1024 * 1024
        private const val MAX_PIECE_CHARACTERS = 256
    }
}
