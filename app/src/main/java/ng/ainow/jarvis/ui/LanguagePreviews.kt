package ng.ainow.jarvis.ui

import android.content.Context
import ng.ainow.jarvis.Prefs
import ng.ainow.jarvis.language.Packs
import ng.ainow.jarvis.listening.Yarn
import ng.ainow.jarvis.speaking.Mms

/** Keeps the existing offline voice downloads and prewarms the picker previews. */
internal object LanguagePreviews {
    @Volatile private var preparingMms = false
    @Volatile private var preparingYarn = false

    fun prepare(context: Context) {
        val app = context.applicationContext
        val prefs = Prefs(app)
        if (!preparingMms) {
            preparingMms = true
            val previews = listOf("yo", "ha").map { code -> code to LanguageActivity.previewText(Packs.load(app, code)) }
            Mms.downloadAll(app) {
                try {
                    for ((code, text) in previews) if (Mms.ready(app, code)) Mms.synth(app, code, text)
                } finally {
                    preparingMms = false
                }
            }
        }
        if (!preparingYarn && Yarn.allowed(prefs)) {
            preparingYarn = true
            val text = LanguageActivity.previewText(Packs.load(app, "ig"))
            Thread({
                try {
                    Yarn.prefetch(prefs.yarnKey, text, app.cacheDir)
                } finally {
                    preparingYarn = false
                }
            }, "yarn-preload").start()
        }
    }
}
