package team.superfun.horrorpets.ui.title

import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.update.Release
import team.superfun.horrorpets.update.UpdateProblem
import team.superfun.horrorpets.update.UpdateStep

private val V060 = Release("0.6.0", "https://github.com/superfunteam/horror-pets/releases/download/v0.6.0/horror-pets-0.6.0.apk", 101_210_643L)

class UpdateDialogTest {
    @Test
    fun theOfferSaysTheVersionTheSizeAndThatThePetsAreSafe() {
        assertEquals("UPDATE AVAILABLE", updateTitle(UpdateStep.Offer(V060)))
        assertEquals("Version 0.6.0 is out (97 MB). Your pets stay just as they are.", offerText(V060))
    }

    @Test
    fun everyStepHasATitle() {
        val steps = listOf(
            UpdateStep.Offer(V060),
            UpdateStep.Downloading(V060, 0),
            UpdateStep.Verifying(V060),
            UpdateStep.NeedsPermission(V060),
            UpdateStep.Installing(V060),
            UpdateStep.Failed(V060, UpdateProblem.Network),
        )
        for (step in steps) assertTrue("$step", updateTitle(step).isNotBlank())
    }

    @Test
    fun theFirstButtonHasTheFocusAndBackIsTheLast() {
        assertEquals(listOf(UpdateButton.UPDATE, UpdateButton.LATER), buttonsFor(UpdateStep.Offer(V060)))
        assertEquals(listOf(UpdateButton.CANCEL), buttonsFor(UpdateStep.Downloading(V060, 5)))
        assertEquals(listOf(UpdateButton.CANCEL), buttonsFor(UpdateStep.Verifying(V060)))
        assertEquals(listOf(UpdateButton.SETTINGS, UpdateButton.LATER), buttonsFor(UpdateStep.NeedsPermission(V060)))
        assertEquals(listOf(UpdateButton.LATER), buttonsFor(UpdateStep.NeedsPermission(V060, manual = true)))
        assertEquals(listOf(UpdateButton.LATER), buttonsFor(UpdateStep.Installing(V060)))
        assertEquals(listOf(UpdateButton.RETRY, UpdateButton.LATER), buttonsFor(UpdateStep.Failed(V060, UpdateProblem.Network)))
        assertEquals(emptyList<UpdateButton>(), buttonsFor(UpdateStep.None))
    }

    @Test
    fun aTapOffTheWindowNeverStopsADownload() {
        assertTrue(tapOffCloses(UpdateStep.Offer(V060)))
        assertTrue(tapOffCloses(UpdateStep.Failed(V060, UpdateProblem.Network)))
        assertTrue(tapOffCloses(UpdateStep.NeedsPermission(V060)))
        assertFalse(tapOffCloses(UpdateStep.Downloading(V060, 5)))
        assertFalse(tapOffCloses(UpdateStep.Verifying(V060)))
    }

    @Test
    fun progressIsMegabytesDoneOfTheTotal() {
        assertEquals("0 / 97 MB", progressText(0, V060.size))
        assertEquals("48 / 97 MB", progressText(V060.size / 2, V060.size))
        assertEquals("97 / 97 MB", progressText(V060.size, V060.size))
        assertEquals(0.5f, progressFraction(50, 100), 0.0001f)
        assertEquals(0f, progressFraction(5, 0), 0.0001f)
        assertEquals(1f, progressFraction(150, 100), 0.0001f)
    }

    @Test
    fun noSpaceSaysHowMuchIsNeededAndFree() {
        val text = problemText(UpdateProblem.NoSpace(needed = 230L * 1_048_576, free = 120L * 1_048_576))
        assertTrue(text, "230 MB" in text && "120 MB" in text)
    }

    @Test
    fun eachInstallerVerdictHasItsOwnShortReason() {
        val statuses = listOf(
            PackageInstaller.STATUS_FAILURE,
            PackageInstaller.STATUS_FAILURE_BLOCKED,
            PackageInstaller.STATUS_FAILURE_ABORTED,
            PackageInstaller.STATUS_FAILURE_INVALID,
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_STORAGE,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
        )
        val texts = statuses.map { problemText(UpdateProblem.Install(it)) }
        assertEquals("one reason each: $texts", statuses.size, texts.toSet().size)
        assertEquals("an unknown status reads as a plain failure", problemText(UpdateProblem.Install(PackageInstaller.STATUS_FAILURE)), problemText(UpdateProblem.Install(99)))
        for (problem in listOf(UpdateProblem.Network, UpdateProblem.BadDownload, UpdateProblem.Installer) + statuses.map { UpdateProblem.Install(it) }) {
            val text = problemText(problem)
            assertTrue("$problem: \"$text\" is short", text.isNotBlank() && text.length <= 70)
        }
    }
}
