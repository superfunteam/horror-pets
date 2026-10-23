package team.superfun.horrorpets.ui.title

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import team.superfun.horrorpets.assets.AssetLoader
import team.superfun.horrorpets.ui.common.GameBackHandler
import team.superfun.horrorpets.ui.common.KeyCap
import team.superfun.horrorpets.ui.common.RequestFocusOnEntry
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.common.ignoreOkUnless
import team.superfun.horrorpets.ui.common.isTouchInput
import team.superfun.horrorpets.ui.common.rememberOkReady
import team.superfun.horrorpets.ui.common.rememberTouchTick
import team.superfun.horrorpets.ui.common.safeInset
import team.superfun.horrorpets.ui.theme.Blood
import team.superfun.horrorpets.ui.theme.Ink
import team.superfun.horrorpets.ui.theme.TextHi
import team.superfun.horrorpets.update.UpdateActions
import team.superfun.horrorpets.update.UpdateStep

/** The title's art and logo (written by tools/make_branding.py from branding/). */
private const val COVER = "sprites/title/cover.webp"
private const val LOGO = "sprites/title/logo.webp"

/** The logo's width, as a fraction of the screen's (as on the TV banner), and its top, of the screen's height. */
private const val LOGO_WIDTH = 0.58f
private const val LOGO_TOP = 0.04f

/** PRESS START is lit this long, then dim as long. */
private const val BLINK_MS = 560

/** PRESS START's red glow, like the stage lights in the carousel. */
private val StartGlow = Shadow(color = Blood, offset = Offset(0f, 0f), blurRadius = 26f)

/**
 * The title screen, on every launch: the cover art filling the screen (cropped to fit any shape), the logo over its
 * open sky and a blinking PRESS START. OK or a tap starts ([onStart]); Back leaves the app ([onExit]). A little
 * CREDITS link sits in the corner (Down or Right gets to it on a remote, Up or Left back) and opens [CreditsDialog].
 *
 * When the launch's update check has found a newer release, [update] is its window's step and [UpdateDialog] comes up
 * over the title (after the credits, if they are open), its buttons doing [updateActions]. It only ever shows here:
 * leaving the title closes it, and an offer that comes in after the player has left waits for the next launch.
 */
@Composable
fun TitleScreen(
    loader: AssetLoader,
    onStart: () -> Unit,
    onExit: () -> Unit,
    focusEntry: Any? = Unit,
    update: UpdateStep = UpdateStep.None,
    updateActions: UpdateActions = UpdateActions.None,
) {
    val focus = remember { FocusRequester() }
    val creditsFocus = remember { FocusRequester() }
    var showCredits by remember { mutableStateOf(false) }
    var backToCredits by remember { mutableStateOf(false) }
    LaunchedEffect(backToCredits) {
        if (backToCredits) runCatching { creditsFocus.requestFocus() }
        backToCredits = false
    }
    val showUpdate = update != UpdateStep.None && focusEntry != null && !showCredits
    // The last step shown, so the window still has its words while it fades out.
    var shownUpdate by remember { mutableStateOf(update) }
    if (update != UpdateStep.None) shownUpdate = update
    // Closed: the title has the focus back, for OK to start.
    var updateWasShown by remember { mutableStateOf(false) }
    LaunchedEffect(showUpdate) {
        if (!showUpdate && updateWasShown && focusEntry != null) runCatching { focus.requestFocus() }
        updateWasShown = showUpdate
    }
    // The window belongs to the title: leaving the title (only the debug puppet can, with the window up) closes it.
    LaunchedEffect(focusEntry) { if (focusEntry == null) updateActions.later() }
    GameBackHandler(enabled = focusEntry != null && !showCredits && !showUpdate, onBack = onExit)
    RequestFocusOnEntry(focus, focusEntry)
    val okReady = rememberOkReady(focusEntry)
    val art by produceState<ImageBitmap?>(null, loader) { value = withContext(Dispatchers.IO) { loader.prop(COVER) } }
    val logo by produceState<ImageBitmap?>(null, loader) { value = withContext(Dispatchers.IO) { loader.prop(LOGO) } }
    val shown by animateFloatAsState(if (art != null && logo != null) 1f else 0f, tween(600), label = "titleIn")
    // A hard blink, arcade style: the easing jumps from lit to dim halfway through each half-cycle.
    val blink = rememberInfiniteTransition(label = "pressStart").animateFloat(
        initialValue = 1f,
        targetValue = 0.22f,
        animationSpec = infiniteRepeatable(tween(BLINK_MS, easing = { if (it < 0.5f) 0f else 1f }), RepeatMode.Reverse),
        label = "pressStartBlink",
    )
    val touch = isTouchInput()
    val tick = rememberTouchTick("ui_select")

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .ignoreOkUnless(okReady)
            .focusRequester(focus)
            .focusProperties {
                down = creditsFocus
                right = creditsFocus
                canFocus = !showCredits && !showUpdate
            }
            .clickable(remember { MutableInteractionSource() }, indication = null) {
                tick()
                onStart()
            },
    ) {
        val safe = safeInset()
        art?.let {
            Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = shown })
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f))))
        logo?.let {
            Image(
                it,
                contentDescription = "Virtual Pet Slasher 2 Turbo",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = maxHeight * LOGO_TOP)
                    .width(minOf(maxWidth * LOGO_WIDTH, maxWidth - safe.horizontal * 2))
                    .graphicsLayer { alpha = shown },
            )
        }
        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = maxHeight * 0.1f).graphicsLayer { alpha = shown },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(
                "PRESS START",
                style = displayStyle(44.sp, tracking = 0.32f).copy(shadow = StartGlow),
                maxLines = 1,
                modifier = Modifier.graphicsLayer { alpha = blink.value },
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                KeyCap(if (touch) "TAP" else "OK")
                BasicText("TO BEGIN", style = displayStyle(14.sp, FontWeight.Bold, color = TextHi.copy(alpha = 0.75f), tracking = 0.22f))
            }
        }
        CreditsLink(
            onClick = { showCredits = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = safe.horizontal, bottom = safe.vertical)
                .graphicsLayer { alpha = shown }
                .focusRequester(creditsFocus)
                .focusProperties {
                    up = focus
                    left = focus
                    canFocus = !showCredits && !showUpdate
                },
        )
        AnimatedVisibility(visible = showCredits, enter = fadeIn(tween(160)), exit = fadeOut(tween(160))) {
            CreditsDialog(
                // The window grows in as the scrim fades in, and settles back as it fades out.
                modifier = Modifier.animateEnterExit(
                    enter = scaleIn(tween(200), initialScale = 0.9f),
                    exit = scaleOut(tween(160), targetScale = 0.95f),
                ),
                onClose = {
                    showCredits = false
                    backToCredits = true
                },
            )
        }
        AnimatedVisibility(visible = showUpdate, enter = fadeIn(tween(160)), exit = fadeOut(tween(160))) {
            UpdateDialog(
                step = shownUpdate,
                actions = updateActions,
                modifier = Modifier.animateEnterExit(
                    enter = scaleIn(tween(200), initialScale = 0.9f),
                    exit = scaleOut(tween(160), targetScale = 0.95f),
                ),
            )
        }
    }
}
