package team.superfun.horrorpets.audio

import android.content.Context
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager

/**
 * The buzz a phone gives with the sound [sfx], as the lengths in milliseconds of its pulses with a pause between each
 * two, or null for a sound that stays silent in the hand. A weapon's sound to come (`hit_<x>`, `heavy_<x>`) buzzes as
 * the plain `hit` or `heavy_hit` does.
 */
fun hapticFor(sfx: String): LongArray? = when (sfx) {
    "hit" -> longArrayOf(15)
    "heavy_hit" -> longArrayOf(35)
    "crit" -> longArrayOf(60)
    "ko", "ko_boom" -> longArrayOf(120)
    "reel_stop" -> longArrayOf(25)
    "pantry_squelch" -> longArrayOf(40)
    "pantry_poof" -> longArrayOf(20)
    "levelup" -> longArrayOf(40, 60, 40)
    else -> when {
        sfx.startsWith("hit_") -> hapticFor("hit")
        sfx.startsWith("heavy_") -> hapticFor("heavy_hit")
        else -> null
    }
}

/** The device's vibrator, or null when it has none (every TV). */
fun vibratorOf(context: Context): Vibrator? {
    val vibrator = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    return vibrator?.takeIf { it.hasVibrator() }
}
