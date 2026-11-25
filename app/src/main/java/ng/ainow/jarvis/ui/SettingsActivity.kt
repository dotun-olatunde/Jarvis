package ng.ainow.jarvis.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import ng.ainow.jarvis.Prefs
import ng.ainow.jarvis.language.Packs
import ng.ainow.jarvis.listening.VoiceService

class SettingsActivity : Activity() {
    internal enum class Section(val title: String, val summary: String) {
        LANGUAGE("Language and replies", "Assistant language, reply style and voice services"),
        LISTENING("Listening", "Start or stop Jarvis, system sounds and fallback name"),
        SETUP("Accessibility and permissions", "Screen access, microphone, contacts and notifications"),
        PRIVACY("Safety and privacy", "Confirmations and the data notice"),
    }

    companion object {
        private const val SECTION = "section"

        internal fun open(activity: Activity, section: Section? = null) {
            activity.startActivity(Intent(activity, SettingsActivity::class.java).putExtra(SECTION, section?.name))
        }
    }

    private lateinit var prefs: Prefs
    private lateinit var ui: JarvisScreen
    private lateinit var body: LinearLayout
    private var language: Button? = null
    private var listening: Button? = null
    private var setupStatus: TextView? = null
    private var permissionsButton: Button? = null
    private val main = Handler(Looper.getMainLooper())
    private val refreshListening = object : Runnable {
        override fun run() {
            updateListeningButton()
            main.postDelayed(this, 400)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        ui = JarvisScreen(this)
        val section = Section.entries.firstOrNull { it.name == intent.getStringExtra(SECTION) }
        val title = section?.title ?: "Settings"
        body = ui.page(title)
        body.addView(ui.button("Back") { finish() }, -2, -2)
        ui.add(body, ui.heading(title), 24)
        when (section) {
            null -> index()
            Section.LANGUAGE -> languageAndReplies()
            Section.LISTENING -> listeningSettings()
            Section.SETUP -> setup()
            Section.PRIVACY -> privacy()
        }
    }

    override fun onResume() {
        super.onResume()
        language?.let { button ->
            val pack = Packs.load(this, prefs.lang)
            val label = android.text.SpannableStringBuilder("Assistant language: ")
            label.append(HomeText.inLanguage(pack.name, pack))
            button.text = label
        }
        if (listening != null) refreshListening.run()
        updateSetupStatus()
    }

    override fun onPause() {
        main.removeCallbacks(refreshListening)
        super.onPause()
    }

    private fun index() {
        for (section in Section.entries) {
            ui.add(body, ui.button(section.title) { open(this, section) }, 20)
            ui.add(body, ui.text(section.summary), 6)
        }
    }

    private fun languageAndReplies() {
        language = ui.button("Assistant language") { startActivity(Intent(this, LanguageActivity::class.java)) }
            .also { ui.add(body, it, 20) }
        choices(
            "Reply language", "Jarvis can follow supported language changes it detects while you speak.",
            "Follow the language I’m speaking", "Always use my assistant language", prefs.replyInSelectedLanguage,
        ) { prefs.replyInSelectedLanguage = it }
        choices(
            "Reply style", "Set how much detail Jarvis gives when answering questions or chatting.",
            "Brief replies", "More detailed replies", prefs.detailedReplies,
        ) { prefs.detailedReplies = it }
        heading("Voice services")
        check("Use YarnGPT for Nigerian voices and listening", prefs.yarnConsent) {
            prefs.yarnConsent = it
            if (it) LanguagePreviews.prepare(this)
        }
        description("Improves listening and voice options for Yorùbá, Igbo, Hausa and Nigerian Pidgin. Jarvis sends voice audio or reply text to YarnGPT over the internet when using this service.")
    }

    private fun listeningSettings() {
        description("Say “Hey Jarvis” and wait for the beep. Then tell Jarvis what you need.", 14)
        listening = ui.button("", primary = true) { toggleListening() }.also { ui.add(body, it, 16) }
        heading("System sounds")
        check("Mute system beeps while listening", prefs.quietEars) { prefs.quietEars = it }
        heading("Fallback name")
        description("If wake detection is unavailable, say this name in the same sentence as your command.")
        val field = EditText(this).apply {
            id = View.generateViewId()
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minHeight = ui.dp(56)
            setText(prefs.wakeName)
        }
        ui.add(body, ui.text("Name to use in a sentence", color = ui.ink).apply { labelFor = field.id }, 12)
        ui.add(body, field)
        val saved = ui.text("").apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        ui.add(body, ui.button("Save fallback name") {
            prefs.wakeName = field.text.toString()
            field.setText(prefs.wakeName)
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(field.windowToken, 0)
            saved.text = "Fallback name saved: ${prefs.wakeName}"
        }, 8)
        ui.add(body, saved, 6)
        ui.add(body, ui.button("View listening status") {
            val report = if (VoiceService.running) {
                listOf(VoiceService.modeName, VoiceService.lastWake, VoiceService.debug).joinToString("\n\n")
            } else "Jarvis is stopped."
            AlertDialog.Builder(this).setTitle("Listening status").setMessage(report).setPositiveButton("Close", null).show()
        }, 18)
    }

    private fun setup() {
        heading("Screen access")
        description("Turn on the Jarvis accessibility service so Jarvis can read the screen and operate apps for you.")
        ui.add(body, ui.button("Open Android accessibility settings", primary = true) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            try {
                startActivity(intent)
            } catch (_: android.content.ActivityNotFoundException) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }, 12)
        heading("App permissions")
        description("Microphone access lets Jarvis hear you. Contacts and notifications are optional.")
        setupStatus = ui.text("").apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
            .also { ui.add(body, it, 12) }
        permissionsButton = ui.button("Allow missing permissions") { askJarvisPermissions(wantedPermissions()) }
            .also { ui.add(body, it, 12) }
    }

    private fun privacy() {
        heading("Confirmations")
        description("Jarvis always asks for confirmation before actions like sending, posting, submitting, paying, buying or deleting.")
        check("Also ask before every tap", prefs.confirmAll) { prefs.confirmAll = it }
        heading("Your data")
        ui.add(body, ui.button("Read the data notice") {
            AlertDialog.Builder(this)
                .setTitle("Data notice")
                .setMessage(
                    "Most of what Jarvis does stays on your phone.\n\n" +
                        "When Jarvis cannot handle something by itself, like chatting, naming an unlabelled icon, " +
                        "or adding missing tone marks to Yorùbá, Igbo and Hausa, it may send what you said and text or " +
                        "pictures from your screen to Google Gemini over the internet.\n\n" +
                        "Jarvis never sends anything from a screen that shows a password.",
                )
                .setPositiveButton("Close", null)
                .show()
        }, 12)
    }

    private fun heading(label: String) = ui.add(body, ui.heading(label, 21f), 24)

    private fun description(label: String, top: Int = 6) = ui.add(body, ui.text(label), top)

    private fun check(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        ui.add(body, CheckBox(this).apply {
            text = label
            textSize = 16f
            minHeight = ui.dp(56)
            setPadding(0, ui.dp(8), ui.dp(8), ui.dp(8))
            isChecked = checked
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }, 8)
    }

    private fun choices(title: String, help: String, first: String, second: String, selectedSecond: Boolean, onChange: (Boolean) -> Unit) {
        heading(title)
        description(help)
        val group = RadioGroup(this)
        val ids = listOf(first, second).map { choice ->
            val option = RadioButton(this).apply {
                id = View.generateViewId()
                text = choice
                textSize = 16f
                minHeight = ui.dp(56)
                setPadding(0, ui.dp(8), ui.dp(8), ui.dp(8))
            }
            group.addView(option)
            option.id
        }
        group.check(if (selectedSecond) ids[1] else ids[0])
        group.setOnCheckedChangeListener { _, id -> onChange(id == ids[1]) }
        ui.add(body, group, 4)
    }

    private fun updateListeningButton() {
        val button = listening ?: return
        val label = when {
            VoiceService.running -> "Stop Jarvis"
            hasJarvisPermission(Manifest.permission.RECORD_AUDIO) -> "Start Jarvis"
            else -> "Allow microphone access"
        }
        if (button.text != label) button.text = label
    }

    private fun toggleListening() {
        prefs.userStopped = VoiceService.running
        if (prefs.userStopped) {
            stopService(Intent(this, VoiceService::class.java))
        } else if (hasJarvisPermission(Manifest.permission.RECORD_AUDIO)) {
            startJarvisIfAllowed(prefs)
        } else {
            askJarvisPermissions(listOf(Manifest.permission.RECORD_AUDIO))
        }
    }

    private fun wantedPermissions() = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun updateSetupStatus() {
        val status = setupStatus ?: return
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').any { it.substringBefore('/') == packageName }
        fun permissionState(permission: String) = if (hasJarvisPermission(permission)) "allowed" else "not allowed"
        status.text = buildList {
            add("Jarvis accessibility service: ${if (enabled) "on" else "off"}")
            add("Microphone: ${permissionState(Manifest.permission.RECORD_AUDIO)}")
            add("Contacts: ${permissionState(Manifest.permission.READ_CONTACTS)}")
            if (Build.VERSION.SDK_INT >= 33) add("Notifications: ${permissionState(Manifest.permission.POST_NOTIFICATIONS)}")
        }.joinToString("\n")
        permissionsButton?.visibility = if (wantedPermissions().all { hasJarvisPermission(it) }) View.GONE else View.VISIBLE
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == JARVIS_PERMISSIONS) {
            startJarvisIfAllowed(prefs)
            updateSetupStatus()
        }
    }
}
