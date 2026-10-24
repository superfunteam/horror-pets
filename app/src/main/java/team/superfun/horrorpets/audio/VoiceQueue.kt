package team.superfun.horrorpets.audio

/** The pause between two announcer lines said back to back. */
internal const val VOICE_GAP_MS = 150L

/**
 * The announcer's lines, first in first out: the one being said ([sounding], which stays set through the
 * [gap][VOICE_GAP_MS] after it) and the ones [queued] behind it. Pure bookkeeping: [GameAudio] plays what it says, and
 * tests run the same queue on a clock of their own.
 */
internal class VoiceQueue {
    /** The line being said, or in the gap after it; null when the announcer is free to start the next. */
    var sounding: String? = null
        private set

    private val waiting = ArrayDeque<String>()

    /** The lines waiting their turn, in order. */
    val queued: List<String> get() = waiting.toList()

    /** The line that goes next, or null. */
    val next: String? get() = waiting.firstOrNull()

    /** Nothing being said and nothing waiting. */
    val idle: Boolean get() = sounding == null && waiting.isEmpty()

    fun add(names: List<String>) {
        waiting.addAll(names)
    }

    /** The [next] line starts: it is now [sounding]. Returns it. */
    fun start(): String = waiting.removeFirst().also { sounding = it }

    /** The [next] line is dropped unsaid (its clip is missing, or the player would not play it). */
    fun dropNext() {
        waiting.removeFirstOrNull()
    }

    /** The line [sounding] and the gap after it are over. */
    fun finished() {
        sounding = null
    }

    fun clear() {
        sounding = null
        waiting.clear()
    }

    /**
     * Makes [lines] the next thing said, for a screen whose own lines must land on time (the VS card), as it opens:
     * - if [lines] are already being said (the line [sounding] is one of them and the rest follow it), nothing changes;
     * - else, if they are [queued], the line sounding is cut and every line queued ahead of their last run is dropped;
     * - else (they are not queued yet) everything is dropped, so they start the moment they are.
     *
     * Their last line sounding alone, with nothing behind it, is not taken for them being said: the screen claims the
     * announcer as it opens, too soon for its lines to be that far on, so it is an earlier screen's line that happens
     * to match (the challenger entrance ends on the name the VS card ends on) and is cut.
     *
     * Returns whether the line sounding (or the gap after it) was cut: the caller silences it.
     */
    fun skipTo(lines: List<String>): Boolean {
        if (lines.isEmpty()) return false
        // Their last full run in the queue: a card opened again says its new lines, not the old ones.
        for (k in waiting.size - lines.size downTo 0) {
            if ((lines.indices).all { waiting[k + it] == lines[it] }) {
                repeat(k) { waiting.removeFirst() }
                return cut()
            }
        }
        val now = sounding
        if (now != null) {
            for (j in lines.indices) {
                if (j > 0 && j == lines.lastIndex) continue // their last line alone: see above
                val rest = lines.subList(j + 1, lines.size)
                if (lines[j] == now && waiting.size >= rest.size && rest.indices.all { waiting[it] == rest[it] }) return false
            }
        }
        waiting.clear()
        return cut()
    }

    private fun cut(): Boolean = (sounding != null).also { sounding = null }
}
