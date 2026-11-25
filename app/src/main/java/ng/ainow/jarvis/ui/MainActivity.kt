package ng.ainow.jarvis.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import ng.ainow.jarvis.BuildConfig
import ng.ainow.jarvis.Prefs
import ng.ainow.jarvis.language.Packs
import ng.ainow.jarvis.listening.VoiceService
import ng.ainow.jarvis.speaking.Speaker

/** The launcher opens a home screen. Device setup and preferences live in Settings. */
class MainActivity : Activity() {
    companion object {
        /** Android allows Jarvis to open other apps while its home screen is visible. */
        @Volatile var showing = false
    }

    private lateinit var prefs: Prefs
    private lateinit var settings: Button
    private lateinit var prompt: TextView
    private lateinit var wakeHint: TextView
    private lateinit var start: Button
    private val main = Handler(Looper.getMainLooper())
    private var preview: Speaker? = null
    private val preferenceChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.LANGUAGE) updateHomeLanguage()
    }

    private val refresh = object : Runnable {
        override fun run() {
            updateListeningControls()
            main.postDelayed(this, 400)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val ui = JarvisScreen(this)
        val body = ui.page("Jarvis")
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val brand = ui.text("Jarvis", 24f, ui.accent).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        settings = ui.button("") { SettingsActivity.open(this) }
        header.addView(settings, LinearLayout.LayoutParams(0, -2, 1f))
        ui.add(body, header)

        fun gap() = body.addView(View(this), LinearLayout.LayoutParams(1, ui.dp(24), 1f))
        gap()
        prompt = ui.heading("", 38f).apply { gravity = Gravity.CENTER }
        ui.add(body, prompt)
        wakeHint = ui.text("", 18f).apply { gravity = Gravity.CENTER }
        ui.add(body, wakeHint, 16)
        start = ui.button("", primary = true) { startFromHome() }.apply { gravity = Gravity.CENTER }
        ui.add(body, start, 20)
        gap()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (showing) handleIntent()
    }

    override fun onResume() {
        super.onResume()
        showing = true
        prefs.registerChangeListener(preferenceChanged)
        updateHomeLanguage()
        startJarvisIfAllowed(prefs)
        LanguagePreviews.prepare(this)
        refresh.run()
        handleIntent()
    }

    override fun onPause() {
        showing = false
        prefs.unregisterChangeListener(preferenceChanged)
        main.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        preview?.shutdown()
        super.onDestroy()
    }

    private fun updateHomeLanguage() {
        val pack = Packs.load(this, prefs.lang)
        val copy = HomeText.labels(pack.code)
        prompt.text = HomeText.inLanguage(copy.prompt, pack)
        settings.text = HomeText.inLanguage(copy.settings, pack)
        wakeHint.text = HomeText.inLanguage(copy.wakeHint, pack)
    }

    private fun updateListeningControls() {
        val canRecord = hasJarvisPermission(Manifest.permission.RECORD_AUDIO)
        val running = canRecord && VoiceService.running
        wakeHint.visibility = if (running) View.VISIBLE else View.GONE
        start.visibility = if (running) View.GONE else View.VISIBLE
        if (!running) {
            val pack = Packs.load(this, prefs.lang)
            val copy = HomeText.labels(pack.code)
            val label = if (canRecord) copy.start else copy.microphone
            if (start.text.toString() != label) start.text = HomeText.inLanguage(label, pack)
        }
    }

    private fun startFromHome() {
        prefs.userStopped = false
        if (hasJarvisPermission(Manifest.permission.RECORD_AUDIO)) {
            startJarvisIfAllowed(prefs)
        } else {
            askJarvisPermissions(listOf(Manifest.permission.RECORD_AUDIO))
        }
    }

    private fun handleIntent() {
        if (intent.getBooleanExtra(SetupNotice.EXTRA_NEED_ACCESSIBILITY, false)) {
            intent.removeExtra(SetupNotice.EXTRA_NEED_ACCESSIBILITY)
            SettingsActivity.open(this, SettingsActivity.Section.SETUP)
        }
        if (BuildConfig.DEBUG && intent.getBooleanExtra("languages", false)) {
            intent.removeExtra("languages")
            logPhoneLanguages()
        }
        if (BuildConfig.DEBUG) intent.getStringExtra("speak")?.let { text ->
            intent.removeExtra("speak")
            val speaker = preview ?: Speaker(this) {}.also { preview = it }
            speaker.speak(text, null)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == JARVIS_PERMISSIONS) startJarvisIfAllowed(prefs)
    }

    private fun logPhoneLanguages() {
        var tts: android.speech.tts.TextToSpeech? = null
        tts = android.speech.tts.TextToSpeech(this) { status ->
            val voices = tts?.voices?.map { it.locale.toLanguageTag() + if (it.isNetworkConnectionRequired) "(net)" else "" }
            android.util.Log.d("JarvisLangs", "TTS status=$status voices=${voices?.distinct()?.sorted()?.joinToString()}")
            tts?.shutdown()
        }
        if (Build.VERSION.SDK_INT < 33) return
        val request = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        val recognizers = listOf(
            "default" to android.speech.SpeechRecognizer.createSpeechRecognizer(this),
            "ondevice" to if (android.speech.SpeechRecognizer.isOnDeviceRecognitionAvailable(this))
                android.speech.SpeechRecognizer.createOnDeviceSpeechRecognizer(this) else null,
        )
        for ((name, recognizer) in recognizers) {
            if (recognizer == null) continue
            recognizer.checkRecognitionSupport(request, mainExecutor, object : android.speech.RecognitionSupportCallback {
                override fun onSupportResult(result: android.speech.RecognitionSupport) {
                    android.util.Log.d("JarvisLangs", "STT $name installed=${result.installedOnDeviceLanguages.sorted()} downloadable=${result.supportedOnDeviceLanguages.sorted()} online=${result.onlineLanguages.sorted()}")
                    recognizer.destroy()
                }
                override fun onError(error: Int) {
                    android.util.Log.d("JarvisLangs", "STT $name error $error")
                    recognizer.destroy()
                }
            })
        }
    }
}
