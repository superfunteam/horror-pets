package team.superfun.horrorpets.ui.common

import androidx.compose.runtime.staticCompositionLocalOf
import team.superfun.horrorpets.audio.SoundSink

/**
 * Where a screen plays the sounds it times itself (the fake-death stings, the switcher's move tick), as
 * opposed to the view model's queued `sfx` / `voice`, which the router drains. `HorrorPetsApp` provides
 * the same sink it drains the queues into, so both kinds reach one player.
 */
val LocalSoundSink = staticCompositionLocalOf<SoundSink> {
    error("LocalSoundSink is not provided: wrap the screens in HorrorPetsApp")
}
