package ng.ainow.jarvis.speaking

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import ng.ainow.jarvis.BuildConfig
import ng.ainow.jarvis.Prefs
import ng.ainow.jarvis.ai.AiClient
import ng.ainow.jarvis.language.LangDetect
import ng.ainow.jarvis.language.Packs
import ng.ainow.jarvis.listening.Yarn

/**
 * Everything Jarvis says goes through here, one piece of text at a time, in order:
 *   1. split into sentences;
 *   2. work out each sentence's language (or use the one given), so English on screen is read in
 *      English and Yorùbá in Yorùbá;
 *   3. plain-typed Yorùbá/Igbo/Hausa gets its tone marks back from Gemini (internet only);
 *   4. each sentence goes to the best voice for its language:
 *        English, Pidgin  -> the phone's voice (instant, free)
 *        Yorùbá, Hausa    -> Meta's MMS voice on the phone (instant, offline), else YarnGPT
 *        Igbo             -> YarnGPT (internet), fetched a sentence ahead so only the first waits
 *      and anything that fails falls back to the phone's voice.
 */
class Speaker(ctx: Context, private val onIdle: () -> Unit) {
    private val app = ctx.applicationContext
    private val prefs = Prefs(app)
    private val ai = AiClient(prefs)
    private val main = Handler(Looper.getMainLooper())

    private val player = Executors.newSingleThreadExecutor()    // speaks pieces of text in order
    private val makers = Executors.newFixedThreadPool(2)        // makes audio for upcoming sentences

    @Volatile private var gen = 0                               // bumped by stop()
    @Volatile private var track: AudioTrack? = null
    private var tts: TextToSpeech? = null
    private val ttsReady = CountDownLatch(1)
    @Volatile private var ttsOk = false
    private val phoneDone = ConcurrentHashMap<String, CountDownLatch>()
    private var pending = 0                                     // main thread only

    val busy: Boolean get() = pending > 0

    /** When speech last finished (elapsedRealtime). Lets the wake word ignore Jarvis's own voice. */
    @Volatile
    var lastDoneAt = 0L
        private set

    private enum class Engine { PHONE, MMS, YARN }
    private class Seg(val text: String, val lang: String, val engine: Engine)

    init {
        tts = TextToSpeech(app) { status ->
            val t = tts
            ttsOk = status == TextToSpeech.SUCCESS && t != null
            if (ttsOk && t != null) {
                t.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { release(utteranceId) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { release(utteranceId) }
                    override fun onError(utteranceId: String?, errorCode: Int) { release(utteranceId) }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) { release(utteranceId) }
                })
            }
            ttsReady.countDown()
            main.post { if (!busy) onIdle() }
        }
    }

    private fun release(id: String?) {
        if (id != null) phoneDone.remove(id)?.countDown()
    }

    /**
     * Says [text]. [lang] is a language code ("yo") or a pack voice tag ("yo-NG") when the language is
     * known (Jarvis's own replies); null means "work it out per sentence" (reading the screen).
     */
    fun speak(text: String, lang: String?) {
        if (text.isBlank()) return
        val g = gen
        pending++
        player.execute {
            try {
                if (g == gen) run(text, lang?.let { code(it) }, g)
            } catch (e: Exception) {
            } finally {
                main.post {
                    pending = maxOf(0, pending - 1)
                    lastDoneAt = android.os.SystemClock.elapsedRealtime()
                    if (pending == 0) onIdle()
                }
            }
        }
    }

    fun stop() {
        gen++
        try { track?.pause(); track?.flush() } catch (e: Exception) {}
        tts?.stop()
        for (l in phoneDone.values) l.countDown()
        phoneDone.clear()
    }

    fun shutdown() {
        stop()
        player.shutdownNow()
        makers.shutdownNow()
        tts?.shutdown()
        tts = null
    }

    /** False means this language will be read with an English voice. Null while the phone voice starts. */
    fun hasVoice(tag: String): Boolean? = when (code(tag)) {
        LangDetect.YO, LangDetect.HA -> Mms.ready(app, code(tag)) || Yarn.allowed(prefs)
        LangDetect.IG -> Yarn.allowed(prefs)
        else -> if (ttsReady.count > 0) null else ttsOk
    }

    // ------------------------------------------------------------------ one piece of text

    private fun code(tagOrCode: String) = tagOrCode.substringBefore('-').lowercase()

    private fun engineFor(lang: String): Engine = when (lang) {
        LangDetect.YO, LangDetect.HA -> when {
            Mms.ready(app, lang) -> Engine.MMS
            Yarn.allowed(prefs) -> Engine.YARN
            else -> Engine.PHONE
        }
        LangDetect.IG -> if (Yarn.allowed(prefs)) Engine.YARN else Engine.PHONE
        else -> Engine.PHONE
    }

    private fun run(text: String, lang: String?, g: Int) {
        var sentences = LangDetect.split(text)
        if (sentences.isEmpty()) return
        val langs = if (lang != null) List(sentences.size) { lang }
        else LangDetect.detectAll(sentences, LangDetect.EN) // phone screens are mostly English
        if (g != gen) return
        sentences = restoreTones(sentences, langs)
        if (g != gen) return

        val segs = merge(sentences, langs)
        if (BuildConfig.DEBUG) for (s in segs) android.util.Log.d("JarvisVoice", "[${s.lang}/${s.engine}] ${s.text}")
        val made = arrayOfNulls<Future<Pair<Int, ByteArray>?>>(segs.size)
        fun makeAhead(from: Int) {
            var ahead = 0
            for (j in from until segs.size) {
                if (segs[j].engine == Engine.PHONE) continue
                if (made[j] == null) made[j] = makers.submit<Pair<Int, ByteArray>?> { if (g == gen) audioFor(segs[j]) else null }
                if (++ahead >= 2) break
            }
        }
        for (i in segs.indices) {
            if (g != gen) return
            makeAhead(i)
            val s = segs[i]
            if (s.engine == Engine.PHONE) {
                phoneSay(s.text, s.lang, g)
                continue
            }
            val audio = waitFor(made[i]!!, g)
            if (g != gen) return
            if (audio == null) {
                phoneSay(s.text, s.lang, g) // couldn't make it: better an English voice than silence
            } else {
                // Meta's voice ignores punctuation, so add the pause between sentences ourselves.
                playPcm(audio.first, audio.second, if (s.engine == Engine.MMS) 280 else 120, g)
            }
        }
    }

    /**
     * Groups sentences for speaking. Phone-voice sentences in the same language are joined into one
     * utterance; YarnGPT needs at least 20 characters, so short ones are joined to a neighbour.
     */
    private fun merge(sentences: List<String>, langs: List<String>): List<Seg> {
        val out = ArrayList<Seg>()
        for (i in sentences.indices) {
            val lang = langs[i]
            val e = engineFor(lang)
            val last = out.lastOrNull()
            val join = last != null && last.lang == lang && last.engine == e &&
                (e == Engine.PHONE || (e == Engine.YARN && last.text.length < 20))
            if (join) out[out.size - 1] = Seg(last!!.text + " " + sentences[i], lang, e)
            else out.add(Seg(sentences[i], lang, e))
        }
        // A short YarnGPT piece with no same-language neighbour ("Kedu ka ị mere?") is padded with
        // dots, which YarnGPT counts but doesn't voice, rather than read with an English voice.
        return out.map {
            if (it.engine != Engine.YARN || it.text.trim().length >= 20) it
            else {
                var t = it.text.trim()
                while (t.length < 20) t += " ."
                Seg(t, it.lang, it.engine)
            }
        }
    }

    private fun audioFor(s: Seg): Pair<Int, ByteArray>? = when (s.engine) {
        Engine.MMS -> Mms.synth(app, s.lang, s.text)
            ?: if (Yarn.allowed(prefs)) Yarn.audio(prefs.yarnKey, s.text, app.cacheDir) else null
        Engine.YARN -> Yarn.audio(prefs.yarnKey, s.text, app.cacheDir)
        Engine.PHONE -> null
    }

    private fun <T> waitFor(f: Future<T>, g: Int): T? {
        while (g == gen) {
            try {
                return f.get(100, TimeUnit.MILLISECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
            } catch (e: Exception) {
                return null
            }
        }
        f.cancel(true)
        return null
    }

    /**
     * Plain-typed Yorùbá/Igbo/Hausa ("E kaaro, se daadaa ni?") is ambiguous without tone marks, so a
     * voice reads it flat or wrong. Gemini puts the marks back using the context. Internet only; if it
     * is slow, off, or fails, the text is read as written.
     */
    private fun restoreTones(sentences: List<String>, langs: List<String>): List<String> {
        if (!prefs.aiAllowed) return sentences
        val idx = sentences.indices.filter {
            langs[it] in setOf(LangDetect.YO, LangDetect.IG, LangDetect.HA) &&
                !LangDetect.hasToneMarks(sentences[it]) && sentences[it].trim().count { c -> c == ' ' } >= 2
        }
        if (idx.isEmpty()) return sentences
        val t0 = System.currentTimeMillis()
        val fixed = ai.restoreTonesSync(idx.map { sentences[it] }, idx.map { langs[it] }, 6000)
        if (BuildConfig.DEBUG) android.util.Log.d("JarvisVoice", "tone marks: ${System.currentTimeMillis() - t0} ms, ${if (fixed == null) "failed/slow, reading as written" else "ok"}")
        if (fixed == null) return sentences
        val out = sentences.toMutableList()
        for ((k, i) in idx.withIndex()) if (k < fixed.size && fixed[k].isNotBlank()) out[i] = fixed[k]
        return out
    }

    // ------------------------------------------------------------------ the phone's own voice

    private fun phoneSay(text: String, lang: String, g: Int) {
        if (!ttsReady.await(5, TimeUnit.SECONDS) || !ttsOk) return
        val t = tts ?: return
        for (chunk in chunk(text, 800)) {
            if (g != gen) return
            val id = "jarvis-" + System.nanoTime()
            val done = CountDownLatch(1)
            phoneDone[id] = done
            setLang(t, voiceTag(lang))
            if (t.speak(chunk, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS) {
                phoneDone.remove(id)
                continue
            }
            // Wait for this utterance to finish (bounded, in case the engine never reports back).
            val deadline = System.currentTimeMillis() + 15000 + chunk.length * 150L
            while (g == gen && !done.await(100, TimeUnit.MILLISECONDS)) {
                if (System.currentTimeMillis() > deadline) break
            }
            phoneDone.remove(id)
        }
    }

    /** The phone voice to ask for: Nigerian English for English and Pidgin, else the pack's or the language's own. */
    private fun voiceTag(lang: String): String = when (lang) {
        LangDetect.EN, LangDetect.PCM -> "en-NG"
        else -> Packs.load(app, lang).takeIf { it.code == lang }?.tts ?: lang
    }

    private fun setLang(t: TextToSpeech, tag: String) {
        val r = t.setLanguage(Locale.forLanguageTag(tag))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            val r2 = t.setLanguage(Locale("en", "NG"))
            if (r2 == TextToSpeech.LANG_MISSING_DATA || r2 == TextToSpeech.LANG_NOT_SUPPORTED) {
                t.setLanguage(Locale.US)
            }
        }
    }

    private fun chunk(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val out = ArrayList<String>()
        var rest = text
        while (rest.length > max) {
            var cut = rest.lastIndexOf(". ", max)
            if (cut < max / 2) cut = rest.lastIndexOf(' ', max)
            if (cut <= 0) cut = max
            out.add(rest.substring(0, cut + 1).trim())
            rest = rest.substring(cut + 1).trim()
        }
        if (rest.isNotEmpty()) out.add(rest)
        return out
    }

    // ------------------------------------------------------------------ raw audio (MMS, YarnGPT)

    /** Plays 16-bit mono audio plus [pauseMs] of silence, and waits until it has been heard. */
    private fun playPcm(rate: Int, pcm: ByteArray, pauseMs: Int, g: Int) {
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, rate))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            return
        }
        track = t
        try {
            val silence = ByteArray((rate * pauseMs / 1000) * 2)
            val all = pcm.size + silence.size
            t.play()
            var off = 0
            while (off < pcm.size && g == gen) {
                val n = t.write(pcm, off, minOf(8192, pcm.size - off))
                if (n <= 0) break
                off += n
            }
            if (g == gen) t.write(silence, 0, silence.size)
            val frames = all / 2
            val deadline = System.currentTimeMillis() + frames * 1000L / rate + 1000
            while (g == gen && t.playbackHeadPosition < frames && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
        } finally {
            track = null
            try { t.stop() } catch (e: Exception) {}
            try { t.release() } catch (e: Exception) {}
        }
    }
}
