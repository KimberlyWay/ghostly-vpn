package app.ghostly.ui

import app.ghostly.ui.components.flagCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FlagTest {
    @Test
    fun emojiFlagsBecomeCountryCodes() {
        assertEquals("FI", flagCode("\uD83C\uDDEB\uD83C\uDDEE"))
        assertEquals("RU", flagCode("\uD83C\uDDF7\uD83C\uDDFA"))
        assertEquals("NL", flagCode("nl"))
        assertNull(flagCode("\uD83D\uDC7B"))
        assertNull(flagCode(null))
    }
}
