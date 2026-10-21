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
import kotlinx.coroutines.launch
import team.superfun.horrorpets.assets.AssetLoader
import team.superfun.horrorpets.assets.feedAnim
import team.superfun.horrorpets.audio.LogSoundSink
import team.superfun.horrorpets.data.OffsetClock
import team.superfun.horrorpets.data.SaveRepository
import team.superfun.horrorpets.data.WallClock
import team.superfun.horrorpets.ui.GameViewModel
import team.superfun.horrorpets.ui.HorrorPetsApp
import team.superfun.horrorpets.ui.Screen
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()
        applyDebugTimeTravel(intent)
        vm.start()

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
                HorrorPetsApp(vm = vm, loader = loader, sounds = LogSoundSink, onExit = ::finish)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (applyDebugTimeTravel(intent)) vm.onResume()
    }

    /**
     * Remote keys the screens don't own:
     * - held OK / Enter / Back repeat: dropped everywhere, so a held button acts once and can never
     *   cascade through several screens;
     * - on Home, Menu opens the pet switcher and Rewind / Fast-forward walk to the neighbouring scene.
     *
     * This has to run before the view hierarchy (Compose) sees the key, hence `dispatchKeyEvent`. It is
     * the public `android.app.Activity` method; lint's RestrictedApi hit comes from androidx.core's
     * `ComponentActivity` re-declaring it with `@RestrictTo`, which does not apply to overriding it.
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.repeatCount > 0 && event.keyCode in NO_REPEAT_KEYS) return true
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /**
     * Debug builds only: `--ef advance_hours <h>` on the launch intent shifts the game clock [h] hours
     * into the future, so decay and the fake death can be tried without waiting. Returns whether it applied.
     */
    private fun applyDebugTimeTravel(intent: Intent?): Boolean {
        if (!BuildConfig.DEBUG || intent?.hasExtra(EXTRA_ADVANCE_HOURS) != true) return false
        val hours = intent.getFloatExtra(EXTRA_ADVANCE_HOURS, 0f)
        offsetClock.offsetMs = (hours.toDouble() * MS_PER_HOUR).toLong()
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
        val HOME_SHORTCUT_KEYS = setOf(
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        )

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
