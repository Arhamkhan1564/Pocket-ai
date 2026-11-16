package com.pocketai.assistant

import java.text.Normalizer
import java.util.Locale

/** Product rules run before a model. A retrieved leaflet is evidence, not permission to prescribe. */
object MedicalGuidance {
    enum class Intent { NORMAL, MEDICINE_INFORMATION, PERSONAL_TREATMENT, URGENT }
    data class Assessment(val intent: Intent, val reason: String = "")

    private val medicine = Regex(
        "\\b(?:medicines?|medications?|drugs?|tablets?|capsules?|pills?|antibiotics?|doses?|dosages?|prescrib\\w*|side effects?|contraindication\\w*|" +
            "aspirin|paracetamol|acetaminophen|ibuprofen|amoxicillin|insulin|metformin|warfarin|tylenol|panadol|dawai|dawa|davai|goli|khurak)\\b|" +
            "دوا|دوائی|دوائ|گولی|خوراک|اینٹی بائیوٹک|پراسیٹامول|پیراسیٹامول|اسپرین|" +
            "ਦਵਾਈ|ਦਵਾ|ਗੋਲੀ|ਖੁਰਾਕ|ਐਂਟੀਬਾਇਓਟਿਕ|ਪੈਰਾਸੀਟਾਮੋਲ|ਐਸਪਰੀਨ",
        RegexOption.IGNORE_CASE
    )
    private val health = Regex(
        "\\b(?:symptoms?|treat\\w*|diagnos\\w*|clinical|pharmac\\w*|medical|patient leaflet|patient information|" +
            "headache|fever|cough|pain|sneez\\w*|overdose|poisoning|pregnan\\w*|allerg\\w*|breastfeed\\w*|bukhar|dard|khansi|saans)\\b|" +
            "علامات|علاج|بخار|درد|کھانسی|حاملہ|حمل|الرجی|بیمار|مریض|" +
            "ਲੱਛਣ|ਇਲਾਜ|ਬੁਖਾਰ|ਦਰਦ|ਖੰਘ|ਗਰਭ|ਐਲਰਜੀ|ਮਰੀਜ਼",
        RegexOption.IGNORE_CASE
    )
    private val referenceQuestion = Regex(
        "\\b(?:document|leaflet|reference|book|pdf|text|source|listed|definition|meaning|what is|what are|what causes|uses of|used for|explain|describe)\\b|" +
            "کتاب|دستاویز|حوالہ|پرچہ|مطلب|تعریف|استعمال|ਕੀ ਹੈ|ਕੀ ਹੁੰਦਾ|ਕਿਤਾਬ|ਪਰਚੇ|ਦਸਤਾਵੇਜ਼|ਮਤਲਬ|ਵਰਤੋਂ",
        RegexOption.IGNORE_CASE
    )
    private val medicineChoice = Regex(
        "\\b(?:which|what)\\s+(?:\\w+\\s+){0,2}(?:medicines?|medications?|drugs?|tablets?|pills?|antibiotics?)\\b|" +
            "\\b(?:should|can|may)\\s+(?:i|we|my child|my baby|my patient|the patient)\\s+(?:take|use|give)|" +
            "\\b(?:how much|how many|what dose)\\b|\\bprescribe\\b|\\b(?:konsi|kaunsi|kon si|kaun si|kehri|kehre)\\s+(?:dawai|dawa|davai|goli)\\b|" +
            "کون سی دوا|کونسی دوا|کون سا علاج|کونسی گولی|کیا لوں|کتنی خوراک|کتنی دوا|کیہڑی دوا|کیہڑی گولی|" +
            "ਕਿਹੜੀ ਦਵਾਈ|ਕਿਹੜਾ ਇਲਾਜ|ਕੀ ਲਵਾਂ|ਕਿੰਨੀ ਖੁਰਾਕ|ਕਿੰਨੀ ਦਵਾਈ",
        RegexOption.IGNORE_CASE
    )
    private val redFlags = listOf(
        "breathing" to Regex("\\b(?:cannot|can't|unable to|not able to|struggling to)\\s+breathe\\b|\\b(?:severe|serious)\\s+(?:breathing difficulty|difficulty breathing|shortness of breath)\\b|\\bchoking\\b|\\bsaans (?:nahi|nahin|nhi|ni) (?:aa|a |le)|سانس (?:نہیں آ|نہیں لے|لینے میں شدید|بہت مشکل)|سانس لین وچ بہت اوکھ|ਸਾਹ (?:ਨਹੀਂ|ਲੈਣ ਵਿੱਚ ਬਹੁਤ ਔਖ)", RegexOption.IGNORE_CASE),
        "chest pain" to Regex("\\b(?:severe\\s+)?chest pain\\b|\\bmy chest hurts\\b|\\b(?:seene|sine|seena|chati) (?:mein|me|vich|wich) (?:shadeed |tez )?dard\\b|سینے (?:میں|وچ) (?:شدید )?درد|ਛਾਤੀ (?:ਵਿੱਚ|ਵਿਚ) (?:ਤੇਜ਼ |ਤੀਖਾ )?ਦਰਦ", RegexOption.IGNORE_CASE),
        "overdose or poisoning" to Regex("\\b(?:overdosed|overdose|poisoning|swallowed poison|took too many (?:pills|tablets|medicines?))\\b|دوا کی زیادہ مقدار|زیادہ گولیاں|زہر (?:کھا|پی|نگل)|دوا دی ودھ مقدار|ਦਵਾਈ ਦੀ ਵੱਧ ਮਾਤਰਾ|ਜ਼ਹਿਰ (?:ਪੀ|ਖਾ|ਨਿਗਲ)", RegexOption.IGNORE_CASE)
    )

    fun assess(question: String): Assessment {
        val text = normalize(question)
        val emergencyReport = Regex("\\b(?:i (?:have|am having|can't|cannot|am unable|am struggling)|my (?:chest|child|baby|patient|father|mother)|the patient|someone|took too many|overdosed)\\b|" +
            "مجھے|میرے سینے|میرے بچے|مینوں|ਮੈਨੂੰ|ਮੇਰੇ ਬੱਚੇ|\\b(?:mujhe|mujhay|mujhy|mainu|menu|mere seene)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val educationalEmergencyQuery = referenceQuestion.containsMatchIn(text) ||
            Regex("\\btell me about\\b|\\bwhat does .{0,50}mean\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
        for ((reason, pattern) in redFlags) {
            val active = pattern.findAll(text).any { !isNegated(text, it.range) }
            if (active && (emergencyReport || !educationalEmergencyQuery)) return Assessment(Intent.URGENT, reason)
        }
        val medicineMentioned = medicine.containsMatchIn(text)
        val choosingMedicine = medicineChoice.containsMatchIn(text)
        val symptoms = Regex("\\b(?:fever|headache|cough|pain|sneez\\w*|vomit\\w*|nausea|dizz\\w*|rash|bukhar|dard|khansi)\\b|بخار|درد|کھانسی|الٹی|چکر|ਬੁਖਾਰ|ਦਰਦ|ਖੰਘ|ਉਲਟੀ", RegexOption.IGNORE_CASE)
        val reportsSymptoms = Regex("\\b(?:i have|i am having|i feel|my (?:child|baby|patient|father|mother|son|daughter) has|(?:the |a )?patient has|we have)\\b|" +
            "مجھے.{0,60}(?:ہے|ہیں|ہو رہا)|میرے بچے کو.{0,60}(?:ہے|ہیں)|مینوں.{0,60}(?:اے|ہے)|" +
            "ਮੈਨੂੰ.{0,60}ਹੈ|ਮੇਰੇ ਬੱਚੇ ਨੂੰ.{0,60}ਹੈ|\\b(?:mujhe|mujhay|mujhy|mainu|menu)\\b.{0,60}\\b(?:hai|hae|aa|ae)\\b", RegexOption.IGNORE_CASE)
        val activeSymptomReport = reportsSymptoms.containsMatchIn(text) && symptoms.findAll(text).any { !isNegated(text, it.range) }
        val explicitPatientChoice = Regex("\\b(?:should|can|may|could)\\s+(?:i|we|my child|my baby|my patient|the patient)\\s+(?:take|use|give)|" +
            "\\b(?:i|we)\\s+(?:should take|want to take|need a medicine)|\\b(?:safe|suitable|okay)\\s+for\\s+(?:me|my child)|" +
            "کیا (?:میں|بچے کو).{0,40}(?:لوں|دوں|لے سکت|لینا چاہیے)|کیا لوں|ਕੀ ਲਵਾਂ|" +
            "\\b(?:mujhe|mainu|menu|main)\\b.{0,50}\\b(?:loon|lu|lawa|lena|leni)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
        if (activeSymptomReport || (explicitPatientChoice && medicineMentioned) ||
            (choosingMedicine && !referenceQuestion.containsMatchIn(text))) return Assessment(Intent.PERSONAL_TREATMENT)
        if (medicineMentioned || health.containsMatchIn(text)) return Assessment(Intent.MEDICINE_INFORMATION)
        return Assessment(Intent.NORMAL)
    }

    fun isMedicalReference(text: String): Boolean = medicine.containsMatchIn(normalize(text)) || health.containsMatchIn(normalize(text))

    fun isPatientContextReply(text: String): Boolean = Regex(
        "\\b(?:years? old|age|pregnan\\w*|breastfeed\\w*|allerg\\w*|currently taking|current medication|kidney|liver)\\b|" +
            "عمر|سال|حاملہ|حمل|الرجی|دودھ پلات|گردے|جگر|ਉਮਰ|ਸਾਲ|ਗਰਭ|ਐਲਰਜੀ|ਦੁੱਧ ਪਿਲਾ|ਗੁਰਦੇ|ਜਿਗਰ",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(text)

    fun urgentMessage(languageTag: String): String = localized(languageTag,
        "Serious breathing difficulty, chest pain, or suspected poisoning/overdose can be an emergency. Call your local emergency number or poison centre now, or ask someone to get urgent medical help. Do not wait for Pocket AI or try a new medicine. If safe, bring the medicine container/list.",
        "سانس لینے میں بہت دشواری، سینے کا درد، یا زہر/دوا کی زیادہ مقدار ہنگامی حالت ہو سکتی ہے۔ ابھی مقامی ایمرجنسی نمبر یا زہر سے متعلق ہیلپ لائن پر رابطہ کریں، یا کسی سے فوری طبی مدد بلانے کو کہیں۔ پاکٹ اے آئی کے جواب یا نئی دوا آزمانے کا انتظار نہ کریں۔ اگر محفوظ ہو تو دوا کا پیکٹ/فہرست ساتھ رکھیں۔",
        "سانس لین وچ بہت اوکھ، سینے دا درد، یا زہر/دوا دی ودھ مقدار ہنگامی حالت ہو سکدی اے۔ ہُن مقامی ایمرجنسی نمبر یا زہر دی ہیلپ لائن نوں فون کرو تے فوری طبی مدد منگواؤ۔ پاکٹ اے آئی یا نویں دوا دی اُڈیک نہ کرو۔ جے محفوظ ہووے تاں دوا دا پیکٹ/فہرست نال رکھو۔",
        "ਸਾਹ ਲੈਣ ਵਿੱਚ ਬਹੁਤ ਔਖ, ਛਾਤੀ ਦਾ ਦਰਦ ਜਾਂ ਜ਼ਹਿਰ/ਦਵਾਈ ਦੀ ਵੱਧ ਮਾਤਰਾ ਐਮਰਜੈਂਸੀ ਹੋ ਸਕਦੀ ਹੈ। ਹੁਣੇ ਸਥਾਨਕ ਐਮਰਜੈਂਸੀ ਨੰਬਰ ਜਾਂ ਜ਼ਹਿਰ ਸਹਾਇਤਾ ਕੇਂਦਰ ਨੂੰ ਫ਼ੋਨ ਕਰੋ ਅਤੇ ਤੁਰੰਤ ਡਾਕਟਰੀ ਮਦਦ ਲਵੋ। ਪਾਕੇਟ ਏਆਈ ਜਾਂ ਨਵੀਂ ਦਵਾਈ ਦੀ ਉਡੀਕ ਨਾ ਕਰੋ। ਜੇ ਸੁਰੱਖਿਅਤ ਹੋਵੇ ਤਾਂ ਦਵਾਈ ਦਾ ਪੈਕਟ/ਸੂਚੀ ਨਾਲ ਰੱਖੋ।"
    )

    fun treatmentMessage(languageTag: String, hasSources: Boolean): String = localized(languageTag,
        "Symptoms alone do not establish a diagnosis or a safe medicine choice. What is the person's age, symptoms and their duration/severity, pregnancy/breastfeeding status, allergies, current medicines/supplements, and important conditions such as kidney or liver disease? A clinician or pharmacist should check these before choosing a medicine or dose. " + if (hasSources) "I can explain the saved references below; they are not a personal prescription." else "I have no matching saved medical reference to explain.",
        "صرف علامات سے تشخیص یا محفوظ دوا کا انتخاب نہیں ہو سکتا۔ عمر، علامات اور ان کی مدت/شدت، حمل یا دودھ پلانے کی حالت، الرجی، موجودہ ادویات/سپلیمنٹس اور گردے یا جگر جیسی اہم بیماریوں کی تفصیل بتائیں۔ دوا یا خوراک کے انتخاب سے پہلے ڈاکٹر یا فارماسسٹ سے جانچ کروائیں۔ " + if (hasSources) "نیچے محفوظ حوالہ موجود ہے؛ یہ ذاتی نسخہ نہیں ہے۔" else "وضاحت کے لیے کوئی متعلقہ محفوظ طبی حوالہ نہیں ملا۔",
        "صرف علامتاں نال تشخیص یا محفوظ دوا دا انتخاب نہیں ہو سکدا۔ عمر، علامتاں تے اوہناں دی مدت/شدت، حمل یا دودھ پلاؤن دی حالت، الرجی، موجودہ دواواں/سپلیمنٹس تے گردے یا جگر ورگیاں اہم بیماریاں دسو۔ دوا یا خوراک چُنن توں پہلاں ڈاکٹر یا فارماسسٹ توں جانچ کراؤ۔ " + if (hasSources) "تھلے محفوظ حوالہ اے؛ ایہہ ذاتی نسخہ نہیں۔" else "وضاحت لئی کوئی متعلقہ محفوظ طبی حوالہ نہیں لبھیا۔",
        "ਸਿਰਫ਼ ਲੱਛਣਾਂ ਨਾਲ ਨਿਦਾਨ ਜਾਂ ਸੁਰੱਖਿਅਤ ਦਵਾਈ ਦੀ ਚੋਣ ਨਹੀਂ ਹੋ ਸਕਦੀ। ਉਮਰ, ਲੱਛਣ ਅਤੇ ਉਹਨਾਂ ਦੀ ਮਿਆਦ/ਗੰਭੀਰਤਾ, ਗਰਭ ਜਾਂ ਦੁੱਧ ਪਿਲਾਉਣ ਦੀ ਹਾਲਤ, ਐਲਰਜੀਆਂ, ਮੌਜੂਦਾ ਦਵਾਈਆਂ/ਸਪਲੀਮੈਂਟ ਅਤੇ ਗੁਰਦੇ ਜਾਂ ਜਿਗਰ ਵਰਗੀਆਂ ਅਹਿਮ ਬਿਮਾਰੀਆਂ ਦੱਸੋ। ਦਵਾਈ ਜਾਂ ਖੁਰਾਕ ਚੁਣਨ ਤੋਂ ਪਹਿਲਾਂ ਡਾਕਟਰ ਜਾਂ ਫਾਰਮਾਸਿਸਟ ਤੋਂ ਜਾਂਚ ਕਰਵਾਓ। " + if (hasSources) "ਹੇਠਾਂ ਸੰਭਾਲਿਆ ਹਵਾਲਾ ਹੈ; ਇਹ ਨਿੱਜੀ ਨੁਸਖ਼ਾ ਨਹੀਂ ਹੈ।" else "ਸਮਝਾਉਣ ਲਈ ਕੋਈ ਸੰਬੰਧਿਤ ਸੰਭਾਲਿਆ ਮੈਡੀਕਲ ਹਵਾਲਾ ਨਹੀਂ ਮਿਲਿਆ।"
    )

    fun noSourcesMessage(languageTag: String): String = localized(languageTag,
        "I found no matching saved medical reference. Import a reliable medicine document or ask a clinician/pharmacist; I will not invent a diagnosis, medicine, or dose.",
        "کوئی متعلقہ محفوظ طبی حوالہ نہیں ملا۔ مستند دوا کی دستاویز شامل کریں یا ڈاکٹر/فارماسسٹ سے پوچھیں؛ میں تشخیص، دوا یا خوراک خود نہیں بناؤں گا۔",
        "کوئی متعلقہ محفوظ طبی حوالہ نہیں لبھیا۔ مستند دوا دی دستاویز شامل کرو یا ڈاکٹر/فارماسسٹ توں پچھو؛ میں تشخیص، دوا یا خوراک آپ نہیں بناواں گا۔",
        "ਕੋਈ ਸੰਬੰਧਿਤ ਸੰਭਾਲਿਆ ਮੈਡੀਕਲ ਹਵਾਲਾ ਨਹੀਂ ਮਿਲਿਆ। ਭਰੋਸੇਯੋਗ ਦਵਾਈ ਦਸਤਾਵੇਜ਼ ਜੋੜੋ ਜਾਂ ਡਾਕਟਰ/ਫਾਰਮਾਸਿਸਟ ਨੂੰ ਪੁੱਛੋ; ਮੈਂ ਨਿਦਾਨ, ਦਵਾਈ ਜਾਂ ਖੁਰਾਕ ਨਹੀਂ ਘੜਾਂਗਾ।"
    )

    fun translationUnavailable(languageTag: String): String = localized(languageTag,
        "Translation is unavailable. The saved original text is shown below; selecting a language does not translate it.",
        "ترجمہ دستیاب نہیں۔ نیچے محفوظ اصل متن دکھایا گیا ہے؛ صرف زبان منتخب کرنے سے متن کا ترجمہ نہیں ہوتا۔",
        "ترجمہ دستیاب نہیں۔ تھلے محفوظ اصل متن اے؛ صرف زبان چُنن نال متن دا ترجمہ نہیں ہوندا۔",
        "ਅਨੁਵਾਦ ਉਪਲਬਧ ਨਹੀਂ। ਹੇਠਾਂ ਸੰਭਾਲਿਆ ਮੂਲ ਪਾਠ ਹੈ; ਸਿਰਫ਼ ਭਾਸ਼ਾ ਚੁਣਨ ਨਾਲ ਪਾਠ ਦਾ ਅਨੁਵਾਦ ਨਹੀਂ ਹੁੰਦਾ।"
    )

    fun originalLabel(languageTag: String): String = localized(languageTag,
        "Saved reference — original text (not clinically verified by Pocket AI):",
        "محفوظ حوالہ — اصل متن (پاکٹ اے آئی نے طبی تصدیق نہیں کی):",
        "محفوظ حوالہ — اصل متن (پاکٹ اے آئی نے طبی تصدیق نہیں کی):",
        "ਸੰਭਾਲਿਆ ਹਵਾਲਾ — ਮੂਲ ਪਾਠ (ਪਾਕੇਟ ਏਆਈ ਨੇ ਡਾਕਟਰੀ ਤਸਦੀਕ ਨਹੀਂ ਕੀਤੀ):"
    )

    fun translationLabel(languageTag: String): String = localized(languageTag,
        "Local model translation of selected excerpt [1] only — full original passages follow. Check the original; this is not a prescription:",
        "صرف منتخب اقتباس [1] کا مقامی ماڈل سے ترجمہ — مکمل اصل اقتباسات نیچے ہیں۔ اصل متن سے جانچیں؛ یہ نسخہ نہیں ہے:",
        "صرف چُنے اقتباس [1] دا مقامی ماڈل نال ترجمہ — مکمل اصل اقتباس تھلے نیں۔ اصل متن نال جانچو؛ ایہہ نسخہ نہیں:",
        "ਸਿਰਫ਼ ਚੁਣੇ ਅੰਸ਼ [1] ਦਾ ਸਥਾਨਕ ਮਾਡਲ ਅਨੁਵਾਦ — ਪੂਰੇ ਮੂਲ ਅੰਸ਼ ਹੇਠਾਂ ਹਨ। ਮੂਲ ਨਾਲ ਜਾਂਚੋ; ਇਹ ਨੁਸਖ਼ਾ ਨਹੀਂ ਹੈ:"
    )

    /** Detects direct patient-directed output; source quotations are kept separate and unchanged. */
    fun containsPersonalPrescription(text: String): Boolean = Regex(
        "\\b(?:you should|you must|i recommend|i prescribe|you have been diagnosed|you have (?:a|an|the))\\b|" +
            "\\b(?:take|give|use)\\s+\\d|آپ کو.{0,50}(?:لینا چاہیے|لینی چاہیے|بیماری ہے)|" +
            "(?:روزانہ|دن میں).{0,30}(?:لیں|لو)|ਤੁਹਾਨੂੰ.{0,50}(?:ਲੈਣੀ ਚਾਹੀਦੀ|ਲੈਣਾ ਚਾਹੀਦਾ|ਬਿਮਾਰੀ ਹੈ)",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(text)

    fun matchesRequestedScript(text: String, languageTag: String): Boolean = when {
        languageTag.startsWith("ur") || (languageTag.startsWith("pa") && !languageTag.contains("Guru", true)) -> text.count { it in '\u0600'..'\u06FF' } >= 8
        languageTag.startsWith("pa") -> text.count { it in '\u0A00'..'\u0A7F' } >= 8
        else -> true
    }

    private fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace('ي', 'ی').replace('ك', 'ک').replace('’', '\'')
        .replace(Regex("[\\u064B-\\u065F\\u0670\\u0640]"), "").replace(Regex("\\s+"), " ")

    private fun isNegated(text: String, range: IntRange): Boolean {
        val breaks = Regex("[.!?;,،\\n]|\\b(?:but|however|and|aur)\\b|اور|لیکن|ਅਤੇ|ਪਰ").findAll(text).toList()
        val start = breaks.lastOrNull { it.range.last < range.first }?.range?.last?.plus(1) ?: 0
        val end = breaks.firstOrNull { it.range.first > range.last }?.range?.first ?: text.length
        val before = text.substring(start, range.first).takeLast(40)
        val after = text.substring(range.last + 1, end).take(30)
        return Regex("\\b(?:no|not|without|denies)\\b|don't|do not", RegexOption.IGNORE_CASE).containsMatchIn(before) ||
            Regex("نہیں|نئیں|نہ |ਨਹੀਂ|ਨਹੀ|\\b(?:nahi|nahin|nhi)\\b").containsMatchIn(before + after)
    }

    private fun localized(tag: String, english: String, urdu: String, shahmukhi: String, gurmukhi: String): String = when {
        tag.startsWith("ur") -> urdu
        tag.startsWith("pa") && tag.contains("Guru", true) -> gurmukhi
        tag.startsWith("pa") -> shahmukhi
        else -> english
    }
}
