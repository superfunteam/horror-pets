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
 * (see `TapRadio`), so a tap finds one of each. Newer answers to the SELECT, which older versions read as no room:
 * - [readyResponse] (`HPVP?`): no room, but a tap may start a fight from where this phone is. The reader claims it
 *   ([claimApdu]: "I'm opening a room for you"), opens a room, and hands over its code ([handOverApdu]) over the same
 *   tap, asking "still there?" ([pingApdu]) while the room opens. The card joins the code it is handed, or, when the
 *   reader's room won't open, is let go ([releaseApdu]) to ask for another tap.
 * - [openingResponse] (`HPVP.`): this phone's room is opening and its code is not in yet. The reader asks again
 *   until it gets the code (so a tap that comes too soon, or a hand-over cut short, still ends in a join).
 */

/** The application ID a tap selects: proprietary (it starts with F0), "HPVP" and 1. Also in res/xml/pvp_tap_service.xml. */
val PVP_AID: ByteArray = byteArrayOf(0xF0.toByte(), 0x48, 0x50, 0x56, 0x50, 0x31)

private val OK = byteArrayOf(0x90.toByte(), 0x00)
private val NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())
private const val PREFIX = "HPVP:"
private const val READY = "HPVP?"
private const val OPENING = "HPVP."

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

/** What a tapped phone said to the SELECT: a room to join, a room on its way, or that it is ready for a tap fight. */
sealed interface TapAnswer {
    data class Room(val code: String) : TapAnswer

    data object Opening : TapAnswer

    data object Ready : TapAnswer
}

/** A card's answer when it hosts no room but a tap may start a fight from where it is: "HPVP?", then 90 00. */
fun readyResponse(): ByteArray = READY.toByteArray(Charsets.US_ASCII) + OK

/** A card's answer while its room is opening and the code is not in yet: "HPVP.", then 90 00. */
fun openingResponse(): ByteArray = OPENING.toByteArray(Charsets.US_ASCII) + OK

/** What a tapped phone's [response] to the SELECT says, or null when it is nothing this app understands. */
fun answerFrom(response: ByteArray): TapAnswer? {
    codeFrom(response)?.let { return TapAnswer.Room(it) }
    if (response.contentEquals(readyResponse())) return TapAnswer.Ready
    if (response.contentEquals(openingResponse())) return TapAnswer.Opening
    return null
}

/** The reader's claim on a ready card, "I'm opening a room for you": CLA 80, INS 43 ("C"), P1 00, P2 00. */
fun claimApdu(): ByteArray = byteArrayOf(0x80.toByte(), 0x43, 0x00, 0x00)

fun isClaim(apdu: ByteArray): Boolean = apdu.size >= 4 && apdu.copyOfRange(0, 4).contentEquals(claimApdu())

/** A reader's "still there?" while its room opens: CLA 80, INS 50 ("P"), P1 00, P2 00. */
fun pingApdu(): ByteArray = byteArrayOf(0x80.toByte(), 0x50, 0x00, 0x00)

fun isPing(apdu: ByteArray): Boolean = apdu.size >= 4 && apdu.copyOfRange(0, 4).contentEquals(pingApdu())

/** A reader letting a claimed card go, with no room coming: CLA 80, INS 52 ("R"), P1 00, P2 00. */
fun releaseApdu(): ByteArray = byteArrayOf(0x80.toByte(), 0x52, 0x00, 0x00)

fun isRelease(apdu: ByteArray): Boolean = apdu.size >= 4 && apdu.copyOfRange(0, 4).contentEquals(releaseApdu())

/**
 * The reader's room [code], handed to the card it claimed: a proprietary command (CLA 80, INS 4A for "J", P1 00,
 * P2 00, Lc 04) with the code's four ASCII digits.
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

/** The card's answer to a claim, a ping or a hand-over: yes (90 00), or no (6A 82): it has moved on, or never took it. */
fun yesNo(yes: Boolean): ByteArray = if (yes) OK.copyOf() else NOT_FOUND.copyOf()

/** Whether the card said yes. */
fun saidYes(response: ByteArray): Boolean = response.contentEquals(OK)
