package team.superfun.horrorpets.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import team.superfun.horrorpets.ui.common.GlassPanel
import team.superfun.horrorpets.ui.common.bodyStyle
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.theme.Blood
import team.superfun.horrorpets.ui.theme.TextHi
import team.superfun.horrorpets.ui.theme.TextLo

/** How long the welcome-back toast stays up. */
const val TOAST_SHOW_MS = 6_000L
private const val TOAST_ANIM_MS = 300

/**
 * The welcome-back toast, top-right: slides in when [message] arrives, stays [TOAST_SHOW_MS], slides
 * out and then calls [onDismissed] (where the caller clears the message in the view model).
 */
@Composable
fun WelcomeToast(message: String?, onDismissed: () -> Unit, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(false) }
    val dismissed by rememberUpdatedState(onDismissed)

    LaunchedEffect(message) {
        if (message == null) {
            // Cleared early (the player walked off): just slide out.
            visible = false
            pending = false
            return@LaunchedEffect
        }
        text = message
        visible = true
        pending = true
        delay(TOAST_SHOW_MS)
        visible = false
        delay(TOAST_ANIM_MS.toLong())
        pending = false
        dismissed()
    }
    // Leaving Home before it timed out still counts as seen: it must not pop up again on the way back.
    DisposableEffect(Unit) {
        onDispose { if (pending) dismissed() }
    }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(TOAST_ANIM_MS)) + slideInHorizontally(tween(TOAST_ANIM_MS)) { it / 2 },
        exit = fadeOut(tween(TOAST_ANIM_MS)) + slideOutHorizontally(tween(TOAST_ANIM_MS)) { it / 2 },
    ) {
        GlassPanel(contentPadding = PaddingValues(start = 14.dp, end = 20.dp, top = 10.dp, bottom = 12.dp)) {
            Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.width(4.dp).fillMaxHeight().background(Blood, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("WELCOME BACK", style = displayStyle(17.sp, color = TextLo, tracking = 0.14f))
                    Text(text, style = bodyStyle(18.sp, color = TextHi), maxLines = 1)
                }
            }
        }
    }
}
