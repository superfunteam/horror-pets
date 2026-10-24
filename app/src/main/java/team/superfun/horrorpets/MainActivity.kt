package team.superfun.horrorpets

import android.annotation.SuppressLint
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlin.math.max
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import team.superfun.horrorpets.assets.Anim
import team.superfun.horrorpets.assets.AssetLoader
import team.superfun.horrorpets.assets.has
import team.superfun.horrorpets.audio.GameAudio
import team.superfun.horrorpets.data.OffsetClock
import team.superfun.horrorpets.data.SaveRepository
import team.superfun.horrorpets.data.WallClock
import team.superfun.horrorpets.game.Tuning
import team.superfun.horrorpets.pvp.HttpPvpRelay
import team.superfun.horrorpets.pvp.nfc.TapCard
import team.superfun.horrorpets.pvp.nfc.TapEvents
import team.superfun.horrorpets.pvp.nfc.TapMode
import team.superfun.horrorpets.pvp.nfc.NfcTapReader
import team.superfun.horrorpets.puppet.Puppet
import team.superfun.horrorpets.ui.LobbyPhase
import team.superfun.horrorpets.ui.GameViewModel
import team.superfun.horrorpets.ui.HorrorPetsApp
import team.superfun.horrorpets.ui.Screen
import team.superfun.horrorpets.ui.UiState
import team.superfun.horrorpets.ui.ownsKeySounds
import team.superfun.horrorpets.ui.common.LocalTouchDevice
import team.superfun.horrorpets.ui.common.ProvideScreenDensity
import team.superfun.horrorpets.ui.common.isTouchDevice
import team.superfun.horrorpets.ui.common.isTouchInput
import team.superfun.horrorpets.ui.pvp.LocalCanTap
import team.superfun.horrorpets.ui.tapModeOf
import team.superfun.horrorpets.ui.tapReadyOf
import team.superfun.horrorpets.ui.theme.HorrorPetsTheme
import team.superfun.horrorpets.update.AndroidUpdateHost
import team.superfun.horrorpets.update.UPDATES_TAG
import team.superfun.horrorpets.update.UpdateController
import team.superfun.horrorpets.update.showInstallConfirmation

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
                    canDance = { loader.manifest(it).has(Anim.DANCE) },
                    onError = { Log.e(TAG, "Save or load failed; carrying on from memory", it) },
                    clockFloorMs = clockFloorMs(appContext),
                    pvpRelay = HttpPvpRelay(PVP_RELAY, appVersionCode(appContext)),
                    // A phone tapped against this one reads the room code this one is hosting (see TapCard).
                    onHostCode = { TapCard.shared.hosted = it },
                )
            }
        }
    }

    /** The in-app update: a check for a newer release on GitHub once per launch, and its window over the title. */
    private val updates: UpdateController by viewModels {
        val appContext = applicationContext
        viewModelFactory {
            initializer {
                UpdateController(
                    host = AndroidUpdateHost(appContext, userAgent = "horror-pets/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE})"),
                    // A debug build's puppet can fake this build's version (fake_version), so a dev build can try updates.
                    runningVersion = { Puppet.current.fakeVersion ?: BuildConfig.VERSION_NAME },
                    scope = null, // viewModelScope
                    log = { Log.i(UPDATES_TAG, it) },
                )
            }
        }
    }

    /** One per Activity instance: music, effects and voice. Released in [onDestroy]. */
    private lateinit var audio: GameAudio

    /** Whether this device takes touch (a phone or tablet, never a TV); see `isTouchDevice`. */
    private var touchDevice = false

    /** This phone's NFC for PvP taps; null without NFC (every TV). */
    private var tap: NfcTapReader? = null

    /** What a tap read off another phone means to the game. */
    private val tapEvents = object : TapEvents {
        override fun onCodeRead(code: String) = vm.onTapCode(code)

        override fun onReadyFound(): Boolean = vm.onTapFoundReady()

        override fun stillHosting(): Boolean = vm.tapHosting()
    }

    /** This phone read by another's tap: it holds still as a card while the other opens a room, then joins it. */
    private val cardListener = object : TapCard.Listener {
        override fun onFound() {
            tap?.holdAsCard()
            vm.onTappedAsCard()
        }

        override fun onHandedCode(code: String): Boolean {
            tap?.release()
            return vm.onTapHandedCode(code)
        }

        override fun onLost() {
            tap?.release()
            vm.onTapLost()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        touchDevice = detectTouchDevice(this)
        tap = NfcTapReader.create(this, tapEvents)
        TapCard.shared.listener = cardListener
        // On a phone the scene runs under the camera cutout too (Android 15 does this anyway); the UI keeps
        // clear of it through `safeInset`. A TV has no cutout.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        hideSystemBars()
        audio = GameAudio(this)
        applyDebugTimeTravel(intent)
        vm.start()
        Puppet.current.attach(this, vm)
        // A recreated Activity gets the launch's intent again, whose commands have already run.
        if (savedInstanceState == null) Puppet.current.onIntent(intent)

        // Look for a newer release as the app starts, so the answer is usually in by the time the title is up (once
        // per launch: the controller outlives a recreation). Its window only ever shows over the title.
        updates.checkOnce()
        // A debug build's puppet can ask for another check (check_update).
        lifecycleScope.launch {
            Puppet.current.updateChecks.collect { updates.checkNow() }
        }
        // Android's installer wants the player to confirm an update: its screen is shown from here, while the game is in
        // front, and only while the update window is still installing (not after LATER).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                updates.step.collect {
                    val confirm = updates.takeConfirmation() ?: return@collect
                    if (!showInstallConfirmation(this@MainActivity, confirm)) updates.confirmationFailed()
                }
            }
        }

        // The music follows the screen. Collecting only while started means a stopped game stays quiet;
        // GameAudio ignores a track it is already playing, so coming back never restarts it.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.map { musicTrackFor(it) }.distinctUntilChanged().collect { audio.music(it) }
            }
        }

        // Tapping phones together: where the game is says whether this phone reads, takes turns reading and being
        // read, or neither, and whether it tells a reader it is ready for a fight (see ui/TapFight.kt). Only while
        // the game is in front.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                try {
                    vm.state.map { tapModeOf(it) to tapReadyOf(it) }
                        .distinctUntilChanged()
                        .collect { (mode, ready) ->
                            TapCard.shared.ready = ready
                            tap?.setMode(mode)
                        }
                } finally {
                    TapCard.shared.ready = false
                    tap?.setMode(TapMode.OFF)
                }
            }
        }

        // While this phone shows a room code, a phone tapped against it reaches this app's card service.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                vm.state.map { ((it.screen as? Screen.PvpLobby)?.phase as? LobbyPhase.Hosting)?.code != null }
                    .distinctUntilChanged()
                    .collect { hosting -> runCatching { tap?.preferHosting(hosting) } }
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
            CompositionLocalProvider(LocalTouchDevice provides touchDevice, LocalCanTap provides (tap != null)) {
                ProvideScreenDensity {
                    HorrorPetsTheme {
                        val showFightHud by Puppet.current.fightHud.collectAsState()
                        val update by updates.step.collectAsState()
                        HorrorPetsApp(
                            vm = vm,
                            loader = loader,
                            sounds = audio,
                            onExit = ::finish,
                            scriptedPicks = Puppet.current.fightPicks,
                            showFightHud = showFightHud,
                            update = update,
                            updateActions = updates,
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        audio.resume()
        vm.onResume()
        tap?.onResume()
        // Back from allowing installs from this app, maybe: the update carries on.
        updates.onResume()
    }

    override fun onPause() {
        audio.pause()
        tap?.onPause()
        super.onPause()
    }

    override fun onStop() {
        audio.pause()
        super.onStop()
    }

    override fun onDestroy() {
        if (TapCard.shared.listener === cardListener) TapCard.shared.listener = null
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
        Puppet.current.onIntent(intent)
    }

    /**
     * Remote keys the screens don't own:
     * - held OK / Enter / Back repeat: dropped everywhere, so a held button acts once and can never
     *   cascade through several screens;
     * - on Home, Menu opens the pet switcher and Rewind / Fast-forward walk to the neighbouring scene;
     * - the UI tick sounds: a D-pad arrow plays `ui_move` (except in the pet switcher, which times its own),
     *   OK / Enter plays `ui_select` (both stay quiet in a training game and in the pantry, whose presses make their own sounds) and
     *   Back plays `ui_back`. Only a key's first down counts, not its repeats.
     *   A phone's back gesture can arrive as a Back key too (before Android 16), but the screens tick for a
     *   gesture themselves (`GameBackHandler`), so a Back key that comes in touch mode stays quiet here.
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
                    KeyEvent.KEYCODE_MENU -> vm.openStable()
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
        val screen = vm.state.value.screen
        when (keyCode) {
            in MOVE_KEYS -> if (screen !is Screen.Switcher && !screen.ownsKeySounds) audio.sfx("ui_move")
            in SELECT_KEYS -> if (!screen.ownsKeySounds) audio.sfx("ui_select")
            KeyEvent.KEYCODE_BACK -> if (!isTouchInput(touchDevice, window.decorView.isInTouchMode)) audio.sfx("ui_back")
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

        /** The PvP relay (netlify/functions/pvp.mjs, deployed with the landing page). */
        const val PVP_RELAY = "https://pets.superfun.games/api/pvp"

        /**
         * This build's versionCode: PvP only pairs apps of the same one, since both must play a fight the same
         * way (every dev build is 1).
         */
        fun appVersionCode(context: android.content.Context): Int {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
        }
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

        /**
         * The music track for what is on screen, or null for silence. Names are the files in `audio/music/`. The
         * challenger's entrance cuts the music for its bass hit and announcer; the fight music comes in on his VS card.
         */
        fun musicTrackFor(state: UiState): String? = when (state.screen) {
            Screen.Loading, is Screen.FakeDeath, is Screen.Challenger -> null
            Screen.Title, is Screen.Switcher, is Screen.PvpLobby -> "switcher"
            Screen.Home, is Screen.Learn, Screen.Moves, is Screen.Training, is Screen.Pantry -> state.game.selected?.name?.lowercase()
            is Screen.Versus, is Screen.Fight, is Screen.Unlocked, is Screen.Results -> "fight"
        }

        /**
         * One clock and one asset cache per process: the view model outlives an Activity (it is
         * retained across recreation), so both must be the same objects every Activity instance sees.
         */
        val offsetClock = OffsetClock(WallClock)

        @Volatile
        private var sharedLoader: AssetLoader? = null

        /**
         * The earliest believable wall-clock time: when the app was first installed, and never below
         * [Tuning.MIN_VALID_EPOCH_MS]. A TV stick with no clock battery boots to its firmware's build
         * date until the network clock syncs; that date is past 2024 but before the install, so this
         * floor recognises it as stale. The first install, not the last update: an update must not
         * turn the time the player was away before it into a "first run" that forgives the decay.
         */
        fun clockFloorMs(context: Context): Long {
            val installedAt = try {
                val pm = context.packageManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0)).firstInstallTime
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(context.packageName, 0).firstInstallTime
                }
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "No package info for the clock floor; using the default", e)
                0L
            }
            return max(Tuning.MIN_VALID_EPOCH_MS, installedAt)
        }

        /** Whether [context]'s device takes touch: it has a touchscreen and is not a TV (see `isTouchDevice`). */
        fun detectTouchDevice(context: Context): Boolean {
            val uiMode = (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType
            val pm = context.packageManager
            return isTouchDevice(
                television = uiMode == Configuration.UI_MODE_TYPE_TELEVISION || pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK),
                touchscreen = pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN),
            )
        }

        fun assetLoader(context: Context): AssetLoader =
            sharedLoader ?: synchronized(this) {
                sharedLoader ?: AssetLoader(context.applicationContext).also { sharedLoader = it }
            }
    }
}
