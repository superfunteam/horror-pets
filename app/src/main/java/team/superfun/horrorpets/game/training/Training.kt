package team.superfun.horrorpets.game.training

import kotlin.math.roundToInt
import kotlin.random.Random
import team.superfun.horrorpets.game.Tuning
import team.superfun.horrorpets.game.addXp
import team.superfun.horrorpets.game.clampStat
import team.superfun.horrorpets.model.GameState
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.PetState
import team.superfun.horrorpets.model.TrainingGame

/**
 * What the training games take as input: the arrows and OK on a TV remote or keyboard. The UI maps real keys onto
 * these (a phone has no keys; its games read touches), so the games' rules never see an Android key code.
 */
enum class TrainingKey { UP, DOWN, LEFT, RIGHT, OK }

/** The games TRAIN may open, in enum order: the ones that need a stable mate only when the stable has one ([hasMate]). */
fun eligibleGames(hasMate: Boolean): List<TrainingGame> = TrainingGame.entries.filter { hasMate || !it.needsMate }

/**
 * A random game from [eligible] (never empty), other than [last] whenever there is another choice, so TRAIN never
 * opens the same game twice running unless it is the only one on offer.
 */
fun pickTrainingGame(eligible: List<TrainingGame>, last: TrainingGame?, rng: Random): TrainingGame {
    require(eligible.isNotEmpty()) { "no training game to pick from" }
    return eligible.filter { it != last }.ifEmpty { eligible }.random(rng)
}

/** A random killer in the stable other than the selected one, for the games that use a mate; null when there is none. */
fun pickMate(game: GameState, rng: Random): PetId? =
    PetId.entries.filter { it in game.pets && it != game.selected }.randomOrNull(rng)

/**
 * How much a score multiplies today's TRAIN reward: 1x at 0, rising evenly to 3x at the game's par and no further.
 * A negative score counts as 0.
 */
fun trainingMultiplier(game: TrainingGame, score: Int): Double =
    1.0 + 2.0 * score.coerceIn(0, game.par) / game.par

/** This pet's best score in [game], or 0 when it has not scored one. */
fun PetState.bestAt(game: TrainingGame): Int = trainingBest[game.name] ?: 0

/**
 * What a finished game paid: the trained [pet], the [levelsGained], the [strengthGained] (the actual change, so
 * less than the reward at the stat cap), the [xpGained] before level carry, and the pet's [best] in the game, which
 * [newBest] says this score just set.
 */
data class TrainingOutcome(
    val pet: PetState,
    val levelsGained: Int,
    val strengthGained: Double,
    val xpGained: Int,
    val best: Int,
    val newBest: Boolean,
)

/**
 * Pays out a finished training game. Hunger and fun change as [team.superfun.horrorpets.game.perform] does for TRAIN,
 * strength by [Tuning.TRAIN_STRENGTH] and xp by [Tuning.XP_TRAIN] times [trainingMultiplier], so a score of 0 pays
 * exactly today's TRAIN. A negative score counts as 0, and a score that does not beat the pet's best leaves it alone.
 * It does not check hunger: a starving pet is turned away before a game starts.
 */
fun applyTraining(pet: PetState, game: TrainingGame, score: Int): TrainingOutcome {
    val s = score.coerceAtLeast(0)
    val m = trainingMultiplier(game, s)
    val trained = pet.copy(
        strength = clampStat(pet.strength + Tuning.TRAIN_STRENGTH * m),
        hunger = clampStat(pet.hunger + Tuning.TRAIN_HUNGER),
        funLevel = clampStat(pet.funLevel + Tuning.TRAIN_FUN),
    )
    val xp = (Tuning.XP_TRAIN * m).roundToInt()
    val leveled = addXp(trained, xp)
    val previous = pet.bestAt(game)
    val newBest = s > previous
    return TrainingOutcome(
        pet = if (newBest) leveled.pet.copy(trainingBest = pet.trainingBest + (game.name to s)) else leveled.pet,
        levelsGained = leveled.levelsGained,
        strengthGained = trained.strength - pet.strength,
        xpGained = xp,
        best = if (newBest) s else previous,
        newBest = newBest,
    )
}
