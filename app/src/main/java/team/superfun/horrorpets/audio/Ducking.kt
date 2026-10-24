package team.superfun.horrorpets.audio

/** The music's level (a factor on its volume) while the announcer speaks over it. */
internal const val DUCK_LEVEL = 0.3f

/** The music's level under a special's riff: lower than under a voice, so the riff owns the room. */
internal const val RIFF_DUCK = 0.25f

/**
 * How loud the music plays (a factor on its volume) under what sounds over it: 1 with nothing, [DUCK_LEVEL] while the
 * announcer speaks ([voice]), [RIFF_DUCK] while a special's riff plays ([riff]), and the lower of the two for both.
 */
internal fun duckLevel(voice: Boolean, riff: Boolean): Float {
    var level = 1f
    if (voice) level = minOf(level, DUCK_LEVEL)
    if (riff) level = minOf(level, RIFF_DUCK)
    return level
}
