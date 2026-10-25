package team.superfun.horrorpets.audio

/** Where the UI hands the view model's sound queues. [GameAudio] plays them. */
interface SoundSink {
    /** Play the one-shot effect `audio/sfx/<name>.wav`. */
    fun sfx(name: String)

    /** Play the announcer lines `audio/voice/<name>.wav` back to back. */
    fun voice(names: List<String>)

    /**
     * A killer's line in a fight, `audio/voice/<name>.wav`: said now if the announcer is quiet, else dropped (never
     * queued behind him, never over him; see [VoiceQueue.quip]). A sink with no announcer ignores it.
     */
    fun quip(name: String) = Unit

    /**
     * Make [lines] the announcer's next words, now: whatever an earlier screen left him saying is cut short and its
     * queued lines are dropped, while [lines] themselves (queued already, or about to be) are kept and start at once.
     * Lines already under way are left alone. See [VoiceQueue.skipTo]. A sink with no announcer ignores it.
     */
    fun skipVoiceTo(lines: List<String>) = Unit

    /**
     * Starts ([on]) or ends a special's riff, `audio/music/special_riff.ogg`: a loop over the music, which ducks under
     * it while it plays. Ending it fades it out and brings the music back. A sink with no music ignores it.
     */
    fun riff(on: Boolean) = Unit
}
