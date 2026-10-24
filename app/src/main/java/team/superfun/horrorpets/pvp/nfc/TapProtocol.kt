package team.superfun.horrorpets.pvp.nfc

/**
 * Joining a PvP room by tapping two phones together (NFC). The phone hosting the room acts as a contactless
 * card ([PvpTapService], host card emulation) and the joining phone reads it (`NfcTapReader`, reader mode):
 * it selects this app's [PVP_AID] and gets back the room's code. Android dropped phone-to-phone Beam in
 * Android 10, so a card and a reader is how two phones still talk over NFC.
 *
 * The exchange, in ISO 7816-4 APDUs: the reader sends [selectApdu]; the host answers [codeResponse] (`HPVP:`
 * and the 4-digit code, then status 90 00), or 6A 82 (not found) when it has no room open.
 *
 * Two phones that are both just on Home can start a fight with a tap too. Each takes turns being a reader and a card
 * (see `TapRadio`), so a tap finds one of each. A card that is open to a tap fight but hosts no room answers the
 * SELECT with [readyResponse] (`HPVP?`); the reader then opens a room itself and, over the same tap, hands the card
 * its code with [handOverApdu], which the card takes ([handedOverCode]) to join. Older versions read `HPVP?` as no
 * room, so they never mistake it for a code.
 */

/** The application ID a tap selects: proprietary (it starts with F0), "HPVP" and 1. Also in res/xml/pvp_tap_service.xml. */
val PVP_AID: ByteArray = byteArrayOf(0xF0.toByte(), 0x48, 0x50, 0x56, 0x50, 0x31)

private val OK = byteArrayOf(0x90.toByte(), 0x00)
private val NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())
private const val PREFIX = "HPVP:"
private const val READY = "HPVP?"

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

/** What a tapped phone said to the SELECT: a room to join, or that it is ready for a tap fight and hosts none. */
sealed interface TapAnswer {
    data class Room(val code: String) : TapAnswer

    data object Ready : TapAnswer
}

/** A card's answer when it hosts no room but a tap may start a fight from where it is: "HPVP?", then 90 00. */
fun readyResponse(): ByteArray = READY.toByteArray(Charsets.US_ASCII) + OK

/** What a tapped phone's [response] to the SELECT says, or null when it is nothing this app understands. */
fun answerFrom(response: ByteArray): TapAnswer? {
    codeFrom(response)?.let { return TapAnswer.Room(it) }
    return TapAnswer.Ready.takeIf { response.contentEquals(readyResponse()) }
}

/**
 * The reader's room [code], handed to a ready card over the same tap: a proprietary command (CLA 80, INS 4A for
 * "J", P1 00, P2 00, Lc 04) with the code's four ASCII digits.
 */
fun handOverApdu(code: String): ByteArray {
    require(code.length == 4 && code.all(Char::isDigit)) { "A room code is 4 digits" }
    return byteArrayOf(0x80.toByte(), 0x4A, 0x00, 0x00, 0x04) + code.toByteArray(Charsets.US_ASCII)
}

/** The room code in a hand-over [apdu], or null when it is not one. */
fun handedOverCode(apdu: ByteArray): String? {
    if (apdu.size < 9) return null
    val header = apdu[0] == 0x80.toByte() && apdu[1] == 0x4A.toByte() && apdu[2] == 0x00.toByte() && apdu[3] == 0x00.toByte()
    if (!header || apdu[4] != 0x04.toByte()) return null
    val code = String(apdu, 5, 4, Charsets.US_ASCII)
    return code.takeIf { it.all(Char::isDigit) }
}

/** The card's answer to a hand-over: taken (90 00), or not wanted any more (6A 82). */
fun handOverResponse(taken: Boolean): ByteArray = if (taken) OK.copyOf() else NOT_FOUND.copyOf()

/** A reader's "still there?" while it opens its room: a card answers anything it does not know with 6D 00. */
fun pingApdu(): ByteArray = byteArrayOf(0x80.toByte(), 0x50, 0x00, 0x00)

/** Whether the card took the code it was handed. */
fun handOverTaken(response: ByteArray): Boolean = response.contentEquals(OK)
