# The Living Home Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Home changes with the real day (dawn, dusk and night light over the scene) and the killers sleep at night:
slumped, snoring, Zzz rising, with hunger and fun moving at half speed, woken by a press for ten minutes.

**Architecture:** Pure time rules in `game/DayLight.kt` (the hour's light, the sleep hours, how many of an away
stretch were sleep) on `java.util.Calendar` with an injectable `TimeZone` (the app has no java.time desugaring).
`applyElapsed` learns the sleep share. The view model publishes `nowMs` (from the meal-perks plan) and
`awakeUntilMs`; `UiState.asleep` is derived. Home draws a tinted layer under the killer and, asleep, holds the sprite
on its first frame through the fight plan's `SpriteView.timeScale`, slumps it through `SpritePose`, floats Zzz and
loops the snore from the fight plan's `snore.wav`.

**Tech Stack:** Kotlin, Jetpack Compose, java.util.Calendar/TimeZone, JUnit4.

**Spec:** docs/superpowers/specs/2026-10-24-feel-and-depth-design.md, section C.

## Global Constraints

- Depends on: `SpriteView.timeScale` (fights plan, Task 2), `audio-final/sfx/snore.wav` (fights plan, Task 5),
  `UiState.nowMs` (meal-perks plan, Task 3). Run after both plans are merged.
- `GameState.version` stays 2; nothing new is saved (`awakeUntilMs` lives in `UiState`).
- Hours, by the device's local clock (`TimeZone.getDefault()`, injectable): DAWN 5 ≤ h < 7, DAY 7 ≤ h < 18, DUSK
  18 ≤ h < 21, NIGHT otherwise; sleep 23 ≤ h or h < 6. Light: DAWN `#FFB36B` at 0.16, DUSK `#FF6A2A` at 0.22, NIGHT
  `#1B2A6B` at 0.38; DAY none; cross-faded over 2 s. Asleep: hunger and fun move at `Tuning.SLEEP_DECAY_SHARE = 0.5`;
  strength and mood as before. A press wakes for `Tuning.AWAKE_MS = 10 * 60 * 1000`.
- Only Home gets the light and the sleep. The pantry, training, fights, the VS card and the title are unchanged.
- Tests pure-JVM; UI checked on `emulator-5554` with the debug puppet's new `hour N` (fakes the local hour for the
  light and the sleep, like `fake_version` fakes the build) and `advance_hours`.

## Review Focus

1. A stretch away that crosses midnight twice (36 hours from 20:00): `sleepHoursBetween` counts 7 + 7 sleep hours;
   Task 1's test.
2. The clock jumping backwards (`gapMs` already guards): `sleepHoursBetween(from > to)` is 0, never negative; Task 1.
3. A press at 23:30 wakes until 23:40; at 23:41 the killer is asleep again; at 06:00 it is awake with no press;
   Task 3's view-model test on `asleep`.
4. The FEED press while asleep opens the pantry at once (awake), and the pantry's killer is not slumped; Task 3's
   emulator check.
5. The welcome-back toast still says the whole stretch ("Away 13h"), not only the awake part; Task 1 leaves
   `summarize` alone and its test pins the toast.

---

### Task 1: The day's hours

**Files:**
- Create: `app/src/main/java/team/superfun/horrorpets/game/DayLight.kt`
- Modify: `app/src/main/java/team/superfun/horrorpets/game/Tuning.kt` (`SLEEP_DECAY_SHARE = 0.5`, `AWAKE_MS = 600_000L`)
- Modify: `app/src/main/java/team/superfun/horrorpets/game/Decay.kt` (`applyElapsed(state, nowMs, floorMs, zone)`; `decay(p, hours, sleepHours)`)
- Modify: `app/src/main/java/team/superfun/horrorpets/ui/GameViewModel.kt` (passes its `zone`; a `zone: TimeZone = TimeZone.getDefault()` constructor parameter)
- Test: `app/src/test/java/team/superfun/horrorpets/game/DayLightTest.kt`, additions to `DecayTest.kt`

**Interfaces:**
- `enum class DayLight { DAWN, DAY, DUSK, NIGHT }`; `fun dayLightAt(hour: Int): DayLight`; `fun isSleepHour(hour: Int): Boolean`;
  `fun localHour(nowMs: Long, zone: TimeZone): Int` (0..23); `fun sleepHoursBetween(fromMs: Long, toMs: Long, zone: TimeZone): Double`
  — the hours of `[fromMs, toMs)` that fall in sleep hours, walking hour by hour (minute precision is enough: count
  per whole local hour, with the partial first and last hours pro-rated); 0.0 when `toMs <= fromMs`.
- `applyElapsed(..., zone: TimeZone = TimeZone.getDefault())`: `decay(p, hours, sleepHours)` applies
  `HUNGER_PER_HOUR * (hours - sleepHours * (1 - SLEEP_DECAY_SHARE))` and the same for fun; strength and mood use `hours`.

- [ ] **Step 1: Write the failing tests**

`DayLightTest`: `theHoursHaveTheirLight` (4 → NIGHT, 5 → DAWN, 6 → DAWN, 7 → DAY, 17 → DAY, 18 → DUSK, 20 → DUSK,
21 → NIGHT, 23 → NIGHT); `sleepIsElevenToSix` (22 no, 23 yes, 0 yes, 5 yes, 6 no); `localHourFollowsTheZone`
(an epoch ms that is 03:00 UTC is 03 in UTC and 22 the day before in "America/Chicago"); `sleepHoursAcrossMidnight`
(UTC, 22:00 → 08:00 = 7.0; 20:00 → 20:00 + 36 h = 14.0; 23:30 → 00:30 = 1.0; 12:00 → 13:00 = 0.0; from after to = 0.0).
`DecayTest`: `asleepTheHungerGrowsAtHalfSpeed` (10 hours all in sleep: hunger rises by `HUNGER_PER_HOUR * 5`;
fun falls by `FUN_PER_HOUR * 5`; strength falls by the full 10 hours' worth); `theWelcomeBackCountsTheWholeStretch`
(the `AwaySummary` still reports the full elapsed time).

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew testDebugUnitTest --tests '*DayLightTest*' --tests '*DecayTest*'`

- [ ] **Step 3: Implement** as the Interfaces say (`Calendar.getInstance(zone)` for the hour; keep `summarize` as it is).

- [ ] **Step 4: Run the whole suite**: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A app/src
git commit -m "feat(home): the day's hours: dawn, dusk, night and the sleep hours, which halve the hunger"
```

### Task 2: The light over the scene

**Files:**
- Create: `app/src/main/java/team/superfun/horrorpets/ui/home/SceneLight.kt` (`data class SceneLight(val color: Color, val alpha: Float)`, `fun sceneLightFor(light: DayLight): SceneLight?`)
- Modify: `app/src/main/java/team/superfun/horrorpets/ui/home/HomeScreen.kt` (the layer under the killer; the killer's tint blended toward it)
- Modify: `app/src/main/java/team/superfun/horrorpets/ui/GameViewModel.kt` (`UiState.hour: Int`, set with `nowMs` from `hourOf(nowMs)`; a `hourOf: (Long) -> Int` constructor parameter defaulting to `localHour(it, zone)`)
- Modify: `app/src/debug/java/team/superfun/horrorpets/puppet/` (`hour N` sets `Puppet.current.fakeHour`; `MainActivity` builds the view model with `hourOf = { Puppet.current.fakeHour ?: localHour(it, zone) }`, as `fakeVersion` is wired)
- Test: `app/src/test/java/team/superfun/horrorpets/ui/home/SceneLightTest.kt` (the table; DAY is null)

- [ ] **Step 1: Write the failing test**, **Step 2: run it**, **Step 3: implement**:

Inside `SceneBackdrop`'s content, before the `SpriteView`: a `Spacer(Modifier.fillMaxSize().drawBehind { drawRect(light) })`
where `light` is `animateColorAsState(target = sceneLightFor(dayLightAt(state.hour))?.let { it.color.copy(alpha = it.alpha) } ?: Color.Transparent, tween(2000))`.
The killer's `tint` becomes `lerp(sceneTint, lightColor, 0.5f * lightAlpha)` so it sits in the same light. The HUD
(stat card, bar, badge) stays over it, unlit.

- [ ] **Step 4: Check on the TV emulator**: `hour 22`, `hour 5`, `hour 19`, `hour 12`: night, dawn, dusk, none; the
change fades over two seconds. Screenshots of night and dusk.

- [ ] **Step 5: Run the whole suite, then commit** `feat(home): dawn, dusk and night over the scene`.

### Task 3: Sleep

**Files:**
- Modify: `app/src/main/java/team/superfun/horrorpets/game/Status.kt` (`StatusTag.ASLEEP`; `statusOf(p, asleep: Boolean = false)`: STARVING > ASLEEP > GRUMPY > HUNGRY > BORED > CONTENT)
- Modify: `app/src/main/java/team/superfun/horrorpets/ui/Screen.kt` (`UiState.awakeUntilMs: Long = 0L`, `UiState.wakeLine: Pair<String, Long>? = null`; `fun UiState.asleep(): Boolean = isSleepHour(hour) && nowMs >= awakeUntilMs`)
- Modify: `app/src/main/java/team/superfun/horrorpets/ui/GameViewModel.kt` (`wake()` on `onCare`, `onWalk`, `onFightPressed`, `onMoves`, the stable opening: `awakeUntilMs = nowMs + AWAKE_MS` and, when it was asleep, `wakeLine = SLEEPY_LINES.random(rng) to nextToken++`; the press then proceeds)
- Create: `app/src/main/java/team/superfun/horrorpets/ui/home/SleepZs.kt` (the rising Zzz)
- Modify: `app/src/main/java/team/superfun/horrorpets/ui/home/HomeScreen.kt` (asleep: `timeScale = { 0f }` on the sprite, the slump through `pose` (swing 12° toward the floor, lift −5% of the pet's height, eased over 600 ms; reset on waking), `SleepZs` over the head, the snore loop, the wake line in the speech bubble), `StatCard.kt` (`statusOf(pet, state.asleep())`)
- Test: `StatusTest` (or where `statusOf` is tested) for the priority; `GameViewModelTest`: `aPressWakesTheKillerForTenMinutes`, `atSixItWakesByItself`; `UiStateTest` for `asleep()`

**Interfaces:**
- `SLEEPY_LINES = listOf("Five more minutes.", "...what time is it?", "I was dreaming about camp.", "Can't a killer sleep?")`.
- `SleepZs(headX: () -> Float, headY: () -> Float, modifier)`: every 1.6 s a "Z" in the HUD's display font at 26 sp
  rises 40 dp over 2 s while fading, drifting 8 dp to the right; at most three on screen.

- [ ] **Step 1: Write the failing tests**, **Step 2: run them**, **Step 3: implement**:

The snore: `LaunchedEffect(asleep) { while (asleep) { sounds.sfx("snore"); delay(4_000) } }`. The slump and the hold
apply only to the Home sprite (`SpriteView(timeScale = { if (asleep) 0f else 1f })`); a gag, walk or variety never
starts while asleep (the variety timer waits). The wake line shows in the bubble for `BUBBLE_MS` when its token
changes, as the refusal does.

- [ ] **Step 4: Check on the TV emulator**: `hour 23`: the killer slumps and holds, Zs rise, the snore loops, the tag
says ASLEEP; press PLAY: a sleepy line, it sits up and the gag plays; `hour 6`: awake. Screenshots asleep and waking.

- [ ] **Step 5: Run the whole suite, then commit** `feat(home): the killers sleep at night`.

### Task 4: Docs

- [ ] README's "Real-time care" bullet (the light; sleep; half speed), the puppet table (`hour N`), the spec's C marked
  done. Commit `docs: the living Home`.
