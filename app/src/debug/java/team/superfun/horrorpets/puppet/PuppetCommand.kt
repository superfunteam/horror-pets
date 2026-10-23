package team.superfun.horrorpets.puppet

import team.superfun.horrorpets.DEV_VERSION_NAME
import team.superfun.horrorpets.fight.Move
import team.superfun.horrorpets.game.CareAction
import team.superfun.horrorpets.game.Tuning
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.TrainingGame
import team.superfun.horrorpets.versionCodeOf

/** One puppet command, as [parseCommand] reads it from adb (see "Puppet mode" in the README). */
sealed interface PuppetCommand {
    /** `unlock_all`: every killer joins the stable. */
    data object UnlockAll : PuppetCommand

    /** `pet <killer>`: [pet] becomes the active pet, joining the stable first if it is not in it, and Home comes up. */
    data class Pet(val pet: PetId) : PuppetCommand

    /** `level <n>`: the active pet is at [level], and Home offers what that unlocks, one learn screen at a time. */
    data class Level(val level: Int) : PuppetCommand

    /** `learn_all`: the active pet learns everything its level unlocks and equips the best of it, with no learn screens. */
    data object LearnAll : PuppetCommand

    /** `fight <killer>`: straight into a CPU fight against [opponent], through the VS card. */
    data class Fight(val opponent: PetId) : PuppetCommand

    /** `autoplay on|off`: fights pick the player's moves, and the cards after them continue, by themselves. */
    data class Autoplay(val on: Boolean) : PuppetCommand

    /** `hud on|off`: fights show, or hide, their HUD (the bars, meters, turn, chips, command and help windows). */
    data class Hud(val on: Boolean) : PuppetCommand

    /** `move <move>`: picks [move] now in the current fight. */
    data class Pick(val move: Move) : PuppetCommand

    /** `meter full`: fills the player's special meter in the current CPU fight. */
    data object MeterFull : PuppetCommand

    /**
     * `feed`, `play` or `train`: that care [action] on Home (going Home first). `train <game>` opens training on
     * [game] (when the stable has what it needs), else on the game the pick makes.
     */
    data class Care(val action: CareAction, val game: TrainingGame? = null) : PuppetCommand

    /** `score <n>`: the training game on screen ends now with [score], which pays and shows its results card. */
    data class Score(val score: Int) : PuppetCommand

    /** `home`: back to Home from wherever the game is, but a fight. */
    data object Home : PuppetCommand

    /** `wait <seconds>`: holds the commands after it for [ms]. */
    data class Wait(val ms: Long) : PuppetCommand

    /** `wait home`: holds the commands after it until Home is on screen and idle, such as after a fight and its cards. */
    data object WaitHome : PuppetCommand

    /** `status`: logs where the game is. */
    data object Status : PuppetCommand

    /** `fake_version <x.y.z>|off`: the update check takes this build to be [version] (null: its real one). */
    data class FakeVersion(val version: String?) : PuppetCommand

    /** `check_update`: checks GitHub for a newer release now, and offers it over the title. */
    data object CheckUpdate : PuppetCommand

    /** [text] is not a command the puppet knows, for [reason]. */
    data class Invalid(val text: String, val reason: String) : PuppetCommand
}

/** The longest `wait <seconds>`. */
const val MAX_WAIT_SECONDS = 600

/** Every command's syntax, for the log when one is not understood. */
const val PUPPET_USAGE = "unlock_all, pet <killer>, level <n>, learn_all, fight <killer>, autoplay on|off, hud on|off, " +
    "move <strike|heavy|block|special|potion|run>, meter full, feed, play, train [<game>], score <n>, home, " +
    "wait <seconds>|home, status, fake_version <x.y.z>|off, check_update"

private val KILLERS = PetId.entries.joinToString(", ") { it.name.lowercase() }
private val TRAINING_GAMES = TrainingGame.entries.joinToString(", ") { it.name.lowercase() }

/** A line of commands split into one per command: they are separated by `;` or new lines, and blanks are dropped. */
fun splitCommands(line: String): List<String> = line.split(';', '\n').map { it.trim() }.filter { it.isNotEmpty() }

/**
 * Reads one command (see [PuppetCommand] for each one's syntax). Case and extra spaces do not matter; anything else
 * that is off (an unknown command, a missing or extra argument, a killer or move that does not exist) is
 * [PuppetCommand.Invalid], saying why.
 */
fun parseCommand(text: String): PuppetCommand {
    val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val name = words.firstOrNull()?.lowercase() ?: return PuppetCommand.Invalid(text, "empty command")
    val args = words.drop(1).map { it.lowercase() }
    val arg = args.singleOrNull()
    fun invalid(reason: String) = PuppetCommand.Invalid(text.trim(), reason)
    fun bare(command: PuppetCommand) = if (args.isEmpty()) command else invalid("$name takes no argument")

    return when (name) {
        "unlock_all" -> bare(PuppetCommand.UnlockAll)
        "learn_all" -> bare(PuppetCommand.LearnAll)
        "feed" -> bare(PuppetCommand.Care(CareAction.FEED))
        "play" -> bare(PuppetCommand.Care(CareAction.PLAY))
        "train" -> {
            if (args.isEmpty()) return PuppetCommand.Care(CareAction.TRAIN)
            val game = arg?.let(::trainingGameNamed) ?: return invalid("train takes no argument or one game: $TRAINING_GAMES")
            PuppetCommand.Care(CareAction.TRAIN, game)
        }
        "score" -> {
            val score = arg?.toIntOrNull()?.takeIf { it >= 0 }
            if (score == null) invalid("score needs a whole number, 0 or more") else PuppetCommand.Score(score)
        }
        "home" -> bare(PuppetCommand.Home)
        "status" -> bare(PuppetCommand.Status)
        "check_update" -> bare(PuppetCommand.CheckUpdate)
        "fake_version" -> when {
            arg == "off" -> PuppetCommand.FakeVersion(null)
            arg != null && arg != DEV_VERSION_NAME && runCatching { versionCodeOf(arg) }.isSuccess -> PuppetCommand.FakeVersion(arg)
            else -> invalid("fake_version needs off or a release's MAJOR.MINOR.PATCH, such as 0.5.0")
        }
        "pet", "fight" -> {
            val pet = arg?.let(::killerNamed) ?: return invalid("$name needs one killer: $KILLERS")
            if (name == "pet") PuppetCommand.Pet(pet) else PuppetCommand.Fight(pet)
        }
        "level" -> {
            val level = arg?.toIntOrNull()?.takeIf { it in 1..Tuning.MAX_LEVEL }
            if (level == null) invalid("level needs a whole number from 1 to ${Tuning.MAX_LEVEL}") else PuppetCommand.Level(level)
        }
        "autoplay" -> when (arg) {
            "on" -> PuppetCommand.Autoplay(true)
            "off" -> PuppetCommand.Autoplay(false)
            else -> invalid("autoplay needs on or off")
        }
        "hud" -> when (arg) {
            "on" -> PuppetCommand.Hud(true)
            "off" -> PuppetCommand.Hud(false)
            else -> invalid("hud needs on or off")
        }
        "move" -> {
            val move = Move.entries.firstOrNull { it.name.lowercase() == arg }
            if (move == null) invalid("move needs one of ${Move.entries.joinToString(", ") { it.name.lowercase() }}") else PuppetCommand.Pick(move)
        }
        "meter" -> if (arg == "full") PuppetCommand.MeterFull else invalid("meter only takes full")
        "wait" -> {
            if (arg == "home") return PuppetCommand.WaitHome
            val seconds = arg?.toDoubleOrNull()?.takeIf { it > 0 && it <= MAX_WAIT_SECONDS }
            if (seconds == null) invalid("wait needs home or a number of seconds up to $MAX_WAIT_SECONDS") else PuppetCommand.Wait((seconds * 1000).toLong())
        }
        else -> invalid("unknown command; the commands are $PUPPET_USAGE")
    }
}

/** The killer called [name] (its id, in any case), if there is one. */
private fun killerNamed(name: String): PetId? = PetId.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

/** The training game called [name] (its id, in any case), if there is one. */
private fun trainingGameNamed(name: String): TrainingGame? = TrainingGame.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
