package ng.ainow.jarvis.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnswerWindowTest {
    private var now = 0L
    private val window = AnswerWindow { now }

    @Test fun `a long spoken prompt does not use up answer time`() {
        now = 45_000L
        assertNull(window.remainingMs)
        assertEquals(30_000L, window.start())
    }

    @Test fun `a recognition retry preserves the original deadline`() {
        window.start()
        now = 21_000L
        assertEquals(9_000L, window.start())
        now = 30_001L
        assertEquals(0L, window.start())
    }

    @Test fun `a new prompt can open a fresh window`() {
        window.start()
        now = 26_000L
        window.reset()
        assertNull(window.remainingMs)
        now = 39_000L
        assertEquals(30_000L, window.start())
    }
}
