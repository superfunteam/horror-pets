# Fights That Hit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fights play like a classic RPG (no tell, no feint) and hit like a fighting game: hit-stop on impact, the
knockout in slow motion, specials that turn the arena red under their own riff, music that hardens when a fighter is
nearly done, and phone haptics on the sounds.

**Architecture:** The CPU picks its move when the turn resolves (`cpuPick`), so the plan-and-tell machinery goes.
The fight screen gets one time scale (`FightStage.timeScale`) that the sprites and the stage's own waits honour, so
a hit-stop is "scale 0 for N ms" and the knockout is "scale 0.3 for 900 ms". New music and sounds are synthesized
by tools/synth_audio.py like the rest (no key, no license). `GameAudio` learns a position-matched cross-fade, a riff
slot over ducked music, and a vibrator keyed to sound names.

**Tech Stack:** Kotlin, Jetpack Compose, ExoPlayer (media3), SoundPool, NumPy/SciPy (tools/.venv), JUnit4.

**Spec:** docs/superpowers/specs/2026-10-24-feel-and-depth-design.md, sections D, E and F (the phase-1 parts).

## Global Constraints

- `GameState.version` stays 2; nothing in this plan touches the save.
- Pure logic goes under `fight/`, `audio/` or `ui/fight/` as plain functions with JVM tests (`./gradlew testDebugUnitTest`
  must stay green: 1172 tests today); UI is checked on `emulator-5554` (the TV) with the debug puppet
  (`adb -s emulator-5554 shell "am broadcast -a team.superfun.horrorpets.PUPPET --es cmd 'fight'"`, `move strike`,
  `autoplay on`, `meter full`, `status`). Never touch 192.168.1.42:5555.
- PvP is untouched: `resolvePvpTurn`, `PvpMatch` and the relay keep their wire format and dice.
- Audio ships as today: music is Opus `.ogg` in `audio-final/music/`, effects 44.1 kHz mono `.wav` in
  `audio-final/sfx/`, both mirrored into the APK by the Gradle task. New files are committed with their generator.
- Hit-stop: 70 ms for a STRIKE that lands, 110 ms for a HEAVY, a crit, a counter or a special (`FightTuning`
  constants `HIT_STOP_STRIKE_MS`, `HIT_STOP_HEAVY_MS`). Knockout: `KO_SLOW = 0.3f` for `KO_SLOW_MS = 900`. Red:
  `#C1121F` to 0.45 over 220 ms, off over 400 ms. Duck: music to 0.25 (−12 dB) over 150 ms for a special; the riff
  fades out over 600 ms. Intense music: when either fighter's HP first drops under 30% of max, cross-faded over 1.5 s
  from the same position, and kept to the end of the fight.
- The secret killer is never named in README copy or notes.

## Review Focus

1. A special whose blow lands partway through its effect (`specialLandsAtMs > 0`): the hit-stop and the knockout must
   follow the landing, not the swing's impact; Task 2 tests `impactSchedule` for `landMs > 0`.
2. A knockout by a bleed tick or a counter (no lunge): the slow motion must not be expected where no blow plays; Task 2
   tests `koSlowFor` returns 0 for a non-Hit event and the finale still plays.
3. A potion that heals above 30% after the intense track started: the music stays intense; Task 4's view-model test.
4. A phone with no vibrator, and a TV: `hapticFor` is pure and `buzz` is a no-op without a `Vibrator`; Task 5 tests
   the table only, and the implementer checks the TV emulator logs show no vibrator error.
5. Autoplay (the debug puppet) after the tell is gone: Task 1 tests `autoplayPick` picks STRIKE and HEAVY in turn.

---

### Task 1: The tell and the feint go

**Files:**
- Modify: `app/src/main/java/team/superfun/horrorpets/fight/CpuBrain.kt` (remove `cpuPlan`, `planCpu`; keep `cpuPick`)
- Modify: `app/src/main/java/team/superfun/horrorpets/fight/FightModel.kt` (remove `CpuPlan`, `FightState.cpuPlan`)
- Modify: `app/src/main/java/team/superfun/horrorpets/fight/FightTuning.kt` (`CPU_COUNTER_CHANCE = 0.25`; remove
  `CPU_FEINT_CHANCE`, `GHOSTFACE_FEINT_BONUS`)
- Modify: `app/src/main/java/team/superfun/horrorpets/model/Roster.kt` (remove `Passive.feintBonus` and Ghostface's use)
- Modify: `app/src/main/java/team/superfun/horrorpets/ui/Screen.kt` (remove `Screen.Fight.cpuTell`)
- Modify: `app/src/main/java/team/superfun/horrorpets/ui/GameViewModel.kt` (`onMove`: `cpuMove = if (m == Move.RUN) null else cpuPick(fight, rng)`; no `planCpu`)
- Modify: `app/src/main/java/team/superfun/horrorpets/ui/fight/FightScreen.kt` (remove `shownTell`, the `TellBubble` call,
  `TELL_AT`, the `FEINT!` float and `FloatKind.FEINT`)
- Delete: `app/src/main/java/team/superfun/horrorpets/ui/fight/TellBubble.kt`
- Modify: `app/src/debug/java/team/superfun/horrorpets/puppet/Autoplay.kt` (drop step 3; renumber the doc)
- Modify: `README.md` (the `autoplay` and `hud` puppet rows no longer mention the tell; the VS fights bullet, if it does)
- Test: `app/src/test/java/team/superfun/horrorpets/fight/CpuBrainTest.kt`, `FightEngineTest.kt`, `TwoPlayerTest.kt`,
  `app/src/test/java/team/superfun/horrorpets/ui/GameViewModelTest.kt`, `app/src/test/java/team/superfun/horrorpets/puppet/AutoplayTest.kt` (if it exists; else add one)

**Interfaces:**
- Consumes: `cpuPick(s: FightState, rng: Random): Move` (unchanged).
- Produces: `Screen.Fight(fight, lastEvents, playerMove, cpuMove, pvp)`; `FightState` without `cpuPlan`.

- [ ] **Step 1: Write the failing tests**

In `CpuBrainTest.kt` delete `aPlanShowsItsMoveUnlessItFeints`, `aFeintShowsTheMoveThatAnswersTheRealOne`,
`aSpecialIsNeverAFeintAndRollsNoFeint`, `ghostfaceFeintsMoreOften`; change whatever asserts the counter chance to
`0.25` (`countersMostUsed` draws 1000 picks: expect about 250 answers, not 400; use the existing tolerance style).
In `GameViewModelTest.kt`, where `cpuTell`/`cpuPlan` are read, assert instead that `screen.cpuMove` is set after
`onMove` and that two fights with the same seed play the same CPU moves (determinism kept). Add to the puppet's
autoplay test (create `app/src/test/java/team/superfun/horrorpets/puppet/AutoplayTest.kt` under the debug test
source set if none exists; check `app/src/testDebug` first):

```kotlin
@Test
fun withoutASpecialOrAPotionAutoplayAlternatesStrikeAndHeavy() {
    val fight = fightAtTurn(1) // any FightState with an empty meter and full HP
    assertEquals(Move.STRIKE, autoplayPick(fight).move)
    assertEquals(Move.HEAVY, autoplayPick(fight.copy(turn = 2)).move)
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests '*CpuBrainTest*' --tests '*GameViewModelTest*' --tests '*AutoplayTest*'`
Expected: compile errors on the removed names, or failures on the 0.25 chance.

- [ ] **Step 3: Remove the tell**

`CpuBrain.kt`: delete `cpuPlan` and `planCpu` and their KDoc; update the file's dice comment. `FightModel.kt`:
delete `CpuPlan` and the `cpuPlan` field and its KDoc lines (`FightState` keeps `playerHistory`). `FightTuning.kt`:
`CPU_COUNTER_CHANCE = 0.25`, delete the two feint constants and their comments. `Roster.kt`: delete `feintBonus`
(the KDoc bullet too); Ghostface's `Passive("Slippery", evadeBonus = ...)`. `Screen.kt`: delete `cpuTell` and its
KDoc. `GameViewModel.onMove`: the CPU's move is `cpuPick(fight, rng)` drawn when the player's move comes in (RUN:
null), passed to `resolveTurn`; `Screen.Fight(turn.state, turn.events, playerMove = m, cpuMove = cpuMove)`. Update the
KDoc that says "the move it planned (and told, or feinted)". `FightScreen.kt`: remove the tell bubble block, the
`shownTell` state and its setting, `TELL_AT`, the `FEINT!` float and `FloatKind.FEINT`; delete `TellBubble.kt`.
`Autoplay.kt`: remove the tell step. README: the two puppet rows.

- [ ] **Step 4: Run the whole suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS, with the four feint tests gone.

- [ ] **Step 5: Check a fight on the TV emulator**

Install the debug build on `emulator-5554`, `fight`, play two turns with `move strike` / `move heavy`: no bubble over
the CPU, no FEINT! float, the callouts pop as before. Screenshot to your scratch area.

- [ ] **Step 6: Commit**

```bash
git add -A app/src README.md
git commit -m "feat(fight): no tell, no feint: the CPU picks as the turn resolves"
```

### Task 2: Fight time: hit-stop and the knockout in slow motion

**Files:**
- Create: `app/src/main/java/team/superfun/horrorpets/ui/fight/FightTime.kt`
- Modify: `app/src/main/java/team/superfun/horrorpets/fight/FightTuning.kt` (`HIT_STOP_STRIKE_MS = 70L`,
  `HIT_STOP_HEAVY_MS = 110L`, `KO_SLOW = 0.3f`, `KO_SLOW_MS = 900L`)
- Modify: `app/src/main/java/team/superfun/horrorpets/ui/common/SpriteView.kt` (a `timeScale: () -> Float = { 1f }` parameter)
- Modify: `app/src/main/java/team/superfun/horrorpets/ui/fight/FightScreen.kt` (`FightStage.timeScale`, `koGrey`,
  the stop in `lunge`/`impactOf`, the knockout sequence, `FighterView` passing the scale and the grey)
- Modify: `tools/synth_audio.py` (`ko_boom` in `sfx_all`) and commit `audio-final/sfx/ko_boom.wav`
- Test: `app/src/test/java/team/superfun/horrorpets/ui/fight/FightTimeTest.kt`

**Interfaces:**
- Consumes: `FightEvent.Hit(side, move, damage, crit, counter, combo, effect, shattered)`, `specialLandsAtMs(style)`,
  `FighterRig.hpTarget`.
- Produces (all in `FightTime.kt`, pure):
  - `fun hitStopFor(event: FightEvent): Long` — 0 unless `event is FightEvent.Hit`; then
    `HIT_STOP_HEAVY_MS` when `move == HEAVY || move == SPECIAL || crit || counter`, else `HIT_STOP_STRIKE_MS`.
  - `fun koBlow(event: FightEvent, defenderHp: Int): Boolean` — a `Hit` whose `damage >= defenderHp`.
  - `data class ImpactSchedule(val soundAtMs: Long, val stopMs: Long, val blowAtMs: Long)` and
    `fun impactSchedule(landMs: Long, stopMs: Long): ImpactSchedule` — sound at `landMs`, the blow at `landMs + stopMs`.
  - `class ScaledClock { fun advance(dtMs: Long, scale: Float): Long }` — accumulates `dtMs * scale`, returning the
    scaled elapsed so far (rounded to the millisecond, carrying the remainder).

- [ ] **Step 1: Write the failing tests**

```kotlin
class FightTimeTest {
    private fun hit(move: Move, crit: Boolean = false, counter: Boolean = false, damage: Int = 10) =
        FightEvent.Hit(Side.PLAYER, move, damage, crit, counter, combo = 0, effect = null, shattered = 0)

    @Test fun aStrikeStopsShortAndTheBigOnesStopLonger() {
        assertEquals(FightTuning.HIT_STOP_STRIKE_MS, hitStopFor(hit(Move.STRIKE)))
        assertEquals(FightTuning.HIT_STOP_HEAVY_MS, hitStopFor(hit(Move.HEAVY)))
        assertEquals(FightTuning.HIT_STOP_HEAVY_MS, hitStopFor(hit(Move.STRIKE, crit = true)))
        assertEquals(FightTuning.HIT_STOP_HEAVY_MS, hitStopFor(hit(Move.STRIKE, counter = true)))
        assertEquals(FightTuning.HIT_STOP_HEAVY_MS, hitStopFor(hit(Move.SPECIAL)))
        assertEquals(0L, hitStopFor(FightEvent.Miss(Side.PLAYER, Move.STRIKE, dazed = false)))
        assertEquals(0L, hitStopFor(FightEvent.Blocked(Side.PLAYER, Move.STRIKE)))
    }

    @Test fun theBlowThatEmptiesTheBarIsTheKnockout() {
        assertTrue(koBlow(hit(Move.HEAVY, damage = 12), defenderHp = 12))
        assertFalse(koBlow(hit(Move.HEAVY, damage = 11), defenderHp = 12))
        assertFalse(koBlow(FightEvent.Miss(Side.PLAYER, Move.STRIKE, dazed = false), defenderHp = 1))
    }

    @Test fun theSoundLandsBeforeTheStopAndTheBlowAfterIt() {
        assertEquals(ImpactSchedule(0, 110, 110), impactSchedule(landMs = 0, stopMs = 110))
        assertEquals(ImpactSchedule(780, 110, 890), impactSchedule(landMs = 780, stopMs = 110))
        assertEquals(ImpactSchedule(0, 0, 0), impactSchedule(landMs = 0, stopMs = 0))
    }

    @Test fun aScaledClockHoldsAtZeroAndCrawlsAtATenth() {
        val c = ScaledClock()
        assertEquals(0L, c.advance(100, 0f))
        assertEquals(10L, c.advance(100, 0.1f))
        assertEquals(110L, c.advance(100, 1f))
        c.advance(5, 0.3f) // 1.5 ms: the half carries
        assertEquals("remainders carry", 113L, c.advance(5, 0.3f))
    }
}
```
Check the exact constructor shapes of `FightEvent.Hit`, `Miss` and `Blocked` in `fight/FightModel.kt` and match them.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests '*FightTimeTest*'`
Expected: FAIL, unresolved references.

- [ ] **Step 3: Implement `FightTime.kt` and the tuning constants**

Pure functions as specified; `ScaledClock` keeps a `Double` accumulator and returns `floor` as `Long`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests '*FightTimeTest*'`
Expected: PASS

- [ ] **Step 5: Give `SpriteView` a time scale**

`SpriteView(..., timeScale: () -> Float = { 1f }, ...)`: in the frame loop, keep `last` and a `ScaledClock`; each
frame `elapsed = clock.advance(now - last, timeScale())`. At scale 0 the frame holds; at 0.3 it crawls. Loop and
one-shot logic otherwise unchanged. Every existing caller compiles unchanged (the default).

- [ ] **Step 6: The stop and the knockout in `FightScreen.kt`**

- `FightStage`: `var timeScale by mutableFloatStateOf(1f)`; `val koGrey = Animatable(0f)`; `suspend fun pass(ms: Long)`
  that waits `ms` of *scaled* time (a `withFrameMillis` loop through a `ScaledClock` with `timeScale`), so a wait during
  slow motion stretches with it.
- `FighterView` passes `timeScale = { stage.timeScale }` to `SpriteView`, and `saturation = 1f - 0.4f * grey` where
  `grey` is `koGrey` quantized to quarter steps (as `FLASH_STEPS` does for the flash), so the sprites drain with the
  scene.
- The scene's grey: where `darken` is drawn over the arena, also draw `Color(0xFF9A9A9A)` at `koGrey.value * 0.4f`.
- `lunge`: after `delay(impact)`, compute `stop = hitStopFor(event)` and `ko = koBlow(event, d.hpTarget.toInt())`;
  call `impactOf(..., stopMs = stop)`; then if `stop > 0`: `timeScale = 0f; delay(stop); timeScale = 1f`. Then, if `ko`:
  `knockout(scope, d)`: `timeScale = KO_SLOW`, `koGrey.animateTo(1f, tween(100))`, `flash(scope, Color.White, 0.6f, 10)`,
  `play(FightSounds(listOf("ko_boom"), emptyList()))`, `delay(KO_SLOW_MS)` (real time), `timeScale = 1f`. The
  existing `delay(swingHoldMs(...))` and the rest follow. `koGrey` returns to 0 (`animateTo(0f, tween(300))`) as the
  finale's K.O. slam starts, so the winner's jump plays in colour.
- `impactOf(..., stopMs: Long)`: the sounds stay at `landMs` (and the announcer at its own time, as today); the
  `blow()` lambda and the special's late `hurt` are scheduled from `impactSchedule(landMs, stopMs).blowAtMs` instead
  of `landMs`. For a special with `landMs > 0`, the stop itself is applied inside that scheduled block: `timeScale = 0f`
  at `landMs`, `blow()` at `blowAtMs`, `timeScale = 1f` right after (`scope.launch` with two delays). The drain during
  a knockout runs `DRAIN_MS * 3`.
- `ko_boom` in `tools/synth_audio.py` `sfx_all`: a 0.9 s sub boom: a sine sweeping 90 → 32 Hz over 0.5 s under a
  short noise burst (bandpass 60–900 Hz, 30 ms) and a tanh-saturated low saw, exponential decay 0.35 s. Run
  `tools/.venv/bin/python -I tools/synth_audio.py --only sfx` and commit `audio-final/sfx/ko_boom.wav` (the other
  sfx files must come out byte-identical: check `git status` shows only the new file; if not, stop and report).

- [ ] **Step 7: Check on the TV emulator**

`fight`, `meter full`, `move heavy`: the sprites freeze for a blink at the impact and the number, flash and shake
come as they resume. Win a fight (`autoplay on` and let it run): the last blow crawls with the scene greyed and a
boom, then K.O. slams in as before. Two screenshots during the slow motion to your scratch area. Watch logcat for
"ko_boom" and no "unknown sfx".

- [ ] **Step 8: Run the whole suite, then commit**

```bash
./gradlew testDebugUnitTest
git add -A app/src tools/synth_audio.py audio-final/sfx/ko_boom.wav
git commit -m "feat(fight): hit-stop on impact and the knockout in slow motion"
```

### Task 3: Specials turn the arena red under a riff

**Files:**
- Modify: `app/src/main/java/team/superfun/horrorpets/audio/SoundSink.kt` (`fun riff(on: Boolean)`, default no-op in fakes)
- Modify: `app/src/main/java/team/superfun/horrorpets/audio/GameAudio.kt` (a riff slot over ducked music)
- Modify: `app/src/main/java/team/superfun/horrorpets/ui/fight/FightScreen.kt` (`FightStage.red`, drawn with `darken`;
  `riff(true)` at the special's wind-up, `riff(false)` at `blowAtMs + 1000`)
- Modify: `tools/synth_audio.py` (`music_special_riff`, in `MUSIC` at the end) and commit `audio-final/music/special_riff.ogg`
- Test: `app/src/test/java/team/superfun/horrorpets/audio/RiffTest.kt` (the duck arithmetic only, see below)

**Interfaces:**
- Produces: `SoundSink.riff(on: Boolean)`; in `audio/Ducking.kt` (new, pure): `fun duckLevel(voice: Boolean, riff: Boolean): Float`
  — 1.0 with neither, `DUCK_LEVEL` for a voice, `RIFF_DUCK = 0.25f` for a riff, the lower of the two for both.

- [ ] **Step 1: Write the failing test**

```kotlin
class RiffTest {
    @Test fun theRiffDucksHarderThanAVoiceAndTheLowerWins() {
        assertEquals(1f, duckLevel(voice = false, riff = false))
        assertEquals(DUCK_LEVEL, duckLevel(voice = true, riff = false))
        assertEquals(RIFF_DUCK, duckLevel(voice = false, riff = true))
        assertEquals(minOf(DUCK_LEVEL, RIFF_DUCK), duckLevel(voice = true, riff = true))
    }
}
```

(`DUCK_LEVEL` is `GameAudio`'s private constant today: move it to `Ducking.kt` as `internal const val`.)

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests '*RiffTest*'`
Expected: FAIL, unresolved.

- [ ] **Step 3: Implement**

- `Ducking.kt` as specified. `GameAudio`: `duckTarget` becomes `duckLevel(voice = !voices.idle, riff = riffOn)`
  wherever it was set; a third `MusicSlot("R")` (`riffSlot`, created on first use) whose `start("special_riff")`
  plays the loop at its own level (it is not part of `quieterSlot`'s pair and never the `activeSlot`); `riff(true)`
  starts it with `target = 1f` and the music ducks over `RIFF_DUCK_DOWN_MS = 150`; `riff(false)` sets its `target = 0f`
  with the slot's fade at `RIFF_FADE_MS = 600` (give `MusicSlot` a `fadeMs` the tick uses instead of the shared
  `CROSSFADE_MS`) and restores the duck. `pause()` stops the riff too.
- `SoundSink.riff` default no-op for the test fakes (check every implementation compiles: `grep -rn ": SoundSink"`).
- `FightScreen.kt`: `val red = Animatable(0f)` on the stage; where `darken.animateTo(0.5f + ...)` fires at the
  wind-up, also `red.animateTo(0.45f, tween(220))` and `sink.riff(true)`; where `darken.animateTo(0f, tween(260))`
  releases, `red.animateTo(0f, tween(400))`; `sink.riff(false)` is scheduled at `blowAtMs + 1000` from the impact.
  Draw `Color(0xFFC1121F)` at `red.value` right over the `darken` layer (over the arena, under the fighters' HUD).
- `music_special_riff()` in `tools/synth_audio.py`: 8 s at 120 BPM (4 bars), E minor like `music_fight`: a saturated
  saw bass riff on E2–G2–A2–Bb2 in eighths, a tom roll on every fourth beat, a siren-like sine sweep 400 → 900 Hz over
  each bar, hats on sixteenths; `loopify` with a 0.5 s fade so it loops clean; `level_music` as the others. Add
  `"special_riff": music_special_riff` at the end of `MUSIC`. Run
  `tools/.venv/bin/python -I tools/synth_audio.py --only special_riff` and commit the .ogg.

- [ ] **Step 4: Check on the TV emulator**

`fight`, `meter full`, `move special`: the arena darkens and turns red at the wind-up, the music drops and the riff
rides over it, then the red lifts and the music comes back a second after the blow. Logcat shows "music ducked" and
the riff slot starting and fading. Screenshot the red arena.

- [ ] **Step 5: Run the whole suite, then commit**

```bash
./gradlew testDebugUnitTest
git add -A app/src tools/synth_audio.py audio-final/music/special_riff.ogg
git commit -m "feat(fight): a special turns the arena red under its own riff"
```

### Task 4: The music hardens when a fighter is nearly done

**Files:**
- Modify: `tools/synth_audio.py` (`music_fight_intense`, in `MUSIC` at the end) and commit `audio-final/music/fight_intense.ogg`
- Modify: `app/src/main/java/team/superfun/horrorpets/ui/Screen.kt` (`Screen.Fight.intense: Boolean = false`)
- Modify: `app/src/main/java/team/superfun/horrorpets/ui/GameViewModel.kt` (`onMove` and the PvP turn set `intense`)
- Modify: `app/src/main/java/team/superfun/horrorpets/fight/FightTime.kt` (`fun nearlyDone(s: FightState): Boolean`)
- Modify: wherever `musicTrackFor(state)` lives (`grep -rn "fun musicTrackFor"`): `"fight_intense"` for an intense fight
- Modify: `app/src/main/java/team/superfun/horrorpets/audio/GameAudio.kt` (`music(track, keepPlace)`), `MainActivity.kt`
  (passes `keepPlace = keepsPlace(previous, next)`)
- Create: `app/src/main/java/team/superfun/horrorpets/audio/MusicTracks.kt` (`fun keepsPlace(from: String?, to: String?): Boolean`)
- Test: `FightTimeTest.kt` (`nearlyDone`), `app/src/test/java/team/superfun/horrorpets/audio/MusicTracksTest.kt`,
  `GameViewModelTest.kt` (intense sticks)

**Interfaces:**
- Produces: `nearlyDone(s)` true when `player.hp < player.maxHp * 0.3` or the same for `cpu` (strictly under);
  `keepsPlace("fight", "fight_intense") == true`, false for any other pair; `Screen.Fight.intense`.

- [ ] **Step 1: Write the failing tests**

`FightTimeTest`: `nearlyDoneUnderThirtyPercentOnEitherSide` (29% yes, 30% no, the CPU's side counts).
`MusicTracksTest`: `onlyTheFightTracksKeepTheirPlace`. `GameViewModelTest`, in the fight tests' style: a fight where
the player drops under 30% sets `(screen as Screen.Fight).intense`, and a POTION that heals it above keeps `intense`
true; `musicTrackFor` of that state is `"fight_intense"`.

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew testDebugUnitTest --tests '*FightTimeTest*' --tests '*MusicTracksTest*' --tests '*GameViewModelTest*'`

- [ ] **Step 3: Implement**

- `GameViewModel`: after a turn resolves (CPU and PvP paths), `intense = screen.intense || nearlyDone(turn.state)`.
- `musicTrackFor`: a `Screen.Fight` with `intense` plays `"fight_intense"`.
- `GameAudio.music(track: String?, keepPlace: Boolean = false)`: with `keepPlace`, the incoming slot seeks to the
  outgoing player's `currentPosition` before `prepare()`/play (`player.seekTo(ms)` after `setMediaItem`), and the
  cross-fade is `INTENSE_CROSSFADE_MS = 1500` (a per-slot `fadeMs`, as Task 3 adds). `MainActivity` keeps the previous
  track in a local and passes `keepPlace = keepsPlace(previous, track)`.
- `music_fight_intense()`: copy `music_fight`'s structure with the same `bpm`, `bars`, `L`, `F` and the same bass line
  and chord positions (so the beat lines up when seeking), then: kicks on every beat (not 0 and 2 only), the snare
  doubled with a clap (a second, brighter noise burst), a saw lead an octave above the bass on the off-beats, the
  stabs at 0.14, a noise riser over the last bar of each 16, and the toms every 8 bars. `MUSIC["fight_intense"]`.
  Run `tools/.venv/bin/python -I tools/synth_audio.py --only fight_intense` and commit the .ogg. Assert in a quick
  Python check that `fight.ogg` and `fight_intense.ogg` have the same duration to 0.05 s (ffprobe).

- [ ] **Step 4: Check on the TV emulator**

`fight`, `autoplay on`; when either bar drops under 30% the music hardens without the beat skipping. Logcat: the
cross-fade with a seek.

- [ ] **Step 5: Run the whole suite, then commit**

```bash
./gradlew testDebugUnitTest
git add -A app/src tools/synth_audio.py audio-final/music/fight_intense.ogg
git commit -m "feat(fight): the music hardens, on the beat, when a fighter is nearly done"
```

### Task 5: Haptics keyed to the sounds, and the snore

**Files:**
- Create: `app/src/main/java/team/superfun/horrorpets/audio/Haptics.kt` (`fun hapticFor(sfx: String): LongArray?`)
- Modify: `app/src/main/java/team/superfun/horrorpets/audio/GameAudio.kt` (a `Vibrator`, buzzed as a clip plays)
- Modify: `tools/synth_audio.py` (`snore` in `sfx_all`) and commit `audio-final/sfx/snore.wav`
- Test: `app/src/test/java/team/superfun/horrorpets/audio/HapticsTest.kt`

**Interfaces:**
- Produces: `hapticFor(name)`: `hit` → `[15]`, `heavy_hit` → `[35]`, `crit` → `[60]`, `ko` and `ko_boom` → `[120]`,
  `reel_stop` → `[25]`, `pantry_squelch` → `[40]`, `pantry_poof` → `[20]`, `levelup` → `[40, 60, 40]` (buzz, pause, buzz),
  any `hit_<x>`/`heavy_<x>` → as `hit`/`heavy_hit` (for the weapon sounds to come), else null.

- [ ] **Step 1: Write the failing test** (`HapticsTest`: each row above, plus `hapticFor("ui_move") == null` and the prefixes)

- [ ] **Step 2: Run it to verify it fails**

- [ ] **Step 3: Implement**

`Haptics.kt` as the table. `GameAudio`: `private val vibrator: Vibrator?` from `VibratorManager` (API 31+) or
`Context.VIBRATOR_SERVICE` (as `MainActivity.buzz` does; move that lookup into a shared `vibratorOf(context)` in
`Haptics.kt` and have `MainActivity` use it); in `flushPendingSfx` when a clip plays, `hapticFor(name)?.let(::buzz)`,
where `buzz` builds a `VibrationEffect.createWaveform(pattern-with-leading-0, -1)` on API 26+ and `vibrate(pattern)`
below, inside `runCatching`. No vibrator (every TV): nothing. `snore` in `sfx_all`: 4.0 s, two breaths: in (1.4 s,
lowpass brown noise swelling, 300 Hz) and out (1.2 s, a soft reed buzz: a 70 Hz saw through a 400 Hz lowpass with a
slow 6 Hz tremolo), 0.4 s of quiet between, `loopify`-style fades at both ends so it loops.

- [ ] **Step 4: Check** on the TV emulator that fights log no vibrator errors; run `tools/.venv/bin/python -I tools/synth_audio.py --only sfx` and confirm only `snore.wav` is new.

- [ ] **Step 5: Run the whole suite, then commit**

```bash
./gradlew testDebugUnitTest
git add -A app/src tools/synth_audio.py audio-final/sfx/snore.wav
git commit -m "feat(audio): phone haptics keyed to the sounds; the snore"
```

### Task 6: Docs

**Files:**
- Modify: `README.md` (the VS fights bullet: hit-stop, the knockout, red specials, the music; no tell), `audio-final/CREDITS.md`
  (the new synthesized tracks and sounds), `docs/superpowers/specs/2026-10-24-feel-and-depth-design.md` (mark D and
  the phase-1 parts of E and F done, with any numbers that were tuned by eye).

- [ ] **Step 1: Edit, then commit**

```bash
git add README.md audio-final/CREDITS.md docs/superpowers/specs/2026-10-24-feel-and-depth-design.md
git commit -m "docs: fights that hit"
```
