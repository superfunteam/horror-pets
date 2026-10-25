package team.superfun.horrorpets.puppet

import android.content.Intent
import androidx.activity.ComponentActivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /**
     * Whether the fight screen shows its HUD: the bars and meters, the turn, the status chips, the command and help
     * windows. Always true but in a debug build told `hud off`, for clean footage.
     */
    val fightHud: StateFlow<Boolean> get() = HUD_SHOWN

    /**
     * The versionName the update check takes this build to be, or null for its real one. Only a debug build told
     * `fake_version` has one, so a dev build (which never updates) can be offered a release.
     */
    val fakeVersion: String? get() = null

    /**
     * The hour of the day (0..23) the game takes it to be, or null for the clock's own. Only a debug build told `hour`
     * has one, so Home can be seen at any hour without waiting for it.
     */
    val fakeHour: Int? get() = null

    /** Update checks to run now (`check_update`), even after the launch's own check or a LATER. */
    val updateChecks: Flow<Unit> get() = emptyFlow()

    companion object {
        /** The installed puppet: a do-nothing one unless a debug build has put its own here. */
        @Volatile
        var current: Puppet = object : Puppet {}
    }
}

/** The do-nothing puppet's [Puppet.fightHud]: shown, for good. */
private val HUD_SHOWN: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
