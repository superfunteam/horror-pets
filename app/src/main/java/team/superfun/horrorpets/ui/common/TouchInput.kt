package team.superfun.horrorpets.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sign

/**
 * Touch on phones, next to the remote on TVs. The game is one APK for both: on a TV nothing here changes a
 * thing (a TV never has a touchscreen, so it never goes into touch mode), and on a phone the same screens
 * take taps and swipes. Which one the player is using right now is Android's touch mode: a touch puts the
 * window in it, a D-pad or keyboard press takes it out (see [isTouchInput]).
 */

/**
 * Whether this device takes touch at all: it has a touchscreen and is not a TV. Some TV boxes start in touch
 * mode for want of a better default, so a TV never counts, whatever its touch mode says.
 */
fun isTouchDevice(television: Boolean, touchscreen: Boolean): Boolean = touchscreen && !television

/** Whether the UI is being driven by touch: on a [touchDevice] whose window is in touch mode ([inTouchMode]). */
fun isTouchInput(touchDevice: Boolean, inTouchMode: Boolean): Boolean = touchDevice && inTouchMode

/** Whether this device takes touch (see [isTouchDevice]). `MainActivity` provides it; false means remote only. */
val LocalTouchDevice = staticCompositionLocalOf { false }

/**
 * Whether the player is using touch right now: a phone's last input was a touch. Then focus is not drawn
 * (nothing is focused under a finger) and a pressed control lights up instead. Always false on a TV.
 */
@Composable
fun isTouchInput(): Boolean =
    isTouchInput(LocalTouchDevice.current, LocalInputModeManager.current.inputMode == InputMode.Touch)

/**
 * A function that plays the UI tick [sound] when the press it accompanies came from touch. A remote's keys
 * tick in `MainActivity` (`ui_select` on OK, `ui_back` on Back) before any screen sees them, so on a TV, or
 * on a phone driven by a gamepad, this stays quiet and the press ticks exactly once either way.
 */
@Composable
fun rememberTouchTick(sound: String): () -> Unit {
    val sounds = LocalSoundSink.current
    val touchDevice = LocalTouchDevice.current
    val input = LocalInputModeManager.current
    return remember(sounds, touchDevice, input, sound) {
        { if (isTouchInput(touchDevice, input.inputMode == InputMode.Touch)) sounds.sfx(sound) }
    }
}

/**
 * `BackHandler` for the game's screens: the remote's Back and a phone's back gesture both end up here. A
 * gesture plays the `ui_back` tick that `MainActivity` plays for the remote's key.
 */
@Composable
fun GameBackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val tick = rememberTouchTick("ui_back")
    BackHandler(enabled) {
        tick()
        onBack()
    }
}

// ---- the system's edges ----

/**
 * The strips along the window's edges where the system takes swipes, in px from each edge: the back gesture
 * down the sides, the bars and the home gesture along the top and bottom. With the bars hidden, the first
 * swipe in from an edge only brings the bars back, and the app sees it too; a swipe that starts in a strip
 * is the system's, so the game's own swipes leave it alone (a tap there still works). All zero on a TV.
 */
@Immutable
class GestureEdges(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    /** Whether [position], in a window [size] big, is in one of the strips. */
    fun contain(position: Offset, size: IntSize): Boolean =
        position.x < left || position.y < top || position.x >= size.width - right || position.y >= size.height - bottom

    companion object {
        val None = GestureEdges(0f, 0f, 0f, 0f)
    }
}

/** The system's [GestureEdges] for this window, right now. */
@Composable
fun systemGestureEdges(): GestureEdges {
    val insets = WindowInsets.systemGestures.union(WindowInsets.mandatorySystemGestures)
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    return GestureEdges(
        left = insets.getLeft(density, direction).toFloat(),
        top = insets.getTop(density).toFloat(),
        right = insets.getRight(density, direction).toFloat(),
        bottom = insets.getBottom(density).toFloat(),
    )
}

// ---- swiping between scenes ----

/** A swipe walks once the finger has gone this far sideways (on the 540-dp canvas, about a tenth of its height). */
val SWIPE_WALK_DISTANCE = 56.dp

/** A shorter swipe still walks if it is a flick at least this fast (dp per second). */
const val SWIPE_WALK_FLING_DP_PER_S = 500f

/** A swipe walks only if it goes mostly sideways: at least this many times further across than up or down. */
private const val SWIPE_HORIZONTAL_RATIO = 1.5f

/**
 * The scene to walk to after a swipe that moved the finger [dx], [dy] (px) and let go at [velocityX] (px/s):
 * 1 for the next scene, -1 for the previous one, 0 for no walk. It reads like paging: swipe left for the
 * next scene (the pet runs off to the right and the next scene comes in from there), right for the previous.
 * A swipe counts when it goes mostly sideways and either covers [minDistance] or is a flick of at least
 * [minFlingVelocity] in the same direction.
 */
fun swipeWalkDirection(dx: Float, dy: Float, velocityX: Float, minDistance: Float, minFlingVelocity: Float): Int {
    if (dx == 0f || abs(dx) < abs(dy) * SWIPE_HORIZONTAL_RATIO) return 0
    val far = abs(dx) >= minDistance
    val flick = abs(velocityX) >= minFlingVelocity && sign(velocityX) == sign(dx)
    return if (far || flick) -sign(dx).toInt() else 0
}

/**
 * Detects sideways swipes anywhere in this element and calls [onWalk] with [swipeWalkDirection]'s answer
 * when one ends. It watches gestures that start on a button too, and once the finger has moved past the
 * touch slop sideways it takes the gesture over, so the button under it never clicks. A gesture that starts
 * while [enabled] says no, or in one of the system's [edges] (the element must fill the window), is ignored
 * to the end. The lambdas are read when they are needed, so they may read state; the detector itself never
 * needs restarting.
 */
suspend fun PointerInputScope.detectWalkSwipes(
    enabled: () -> Boolean,
    edges: () -> GestureEdges,
    onWalk: (dir: Int) -> Unit,
) {
    val minDistance = SWIPE_WALK_DISTANCE.toPx()
    val minFling = SWIPE_WALK_FLING_DP_PER_S * density
    awaitEachGesture {
        // A button under the finger has already taken the down; the swipe is watched all the same.
        val down = awaitFirstDown(requireUnconsumed = false)
        if (!enabled() || edges().contain(down.position, size)) return@awaitEachGesture
        val tracker = VelocityTracker()
        tracker.addPointerInputChange(down)
        var swiping = false
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            tracker.addPointerInputChange(change)
            val moved = change.position - down.position
            if (!change.pressed) {
                if (swiping) {
                    change.consume()
                    val dir = swipeWalkDirection(moved.x, moved.y, tracker.calculateVelocity().x, minDistance, minFling)
                    if (dir != 0 && enabled()) onWalk(dir)
                }
                return@awaitEachGesture
            }
            if (!swiping && abs(moved.x) > viewConfiguration.touchSlop && abs(moved.x) > abs(moved.y)) swiping = true
            // Consumed from here on, so a button the swipe started on lets go of it instead of clicking.
            if (swiping) change.consume()
        }
    }
}
