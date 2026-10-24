package team.superfun.horrorpets.ui.training

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import team.superfun.horrorpets.assets.Anim
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.ui.common.SpriteView
import team.superfun.horrorpets.ui.common.hexColor
import team.superfun.horrorpets.ui.common.mirrorToFace

/**
 * One of a training game's killers (Quick Draw's two, Hide and Eek!'s): its animation, and its play count so that the
 * same one plays again. Main thread only.
 */
@Stable
internal class TrainingActor {
    var anim by mutableStateOf(Anim.IDLE)
        private set
    var playKey by mutableIntStateOf(0)
        private set
    private var holds = false

    /** A swing or a flinch ends back at idle, unless it [holds] its last frame (the game is over). */
    val onFinished: () -> Unit = { if (!holds) anim = Anim.IDLE }

    /** Plays [next], from the start even if it is playing now; [hold] keeps its last frame up when it ends. */
    fun act(next: Anim, hold: Boolean = false) {
        anim = next
        holds = hold
        playKey++
    }
}

/**
 * Killer [pet] on the game's scene, [heightPx] tall (its cell) in the scene's light, acting as [actor] has it: idling
 * ([idleOffsetMs] into its cycle, so two side by side don't bob in step), swinging, flinching. On the [onLeft] of what
 * it watches, side-on art (Jaws) is turned round to face it (see [mirrorToFace]); art that faces the camera never is.
 * [modifier] places it (`placeAt`), and the turn is about its middle.
 */
@Composable
internal fun TrainingKiller(
    env: TrainingEnv,
    pet: PetId,
    actor: TrainingActor,
    heightPx: Float,
    idleOffsetMs: Long,
    onLeft: Boolean,
    modifier: Modifier,
) {
    val tint = remember { hexColor(env.scene.tint) }
    SpriteView(
        loader = env.loader,
        pet = pet,
        anim = actor.anim,
        heightPx = heightPx,
        tint = tint,
        tintStrength = env.scene.tintStrength,
        shadowOpacity = env.scene.shadowOpacity,
        loopOffsetMs = idleOffsetMs,
        playKey = actor.playKey,
        onFinished = actor.onFinished,
        modifier = if (mirrorToFace(pet, onLeft)) modifier.graphicsLayer { scaleX = -1f } else modifier,
    )
}
