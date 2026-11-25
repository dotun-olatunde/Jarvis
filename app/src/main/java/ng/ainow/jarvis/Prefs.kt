package ng.ainow.jarvis

import android.content.Context
import android.content.SharedPreferences

/** Small settings store. Everything stays on the phone. */
class Prefs(ctx: Context) {
    companion object {
        const val LANGUAGE = "lang"
        const val QUIET_EARS = "quietEars"
    }

    private val sp = ctx.applicationContext.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    var lang: String
        get() = sp.getString(LANGUAGE, "en") ?: "en"
        set(v) { sp.edit().putString(LANGUAGE, v).apply() }

    var replyInSelectedLanguage: Boolean
        get() = sp.getBoolean("replyInSelectedLanguage", false)
        set(v) { sp.edit().putBoolean("replyInSelectedLanguage", v).apply() }

    var detailedReplies: Boolean
        get() = sp.getBoolean("detailedReplies", false)
        set(v) { sp.edit().putBoolean("detailedReplies", v).apply() }

    var wakeName: String
        get() = sp.getString("wakeName", "Jarvis") ?: "Jarvis"
        set(v) { sp.edit().putString("wakeName", v.trim().ifEmpty { "Jarvis" }).apply() }

    /** Built into the app from local.properties, so users never have to get or type a key. */
    val aiKey: String get() = BuildConfig.GEMINI_API_KEY.trim()

    val yarnKey: String get() = BuildConfig.YARNGPT_API_KEY.trim()

    /** The user must agree before their voice is sent to YarnGPT. */
    var yarnConsent: Boolean
        get() = sp.getBoolean("yarnConsent", false)
        set(v) { sp.edit().putBoolean("yarnConsent", v).apply() }

    var confirmAll: Boolean
        get() = sp.getBoolean("confirmAll", false)
        set(v) { sp.edit().putBoolean("confirmAll", v).apply() }

    var requireName: Boolean
        get() = sp.getBoolean("requireName", true)
        set(v) { sp.edit().putBoolean("requireName", v).apply() }

    var quietEars: Boolean
        get() = sp.getBoolean(QUIET_EARS, true)
        set(v) { sp.edit().putBoolean(QUIET_EARS, v).apply() }

    fun registerChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sp.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sp.unregisterOnSharedPreferenceChangeListener(listener)
    }

    /** Internal threshold for the backup wake model, honoring values saved by earlier versions. */
    val wakeThreshold: Float
        get() = sp.getFloat("wakeThreshold", 0.15f)

    /** True only if the user pressed Stop. Otherwise Jarvis starts by itself whenever the app opens. */
    var userStopped: Boolean
        get() = sp.getBoolean("userStopped", false)
        set(v) { sp.edit().putBoolean("userStopped", v).apply() }

    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) { sp.edit().putBoolean("onboarded", v).apply() }

    /** Gemini is on whenever the builders shipped a key; the setup screen shows a data notice instead of asking. */
    val aiAllowed: Boolean get() = aiKey.isNotBlank()
}
