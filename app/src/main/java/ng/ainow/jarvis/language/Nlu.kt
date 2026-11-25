package ng.ainow.jarvis.language
/** What the user asked for. */
sealed class Cmd {
    data class Simple(val id: String) : Cmd()
    data class Arg(val id: String, val arg: String, val arg2: String = "") : Cmd()
    data class Unknown(val text: String) : Cmd()
}

/**
 * Rule-based command understanding driven entirely by the language packs.
 * Order: exact phrase, then "verb + argument", then a short loose match for a few safe commands.
 */
object Nlu {
    private val WAKE_PREFIX = setOf("hey", "ok", "okay", "hi", "hello", "abeg")
    private val VERB_LAST = setOf("ko", "ja", "hi", "tr")
    private val LOOSE = setOf("back", "home", "help", "yes", "no")

    /** Removes "hey Jarvis" style call from the start. Returns (heardName, restOfSentence). */
    fun stripWake(raw: String, wakeName: String): Pair<Boolean, String> {
        val tokens = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val wake = Matcher.norm(wakeName)
        if (wake.isEmpty()) return Pair(false, raw.trim())
        for (i in 0 until minOf(3, tokens.size)) {
            val n = Matcher.norm(tokens[i])
            val same = n == wake || (wake.length >= 4 && n.length >= 3 && Matcher.lev(n, wake) <= 1)
            if (same) {
                val before = tokens.subList(0, i).all { Matcher.norm(it) in WAKE_PREFIX }
                if (before) return Pair(true, tokens.drop(i + 1).joinToString(" ").trim())
            }
        }
        return Pair(false, raw.trim())
    }

    fun parse(raw: String, packs: List<Pack>): Cmd {
        var t = Matcher.norm(raw)
        // Chinese/Japanese/Thai: no spaces, so drop polite words like "请" from the front by prefix.
        if (Matcher.spaceless(t)) {
            val fl = fillers(packs).filter { it.isNotEmpty() && Matcher.spaceless(it) }
            var changed = true
            while (changed) {
                changed = false
                for (f in fl) if (t.startsWith(f) && t.length > f.length) { t = t.removePrefix(f).trim(); changed = true }
            }
        }
        if (t.isEmpty()) return Cmd.Unknown(raw)
        val words = t.split(" ")

        // 1. exact phrase
        for (p in packs) for ((id, list) in p.phrases) {
            if (list.any { Matcher.norm(it) == t }) return Cmd.Simple(id)
        }

        // 2. verb + argument, longest verb first
        data class V(val verb: String, val id: String, val pack: Pack)
        val all = ArrayList<V>()
        for (p in packs) for ((id, vs) in p.verbs) for (v in vs) {
            val n = Matcher.norm(v)
            if (n.isNotEmpty()) all.add(V(n, id, p))
        }
        all.sortByDescending { it.verb.length }
        val rawTokens = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        // Verb first: "open WhatsApp", "打开微信" (no space in Chinese/Thai).
        fun verbFirst(): Cmd? {
        for (v in all) {
            if (t == v.verb) return Cmd.Arg(v.id, "")
            if (Matcher.spaceless(v.verb) && t.startsWith(v.verb) && t.length > v.verb.length) {
                val rest = t.substring(v.verb.length).trim()
                return build(v.id, rest, rest.split(" ").filter { it.isNotEmpty() }, packs)
            }
            if (t.startsWith(v.verb + " ")) {
                val vc = v.verb.split(" ").size
                val restNorm = t.substring(v.verb.length + 1).trim()
                val restRaw = rawTokens.drop(vc)
                return build(v.id, restNorm, restRaw, packs)
            }
        }
        return null
        }

        // Verb last, only for languages that put it there: Korean "왓츠앱 열어", Japanese "LINEを開いて",
        // Hindi "व्हाट्सएप खोलो", Turkish "WhatsApp aç".
        fun verbLast(): Cmd? {
        for (v in all) {
            if (v.pack.code !in VERB_LAST) continue
            if (t.endsWith(" " + v.verb) && t.length > v.verb.length + 1) {
                val restNorm = t.dropLast(v.verb.length + 1).trim()
                val restRaw = rawTokens.dropLast(v.verb.split(" ").size)
                return build(v.id, restNorm, restRaw, packs)
            }
            if (Matcher.spaceless(v.verb) && t.endsWith(v.verb) && t.length > v.verb.length) {
                val rest = t.dropLast(v.verb.length).trim()
                return build(v.id, rest, rest.split(" ").filter { it.isNotEmpty() }, packs)
            }
        }
        return null
        }
        // In a verb-last language, "WhatsApp aç" must not be read as the English "whatsapp <contact>".
        if (packs.firstOrNull()?.code in VERB_LAST) {
            verbLast()?.let { return it }
            verbFirst()?.let { return it }
        } else {
            verbFirst()?.let { return it }
            verbLast()?.let { return it }
        }

        // 3. loose match for a few harmless commands ("please go back")
        for (p in packs) for ((id, list) in p.phrases) {
            if (id !in LOOSE) continue
            for (ph in list) {
                val pn = Matcher.norm(ph)
                if (Matcher.spaceless(pn) && pn.isNotEmpty() && t.contains(pn) && t.length <= pn.length + 4) return Cmd.Simple(id)
                val pw = Matcher.words(ph)
                if (pw.isNotEmpty() && words.size <= pw.size + 2 && Matcher.containsWords(words, pw)) {
                    return Cmd.Simple(id)
                }
            }
        }
        return Cmd.Unknown(raw)
    }

    /**
     * Grammar endings glued to a name in verb-last languages: Korean "왓츠앱을" / "엄마한테", Japanese
     * "WhatsAppを" / "母に". Only stripped when something is left, so names ending in these stay intact
     * when they are the whole word.
     */
    private val PARTICLES = listOf("에게", "한테", "으로", "을", "를", "에", "로", "を", "に", "へ", "で", "と", "は", "が")

    private fun stripParticles(arg: String): String {
        var a = arg.trim()
        for (p in PARTICLES) if (a.endsWith(p) && a.length > p.length + 1) { a = a.dropLast(p.length).trim(); break }
        return a
    }

    private fun fillers(packs: List<Pack>): Set<String> = packs.flatMap { p -> p.fillers.map { Matcher.norm(it) } }.toSet()
    private fun withs(packs: List<Pack>): Set<String> = packs.flatMap { p -> p.withWords.map { Matcher.norm(it) } }.toSet()

    private fun trimFillers(tokens: List<String>, f: Set<String>): List<String> {
        var a = 0
        var b = tokens.size
        while (a < b && Matcher.norm(tokens[a]) in f) a++
        while (b > a && Matcher.norm(tokens[b - 1]) in f) b--
        return tokens.subList(a, b)
    }

    private fun build(id: String, restNorm: String, restRaw: List<String>, packs: List<Pack>): Cmd {
        val f = fillers(packs)
        return when (id) {
            "type" -> Cmd.Arg("type", restRaw.joinToString(" ").trim())
            "fill" -> {
                val w = withs(packs)
                val idx = restRaw.indexOfFirst { Matcher.norm(it) in w }
                if (idx > 0 && idx < restRaw.size - 1) {
                    val field = trimFillers(restRaw.subList(0, idx), f).joinToString(" ")
                    val value = restRaw.subList(idx + 1, restRaw.size).joinToString(" ").trim()
                    Cmd.Arg("fill", field, value)
                } else {
                    Cmd.Arg("type", restRaw.joinToString(" ").trim())
                }
            }
            else -> Cmd.Arg(id, stripParticles(trimFillers(restNorm.split(" ").filter { it.isNotEmpty() }, f).joinToString(" ")))
        }
    }

    /** [raw] as the argument of command [id], cleaned the same way as "verb + argument". */
    fun argument(id: String, raw: String, packs: List<Pack>): Cmd {
        val tokens = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return build(id, Matcher.norm(raw), tokens, packs)
    }

    private val THEN = listOf(" and then ", " then ", " and ")

    /** "google and search for song" -> ("google", "search for song"). Null if there is no second step. */
    fun splitThen(arg: String): Pair<String, String>? {
        for (sep in THEN) {
            val i = arg.indexOf(sep)
            if (i > 0 && i + sep.length < arg.length) return arg.substring(0, i).trim() to arg.substring(i + sep.length).trim()
        }
        return null
    }

    /** "second", "2", "dos" ... -> 2 */
    fun ordinal(raw: String, packs: List<Pack>): Int? {
        val words = Matcher.words(raw)
        for (w in words) {
            w.toIntOrNull()?.let { if (it in 1..9) return it }
            for (p in packs) for ((i, alts) in p.ordinals.withIndex()) {
                if (alts.any { Matcher.norm(it) == w }) return i + 1
            }
        }
        return null
    }
}
