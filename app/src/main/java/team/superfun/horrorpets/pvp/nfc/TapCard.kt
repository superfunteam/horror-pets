package team.superfun.horrorpets.pvp.nfc

/**
 * This phone as a contactless card, for another phone's tap (see TapProtocol.kt): what [PvpTapService] answers.
 *
 * To a SELECT it answers with the room it is [hosted] in, else, when a tap may start a fight from where the game is
 * ([ready]), that it is ready: the reader will open a room and hand over its code, which [listener] hears about
 * ([Listener.onFound], then [Listener.onHandedCode]). Otherwise, "not found".
 *
 * Android calls [answer] and [deactivated] on the main thread, where [listener] is called too.
 */
class TapCard {
    /** What this phone does when a tap reads it as ready. */
    interface Listener {
        /** A reader found this phone ready: hold still as a card, its room code is on the way. */
        fun onFound()

        /** The reader's room [code], handed over: whether this phone takes it (and joins). */
        fun onHandedCode(code: String): Boolean

        /** The tap is over (the phones parted, or the reader moved on) before a code was handed over. */
        fun onLost()
    }

    /** The room code this phone is hosting, for a tap to read; null when it has none open. */
    @Volatile
    var hosted: String? = null

    /** Whether a tap may start a fight from where the game is (see `tapReadyOf`). */
    @Volatile
    var ready: Boolean = false

    var listener: Listener? = null

    /** A reader found this phone ready on this tap and has not handed over its code yet. */
    private var awaitingCode = false

    fun answer(apdu: ByteArray): ByteArray {
        if (isPvpSelect(apdu)) {
            val code = hosted
            if (code != null) return codeResponse(code)
            if (!ready) return codeResponse(null)
            awaitingCode = true
            listener?.onFound()
            return readyResponse()
        }
        val code = handedOverCode(apdu) ?: return UNKNOWN_COMMAND
        if (!awaitingCode) return handOverResponse(taken = false)
        awaitingCode = false
        return handOverResponse(taken = listener?.onHandedCode(code) == true)
    }

    /** The reader went away (or chose another card). */
    fun deactivated() {
        if (!awaitingCode) return
        awaitingCode = false
        listener?.onLost()
    }

    companion object {
        /** The app's one card, shared by [PvpTapService] and the game. */
        val shared = TapCard()
    }
}
