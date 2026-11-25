package ng.ainow.jarvis.language

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/**
 * Works out which language a piece of text is in: English, Pidgin, Yorùbá, Igbo or Hausa.
 *
 * Most Nigerians type without tone marks, so special letters alone are not enough. Each sentence is
 * judged on three clues: letters only one language uses (ẹ ṣ / ị ụ ṅ / ƙ ɗ ɓ), common words of each
 * language (which work on plain text), and Google's on-phone language detector (ML Kit, offline).
 * Sentences that are still unclear ("Ok o") take the language of the sentences around them.
 */
object LangDetect {
    const val EN = "en"
    const val PCM = "pcm"
    const val YO = "yo"
    const val IG = "ig"
    const val HA = "ha"

    // Common words, written plainly (no tone marks) because that's how most people type.
    private val WORDS: Map<String, Set<String>> = mapOf(
        YO to setOf(
            "ni", "ti", "si", "fun", "mo", "won", "awon", "ati", "je", "se", "ko", "bi", "pe", "lo", "wa",
            "oun", "naa", "yii", "eyi", "kini", "bawo", "nko", "ejo", "jowo", "daadaa", "ekaaro", "ekasan",
            "ekale", "ku", "ile", "omo", "owo", "oja", "lati", "sugbon", "tabi", "nigba", "gbogbo", "wipe",
            "ope", "ki", "lori", "pelu", "eni", "emi", "iwo", "awa", "eyin",
            "loni", "lola", "ana", "nibo", "melo", "dupe", "padà", "sile", "jade", "inu", "ode", "ojo",
            "ohun", "oju", "loju", "iboju", "botini", "tan", "fi", "ranse", "ifiranse", "pa", "gbe", "soke",
            "sile", "dakun", "ewo", "kan", "meji", "yen", "nkan", "lati", "tun", "maa", "n", "un",
        ),
        IG to setOf(
            "nke", "ka", "ndi", "bu", "nwere", "ihe", "gi", "anyi", "ma", "maka", "otu", "nne", "nna",
            "daalu", "kedu", "biko", "ozo", "onye", "ebe", "ugbu", "ahu", "nwa", "mana", "dika", "unu",
            "mmadu", "ego", "ulo", "ahia", "taa", "echi", "nnoo", "ndewo", "chukwu", "ike", "ruo", "gaa",
            "bia", "mee", "kwuo", "hu", "nyere", "ihu", "obi", "oge", "nso",
        ),
        HA to setOf(
            "da", "ba", "ne", "shi", "ta", "su", "mun", "kuma", "wannan", "sai", "amma", "domin",
            "saboda", "yanzu", "yaya", "sannu", "allah", "gida", "zuwa", "daga", "kai", "ita",
            "shine", "kawai", "wani", "abin", "lafiya", "nagode", "haka", "akwai", "babu", "zan", "zai",
            "suna", "muna", "kana", "kina", "ranar", "gobe", "jiya", "yau", "kudi", "aiki", "sosai",
            "mai", "cikin", "tare", "ina", "nake", "yake", "take", "ake",
        ),
        PCM to setOf(
            "dey", "wetin", "abeg", "una", "wey", "pikin", "sabi", "oya", "comot", "waka", "chop", "sef",
            "sha", "abi", "jare", "wahala", "dem", "naim", "na", "wan", "dis", "dat", "wen", "ehn",
            "shey", "sey", "gist", "wia", "ehen", "kpele", "sabi", "beta",
        ),
        EN to setOf(
            "the", "is", "are", "and", "you", "to", "of", "in", "it", "for", "on", "with", "this", "that",
            "what", "how", "please", "open", "call", "send", "my", "your", "will", "have", "has", "was",
            "be", "can", "from", "at", "by", "we", "they", "he", "she", "not", "do", "does", "an", "a",
            "message", "new", "today", "tomorrow", "time", "settings", "button", "back", "home",
        ),
    )

    // Words that are a strong sign of Pidgin even inside otherwise English text.
    private val PIDGIN_STRONG = setOf("dey", "wetin", "abeg", "una", "wey", "pikin", "sabi", "comot", "naim", "wahala", "shey")

    private val idClient by lazy {
        LanguageIdentification.getClient(
            LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.2f).build()
        )
    }

    /** True if the text carries tone marks or under-dots, i.e. it's written "properly". */
    fun hasToneMarks(text: String): Boolean {
        val d = Normalizer.normalize(text, Normalizer.Form.NFD)
        return d.any { it == '̀' || it == '́' || it == '̣' || it == '̇' }
    }

    private fun plainWords(text: String): List<String> {
        val d = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return d.split(Regex("[^\\p{L}']+")).filter { it.isNotEmpty() }
    }

    /** Scores from letters and words only. Instant, works offline. */
    private fun scores(text: String): MutableMap<String, Double> {
        val s = mutableMapOf(EN to 0.0, PCM to 0.0, YO to 0.0, IG to 0.0, HA to 0.0)
        val low = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFC)
        for (ch in low) when (ch) {
            'ẹ', 'ṣ' -> s[YO] = s[YO]!! + 3
            'ị', 'ụ', 'ṅ' -> s[IG] = s[IG]!! + 3
            'ọ' -> { s[YO] = s[YO]!! + 1.5; s[IG] = s[IG]!! + 1.5 }
            'ƙ', 'ɗ', 'ɓ', 'ƴ' -> s[HA] = s[HA]!! + 3
        }
        val words = plainWords(text)
        for (w in words) {
            for ((lang, set) in WORDS) if (w in set) s[lang] = s[lang]!! + 1
            if (w in PIDGIN_STRONG) s[PCM] = s[PCM]!! + 2
        }
        return s
    }

    private fun best(s: Map<String, Double>): Pair<String, Double> {
        val top = s.maxByOrNull { it.value }!!
        val second = s.filter { it.key != top.key }.maxOf { it.value }
        return top.key to (top.value - second)
    }

    /**
     * Quick guess for something the user said (commands are short and use known words).
     * Returns null when there's no clear winner. A mix of English and a Nigerian language counts as
     * the Nigerian language: "abeg open WhatsApp" is Pidgin, "kí n pe Mama" is Yorùbá.
     */
    fun quick(text: String): String? {
        val s = scores(text)
        val nigerian = s.filterKeys { it != EN }
        val nTop = nigerian.maxByOrNull { it.value }!!
        val unique = nigerian.values.count { it == nTop.value } == 1
        if (unique && nTop.value >= 2) return nTop.key
        if (unique && nTop.value >= 1 && s[EN]!! < 1) return nTop.key
        if (s[EN]!! >= 1 && nTop.value < 1) return EN
        return null
    }

    /** One sentence, using the on-phone detector too. Blocking (call off the main thread). Null = unsure. */
    private fun sentence(text: String): String? {
        val s = scores(text)
        if (s[PCM]!! >= 2 && s[YO]!! < 3 && s[IG]!! < 3 && s[HA]!! < 3) return PCM
        var other: String? = null
        try {
            val found = Tasks.await(idClient.identifyPossibleLanguages(text), 1500, TimeUnit.MILLISECONDS)
            for (l in found) {
                val tag = l.languageTag.substringBefore('-').lowercase()
                if (tag in s) s[tag] = s[tag]!! + 3.0 * l.confidence
                // Another language (French, Hindi, Chinese...): read it with the phone's voice for it.
                else if (l.confidence >= 0.7f && tag != "und" && other == null) other = tag
            }
        } catch (e: Exception) {
            // Detector unavailable: letters and words still decide.
        }
        if (other != null && s.filterKeys { it != EN }.values.all { it < 2 } && s[EN]!! < 2) return other
        val (lang, margin) = best(s)
        val words = plainWords(text).size
        if (s[lang]!! < 1.0 || margin < 0.5) return null
        if (words <= 2 && margin < 1.5) return null // too short to be sure; neighbours decide
        return lang
    }

    /** Splits text into sentences (long ones at commas) so each can go to the right voice. */
    fun split(text: String): List<String> {
        val out = ArrayList<String>()
        for (line in text.split('\n')) {
            // "." needs a space after it; Chinese/Japanese "。！？" and Hindi "।" don't.
            val parts = line.split(Regex("(?<=[.!?…])\\s+|(?<=[。！？।])\\s*"))
            for (p in parts) {
                var rest = p.trim()
                while (rest.length > 220) {
                    var cut = rest.lastIndexOf(", ", 220)
                    if (cut < 60) cut = rest.lastIndexOf(' ', 220)
                    if (cut <= 0) cut = 220
                    out.add(rest.substring(0, cut + 1).trim())
                    rest = rest.substring(cut + 1).trim()
                }
                if (rest.isNotEmpty()) out.add(rest)
            }
        }
        return out
    }

    /** Language of each sentence. Unclear ones take their neighbours' language, then [fallback]. */
    fun detectAll(sentences: List<String>, fallback: String): List<String> {
        val raw = sentences.map { sentence(it) }
        return raw.indices.map { i ->
            raw[i]
                ?: (i - 1 downTo 0).firstNotNullOfOrNull { raw[it] }
                ?: (i + 1 until raw.size).firstNotNullOfOrNull { raw[it] }
                ?: fallback
        }
    }
}
