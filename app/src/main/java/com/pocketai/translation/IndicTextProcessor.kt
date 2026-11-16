package com.pocketai.translation

/**
 * Deterministic English/Urdu/Gurmukhi text adapter for IndicTransToolkit1.1.1.
 * Portions ported from AI4Bharat IndicTransToolkit (MIT), sacremoses0.2.0 (MIT),
 * and Anoop Kunchukuttan's IndicNLP Library (MIT); original license notices ship
 * with the project. This adapter does not translate languages or validate medicine.
 */
object IndicTextProcessor {
    data class PreparedSource(val text: String, val entities: Map<String, String>)
    private const val MAX_SOURCE = 8192
    private const val MAX_ENTITIES = 128
    private const val MAX_DECODED = 65536
    // Android6/API23 ICU rejects Java's later (?U) flag. Expand Python's
    // Unicode-aware shorthands explicitly, including inside character sets.
    private fun rx(pattern: String): Regex {
        val whitespace = "\\p{Z}\\t\\n\\u000b\\f\\r\\u001c-\\u001f\\u0085"
        // The toolkit imports Python's `regex` module: its word class includes
        // marks, letter-numbers, connectors and join controls, unlike stdlib re.
        val word = "\\p{L}\\p{M}\\p{Nl}\\p{Nd}\\p{Pc}\\u200c\\u200d"
        var inSet = false
        return Regex(buildString {
            var index = 0
            while (index < pattern.length) {
                val ch = pattern[index++]
                if (ch == '\\' && index < pattern.length) {
                    val escaped = pattern[index++]
                    when (escaped) {
                        'd' -> append("\\p{Nd}")
                        's', 'w' -> {
                            val body = if (escaped == 's') whitespace else word
                            if (!inSet) append('[')
                            append(body)
                            if (!inSet) append(']')
                        }
                        else -> append('\\').append(escaped)
                    }
                } else {
                    if (ch == '[') inSet = true
                    if (ch == ']') inSet = false
                    append(ch)
                }
            }
        })
    }

    fun preprocessEnglish(source: String): PreparedSource {
        require(source.length <= MAX_SOURCE) { "Translate at most 8,192 source characters at a time." }
        require(!containsReservedPlaceholder(source)) { "The source contains a reserved toolkit ID placeholder." }
        var text = punctuationNormalize(source)
        text = buildString(text.length) { text.forEach { append(normalizeDigit(it)) } }
        val entities = linkedMapOf<String, String>()
        for ((kind, pattern) in entityPatterns) {
            val matches = pattern.findAll(text).map { it.value }.toCollection(linkedSetOf())
            for (match in matches) {
                if (kind == "url" && match.replace(".", "").length < 4) continue
                if (kind == "number" && match.replace(" ", "").replace(".", "").replace(":", "").length < 4) continue
                require(entities.size < MAX_ENTITIES) { "A translation can protect at most 128 distinct entities." }
                val placeholder = "<ID${entities.size + 1}>"
                entities[placeholder] = match
                text = text.replace(match, placeholder)
            }
        }
        text = rx("\\s+").replace(text, " ").replace(">/", ">").replace("]/", "]")
        for ((pattern, replacement) in mosesNormalization) text = pattern.replace(text, replacement)
        return PreparedSource(tokenizeEnglish(text.trim()), entities.toMap())
    }

    fun postprocess(decoded: String, targetTag: String, entities: Map<String, String> = emptyMap()): String {
        require(targetTag == "urd_Arab" || targetTag == "pan_Guru") { "This text processor supports urd_Arab and pan_Guru only." }
        require(decoded.length <= MAX_DECODED) { "The translated text exceeds the processor limit." }
        require(entities.size <= MAX_ENTITIES) { "Too many protected translation entities." }
        var text = if (targetTag == "urd_Arab") decoded.replace(" ؟", "؟").replace(" ۔", "۔").replace(" ،", "،").replace("ٮ۪", "ؠ") else decoded
        for ((key, value) in entities) {
            val serial = Regex("<ID([1-9][0-9]{0,2})>").matchEntire(key)?.groupValues?.get(1)
                ?: throw IllegalArgumentException("Invalid protected entity key.")
            require(serial.toInt() <= MAX_ENTITIES && value.length <= MAX_SOURCE && !containsReservedPlaceholder(value)) { "Invalid protected translation entity." }
            for (variant in placeholderVariants(serial)) text = boundedReplace(text, variant, value)
        }
        if (targetTag == "pan_Guru") text = buildString(text.length) {
            text.forEach { ch -> append(if (ch in '\u0900'..'\u096f' && ch != '\u0964' && ch != '\u0965') (ch.code + 0x100).toChar() else ch) }
        }
        return detokenize(text)
    }

    private fun boundedReplace(text: String, token: String, replacement: String): String {
        if (token !in text) return text
        var count = 0
        var offset = 0
        while (true) {
            val found = text.indexOf(token, offset)
            if (found < 0) break
            count++
            offset = found + token.length
        }
        require(text.length.toLong() + count.toLong() * (replacement.length - token.length) <= MAX_DECODED) {
            "Restored translation entities exceed the output size limit."
        }
        return text.replace(token, replacement)
    }

    private fun punctuationNormalize(source: String): String {
        var text = source
        for ((pattern, replacement) in punctuationRules) text = pattern.replace(text, replacement)
        text = rx("[ ]{2,}").replace(text, " ")
        text = rx("\\) ([.!:?;,])").replace(text, ")\$1")
        text = rx("(\\d) %").replace(text, "\$1%")
        text = rx("\"([,.]+)").replace(text, "\$1\"")
        text = rx("(\\d) (\\d)").replace(text, "\$1.\$2")
        return text.trim()
    }

    private fun normalizeDigit(character: Char): Char {
        // This deliberately follows the reviewed toolkit's explicit table,
        // including its asymmetric Arabic-Indic/Telugu zero entries.
        val code = character.code
        for (base in digitBases) if (code in base..base + 9) {
            if (base == 0x0c66 && code == base) return character
            return ('0'.code + code - base).toChar()
        }
        return if (code == 0x0660) '0' else character
    }

    private fun tokenizeEnglish(source: String): String {
        var text = rx("\\s+").replace(source, " ")
        text = Regex("[\\u0000-\\u001f]").replace(text, "").trim()
        text = rx("([^${alnumClass}\\s.'`,\\-])").replace(text, " \$1 ")
        text = Regex("\\.{2,}").replace(text) { match -> " " + "DOT".repeat(match.value.length) + "MULTI" + if (match.range.last < text.lastIndex) " " else "" }
        text = rx("([^${numberClass}]),").replace(text, "\$1 , ")
        text = rx(",([^${numberClass}])").replace(text, " , \$1")
        text = rx("([${numberClass}]),$").replace(text, "\$1 , ")
        text = rx("([^${alphaClass}])'([^${alphaClass}])").replace(text, "\$1 ' \$2")
        text = rx("([^${alphaClass}${numberClass}])'([${alphaClass}])").replace(text, "\$1 ' \$2")
        text = rx("([${alphaClass}])'([^${alphaClass}])").replace(text, "\$1 ' \$2")
        text = rx("([${alphaClass}])'([${alphaClass}])").replace(text, "\$1 '\$2")
        text = rx("([${numberClass}])'s").replace(text, "\$1 's")
        val tokens = rx("\\s+").split(text.trim()).filter { it.isNotEmpty() }.toMutableList()
        for (index in tokens.indices) {
            val token = tokens[index]
            if (token.length < 2 || !token.endsWith('.')) continue
            val prefix = token.dropLast(1)
            val next = tokens.getOrNull(index + 1)
            val nextLower = next?.let { lowerRegex.matches(String(Character.toChars(it.codePointAt(0)))) } == true
            val internalAbbreviation = '.' in prefix && alphaRegex.containsMatchIn(prefix)
            if (internalAbbreviation || prefix in nonbreakingPrefixes || nextLower) continue
            if (prefix in numericPrefixes && next?.firstOrNull() in '0'..'9') continue
            tokens[index] = "$prefix ."
        }
        text = rx("\\s+").replace(tokens.joinToString(" "), " ").trim()
        text = Regex("\\.' ?$").replace(text, " . ' ")
        text = Regex("(?:DOT)+MULTI").replace(text) { ".".repeat((it.value.length - 5) / 3) }
        return rx("\\s+").split(text.trim()).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun detokenize(input: String): String {
        var text = input
        val numberSequences = Regex("([0-9]+ [,.:/] )+[0-9]+")
        // Preserve the toolkit's first-position behavior (start must exceed prev).
        val original = text
        var previous = 0
        text = buildString {
            for (match in numberSequences.findAll(original)) if (match.range.first > previous) {
                append(original.substring(previous, match.range.first))
                append(match.value.replace(" ", ""))
                previous = match.range.last + 1
            }
            append(original.substring(previous))
        }
        text = Regex(" ([\\-/\\\\]) ").replace(text, "\$1")
        text = Regex(" ([!%)\\]},.:;>?।॥])").replace(text, "\$1")
        text = Regex("([#\$(\\[{<@]) ").replace(text, "\$1")
        for (quote in charArrayOf('\'', '"', '`')) {
            var count = 0
            text = buildString(text.length) {
                text.forEach { ch -> if (ch == quote) { append(if (count++ % 2 == 0) "@RA" else "@LA") } else append(ch) }
            }.replace("@RA ", quote.toString()).replace(" @LA", quote.toString()).replace("@RA", quote.toString()).replace("@LA", quote.toString())
        }
        return text
    }

    private fun containsReservedPlaceholder(text: String): Boolean =
        Regex("(?i)[<\\[]\\s*id\\s*[0-9]+\\s*[>\\]]").containsMatchIn(text) ||
            indicFailures.any { rx(Regex.escape(it) + "\\s*[0-9]+").containsMatchIn(text) }

    private fun placeholderVariants(serial: String): List<String> = buildList {
        for (id in listOf("ID", "id")) {
            add("<$id$serial>"); add("< $id$serial >"); add("[$id$serial]"); add("[ $id$serial ]")
            add("[$id $serial]"); add("<$id$serial]"); add("< $id$serial]"); add("<$id$serial ]")
        }
        for (prefix in indicFailures) {
            add("<$prefix$serial>"); add("< $prefix$serial >"); add("< $prefix $serial >")
            add("<$prefix $serial]"); add("< $prefix $serial ]"); add("[$prefix$serial]"); add("[$prefix $serial]")
            add("[ $prefix$serial ]"); add("[ $prefix $serial ]"); add("$prefix $serial"); add("$prefix$serial")
        }
    }

    private val digitBases = intArrayOf(0x09e6, 0x0ae6, 0x0ce6, 0x0966, 0xabf0, 0x0b66, 0x0a66, 0x1c50, 0x06f0, 0x0c66)
    private val entityPatterns = listOf(
        "email" to rx("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}"),
        "url" to rx("\\b(?<![\\w/.])(?:(?:https?|ftp)://)?(?:(?:[\\w-]+\\.)+(?!\\.))(?:[\\w/\\-?#&=%.]+)+(?!\\.\\w+)\\b"),
        "number" to rx("(~?\\d+\\.?\\d*\\s?%?\\s?-?\\s?~?\\d+\\.?\\d*\\s?%|~?\\d+%|\\d+[-/.,:']\\d+[-/.,:'+]\\d+(?:\\.\\d+)?|\\d+[-/.:'+]\\d+(?:\\.\\d+)?)"),
        "other" to rx("[A-Za-z0-9]*[#|@]\\w+")
    )
    private val indicFailures = listOf("آی ڈی ", "ꯑꯥꯏꯗꯤ", "आईडी", "आई . डी . ", "आई . डी .", "आई. डी. ", "आई. डी.", "आय. डी. ", "आय. डी.", "आय . डी . ", "आय . डी .आइ . डी . ", "आइ . डी .", "आइ. डी. ", "आइ. डी.", "ऐटि", "آئی ڈی ", "ᱟᱭᱰᱤ ᱾", "आयडी", "ऐडि", "आइडि", "ᱟᱭᱰᱤ")

    // Frozen sacremoses0.2.0 Perl Unicode membership classes (range-compressed).
    private const val alphaClass = "\\u0041-\\u005a\\u0061-\\u007a\\u00aa\\u00b5\\u00ba\\u00c0-\\u00d6\\u00d8-\\u00f6\\u00f8-\\u02c1\\u02c6-\\u02d1\\u02e0-\\u02e4\\u02ec\\u02ee\\u0345\\u0370-\\u0374\\u0376-\\u0377\\u037a-\\u037d\\u037f\\u0386\\u0388-\\u038a\\u038c\\u038e-\\u03a1\\u03a3-\\u03f5\\u03f7-\\u0481\\u048a-\\u052f\\u0531-\\u0556\\u0559\\u0561-\\u0587\\u05b0-\\u05bd\\u05bf\\u05c1-\\u05c2\\u05c4-\\u05c5\\u05c7\\u05d0-\\u05ea\\u05f0-\\u05f2\\u0610-\\u061a\\u0620-\\u0657\\u0659-\\u065f\\u066e-\\u06d3\\u06d5-\\u06dc\\u06e1-\\u06e8\\u06ed-\\u06ef\\u06fa-\\u06fc\\u06ff\\u0710-\\u073f\\u074d-\\u07b1\\u07ca-\\u07ea\\u07f4-\\u07f5\\u07fa\\u0800-\\u0817\\u081a-\\u082c\\u0840-\\u0858\\u08a0-\\u08b2\\u08e4-\\u08e9\\u08f0-\\u0950\\u0955-\\u0963\\u0971-\\u0983\\u0985-\\u098c\\u098f-\\u0990\\u0993-\\u09a8\\u09aa-\\u09b0\\u09b2\\u09b6-\\u09b9\\u09bc-\\u09c4\\u09c7-\\u09c8\\u09cb-\\u09ce\\u09d7\\u09dc-\\u09dd\\u09df-\\u09e3\\u09f0-\\u09f1\\u0a01-\\u0a03\\u0a05-\\u0a0a\\u0a0f-\\u0a10\\u0a13-\\u0a28\\u0a2a-\\u0a30\\u0a32-\\u0a33\\u0a35-\\u0a36\\u0a38-\\u0a39\\u0a3c\\u0a3e-\\u0a42\\u0a47-\\u0a48\\u0a4b-\\u0a4d\\u0a51\\u0a59-\\u0a5c\\u0a5e\\u0a70-\\u0a75\\u0a81-\\u0a83\\u0a85-\\u0a8d\\u0a8f-\\u0a91\\u0a93-\\u0aa8\\u0aaa-\\u0ab0\\u0ab2-\\u0ab3\\u0ab5-\\u0ab9\\u0abc-\\u0ac5\\u0ac7-\\u0ac9\\u0acb-\\u0acd\\u0ad0\\u0ae0-\\u0ae3\\u0afd-\\u0aff\\u0b01-\\u0b03\\u0b05-\\u0b0c\\u0b0f-\\u0b10\\u0b13-\\u0b28\\u0b2a-\\u0b30\\u0b32-\\u0b33\\u0b35-\\u0b39\\u0b3c-\\u0b44\\u0b47-\\u0b48\\u0b4b-\\u0b4d\\u0b56-\\u0b57\\u0b5c-\\u0b5d\\u0b5f-\\u0b63\\u0b71\\u0b82-\\u0b83\\u0b85-\\u0b8a\\u0b8e-\\u0b90\\u0b92-\\u0b95\\u0b99-\\u0b9a\\u0b9c\\u0b9e-\\u0b9f\\u0ba3-\\u0ba4\\u0ba8-\\u0baa\\u0bae-\\u0bb9\\u0bbe-\\u0bc2\\u0bc6-\\u0bc8\\u0bca-\\u0bcd\\u0bd0\\u0bd7\\u0c00-\\u0c03\\u0c05-\\u0c0c\\u0c0e-\\u0c10\\u0c12-\\u0c28\\u0c2a-\\u0c39\\u0c3d-\\u0c44\\u0c46-\\u0c48\\u0c4a-\\u0c4d\\u0c55-\\u0c56\\u0c58-\\u0c59\\u0c60-\\u0c63\\u0c81-\\u0c83\\u0c85-\\u0c8c\\u0c8e-\\u0c90\\u0c92-\\u0ca8\\u0caa-\\u0cb3\\u0cb5-\\u0cb9\\u0cbc-\\u0cc4\\u0cc6-\\u0cc8\\u0cca-\\u0ccd\\u0cd5-\\u0cd6\\u0cde\\u0ce0-\\u0ce3\\u0cf1-\\u0cf2\\u0d01-\\u0d03\\u0d05-\\u0d0c\\u0d0e-\\u0d10\\u0d12-\\u0d44\\u0d46-\\u0d48\\u0d4a-\\u0d4e\\u0d57\\u0d60-\\u0d63\\u0d7a-\\u0d7f\\u0d82-\\u0d83\\u0d85-\\u0d96\\u0d9a-\\u0db1\\u0db3-\\u0dbb\\u0dbd\\u0dc0-\\u0dc6\\u0dca\\u0dcf-\\u0dd4\\u0dd6\\u0dd8-\\u0ddf\\u0df2-\\u0df3\\u0e01-\\u0e3a\\u0e40-\\u0e46\\u0e4d\\u0e81-\\u0e82\\u0e84\\u0e87-\\u0e88\\u0e8a\\u0e8d\\u0e94-\\u0e97\\u0e99-\\u0e9f\\u0ea1-\\u0ea3\\u0ea5\\u0ea7\\u0eaa-\\u0eab\\u0ead-\\u0ebd\\u0ec0-\\u0ec4\\u0ec6\\u0ecd\\u0edc-\\u0edf\\u0f00\\u0f40-\\u0f47\\u0f49-\\u0f6c\\u0f71-\\u0f81\\u0f88-\\u0f97\\u0f99-\\u0fbc\\u1000-\\u1036\\u1038-\\u1039\\u103b-\\u103f\\u1050-\\u1062\\u1065-\\u1068\\u106e-\\u1086\\u108e\\u109c-\\u109d\\u10a0-\\u10c5\\u10c7\\u10cd\\u10d0-\\u10fa\\u10fc-\\u1248\\u124a-\\u124d\\u1250-\\u1256\\u1258\\u125a-\\u125d\\u1260-\\u1288\\u128a-\\u128d\\u1290-\\u12b0\\u12b2-\\u12b5\\u12b8-\\u12be\\u12c0\\u12c2-\\u12c5\\u12c8-\\u12d6\\u12d8-\\u1310\\u1312-\\u1315\\u1318-\\u135a\\u135f\\u1380-\\u138f\\u13a0-\\u13f4\\u1401-\\u166c\\u166f-\\u167f\\u1681-\\u169a\\u16a0-\\u16ea\\u16ee-\\u16f8\\u1700-\\u170c\\u170e-\\u1714\\u1720-\\u1733\\u1740-\\u1753\\u1760-\\u176c\\u176e-\\u1770\\u1772-\\u1773\\u1780-\\u17b3\\u17b6-\\u17c8\\u17d7\\u17dc\\u1820-\\u1877\\u1880-\\u18aa\\u18b0-\\u18f5\\u1900-\\u191e\\u1920-\\u192b\\u1930-\\u1938\\u1950-\\u196d\\u1970-\\u1974\\u1980-\\u19ab\\u19b0-\\u19c9\\u1a00-\\u1a1b\\u1a20-\\u1a5e\\u1a61-\\u1a74\\u1aa7\\u1b00-\\u1b33\\u1b35-\\u1b43\\u1b45-\\u1b4b\\u1b80-\\u1ba9\\u1bab-\\u1baf\\u1bba-\\u1be5\\u1be7-\\u1bf1\\u1c00-\\u1c35\\u1c37\\u1c4d-\\u1c4f\\u1c5a-\\u1c7d\\u1ce9-\\u1cec\\u1cee-\\u1cf3\\u1cf5-\\u1cf6\\u1d00-\\u1dbf\\u1de7-\\u1df4\\u1e00-\\u1f15\\u1f18-\\u1f1d\\u1f20-\\u1f45\\u1f48-\\u1f4d\\u1f50-\\u1f57\\u1f59\\u1f5b\\u1f5d\\u1f5f-\\u1f7d\\u1f80-\\u1fb4\\u1fb6-\\u1fbc\\u1fbe\\u1fc2-\\u1fc4\\u1fc6-\\u1fcc\\u1fd0-\\u1fd3\\u1fd6-\\u1fdb\\u1fe0-\\u1fec\\u1ff2-\\u1ff4\\u1ff6-\\u1ffc\\u2071\\u207f\\u2090-\\u209c\\u2102\\u2107\\u210a-\\u2113\\u2115\\u2119-\\u211d\\u2124\\u2126\\u2128\\u212a-\\u212d\\u212f-\\u2139\\u213c-\\u213f\\u2145-\\u2149\\u214e\\u2160-\\u2188\\u24b6-\\u24e9\\u25cc\\u2c00-\\u2c2e\\u2c30-\\u2c5e\\u2c60-\\u2ce4\\u2ceb-\\u2cee\\u2cf2-\\u2cf3\\u2d00-\\u2d25\\u2d27\\u2d2d\\u2d30-\\u2d67\\u2d6f\\u2d80-\\u2d96\\u2da0-\\u2da6\\u2da8-\\u2dae\\u2db0-\\u2db6\\u2db8-\\u2dbe\\u2dc0-\\u2dc6\\u2dc8-\\u2dce\\u2dd0-\\u2dd6\\u2dd8-\\u2dde\\u2de0-\\u2dff\\u2e2f\\u3006\\u3031-\\u3035\\u303c\\u3041-\\u3096\\u309d-\\u309f\\u30a1-\\u30fa\\u30fc-\\u30ff\\u3105-\\u312d\\u3131-\\u318e\\u31a0-\\u31ba\\u31f0-\\u31ff\\ua000-\\ua48c\\ua4d0-\\ua4fd\\ua500-\\ua60c\\ua610-\\ua61f\\ua62a-\\ua62b\\ua640-\\ua66e\\ua674-\\ua67b\\ua67f-\\ua69d\\ua69f-\\ua6ef\\ua717-\\ua71f\\ua722-\\ua788\\ua78b-\\ua78e\\ua790-\\ua7ad\\ua7b0-\\ua7b1\\ua7f7-\\ua801\\ua803-\\ua805\\ua807-\\ua80a\\ua80c-\\ua827\\ua840-\\ua873\\ua880-\\ua8c4\\ua8f2-\\ua8f7\\ua8fb\\ua90a-\\ua92a\\ua930-\\ua953\\ua960-\\ua97c\\ua980-\\ua9b2\\ua9b4-\\ua9bf\\ua9cf\\ua9e0-\\ua9e4\\ua9e6-\\ua9ef\\ua9fa-\\ua9fe\\uaa00-\\uaa36\\uaa40-\\uaa4d\\uaa60-\\uaa76\\uaa7a\\uaa7e-\\uaabe\\uaac0\\uaac2\\uaadb-\\uaadd\\uaae0-\\uaaef\\uaaf2-\\uaaf6\\uab01-\\uab06\\uab09-\\uab0e\\uab11-\\uab16\\uab20-\\uab26\\uab28-\\uab2e\\uab30-\\uab5a\\uab5c-\\uab5f\\uab64-\\uab65\\uabc0-\\uabea\\ud7b0-\\ud7c6\\ud7cb-\\ud7fb\\ufb00-\\ufb06\\ufb13-\\ufb17\\ufb1d-\\ufb28\\ufb2a-\\ufb36\\ufb38-\\ufb3c\\ufb3e\\ufb40-\\ufb41\\ufb43-\\ufb44\\ufb46-\\ufbb1\\ufbd3-\\ufd3d\\ufd50-\\ufd8f\\ufd92-\\ufdc7\\ufdf0-\\ufdfb\\ufe70-\\ufe74\\ufe76-\\ufefc\\uff21-\\uff3a\\uff41-\\uff5a\\uff66-\\uffbe\\uffc2-\\uffc7\\uffca-\\uffcf\\uffd2-\\uffd7\\uffda-\\uffdc\\x{10a3f}\\x{11046}\\x{110b9}-\\x{110ba}\\x{11133}\\x{11173}\\x{111c0}\\x{111ca}\\x{11235}-\\x{11236}\\x{112e9}-\\x{112ea}\\x{1133c}\\x{1134d}\\x{11442}\\x{11446}\\x{114c2}-\\x{114c3}\\x{115bf}-\\x{115c0}\\x{1163f}\\x{116b6}-\\x{116b7}\\x{11839}-\\x{1183a}\\x{119e0}\\x{11a34}\\x{11c3f}\\x{11d42}\\x{11d45}\\x{11d97}\\x{1e94a}"
    private const val alnumClass = "\\u0030-\\u0039\\u0041-\\u005a\\u0061-\\u007a\\u00aa\\u00b5\\u00ba\\u00c0-\\u00d6\\u00d8-\\u00f6\\u00f8-\\u02c1\\u02c6-\\u02d1\\u02e0-\\u02e4\\u02ec\\u02ee\\u0345\\u0370-\\u0374\\u0376-\\u0377\\u037a-\\u037d\\u037f\\u0386\\u0388-\\u038a\\u038c\\u038e-\\u03a1\\u03a3-\\u03f5\\u03f7-\\u0481\\u048a-\\u052f\\u0531-\\u0556\\u0559\\u0561-\\u0587\\u05b0-\\u05bd\\u05bf\\u05c1-\\u05c2\\u05c4-\\u05c5\\u05c7\\u05d0-\\u05ea\\u05f0-\\u05f2\\u0610-\\u061a\\u0620-\\u0657\\u0659-\\u0669\\u066e-\\u06d3\\u06d5-\\u06dc\\u06e1-\\u06e8\\u06ed-\\u06fc\\u06ff\\u0710-\\u073f\\u074d-\\u07b1\\u07c0-\\u07ea\\u07f4-\\u07f5\\u07fa\\u0800-\\u0817\\u081a-\\u082c\\u0840-\\u0858\\u08a0-\\u08b2\\u08e4-\\u08e9\\u08f0-\\u0950\\u0955-\\u0963\\u0966-\\u096f\\u0971-\\u0983\\u0985-\\u098c\\u098f-\\u0990\\u0993-\\u09a8\\u09aa-\\u09b0\\u09b2\\u09b6-\\u09b9\\u09bc-\\u09c4\\u09c7-\\u09c8\\u09cb-\\u09ce\\u09d7\\u09dc-\\u09dd\\u09df-\\u09e3\\u09e6-\\u09f1\\u0a01-\\u0a03\\u0a05-\\u0a0a\\u0a0f-\\u0a10\\u0a13-\\u0a28\\u0a2a-\\u0a30\\u0a32-\\u0a33\\u0a35-\\u0a36\\u0a38-\\u0a39\\u0a3c\\u0a3e-\\u0a42\\u0a47-\\u0a48\\u0a4b-\\u0a4d\\u0a51\\u0a59-\\u0a5c\\u0a5e\\u0a66-\\u0a75\\u0a81-\\u0a83\\u0a85-\\u0a8d\\u0a8f-\\u0a91\\u0a93-\\u0aa8\\u0aaa-\\u0ab0\\u0ab2-\\u0ab3\\u0ab5-\\u0ab9\\u0abc-\\u0ac5\\u0ac7-\\u0ac9\\u0acb-\\u0acd\\u0ad0\\u0ae0-\\u0ae3\\u0ae6-\\u0aef\\u0afd-\\u0aff\\u0b01-\\u0b03\\u0b05-\\u0b0c\\u0b0f-\\u0b10\\u0b13-\\u0b28\\u0b2a-\\u0b30\\u0b32-\\u0b33\\u0b35-\\u0b39\\u0b3c-\\u0b44\\u0b47-\\u0b48\\u0b4b-\\u0b4d\\u0b56-\\u0b57\\u0b5c-\\u0b5d\\u0b5f-\\u0b63\\u0b66-\\u0b6f\\u0b71\\u0b82-\\u0b83\\u0b85-\\u0b8a\\u0b8e-\\u0b90\\u0b92-\\u0b95\\u0b99-\\u0b9a\\u0b9c\\u0b9e-\\u0b9f\\u0ba3-\\u0ba4\\u0ba8-\\u0baa\\u0bae-\\u0bb9\\u0bbe-\\u0bc2\\u0bc6-\\u0bc8\\u0bca-\\u0bcd\\u0bd0\\u0bd7\\u0be6-\\u0bef\\u0c00-\\u0c03\\u0c05-\\u0c0c\\u0c0e-\\u0c10\\u0c12-\\u0c28\\u0c2a-\\u0c39\\u0c3d-\\u0c44\\u0c46-\\u0c48\\u0c4a-\\u0c4d\\u0c55-\\u0c56\\u0c58-\\u0c59\\u0c60-\\u0c63\\u0c66-\\u0c6f\\u0c81-\\u0c83\\u0c85-\\u0c8c\\u0c8e-\\u0c90\\u0c92-\\u0ca8\\u0caa-\\u0cb3\\u0cb5-\\u0cb9\\u0cbc-\\u0cc4\\u0cc6-\\u0cc8\\u0cca-\\u0ccd\\u0cd5-\\u0cd6\\u0cde\\u0ce0-\\u0ce3\\u0ce6-\\u0cef\\u0cf1-\\u0cf2\\u0d01-\\u0d03\\u0d05-\\u0d0c\\u0d0e-\\u0d10\\u0d12-\\u0d44\\u0d46-\\u0d48\\u0d4a-\\u0d4e\\u0d57\\u0d60-\\u0d63\\u0d66-\\u0d6f\\u0d7a-\\u0d7f\\u0d82-\\u0d83\\u0d85-\\u0d96\\u0d9a-\\u0db1\\u0db3-\\u0dbb\\u0dbd\\u0dc0-\\u0dc6\\u0dca\\u0dcf-\\u0dd4\\u0dd6\\u0dd8-\\u0ddf\\u0de6-\\u0def\\u0df2-\\u0df3\\u0e01-\\u0e3a\\u0e40-\\u0e46\\u0e4d\\u0e50-\\u0e59\\u0e81-\\u0e82\\u0e84\\u0e87-\\u0e88\\u0e8a\\u0e8d\\u0e94-\\u0e97\\u0e99-\\u0e9f\\u0ea1-\\u0ea3\\u0ea5\\u0ea7\\u0eaa-\\u0eab\\u0ead-\\u0ebd\\u0ec0-\\u0ec4\\u0ec6\\u0ecd\\u0ed0-\\u0ed9\\u0edc-\\u0edf\\u0f00\\u0f20-\\u0f29\\u0f40-\\u0f47\\u0f49-\\u0f6c\\u0f71-\\u0f81\\u0f88-\\u0f97\\u0f99-\\u0fbc\\u1000-\\u1036\\u1038-\\u1039\\u103b-\\u1049\\u1050-\\u1062\\u1065-\\u1068\\u106e-\\u1086\\u108e\\u1090-\\u1099\\u109c-\\u109d\\u10a0-\\u10c5\\u10c7\\u10cd\\u10d0-\\u10fa\\u10fc-\\u1248\\u124a-\\u124d\\u1250-\\u1256\\u1258\\u125a-\\u125d\\u1260-\\u1288\\u128a-\\u128d\\u1290-\\u12b0\\u12b2-\\u12b5\\u12b8-\\u12be\\u12c0\\u12c2-\\u12c5\\u12c8-\\u12d6\\u12d8-\\u1310\\u1312-\\u1315\\u1318-\\u135a\\u135f\\u1380-\\u138f\\u13a0-\\u13f4\\u1401-\\u166c\\u166f-\\u167f\\u1681-\\u169a\\u16a0-\\u16ea\\u16ee-\\u16f8\\u1700-\\u170c\\u170e-\\u1714\\u1720-\\u1733\\u1740-\\u1753\\u1760-\\u176c\\u176e-\\u1770\\u1772-\\u1773\\u1780-\\u17b3\\u17b6-\\u17c8\\u17d7\\u17dc\\u17e0-\\u17e9\\u1810-\\u1819\\u1820-\\u1877\\u1880-\\u18aa\\u18b0-\\u18f5\\u1900-\\u191e\\u1920-\\u192b\\u1930-\\u1938\\u1946-\\u196d\\u1970-\\u1974\\u1980-\\u19ab\\u19b0-\\u19c9\\u19d0-\\u19d9\\u1a00-\\u1a1b\\u1a20-\\u1a5e\\u1a61-\\u1a74\\u1a80-\\u1a89\\u1a90-\\u1a99\\u1aa7\\u1b00-\\u1b33\\u1b35-\\u1b43\\u1b45-\\u1b4b\\u1b50-\\u1b59\\u1b80-\\u1ba9\\u1bab-\\u1be5\\u1be7-\\u1bf1\\u1c00-\\u1c35\\u1c37\\u1c40-\\u1c49\\u1c4d-\\u1c7d\\u1ce9-\\u1cec\\u1cee-\\u1cf3\\u1cf5-\\u1cf6\\u1d00-\\u1dbf\\u1de7-\\u1df4\\u1e00-\\u1f15\\u1f18-\\u1f1d\\u1f20-\\u1f45\\u1f48-\\u1f4d\\u1f50-\\u1f57\\u1f59\\u1f5b\\u1f5d\\u1f5f-\\u1f7d\\u1f80-\\u1fb4\\u1fb6-\\u1fbc\\u1fbe\\u1fc2-\\u1fc4\\u1fc6-\\u1fcc\\u1fd0-\\u1fd3\\u1fd6-\\u1fdb\\u1fe0-\\u1fec\\u1ff2-\\u1ff4\\u1ff6-\\u1ffc\\u2071\\u207f\\u2090-\\u209c\\u2102\\u2107\\u210a-\\u2113\\u2115\\u2119-\\u211d\\u2124\\u2126\\u2128\\u212a-\\u212d\\u212f-\\u2139\\u213c-\\u213f\\u2145-\\u2149\\u214e\\u2160-\\u2188\\u24b6-\\u24e9\\u25cc\\u2c00-\\u2c2e\\u2c30-\\u2c5e\\u2c60-\\u2ce4\\u2ceb-\\u2cee\\u2cf2-\\u2cf3\\u2d00-\\u2d25\\u2d27\\u2d2d\\u2d30-\\u2d67\\u2d6f\\u2d80-\\u2d96\\u2da0-\\u2da6\\u2da8-\\u2dae\\u2db0-\\u2db6\\u2db8-\\u2dbe\\u2dc0-\\u2dc6\\u2dc8-\\u2dce\\u2dd0-\\u2dd6\\u2dd8-\\u2dde\\u2de0-\\u2dff\\u2e2f\\u3006\\u3031-\\u3035\\u303c\\u3041-\\u3096\\u309d-\\u309f\\u30a1-\\u30fa\\u30fc-\\u30ff\\u3105-\\u312d\\u3131-\\u318e\\u31a0-\\u31ba\\u31f0-\\u31ff\\ua000-\\ua48c\\ua4d0-\\ua4fd\\ua500-\\ua60c\\ua610-\\ua62b\\ua640-\\ua66e\\ua674-\\ua67b\\ua67f-\\ua69d\\ua69f-\\ua6ef\\ua717-\\ua71f\\ua722-\\ua788\\ua78b-\\ua78e\\ua790-\\ua7ad\\ua7b0-\\ua7b1\\ua7f7-\\ua801\\ua803-\\ua805\\ua807-\\ua80a\\ua80c-\\ua827\\ua840-\\ua873\\ua880-\\ua8c4\\ua8d0-\\ua8d9\\ua8f2-\\ua8f7\\ua8fb\\ua900-\\ua92a\\ua930-\\ua953\\ua960-\\ua97c\\ua980-\\ua9b2\\ua9b4-\\ua9bf\\ua9cf-\\ua9d9\\ua9e0-\\ua9e4\\ua9e6-\\ua9fe\\uaa00-\\uaa36\\uaa40-\\uaa4d\\uaa50-\\uaa59\\uaa60-\\uaa76\\uaa7a\\uaa7e-\\uaabe\\uaac0\\uaac2\\uaadb-\\uaadd\\uaae0-\\uaaef\\uaaf2-\\uaaf6\\uab01-\\uab06\\uab09-\\uab0e\\uab11-\\uab16\\uab20-\\uab26\\uab28-\\uab2e\\uab30-\\uab5a\\uab5c-\\uab5f\\uab64-\\uab65\\uabc0-\\uabea\\uabf0-\\uabf9\\ud7b0-\\ud7c6\\ud7cb-\\ud7fb\\ufb00-\\ufb06\\ufb13-\\ufb17\\ufb1d-\\ufb28\\ufb2a-\\ufb36\\ufb38-\\ufb3c\\ufb3e\\ufb40-\\ufb41\\ufb43-\\ufb44\\ufb46-\\ufbb1\\ufbd3-\\ufd3d\\ufd50-\\ufd8f\\ufd92-\\ufdc7\\ufdf0-\\ufdfb\\ufe70-\\ufe74\\ufe76-\\ufefc\\uff10-\\uff19\\uff21-\\uff3a\\uff41-\\uff5a\\uff66-\\uffbe\\uffc2-\\uffc7\\uffca-\\uffcf\\uffd2-\\uffd7\\uffda-\\uffdc\\x{10a3f}\\x{11046}\\x{110b9}-\\x{110ba}\\x{11133}\\x{11173}\\x{111c0}\\x{111ca}\\x{11235}-\\x{11236}\\x{112e9}-\\x{112ea}\\x{1133c}\\x{1134d}\\x{11442}\\x{11446}\\x{114c2}-\\x{114c3}\\x{115bf}-\\x{115c0}\\x{1163f}\\x{116b6}-\\x{116b7}\\x{11839}-\\x{1183a}\\x{119e0}\\x{11a34}\\x{11c3f}\\x{11d42}\\x{11d45}\\x{11d97}\\x{1e94a}"
    private const val numberClass = "\\u000a\\u0030-\\u0039\\u00b2-\\u00b3\\u00b9\\u00bc-\\u00be\\u0660-\\u0669\\u06f0-\\u06f9\\u07c0-\\u07c9\\u0966-\\u096f\\u09e6-\\u09ef\\u09f4-\\u09f9\\u0a66-\\u0a6f\\u0ae6-\\u0aef\\u0b66-\\u0b6f\\u0b72-\\u0b77\\u0be6-\\u0bf2\\u0c66-\\u0c6f\\u0c78-\\u0c7e\\u0ce6-\\u0cef\\u0d66-\\u0d75\\u0de6-\\u0def\\u0e50-\\u0e59\\u0ed0-\\u0ed9\\u0f20-\\u0f33\\u1040-\\u1049\\u1090-\\u1099\\u1369-\\u137c\\u16ee-\\u16f0\\u17e0-\\u17e9\\u17f0-\\u17f9\\u1810-\\u1819\\u1946-\\u194f\\u19d0-\\u19da\\u1a80-\\u1a89\\u1a90-\\u1a99\\u1b50-\\u1b59\\u1bb0-\\u1bb9\\u1c40-\\u1c49\\u1c50-\\u1c59\\u2070\\u2074-\\u2079\\u2080-\\u2089\\u2150-\\u2182\\u2185-\\u2189\\u2460-\\u249b\\u24ea-\\u24ff\\u2776-\\u2793\\u2cfd\\u3192-\\u3195\\u3220-\\u3229\\u3248-\\u324f\\u3251-\\u325f\\u3280-\\u3289\\u32b1-\\u32bf\\ua620-\\ua629\\ua6e6-\\ua6ef\\ua830-\\ua835\\ua8d0-\\ua8d9\\ua900-\\ua909\\ua9d0-\\ua9d9\\ua9f0-\\ua9f9\\uaa50-\\uaa59\\uabf0-\\uabf9\\uff10-\\uff19"
    private const val lowerClass = "\\u0061-\\u007a\\u00aa\\u00b5\\u00ba\\u00df-\\u00f6\\u00f8-\\u00ff\\u0101\\u0103\\u0105\\u0107\\u0109\\u010b\\u010d\\u010f\\u0111\\u0113\\u0115\\u0117\\u0119\\u011b\\u011d\\u011f\\u0121\\u0123\\u0125\\u0127\\u0129\\u012b\\u012d\\u012f\\u0131\\u0133\\u0135\\u0137-\\u0138\\u013a\\u013c\\u013e\\u0140\\u0142\\u0144\\u0146\\u0148-\\u0149\\u014b\\u014d\\u014f\\u0151\\u0153\\u0155\\u0157\\u0159\\u015b\\u015d\\u015f\\u0161\\u0163\\u0165\\u0167\\u0169\\u016b\\u016d\\u016f\\u0171\\u0173\\u0175\\u0177\\u017a\\u017c\\u017e-\\u0180\\u0183\\u0185\\u0188\\u018c-\\u018d\\u0192\\u0195\\u0199-\\u019b\\u019e\\u01a1\\u01a3\\u01a5\\u01a8\\u01aa-\\u01ab\\u01ad\\u01b0\\u01b4\\u01b6\\u01b9-\\u01ba\\u01bd-\\u01bf\\u01c6\\u01c9\\u01cc\\u01ce\\u01d0\\u01d2\\u01d4\\u01d6\\u01d8\\u01da\\u01dc-\\u01dd\\u01df\\u01e1\\u01e3\\u01e5\\u01e7\\u01e9\\u01eb\\u01ed\\u01ef-\\u01f0\\u01f3\\u01f5\\u01f9\\u01fb\\u01fd\\u01ff\\u0201\\u0203\\u0205\\u0207\\u0209\\u020b\\u020d\\u020f\\u0211\\u0213\\u0215\\u0217\\u0219\\u021b\\u021d\\u021f\\u0221\\u0223\\u0225\\u0227\\u0229\\u022b\\u022d\\u022f\\u0231\\u0233-\\u0239\\u023c\\u023f-\\u0240\\u0242\\u0247\\u0249\\u024b\\u024d\\u024f-\\u0293\\u0295-\\u02b8\\u02c0-\\u02c1\\u02e0-\\u02e4\\u0345\\u0371\\u0373\\u0377\\u037a-\\u037d\\u0390\\u03ac-\\u03ce\\u03d0-\\u03d1\\u03d5-\\u03d7\\u03d9\\u03db\\u03dd\\u03df\\u03e1\\u03e3\\u03e5\\u03e7\\u03e9\\u03eb\\u03ed\\u03ef-\\u03f3\\u03f5\\u03f8\\u03fb-\\u03fc\\u0430-\\u045f\\u0461\\u0463\\u0465\\u0467\\u0469\\u046b\\u046d\\u046f\\u0471\\u0473\\u0475\\u0477\\u0479\\u047b\\u047d\\u047f\\u0481\\u048b\\u048d\\u048f\\u0491\\u0493\\u0495\\u0497\\u0499\\u049b\\u049d\\u049f\\u04a1\\u04a3\\u04a5\\u04a7\\u04a9\\u04ab\\u04ad\\u04af\\u04b1\\u04b3\\u04b5\\u04b7\\u04b9\\u04bb\\u04bd\\u04bf\\u04c2\\u04c4\\u04c6\\u04c8\\u04ca\\u04cc\\u04ce-\\u04cf\\u04d1\\u04d3\\u04d5\\u04d7\\u04d9\\u04db\\u04dd\\u04df\\u04e1\\u04e3\\u04e5\\u04e7\\u04e9\\u04eb\\u04ed\\u04ef\\u04f1\\u04f3\\u04f5\\u04f7\\u04f9\\u04fb\\u04fd\\u04ff\\u0501\\u0503\\u0505\\u0507\\u0509\\u050b\\u050d\\u050f\\u0511\\u0513\\u0515\\u0517\\u0519\\u051b\\u051d\\u051f\\u0521\\u0523\\u0525\\u0527\\u0529\\u052b\\u052d\\u052f\\u0561-\\u0587\\u13f8-\\u13fd\\u1c80-\\u1c88\\u1d00-\\u1dbf\\u1e01\\u1e03\\u1e05\\u1e07\\u1e09\\u1e0b\\u1e0d\\u1e0f\\u1e11\\u1e13\\u1e15\\u1e17\\u1e19\\u1e1b\\u1e1d\\u1e1f\\u1e21\\u1e23\\u1e25\\u1e27\\u1e29\\u1e2b\\u1e2d\\u1e2f\\u1e31\\u1e33\\u1e35\\u1e37\\u1e39\\u1e3b\\u1e3d\\u1e3f\\u1e41\\u1e43\\u1e45\\u1e47\\u1e49\\u1e4b\\u1e4d\\u1e4f\\u1e51\\u1e53\\u1e55\\u1e57\\u1e59\\u1e5b\\u1e5d\\u1e5f\\u1e61\\u1e63\\u1e65\\u1e67\\u1e69\\u1e6b\\u1e6d\\u1e6f\\u1e71\\u1e73\\u1e75\\u1e77\\u1e79\\u1e7b\\u1e7d\\u1e7f\\u1e81\\u1e83\\u1e85\\u1e87\\u1e89\\u1e8b\\u1e8d\\u1e8f\\u1e91\\u1e93\\u1e95-\\u1e9d\\u1e9f\\u1ea1\\u1ea3\\u1ea5\\u1ea7\\u1ea9\\u1eab\\u1ead\\u1eaf\\u1eb1\\u1eb3\\u1eb5\\u1eb7\\u1eb9\\u1ebb\\u1ebd\\u1ebf\\u1ec1\\u1ec3\\u1ec5\\u1ec7\\u1ec9\\u1ecb\\u1ecd\\u1ecf\\u1ed1\\u1ed3\\u1ed5\\u1ed7\\u1ed9\\u1edb\\u1edd\\u1edf\\u1ee1\\u1ee3\\u1ee5\\u1ee7\\u1ee9\\u1eeb\\u1eed\\u1eef\\u1ef1\\u1ef3\\u1ef5\\u1ef7\\u1ef9\\u1efb\\u1efd\\u1eff-\\u1f07\\u1f10-\\u1f15\\u1f20-\\u1f27\\u1f30-\\u1f37\\u1f40-\\u1f45\\u1f50-\\u1f57\\u1f60-\\u1f67\\u1f70-\\u1f7d\\u1f80-\\u1f87\\u1f90-\\u1f97\\u1fa0-\\u1fa7\\u1fb0-\\u1fb4\\u1fb6-\\u1fb7\\u1fbe\\u1fc2-\\u1fc4\\u1fc6-\\u1fc7\\u1fd0-\\u1fd3\\u1fd6-\\u1fd7\\u1fe0-\\u1fe7\\u1ff2-\\u1ff4\\u1ff6-\\u1ff7\\u2071\\u207f\\u2090-\\u209c\\u210a\\u210e-\\u210f\\u2113\\u212f\\u2134\\u2139\\u213c-\\u213d\\u2146-\\u2149\\u214e\\u2170-\\u217f\\u2184\\u24d0-\\u24e9\\u25cc\\u2c30-\\u2c5e\\u2c61\\u2c65-\\u2c66\\u2c68\\u2c6a\\u2c6c\\u2c71\\u2c73-\\u2c74\\u2c76-\\u2c7d\\u2c81\\u2c83\\u2c85\\u2c87\\u2c89\\u2c8b\\u2c8d\\u2c8f\\u2c91\\u2c93\\u2c95\\u2c97\\u2c99\\u2c9b\\u2c9d\\u2c9f\\u2ca1\\u2ca3\\u2ca5\\u2ca7\\u2ca9\\u2cab\\u2cad\\u2caf\\u2cb1\\u2cb3\\u2cb5\\u2cb7\\u2cb9\\u2cbb\\u2cbd\\u2cbf\\u2cc1\\u2cc3\\u2cc5\\u2cc7\\u2cc9\\u2ccb\\u2ccd\\u2ccf\\u2cd1\\u2cd3\\u2cd5\\u2cd7\\u2cd9\\u2cdb\\u2cdd\\u2cdf\\u2ce1\\u2ce3-\\u2ce4\\u2cec\\u2cee\\u2cf3\\u2d00-\\u2d25\\u2d27\\u2d2d\\ua641\\ua643\\ua645\\ua647\\ua649\\ua64b\\ua64d\\ua64f\\ua651\\ua653\\ua655\\ua657\\ua659\\ua65b\\ua65d\\ua65f\\ua661\\ua663\\ua665\\ua667\\ua669\\ua66b\\ua66d\\ua681\\ua683\\ua685\\ua687\\ua689\\ua68b\\ua68d\\ua68f\\ua691\\ua693\\ua695\\ua697\\ua699\\ua69b-\\ua69d\\ua723\\ua725\\ua727\\ua729\\ua72b\\ua72d\\ua72f-\\ua731\\ua733\\ua735\\ua737\\ua739\\ua73b\\ua73d\\ua73f\\ua741\\ua743\\ua745\\ua747\\ua749\\ua74b\\ua74d\\ua74f\\ua751\\ua753\\ua755\\ua757\\ua759\\ua75b\\ua75d\\ua75f\\ua761\\ua763\\ua765\\ua767\\ua769\\ua76b\\ua76d\\ua76f-\\ua778\\ua77a\\ua77c\\ua77f\\ua781\\ua783\\ua785\\ua787\\ua78c\\ua78e\\ua791\\ua793-\\ua795\\ua797\\ua799\\ua79b\\ua79d\\ua79f\\ua7a1\\ua7a3\\ua7a5\\ua7a7\\ua7a9\\ua7b5\\ua7b7\\ua7f8-\\ua7fa\\uab30-\\uab5a\\uab5c-\\uab65\\uab70-\\uabbf\\ufb00-\\ufb06\\ufb13-\\ufb17\\uff41-\\uff5a\\x{10428}-\\x{1044f}\\x{104d8}-\\x{104fb}\\x{10cc0}-\\x{10cf2}\\x{118c0}-\\x{118df}\\x{1d41a}-\\x{1d433}\\x{1d44e}-\\x{1d454}\\x{1d456}-\\x{1d467}\\x{1d482}-\\x{1d49b}\\x{1d4b6}-\\x{1d4b9}\\x{1d4bb}\\x{1d4bd}-\\x{1d4c3}\\x{1d4c5}-\\x{1d4cf}\\x{1d4ea}-\\x{1d503}\\x{1d51e}-\\x{1d537}\\x{1d552}-\\x{1d56b}\\x{1d586}-\\x{1d59f}\\x{1d5ba}-\\x{1d5d3}\\x{1d5ee}-\\x{1d607}\\x{1d622}-\\x{1d63b}\\x{1d656}-\\x{1d66f}\\x{1d68a}-\\x{1d6a5}\\x{1d6c2}-\\x{1d6da}\\x{1d6dc}-\\x{1d6e1}\\x{1d6fc}-\\x{1d714}\\x{1d716}-\\x{1d71b}\\x{1d736}-\\x{1d74e}\\x{1d750}-\\x{1d755}\\x{1d770}-\\x{1d788}\\x{1d78a}-\\x{1d78f}\\x{1d7aa}-\\x{1d7c2}\\x{1d7c4}-\\x{1d7c9}\\x{1d7cb}\\x{1e922}-\\x{1e943}"
    private val alphaRegex = rx("[$alphaClass]")
    private val lowerRegex = rx("[$lowerClass]")
    private val nonbreakingPrefixes = setOf("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z", "Adj", "Adm", "Adv", "Asst", "Bart", "Bldg", "Brig", "Bros", "Capt", "Cmdr", "Col", "Comdr", "Con", "Corp", "Cpl", "DR", "Dr", "Drs", "Ens", "Gen", "Gov", "Hon", "Hr", "Hosp", "Insp", "Lt", "MM", "MR", "MRS", "MS", "Maj", "Messrs", "Mlle", "Mme", "Mr", "Mrs", "Ms", "Msgr", "Op", "Ord", "Pfc", "Ph", "Prof", "Pvt", "Rep", "Reps", "Res", "Rev", "Rt", "Sen", "Sens", "Sfc", "Sgt", "Sr", "St", "Supt", "Surg", "v", "vs", "i.e", "rev", "e.g", "Rs", "Nos", "Nr", "Jan", "Feb", "Mar", "Apr", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val numericPrefixes = setOf("No", "Art", "pp")
    private val punctuationRules = listOf(
        rx("\\r") to "",
        rx("\\(\\s*") to "(",
        rx("\\s*\\)") to ")",
        rx("\\s:\\s?") to ":",
        rx("\\s;\\s?") to ";",
        rx("[`´‘‚’]") to "'",
        rx("[„“”«»]") to "\"",
        rx("[–—]") to "-",
        rx("\\.\\.\\.") to "...",
        rx(" %") to "%",
        rx("nº ") to "nº ",
        rx(" ºC") to " ºC",
        rx(" ([?!;])") to "\$1",
        rx(", ") to ", ",
    )
    private val mosesNormalization = listOf(
        rx("\\r") to "",
        rx("\\(") to " (",
        rx("\\)") to ") ",
        rx(" +") to " ",
        rx("\\) ([.!:?;,])") to ")\$1",
        rx("\\( ") to "(",
        rx(" \\)") to ")",
        rx("(\\d) %") to "\$1%",
        rx(" :") to ":",
        rx(" ;") to ";",
        rx("`") to "'",
        rx("''") to " \" ",
        rx("„") to "\"",
        rx("“") to "\"",
        rx("”") to "\"",
        rx("–") to "-",
        rx("—") to " - ",
        rx(" +") to " ",
        rx("´") to "'",
        rx("([a-zA-Z])‘([a-zA-Z])") to "\$1'\$2",
        rx("([a-zA-Z])’([a-zA-Z])") to "\$1'\$2",
        rx("‘") to "'",
        rx("‚") to "'",
        rx("’") to "'",
        rx("''") to "\"",
        rx("´´") to "\"",
        rx("…") to "...",
        rx(" « ") to "\"",
        rx("« ") to "\"",
        rx("«") to "\"",
        rx(" » ") to "\"",
        rx(" »") to "\"",
        rx("»") to "\"",
        rx(" %") to "%",
        rx("nº ") to "nº ",
        rx(" :") to ":",
        rx(" ºC") to " ºC",
        rx(" cm") to " cm",
        rx(" \\?") to "?",
        rx(" \\!") to "!",
        rx(" ;") to ";",
        rx(", ") to ", ",
        rx(" +") to " ",
        rx("\"([,.]+)") to "\$1\"",
        rx("(\\d) (\\d)") to "\$1.\$2",
    )
}
