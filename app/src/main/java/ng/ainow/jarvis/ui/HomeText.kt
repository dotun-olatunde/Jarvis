package ng.ainow.jarvis.ui

import android.text.SpannableString
import android.text.Spanned
import android.text.style.LocaleSpan
import java.util.Locale
import ng.ainow.jarvis.language.Pack

internal data class HomeLabels(
    val prompt: String,
    val wakeHint: String,
    val settings: String,
    val microphone: String,
    val start: String,
)

/** Home follows Jarvis's language choice, which may differ from the phone's locale. */
internal object HomeText {
    fun labels(code: String) = translations[code] ?: translations.getValue("en")

    fun inLanguage(text: String, pack: Pack): CharSequence = SpannableString(text).apply {
        setSpan(LocaleSpan(Locale.forLanguageTag(pack.code)), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    // The wake phrase stays in English because it is recognized by the bundled wake model.
    private val translations = mapOf(
        "en" to HomeLabels("How can I help?", "Say “Hey Jarvis” and wait for the beep.", "Settings", "Allow microphone access", "Start Jarvis"),
        "pcm" to HomeLabels("How I fit help you?", "Talk “Hey Jarvis” and wait for the beep.", "Settings", "Allow microphone", "Start Jarvis"),
        "yo" to HomeLabels("Báwo ni mo ṣe lè ràn ọ́ lọ́wọ́?", "Sọ “Hey Jarvis” kí o sì dúró de ìró ìkìlọ̀.", "Ètò", "Gba Jarvis láàyè láti lo gbohùngbohùn", "Bẹ̀rẹ̀ Jarvis"),
        "ig" to HomeLabels("Kedụ ka m ga-esi nyere gị aka?", "Kwuo “Hey Jarvis” ma chere ka ụda daa.", "Ntọala", "Nye ikike iji igwe okwu", "Malite Jarvis"),
        "ha" to HomeLabels("Ta yaya zan taimaka maka?", "Faɗi “Hey Jarvis” sannan ka jira ƙarar alama.", "Saituna", "Ba da izinin amfani da makirufo", "Kunna Jarvis"),
        "de" to HomeLabels("Wie kann ich helfen?", "Sag „Hey Jarvis“ und warte auf den Signalton.", "Einstellungen", "Mikrofonzugriff erlauben", "Jarvis starten"),
        "es" to HomeLabels("¿En qué puedo ayudarte?", "Di «Hey Jarvis» y espera el pitido.", "Ajustes", "Permitir acceso al micrófono", "Iniciar Jarvis"),
        "fr" to HomeLabels("Comment puis-je vous aider ?", "Dites « Hey Jarvis » et attendez le bip.", "Paramètres", "Autoriser l’accès au micro", "Démarrer Jarvis"),
        "hi" to HomeLabels("मैं आपकी कैसे मदद करूँ?", "“Hey Jarvis” बोलें और बीप का इंतज़ार करें।", "सेटिंग", "माइक्रोफ़ोन की अनुमति दें", "Jarvis शुरू करें"),
        "id" to HomeLabels("Apa yang bisa saya bantu?", "Ucapkan “Hey Jarvis” dan tunggu bunyi bip.", "Setelan", "Izinkan akses mikrofon", "Mulai Jarvis"),
        "it" to HomeLabels("Come posso aiutarti?", "Di’ «Hey Jarvis» e attendi il segnale acustico.", "Impostazioni", "Consenti l’accesso al microfono", "Avvia Jarvis"),
        "ja" to HomeLabels("何をお手伝いしましょうか？", "「Hey Jarvis」と言って、合図の音を待ってください。", "設定", "マイクへのアクセスを許可", "Jarvis を開始"),
        "ko" to HomeLabels("무엇을 도와드릴까요?", "“Hey Jarvis”라고 말하고 신호음을 기다리세요.", "설정", "마이크 접근 허용", "Jarvis 시작"),
        "pl" to HomeLabels("Jak mogę pomóc?", "Powiedz „Hey Jarvis” i poczekaj na sygnał.", "Ustawienia", "Zezwól na dostęp do mikrofonu", "Uruchom Jarvis"),
        "pt" to HomeLabels("Como posso ajudar?", "Diga “Hey Jarvis” e espere o sinal sonoro.", "Configurações", "Permitir acesso ao microfone", "Iniciar Jarvis"),
        "ru" to HomeLabels("Чем я могу помочь?", "Скажите «Hey Jarvis» и дождитесь сигнала.", "Настройки", "Разрешить доступ к микрофону", "Запустить Jarvis"),
        "th" to HomeLabels("ให้ฉันช่วยอะไรดี?", "พูดว่า “Hey Jarvis” แล้วรอเสียงสัญญาณ", "การตั้งค่า", "อนุญาตให้ใช้ไมโครโฟน", "เริ่ม Jarvis"),
        "tr" to HomeLabels("Nasıl yardımcı olabilirim?", "“Hey Jarvis” deyin ve sinyal sesini bekleyin.", "Ayarlar", "Mikrofon erişimine izin ver", "Jarvis’i başlat"),
        "vi" to HomeLabels("Tôi có thể giúp gì cho bạn?", "Nói “Hey Jarvis” và chờ tiếng bíp.", "Cài đặt", "Cho phép truy cập micrô", "Khởi động Jarvis"),
        "zh" to HomeLabels("我能帮你做什么？", "说“Hey Jarvis”，然后等待提示音。", "设置", "允许使用麦克风", "启动 Jarvis"),
    )
}
