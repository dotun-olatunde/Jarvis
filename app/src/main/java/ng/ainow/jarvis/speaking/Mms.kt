package ng.ainow.jarvis.speaking

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import ng.ainow.jarvis.listening.Yarn
import okhttp3.Request

/**
 * Meta's MMS voices, run on the phone with sherpa-onnx: instant, offline and free.
 * Only Yorùbá and Hausa exist (Meta never released Igbo). Licence: CC BY-NC 4.0 (non-commercial).
 *
 * The models are ~108 MB each, so they are not bundled in the app: they download once (when the
 * setup screen opens with internet) into the app's private storage and stay there.
 */
object Mms {
    /** Our language code -> MMS code. */
    private val MODELS = mapOf("yo" to "yor", "ha" to "hau")

    // Pinned to one revision so a change upstream can't silently swap the model.
    private const val BASE =
        "https://huggingface.co/willwade/mms-tts-multilingual-models-onnx/resolve/4f49fc254f11ddb0e35fbf25b6e48b04463a6779"

    private val downloading = ConcurrentHashMap.newKeySet<String>()
    private val lock = Any()
    private var loadedCode = ""
    private var engine: OfflineTts? = null

    fun supports(code: String) = code in MODELS

    private fun dir(ctx: Context, code: String) = File(ctx.applicationContext.filesDir, "mms/" + MODELS[code])

    fun ready(ctx: Context, code: String): Boolean {
        if (!supports(code)) return false
        val d = dir(ctx, code)
        return File(d, "model.onnx").isFile && File(d, "tokens.txt").isFile
    }

    /** Downloads one voice if missing. Blocking: call on a background thread. */
    fun download(ctx: Context, code: String): Boolean {
        if (ready(ctx, code)) return true
        if (!supports(code) || !downloading.add(code)) return false
        try {
            val d = dir(ctx, code)
            d.mkdirs()
            val mms = MODELS[code]
            // tokens first, model last: ready() only says yes once the big file is complete.
            for (name in listOf("tokens.txt", "model.onnx")) {
                val target = File(d, name)
                if (target.isFile) continue
                val tmp = File(d, "$name.part")
                val req = Request.Builder().url("$BASE/$mms/$name").build()
                Yarn.http.newBuilder().readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()
                    .newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return false
                        val body = resp.body ?: return false
                        tmp.outputStream().use { out -> body.byteStream().copyTo(out, 64 * 1024) }
                    }
                if (!tmp.renameTo(target)) return false
            }
            return ready(ctx, code)
        } catch (e: Exception) {
            return false
        } finally {
            downloading.remove(code)
        }
    }

    /** Fetches every missing voice in the background. Safe to call often. */
    /** Fetches every missing voice in the background, then runs [then] on that thread. */
    fun downloadAll(ctx: Context, then: () -> Unit = {}) {
        val app = ctx.applicationContext
        // ~220 MB: only on Wi-Fi (or another unmetered network), never on the user's mobile data.
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val unmetered = cm.activeNetwork != null && !cm.isActiveNetworkMetered
        val missing = if (unmetered) MODELS.keys.filter { !ready(app, it) && it !in downloading } else emptyList()
        Thread({
            for (c in missing) download(app, c)
            then()
        }, "mms-download").start()
    }

    /**
     * Speech for [text] as 16-bit mono PCM, or null if this voice isn't available.
     * Only one model is kept in memory (~120 MB), so switching language reloads (~1 s).
     */
    fun synth(ctx: Context, code: String, text: String): Pair<Int, ByteArray>? {
        if (!ready(ctx, code)) return null
        // Short phrases (previews, Jarvis's usual replies) are saved, so they play instantly next time.
        val saved = if (text.length <= 300) Yarn.savedFile(ctx.applicationContext.cacheDir, "mms-$code|$text") else null
        saved?.let { Yarn.load(it) }?.let { return it }
        val made = make(ctx, code, text) ?: return null
        saved?.let { Yarn.save(it, made.first, made.second) }
        return made
    }

    private fun make(ctx: Context, code: String, text: String): Pair<Int, ByteArray>? {
        synchronized(lock) {
            try {
                if (loadedCode != code || engine == null) {
                    engine?.release()
                    engine = null
                    loadedCode = ""
                    val d = dir(ctx, code)
                    val cfg = OfflineTtsConfig(
                        model = OfflineTtsModelConfig(
                            vits = OfflineTtsVitsModelConfig(
                                model = File(d, "model.onnx").path,
                                tokens = File(d, "tokens.txt").path,
                            ),
                            numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
                            provider = "cpu",
                        ),
                    )
                    engine = OfflineTts(null, cfg)
                    loadedCode = code
                }
                val audio = engine!!.generate(text, 0, 1.0f)
                val f = audio.samples
                if (f.isEmpty()) return null
                val out = ByteArray(f.size * 2)
                for (i in f.indices) {
                    val v = (f[i].coerceIn(-1f, 1f) * 32767f).toInt()
                    out[2 * i] = (v and 0xff).toByte()
                    out[2 * i + 1] = ((v shr 8) and 0xff).toByte()
                }
                return audio.sampleRate to out
            } catch (e: Throwable) {
                engine = null
                loadedCode = ""
                return null
            }
        }
    }
}
