package team.superfun.horrorpets.pvp.nfc

/**
 * Joining a PvP room by tapping two phones together (NFC). The phone hosting the room acts as a contactless
 * card ([PvpTapService], host card emulation) and the joining phone reads it (`NfcTapReader`, reader mode):
 * it selects this app's [PVP_AID] and gets back the room's code. Android dropped phone-to-phone Beam in
 * Android 10, so a card and a reader is how two phones still talk over NFC.
 *
 * The exchange, in ISO 7816-4 APDUs: the reader sends [selectApdu]; the host answers [codeResponse] (`HPVP:`
 * and the 4-digit code, then status 90 00), or 6A 82 (not found) when it has no room open.
 */

/** The application ID a tap selects: proprietary (it starts with F0), "HPVP" and 1. Also in res/xml/pvp_tap_service.xml. */
val PVP_AID: ByteArray = byteArrayOf(0xF0.toByte(), 0x48, 0x50, 0x56, 0x50, 0x31)

private val OK = byteArrayOf(0x90.toByte(), 0x00)
private val NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())
private const val PREFIX = "HPVP:"

/** SELECT by name of [PVP_AID] (CLA 00, INS A4, P1 04, P2 00, Lc, the AID, Le 00). */
fun selectApdu(): ByteArray = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, PVP_AID.size.toByte()) + PVP_AID + byteArrayOf(0x00)

/** Whether [apdu] is a SELECT by name of [PVP_AID] (with or without Le). */
fun isPvpSelect(apdu: ByteArray): Boolean {
    if (apdu.size < 5 + PVP_AID.size) return false
    val header = apdu[0] == 0x00.toByte() && apdu[1] == 0xA4.toByte() && apdu[2] == 0x04.toByte() && apdu[3] == 0x00.toByte()
    val lc = apdu[4].toInt() and 0xFF
    return header && lc == PVP_AID.size && apdu.copyOfRange(5, 5 + lc).contentEquals(PVP_AID)
}

/** The host's answer: its room's [code] when it has one open, else "not found". */
fun codeResponse(code: String?): ByteArray =
    if (code != null && code.length == 4 && code.all(Char::isDigit)) (PREFIX + code).toByteArray(Charsets.US_ASCII) + OK else NOT_FOUND

/** The room code in a host's [response], or null when it had none (or this is not a PvP host at all). */
fun codeFrom(response: ByteArray): String? {
    if (response.size < 2 || !response.copyOfRange(response.size - 2, response.size).contentEquals(OK)) return null
    val text = String(response, 0, response.size - 2, Charsets.US_ASCII)
    if (!text.startsWith(PREFIX)) return null
    val code = text.removePrefix(PREFIX)
    return code.takeIf { it.length == 4 && it.all(Char::isDigit) }
}

/** Anything a host does not understand. */
val UNKNOWN_COMMAND: ByteArray = byteArrayOf(0x6D, 0x00)
