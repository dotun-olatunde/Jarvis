package ng.ainow.jarvis.listening

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.rementia.openwakeword.lib.WakeWordEngine
import com.rementia.openwakeword.lib.model.DetectionMode
import com.rementia.openwakeword.lib.model.WakeWordModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ng.ainow.jarvis.Prefs
import ng.ainow.jarvis.actions.Brain
import ng.ainow.jarvis.speaking.Speaker
import ng.ainow.jarvis.ui.SetupNotice

/**
 * Works like "Hey Google":
 *   WAKE     a small on-device model listens for "Jarvis" or "Hey Jarvis" (no beeps, little battery).
 *            First choice is the keyword spotter (KwsWake); if it fails, the older
 *            "Hey Jarvis"-only model (openWakeWord) is used instead
 *   SESSION  one beep, then the speech recogniser takes ONE command. If Jarvis asks a question
 *            ("are you sure?", "which one?") it listens for the answer; otherwise it plays a
 *            closing beep and returns to WAKE, so every new command needs the name again
 *   LOOP     fallback if the wake model cannot start: the old always-listening mode, where the
 *            name must be said inside the sentence
 */
class VoiceService : Service() {

    companion object {
        @Volatile
        var running = false
        @Volatile
        var modeName = "starting"
        @Volatile
        var lastWake = "No wake word heard yet"
        @Volatile
        var debug = "Recogniser: nothing yet"
    }

    private enum class Mode { WAKE, SESSION, LOOP }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var speaker: Speaker
    private lateinit var brain: Brain
    private var rec: SpeechRecognizer? = null
    private var yarnEars: Yarn.Listener? = null
    private var yarnFailures = 0
    private var listening = false
    private var sttFallback = false
    private var sttLanguage: String? = null
    private var muted = false
    private var mode = Mode.LOOP
    private val preferenceChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.QUIET_EARS) quiet(prefs.quietEars)
    }

    private var kws: KwsWake? = null
    private var kwsFailed = false
    private var wake: WakeWordEngine? = null
    private var wakeScope: CoroutineScope? = null
    private var wakeJob: Job? = null
    private var tone: ToneGenerator? = null
    private var silentTurns = 0
    /** Set once the current session's command has been acted on. */
    private var handledInSession = false

    /** Ignore wake detections this long after Jarvis stops talking, so it cannot wake itself. */
    private val SELF_VOICE_MS = 1500L
    private val answerTimedOut = Runnable { endSession() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        prefs.registerChangeListener(preferenceChanged)
        startInForeground()
        speaker = Speaker(this) { maybeListen() }
        brain = Brain(this, prefs) { text, lang -> speaker.speak(text, lang) }
        brain.onSettled = { maybeListen() }
        brain.onQuit = { main.postDelayed({ stopSelf() }, 1500) }
        running = true
        quiet(true)
        try { tone = ToneGenerator(AudioManager.STREAM_MUSIC, 70) } catch (e: Exception) { tone = null }
        startWake()
        brain.welcome()
        maybeListen()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        prefs.unregisterChangeListener(preferenceChanged)
        running = false
        main.removeCallbacksAndMessages(null)
        stopWake(release = true)
        try { kws?.release() } catch (e: Throwable) {}
        kws = null
        try { rec?.destroy() } catch (e: Exception) {}
        rec = null
        yarnEars?.cancel()
        yarnEars = null
        try { tone?.release() } catch (e: Exception) {}
        speaker.shutdown()
        quiet(false)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ wake word

    private fun startWake() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            enterLoop("Microphone permission is missing.")
            return
        }
        if (startKws()) return
        try {
            val th = prefs.wakeThreshold
            val model = WakeWordModel("Hey Jarvis", "hey_jarvis_v0.1.onnx", th)
            brain.wakePhrase = "Hey Jarvis"

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val engine = WakeWordEngine(this, listOf(model), DetectionMode.SINGLE_BEST, 2000L, CoroutineScope(Dispatchers.Default))
            wakeScope = scope
            wake = engine
            wakeJob = scope.launch {
                engine.detections.collect { d ->
                    val score = "score " + String.format("%.2f", d.score) + " (threshold " + String.format("%.2f", th) + ")"
                    wakeDetected(score)
                }
            }
            engine.start { error ->
                main.post {
                    if (running && mode == Mode.WAKE && wake === engine) {
                        Log.w("JarvisWake", "Backup wake listener lost microphone access", error)
                        stopWake(release = true)
                        enterLoop("The backup wake listener stopped: ${error.javaClass.simpleName}")
                    }
                }
            }
            mode = Mode.WAKE
            modeName = "waiting for \"" + brain.wakePhrase + "\""
        } catch (e: Throwable) {
            stopWake(release = true)
            enterLoop("The wake word could not start: " + e.javaClass.simpleName)
        }
    }

    /** Keyword spotter: wakes on "Jarvis" and "Hey Jarvis". False if it can't run on this phone. */
    private fun startKws(): Boolean {
        if (kwsFailed) return false
        try {
            val k = kws ?: KwsWake(
                this,
                onWake = { word -> wakeDetected("heard " + word.replace('_', ' ').lowercase()) },
                onFailure = { error ->
                    if (running && mode == Mode.WAKE) {
                        fallBackFromKws("stopped", error)
                        startWakeWhenQuiet()
                    }
                },
            )
            kws = k
            k.start()
            brain.wakePhrase = "Jarvis"
            mode = Mode.WAKE
            modeName = "waiting for \"Jarvis\" or \"Hey Jarvis\""
            return true
        } catch (error: Throwable) {
            fallBackFromKws("could not start", error)
            return false
        }
    }

    private fun fallBackFromKws(reason: String, error: Throwable) {
        kwsFailed = true
        kws?.release()
        kws = null
        debug = "Keyword spotter $reason (${error.javaClass.simpleName}), switching to the Hey Jarvis model"
        Log.w("JarvisWake", "Keyword spotter $reason", error)
    }

    /** Either wake engine heard the name. [what] is shown on the setup screen for debugging. */
    private fun wakeDetected(what: String) {
        if (hearingOwnVoice()) {
            lastWake = "Ignored wake while Jarvis was speaking: $what"
        } else {
            lastWake = "Last wake: $what"
            onWakeHeard()
        }
    }

    /** True while Jarvis is talking or has only just stopped (it says "Hey Jarvis" in some replies). */
    private fun hearingOwnVoice(): Boolean =
        speaker.busy || android.os.SystemClock.elapsedRealtime() - speaker.lastDoneAt < SELF_VOICE_MS

    /** Starts the wake word only once Jarvis has finished talking. */
    private fun startWakeWhenQuiet() {
        if (mode != Mode.WAKE || !running) return
        if (speaker.busy || brain.busy) {
            main.postDelayed({ startWakeWhenQuiet() }, 300)
            return
        }
        startWake()
    }

    private fun stopWake(release: Boolean) {
        // The keyword spotter always frees the microphone; its model stays loaded for next time.
        try { kws?.stop() } catch (e: Throwable) {}
        try { wake?.stop() } catch (e: Throwable) {}
        if (release) {
            try { wakeJob?.cancel() } catch (e: Throwable) {}
            try { wake?.release() } catch (e: Throwable) {}
            try { wakeScope?.cancel() } catch (e: Throwable) {}
            wake = null
            wakeJob = null
            wakeScope = null
        }
    }

    private fun enterLoop(why: String) {
        mode = Mode.LOOP
        modeName = "listening for the name in each sentence ($why)"
        speaker.speak("$why I will listen for my name in each sentence instead.", "en-NG")
        maybeListen()
    }

    private fun onWakeHeard() {
        if (mode != Mode.WAKE) return
        mode = Mode.SESSION
        silentTurns = 0
        handledInSession = false
        // Fully release the wake word's microphone: two recorders at once can leave the recogniser deaf.
        stopWake(release = true)
        beep(true)
        main.postDelayed({ if (mode == Mode.SESSION) listen() }, 700)
    }

    private fun endSession() {
        if (mode != Mode.SESSION) return
        mode = Mode.WAKE
        main.removeCallbacks(answerTimedOut)
        resetRecognizer()
        yarnEars?.cancel()
        yarnEars = null
        yarnFailures = 0 // try YarnGPT again next time
        brain.endConversation()
        listening = false
        beep(false)
        modeName = "waiting for \"" + brain.wakePhrase + "\""
        main.postDelayed({ startWakeWhenQuiet() }, 600)
    }

    private fun beep(start: Boolean) {
        try {
            tone?.startTone(if (start) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_BEEP2, 120)
        } catch (e: Exception) {}
    }

    // ------------------------------------------------------------------ foreground notification, sounds

    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("jarvis", "Jarvis", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "jarvis")
            .setContentTitle("Jarvis is listening")
            .setContentText("Waiting for the wake phrase")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(SetupNotice.openJarvis(this))
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
    }

    /** Silences the system sounds that the recogniser makes each time it starts. */
    private fun quiet(on: Boolean) {
        if (!prefs.quietEars && on) return
        if (on == muted) return
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        for (s in intArrayOf(AudioManager.STREAM_SYSTEM, AudioManager.STREAM_NOTIFICATION)) {
            try {
                am.adjustStreamVolume(s, if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
            } catch (e: Exception) {
            }
        }
        muted = on
    }

    // ------------------------------------------------------------------ speech recogniser

    private fun maybeListen() {
        if (!running || mode == Mode.WAKE || listening || speaker.busy || brain.busy) return
        // Start the clock after the prompt and keep the same deadline across recognition attempts.
        val answerMs = brain.startListeningForAnswer()?.takeIf { it > 0 }
        if (mode == Mode.SESSION) {
            if (answerMs != null) {
                modeName = "waiting for your answer"
                main.removeCallbacks(answerTimedOut)
                main.postDelayed(answerTimedOut, answerMs)
            } else if (handledInSession) {
                endSession()
                return
            }
        }
        listen(answerMs)
    }

    private fun listen(answerMs: Long? = null) {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }
        // After "Hey Jarvis": YarnGPT hears all five Nigerian languages, even mixed in one sentence.
        // Not used in the always-listening fallback mode, where it would stream (and bill) all day.
        if (mode == Mode.SESSION && Yarn.allowed(prefs) && yarnFailures < 2) {
            yarnListen(answerMs)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speaker.speak("Speech recognition is not available on this phone.", "en-US")
            main.postDelayed({ stopSelf() }, 3000)
            return
        }
        if (rec == null) {
            rec = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(listener) }
        }
        val selected = brain.pack.stt
        if (selected != sttLanguage) {
            sttLanguage = selected
            sttFallback = false
        }
        val tag = if (sttFallback) "en-US" else selected
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        listening = true
        try {
            rec?.startListening(i)
        } catch (e: Exception) {
            listening = false
            resetRecognizer()
            retryIn(1000)
        }
    }

    private fun yarnListen(answerMs: Long?) {
        listening = true
        lateinit var ears: Yarn.Listener
        ears = Yarn.Listener(prefs.yarnKey, object : Yarn.Ears {
            override fun onText(text: String) {
                if (yarnEars !== ears) return
                listening = false
                yarnEars = null
                yarnFailures = 0
                silentTurns = 0
                debug = "Heard (YarnGPT): $text"
                handle(listOf(text))
            }

            override fun onSilence() {
                if (yarnEars !== ears) return
                listening = false
                yarnEars = null
                yarnFailures = 0
                quietTurn()
            }

            override fun onFailed(why: String) {
                if (yarnEars !== ears) return
                listening = false
                yarnEars = null
                yarnFailures++
                debug = "YarnGPT listening failed: $why"
                // Two failures in a row (no internet, out of credit): use the phone's recogniser.
                retryIn(300)
            }
        }, waitForSpeechMs = answerMs ?: Yarn.Listener.NO_SPEECH_MS)
        yarnEars = ears
        ears.start()
    }

    /** Passes what was heard to the brain, then listens again, waits for an answer, or ends the session. */
    private fun handle(alts: List<String>) {
        val inSession = mode == Mode.SESSION
        val alreadyHandled = handledInSession
        // Set before heard(): the brain may call back into maybeListen() while handling it.
        if (inSession) {
            main.removeCallbacks(answerTimedOut)
            handledInSession = true
        }
        val acted = brain.heard(alts, addressed = inSession)
        if (inSession && !acted) {
            handledInSession = alreadyHandled
            quietTurn()          // heard words but nothing to act on: treat like silence
            return
        }
        maybeListen()
    }

    private fun resetRecognizer() {
        try { rec?.destroy() } catch (e: Exception) {}
        rec = null
    }

    private fun retryIn(ms: Long) {
        main.postDelayed({ maybeListen() }, ms)
    }

    private fun languageMissing() {
        if (!sttFallback) {
            sttFallback = true
            if (brain.pack.stt != "en-NG") {
                speaker.speak(
                    "Your phone cannot listen in " + brain.pack.name + " yet. I will listen in English.",
                    "en-NG"
                )
            }
            retryIn(300)
        } else if (mode == Mode.SESSION) {
            debug = "Recogniser says the language is unavailable even in plain English"
            endSession()
        } else {
            retryIn(5000)
        }
    }

    /** Nothing was said or nothing understood. In a session that means "I'm done". */
    private fun quietTurn() {
        if (mode == Mode.SESSION) {
            if (handledInSession) {
                // A recognizer may report silence before the answer window has elapsed.
                if (brain.awaitingAnswer) retryIn(150) else endSession()
                return
            }
            silentTurns++
            when (silentTurns) {
                1 -> brain.announce("prompt")                       // "I am listening. What would you like?"
                else -> {
                    brain.announce("idle_bye", brain.wakePhrase)
                    main.postDelayed({ endSession() }, 300)
                }
            }
        } else {
            retryIn(150)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            if (!running || mode == Mode.WAKE) return
            listening = false
            debug = "Recogniser error $error (mode $mode)"
            when (error) {
                9 -> stopSelf()                                  // permissions missing
                12, 13 -> languageMissing()                      // language not supported / available
                5, 8 -> { resetRecognizer(); retryIn(800) }      // client error / recogniser busy
                2, 4 -> when {
                    mode == Mode.LOOP -> retryIn(2000)
                    brain.awaitingAnswer -> retryIn(1000)
                    else -> endSession()
                }
                else -> quietTurn()                              // 6 silence, 7 no match
            }
        }

        override fun onResults(results: Bundle?) {
            if (!running || mode == Mode.WAKE) return
            listening = false
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
            if (list.isEmpty()) {
                quietTurn()
                return
            }
            silentTurns = 0
            debug = "Heard: " + list[0]
            handle(list)
        }
    }
}
