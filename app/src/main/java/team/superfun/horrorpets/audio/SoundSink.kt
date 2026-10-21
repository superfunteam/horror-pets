package team.superfun.horrorpets.audio

/** Where the UI hands the view model's sound queues. [GameAudio] plays them. */
interface SoundSink {
    /** Play the one-shot effect `audio/sfx/<name>.wav`. */
    fun sfx(name: String)

    /** Play the announcer lines `audio/voice/<name>.wav` back to back. */
    fun voice(names: List<String>)
}
