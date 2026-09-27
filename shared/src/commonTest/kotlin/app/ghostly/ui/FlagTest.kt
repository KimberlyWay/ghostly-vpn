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
        assertEquals("EU", flagCode("\uD83C\uDDEA\uD83C\uDDFA"))
        assertEquals("NL", flagCode("nl"))
        assertNull(flagCode("\uD83D\uDC7B"))
        assertNull(flagCode(null))
    }
}

class FlagPairTest {
    @Test
    fun findsFlagsAnywhere() {
        val re = Regex("[\\x{1F1E6}-\\x{1F1FF}]{2}")
        val name = "Amsterdam \uD83C\uDDF3\uD83C\uDDF1\uD83C\uDDE9\uD83C\uDDEA"
        assertEquals(listOf("NL", "DE"), re.findAll(name).map { flagCode(it.value) }.toList())
    }
}
