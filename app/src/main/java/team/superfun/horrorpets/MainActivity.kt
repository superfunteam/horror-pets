package team.superfun.horrorpets

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import team.superfun.horrorpets.assets.AssetLoader
import team.superfun.horrorpets.assets.feedAnim
import team.superfun.horrorpets.audio.GameAudio
import team.superfun.horrorpets.data.OffsetClock
import team.superfun.horrorpets.data.SaveRepository
import team.superfun.horrorpets.data.WallClock
import team.superfun.horrorpets.ui.GameViewModel
import team.superfun.horrorpets.ui.HorrorPetsApp
import team.superfun.horrorpets.ui.Screen
import team.superfun.horrorpets.ui.UiState
import team.superfun.horrorpets.ui.theme.HorrorPetsTheme

class MainActivity : ComponentActivity() {
    private val loader: AssetLoader by lazy { assetLoader(this) }

    private val vm: GameViewModel by viewModels {
        // Locals, so the view model's lambdas hold the loader and the app context, never this Activity.
        val loader = loader
        val appContext = applicationContext
        viewModelFactory {
            initializer {
                GameViewModel(
                    store = SaveRepository(appContext),
                    clock = offsetClock,
                    rng = Random.Default,
                    scope = null, // viewModelScope: main-confined, as the view model requires
                    feedAnim = { loader.manifest(it).feedAnim() },
                    onError = { Log.e(TAG, "Save or load failed; carrying on from memory", it) },
                )
            }
        }
    }

    /** One per Activity instance: music, effects and voice. Released in [onDestroy]. */
    private lateinit var audio: GameAudio

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()
        audio = GameAudio(this)
        applyDebugTimeTravel(intent)
        vm.start()

        // The music follows the screen. Collecting only while started means a stopped game stays quiet;
        // GameAudio ignores a track it is already playing, so coming back never restarts it.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.map { musicTrackFor(it) }.distinctUntilChanged().collect { audio.music(it) }
            }
        }

        // A TV left on keeps getting hungrier: tick once a minute while the game is in front.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(TICK_MS)
                    vm.tick()
                }
            }
        }

        setContent {
            HorrorPetsTheme {
                HorrorPetsApp(vm = vm, loader = loader, sounds = audio, onExit = ::finish)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        audio.resume()
        vm.onResume()
    }

    override fun onPause() {
        audio.pause()
        super.onPause()
    }

    override fun onStop() {
        audio.pause()
        super.onStop()
    }

    override fun onDestroy() {
        // Also reached after a config change, where the new Activity builds its own GameAudio.
        if (::audio.isInitialized) audio.release()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // No vm.onResume() here: an Activity is always paused before it gets a new intent, so the real
        // onResume() follows and picks up the new clock offset.
        applyDebugTimeTravel(intent)
    }

    /**
     * Remote keys the screens don't own:
     * - held OK / Enter / Back repeat: dropped everywhere, so a held button acts once and can never
     *   cascade through several screens;
     * - on Home, Menu opens the pet switcher and Rewind / Fast-forward walk to the neighbouring scene;
     * - the UI tick sounds: a D-pad arrow plays `ui_move` (except in the pet switcher, which times its own),
     *   OK / Enter plays `ui_select` and Back plays `ui_back`. Only a key's first down counts, not its repeats.
     *
     * This has to run before the view hierarchy (Compose) sees the key, hence `dispatchKeyEvent`. It is
     * the public `android.app.Activity` method; lint's RestrictedApi hit comes from androidx.core's
     * `ComponentActivity` re-declaring it with `@RestrictTo`, which does not apply to overriding it.
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.repeatCount > 0 && event.keyCode in NO_REPEAT_KEYS) return true
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) playKeySound(event.keyCode)
        if (event.keyCode in HOME_SHORTCUT_KEYS && vm.state.value.screen == Screen.Home) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_MENU -> vm.openSwitcher()
                    KeyEvent.KEYCODE_MEDIA_REWIND -> vm.onWalk(-1)
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> vm.onWalk(1)
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // Lint's GestureBackNavigation is about the touch back gesture, which sends no key event on Android 16+;
    // a TV remote's Back button is still a real KEYCODE_BACK, and this only plays a tick, it doesn't navigate.
    @SuppressLint("GestureBackNavigation")
    private fun playKeySound(keyCode: Int) {
        when (keyCode) {
            in MOVE_KEYS -> if (vm.state.value.screen !is Screen.Switcher) audio.sfx("ui_move")
            in SELECT_KEYS -> audio.sfx("ui_select")
            KeyEvent.KEYCODE_BACK -> audio.sfx("ui_back")
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /**
     * Debug builds only: an `advance_hours` extra on the launch intent shifts the game clock that many
     * hours into the future, so decay and the fake death can be tried without waiting. Any number type
     * works (`--ef`, `--ed`, `--ei`, `--el`), and so does a numeric string (`--es`). Returns whether it applied.
     */
    private fun applyDebugTimeTravel(intent: Intent?): Boolean {
        if (!BuildConfig.DEBUG) return false
        val extras = intent?.extras ?: return false
        if (!extras.containsKey(EXTRA_ADVANCE_HOURS)) return false
        // The untyped getter is deprecated, but it is the only way to accept whichever type adb sent.
        @Suppress("DEPRECATION")
        val hours = when (val raw = extras.get(EXTRA_ADVANCE_HOURS)) {
            is Number -> raw.toDouble()
            is String -> raw.trim().toDoubleOrNull()
            else -> null
        }
        if (hours == null || !hours.isFinite()) {
            Log.w(TAG, "Debug time travel: ignoring non-numeric $EXTRA_ADVANCE_HOURS")
            return false
        }
        offsetClock.offsetMs = (hours * MS_PER_HOUR).toLong()
        Log.i(TAG, "Debug time travel: clock offset $hours h")
        return true
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private companion object {
        const val TAG = "HorrorPets"
        const val TICK_MS = 60_000L
        const val MS_PER_HOUR = 3_600_000.0
        const val EXTRA_ADVANCE_HOURS = "advance_hours"

        val NO_REPEAT_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BACK,
        )
        val MOVE_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
        )
        val SELECT_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
        )
        val HOME_SHORTCUT_KEYS = setOf(
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        )

        /** The music track for what is on screen, or null for silence. Names are the files in `audio/music/`. */
        fun musicTrackFor(state: UiState): String? = when (state.screen) {
            Screen.Loading, is Screen.FakeDeath -> null
            is Screen.Switcher -> "switcher"
            Screen.Home -> state.game.selected?.name?.lowercase()
            is Screen.Versus, is Screen.Fight, is Screen.Results -> "fight"
        }

        /**
         * One clock and one asset cache per process: the view model outlives an Activity (it is
         * retained across recreation), so both must be the same objects every Activity instance sees.
         */
        val offsetClock = OffsetClock(WallClock)

        @Volatile
        private var sharedLoader: AssetLoader? = null

        fun assetLoader(context: Context): AssetLoader =
            sharedLoader ?: synchronized(this) {
                sharedLoader ?: AssetLoader(context.applicationContext).also { sharedLoader = it }
            }
    }
}
