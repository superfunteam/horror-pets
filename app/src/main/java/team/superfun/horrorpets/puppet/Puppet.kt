package team.superfun.horrorpets.puppet

import android.content.Intent
import androidx.activity.ComponentActivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import team.superfun.horrorpets.fight.Move
import team.superfun.horrorpets.ui.GameViewModel

/**
 * The seam for the developer-only puppet: adb commands that drive the game, so marketing footage can be recorded
 * without playing by hand (see "Puppet mode" in the README). Debug builds install one at startup (app/src/debug); a
 * release build has no puppet code at all, so [current] stays the do-nothing default there and every puppet extra is
 * ignored.
 */
interface Puppet {
    /** [activity] is created and its view model [vm] started: listen for commands until it is destroyed. */
    fun attach(activity: ComponentActivity, vm: GameViewModel) {}

    /** Runs the commands [intent] carries as extras. Call it for a launch, not for an Activity recreated with the same intent. */
    fun onIntent(intent: Intent) {}

    /** Moves for the fight screen to pick for the player, the cursor going to each first as a remote's would. */
    val fightPicks: Flow<Move> get() = emptyFlow()

    companion object {
        /** The installed puppet: a do-nothing one unless a debug build has put its own here. */
        @Volatile
        var current: Puppet = object : Puppet {}
    }
}
