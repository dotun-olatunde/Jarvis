package ng.ainow.jarvis.actions

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.ContactsContract
import java.net.URLEncoder
import ng.ainow.jarvis.Prefs
import ng.ainow.jarvis.ai.AiClient
import ng.ainow.jarvis.language.Cmd
import ng.ainow.jarvis.language.Labels
import ng.ainow.jarvis.language.LangDetect
import ng.ainow.jarvis.language.Matcher
import ng.ainow.jarvis.language.Nlu
import ng.ainow.jarvis.language.Pack
import ng.ainow.jarvis.language.Packs
import ng.ainow.jarvis.ui.MainActivity
import ng.ainow.jarvis.ui.SetupNotice
import ng.ainow.jarvis.language.PhoneNumber
import org.json.JSONObject

/** Nigerian packs whose commands are always understood, for code-switching. */
private val NIGERIAN = listOf("pcm", "yo", "ig", "ha")

/**
 * Decides what to do with what the user said. Order of preference:
 *   1. rule-based commands from the language pack  (free, instant, offline)
 *   2. on-device OCR for buttons the screen reader cannot name  (free, offline)
 *   3. Gemini, only for chat, strange sentences and naming icons  (needs the built-in key)
 * Everything runs on the main thread.
 */
class Brain(
    private val ctx: Context,
    private val prefs: Prefs,
    /** Says text. The second value is the language code, or null to detect it per sentence. */
    private val output: (String, String?) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val ai = AiClient(prefs)
    private val ocr = Ocr()
    private val en: Pack = Packs.load(ctx, "en")
    /** Read fresh each time so a language picked in Settings applies straight away. */
    val pack: Pack get() = Packs.load(ctx, prefs.lang)
    /**
     * Try the detected language, selected language, English and the other Nigerian languages.
     * The reply language preference does not change which commands Jarvis can understand.
     */
    private val packs: List<Pack>
        get() = (listOfNotNull(spokenLang?.let { Packs.load(ctx, it) }, pack, en) + NIGERIAN.map { Packs.load(ctx, it) })
            .distinctBy { it.code }

    /**
     * The language heard during this conversation. It can change as the user switches languages.
     */
    private var spokenLang: String? = null
    val replyPack: Pack get() = if (prefs.replyInSelectedLanguage) pack else spokenLang?.let { Packs.load(ctx, it) } ?: pack

    fun endConversation() {
        spokenLang = null
        clearPending()
    }

    /** Jarvis's own words, in the language of the conversation. */
    private fun speak(text: String) = output(text, replyPack.code)

    /** Text from the screen: each sentence is read in the language it is written in. */
    private fun read(text: String) = output(text, null)

    var onSettled: (() -> Unit)? = null
    var onQuit: (() -> Unit)? = null

    private var work = 0
    val busy: Boolean get() = work > 0

    private val answerWindow = AnswerWindow(SystemClock::elapsedRealtime)
    private var pendingYes: (() -> Unit)? = null
    private var pendingChoice: List<() -> Unit>? = null
    /** "send message to" with no name: the next thing said is the name or number (command id). */
    private var pendingArg: String? = null

    private fun begin() { work++ }
    private fun end() {
        work = maxOf(0, work - 1)
        if (work == 0) onSettled?.invoke()
    }

    private fun t(key: String, vararg a: Any): String {
        val s = replyPack.say[key] ?: en.say[key] ?: key
        return if (a.isEmpty()) s else String.format(s, *a)
    }

    /**
     * A phrase missing from the reply pack falls back to English. Speak that English text with
     * an English voice, otherwise a Yorùbá (or other) voice reads English words with its accent.
     */
    private fun say(key: String, vararg a: Any) {
        val lang = if (replyPack.say[key] == null && en.say[key] != null) en.code else replyPack.code
        // Menu names and English words inside the phrase get their own voice (see Labels).
        for (piece in Labels.split(t(key, *a), lang) { Labels.phone(ctx, it) }) output(piece.text, piece.lang)
    }

    /** For the service: speak one of the pack's phrases. */
    fun announce(key: String, vararg a: Any) = say(key, *a)

    private fun clearPending() {
        pendingYes = null
        pendingChoice = null
        pendingArg = null
        answerWindow.reset()
    }

    /** First start: introduce Jarvis and say what to do next. Spoken once. */
    fun welcome() {
        if (prefs.onboarded) return
        prefs.onboarded = true
        say("welcome", wakePhrase)
        if (JarvisAccessibilityService.inst == null) say("setup_hint")
    }

    /** Set by the service so the welcome can name the real wake phrase. */
    var wakePhrase: String = "Hey Jarvis"

    // ------------------------------------------------------------------ hearing

    private val hasPendingQuestion: Boolean
        get() = pendingYes != null || pendingChoice != null || pendingArg != null

    /** A built-in question is pending while it is spoken and during the answer window. */
    val awaitingAnswer: Boolean
        get() = hasPendingQuestion && answerWindow.remainingMs != 0L

    fun startListeningForAnswer(): Long? = if (hasPendingQuestion) answerWindow.start() else null

    /**
     * [alts] are the recogniser's alternatives, best first.
     * Returns true if Jarvis acted on what it heard, false if it ignored it.
     * Jarvis only acts when it was called by name (or the wake word engine heard the name),
     * except for the answer to a question it has just asked.
     */
    fun heard(alts: List<String>, addressed: Boolean = false): Boolean {
        if (hasPendingQuestion && !awaitingAnswer) clearPending()
        // [addressed] = the wake word engine already heard the call, so no name is needed in the sentence.
        val cands = alts.map { Nlu.stripWake(it, prefs.wakeName) }.map { Pair(it.first || addressed, it.second) }

        // Reply in the language the user just spoke (a Yorùbá/English mix counts as Yorùbá).
        cands.firstOrNull()?.second?.let { LangDetect.quick(it) }?.let { spokenLang = it }

        // Asked "who to?" after "send message to": what they say now is the name or number,
        // unless it is a whole new command ("open YouTube").
        val pa = pendingArg
        if (pa != null) {
            val rest = cands.firstOrNull()?.second.orEmpty()
            if (rest.isNotBlank()) {
                clearPending()
                if (Nlu.parse(rest, packs) is Cmd.Unknown) {
                    run(Nlu.argument(pa, rest, packs))
                    return true
                }
            }
        }

        // Waiting for "yes/no" or "first/second"?
        if (pendingYes != null || pendingChoice != null) {
            val py = pendingYes
            val pc = pendingChoice
            for ((_, rest) in cands) {
                if (pc != null) {
                    val n = Nlu.ordinal(rest, packs)
                    if (n != null && n in 1..pc.size) {
                        clearPending()
                        pc[n - 1]()
                        return true
                    }
                }
                val c = Nlu.parse(rest, packs)
                if (c is Cmd.Simple && c.id == "yes" && py != null) {
                    clearPending()
                    py()
                    return true
                }
                if (c is Cmd.Simple && c.id == "no") {
                    clearPending()
                    say("ok")
                    return true
                }
            }
            // Keep the question open through noise or a misheard answer. A new command can replace it.
            val replacement = cands.any { (had, rest) ->
                if (!had || rest.isBlank()) false else when (val cmd = Nlu.parse(rest, packs)) {
                    is Cmd.Unknown -> false
                    is Cmd.Arg -> true
                    is Cmd.Simple -> cmd.id != "yes"
                }
            }
            if (!replacement) return false
            clearPending()
        }

        var chosen: Cmd? = null
        var unknown: Cmd? = null
        var unknownHad = false
        for ((had, rest) in cands) {
            // No follow-up window: every new command needs the name again.
            if (!had && prefs.requireName) continue
            if (rest.isBlank()) continue
            val c = Nlu.parse(rest, packs)
            if (c is Cmd.Unknown) {
                if (unknown == null) {
                    unknown = c
                    unknownHad = had
                }
            } else {
                chosen = c
                break
            }
        }
        if (chosen == null && !unknownHad && !prefs.aiAllowed) return false
        val cmd = chosen ?: unknown ?: return false
        run(cmd)
        return true
    }

    private fun run(cmd: Cmd) {
        when (cmd) {
            is Cmd.Simple -> simple(cmd.id)
            is Cmd.Arg -> withArg(cmd)
            is Cmd.Unknown -> askAi(cmd.text)
        }
    }

    // ------------------------------------------------------------------ simple commands

    private fun simple(id: String) {
        val svc = JarvisAccessibilityService.inst
        val needsService = id !in setOf(
            "help", "quit", "volume_up", "volume_down", "yes", "no", "open_accessibility", "open_app_settings"
        )
        if (needsService && svc == null) {
            say("noservice")
            return
        }
        when (id) {
            "back" -> svc?.global(AccessibilityService.GLOBAL_ACTION_BACK)
            "home" -> svc?.global(AccessibilityService.GLOBAL_ACTION_HOME)
            "recents" -> svc?.global(AccessibilityService.GLOBAL_ACTION_RECENTS)
            "notifications" -> svc?.global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            "quick_settings" -> svc?.global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            "lock" -> svc?.global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            "scroll_down" -> svc?.scroll(true)
            "scroll_up" -> svc?.scroll(false)
            "read_screen" -> readScreen(svc!!)
            "read_buttons" -> readButtons(svc!!)
            "clear_text" -> {
                val f = svc?.focusedInput()
                if (f == null) say("no_input") else svc?.setText(f, "")
            }
            "volume_up", "volume_down" -> {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    if (id == "volume_up") AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                    0
                )
            }
            "help" -> say("help")
            "open_accessibility" -> launch(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            "open_app_settings" -> launch(
                Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + ctx.packageName)
                )
            )
            "quit" -> {
                speak(t("bye"))
                onQuit?.invoke()
            }
            else -> {} // yes / no with nothing pending: ignore
        }
    }

    private fun readScreen(svc: JarvisAccessibilityService) {
        val scr = svc.scan()
        if (scr.texts.isEmpty()) say("empty_screen") else read(scr.texts.joinToString(". "))
    }

    private fun readButtons(svc: JarvisAccessibilityService) {
        val scr = svc.scan()
        val names = scr.controls.filter { !it.unlabeled && it.label.isNotBlank() }.map { it.label }.distinct().take(25)
        val unl = scr.controls.count { it.unlabeled }
        if (names.isEmpty() && unl == 0) { say("empty_screen"); return }
        // "Buttons:" in the conversation's language, then each name in its own language.
        if (names.isNotEmpty()) {
            say("buttons")
            read(names.joinToString(". ") + ".")
        }
        if (unl > 0) say("unlabeled", unl)
    }

    // ------------------------------------------------------------------ commands with an argument

    private fun withArg(c: Cmd.Arg) {
        if (c.arg.isBlank() && c.id != "fill") {
            clearPending()
            pendingArg = c.id
            say("need_arg")
            return
        }
        when (c.id) {
            "tap" -> tap(c.arg)
            "open" -> openThen(c.arg)
            "call" -> call(c.arg)
            "whatsapp" -> whatsapp(c.arg)
            "search" -> googleSearch(c.arg)
            "type" -> typeText(c.arg)
            "fill" -> fill(c.arg, c.arg2)
            "language" -> switchLanguage(c.arg)
            else -> say("notunderstood")
        }
    }

    /**
     * Android blocks apps in the background from opening other screens. Jarvis is allowed while its
     * accessibility service is on (or its own screen is in front). Otherwise say so, instead of
     * saying "Opening..." and then nothing happening.
     */
    private fun canOpen(): Boolean {
        if (JarvisAccessibilityService.inst != null || MainActivity.showing) return true
        say("noservice_open")
        SetupNotice.show(ctx) // tapping a notification is the one way back in Android allows
        return false
    }

    private fun launch(i: Intent) {
        if (!canOpen()) return
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            val host: Context = JarvisAccessibilityService.inst ?: ctx
            host.startActivity(i)
        } catch (e: Exception) {
            say("openfail")
        }
    }

    // ---- tapping: accessibility tree first, then OCR, then icon naming by AI ----

    private fun needsConfirm(label: String): Boolean {
        if (prefs.confirmAll) return true
        val lw = Matcher.words(label)
        return packs.any { p -> p.risky.any { Matcher.containsWords(lw, Matcher.words(it)) } }
    }

    private fun confirmThen(label: String, act: () -> Unit) {
        if (needsConfirm(label)) {
            clearPending()
            pendingYes = act
            say("confirm", label)
        } else {
            act()
        }
    }

    private fun tap(target: String) {
        val svc = JarvisAccessibilityService.inst
        if (svc == null) { say("noservice"); return }
        val scr = svc.scan()
        val scored = scr.controls
            .map { it to Matcher.score(target, it.label) * (if (it.weak) 0.9 else 1.0) }
            .filter { it.second >= 0.6 }
            .sortedByDescending { it.second }
        if (scored.isEmpty()) {
            ocrTap(target, svc, scr)
            return
        }
        val top = scored[0].second
        val ties = scored.filter { top - it.second < 0.05 }
            .map { it.first }
            .sortedWith(compareBy({ it.rect.top }, { it.rect.left }))
            .take(5)
        if (ties.size >= 2) {
            clearPending()
            pendingChoice = ties.map { item -> { clickItem(svc, item) } }
            say("choose", ties.size, target)
            return
        }
        clickItem(svc, ties[0])
    }

    private fun clickItem(svc: JarvisAccessibilityService, item: Item) {
        if (!item.enabled) {
            say("disabled", item.label)
            return
        }
        confirmThen(item.label) {
            begin()
            svc.click(item) { ok ->
                main.post {
                    if (!ok) say("tapfail")
                    end()
                }
            }
        }
    }

    private fun tapPoint(svc: JarvisAccessibilityService, rect: Rect, label: String) {
        confirmThen(label) {
            begin()
            svc.tapAt(rect.exactCenterX(), rect.exactCenterY()) { ok ->
                main.post {
                    if (!ok) say("tapfail")
                    end()
                }
            }
        }
    }

    private fun ocrTap(target: String, svc: JarvisAccessibilityService, scr: Screen) {
        if (Build.VERSION.SDK_INT < 30) {
            say("notfound", target)
            return
        }
        begin()
        svc.screenshot { bmp ->
            main.post {
                if (bmp == null) {
                    say("no_screenshot")
                    end()
                } else {
                    ocr.read(bmp) { lines ->
                        main.post {
                            val hit = lines
                                .map { it to Matcher.score(target, it.text) }
                                .filter { it.second >= 0.6 }
                                .sortedWith(
                                    compareByDescending<Pair<OcrLine, Double>> { it.second }
                                        .thenBy { it.first.rect.width() * it.first.rect.height() }
                                )
                                .firstOrNull()
                            if (hit != null) {
                                tapPoint(svc, hit.first.rect, hit.first.text)
                                end()
                            } else {
                                iconFallback(target, svc, scr, bmp)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun crop(bmp: Bitmap, r: Rect): Bitmap? {
        val l = r.left.coerceIn(0, bmp.width - 1)
        val tp = r.top.coerceIn(0, bmp.height - 1)
        val w = r.width().coerceAtMost(bmp.width - l)
        val h = r.height().coerceAtMost(bmp.height - tp)
        if (w < 8 || h < 8) return null
        return try {
            val c = Bitmap.createBitmap(bmp, l, tp, w, h)
            val scale = 128f / maxOf(w, h)
            if (scale < 1f) Bitmap.createScaledBitmap(c, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), true) else c
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Last resorts, only with the built-in key and never on a screen showing a password:
     *  1. name the unlabeled icon buttons from their pictures,
     *  2. otherwise show Gemini the whole screenshot and ask where the control is.
     */
    private fun iconFallback(target: String, svc: JarvisAccessibilityService, scr: Screen, bmp: Bitmap) {
        if (!prefs.aiAllowed || scr.hasPassword) {
            say("notfound", target)
            end()
            return
        }
        val unl = scr.controls.filter { it.unlabeled }.take(10)
        val pairs = unl.mapNotNull { item -> crop(bmp, item.rect)?.let { item to it } }
        if (pairs.isEmpty()) {
            locateFallback(target, svc, bmp)
            return
        }
        ai.labelIcons(pairs.map { it.second }) { labels ->
            var best = -1
            var bestScore = 0.6
            if (labels != null) {
                for (i in pairs.indices) {
                    val s = Matcher.score(target, labels.getOrElse(i) { "" })
                    if (s >= bestScore) { best = i; bestScore = s }
                }
            }
            if (best >= 0) {
                clickItem(svc, pairs[best].first)
                end()
            } else {
                locateFallback(target, svc, bmp)
            }
        }
    }

    private fun locateFallback(target: String, svc: JarvisAccessibilityService, bmp: Bitmap) {
        ai.locate(bmp, target) { rect ->
            if (rect == null) say("notfound", target) else tapPoint(svc, rect, target)
            end()
        }
    }

    // ---- text fields ----

    /**
     * Types into the text box the cursor is in. If there isn't one, uses the search box on screen
     * (or the only text box). If search is a button or magnifier icon, taps it first. In a search
     * box, presses Enter afterwards so the search actually runs.
     * [search] = this came from "open YouTube and search for ..." rather than "type ...".
     */
    private fun typeText(value: String, search: Boolean = false) {
        val svc = JarvisAccessibilityService.inst
        if (svc == null) { say("noservice"); return }
        val missing = if (search) "no_search" else "no_input"
        val box = svc.focusedInput()?.takeIf { !it.isPassword } ?: textBox(svc)
        if (box != null) {
            typeInto(svc, box, value, search)
            return
        }
        val button = svc.scan().controls.firstOrNull { !it.editable && it.enabled && isSearchWord(it.label) }
        if (button == null) { say(missing); return }
        begin()
        svc.click(button) { _ ->
            // Give the app a moment to open its search box.
            main.postDelayed({
                val opened = svc.focusedInput()?.takeIf { !it.isPassword } ?: textBox(svc)
                if (opened == null) say(missing) else typeInto(svc, opened, value, true)
                end()
            }, 800)
        }
    }

    /** The search box on screen, or the only text box if there is just one. Never a password box. */
    private fun textBox(svc: JarvisAccessibilityService): android.view.accessibility.AccessibilityNodeInfo? {
        val inputs = svc.scan().controls.filter { it.editable && it.enabled && !it.node.isPassword }
        return (inputs.firstOrNull { isSearchBox(it.node) } ?: inputs.singleOrNull())?.node
    }

    private fun typeInto(svc: JarvisAccessibilityService, box: android.view.accessibility.AccessibilityNodeInfo, value: String, enter: Boolean) {
        val pressEnter = enter || isSearchBox(box)
        withTones(spoken(value)) {
            svc.setText(box, it)
            if (pressEnter) main.postDelayed({ svc.pressEnter(box) }, 150)
        }
    }

    /** "Search", "Search YouTube", or the current language's word for search. */
    private fun isSearchWord(label: String): Boolean {
        val n = Matcher.norm(label)
        if (n.isEmpty()) return false
        if (n.contains("search")) return true
        if (pack.code == "en") return false
        return pack.verbs["search"].orEmpty().map { Matcher.norm(it) }.any { it.length >= 3 && n.contains(it) }
    }

    private fun isSearchBox(n: android.view.accessibility.AccessibilityNodeInfo): Boolean =
        isSearchWord(listOf(n.hintText, n.contentDescription, n.viewIdResourceName, n.className).joinToString(" ") { it?.toString().orEmpty() })

    /**
     * Dictated Yorùbá/Igbo/Hausa: YarnGPT already writes most tone marks; Gemini fixes the rest
     * (and word breaks such as "ẹjọ́" -> "Ẹ jọ̀ọ́") before typing. Offline or with AI off, the text
     * is typed as heard.
     */
    private fun withTones(text: String, then: (String) -> Unit) {
        val lang = LangDetect.quick(text)
        if (lang !in setOf(LangDetect.YO, LangDetect.IG, LangDetect.HA) || !prefs.aiAllowed) { then(text); return }
        begin()
        Thread({
            val fixed = ai.restoreTonesSync(listOf(text), listOf(lang!!), 6000)?.firstOrNull()?.takeIf { it.isNotBlank() } ?: text
            main.post { then(fixed); end() }
        }, "tones").start()
    }

    private fun fill(field: String, value: String) {
        val svc = JarvisAccessibilityService.inst
        if (svc == null) { say("noservice"); return }
        if (field.isBlank() || value.isBlank()) { say("need_arg"); return }
        val inputs = svc.scan().controls.filter { it.editable }
        val best = inputs.map { it to Matcher.score(field, it.label) }.filter { it.second >= 0.5 }.maxByOrNull { it.second }?.first
            ?: if (inputs.size == 1) inputs[0] else null
        if (best == null) say("field_missing", field) else withTones(spoken(value)) { svc.setText(best.node, it) }
    }

    /** "john at gmail dot com" -> "john@gmail.com" */
    private fun spoken(v: String): String {
        if (!v.contains(" at ", true) || !v.contains(" dot ", true)) return v
        return v.replace(" at ", "@", true).replace(" dot ", ".", true).replace(" ", "")
    }

    // ---- apps, contacts, web ----

    private fun googleSearch(query: String) {
        launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + URLEncoder.encode(query, "UTF-8"))))
    }

    /** Names that mean "search the web", so "open Google and search for X" goes straight to results. */
    private val WEB = setOf("google", "google search", "chrome", "browser", "the browser", "internet", "the internet", "web", "the web")

    /**
     * "open Google and search for song" -> Google results for "song".
     * "open YouTube and search for song" -> opens YouTube, then searches inside it.
     * "open WhatsApp and read the screen" -> opens WhatsApp, then reads it.
     * Anything else is a plain "open".
     */
    private fun openThen(arg: String) {
        val split = Nlu.splitThen(arg)
        // An app with "and" in its name wins ("Tom and Jerry").
        if (split == null || (bestApp(arg)?.second ?: 0.0) >= 0.9) { openApp(arg); return }
        val (app, rest) = split
        val next = Nlu.parse(rest, packs)
        if (next is Cmd.Unknown) { openApp(arg); return }
        val isSearch = next is Cmd.Arg && next.id == "search"
        if (isSearch && Matcher.norm(app) in WEB) {
            googleSearch((next as Cmd.Arg).arg)
            return
        }
        if (!openApp(app)) return
        begin()
        // Wait for the app to open before working inside it.
        main.postDelayed({
            if (isSearch) typeText((next as Cmd.Arg).arg, search = true) else run(next)
            end()
        }, 2500)
    }

    private fun bestApp(name: String): Pair<android.content.pm.ResolveInfo, Double>? {
        val pm = ctx.packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(q, 0)
            .map { it to Matcher.score(name, it.loadLabel(pm).toString()) }
            .filter { it.second >= 0.6 }
            .maxByOrNull { it.second }
    }

    /** Returns false if nothing could be opened. */
    private fun openApp(name: String): Boolean {
        // "go to the home screen" arrives as open + "home screen": treat it as the plain command
        val asCmd = Nlu.parse(name, packs)
        if (asCmd is Cmd.Simple && asCmd.id != "yes" && asCmd.id != "no") {
            simple(asCmd.id)
            return true
        }
        // "storage settings" -> the Storage page, not the main Settings app.
        if (SettingsPages.mentionsSettings(name)) SettingsPages.find(name)?.let { return openSettingsPage(it) }
        val best = bestApp(name)
        if (best == null) {
            // "open bluetooth": no app by that name, but there is a settings page.
            SettingsPages.find(name)?.let { return openSettingsPage(it) }
            say("app_missing", name)
            return false
        }
        val pm = ctx.packageManager
        val i = pm.getLaunchIntentForPackage(best.first.activityInfo.packageName)
        if (i == null) {
            say("app_missing", name)
            return false
        }
        if (!canOpen()) return false
        say("opening", best.first.loadLabel(pm).toString())
        launch(i)
        return true
    }

    private fun openSettingsPage(p: SettingsPages.Page): Boolean {
        if (!canOpen()) return false
        say("opening", p.title + " settings")
        val host: Context = JarvisAccessibilityService.inst ?: ctx
        for (action in listOf(p.action, android.provider.Settings.ACTION_SETTINGS)) {
            try {
                host.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (e: Exception) {
                // Some phones hide a page: fall back to the main Settings screen.
            }
        }
        say("openfail")
        return false
    }

    private class Contact(val name: String, val number: String)

    private fun findContact(name: String): Contact? {
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            say("nocontacts")
            return null
        }
        val out = ArrayList<Contact>()
        try {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val n = c.getString(0) ?: continue
                    val num = c.getString(1) ?: continue
                    out.add(Contact(n, num))
                }
            }
        } catch (e: Exception) {
            say("nocontacts")
            return null
        }
        val best = out.map { it to Matcher.score(name, it.name) }.filter { it.second >= 0.6 }.maxByOrNull { it.second }?.first
        if (best == null) say("contact_missing", name)
        return best
    }

    /** "call Mum" or "call 0803 123 4567": a number said out loud doesn't need to be a contact. */
    private fun call(name: String) {
        val typed = PhoneNumber.parse(name)
        val digits: String
        val who: String
        if (typed != null) {
            digits = typed
            who = PhoneNumber.spoken(typed)
        } else {
            val c = findContact(name) ?: return
            digits = c.number.filter { it.isDigit() || it == '+' }
            who = c.name
        }
        clearPending()
        pendingYes = { launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(digits)))) }
        say("confirm_call", who)
    }

    /**
     * "message Mum", "send message to 0803 123 4567", "text 470".
     * A full phone number (or a contact) opens a WhatsApp chat. A short number like 470 can't be on
     * WhatsApp, so it opens a text message (SMS) instead. Nothing is sent until the user says so.
     */
    private fun whatsapp(name: String) {
        val typed = PhoneNumber.parse(name)
        val num: String
        if (typed != null) {
            num = typed
            if (!canOpen()) return
            if (num.count { it.isDigit() } < PhoneNumber.WHATSAPP_MIN_DIGITS) {
                say("opening", "a text message to " + PhoneNumber.spoken(num))
                launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(num))))
                return
            }
            say("opening", "WhatsApp for " + PhoneNumber.spoken(num))
        } else {
            val c = findContact(name) ?: return
            if (!canOpen()) return
            num = c.number
            say("opening", "WhatsApp for " + c.name)
        }
        launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + PhoneNumber.international(num))))
    }

    // ---- language ----

    private fun switchLanguage(name: String) {
        val n = Matcher.norm(name)
        for (code in Packs.codes(ctx)) {
            val p = Packs.load(ctx, code)
            if ((p.aliases + p.name + p.code).any { Matcher.norm(it) == n }) {
                prefs.lang = code
                spokenLang = code
                speak(p.say["switched"]?.let { String.format(it, p.name) } ?: p.name)
                return
            }
        }
        say("lang_missing", name)
    }

    // ------------------------------------------------------------------ AI (only when rules fail)

    private fun askAi(text: String) {
        if (!prefs.aiAllowed) {
            say("notunderstood")
            return
        }
        val svc = JarvisAccessibilityService.inst
        val scr = svc?.scan()
        val labels = if (scr == null || scr.hasPassword) emptyList()
        else scr.controls.map { it.label }.filter { it.isNotBlank() }.distinct().take(40)
        begin()
        ai.interpret(text, replyPack.name, labels) { r ->
            if (!r.ok) {
                say("ai_error")
            } else {
                handleAi(r.text)
            }
            end()
        }
    }

    private val AI_SIMPLE = setOf(
        "back", "home", "recents", "notifications", "quick_settings",
        "read_screen", "read_buttons", "scroll_down", "scroll_up"
    )
    private val AI_ARG = setOf("tap", "open", "call", "whatsapp", "search", "type")

    private fun handleAi(raw: String) {
        try {
            val o = JSONObject(raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
            when (o.optString("type")) {
                "chat" -> {
                    val reply = o.optString("reply")
                    if (reply.isBlank()) say("notunderstood") else speak(reply)
                }
                "web" -> {
                    val q = o.optString("query")
                    if (q.isBlank()) say("notunderstood") else {
                        begin()
                        ai.answer(q, replyPack.name) { r ->
                            if (r.ok) speak(r.text) else say("ai_error")
                            end()
                        }
                    }
                }
                "command" -> {
                    val id = o.optString("id")
                    val arg = o.optString("arg")
                    // Only whitelisted actions are ever executed, never free-form AI output.
                    if (id in AI_SIMPLE) simple(id)
                    else if (id in AI_ARG && arg.isNotBlank()) withArg(Cmd.Arg(id, arg))
                    else say("notunderstood")
                }
                else -> say("notunderstood")
            }
        } catch (e: Exception) {
            say("notunderstood")
        }
    }
}
