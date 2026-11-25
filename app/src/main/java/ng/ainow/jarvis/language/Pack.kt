package ng.ainow.jarvis.language

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * One language pack = one JSON file in assets/packs. Adding a language means adding a file,
 * no code changes. See packs/en.json for the format.
 */
class Pack(
    val code: String,
    val name: String,
    val aliases: List<String>,
    val stt: String,                       // speech recognition language tag
    val tts: String,                       // text-to-speech language tag
    val phrases: Map<String, List<String>>, // whole-phrase commands -> intent id
    val verbs: Map<String, List<String>>,   // "verb + argument" commands -> intent id
    val fillers: List<String>,
    val withWords: List<String>,
    val risky: List<String>,
    val ordinals: List<List<String>>,
    val say: Map<String, String>
)

object Packs {
    private val cache = HashMap<String, Pack>()

    fun codes(ctx: Context): List<String> {
        val all = (ctx.assets.list("packs") ?: emptyArray())
            .filter { it.endsWith(".json") }
            .map { it.removeSuffix(".json") }
        return all.sortedWith(compareBy({ it != "en" }, { it }))
    }

    fun load(ctx: Context, code: String): Pack {
        cache[code]?.let { return it }
        val text = try {
            ctx.assets.open("packs/$code.json").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ctx.assets.open("packs/en.json").bufferedReader().use { it.readText() }
        }
        val p = parse(text)
        cache[code] = p
        return p
    }

    private fun list(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        return List(a.length()) { a.getString(it) }
    }

    private fun listMap(o: JSONObject?): Map<String, List<String>> {
        val m = LinkedHashMap<String, List<String>>()
        if (o != null) {
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                m[k] = list(o.optJSONArray(k))
            }
        }
        return m
    }

    private fun strMap(o: JSONObject?): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        if (o != null) {
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                m[k] = o.optString(k)
            }
        }
        return m
    }

    fun parse(text: String): Pack {
        val o = JSONObject(text)
        return Pack(
            code = o.getString("code"),
            name = o.getString("name"),
            aliases = list(o.optJSONArray("aliases")),
            stt = o.optString("stt", "en-NG"),
            tts = o.optString("tts", "en-NG"),
            phrases = listMap(o.optJSONObject("phrases")),
            verbs = listMap(o.optJSONObject("verbs")),
            fillers = list(o.optJSONArray("fillers")),
            withWords = list(o.optJSONArray("with")),
            risky = list(o.optJSONArray("risky")),
            ordinals = list(o.optJSONArray("ordinals")).map { it.split("|") },
            say = strMap(o.optJSONObject("say"))
        )
    }
}
