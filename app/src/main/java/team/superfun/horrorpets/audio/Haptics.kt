package team.superfun.horrorpets.audio

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.annotation.RequiresApi

/**
 * The buzz a phone gives with the sound [sfx], as alternating buzz and pause lengths in ms, starting with a buzz, or
 * null for a sound that stays silent in the hand. A special (`special_<pet>`) buzzes as a crit does, and a killer's
 * weapon sound (`hit_<pet>`, `heavy_<pet>`) as the plain `hit` or `heavy_hit` does.
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
        sfx.startsWith("special_") -> hapticFor("crit")
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

/**
 * Vibrates [effect] as part of the game: a media (or, before Android 13, game) vibration. Left to itself the system
 * files a short one as touch feedback, which many players switch off.
 */
@RequiresApi(26)
fun Vibrator.play(effect: VibrationEffect) {
    if (Build.VERSION.SDK_INT >= 33) {
        vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
    } else {
        @Suppress("DEPRECATION")
        vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build())
    }
}
