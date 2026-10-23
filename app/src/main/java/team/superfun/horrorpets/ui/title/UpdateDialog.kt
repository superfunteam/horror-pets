package team.superfun.horrorpets.ui.title

import android.content.pm.PackageInstaller
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.superfun.horrorpets.ui.common.FocusPill
import team.superfun.horrorpets.ui.common.GameBackHandler
import team.superfun.horrorpets.ui.common.bodyStyle
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.common.gameFrame
import team.superfun.horrorpets.ui.common.ignoreOkUnless
import team.superfun.horrorpets.ui.common.rememberOkReady
import team.superfun.horrorpets.ui.common.rememberTouchTick
import team.superfun.horrorpets.ui.theme.Amber
import team.superfun.horrorpets.ui.theme.Blood
import team.superfun.horrorpets.ui.theme.BloodGlow
import team.superfun.horrorpets.ui.theme.TextHi
import team.superfun.horrorpets.ui.theme.TextLo
import team.superfun.horrorpets.update.Release
import team.superfun.horrorpets.update.UpdateActions
import team.superfun.horrorpets.update.UpdateProblem
import team.superfun.horrorpets.update.UpdateStep
import team.superfun.horrorpets.update.megabytes
import team.superfun.horrorpets.update.openInstallSettings

/** One of the update window's buttons. */
internal enum class UpdateButton(val label: String) {
    UPDATE("Update"),
    RETRY("Retry"),
    SETTINGS("Settings"),
    CANCEL("Cancel"),
    LATER("Later"),
}

/** [step]'s buttons, left to right. The first has the focus; the last is what Back does. */
internal fun buttonsFor(step: UpdateStep): List<UpdateButton> = when (step) {
    UpdateStep.None -> emptyList()
    is UpdateStep.Offer -> listOf(UpdateButton.UPDATE, UpdateButton.LATER)
    is UpdateStep.Downloading, is UpdateStep.Verifying -> listOf(UpdateButton.CANCEL)
    is UpdateStep.NeedsPermission -> if (step.manual) listOf(UpdateButton.LATER) else listOf(UpdateButton.SETTINGS, UpdateButton.LATER)
    is UpdateStep.Installing -> listOf(UpdateButton.LATER)
    is UpdateStep.Failed -> listOf(UpdateButton.RETRY, UpdateButton.LATER)
}

/** Whether a tap off the window closes it, as LATER does. Never while a download runs: a stray tap must not stop it. */
internal fun tapOffCloses(step: UpdateStep): Boolean = buttonsFor(step).lastOrNull() == UpdateButton.LATER

/** The window's title at [step]. */
internal fun updateTitle(step: UpdateStep): String = when (step) {
    UpdateStep.None, is UpdateStep.Offer -> "UPDATE AVAILABLE"
    is UpdateStep.Downloading, is UpdateStep.Verifying -> "DOWNLOADING"
    is UpdateStep.NeedsPermission -> "ONE MORE STEP"
    is UpdateStep.Installing -> "INSTALLING"
    is UpdateStep.Failed -> "UPDATE FAILED"
}

/** What the offer says about [release]. */
internal fun offerText(release: Release): String =
    // Two lines, broken by hand so neither wraps on its own: the window is 480 dp of text everywhere (a TV, a 4:3
    // set, a phone: all on the 540-dp-tall canvas), and at 18sp, even at the 1.15 font-scale cap, each fits.
    "Version ${release.version} is out (${megabytes(release.size)} MB).\nYour pets stay just as they are."

/** "48 / 97 MB": the MB downloaded so far (whole ones, so it only reads the total at the end) of the [total]. */
internal fun progressText(done: Long, total: Long): String {
    val doneMb = if (done >= total) megabytes(total) else done / (1024 * 1024)
    return "$doneMb / ${megabytes(total)} MB"
}

/** How full the progress bar is: 0 to 1. */
internal fun progressFraction(done: Long, total: Long): Float = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)

/** What a [problem] says, short. */
internal fun problemText(problem: UpdateProblem): String = when (problem) {
    is UpdateProblem.NoSpace ->
        "Not enough space: the update needs ${megabytes(problem.needed)} MB free, and there's ${megabytes(problem.free)} MB. Free some up and retry."
    UpdateProblem.Network -> "The download didn't go through. Check your internet."
    UpdateProblem.BadDownload -> "The download came out wrong. Try it again."
    UpdateProblem.Installer -> "Couldn't hand the update to Android's installer."
    is UpdateProblem.Install -> when (problem.status) {
        PackageInstaller.STATUS_FAILURE_ABORTED -> "The install was cancelled."
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "This device blocked the install."
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "It doesn't match the installed game (signed with another key)."
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "This device can't run that version."
        PackageInstaller.STATUS_FAILURE_INVALID -> "Android says the download isn't a valid app."
        PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough space to install it."
        else -> "Android couldn't install it."
    }
}

private const val ALLOW_TEXT = "Allow installs from this app, then come back."
private const val ALLOW_MANUAL_TEXT =
    "Allow installs from this app in your device's settings (look for Install unknown apps, or Unknown sources), then come back."
private const val INSTALLING_TEXT = "Confirm on Android's install screen. The game closes while it updates; open it again after."
private const val CHECKING_TEXT = "Checking the download…"

/**
 * The update window, over a scrim on the title: the offer of a newer release, then its download (a progress bar and
 * CANCEL), the check of it, a request to allow installs from this app (Android 8+) and the hand-over to Android's
 * installer, or what went wrong (RETRY). The first button has the focus; Back does what the last does (LATER, or
 * CANCEL during a download), and so does a tap off the window, except during a download. For a moment after it
 * comes up, and after each step, OK and taps are ignored, so a mashed OK cannot start a download.
 */
@Composable
internal fun UpdateDialog(step: UpdateStep, actions: UpdateActions, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val buttons = buttonsFor(step)
    val first = remember { FocusRequester() }
    val press = { button: UpdateButton ->
        when (button) {
            UpdateButton.UPDATE, UpdateButton.RETRY -> actions.accept()
            UpdateButton.LATER -> actions.later()
            UpdateButton.CANCEL -> actions.cancel()
            UpdateButton.SETTINGS -> if (!openInstallSettings(context)) actions.settingsMissing()
        }
    }
    GameBackHandler { buttons.lastOrNull()?.let(press) }
    LaunchedEffect(buttons) { runCatching { first.requestFocus() } }
    val okReady = rememberOkReady(buttons)
    val back = rememberTouchTick("ui_back")
    val closesOnTap = tapOffCloses(step)
    // Taps, not clicks, on the scrim and the window: a clickable would take the remote's focus off the buttons.
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
            .ignoreOkUnless(okReady)
            .pointerInput(closesOnTap) {
                detectTapGestures {
                    if (closesOnTap) {
                        back()
                        actions.later()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier
                .width(560.dp)
                .gameFrame(topEnd = 14.dp, bottomStart = 14.dp)
                .pointerInput(Unit) { detectTapGestures {} } // a tap on the window itself doesn't close it
                .padding(horizontal = 40.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(updateTitle(step), style = displayStyle(40.sp, tracking = 0.08f, lineHeight = 40.sp), maxLines = 1)
            Spacer(Modifier.height(14.dp))
            when (step) {
                UpdateStep.None -> Unit
                is UpdateStep.Offer -> Sentence(offerText(step.release))
                is UpdateStep.Downloading -> Progress(progressFraction(step.done, step.release.size), progressText(step.done, step.release.size))
                is UpdateStep.Verifying -> Progress(1f, CHECKING_TEXT)
                is UpdateStep.NeedsPermission -> Sentence(if (step.manual) ALLOW_MANUAL_TEXT else ALLOW_TEXT)
                is UpdateStep.Installing -> Sentence(INSTALLING_TEXT)
                is UpdateStep.Failed -> Sentence(problemText(step.problem), color = BloodGlow)
            }
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                buttons.forEachIndexed { i, button ->
                    FocusPill(
                        button.label,
                        onClick = { press(button) },
                        modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                        minWidth = 160.dp,
                    )
                }
            }
        }
    }
}

/** A line or two of plain words, centred. */
@Composable
private fun Sentence(text: String, color: Color = TextHi) {
    BasicText(
        text,
        style = bodyStyle(18.sp, color = color).copy(textAlign = TextAlign.Center, lineBreak = BalancedLines),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Lines of about even length, so a wrapped sentence never leaves one word alone on its last line (Android 6+). */
private val BalancedLines = LineBreak(LineBreak.Strategy.Balanced, LineBreak.Strictness.Normal, LineBreak.WordBreak.Default)

/** The download's bar, filled to [fraction], with [label] under it. */
@Composable
private fun Progress(fraction: Float, label: String) {
    // Eased, so the bar glides between the download's progress reports instead of jumping.
    val shown by animateFloatAsState(fraction, tween(250), label = "updateProgress")
    Box(
        Modifier
            .fillMaxWidth()
            .height(14.dp)
            .drawBehind {
                val corner = CornerRadius(size.height / 2f)
                drawRoundRect(Color.White.copy(alpha = 0.1f), cornerRadius = corner)
                if (shown > 0f) {
                    drawRoundRect(
                        Brush.horizontalGradient(listOf(Blood, BloodGlow)),
                        size = Size(size.width * shown, size.height),
                        cornerRadius = corner,
                    )
                }
                drawRoundRect(Amber.copy(alpha = 0.35f), cornerRadius = corner, style = Stroke(1.dp.toPx()))
            },
    )
    Spacer(Modifier.height(10.dp))
    BasicText(label, style = displayStyle(20.sp, color = TextLo, tracking = 0.06f), maxLines = 1)
}
