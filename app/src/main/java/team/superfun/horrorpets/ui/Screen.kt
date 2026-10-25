package team.superfun.horrorpets.ui

import team.superfun.horrorpets.assets.Anim
import team.superfun.horrorpets.fight.FightEvent
import team.superfun.horrorpets.fight.FightReward
import team.superfun.horrorpets.fight.FightState
import team.superfun.horrorpets.fight.Move
import team.superfun.horrorpets.game.RefuseReason
import team.superfun.horrorpets.game.pantry.Reaction
import team.superfun.horrorpets.model.GameState
import team.superfun.horrorpets.model.PantryItem
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.PlayGag
import team.superfun.horrorpets.model.TrainingGame

/**
 * Why the spotlight carousel is open: picking the starter of a new game ([ONBOARD], any of the killers), or an
 * opponent to fight ([OPPONENT], every killer but the selected one, locked ones included: beating one unlocks it).
 */
enum class SwitcherMode { ONBOARD, OPPONENT }

/** Where the PvP lobby is. */
sealed interface LobbyPhase {
    /** Host or join; [problem] says why the last try did not work out. */
    data class Menu(val problem: PvpProblem? = null) : LobbyPhase

    /**
     * Opening a room ([code] null until the relay answers), then waiting in it for a challenger. A room a tap opened
     * ([fromTap]) shows no code, only that the phones are connecting, or that they parted too soon ([tapAgain]).
     */
    data class Hosting(val code: String? = null, val fromTap: Boolean = false, val tapAgain: Boolean = false) : LobbyPhase

    /**
     * Typing a friend's code: the [digits] so far, [busy] while joining, and why the last try failed. A join a tap
     * started ([fromTap]) shows no code, only that the phones are connecting.
     */
    data class Joining(
        val digits: String = "",
        val busy: Boolean = false,
        val problem: PvpProblem? = null,
        val fromTap: Boolean = false,
    ) : LobbyPhase

    /**
     * Claimed by a friend's phone in a tap, which is opening a room for both: connecting. [again] once the phones
     * parted too soon (or the try failed, [problem]): tap again, which this phone now reads for.
     */
    data class Tapped(val again: Boolean = false, val problem: PvpProblem? = null) : LobbyPhase
}

/** Why PvP did not work out, as the lobby explains it. */
enum class PvpProblem {
    /** The relay could not be reached. */
    OFFLINE,

    /** No open room has that code. */
    NO_ROOM,

    /** Someone else got into the room first. */
    FULL,

    /** The other player runs another version of the game. */
    VERSION,

    /** The connection dropped in the middle of a fight. */
    LOST,
}

/** A PvP fight's link to the other player, for the fight screen: this player is [waiting] for the other to lock in, who [opponentIn] already has. */
data class PvpView(val waiting: Boolean = false, val opponentIn: Boolean = false)

/**
 * What a finished training game paid, for its results card: the [score], the pet's [best] in that game (which
 * [newBest] says this score just set), the [strength] and [xp] the training earned, and the [levelsGained].
 */
data class TrainingResult(
    val score: Int,
    val best: Int,
    val newBest: Boolean,
    val strength: Double,
    val xp: Int,
    val levelsGained: Int,
)

/**
 * A cook the pantry has just made, for its screen to show: [inputs] (in the order they went on the board) came together
 * into [result], which now stands in the shelf at [at]. [token] is new for every cook, so a screen that keys its show
 * on it plays each one once, even when the same dish is cooked twice in a row.
 */
data class CookShow(val result: PantryItem, val inputs: List<PantryItem>, val at: Int, val token: Long)

/**
 * What the killer made of the meal it was just fed, for the pantry's screen to show: the [item] it ate, how it took it
 * ([reaction], and the [line] it says), the [xp] the meal paid and the [levelsGained] that carried it past, and how far
 * it moved [strength], [funLevel] and the [mood] bonus (as clamped; negative for a loss). All of it is already paid
 * and saved when this is set.
 */
data class FedShow(
    val item: PantryItem,
    val reaction: Reaction,
    val line: String,
    val xp: Int,
    val levelsGained: Int,
    val strength: Double = 0.0,
    val funLevel: Double = 0.0,
    val mood: Double = 0.0,
    /** The killer's hunger before and after the meal, for the reaction to read out what it took off. */
    val hungerBefore: Double = 0.0,
    val hungerAfter: Double = 0.0,
)

/** Which screen the app is showing. The view model owns the transitions; the screens only render. */
sealed interface Screen {
    /** The save is still being read. */
    data object Loading : Screen

    /** The title screen, on every launch: the cover, the logo and PRESS START. */
    data object Title : Screen

    data class Switcher(val mode: SwitcherMode) : Screen

    /** The one-time "R.I.P. -> GOTCHA -> NEVER DIES" reveal for [pet]. */
    data class FakeDeath(val pet: PetId) : Screen

    data object Home : Screen

    /**
     * "HERE COMES A NEW CHALLENGER!": the secret killer [pet] bursts in, on the FIGHT that finds every other killer in
     * the stable (see `GameState.challengerDue`). He is revealed, and saved so, as it starts, so it plays once. It ends
     * by itself, or OK (or Back) skips it, with `onChallengerDone(session)`: on to the VS card against him.
     *
     * [session] tells one entrance from another (a new number each time), as a training game's does, so that the end
     * of one can never act on any other screen.
     */
    data class Challenger(val pet: PetId, val session: Long) : Screen

    /** The VS card before a fight with [opponent]: the CPU's, or, in [pvp], the other player's killer. */
    data class Versus(val opponent: PetId, val pvp: Boolean = false) : Screen

    /** PvP: open a room and share its 4-digit code, or join one by its code (or, on a phone, by a tap). */
    data class PvpLobby(val phase: LobbyPhase) : Screen

    /** [pet] has unlocked [attack] (an attack's id) by its level: learn it into its slot, or keep the one it has. */
    data class Learn(val pet: PetId, val attack: String) : Screen

    /** The selected pet's attacks, slot by slot: equip any it has unlocked. */
    data object Moves : Screen

    /**
     * TRAIN: the selected pet plays [game], picked from [options] (the games on offer, which the reel shows), against
     * its stable [mate] when the game uses one (else null). [result] is null while the reel spins and the game plays,
     * and set the moment the game has paid, until the player carries on Home.
     *
     * [session] tells one TRAIN from the next (a new number each time): the screen plays each session from its reel,
     * and what it reports (the score, Back, OK on the card) names its session, so that nothing of one can ever act on
     * another, even when a new session follows the last one's card within a frame (as the debug puppet's `train` does).
     *
     * [again] is TRY AGAIN from the last session's card: the same game once more, with no reel to spin (the card has
     * already said what it is), a GET READY beat instead.
     */
    data class Training(
        val game: TrainingGame,
        val mate: PetId?,
        val options: List<TrainingGame>,
        val session: Long,
        val result: TrainingResult? = null,
        val again: Boolean = false,
    ) : Screen

    /**
     * FEED: the pantry. The shelf is [GameState.pantry] (shared by the whole stable and saved with the game); what the
     * player does here is a visit of its own, and everything it reports names its [session], as a training game's does
     * (a new number for every FEED), so that a late press from one visit can never act on the next.
     *
     * - [board] is the shelf indices on the board, at most two, in the order they were put there. It is the visit's
     *   alone: it is never saved, and leaving puts everything back as it was.
     * - [cook] is the last cook of the visit, for the screen to show. It stays until the next cook replaces it.
     * - [refusedCook] and [tooFull] are tokens that change on every refusal (a botch with anything but another botch
     *   can not be cooked, a killer too full to eat can not be fed), 0 until the first one, so the screen reacts to
     *   each.
     * - [fed] is set once the killer has eaten, and ends the visit's doing: from then on only leaving, or FEED again
     *   (a new visit, while the killer could eat more), does anything.
     */
    data class Pantry(
        val session: Long,
        val board: List<Int> = emptyList(),
        val cook: CookShow? = null,
        val refusedCook: Long = 0,
        val tooFull: Long = 0,
        val fed: FedShow? = null,
    ) : Screen

    /**
     * [lastEvents] are the events of the turn just resolved, in the order to animate them (empty before
     * turn 1, and after a RUN). The turn's result is already in [fight]: when `fight.winner` is set the
     * screen still plays the turn out and reports its `PetAnimRequest` finished, and only then does
     * the view model move on to [Results].
     *
     * [playerMove] and [cpuMove] are the two moves just played, for the "both moves pop up" callouts. Both are
     * null before turn 1; [cpuMove] is also null after a RUN, because the CPU never played.
     *
     * [intense] is set once a turn that leaves either fighter nearly done (see `nearlyDone`) has played out, and stays
     * set for the rest of the fight, a potion or not: the music hardens with it. The turn that ends the fight never
     * sets it.
     */
    data class Fight(
        val fight: FightState,
        val lastEvents: List<FightEvent>,
        val playerMove: Move? = null,
        val cpuMove: Move? = null,
        val pvp: PvpView? = null,
        val intense: Boolean = false,
    ) : Screen

    /** A fight's results: what the pet earned ([reward]), and the [fight] as it ended, for its stats (null when not known). */
    data class Results(val reward: FightReward, val won: Boolean, val ran: Boolean, val fight: FightState? = null) : Screen

    /** The player just beat [pet], a killer they did not have, and it joined their stable; [results] come next. */
    data class Unlocked(val pet: PetId, val results: Results) : Screen
}

/**
 * Whether this screen makes the sounds for its own key presses, so `MainActivity` leaves out its `ui_move` and
 * `ui_select` ticks: a training game does, from the reel to the last move (its results card ticks like any other), and
 * the pantry does all through the visit.
 */
val Screen.ownsKeySounds: Boolean get() = (this is Screen.Training && result == null) || this is Screen.Pantry

/**
 * Ask the UI to play [anim] on the selected pet. The UI calls `onAnimFinished(token)` when it ends.
 * Every request has a fresh [token], even when two in a row play the same [anim] (a refusal's FAIL after another),
 * so the UI must key on the token, not the anim.
 *
 * A play's first request carries the [gag] the player's glove does to the pet first, and [anim] (its happy reaction)
 * waits for that.
 */
data class PetAnimRequest(val anim: Anim, val token: Long, val gag: PlayGag? = null)

/**
 * Everything the screens render.
 *
 * - [busy] is true from the start of an action until its last animation finishes.
 * - [petAnim] is the animation to play now, or null when the pet is idle.
 * - [toast] is the welcome-back message; the UI clears it with `consumeToast()`.
 * - [refuse] is the reason the last care/fight request was turned down. It stays set until the next
 *   action starts or the screen changes, so key a timed chip on [petAnim]'s token as well when the
 *   same reason can repeat.
 * - [sfx] and [voice] are queues of audio file base names; the UI plays them and calls `consumeSounds()`.
 * - [stableOpen] is whether the stable menu is up over Home (Home takes no other input meanwhile).
 */
data class UiState(
    val screen: Screen = Screen.Loading,
    val game: GameState = GameState(),
    val busy: Boolean = false,
    val petAnim: PetAnimRequest? = null,
    val toast: String? = null,
    val refuse: RefuseReason? = null,
    val sfx: List<String> = emptyList(),
    val voice: List<String> = emptyList(),
    val stableOpen: Boolean = false,
)
