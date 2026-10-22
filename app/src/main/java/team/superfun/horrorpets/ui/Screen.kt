package team.superfun.horrorpets.ui

import team.superfun.horrorpets.assets.Anim
import team.superfun.horrorpets.fight.FightEvent
import team.superfun.horrorpets.fight.FightReward
import team.superfun.horrorpets.fight.FightState
import team.superfun.horrorpets.fight.Move
import team.superfun.horrorpets.game.RefuseReason
import team.superfun.horrorpets.model.Food
import team.superfun.horrorpets.model.GameState
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.PlayGag

/**
 * Why the spotlight carousel is open: picking the starter of a new game ([ONBOARD], any of the killers), or an
 * opponent to fight ([OPPONENT], every killer but the selected one, locked ones included: beating one unlocks it).
 */
enum class SwitcherMode { ONBOARD, OPPONENT }

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

    data class Versus(val opponent: PetId) : Screen

    /**
     * [lastEvents] are the events of the turn just resolved, in the order to animate them (empty before
     * turn 1, and after a RUN). The turn's result is already in [fight]: when `fight.winner` is set the
     * screen still plays the turn out and reports its `PetAnimRequest` finished, and only then does
     * the view model move on to [Results].
     *
     * [playerMove] and [cpuMove] are the two moves just played, for the "both moves pop up" callouts, and
     * [cpuTell] is what the CPU showed before it (a feint when it differs from [cpuMove]). All are null before
     * turn 1; [cpuMove] and [cpuTell] are also null after a RUN, because the CPU never played. The tell for the
     * coming turn is `fight.cpuPlan`.
     */
    data class Fight(
        val fight: FightState,
        val lastEvents: List<FightEvent>,
        val playerMove: Move? = null,
        val cpuMove: Move? = null,
        val cpuTell: Move? = null,
    ) : Screen

    data class Results(val reward: FightReward, val won: Boolean, val ran: Boolean) : Screen

    /** The player just beat [pet], a killer they did not have, and it joined their stable; [results] come next. */
    data class Unlocked(val pet: PetId, val results: Results) : Screen
}

/**
 * Ask the UI to play [anim] on the selected pet. The UI calls `onAnimFinished(token)` when it ends.
 * Every request has a fresh [token], even when two in a row play the same [anim] (TRAIN plays ATTACK
 * twice), so the UI must key on the token, not the anim.
 *
 * A feed's first request carries the [food] to drop: the UI drops it in beside the pet and only then
 * plays [anim], the first of the feed's munches. A play's first request carries the [gag] the player's
 * glove does to the pet first, and [anim] (its happy reaction) waits for that the same way.
 */
data class PetAnimRequest(val anim: Anim, val token: Long, val food: Food? = null, val gag: PlayGag? = null)

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
