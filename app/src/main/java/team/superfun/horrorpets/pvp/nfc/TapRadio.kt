package team.superfun.horrorpets.pvp.nfc

import kotlin.random.Random

/** How this phone's NFC is used for PvP taps, from where the game is (see `tapModeOf`). */
enum class TapMode {
    /** No reading. The phone can still be read as a card: while it hosts a room, or waits for a code it was promised. */
    OFF,

    /** Reading all the time: the lobby's code entry, where a tap joins the tapped phone's room (or hosts one for it). */
    LISTEN,

    /**
     * Taking turns at reading and being read: Home, where both phones are on the same screen and a tap must catch
     * one of them reading while the other is a card.
     */
    TAKE_TURNS,
}

/**
 * Whether this phone's NFC should be reading right now ([readerOn]). In [TapMode.LISTEN], always. In
 * [TapMode.TAKE_TURNS], for a random [TURN_MIN_MS] to [TURN_MAX_MS], then a card for another such stretch, and so on:
 * two phones doing the same drift in and out of step, so a tap soon finds one reading while the other is a card.
 *
 * While a tap is under way ([holding]), nothing changes, whatever the mode: switching reader mode drops the link. A
 * phone a reader just claimed stays a card ([holdAsCard]) until it is told what came of it ([release]), or for
 * [CARD_HOLD_MS] at most, so its turns cannot pull it out from under the reader.
 *
 * Time is whatever clock the caller passes in milliseconds; [advance] says when to call again.
 */
class TapRadio(private val rng: Random) {
    var mode: TapMode = TapMode.OFF
        private set

    /** Whether the game is in front: NFC reading is only ever on while it is. */
    var resumed = false

    /** A tap is under way, read by this phone: reading stays as it is until it is done. Set from the NFC thread. */
    @Volatile
    var holding = false

    private var cardUntilMs = Long.MIN_VALUE
    private var reading = false
    private var turnEndsMs = 0L

    fun setMode(mode: TapMode, nowMs: Long) {
        if (mode == this.mode) return
        this.mode = mode
        if (mode == TapMode.TAKE_TURNS) {
            reading = rng.nextBoolean()
            turnEndsMs = nowMs + turn()
        }
    }

    fun holdAsCard(nowMs: Long) {
        cardUntilMs = nowMs + CARD_HOLD_MS
    }

    fun release() {
        cardUntilMs = Long.MIN_VALUE
    }

    /** Whether to read at [nowMs], given whether this phone is reading now ([current]). */
    fun readerOn(nowMs: Long, current: Boolean): Boolean {
        if (!resumed) return false
        if (holding) return current
        if (nowMs < cardUntilMs) return false
        return when (mode) {
            TapMode.OFF -> false
            TapMode.LISTEN -> true
            TapMode.TAKE_TURNS -> reading
        }
    }

    /** Moves the turns on to [nowMs]; returns how long until something changes, or null when nothing is timed. */
    fun advance(nowMs: Long): Long? {
        var due: Long? = null
        if (mode == TapMode.TAKE_TURNS) {
            if (nowMs >= turnEndsMs) {
                reading = !reading
                turnEndsMs = nowMs + turn()
            }
            due = turnEndsMs - nowMs
        }
        if (nowMs < cardUntilMs) due = minOf(due ?: Long.MAX_VALUE, cardUntilMs - nowMs)
        return due
    }

    private fun turn(): Long = rng.nextLong(TURN_MIN_MS, TURN_MAX_MS + 1)

    companion object {
        // Long enough for a reader to restart its discovery, find the card, and say hello, with time to spare.
        const val TURN_MIN_MS = 500L
        const val TURN_MAX_MS = 1_300L
        const val CARD_HOLD_MS = 4_000L
    }
}
