package ng.ainow.jarvis.ai

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import ng.ainow.jarvis.Prefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Google Gemini over plain HTTPS. Used ONLY when the rules and on-device OCR cannot do the job:
 * chat, internet questions (weather, news), unusual sentences, and finding things in a screenshot.
 *
 * Model names change often, so the app asks Google which models YOUR key can use, then picks from those.
 */
class AiClient(private val prefs: Prefs) {
    class Result(val ok: Boolean, val text: String, val error: String)
    private class Resp(val code: Int, val body: String, val msg: String)

    companion object {
        /** Best first. Fast "lite" models first because answers must come back in a couple of seconds. */
        private val PREFERRED = listOf(
            "gemini-3.5-flash-lite", "gemini-3.8-flash", "gemini-3.6-flash",
            "gemini-flash-lite-latest", "gemini-flash-latest",
            "gemini-2.5-flash-lite", "gemini-2.5-flash"
        )
        private val AVOID = listOf("image", "tts", "live", "audio", "embedding", "robotics", "computer", "exp", "8b", "native", "thinking")
        private val ex = Executors.newCachedThreadPool()
        @Volatile private var cachedFor = ""
        @Volatile private var cachedModels: List<String> = emptyList()
    }

    private val main = Handler(Looper.getMainLooper())

    private fun <T> bg(work: () -> T, cb: (T) -> Unit) {
        ex.execute {
            val r = work()
            main.post { cb(r) }
        }
    }

    // ------------------------------------------------------------------ models

    /** Asks Google which models this key can call. Returns (models, errorText). */
    private fun usableModels(key: String): Pair<List<String>, String> {
        if (cachedFor == key && cachedModels.isNotEmpty()) return Pair(cachedModels, "")
        val r = get("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200", key)
        if (r.code != 200) return Pair(emptyList(), "HTTP ${r.code} ${r.msg}".trim())
        val found = ArrayList<String>()
        try {
            val arr = JSONObject(r.body).getJSONArray("models")
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val name = m.optString("name").removePrefix("models/")
                val methods = m.optJSONArray("supportedGenerationMethods")
                var can = false
                if (methods != null) for (j in 0 until methods.length()) if (methods.getString(j) == "generateContent") can = true
                if (can && name.contains("flash") && AVOID.none { name.contains(it) }) found.add(name)
            }
        } catch (e: Exception) {
            return Pair(emptyList(), "Could not read the model list")
        }
        val ordered = PREFERRED.filter { it in found } + found.filter { it !in PREFERRED && !it.contains("preview") }
        val pick = ordered.take(3)
        if (pick.isEmpty()) return Pair(emptyList(), "This key can use no Gemini Flash model. Models seen: " + found.take(5).joinToString())
        cachedFor = key
        cachedModels = pick
        return Pair(pick, "")
    }

    // ------------------------------------------------------------------ core request

    private fun askSync(system: String, user: String, images: List<Bitmap>, json: Boolean, search: Boolean): Result {
        val key = prefs.aiKey.trim()
        if (key.isEmpty()) return Result(false, "", "No API key set")
        val (models, err) = usableModels(key)
        if (models.isEmpty()) return Result(false, "", err)
        var last = "unknown error"
        for (model in models) {
            for (think in listOf(true, false)) {
                val res = post(model, key, body(system, user, images, json, think, search, model))
                if (res.code == 200) {
                    val t = extractText(res.body)
                    if (t.isNotBlank()) return Result(true, t, "")
                    last = "empty reply from $model"
                    break
                }
                last = "$model: HTTP ${res.code} ${res.msg}".trim()
                if (res.code == -1) return Result(false, "", last)
                if (res.code == 400 && think && res.msg.contains("think", true)) continue
                if (res.code == 401 || res.code == 403) return Result(false, "", last)
                if (res.code == 400) break
                break
            }
        }
        cachedFor = "" // force a fresh model list next time
        return Result(false, "", last)
    }

    fun ask(system: String, user: String, images: List<Bitmap> = emptyList(), json: Boolean = false, search: Boolean = false, cb: (Result) -> Unit) {
        bg({ askSync(system, user, images, json, search) }, cb)
    }

    private fun body(system: String, user: String, images: List<Bitmap>, json: Boolean, think: Boolean, search: Boolean, model: String): String {
        val parts = JSONArray().put(JSONObject().put("text", user))
        for (b in images) {
            val bos = ByteArrayOutputStream()
            b.compress(Bitmap.CompressFormat.JPEG, 70, bos)
            parts.put(
                JSONObject().put(
                    "inline_data",
                    JSONObject().put("mime_type", "image/jpeg")
                        .put("data", Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP))
                )
            )
        }
        val o = JSONObject()
        o.put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
        if (system.isNotEmpty()) {
            o.put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
        }
        if (search) o.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        val gc = JSONObject()
        gc.put("maxOutputTokens", 700)
        if (json && !search) gc.put("responseMimeType", "application/json")
        if (think) {
            // Fast answers: switch thinking off (2.5) or to minimum (3.x). Retried without this if rejected.
            if (model.contains("2.5")) gc.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
            else gc.put("thinkingConfig", JSONObject().put("thinkingLevel", "minimal"))
        }
        o.put("generationConfig", gc)
        return o.toString()
    }

    private fun get(url: String, key: String): Resp = call(url, key, null)
    private fun post(model: String, key: String, payload: String): Resp =
        call("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent", key, payload)

    private fun call(url: String, key: String, payload: String?): Resp {
        var c: HttpURLConnection? = null
        return try {
            c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = if (payload == null) "GET" else "POST"
            c.connectTimeout = 15000
            c.readTimeout = 45000
            c.setRequestProperty("x-goog-api-key", key)
            if (payload != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code < 400) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            var msg = ""
            if (code >= 400) {
                msg = try { JSONObject(text).optJSONObject("error")?.optString("message") ?: "" } catch (e: Exception) { text.take(120) }
            }
            Resp(code, text, msg.take(200))
        } catch (e: Exception) {
            Resp(-1, "", (e.javaClass.simpleName + ": " + (e.message ?: "")).take(160))
        } finally {
            c?.disconnect()
        }
    }

    private fun extractText(body: String): String {
        return try {
            val parts = JSONObject(body).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            val sb = StringBuilder()
            for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
            sb.toString().trim()
        } catch (e: Exception) {
            ""
        }
    }

    // ------------------------------------------------------------------ jobs

    /** For the Test AI button: says exactly which step works and how long it took. */
    fun diagnose(cb: (String) -> Unit) {
        bg({
            val key = prefs.aiKey.trim()
            if (key.isEmpty()) "No API key is saved."
            else {
                val sb = StringBuilder()
                val t0 = SystemClock.elapsedRealtime()
                cachedFor = ""
                val (models, err) = usableModels(key)
                val t1 = SystemClock.elapsedRealtime()
                if (models.isEmpty()) {
                    sb.append("Step 1, asking Google which models your key can use: FAILED after ${(t1 - t0) / 1000.0} s. $err")
                } else {
                    sb.append("Step 1 ok in ${(t1 - t0) / 1000.0} s. Models: ${models.joinToString()}. ")
                    val r = askSync("", "Reply with the single word: ok", emptyList(), false, false)
                    val t2 = SystemClock.elapsedRealtime()
                    sb.append(
                        if (r.ok) "Step 2, test question: ok in ${(t2 - t1) / 1000.0} s. Reply: ${r.text.take(30)}"
                        else "Step 2, test question: FAILED after ${(t2 - t1) / 1000.0} s. ${r.error}"
                    )
                }
                sb.toString()
            }
        }, cb)
    }

    /** One short request: is this a command, a question needing the internet, or just chat? Returns JSON text. */
    fun interpret(text: String, languageName: String, buttons: List<String>, cb: (Result) -> Unit) {
        val style = if (prefs.detailedReplies) "give a useful explanation in up to 150 words" else "be brief, at most 40 words"
        val system = """
You are Jarvis, a friendly voice assistant that controls an Android phone for a blind user.
The user may speak or mix different languages. Answer ONLY with JSON.
1. If the user wants the phone to do something, reply {"type":"command","id":"<id>","arg":"<text>"}.
   Allowed ids: tap (arg = the button the user means; prefer a name from the list below), open (arg = app name),
   call (arg = contact name), whatsapp (arg = contact name), search (arg = what to search),
   type (arg = text to type), back, home, recents, notifications, quick_settings,
   read_screen, read_buttons, scroll_down, scroll_up.
2. If the answer needs current information from the internet (weather, news, sports, prices, anything recent),
   reply {"type":"web","query":"<a clear search question>"}.
3. Otherwise reply {"type":"chat","reply":"<answer in $languageName, plain text, no markdown>"}. For chat, $style.
Buttons visible now: ${buttons.joinToString(" | ")}
""".trimIndent()
        ask(system, text, json = true, cb = cb)
    }

    /**
     * Puts back the tone marks and under-dots that people leave out when typing Yorùbá, Igbo or Hausa
     * ("E kaaro, se daadaa ni?" -> "Ẹ káàrọ̀, ṣé dáadáa ni?"), using the context to pick the right word.
     * Blocking; returns null if Gemini is off, slow (over [timeoutMs]) or fails, so callers keep the
     * original text. One result per input, same order.
     */
    fun restoreTonesSync(sentences: List<String>, langs: List<String>, timeoutMs: Long): List<String>? {
        if (sentences.isEmpty()) return emptyList()
        val names = mapOf("yo" to "Yorùbá", "ig" to "Igbo", "ha" to "Hausa")
        val items = JSONArray()
        for (i in sentences.indices) items.put(JSONObject().put("lang", names[langs[i]] ?: langs[i]).put("text", sentences[i]))
        val system = """
You restore diacritics in Nigerian-language text that was typed without them.
For each item, rewrite "text" in its language with the correct tone marks and under-dots
(Yorùbá: à á è é ẹ ì í ò ó ọ ù ú ṣ ń; Igbo: ị ọ ụ ṅ and tones; Hausa: ƙ ɗ ɓ ƴ).
Use the surrounding items as context to choose the right words. Do NOT translate, add, remove or
reorder words. Leave English words, names and numbers unchanged, including the English words
Nigerians mix into their languages (ok, okay, please, sorry, thank you, bye, phone, money...):
"Ok o" stays "Ok o". If you are unsure, return the text unchanged.
Answer ONLY with a JSON array of strings, one per item, in the same order.
""".trimIndent()
        val job = ex.submit<Result> { askSync(system, items.toString(), emptyList(), json = true, search = false) }
        val r = try { job.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (e: Exception) { job.cancel(true); return null }
        if (!r.ok) return null
        return try {
            val a = JSONArray(clean(r.text))
            if (a.length() != sentences.size) null else List(a.length()) { a.getString(it) }
        } catch (e: Exception) {
            null
        }
    }

    /** Answers from the internet using Google Search grounding. Plain text for speaking. */
    fun answer(question: String, languageName: String, cb: (Result) -> Unit) {
        val style = if (prefs.detailedReplies) "Give a useful explanation in up to 150 words." else "Answer in at most 60 words."
        val system = "You are Jarvis, a voice assistant. Use Google Search for current facts. Reply in $languageName. $style Use plain text only: no markdown, no lists, no links."
        ask(system, question, search = true, cb = cb)
    }

    /** Names icon-only buttons from small pictures. Returns one label per picture, same order. */
    fun labelIcons(crops: List<Bitmap>, cb: (List<String>?) -> Unit) {
        val system = "You name icon buttons in a phone app for a blind user. Answer ONLY with a JSON array of strings."
        val user = "The next ${crops.size} images are icon buttons, in order. For each give 1 to 3 lowercase words naming what the button does (for example: send, back, search, camera, record, settings, more options, close). Use an empty string if unsure."
        ask(system, user, crops, true) { r ->
            if (!r.ok) { cb(null); return@ask }
            try {
                val a = JSONArray(clean(r.text))
                cb(List(a.length()) { a.optString(it, "") })
            } catch (e: Exception) {
                cb(null)
            }
        }
    }

    /** Finds a control in a full screenshot. Returns its rectangle in screenshot pixels, or null. */
    fun locate(shot: Bitmap, target: String, cb: (Rect?) -> Unit) {
        val scale = 1024f / maxOf(shot.width, shot.height)
        val small = if (scale < 1f) Bitmap.createScaledBitmap(shot, (shot.width * scale).toInt(), (shot.height * scale).toInt(), true) else shot
        val system = "You find controls in phone screenshots for a blind user. Answer ONLY with JSON."
        val user = "Find the button or control described as \"$target\" in this screenshot. " +
            "Reply {\"found\":true,\"box\":[ymin,xmin,ymax,xmax]} with coordinates normalised to 0-1000, " +
            "or {\"found\":false} if it is not visible. Do not guess."
        ask(system, user, listOf(small), true) { r ->
            if (!r.ok) { cb(null); return@ask }
            try {
                val o = JSONObject(clean(r.text))
                if (!o.optBoolean("found")) { cb(null); return@ask }
                val b = o.getJSONArray("box")
                val w = shot.width
                val h = shot.height
                val rect = Rect(
                    (b.getDouble(1) / 1000.0 * w).toInt(), (b.getDouble(0) / 1000.0 * h).toInt(),
                    (b.getDouble(3) / 1000.0 * w).toInt(), (b.getDouble(2) / 1000.0 * h).toInt()
                )
                cb(if (rect.width() > 4 && rect.height() > 4) rect else null)
            } catch (e: Exception) {
                cb(null)
            }
        }
    }

    private fun clean(s: String) = s.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
}
