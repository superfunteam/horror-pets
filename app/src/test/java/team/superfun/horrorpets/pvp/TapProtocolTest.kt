package team.superfun.horrorpets.pvp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.pvp.nfc.PVP_AID
import team.superfun.horrorpets.pvp.nfc.TapAnswer
import team.superfun.horrorpets.pvp.nfc.answerFrom
import team.superfun.horrorpets.pvp.nfc.codeFrom
import team.superfun.horrorpets.pvp.nfc.codeResponse
import team.superfun.horrorpets.pvp.nfc.claimApdu
import team.superfun.horrorpets.pvp.nfc.handOverApdu
import team.superfun.horrorpets.pvp.nfc.handedOverCode
import team.superfun.horrorpets.pvp.nfc.isClaim
import team.superfun.horrorpets.pvp.nfc.isPing
import team.superfun.horrorpets.pvp.nfc.isPvpSelect
import team.superfun.horrorpets.pvp.nfc.isRelease
import team.superfun.horrorpets.pvp.nfc.openingResponse
import team.superfun.horrorpets.pvp.nfc.pingApdu
import team.superfun.horrorpets.pvp.nfc.readyResponse
import team.superfun.horrorpets.pvp.nfc.releaseApdu
import team.superfun.horrorpets.pvp.nfc.saidYes
import team.superfun.horrorpets.pvp.nfc.selectApdu
import team.superfun.horrorpets.pvp.nfc.yesNo

/** The two-phone tap: a SELECT of the PvP AID, answered with the hosted room's code (or "ready", then a hand-over). */
class TapProtocolTest {
    @Test
    fun theSelectIsAnIso7816SelectByNameOfThePvpAid() {
        val apdu = selectApdu()
        assertArrayEquals(byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, 6) + PVP_AID + byteArrayOf(0x00), apdu)
        assertTrue(isPvpSelect(apdu))
        assertTrue("without Le too", isPvpSelect(apdu.copyOf(apdu.size - 1)))
        assertEquals("F04850565031", PVP_AID.joinToString("") { "%02X".format(it) })
    }

    @Test
    fun otherCommandsAreNotOurSelect() {
        assertFalse(isPvpSelect(byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, 7, 0xA0.toByte(), 0, 0, 0, 0x04, 0x10, 0x10)))
        assertFalse(isPvpSelect(byteArrayOf(0x00, 0xB0.toByte(), 0x00, 0x00)))
        assertFalse(isPvpSelect(ByteArray(0)))
    }

    @Test
    fun aHostAnswersWithItsCodeAndTheReaderReadsIt() {
        val answer = codeResponse("4821")
        assertEquals("HPVP:4821", String(answer, 0, answer.size - 2, Charsets.US_ASCII))
        assertArrayEquals(byteArrayOf(0x90.toByte(), 0x00), answer.copyOfRange(answer.size - 2, answer.size))
        assertEquals("4821", codeFrom(answer))
    }

    @Test
    fun noRoomIsNotFoundAndReadsAsNothing() {
        assertArrayEquals(byteArrayOf(0x6A, 0x82.toByte()), codeResponse(null))
        assertNull(codeFrom(codeResponse(null)))
        assertArrayEquals("a malformed code is no code", codeResponse(null), codeResponse("48a1"))
    }

    @Test
    fun anythingElseReadsAsNothing() {
        assertNull(codeFrom(byteArrayOf(0x90.toByte(), 0x00)))
        assertNull(codeFrom("HPVP:12345".toByteArray() + byteArrayOf(0x90.toByte(), 0x00)))
        assertNull(codeFrom("PAY:4821".toByteArray() + byteArrayOf(0x90.toByte(), 0x00)))
        assertNull(codeFrom("HPVP:4821".toByteArray() + byteArrayOf(0x6F, 0x00)))
        assertNull(codeFrom(ByteArray(1)))
    }

    @Test
    fun aReadyPhoneSaysSoAndOlderReadersSeeNoRoom() {
        val ready = readyResponse()
        assertEquals("HPVP?", String(ready, 0, ready.size - 2, Charsets.US_ASCII))
        assertEquals(TapAnswer.Ready, answerFrom(ready))
        assertNull("0.8.0 reads it as no room", codeFrom(ready))
        assertEquals(TapAnswer.Room("4821"), answerFrom(codeResponse("4821")))
        assertNull(answerFrom(codeResponse(null)))
        assertNull(answerFrom("HPVP?".toByteArray() + byteArrayOf(0x6A, 0x82.toByte())))
    }

    @Test
    fun aRoomOnItsWaySaysSoAndOlderReadersSeeNoRoom() {
        val opening = openingResponse()
        assertEquals("HPVP.", String(opening, 0, opening.size - 2, Charsets.US_ASCII))
        assertEquals(TapAnswer.Opening, answerFrom(opening))
        assertNull("0.8.0 reads it as no room", codeFrom(opening))
    }

    @Test
    fun claimsAndPingsAreTheirOwnCommands() {
        assertTrue(isClaim(claimApdu()))
        assertTrue(isPing(pingApdu()))
        assertTrue(isRelease(releaseApdu()))
        assertFalse(isRelease(pingApdu()))
        assertFalse(isClaim(releaseApdu()))
        assertFalse(isClaim(pingApdu()))
        assertFalse(isPing(claimApdu()))
        assertFalse(isClaim(selectApdu()))
        assertFalse(isPvpSelect(claimApdu()))
        assertNull(handedOverCode(claimApdu()))
        assertFalse(isClaim(ByteArray(2)))
    }

    @Test
    fun aHandOverCarriesTheCode() {
        val apdu = handOverApdu("0427")
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x4A, 0x00, 0x00, 0x04) + "0427".toByteArray(), apdu)
        assertEquals("0427", handedOverCode(apdu))
        assertFalse("not a SELECT", isPvpSelect(apdu))
        assertFalse(isClaim(apdu))
        assertNull(handedOverCode(selectApdu()))
        assertNull(handedOverCode(pingApdu()))
        assertNull(handedOverCode(byteArrayOf(0x80.toByte(), 0x4A, 0x00, 0x00, 0x04) + "04a7".toByteArray()))
    }

    @Test
    fun yesIsNineThousandAndNoIsNotFound() {
        assertArrayEquals(byteArrayOf(0x90.toByte(), 0x00), yesNo(true))
        assertArrayEquals(byteArrayOf(0x6A, 0x82.toByte()), yesNo(false))
        assertTrue(saidYes(yesNo(true)))
        assertFalse(saidYes(yesNo(false)))
        assertFalse(saidYes(ByteArray(0)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun onlyACodeIsHandedOver() {
        handOverApdu("42")
    }
}
