package ng.ainow.jarvis.language

import org.junit.Assert.assertEquals
import org.junit.Test

/** What language Jarvis thinks the user spoke, so it can reply in the same language. */
class LangDetectTest {
    private fun check(expected: String?, said: String) = assertEquals(said, expected, LangDetect.quick(said))

    @Test fun english() {
        check("en", "open WhatsApp")
        check("en", "read the screen please")
        check("en", "call my mother")
    }

    @Test fun pidgin() {
        check("pcm", "abeg open WhatsApp")
        check("pcm", "wetin dey for screen")
        check("pcm", "abeg open whatsapp kí n pe màmá mi".replace("kí n pe màmá mi", ""))
    }

    @Test fun yoruba_with_and_without_tone_marks() {
        check("yo", "mò ń sọ yorùbá báyìí, ẹjọ́ ṣí whatsapp")
        check("yo", "e jowo si WhatsApp")
        check("yo", "ka ohun to wa loju iboju")
    }

    @Test fun igbo() {
        check("ig", "biko meghee WhatsApp")
        check("ig", "kedu ka ị mere")
    }

    @Test fun hausa() {
        check("ha", "don Allah bude WhatsApp")
        check("ha", "ina kwana, yaya aiki")
    }

    @Test fun mixed_counts_as_the_nigerian_language() {
        check("yo", "please ṣí WhatsApp fun mi")
        check("pcm", "abeg read the screen")
    }

    @Test fun splits_sentences() {
        assertEquals(listOf("Ẹ kú àárọ̀ o.", "Ṣé dáadáa ni?", "Ok o."), LangDetect.split("Ẹ kú àárọ̀ o. Ṣé dáadáa ni? Ok o."))
        assertEquals(listOf("今日はいい天気ですね。", "Ẹ kú àárọ̀ o."), LangDetect.split("今日はいい天気ですね。 Ẹ kú àárọ̀ o."))
        assertEquals(listOf("नमस्ते।", "आप कैसे हैं?"), LangDetect.split("नमस्ते।आप कैसे हैं?"))
    }
}
