package team.superfun.horrorpets.ui.common

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt

/**
 * Places the child (measured at its own size) so the point ([pivotX], [pivotY]) of it, as fractions of its
 * width and height, sits at ([x], [y]) pixels in the parent box; the child then takes up the whole box.
 * `pivotX = 0.5, pivotY = 1` plants a [SpriteView]'s feet on a floor point. The positions are read during
 * layout, so moving the child never recomposes it.
 */
fun Modifier.placeAt(x: () -> Float, y: () -> Float, pivotX: Float = 0.5f, pivotY: Float = 1f): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(Constraints())
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.place(
                (x() - placeable.width * pivotX).roundToInt(),
                (y() - placeable.height * pivotY).roundToInt(),
            )
        }
    }
