package ng.ainow.jarvis.language

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneNumberTest {
    @Test fun digits() {
        assertEquals("470", PhoneNumber.parse("470"))
        assertEquals("08031234567", PhoneNumber.parse("0803 123 4567"))
        assertEquals("08031234567", PhoneNumber.parse("0803-123-4567"))
        assertEquals("+2348031234567", PhoneNumber.parse("+234 803 123 4567"))
        assertEquals("+2348031234567", PhoneNumber.parse("plus 234 803 123 4567"))
    }

    @Test fun words() {
        assertEquals("470", PhoneNumber.parse("four seven zero"))
        assertEquals("0803", PhoneNumber.parse("oh eight oh three"))
        assertEquals("4470", PhoneNumber.parse("double four seven zero"))
    }

    @Test fun names_are_not_numbers() {
        assertEquals(null, PhoneNumber.parse("mum"))
        assertEquals(null, PhoneNumber.parse("john 2"))
        assertEquals(null, PhoneNumber.parse("12")) // too short
    }

    @Test fun whatsapp_format() {
        assertEquals("2348031234567", PhoneNumber.international("08031234567"))
        assertEquals("2348031234567", PhoneNumber.international("+2348031234567"))
        assertEquals("4 7 0", PhoneNumber.spoken("470"))
    }
}
