package team.superfun.horrorpets.update

import java.io.IOException

/**
 * Where the update window is, from the offer to Android's installer. [UpdateController] moves it along; the title
 * screen shows it (see `UpdateDialog`).
 *
 * Offer -> Downloading -> Verifying -> Installing (or NeedsPermission first, until the player allows installs from
 * this app). Any of the last four can end in Failed, whose RETRY goes back to downloading (or straight to installing,
 * when the download is already done). CANCEL takes a download back to the Offer; LATER closes the window ([None]).
 */
sealed interface UpdateStep {
    /** No window: no update found (yet), or the player closed it for this launch. */
    data object None : UpdateStep

    /** [release] is out: UPDATE or LATER. */
    data class Offer(val release: Release) : UpdateStep

    /** Downloading [release]'s APK: [done] bytes of its size so far. */
    data class Downloading(val release: Release, val done: Long) : UpdateStep

    /** Checking the downloaded APK's size and hash. */
    data class Verifying(val release: Release) : UpdateStep

    /**
     * Android 8+ only: the player has to allow installs from this app first. [manual] when the device has no screen
     * for that to open (some TV builds), so the window says where to find it instead.
     */
    data class NeedsPermission(val release: Release, val manual: Boolean = false) : UpdateStep

    /**
     * Handed to Android's installer, which asks the player to confirm. [confirm] is its confirmation screen (an
     * `Intent`), waiting for the game to show it (see `UpdateController.takeConfirmation`); null once shown, or before
     * Android has asked.
     */
    data class Installing(val release: Release, val confirm: Any? = null) : UpdateStep

    /** It did not work out, for [problem]: RETRY or LATER. */
    data class Failed(val release: Release, val problem: UpdateProblem) : UpdateStep
}

/** Why an update did not work out. */
sealed interface UpdateProblem {
    /** Not enough free space: the update [needed] this many bytes, and there were [free]. */
    data class NoSpace(val needed: Long, val free: Long) : UpdateProblem

    /** The download could not be started or broke off. */
    data object Network : UpdateProblem

    /** The download is not the release's APK: the wrong size or hash, or another app. */
    data object BadDownload : UpdateProblem

    /** The APK could not be handed to Android's installer. */
    data object Installer : UpdateProblem

    /** Android's installer said no, with this `PackageInstaller.STATUS_...` [status]. */
    data class Install(val status: Int) : UpdateProblem
}

/** A step that failed for [problem]. */
class UpdateFailure(val problem: UpdateProblem) : IOException(problem.toString())
