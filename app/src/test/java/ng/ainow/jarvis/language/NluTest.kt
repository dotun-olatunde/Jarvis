package ng.ainow.jarvis.language

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** Commands in each language, including verb-last languages and ones written without spaces. */
class NluTest {
    private fun pack(code: String): Pack {
        // Unit tests run without Android assets, so read the pack files straight from the source tree.
        val f = listOf("src/main/assets/packs/$code.json", "app/src/main/assets/packs/$code.json").map { File(it) }.first { it.exists() }
        return Packs.parse(f.readText())
    }

    private fun check(code: String, said: String, expected: Cmd) {
        val packs = listOf(pack(code), pack("en"))
        assertEquals("$code: $said", expected, Nlu.parse(said, packs))
    }

    @Test fun verb_first() {
        check("en", "open WhatsApp", Cmd.Arg("open", "whatsapp"))
        check("it", "apri WhatsApp", Cmd.Arg("open", "whatsapp"))
        check("ru", "открой WhatsApp", Cmd.Arg("open", "whatsapp"))
        check("id", "buka WhatsApp", Cmd.Arg("open", "whatsapp"))
        check("vi", "mở WhatsApp", Cmd.Arg("open", "whatsapp"))
    }

    @Test fun no_spaces() {
        check("zh", "打开微信", Cmd.Arg("open", "微信"))
        check("zh", "请打开微信", Cmd.Arg("open", "微信"))
        check("th", "เปิดไลน์", Cmd.Arg("open", "ไลน์"))
        check("zh", "返回", Cmd.Simple("back"))
    }

    @Test fun verb_last() {
        check("ko", "왓츠앱 열어", Cmd.Arg("open", "왓츠앱"))
        check("ko", "왓츠앱을 열어", Cmd.Arg("open", "왓츠앱"))
        check("ja", "LINEを開いて", Cmd.Arg("open", "line"))
        check("hi", "व्हाट्सएप खोलो", Cmd.Arg("open", "व्हाट्सएप"))
        check("tr", "WhatsApp aç", Cmd.Arg("open", "whatsapp"))
    }

    @Test fun phrases() {
        check("hi", "स्क्रीन पढ़ो", Cmd.Simple("read_screen"))
        check("ja", "画面を読んで", Cmd.Simple("read_screen"))
        check("ko", "뒤로", Cmd.Simple("back"))
        check("pl", "przeczytaj ekran", Cmd.Simple("read_screen"))
    }

    @Test fun open_then_search() {
        check("en", "open google and search for song", Cmd.Arg("open", "google and search for song"))
        assertEquals("google" to "search for song", Nlu.splitThen("google and search for song"))
        assertEquals("youtube" to "search cats", Nlu.splitThen("youtube and then search cats"))
        assertEquals(null, Nlu.splitThen("whatsapp"))
        check("en", "search for song", Cmd.Arg("search", "song"))
    }

    @Test fun message_a_number() {
        check("en", "send message to 470", Cmd.Arg("whatsapp", "470"))
        check("en", "send a message to 0803 123 4567", Cmd.Arg("whatsapp", "0803 123 4567"))
        check("en", "text 470", Cmd.Arg("whatsapp", "470"))
        check("en", "send message to", Cmd.Arg("whatsapp", ""))
        assertEquals(Cmd.Arg("whatsapp", "470"), Nlu.argument("whatsapp", "470", listOf(pack("en"))))
    }
}
