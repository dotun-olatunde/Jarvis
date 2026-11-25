package ng.ainow.jarvis.language

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** Text to say and the language (voice) to say it in. */
data class Spoken(val text: String, val lang: String)

/**
 * Jarvis's phrases can name things on the phone's screen without spelling them out:
 *
 *   [[accessibility]]   the phone's own name for the Accessibility settings page
 *   [[restricted]]      the phone's own name for "Allow restricted settings"
 *   [[en:some words]]   English words the user should say or will see, read with an English voice
 *
 * A menu name is read in whatever language the phone shows it in, so a Yorùbá sentence
 * still names the item exactly as it appears on screen, with a voice that can pronounce it.
 */
object Labels {
    private val MARK = Regex("""\[\[([a-z_]+)(?::([^\]]+))?]]""")

    /**
     * Splits [text] into pieces to speak in order. Plain text is in [lang]. [label] gives the phone's
     * name for a menu item and its language, or null if it can't be found (the English name is used).
     * Pieces that end up in the same voice are joined, so there is no pause between them.
     */
    fun split(text: String, lang: String, label: (String) -> Spoken?): List<Spoken> {
        val out = ArrayList<Spoken>()
        fun add(text: String, l: String) {
            var t = text
            val voice = if (sameVoice(l, lang)) lang else l
            var last = out.lastOrNull()
            // Punctuation straight after a piece belongs to it ("Accessibility." not ". Ó dáa").
            val lead = t.takeWhile { it in ".,!?;:…" }
            if (last != null && lead.isNotEmpty() && last.lang != voice) {
                last = Spoken(last.text + lead, last.lang)
                out[out.size - 1] = last
                t = t.substring(lead.length)
            }
            if (t.isEmpty()) return
            if (last != null && last.lang == voice) out[out.size - 1] = Spoken(last.text + t, voice)
            else out.add(Spoken(t, voice))
        }
        var at = 0
        for (m in MARK.findAll(text)) {
            add(text.substring(at, m.range.first), lang)
            val (key, words) = m.destructured
            when {
                key == "en" && words.isNotEmpty() -> add(words, LangDetect.EN)
                else -> {
                    val l = label(key) ?: ENGLISH[key]?.let { Spoken(it, LangDetect.EN) }
                    if (l != null) add(l.text, l.lang)
                }
            }
            at = m.range.last + 1
        }
        add(text.substring(at), lang)
        return out.map { Spoken(it.text.trim(), it.lang) }.filter { it.text.any(Char::isLetterOrDigit) }
    }

    /** English and Pidgin are both read with the Nigerian English voice. */
    private fun sameVoice(a: String, b: String): Boolean {
        val english = setOf(LangDetect.EN, LangDetect.PCM)
        return a == b || (a in english && b in english)
    }

    /** Used when the phone's own name can't be found. */
    val ENGLISH = mapOf(
        "accessibility" to "Accessibility",
        "restricted" to "Allow restricted settings",
    )

    /** Names of the strings in Android's Settings app (AOSP), tried in order. */
    private val RESOURCES = mapOf(
        "accessibility" to listOf("accessibility_settings"),
        "restricted" to listOf("app_restricted_settings_lockscreen_title"),
    )

    private const val SETTINGS = "com.android.settings"
    private val cache = HashMap<String, Spoken?>()

    /**
     * The phone's own name for a menu item, in the language the Settings app shows it in.
     * Phone makers sometimes rename or remove these, so null means "use the English name".
     */
    @Synchronized
    fun phone(ctx: Context, key: String): Spoken? {
        val locale = ctx.resources.configuration.locales[0]
        val cacheKey = locale.toLanguageTag() + "/" + key
        if (cacheKey in cache) return cache[cacheKey]
        val found = try {
            val res = ctx.createPackageContext(SETTINGS, 0).resources
            val id = RESOURCES[key].orEmpty().map { res.getIdentifier(it, "string", SETTINGS) }.firstOrNull { it != 0 }
            if (id == null) null else {
                val shown = res.getString(id)
                // Settings may have no translation for the phone's language and show English instead.
                val englishConf = Configuration(res.configuration).apply { setLocale(Locale.ENGLISH) }
                val english = ctx.createPackageContext(SETTINGS, 0)
                    .createConfigurationContext(englishConf).resources.getString(id)
                val lang = if (shown == english) LangDetect.EN else locale.language
                Spoken(shown, lang)
            }
        } catch (e: Exception) {
            null
        }
        cache[cacheKey] = found
        return found
    }
}
