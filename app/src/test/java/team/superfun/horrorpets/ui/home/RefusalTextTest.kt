package team.superfun.horrorpets.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test
import team.superfun.horrorpets.game.RefuseReason
import team.superfun.horrorpets.game.training.FIGHT_PREREQUISITE_NAMES
import team.superfun.horrorpets.game.training.gamesStillToTrain
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.PetState
import team.superfun.horrorpets.model.TrainingGame

/** What the killer says when FIGHT or TRAIN turns the player away. */
class RefusalTextTest {
    @Test
    fun hungryNamesTheCure() {
        assertEquals("Too hungry to fight. Feed me!", refusalText(RefuseReason.TOO_HUNGRY, fight = true, toTrain = emptyList()))
        assertEquals("Too hungry to train. Feed me!", refusalText(RefuseReason.TOO_HUNGRY, fight = false, toTrain = emptyList()))
    }

    @Test
    fun untrainedNamesOnlyTheGamesStillToPlay() {
        assertEquals("Train at Organ Drop first!", refusalText(RefuseReason.NEEDS_TRAINING, fight = true, toTrain = listOf(TrainingGame.ORGAN_DROP)))
        assertEquals(
            "Slasher Says and Organ Drop first!",
            refusalText(RefuseReason.NEEDS_TRAINING, fight = true, toTrain = listOf(TrainingGame.SLASH_SAYS, TrainingGame.ORGAN_DROP)),
        )
        assertEquals("Train more first!", refusalText(RefuseReason.NEEDS_TRAINING, fight = true, toTrain = emptyList()))
    }

    @Test
    fun theGamesStillToTrainAreThePrerequisitesNotYetFinished() {
        assertEquals(listOf(TrainingGame.SLASH_SAYS, TrainingGame.ORGAN_DROP), gamesStillToTrain(PetState(PetId.JASON)))
        assertEquals(listOf(TrainingGame.ORGAN_DROP), gamesStillToTrain(PetState(PetId.JASON, trainedGames = setOf(TrainingGame.SLASH_SAYS.name))))
        assertEquals(emptyList<TrainingGame>(), gamesStillToTrain(PetState(PetId.JASON, trainedGames = FIGHT_PREREQUISITE_NAMES)))
    }
}
