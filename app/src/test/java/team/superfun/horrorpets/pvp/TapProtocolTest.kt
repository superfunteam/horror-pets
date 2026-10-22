package team.superfun.horrorpets.pvp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.pvp.nfc.PVP_AID
import team.superfun.horrorpets.pvp.nfc.codeFrom
import team.superfun.horrorpets.pvp.nfc.codeResponse
import team.superfun.horrorpets.pvp.nfc.isPvpSelect
import team.superfun.horrorpets.pvp.nfc.selectApdu

/** The two-phone tap: a SELECT of the PvP AID, answered with the hosted room's code. */
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
}
