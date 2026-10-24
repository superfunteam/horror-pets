package team.superfun.horrorpets.pvp.nfc

/**
 * This phone as a contactless card, for another phone's tap (see TapProtocol.kt): what [PvpTapService] answers.
 *
 * To a SELECT it answers with the room it is [hosted] in, else that its room is [opening], else, when a tap may start
 * a fight from where the game is ([ready]), that it is ready. A reader that finds it ready claims it, and [listener]
 * decides whether this phone waits for that reader's room ([Listener.onClaimed]); the code then comes over the same
 * tap ([Listener.onHandedCode]), unless the phones part first ([Listener.onLost]). Otherwise, "not found".
 *
 * Android calls [answer] and [deactivated] on the main thread, where [listener] is called too.
 */
class TapCard {
    /** What this phone does when a tap claims it. */
    interface Listener {
        /**
         * A reader found this phone ready and is opening a room for it: whether this phone waits for its code
         * (holding still as a card meanwhile). Answer at once: the reader is waiting on it.
         */
        fun onClaimed(): Boolean

        /** The reader's room [code], handed over: whether this phone takes it (and joins). */
        fun onHandedCode(code: String): Boolean

        /** The tap is over before a code was handed over: the phones parted, or the reader's room won't open. */
        fun onLost()
    }

    /** The room code this phone is hosting, for a tap to read; null when it has none open. */
    @Volatile
    var hosted: String? = null

    /** Whether this phone is opening a room, and its code is not in yet. */
    @Volatile
    var opening: Boolean = false

    /** Whether a tap may start a fight from where the game is (see `tapReadyOf`). */
    @Volatile
    var ready: Boolean = false

    var listener: Listener? = null

    /** A reader claimed this phone on this tap and has not handed over its code yet. */
    private var awaitingCode = false

    fun answer(apdu: ByteArray): ByteArray {
        if (isPvpSelect(apdu)) {
            val code = hosted
            return when {
                code != null -> codeResponse(code)
                opening -> openingResponse()
                ready -> readyResponse()
                else -> codeResponse(null)
            }
        }
        if (isClaim(apdu)) {
            val claimed = hosted == null && !opening && ready && listener?.onClaimed() == true
            if (claimed) awaitingCode = true
            return yesNo(claimed)
        }
        if (isPing(apdu)) return yesNo(awaitingCode)
        if (isRelease(apdu)) {
            deactivated()
            return yesNo(true)
        }
        val code = handedOverCode(apdu) ?: return UNKNOWN_COMMAND
        if (!awaitingCode) return yesNo(false)
        awaitingCode = false
        return yesNo(listener?.onHandedCode(code) == true)
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
