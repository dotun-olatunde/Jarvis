package ng.ainow.jarvis.listening

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt
import ng.ainow.jarvis.Prefs
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

/**
 * YarnGPT (yarngpt.ai): Nigerian voices and speech recognition for English, Pidgin, Yorùbá,
 * Ìgbò and Hausa. One recognition model covers all five, so a sentence that mixes them
 * ("abeg open WhatsApp, kí n pe Mama mi") comes back correctly — that is our code-switching.
 *
 * The key is built into the app (see local.properties), so users never deal with it.
 * Everything here falls back to Android's own voice and recogniser when YarnGPT is not usable.
 */
object Yarn {
    private const val BASE = "https://api.yarngpt.ai/api/v1"
    const val VOICE = "idera"

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
    }

    fun allowed(prefs: Prefs): Boolean = prefs.yarnKey.isNotBlank() && prefs.yarnConsent

    // ------------------------------------------------------------------ speaking

    /** Short phrases (previews, "I am listening", ...) are saved on the phone so they play instantly next time. */
    private const val CACHE_MAX_CHARS = 300

    private fun cacheFile(dir: File, text: String): File = savedFile(dir, "$VOICE|$text")

    /** Where a voice's audio for one phrase is kept. [id] must include the voice and the text. */
    fun savedFile(dir: File, id: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest(id.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir, "yarn-voice/$h.pcm")
    }

    /** File layout: 4-byte sample rate, then raw 16-bit little-endian mono audio. */
    fun save(f: File, rate: Int, pcm: ByteArray) {
        try {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            DataOutputStream(FileOutputStream(tmp).buffered()).use { it.writeInt(rate); it.write(pcm) }
            tmp.renameTo(f)
        } catch (e: Exception) {
        }
    }

    private fun request(key: String, text: String): Request {
        val body = JSONObject()
            .put("text", text)
            .put("voice", VOICE)
            .put("output_format", "pcm")
            .toString()
            .toRequestBody("application/json".toMediaType())
        return Request.Builder()
            .url("$BASE/streaming/conversation")
            .header("Authorization", "Bearer $key")
            .post(body)
            .build()
    }

    /**
     * The audio for [text] as (sample rate, 16-bit mono PCM): from the phone if saved before,
     * otherwise downloaded (short phrases are then saved). Blocking; null if it failed.
     * YarnGPT makes the whole clip before sending, so this costs no extra time over streaming.
     */
    /** Saved audio for a phrase, or null. Deletes a damaged file. */
    fun load(f: File): Pair<Int, ByteArray>? {
        if (!f.exists()) return null
        try {
            DataInputStream(FileInputStream(f).buffered()).use { inp ->
                val rate = inp.readInt()
                val pcm = inp.readBytes()
                if (pcm.size >= 2) return rate to pcm
            }
        } catch (e: Exception) {
        }
        f.delete()
        return null
    }

    fun audio(key: String, text: String, cacheDir: File): Pair<Int, ByteArray>? {
        val f = cacheFile(cacheDir, text)
        load(f)?.let { return it }
        try {
            http.newCall(request(key, text)).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val raw = resp.body?.bytes() ?: return null
                if (raw.size < 2) return null
                val pcm = raw.copyOf(raw.size - raw.size % 2)
                val rate = sampleRate(resp) ?: 16000
                if (text.length <= CACHE_MAX_CHARS) save(f, rate, pcm)
                return rate to pcm
            }
        } catch (e: Exception) {
            return null
        }
    }

    /** Downloads and saves [text] ahead of time so it plays instantly later. Background thread only. */
    fun prefetch(key: String, text: String, cacheDir: File) {
        if (text.length <= CACHE_MAX_CHARS) audio(key, text, cacheDir)
    }

    private fun sampleRate(r: Response): Int? =
        Regex("rate=(\\d+)").find(r.header("Content-Type") ?: "")?.groupValues?.get(1)?.toIntOrNull()

    // ------------------------------------------------------------------ listening

    interface Ears {
        fun onText(text: String)
        fun onSilence()
        fun onFailed(why: String)
    }

    /**
     * One command turn: streams the microphone to YarnGPT until the user stops talking, then
     * reports the text once. Billed per 15-second block, so each turn is kept short.
     */
    class Listener(
        private val key: String,
        private val ears: Ears,
        private val waitForSpeechMs: Long = NO_SPEECH_MS,
    ) {
        private val main = Handler(Looper.getMainLooper())
        @Volatile private var stopped = false
        @Volatile private var started = false
        @Volatile private var finalizing = false
        @Volatile private var reported = false
        private var ws: WebSocket? = null
        private var recorder: Thread? = null
        private val pending = ArrayList<ByteArray>()

        companion object {
            const val RATE = 16000
            const val CHUNK = 4000          // 125 ms of audio
            const val MAX_MS = 10000        // longest command
            const val NO_SPEECH_MS = 6000L  // regular command's initial silence allowance
            const val END_QUIET_MS = 900    // this much quiet after speech ends the turn
        }

        fun start() {
            val req = Request.Builder()
                .url("wss://api.yarngpt.ai/api/v1/asr/stream")
                .header("Authorization", "Bearer $key")
                .build()
            ws = http.newWebSocket(req, socketListener)
            val th = Thread({ record() }, "yarn-mic")
            recorder = th
            th.start()
        }

        fun cancel() {
            stopped = true
            reported = true
            try { ws?.cancel() } catch (e: Exception) {}
        }

        private fun report(block: () -> Unit) {
            if (reported) return
            reported = true
            stopped = true
            main.post(block)
        }

        private fun finalizeTurn() {
            if (finalizing) return
            finalizing = true
            stopped = true
            // Before "started" the server would refuse it; the "started" handler sends it instead.
            synchronized(pending) {
                if (started) try { ws?.send("{\"type\":\"finalize\"}") } catch (e: Exception) {}
            }
            // If the server never answers, don't leave the user hanging.
            main.postDelayed({ report { ears.onSilence() }; try { ws?.cancel() } catch (e: Exception) {} }, 4000)
        }

        @SuppressLint("MissingPermission") // checked by VoiceService before starting
        private fun record() {
            val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, CHUNK * 4))
            } catch (e: Exception) {
                report { ears.onFailed("microphone: " + e.javaClass.simpleName) }
                return
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                report { ears.onFailed("microphone would not start") }
                return
            }
            val t0 = System.currentTimeMillis()
            var noise = -1.0
            var speech = false
            var lastLoud = 0L
            val maxTurnMs = maxOf(MAX_MS.toLong(), waitForSpeechMs)
            try {
                rec.startRecording()
                while (!stopped) {
                    val buf = ByteArray(CHUNK)
                    var got = 0
                    while (got < CHUNK && !stopped) {
                        val n = rec.read(buf, got, CHUNK - got)
                        if (n <= 0) break
                        got += n
                    }
                    if (got <= 0) break
                    val chunk = if (got == CHUNK) buf else buf.copyOf(got - got % 2)
                    send(chunk)

                    // Simple end-of-speech detection so a turn ends soon after the user stops.
                    val now = System.currentTimeMillis()
                    val level = rms(chunk)
                    if (now - t0 < 400) {
                        noise = if (noise < 0) level else (noise + level) / 2
                    } else {
                        val loud = level > maxOf(noise * 3.0, 300.0)
                        if (loud) { speech = true; lastLoud = now }
                        val elapsed = now - t0
                        when {
                            speech && now - lastLoud >= END_QUIET_MS -> finalizeTurn()
                            !speech && elapsed >= waitForSpeechMs -> finalizeTurn()
                            elapsed >= maxTurnMs -> finalizeTurn()
                        }
                    }
                }
            } catch (e: Exception) {
                report { ears.onFailed("microphone: " + e.javaClass.simpleName) }
            } finally {
                try { rec.stop() } catch (e: Exception) {}
                rec.release()
            }
        }

        private fun send(chunk: ByteArray) {
            val s = ws ?: return
            synchronized(pending) {
                if (!started) pending.add(chunk) else s.send(chunk.toByteString())
            }
        }

        private fun rms(b: ByteArray): Double {
            var sum = 0.0
            val n = b.size / 2
            if (n == 0) return 0.0
            for (i in 0 until n) {
                val v = ((b[2 * i + 1].toInt() shl 8) or (b[2 * i].toInt() and 0xff)).toShort().toDouble()
                sum += v * v
            }
            return sqrt(sum / n)
        }

        private val socketListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val start = JSONObject()
                    .put("type", "start")
                    .put("audio", JSONObject().put("encoding", "pcm_s16le").put("sample_rate", RATE).put("channels", 1))
                    .put("options", JSONObject().put("endpoint_silence_ms", 700).put("partials", false))
                webSocket.send(start.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = try { JSONObject(text) } catch (e: Exception) { return }
                when (m.optString("type")) {
                    "started" -> {
                        // Flush inside the lock so the backlog goes out before any newer audio.
                        synchronized(pending) {
                            started = true
                            for (c in pending) webSocket.send(c.toByteString())
                            pending.clear()
                            if (finalizing) webSocket.send("{\"type\":\"finalize\"}")
                        }
                    }
                    "final" -> {
                        val said = m.optString("text").trim()
                        if (said.isNotEmpty()) {
                            report { ears.onText(said) }
                            finalizeTurn()
                        }
                    }
                    "done" -> {
                        report { ears.onSilence() }
                        webSocket.close(1000, null)
                    }
                    "error" -> {
                        val e = m.optJSONObject("error")
                        report { ears.onFailed(e?.optString("code") ?: "error") }
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                report { ears.onFailed(if (response != null) "HTTP " + response.code else t.javaClass.simpleName) }
            }
        }
    }
}
