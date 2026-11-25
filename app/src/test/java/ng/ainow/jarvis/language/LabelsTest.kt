package ng.ainow.jarvis.language

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class LabelsTest {
    private val englishPhone: (String) -> Spoken? = { null }   // lookup failed: English names are used
    private val yorubaPhone: (String) -> Spoken? = { if (it == "accessibility") Spoken("Ìrànlọ́wọ́", "yo") else null }

    @Test fun `an English menu name in a Yoruba sentence gets the English voice`() {
        val pieces = Labels.split("Tan mí nínú ètò [[accessibility]]. Ó dáa.", "yo", englishPhone)
        assertEquals(
            listOf(Spoken("Tan mí nínú ètò", "yo"), Spoken("Accessibility.", "en"), Spoken("Ó dáa.", "yo")),
            pieces
        )
    }

    @Test fun `a menu name in the phone's language stays in the same voice with no break`() {
        val pieces = Labels.split("Tan mí nínú [[accessibility]]. Ó dáa.", "yo", yorubaPhone)
        assertEquals(listOf(Spoken("Tan mí nínú Ìrànlọ́wọ́. Ó dáa.", "yo")), pieces)
    }

    @Test fun `English words to say are read in English`() {
        val pieces = Labels.split("Sọ ṣí ètò [[en:accessibility]], wá Jarvis.", "yo", englishPhone)
        assertEquals(
            listOf(Spoken("Sọ ṣí ètò", "yo"), Spoken("accessibility,", "en"), Spoken("wá Jarvis.", "yo")),
            pieces
        )
    }

    @Test fun `Pidgin and an English menu name share the Nigerian English voice`() {
        val pieces = Labels.split("Choose [[restricted]], then try again.", "pcm", englishPhone)
        assertEquals(listOf(Spoken("Choose Allow restricted settings, then try again.", "pcm")), pieces)
    }

    @Test fun `text with no marks is one piece`() {
        assertEquals(listOf(Spoken("Mo ń gbọ́.", "yo")), Labels.split("Mo ń gbọ́.", "yo", englishPhone))
    }

    @Test fun `every mark in the packs is one Jarvis knows`() {
        val known = Labels.ENGLISH.keys + "en"
        val packs = File("src/main/assets/packs").listFiles { f -> f.name.endsWith(".json") }!!
        for (f in packs) {
            val say = JSONObject(f.readText()).getJSONObject("say")
            for (k in say.keys()) {
                for (m in Regex("""\[\[([a-z_]+)""").findAll(say.getString(k))) {
                    assert(m.groupValues[1] in known) { "${f.name} $k uses unknown mark ${m.value}" }
                }
                assertFalse("${f.name} $k has a broken mark", Labels.split(say.getString(k), "yo", englishPhone).any { "[[" in it.text })
            }
        }
    }
}
