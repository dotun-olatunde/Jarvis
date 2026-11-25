package ng.ainow.jarvis.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPagesTest {
    private fun page(said: String): String? = SettingsPages.find(said)?.title

    @Test fun named_pages() {
        assertEquals("storage", page("storage settings"))
        assertEquals("storage", page("the storage setting"))
        assertEquals("Wi-Fi", page("wifi"))
        assertEquals("Wi-Fi", page("Wi-Fi settings"))
        assertEquals("Bluetooth", page("bluetooth settings"))
        assertEquals("date and time", page("date and time settings"))
        assertEquals("security", page("screen lock settings")) // not "display" for the word screen
        assertEquals("battery", page("my battery settings"))
    }

    @Test fun plain_settings_is_not_a_page() {
        assertEquals(null, page("settings"))
        assertEquals(null, page("whatsapp"))
    }

    @Test fun mentions_settings() {
        assertTrue(SettingsPages.mentionsSettings("storage settings"))
        assertFalse(SettingsPages.mentionsSettings("bluetooth"))
    }
}
