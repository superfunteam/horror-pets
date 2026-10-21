package team.superfun.horrorpets.audio

import android.util.Log

/** Where the UI hands the view model's sound queues. Task 15 replaces [LogSoundSink] with real playback. */
interface SoundSink {
    /** Play the one-shot effect `audio/sfx/<name>.ogg`. */
    fun sfx(name: String)

    /** Play the announcer lines `audio/voice/<name>.ogg` back to back. */
    fun voice(names: List<String>)
}

/** Logs every request under the `GameAudio` tag and plays nothing. */
object LogSoundSink : SoundSink {
    private const val TAG = "GameAudio"

    override fun sfx(name: String) {
        Log.d(TAG, "sfx $name")
    }

    override fun voice(names: List<String>) {
        Log.d(TAG, "voice ${names.joinToString(" > ")}")
    }
}
