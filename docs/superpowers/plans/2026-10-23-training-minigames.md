# Training Mini Games Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** TRAIN spins a film reel onto one of four arcade games (Slash Says, Quick Draw, Hide and Seek, Organ Drop),
played inside a film frame until the first mistake, paying out strength and XP that scale with the score.

**Architecture:** Each game's rules are plain Kotlin under `game/training/` (deterministic given a `Random`, unit
tested); each game's look and input is a composable under `ui/training/` that drives those rules on a pausable
`GameClock`. The view model opens a new `Screen.Training`, and applies the reward once when the screen reports the
final score. A shared shell draws the film frame, the reel, the HUD, pausing and the results card.

**Tech Stack:** Kotlin, Jetpack Compose (TV + phone), kotlinx.serialization (save), JUnit4 pure-JVM tests, Python
tools (Pillow, NumPy, ElevenLabs sound generation) for assets.

**Spec:** `docs/superpowers/specs/2026-10-23-training-minigames-design.md` (read it first; mockups in
`art-work/minigames/mockups/`).

## Global Constraints

- Paths below are relative to `app/src/main/java/team/superfun/horrorpets/` unless they start with `app/`, `tools/`,
  `sprites-final/`, `audio-final/` or `docs/`.
- Do not bump `GameState.version` (2): a lower or changed version reads every existing save as a fresh game.
- New `PetState` fields go at the end of the data class with a default, and are clamped in `SaveCodec.sanitize`.
- Unit tests are pure JVM JUnit4 (no Robolectric, no Compose test rule). Game rules live outside composables.
- Every game is playable with a TV remote (D-pad + OK, Back) and with touch on a phone; `isTouchInput()` decides
  which help text and affordances show, never which inputs work (both always work).
- Screens lay out in dp on a 540 dp-tall design; respect `safeInset()` for HUD text and the help strip.
- Sound files are 16-bit PCM mono 44.1 kHz WAV in `audio-final/sfx/`; `GameAudio` loads every file there by name.
- New sounds in `tools/synth_audio.py` are appended at the END of `sfx_all()` (it shares one RNG; inserting earlier
  changes every existing sound).
- API keys are only ever inline environment variables (`OPENAI_API_KEY=... ELEVENLABS_API_KEY=...`), never written
  to a file or a commit; redact them from any printed output.
- Rewards: hunger `+Tuning.TRAIN_HUNGER`, fun `+Tuning.TRAIN_FUN`, strength `TRAIN_STRENGTH × m`, XP
  `round(XP_TRAIN × m)`, `m = 1 + 2 × min(score, par) / par`. Par: Slash Says 8, Quick Draw 15, Hide and Seek 8,
  Organ Drop 30. A score of 0 pays exactly today's TRAIN.
- Run the app's tests with `./gradlew -q testDebugUnitTest testReleaseUnitTest` and compile with
  `./gradlew -q assembleDebug compileReleaseKotlin`. Python tools run with `tools/.venv/bin/python -I`.
- Commit after each task on branch `training-games`, ending the message with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. Back pressed on the same frame a game ends (or OK double-pressed on the results card): the reward must be
   applied exactly once and home reached once — Task 3 tests `onTrainingOver` twice and `onTrainingDone` twice.
2. A level-up from training: after the results card, Home must offer the newly unlocked attack (the Learn screen) as
   any level-up does — Task 3 test.
3. The app backgrounded mid-game (TV Home button, screen off): the game's clock must stop and resume after a GET READY
   beat; nothing times out while away — Task 4 tests `GameClock` (no time passes while paused).
4. Holding a D-pad direction (key repeats arrive as repeated KeyDown events): Organ Drop keeps running and stops on
   KeyUp; MainActivity drops repeats only for OK/Back — Task 8 tests the key-hold tracker.
5. Every Slash Says dot reachable with the arrows from anywhere, for every layout — Task 5 test over many seeds.

---

### Task 1: Art and sounds

**Files:**
- Create: `tools/finalize_minigame_art.py`, `tools/generate_game_sfx.py`
- Create: `sprites-final/minigames/{tomb-rounded,tomb-cross,tomb-slab,heart,brain,eyeball,bone,hand,kidney}.webp`
- Create: `audio-final/sfx/{tone_1..tone_8,reel_spin,reel_stop,slash,stone_slide,squish,splat,game_over}.wav`
- Modify: `tools/synth_audio.py` (append to `sfx_all()`), `audio-final/CREDITS.md`
- Create: `ui/training/TrainingAssets.kt`
- Test: `app/src/test/java/team/superfun/horrorpets/assets/RosterAssetsTest.kt` (add a test)

**Interfaces:**
- Produces: `internal val TOMBSTONE_ART: List<String>` (asset paths, `"sprites/minigames/tomb-rounded.webp"`,
  `-cross`, `-slab`, in that order), `internal val ORGAN_ART: List<String>` (heart, brain, eyeball, bone, hand,
  kidney), `internal val TRAINING_SFX: List<String>` (the 15 sound names above), `internal fun tone(i: Int): String`
  (`"tone_${i + 1}"`, i in 0..7) — all in `ui/training/TrainingAssets.kt`.

- [ ] **Step 1: Add the asset test** `everyTrainingAssetIsThere()` in `RosterAssetsTest`: every `TOMBSTONE_ART` and
  `ORGAN_ART` path exists under `sprites-final/` (strip the leading `sprites/`), and every `TRAINING_SFX` name exists as
  `audio-final/sfx/<name>.wav`, is mono 16-bit 44.1 kHz (read the WAV header as the existing sound tests do), and
  `TRAINING_SFX.size == 15`.
- [ ] **Step 2: Run it**, expect FAIL (unresolved references).
- [ ] **Step 3: `tools/finalize_minigame_art.py`**, modeled on `tools/finalize_food.py`: `SRC = art-work/minigames`,
  `OUT = sprites-final/minigames`, `PICKS = {every id: 1}`, `SIZE = 512` for tombstones (longer side) and `384` for
  organs, same alpha floor, trim and margin. Run it.
- [ ] **Step 4: Tones**: append to `sfx_all()` in `tools/synth_audio.py` eight bell-like tones `tone_1`..`tone_8`
  on the C-major pentatonic from C5 up (C5 D5 E5 G5 A5 C6 D6 E6), ~0.45 s each, quick attack, soft decay, using the
  file's existing helpers. Regenerate sfx (`--only sfx`); then `git diff --stat audio-final/sfx` must show only the
  eight new files (existing sounds byte-identical).
- [ ] **Step 5: `tools/generate_game_sfx.py`**: ElevenLabs sound generation, modeled on
  `tools/generate_video_sfx.py` (reuse its `generate`, `level_db`, `tonality` by import), writing raws to
  `art-work/sfx/` and finals to `audio-final/sfx/` via `generate_announcer.encode` (mono 44.1 kHz 16-bit), peak
  -1 dBFS, leading silence trimmed. Sounds (prompt gist, seconds): `reel_spin` (old film projector reel spinning
  fast, a clattering whir, 2.0), `reel_stop` (a heavy mechanical clunk as a film reel locks into place, 0.6),
  `slash` (a quick sharp blade swish, 0.4), `stone_slide` (a heavy gravestone scraping across stone, 0.6), `squish`
  (a wet cartoon squish of catching something squishy, 0.4), `splat` (a cartoon gloopy splat on a floor, 0.6),
  `game_over` (a short spooky arcade game-over sting, a descending synth wail, 1.2). Every prompt ends "no music, no
  voices"; reject ringing takes for the non-tonal ones as `generate_video_sfx` does. Run it with 3 takes each.
- [ ] **Step 6: `audio-final/CREDITS.md`**: add that the training sounds `reel_spin, reel_stop, slash, stone_slide,
  squish, splat, game_over` were generated with ElevenLabs sound generation by `tools/generate_game_sfx.py`, and the
  `tone_*` sounds are synthesized like the rest.
- [ ] **Step 7: `ui/training/TrainingAssets.kt`** with the Interfaces above.
- [ ] **Step 8: Run the asset test**, expect PASS; run all tests.
- [ ] **Step 9: Commit** (`feat(training): tombstone and organ art, training sounds`).

### Task 2: Training rules core and the save field

**Files:**
- Create: `model/Training.kt`, `game/training/Training.kt`
- Modify: `model/Model.kt` (`PetState`), `data/SaveCodec.kt` (`sanitize`), `game/Care.kt`
- Test: `app/src/test/java/team/superfun/horrorpets/game/training/TrainingTest.kt`,
  `app/src/test/java/team/superfun/horrorpets/data/SaveCodecTest.kt` (add cases)

**Interfaces:**
- Produces (`model/Training.kt`):
  `@Serializable enum class TrainingGame(val title: String, val needsMate: Boolean, val par: Int)` with
  `SLASH_SAYS("Slash Says", false, 8)`, `QUICK_DRAW("Quick Draw", true, 15)`, `HIDE_AND_SEEK("Hide and Seek", true, 8)`,
  `ORGAN_DROP("Organ Drop", false, 30)`.
- Produces (`model/Model.kt`): `PetState.trainingBest: Map<String, Int> = emptyMap()` (keyed by `TrainingGame.name`,
  so a game a later build adds can't break an older save), last field.
- Produces (`game/Care.kt`): `fun canTrain(p: PetState): Boolean` (hunger below `TRAIN_REFUSE_AT`).
- Produces (`game/training/Training.kt`):
  - `fun eligibleGames(hasMate: Boolean): List<TrainingGame>` (enum order)
  - `fun pickTrainingGame(eligible: List<TrainingGame>, last: TrainingGame?, rng: Random): TrainingGame` (never
    `last` when there's another choice)
  - `fun pickMate(game: GameState, rng: Random): PetId?` (a random pet in `game.pets` other than `game.selected`)
  - `fun trainingMultiplier(game: TrainingGame, score: Int): Double`
  - `fun PetState.bestAt(game: TrainingGame): Int` (0 when none)
  - `data class TrainingOutcome(val pet: PetState, val levelsGained: Int, val strengthGained: Double, val xpGained: Int, val best: Int, val newBest: Boolean)`
  - `fun applyTraining(pet: PetState, game: TrainingGame, score: Int): TrainingOutcome` (negative scores count as 0;
    `strengthGained` is the actual clamped change; best and `trainingBest` updated only when beaten)
  - `enum class TrainingKey { UP, DOWN, LEFT, RIGHT, OK }` — the games' inputs, shared by the rules (Tasks 5, 6, 8)
    and the UI (Task 4 maps real keys onto it), so it lives here, not in `ui/`.

- [ ] **Step 1: Write the failing tests** in `TrainingTest`:
  - `eligibleGamesNeedAMateForQuickDrawAndHideAndSeek`: `eligibleGames(false) == listOf(SLASH_SAYS, ORGAN_DROP)`,
    `eligibleGames(true) == TrainingGame.entries`.
  - `pickNeverRepeatsTheLastGameWhenThereIsAChoice`: 500 picks with `Random(seed)` never return `last`; with a
    single eligible game it returns that game even when it is `last`; over 2000 picks every eligible game shows up.
  - `pickMateIsAnotherStablePet`: null for a one-pet stable; never the selected pet; covers every other pet.
  - `multiplierRunsFromOneToThreeAtPar`: `trainingMultiplier(SLASH_SAYS, 0) == 1.0`, `(…, 4) == 2.0`,
    `(…, 8) == 3.0`, `(…, 99) == 3.0`, `(ORGAN_DROP, 15) == 2.0`, `(…, -3) == 1.0`.
  - `scoreZeroPaysExactlyTodaysTrain`: for a mid-stat pet, `applyTraining(p, any, 0).pet` equals
    `(perform(CareAction.TRAIN, p) as CareResult.Done).pet` except `trainingBest`, and `levelsGained` match.
  - `aHigherScorePaysMore`: strength and xp gained at par are 3× those at 0 (pet far from the stat cap).
  - `strengthGainedReportsTheClampedChange`: a pet at strength 99 gains 1.0.
  - `bestIsKeptPerGame`: first run sets best and `newBest = true`; a lower score keeps the best and `newBest = false`;
    another game's best is untouched.
- [ ] **Step 2: Run** `./gradlew -q testDebugUnitTest --tests '*TrainingTest'`, expect FAIL.
- [ ] **Step 3: Implement** the Interfaces above.
- [ ] **Step 4: SaveCodec** — `sanitize` keeps only `trainingBest` entries whose key is a `TrainingGame` name and
  clamps values to `>= 0`. Add `SaveCodecTest` cases `trainingBestRoundTrips`, `aSaveWithoutTrainingBestLoads`
  (old JSON without the key → empty map), `unknownOrNegativeTrainingBestIsCleaned`.
- [ ] **Step 5: Run all tests**, expect PASS.
- [ ] **Step 6: Commit** (`feat(training): game picking, rewards and per-killer best scores`).

### Task 3: Screen, view model, app wiring and the puppet

**Files:**
- Modify: `ui/Screen.kt`, `ui/GameViewModel.kt`, `ui/HorrorPetsApp.kt`, `MainActivity.kt`
- Create: `ui/training/TrainingScreen.kt` (a stub this task; Task 4 builds it out)
- Modify: `app/src/debug/java/team/superfun/horrorpets/puppet/{PuppetCommand.kt,DebugPuppet.kt}`, `README.md`
  (Puppet mode table)
- Test: `app/src/test/java/team/superfun/horrorpets/ui/GameViewModelTest.kt` (update TRAIN tests, add training ones),
  `app/src/testDebug/java/team/superfun/horrorpets/puppet/PuppetCommandTest.kt`

**Interfaces:**
- Consumes: Task 2's `TrainingGame`, `canTrain`, `eligibleGames`, `pickTrainingGame`, `pickMate`, `applyTraining`.
- Produces (`ui/Screen.kt`):
  `data class TrainingResult(val score: Int, val best: Int, val newBest: Boolean, val strength: Double, val xp: Int, val levelsGained: Int)`;
  `data class Training(val game: TrainingGame, val mate: PetId?, val options: List<TrainingGame>, val result: TrainingResult? = null) : Screen`
  (`options` = the eligible games, which the reel shows; the pet is `game.selected`).
- Produces (`GameViewModel`): `fun onTrainingOver(score: Int)`, `fun onTrainingDone()`, `fun onTrainingQuit()`,
  and for the puppet `internal var debugNextTraining: TrainingGame?`.
- Produces (`ui/training/TrainingScreen.kt`), final signature, kept by Task 4:
  `@Composable fun TrainingScreen(screen: Screen.Training, pet: PetState, mate: PetState?, loader: AssetLoader, onOver: (Int) -> Unit, onDone: () -> Unit, onQuit: () -> Unit, focusEntry: Any?)`.

- [ ] **Step 1: Update and add VM tests** (use the existing `Rig`):
  - `trainOpensTrainingInsteadOfPayingAtOnce` (replaces the old TRAIN-pays tests at `:556-575`, `:625-640`,
    `:656-662`; keep their refusal coverage): TRAIN on a fed pet → `Screen.Training`, pet unchanged, not busy.
  - `trainingWithoutAMateOffersOnlySoloGames`: one-pet stable → `options == [SLASH_SAYS, ORGAN_DROP]`, `mate == null`.
  - `trainingWithAMateMayPickAnyGame`: two pets → options are all four; a mate game has a non-null mate ≠ selected.
  - `starvingPetStillRefusesTraining`: hunger 90 → refuse TOO_HUNGRY as before, screen stays Home.
  - `trainingOverPaysOnceAndShowsTheResult`: `onTrainingOver(8)` → pet strength/xp per Task 2, `result.score == 8`,
    saved; a second `onTrainingOver(8)` changes nothing.
  - `trainingDoneGoesHomeOnce`: `onTrainingDone()` with no result does nothing; after a result → Home; a second call
    does nothing.
  - `quittingDuringTheReelGivesNothing`: `onTrainingQuit()` with no result → Home, pet unchanged; after a result it
    does nothing.
  - `aLevelUpFromTrainingOffersTheNewAttackAfterTheCard`: a pet one training short of a level that unlocks an
    attack → after `onTrainingDone()` the screen is `Screen.Learn`.
- [ ] **Step 2: Run them**, expect FAIL.
- [ ] **Step 3: Implement** `Screen.Training`/`TrainingResult`; in `onCare`, TRAIN → refuse when `!canTrain(pet)`,
  else pick (honoring `debugNextTraining` when eligible, then clearing it), remember the last game picked, and open
  `Screen.Training`. The three functions guard on the screen and `result`. Keep `perform(CareAction.TRAIN, …)` as is
  (Task 2's test pins score 0 to it).
- [ ] **Step 4: Wire the app:** `HorrorPetsApp` branch for `is Screen.Training`; `MainActivity.musicTrackFor` →
  the selected pet's track for `Screen.Training`; `playKeySound` skips `ui_move` and `ui_select` while the screen is
  `Screen.Training` with `result == null` (games make their own sounds). The stub `TrainingScreen` shows the game's
  title and calls `onOver(0)` on OK/tap, `onDone` on OK/tap once `screen.result` is set, `onQuit` on Back.
- [ ] **Step 5: Puppet:** `train` keeps its meaning (opens training); `train <game>` (`slash_says`, `quick_draw`,
  `hide_and_seek`, `organ_drop`) sets `debugNextTraining` first (refused, with a message, if not eligible);
  `score <n>` calls `onTrainingOver(n)` on a training screen. Add `Screen.Training` to `cueOf`, `goHome`
  (`onTrainingQuit` or `onTrainingDone`), `describe` (`"training: <game>[, mate <pet>][, scored <n>]"`). Parse
  tests: `trainTakesAnOptionalGame`, `scoreTakesANumber`; usage text lists both. README Puppet table rows.
- [ ] **Step 6: Run all tests and compile**, expect PASS.
- [ ] **Step 7: Commit** (`feat(training): TRAIN opens training; pays on the score`).

### Task 4: The training shell: film frame, reel, HUD, pause, results

**Files:**
- Create: `ui/training/{GameClock.kt, FilmFrame.kt, TrainingReel.kt, TrainingHud.kt, ResultsCard.kt, TrainingInput.kt}`
- Create: `ui/training/{SlashSaysGame.kt, QuickDrawGame.kt, HideAndSeekGame.kt, OrganDropGame.kt}` as stubs
- Modify: `ui/training/TrainingScreen.kt`
- Test: `app/src/test/java/team/superfun/horrorpets/ui/training/GameClockTest.kt`,
  `app/src/test/java/team/superfun/horrorpets/ui/training/TrainingInputTest.kt`

**Interfaces:**
- Consumes: Task 3's `Screen.Training`, `TrainingScreen` signature; Task 1's assets; `SoundSink`, `AssetLoader`,
  `SceneSpec`, `SceneBackdrop`, `SpriteView`, `KeyCap`, `gameFrame`, `FocusPill`, `GameBackHandler`, `safeInset`.
- Produces:
  - `class GameClock` with `val nowMs: Long` (snapshot state), `fun tick(frameNanos: Long, paused: Boolean)`
    (advances game time by real frame time only while not paused; the first frame after a pause adds nothing),
    and `suspend fun waitMs(ms: Long)` (resumes after `ms` of game time). `@Composable fun rememberGameClock(paused: Boolean): GameClock`.
  - `fun trainingKeyOf(key: Key): TrainingKey?` (Task 2's `TrainingKey`; D-pad,
    arrow keys, OK/Enter/NumpadEnter/Space, gamepad A → OK); `fun Modifier.trainingKeys(onKey: (TrainingKey, down: Boolean) -> Boolean): Modifier`.
  - `class TrainingEnv(val loader: AssetLoader, val pet: PetState, val mate: PetState?, val scene: SceneSpec, val best: Int, val clock: GameClock, val sounds: SoundSink, val focus: FocusRequester, val score: MutableIntState)`
    — a game keeps `score` current as it plays (the shell reads it when Back ends a game early) and calls its own
    `onOver(score)` when it ends itself; after calling `onOver` it takes no more input.
  - The four game entry points, all `@Composable fun XGame(env: TrainingEnv, onOver: (score: Int) -> Unit)`:
    `SlashSaysGame`, `QuickDrawGame`, `HideAndSeekGame`, `OrganDropGame` (stubs here: show the name, OK ends with 0).
  - `@Composable fun TrainingHud(title: String, status: String, best: Int, help: List<HelpItem>, touchHelp: String)`
    with `data class HelpItem(val keys: List<TrainingKey>, val label: String)`; the strip shows key caps (arrows
    drawn, OK as text) on a TV and `touchHelp` under a finger.
  - `@Composable fun FilmFrame(run: Float, content: @Composable BoxScope.() -> Unit)`: black edges with sprocket
    holes down both sides (`run` = how far the film has run, in frames, so the holes can move), grain and flicker
    over the content; content is laid out in the frame between the edges.

- [ ] **Step 1: Tests:** `GameClockTest` — `timeAdvancesWithFrames`, `noTimePassesWhilePaused`,
  `theFirstFrameAfterAPauseAddsNothing`, `aHugeFrameGapIsCapped` (a single frame adds at most 100 ms, so a hitch
  can't skip a call). `TrainingInputTest` — `dpadAndArrowsMapToDirections`, `okEnterAndSpaceAreOk`,
  `otherKeysAreIgnored`.
- [ ] **Step 2: Run**, expect FAIL. **Step 3: Implement** `GameClock`, `TrainingInput`. **Step 4: Run**, PASS.
- [ ] **Step 5: Build the shell UI** (`TrainingScreen`):
  - The pet's current scene (`loader.scenes()[pet.id][pet.sceneIndex mod size]`) behind everything, inside
    `FilmFrame`; edges ≈ 7.8% of the screen width each (the trailer reel's 150 px at 1920).
  - Phase REEL: `TrainingReel(options, landOn = screen.game, …)` — each option a film frame (the scene dimmed, a
    small picture of the game drawn from its assets, the game's name big); spins fast with vertical motion blur
    (`reel_spin` looping), decelerates over ~2.4 s and lands on `screen.game` with `reel_stop` and a flash; then
    "GET READY..." for 0.8 s; then phase GAME. Back during REEL → `onQuit`.
  - Phase GAME: the game composable with a `TrainingEnv`; Back → `onOver(env.score.intValue)`. Lifecycle
    ON_PAUSE pauses the clock; ON_RESUME shows "GET READY" 1 s then unpauses.
  - When `screen.result != null`: `ResultsCard` over the frozen game in a `gameFrame` window: the game title, the
    score big, BEST (and NEW BEST! in gold when `newBest`), `+N STRENGTH`, `+N XP`, `LEVEL UP!` when
    `levelsGained > 0`, and a focused CONTINUE `FocusPill` (OK/tap → `onDone`; Back → `onDone`). `game_over` plays as
    the card appears (`levelup` instead when a level was gained).
  - The four game stubs per the Interfaces.
- [ ] **Step 6: Run all tests and compile.** **Step 7: Commit** (`feat(training): the film-frame shell, reel and results`).

### Task 5: Slash Says

**Files:**
- Create: `game/training/SlashSays.kt`; Modify: `ui/training/SlashSaysGame.kt`
- Test: `app/src/test/java/team/superfun/horrorpets/game/training/SlashSaysTest.kt`

**Interfaces:**
- Consumes: Task 4's `TrainingEnv`, `GameClock`, `TrainingHud`, `trainingKeys`; Task 1's `tone(i)`, `slash`.
- Produces (`game/training/SlashSays.kt`):
  - `const val SLASH_SLOTS = 8`; `fun slotAngle(slot: Int): Float` — degrees, slots at 150, 185, 220, 255, 285, 320,
    355, 30 (0 = right, clockwise, y down), on an ellipse around the pet's chest, never behind its body.
  - `fun slashDots(round: Int): Int` = `min(round + 1, 8)` (round 1 → 2).
  - `data class SlashRound(val round: Int, val slots: List<Int>, val order: List<Int>)` — `slots[i]` is dot i's slot
    (distinct), `order` a permutation of `slots.indices`; dot i's tone is `slots[i]`.
  - `fun nextSlashRound(round: Int, rng: Random): SlashRound`
  - `fun slashShowMs(round: Int): Pair<Long, Long>` = lit `max(220, 520·0.9^(round-1))`, gap `max(80, 180·0.9^(round-1))`.
  - `class SlashTurn(val round: SlashRound)` with `fun slash(dot: Int): SlashStep` where
    `enum class SlashStep { RIGHT, ROUND_DONE, WRONG, IGNORED }` (re-slashing an already slashed dot is IGNORED).
  - `fun aimFrom(from: Pair<Float, Float>, dots: List<Pair<Float, Float>>, dir: TrainingKey, skip: Int?): Int?` —
    the dot best in that direction: candidates within 75° of it, cost = distance × (1 + 2·angle/75°), lowest wins;
    null when none.
- [ ] **Step 1: Tests:** `roundsGrowToEightDots`; `slotsAreDistinctAndOrderIsAPermutation` (1000 seeds × rounds
  1..12); `layoutsChangeEveryRound`; `showSpeedsUpToAFloor`; `aTurnTakesTheOrderThenEnds` (RIGHT…ROUND_DONE);
  `aWrongDotEndsIt`; `reslashingIsIgnored`; `everyDotIsReachableWithTheArrows` (Review Focus 5: for 2000 random
  rounds, BFS over `aimFrom` with the 4 directions from the chest point reaches every dot, and from every dot every
  other one) — dot positions from `slotAngle` on an ellipse with x radius 1.5× its y radius.
- [ ] **Step 2: Run**, FAIL. **Step 3: Implement** the rules. **Step 4: Run**, PASS.
- [ ] **Step 5: The game UI** (`SlashSaysGame`): the pet (SpriteView, idle) standing centered in the frame; the dots
  on the ellipse (radius ≈ 0.32 × play width by 0.21 × height around the chest), each its own color from a fixed
  8-color list by slot, unlit dim ring, lit bright with glow. Show phase: "WATCH..." and each dot in order lights for
  `lit` ms and plays `tone(slot)`, `gap` ms between. Turn phase ("YOUR TURN"): touch — a drag/tap entering a dot's
  hit circle (1.4× its radius) slashes it; TV — arrows move an aim ring with `aimFrom` (from the chest point at first),
  OK slashes the aimed dot. RIGHT lights it with its tone and extends the glowing slash trail; ROUND_DONE: the pet's
  ATTACK + `slash`, 0.8 s, next round; WRONG: the right dot flashes, `refuse`, the pet's FAIL, then `onOver(rounds
  completed)`. HUD: `SLASH SAYS`, status `ROUND n · WATCH` / `ROUND n · k OF n`, help: arrows "AIM", OK "SLASH";
  touch: "SWIPE THROUGH THE DOTS IN ORDER".
- [ ] **Step 6: Run all tests and compile. Step 7: Commit** (`feat(training): Slash Says`).

### Task 6: Quick Draw

**Files:**
- Create: `game/training/QuickDraw.kt`; Modify: `ui/training/QuickDrawGame.kt`
- Test: `app/src/test/java/team/superfun/horrorpets/game/training/QuickDrawTest.kt`

**Interfaces:**
- Consumes: Task 4's shell; `env.mate` is non-null for this game.
- Produces (`game/training/QuickDraw.kt`):
  - `enum class DrawCall { UP, DOWN, LEFT, RIGHT, CENTER }`; `fun DrawCall.key(): TrainingKey` (CENTER → OK).
  - `fun drawWaitMs(rng: Random): Long` in 700..2200; `fun drawWindowMs(streak: Int): Long` = `max(350, 1500·0.92^streak)`.
  - `fun nextCall(recent: List<DrawCall>, rng: Random): DrawCall` (never the same three times running).
  - `enum class DrawOutcome { HIT, WRONG, EARLY, LATE }`;
    `fun judgeDraw(called: DrawCall?, pressed: DrawCall?, sinceCallMs: Long, windowMs: Long): DrawOutcome`
    (`called == null` → a press is EARLY; `pressed == null` past the window → LATE).
- [ ] **Step 1: Tests:** `waitsAreUnpredictable` (range and spread), `windowShrinksToAFloor`,
  `neverTheSameCallThreeTimes`, `everyCallComesUp`, `judging` (HIT, WRONG, EARLY, LATE, and a press exactly at the
  window's end is HIT).
- [ ] **Step 2–4:** FAIL, implement, PASS.
- [ ] **Step 5: The game UI** (`QuickDrawGame`): the pet left, the mate right (both SpriteView, idle), the D-pad ring
  between them as in the mockup `7-quick-draw-ring.jpg` (four wedges with arrows, a center OK disc, a thin time ring
  around it that drains over the window). READY: all parts dim for the wait. The call lights one part red; match it.
  TV: the D-pad direction or OK. Touch: tap the part (wedges by angle, the center disc by radius). HIT: the pet's
  ATTACK + `hit` (`crit` every fifth), the mate's FAIL, streak +1, next call. WRONG/EARLY/LATE: the mate's ATTACK, the
  pet's FAIL, a callout ("TOO SOON!", "TOO SLOW!", "WRONG!"), then `onOver(streak)`. HUD: `QUICK DRAW`, status
  `STREAK ×n`, help: arrows + OK "MATCH IT, FAST"; touch: "TAP THE PART THAT LIGHTS UP, FAST".
- [ ] **Step 6–7:** tests + compile, commit (`feat(training): Quick Draw`).

### Task 7: Hide and Seek

**Files:**
- Create: `game/training/HideAndSeek.kt`; Modify: `ui/training/HideAndSeekGame.kt`
- Test: `app/src/test/java/team/superfun/horrorpets/game/training/HideAndSeekTest.kt`

**Interfaces:**
- Consumes: Task 4's shell; Task 1's `TOMBSTONE_ART`, `stone_slide`; `env.mate` non-null.
- Produces (`game/training/HideAndSeek.kt`):
  - `fun tombDesign(round: Int): Int` = `(round - 1) % 3` (index into `TOMBSTONE_ART`; all three stones the same).
  - `data class Shuffle(val hideAt: Int, val swaps: List<Pair<Int, Int>>, val swapMs: Long)` — positions 0..2; each
    swap exchanges the stones at two positions.
  - `fun shuffleFor(round: Int, rng: Random): Shuffle` — swaps `min(2 + round, 14)`, `swapMs = max(170, 600·0.88^(round-1))`,
    no swap of a position with itself.
  - `fun mateAfter(shuffle: Shuffle): Int` — where the mate's stone ends up.
- [ ] **Step 1: Tests:** `moreSwapsEachRoundUpToACap`, `swapsSpeedUpToAFloor`, `theMateFollowsItsStone` (hand-made
  swaps), `designsCycleEveryRound`, `noSwapIsANoOp`.
- [ ] **Step 2–4:** FAIL, implement, PASS.
- [ ] **Step 5: The game UI** (`HideAndSeekGame`): three identical tombstones (`TOMBSTONE_ART[tombDesign(round)]`)
  in a row on the floor; your pet watching from the left edge. Hide: the mate stands in front of `hideAt`, then
  ducks down behind it (slides down and fades out over 0.4 s). Shuffle: each swap animates the two stones exchanging
  x over `swapMs`, one arcing in front (lower, larger) and one behind (higher, smaller), `stone_slide` per swap. Pick:
  a pointer arrow over a stone (starts center); TV Left/Right moves it, OK checks; touch taps a stone. Right: the stone
  lifts, the mate pops up (JUMP), next round after 0.8 s. Wrong: the picked stone lifts empty, `refuse`, then the
  right one lifts with the mate (WAVE), your pet's FAIL, `onOver(rounds won)`. HUD: `HIDE AND SEEK`, status
  `ROUND n` (+ `· WATCH` during the shuffle), header callout `WHERE'S <MATE>?` during the pick, help: Left/Right
  "PICK", OK "CHECK"; touch: "TAP THE STONE <MATE> IS BEHIND".
- [ ] **Step 6–7:** tests + compile, commit (`feat(training): Hide and Seek`).

### Task 8: Organ Drop

**Files:**
- Create: `game/training/OrganDrop.kt`; Modify: `ui/training/OrganDropGame.kt`
- Test: `app/src/test/java/team/superfun/horrorpets/game/training/OrganDropTest.kt`

**Interfaces:**
- Consumes: Task 4's shell; Task 1's `ORGAN_ART`, `squish`, `splat`.
- Produces (`game/training/OrganDrop.kt`):
  - `const val RUN_SPEED = 0.9f` (play widths per second), `const val CATCH_HALF = 0.075f`, x range `0.1f..0.9f`.
  - `data class Drop(val organ: Int, val x: Float, val landMs: Long, val fallMs: Long)` (`organ` indexes
    `ORGAN_ART`; it appears at the top at `landMs - fallMs` and reaches the catch line at `landMs`).
  - `fun fallMs(index: Int): Long` = `max(900, 2600·0.97^index)`; `fun landGapMs(index: Int): Long` = `max(420, 1400·0.96^index)`.
  - `fun nextDrop(prev: Drop?, index: Int, rng: Random): Drop` — the first lands at `1600 ms` within reach of x 0.5;
    each next one lands `landGapMs(index)` after the previous, at an x within `RUN_SPEED × gap − 0.05` of the
    previous drop's x, clamped to the range; never the same organ twice running.
  - `fun caught(dropX: Float, killerX: Float): Boolean` (`|Δ| ≤ CATCH_HALF`).
  - `class KeyHold` — `fun key(key: TrainingKey, down: Boolean)`, `val direction: Int` (−1, 0, +1; the most recently
    pressed held direction wins; repeats of a held key change nothing).
- [ ] **Step 1: Tests:** `dropsSpeedUpAndCrowdIn` (after ~25 drops, more than one is in the air at once),
  `everyDropIsReachableFromTheLast` (2000 seeds × 120 drops: `|Δx| ≤ RUN_SPEED·gap − 0.05 + ε` and inside the range),
  `organsVary`, `catchWindow`, `keyHoldFollowsTheLatestHeldKeyAndIgnoresRepeats` (Review Focus 4).
- [ ] **Step 2–4:** FAIL, implement, PASS.
- [ ] **Step 5: The game UI** (`OrganDropGame`): the pet on the floor at x 0.5 (of the play width), IDLE when still,
  RUN_RIGHT/RUN_LEFT when moving at `RUN_SPEED`; drops fall from above the top edge (linear in game time, a slight
  spin), the catch line at the pet's chest. TV: `KeyHold` from `trainingKeys` (down and up). Touch: while a finger is
  down the pet runs toward its x (stops within a few px). At `landMs`: caught → the organ pops into the pet with
  `squish`, a "+1", score +1; missed → it falls to the floor, `splat`, the pet's FAIL, `onOver(caught)`. HUD:
  `ORGAN DROP`, status `CAUGHT n`, help: Left/Right "MOVE", touch: "HOLD A FINGER WHERE YOU WANT TO RUN".
- [ ] **Step 6–7:** tests + compile, commit (`feat(training): Organ Drop`).

### Task 9: Play-test and polish (controller)

Done by the controlling session, not a subagent: build, install on the TV emulator (`emulator-5554`) and the phone
emulator (`emulator-5556`; never the real TV `192.168.1.42:5555`), use `train <game>` from the puppet to reach each
game, play each with the remote and with touch, screenshot each phase, tune timings that feel wrong, fix what's off,
and add a README feature line for training. Then the final whole-branch review.
