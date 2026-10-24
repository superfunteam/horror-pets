package team.superfun.horrorpets.pvp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import team.superfun.horrorpets.pvp.nfc.TapCard
import team.superfun.horrorpets.pvp.nfc.UNKNOWN_COMMAND
import team.superfun.horrorpets.pvp.nfc.claimApdu
import team.superfun.horrorpets.pvp.nfc.codeResponse
import team.superfun.horrorpets.pvp.nfc.handOverApdu
import team.superfun.horrorpets.pvp.nfc.openingResponse
import team.superfun.horrorpets.pvp.nfc.pingApdu
import team.superfun.horrorpets.pvp.nfc.readyResponse
import team.superfun.horrorpets.pvp.nfc.selectApdu
import team.superfun.horrorpets.pvp.nfc.yesNo

/** This phone as a card: what it answers a tap, and what it tells the game. */
class TapCardTest {
    private val heard = mutableListOf<String>()
    private var waits = true
    private var takes = true
    private val card = TapCard().apply {
        listener = object : TapCard.Listener {
            override fun onClaimed(): Boolean {
                heard += "claimed"
                return waits
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
    fun aHostAnswersWithItsCodeAndCannotBeClaimed() {
        card.hosted = "4821"
        card.ready = true
        assertArrayEquals(codeResponse("4821"), card.answer(selectApdu()))
        assertArrayEquals(yesNo(false), card.answer(claimApdu()))
        assertEquals(emptyList<String>(), heard)
    }

    @Test
    fun aRoomOnItsWaySaysSo() {
        card.opening = true
        card.ready = true
        assertArrayEquals(openingResponse(), card.answer(selectApdu()))
        assertArrayEquals(yesNo(false), card.answer(claimApdu()))
        card.hosted = "4821"
        assertArrayEquals("asked again, the code is in", codeResponse("4821"), card.answer(selectApdu()))
    }

    @Test
    fun aPhoneThatIsNotReadyHasNoRoom() {
        assertArrayEquals(codeResponse(null), card.answer(selectApdu()))
        assertArrayEquals(yesNo(false), card.answer(claimApdu()))
        assertArrayEquals(yesNo(false), card.answer(handOverApdu("4821")))
        assertEquals(emptyList<String>(), heard)
    }

    @Test
    fun aReadyPhoneSaysSoButWaitsForNothingUntilClaimed() {
        card.ready = true
        assertArrayEquals(readyResponse(), card.answer(selectApdu()))
        assertArrayEquals("not claimed: nothing to wait for", yesNo(false), card.answer(pingApdu()))
        card.deactivated()
        assertEquals("an older reader's SELECT alone changes nothing", emptyList<String>(), heard)
    }

    @Test
    fun aClaimedPhoneWaitsThenTakesTheCode() {
        card.ready = true
        card.answer(selectApdu())
        assertArrayEquals(yesNo(true), card.answer(claimApdu()))
        assertArrayEquals("still there", yesNo(true), card.answer(pingApdu()))
        assertArrayEquals(yesNo(true), card.answer(handOverApdu("4821")))
        assertArrayEquals("done waiting", yesNo(false), card.answer(pingApdu()))
        card.deactivated()
        assertEquals(listOf("claimed", "code 4821"), heard)
    }

    @Test
    fun aClaimTheGameTurnsDownIsSaidSo() {
        card.ready = true
        waits = false
        assertArrayEquals(yesNo(false), card.answer(claimApdu()))
        assertArrayEquals(yesNo(false), card.answer(handOverApdu("4821")))
        assertEquals(listOf("claimed"), heard)
    }

    @Test
    fun aCodeTheGameTurnsDownIsSaidSo() {
        card.ready = true
        takes = false
        card.answer(claimApdu())
        assertArrayEquals(yesNo(false), card.answer(handOverApdu("4821")))
        assertArrayEquals("one code per claim", yesNo(false), card.answer(handOverApdu("4821")))
        assertEquals(listOf("claimed", "code 4821"), heard)
    }

    @Test
    fun phonesPartingBeforeTheCodeAreLost() {
        card.ready = true
        card.answer(claimApdu())
        card.deactivated()
        card.deactivated()
        assertArrayEquals("a new tap starts over", yesNo(false), card.answer(handOverApdu("4821")))
        assertEquals(listOf("claimed", "lost"), heard)
    }

    @Test
    fun anythingElseIsNotUnderstood() {
        assertArrayEquals(UNKNOWN_COMMAND, card.answer(byteArrayOf(0x00, 0xB0.toByte(), 0x00, 0x00)))
    }
}
