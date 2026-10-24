package team.superfun.horrorpets.pvp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import team.superfun.horrorpets.pvp.nfc.TapCard
import team.superfun.horrorpets.pvp.nfc.UNKNOWN_COMMAND
import team.superfun.horrorpets.pvp.nfc.codeResponse
import team.superfun.horrorpets.pvp.nfc.handOverApdu
import team.superfun.horrorpets.pvp.nfc.handOverResponse
import team.superfun.horrorpets.pvp.nfc.pingApdu
import team.superfun.horrorpets.pvp.nfc.readyResponse
import team.superfun.horrorpets.pvp.nfc.selectApdu

/** This phone as a card: what it answers a tap, and what it tells the game. */
class TapCardTest {
    private val heard = mutableListOf<String>()
    private var takes = true
    private val card = TapCard().apply {
        listener = object : TapCard.Listener {
            override fun onFound() {
                heard += "found"
            }

            override fun onHandedCode(code: String): Boolean {
                heard += "code $code"
                return takes
            }

            override fun onLost() {
                heard += "lost"
            }
        }
    }

    @Test
    fun aHostAnswersWithItsCodeReadyOrNot() {
        card.hosted = "4821"
        card.ready = true
        assertArrayEquals(codeResponse("4821"), card.answer(selectApdu()))
        assertEquals("a host is not waiting for anyone's code", emptyList<String>(), heard)
    }

    @Test
    fun aPhoneThatIsNotReadyHasNoRoom() {
        assertArrayEquals(codeResponse(null), card.answer(selectApdu()))
        assertArrayEquals(handOverResponse(taken = false), card.answer(handOverApdu("4821")))
        assertEquals(emptyList<String>(), heard)
    }

    @Test
    fun aReadyPhoneIsFoundThenTakesTheCode() {
        card.ready = true
        assertArrayEquals(readyResponse(), card.answer(selectApdu()))
        assertArrayEquals("still there", UNKNOWN_COMMAND, card.answer(pingApdu()))
        assertArrayEquals(handOverResponse(taken = true), card.answer(handOverApdu("4821")))
        card.deactivated()
        assertEquals(listOf("found", "code 4821"), heard)
    }

    @Test
    fun aCodeTheGameTurnsDownIsSaidSo() {
        card.ready = true
        takes = false
        card.answer(selectApdu())
        assertArrayEquals(handOverResponse(taken = false), card.answer(handOverApdu("4821")))
        assertArrayEquals("one code per tap", handOverResponse(taken = false), card.answer(handOverApdu("4821")))
        assertEquals(listOf("found", "code 4821"), heard)
    }

    @Test
    fun phonesPartingBeforeTheCodeAreLost() {
        card.ready = true
        card.answer(selectApdu())
        card.deactivated()
        card.deactivated()
        assertArrayEquals("a new tap starts over", handOverResponse(taken = false), card.answer(handOverApdu("4821")))
        assertEquals(listOf("found", "lost"), heard)
    }
}
