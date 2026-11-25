package ng.ainow.jarvis.actions

import android.content.Intent
import android.provider.Settings
import ng.ainow.jarvis.language.Matcher

/**
 * "Open storage settings", "open wifi", "bluetooth settings": goes straight to that page of the
 * phone's Settings app using Android's standard shortcuts, so it works offline on any phone.
 * Names are English only for now (see LIMITATIONS.md).
 */
object SettingsPages {
    class Page(val title: String, val action: String, val names: List<String>)

    val pages = listOf(
        Page("Wi-Fi", Settings.ACTION_WIFI_SETTINGS, listOf("wifi", "wi fi", "wireless", "wlan")),
        Page("Bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS, listOf("bluetooth")),
        Page("storage", Settings.ACTION_INTERNAL_STORAGE_SETTINGS, listOf("storage", "memory", "space", "phone storage")),
        Page("display", Settings.ACTION_DISPLAY_SETTINGS, listOf("display", "screen", "brightness", "font size", "text size")),
        Page("sound", Settings.ACTION_SOUND_SETTINGS, listOf("sound", "sounds", "volume", "ringtone", "vibration")),
        Page("battery", Intent.ACTION_POWER_USAGE_SUMMARY, listOf("battery", "power", "battery saver")),
        Page("location", Settings.ACTION_LOCATION_SOURCE_SETTINGS, listOf("location", "gps")),
        Page("language", Settings.ACTION_LOCALE_SETTINGS, listOf("language", "languages", "phone language")),
        Page("keyboard", Settings.ACTION_INPUT_METHOD_SETTINGS, listOf("keyboard", "keyboards", "input")),
        Page("date and time", Settings.ACTION_DATE_SETTINGS, listOf("date", "time", "date and time", "clock")),
        Page("apps", Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS, listOf("apps", "applications", "app list", "installed apps")),
        Page("mobile network", Settings.ACTION_WIRELESS_SETTINGS, listOf("mobile data", "data", "network", "internet", "sim", "mobile network", "hotspot")),
        Page("airplane mode", Settings.ACTION_AIRPLANE_MODE_SETTINGS, listOf("airplane", "airplane mode", "aeroplane mode", "flight mode")),
        Page("security", Settings.ACTION_SECURITY_SETTINGS, listOf("security", "screen lock", "password", "fingerprint", "pin")),
        Page("privacy", Settings.ACTION_PRIVACY_SETTINGS, listOf("privacy")),
        Page("NFC", Settings.ACTION_NFC_SETTINGS, listOf("nfc")),
        Page("about phone", Settings.ACTION_DEVICE_INFO_SETTINGS, listOf("about phone", "about", "phone info", "device info")),
        Page("accessibility", Settings.ACTION_ACCESSIBILITY_SETTINGS, listOf("accessibility")),
    )

    private val DROP = setOf("setting", "settings", "page", "the", "my", "phone's", "options", "menu")

    /** True if the user said the word "settings" ("storage settings", "settings for wifi"). */
    fun mentionsSettings(name: String): Boolean =
        Matcher.words(name).any { it == "setting" || it == "settings" }

    /** The page for [name] ("storage settings" -> storage), or null if it isn't a known page. */
    fun find(name: String): Page? {
        val words = Matcher.words(name).filter { it !in DROP }
        if (words.isEmpty()) return null // plain "settings": open the Settings app itself
        val said = words.joinToString(" ")
        // Whole name first ("date and time"), then any single known word ("wifi page please").
        pages.firstOrNull { p -> p.names.any { Matcher.norm(it) == said } }?.let { return it }
        return pages.firstOrNull { p -> p.names.any { n -> Matcher.containsWords(words, Matcher.words(n)) } }
    }
}
