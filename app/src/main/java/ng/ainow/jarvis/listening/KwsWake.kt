package ng.ainow.jarvis.listening

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * Wake word by keyword spotting (sherpa-onnx, English GigaSpeech model, Apache-2.0, in assets/kws).
 * Unlike the "Hey Jarvis" model, this is a tiny speech recogniser that listens for any word in
 * assets/kws/keywords.txt, so it wakes on both "Jarvis" and "Hey Jarvis".
 *
 * To add or change a wake word, add a line to keywords.txt. Each word must be spelled in the
 * model's BPE pieces (e.g. JARVIS = "▁JA R VI S"); make them with the model's bpe.model and
 * sentencepiece, or sherpa-onnx's text2token tool.
 *
 * [onWake] and [onFailure] are called on the main thread. Start, stop, and release also run there.
 */
class KwsWake(
    ctx: Context,
    private val onWake: (String) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val spotter: KeywordSpotter

    @Volatile private var recorder: AudioRecord? = null
    private var thread: Thread? = null
    private var released = false

    init {
        val config = KeywordSpotterConfig().apply {
            featConfig = FeatureConfig().apply {
                sampleRate = SAMPLE_RATE
                featureDim = 80
            }
            modelConfig = OnlineModelConfig().apply {
                transducer = OnlineTransducerModelConfig().apply {
                    encoder = "kws/encoder.int8.onnx"
                    decoder = "kws/decoder.int8.onnx"
                    joiner = "kws/joiner.int8.onnx"
                }
                tokens = "kws/tokens.txt"
                modelType = "zipformer2"
                numThreads = 1
                provider = "cpu"
            }
            keywordsFile = "kws/keywords.txt"
            // Defaults from sherpa-onnx. Lower threshold = easier to wake (and more false alarms).
            keywordsScore = 1.5f
            keywordsThreshold = 0.25f
        }
        spotter = KeywordSpotter(ctx.assets, config)
    }

    /** Opens the microphone and starts listening. Throws if the microphone can't be opened. */
    @SuppressLint("MissingPermission") // the service checks RECORD_AUDIO before calling this
    fun start() {
        check(!released) { "keyword spotter was released" }
        if (recorder != null) return
        check(thread?.isAlive != true) { "previous wake listener is still stopping" }
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, CHUNK * 2 * 2)
        )
        try {
            check(rec.state == AudioRecord.STATE_INITIALIZED) { "microphone could not be opened" }
            rec.startRecording()
            check(rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "microphone did not start recording" }
            recorder = rec
            thread = Thread({ runListener(rec) }, "jarvis-kws").also { it.start() }
        } catch (error: Throwable) {
            recorder = null
            runCatching { rec.release() }
            throw error
        }
    }

    private fun runListener(rec: AudioRecord) {
        val worker = Thread.currentThread()
        val failure = try {
            loop(rec)
            null
        } catch (error: Throwable) {
            error
        } finally {
            runCatching { rec.release() }
        }
        main.post {
            if (thread !== worker) return@post
            thread = null
            if (released) {
                runCatching { spotter.release() }
            } else if (recorder === rec) {
                recorder = null
                if (failure != null) onFailure(failure)
            }
        }
    }

    private fun loop(rec: AudioRecord) {
        val stream = spotter.createStream("")
        try {
            val pcm = ShortArray(CHUNK)
            val samples = FloatArray(CHUNK)
            while (recorder === rec) {
                val n = rec.read(pcm, 0, pcm.size)
                if (recorder !== rec) break
                check(n >= 0) { "microphone read failed ($n)" }
                if (n == 0) continue
                for (i in 0 until n) samples[i] = pcm[i] / 32768f
                stream.acceptWaveform(if (n == CHUNK) samples else samples.copyOf(n), SAMPLE_RATE)
                while (recorder === rec && spotter.isReady(stream)) spotter.decode(stream)
                val word = spotter.getResult(stream).keyword
                if (word.isNotBlank()) {
                    spotter.reset(stream) // start fresh so one "Jarvis" fires once
                    main.post { if (recorder === rec) onWake(word) }
                }
            }
        } finally {
            stream.release()
        }
    }

    /** Stops listening and frees the microphone, so the speech recogniser can use it. */
    fun stop() {
        val rec = recorder
        recorder = null
        runCatching { rec?.stop() }
        runCatching { thread?.join(500) }
        if (thread?.isAlive != true) thread = null
    }

    fun release() {
        if (released) return
        released = true
        stop()
        // A worker still in native code will release the model after closing its stream.
        if (thread == null) runCatching { spotter.release() }
    }

    private companion object {
        const val SAMPLE_RATE = 16000
        const val CHUNK = 1600 // 100 ms
    }
}
