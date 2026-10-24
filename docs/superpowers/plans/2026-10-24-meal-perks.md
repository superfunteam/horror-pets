# Meal Perks and the Pantry That Teaches Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The last thing a killer ate gives it one status (a buff, or a hangover) that the player sees everywhere
and feels in the next CPU fights; and the pantry teaches its own logic (what a way to cook does, what a dish tastes
like, what this killer has turned out to love or hate) without giving a recipe away.

**Architecture:** `PetState` remembers the last meal (id and time) and the flavors the killer has reacted to. A pure
`mealStatusOf(pet, nowMs)` derives the status; `feed()` records the meal and reports the status it gave; `buildFighter`
carries it into the engine as `Fighter.status`, applied in four places with `FightTuning` numbers. The UI reads
`mealStatusOf` with `UiState.nowMs` (the view model's clock, published on every tick) and shows one chip style
everywhere. The pantry's hints are three pure tables with tests.

**Tech Stack:** Kotlin, Jetpack Compose, kotlinx.serialization, NumPy (tools/synth_audio.py), JUnit4.

**Spec:** docs/superpowers/specs/2026-10-24-feel-and-depth-design.md, sections A and G.

## Global Constraints

- `GameState.version` stays 2. New `PetState` fields go last, in this order and with these defaults:
  `lastMeal: String? = null`, `lastMealAtMs: Long? = null`, `tastesLearned: Set<String> = emptySet()`. `SaveCodec`
  decodes them tolerantly and sanitises them (an unknown item id or flavor name is dropped).
- Numbers (all in `FightTuning` unless said): `PERK_DAMAGE_UP = 1.15`, `PERK_DAMAGE_DOWN = 0.9`, `PERK_CRIT_BONUS = 0.10`,
  `PERK_MISS_BONUS = 0.15`, `PERK_START_METER = 50`, `PERK_FEAST_POTIONS = 3`; `Tuning.MEAL_PERK_HOURS = 24`.
- The status applies to the player's fighter in CPU fights only. `buildOpponent`, `pvpFighter`, `PvpRelay.wire` and
  the relay never see it. PvP's dice and wire format are unchanged.
- Copy: the statuses are BEEFED UP, SUGAR RUSH, IRON STOMACH, FAVORITE FEAST and HANGOVER; their one-line meanings are
  "+15% DAMAGE", "METER STARTS HALF FULL", "+10% CRIT CHANCE", "A THIRD POTION" (after its flavor's line) and
  "MISSES MORE, HITS SOFTER".
- Tests are pure-JVM JUnit4 under `app/src/test/`; the suite must stay green. UI is checked on `emulator-5554` (the
  TV) with the debug puppet (`feed`, `stock <id>`, `serve <id>`, `fight`, `status`); never touch 192.168.1.42:5555.
- The secret killer is never named in README copy or notes.

## Review Focus

1. A save from 0.8.2 (no new fields): every pet loads with no status and no tastes learned; Task 1's codec test.
2. A hated dish clears an old status rather than keeping it; a raw food too; Task 1's `mealStatusOf` tests pin both.
3. The status chip must never show on the CPU's side of the HUD or on the opponent's half of the VS card; Task 3's
   implementer checks a fight on the emulator against a hungover killer.
4. The 24-hour expiry: `mealStatusOf` at exactly `MEAL_PERK_HOURS` later is null; the chip on Home follows
   `UiState.nowMs`, which the tick refreshes, so a status left overnight is gone in the morning without a restart.
5. The pantry's taste line must only name flavors the killer has actually reacted to (loved or hated), never the
   `Taste` table's secrets; Task 4's `tastesLine` test feeds a `tastesLearned` that lacks the hated flavor.

---

### Task 1: The model remembers meals and tastes

**Files:**
- Create: `app/src/main/java/team/superfun/horrorpets/game/pantry/MealStatus.kt`
- Modify: `app/src/main/java/team/superfun/horrorpets/model/Model.kt` (`PetState` fields, KDoc)
- Modify: `app/src/main/java/team/superfun/horrorpets/game/Tuning.kt` (`MEAL_PERK_HOURS = 24`)
- Modify: `app/src/main/java/team/superfun/horrorpets/game/pantry/Feeding.kt` (`feed(pet, item, nowMs)` records the meal and the tastes; `FeedOutcome.status`)
- Modify: `app/src/main/java/team/superfun/horrorpets/data/SaveCodec.kt` (`sanitize`: `lastMeal` kept only when `pantryItemById(it) != null`, `tastesLearned` filtered to `Flavor` names)
- Modify: `app/src/main/java/team/superfun/horrorpets/ui/GameViewModel.kt` (`onPantryFeed` passes `clock.nowMs()`; every other `feed(` caller too: `grep -rn "feed(" app/src`)
- Test: `app/src/test/java/team/superfun/horrorpets/game/pantry/MealStatusTest.kt`, additions to `FeedingTest.kt` and `SaveCodecTest.kt`

**Interfaces:**
- Produces, in `MealStatus.kt`:
  ```kotlin
  enum class MealPerk(val title: String, val meaning: String) {
      BEEFED_UP("Beefed up", "+15% damage"), SUGAR_RUSH("Sugar rush", "Meter starts half full"),
      IRON_STOMACH("Iron stomach", "+10% crit chance"), HANGOVER("Hangover", "Misses more, hits softer"),
  }
  /** [perk], and whether it was the killer's favorite dish ([feast]: a third potion on top). */
  data class MealStatus(val perk: MealPerk, val feast: Boolean = false) { val title: String get() = if (feast) "Favorite feast" else perk.title }
  fun mealStatusOf(pet: PetState, nowMs: Long): MealStatus?
  ```
  `mealStatusOf`: null when `lastMeal` or `lastMealAtMs` is null, when `nowMs - lastMealAtMs >= MEAL_PERK_HOURS * 3_600_000L`,
  or when the item is unknown, a FOOD or a METHOD. A BOTCHED item is `MealStatus(HANGOVER)`. A dish (SNACK or MEAL):
  `taste = tasteOf(pet.id)`; the favorite is `MealStatus(perkOf(item.flavor), feast = true)`; a dish in `taste.hates`
  is null; else `MealStatus(perkOf(flavor))` with SAVORY → BEEFED_UP, SWEET → SUGAR_RUSH, GROSS → IRON_STOMACH.
- `feed(pet: PetState, item: PantryItem, nowMs: Long): FeedOutcome`: the returned pet has `lastMeal = item.id`,
  `lastMealAtMs = nowMs`, and `tastesLearned` grown by `item.flavor!!.name` when the reaction is LOVED, HATED or
  FAVORITE; `FeedOutcome.status: MealStatus? = mealStatusOf(outcome.pet, nowMs)` (the status this meal left, null when none).

- [ ] **Step 1: Write the failing tests**

`MealStatusTest` (use real items: `STEAK` is savory, `MILKSHAKE` sweet, `WORM_SPAGHETTI` gross, `PILE_OF_SLOP` a
botch, `RAW_STEAK` a food, `GRILL` a method; pick a killer whose `tasteOf` hates GROSS and whose favorite you read
from `tasteOf`): `aSavoryDishBeefsUp`, `aSweetDishIsASugarRush`, `aGrossDishIsAnIronStomachUnlessHated` (the hater
gets null), `theFavoriteIsAFeastOfItsFlavor`, `anyBotchIsAHangover` (slop, KITCHEN_FIRE, a named botch),
`rawFoodAndWaysToCookLeaveNoStatus`, `aStatusLastsADayNotLonger` (at `24h - 1 ms` present, at `24h` null),
`noMealNoStatus`. `FeedingTest`: `feedingRecordsTheMealAndTheTime`, `aLovedOrHatedFlavorIsLearnedAShruggedOneIsNot`
(a raw food teaches nothing; the favorite teaches its flavor). `SaveCodecTest`: `mealFieldsRoundTripAndAreCleaned`
(round trip; `"lastMeal":"nonsense"` reads null; `"tastesLearned":["SWEET","salty"]` reads `{SWEET}`; a 0.8.2 pet
with no fields reads the defaults).

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew testDebugUnitTest --tests '*MealStatusTest*' --tests '*FeedingTest*' --tests '*SaveCodecTest*'`

- [ ] **Step 3: Implement** as the Interfaces say. Update `feed`'s KDoc and `PetState`'s.

- [ ] **Step 4: Run the whole suite** (`./gradlew testDebugUnitTest`): PASS.

- [ ] **Step 5: Commit**

```bash
git add -A app/src
git commit -m "feat(pantry): the last meal and the tastes learned are remembered, and the meal's status derived"
```

### Task 2: The status in the fight engine

**Files:**
- Modify: `app/src/main/java/team/superfun/horrorpets/fight/FightModel.kt` (`Fighter.status: MealStatus? = null`)
- Modify: `app/src/main/java/team/superfun/horrorpets/fight/FightTuning.kt` (the six `PERK_*` constants)
- Modify: `app/src/main/java/team/superfun/horrorpets/fight/FightEngine.kt` (`buildFighter(p, status)`, `startFight(player, opponent, rng, status)`, the four applications)
- Modify: `app/src/main/java/team/superfun/horrorpets/ui/GameViewModel.kt` (CPU fights pass `mealStatusOf(pet, clock.nowMs())`; PvP passes nothing)
- Test: `app/src/test/java/team/superfun/horrorpets/fight/FightEngineTest.kt` (or a new `MealStatusInFightTest.kt` beside it)

**Interfaces:**
- `fun buildFighter(p: PetState, status: MealStatus? = null): Fighter` — `meter = if (status?.perk == SUGAR_RUSH) PERK_START_METER else 0`, `status = status`.
- `fun startFight(player: PetState, opponent: PetId, rng: Random, status: MealStatus? = null): FightState` —
  `playerPotions = if (status?.feast == true) PERK_FEAST_POTIONS else POTIONS_PER_FIGHT`.
- In the engine: `damageOf` multiplies by `PERK_DAMAGE_UP` for BEEFED_UP and `PERK_DAMAGE_DOWN` for HANGOVER (the
  attacker's status, before the final rounding); `critChance` adds `PERK_CRIT_BONUS` for IRON_STOMACH; `missChance`
  adds `PERK_MISS_BONUS` for the attacker's HANGOVER on STRIKE and HEAVY (never counters or specials). The dice order
  is unchanged.

- [ ] **Step 1: Write the failing tests**

With a fixed seed, resolve the same STRIKE turn with and without BEEFED_UP: the damage is `round(x * 1.15)` of the
plain one (compute from the plain result; at least greater). HANGOVER: over 2000 seeded turns the miss count is
about 0.15 higher as a share (use the suite's statistical tolerance style, see `CpuBrainTest`). IRON_STOMACH: the crit
share rises by about 0.10. SUGAR_RUSH: `buildFighter(pet, SUGAR_RUSH).meter == 50`. FAVORITE FEAST:
`startFight(..., MealStatus(BEEFED_UP, feast = true)).playerPotions == 3`. The CPU's fighter never carries a status.
`PvpRelay`: a test that `wire` of a fighter with a status equals `wire` of the same fighter without (add it to the
PvP tests if there is a unit test on `WireFighter`; else assert `WireFighter` has no status field by reading it).

- [ ] **Step 2: Run them to verify they fail**

- [ ] **Step 3: Implement**, including the view model's two call sites (`startFight` for a CPU fight; PvP untouched).

- [ ] **Step 4: Run the whole suite**: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A app/src
git commit -m "feat(fight): the meal's status fights too: damage, crits, misses, the meter and a third potion"
```

### Task 3: The status on show: the reaction, Home, the VS card, the HUD

**Files:**
- Modify: `app/src/main/java/team/superfun/horrorpets/ui/Screen.kt` (`FedShow.status: MealStatus? = null`; `UiState.nowMs: Long = 0L`)
- Modify: `app/src/main/java/team/superfun/horrorpets/ui/GameViewModel.kt` (`nowMs` set in `start`, `onResume`, `tick` and after a feed; `FedShow.status`)
- Create: `app/src/main/java/team/superfun/horrorpets/ui/common/StatusChip.kt` (the shared chip: the stat card's outlined tag, in the status's colour)
- Modify: `app/src/main/java/team/superfun/horrorpets/ui/pantry/Reaction.kt` (`FeedStage.status`; a banner after the gains, with its sound), `PantryScreen.kt` (nothing else)
- Modify: `app/src/main/java/team/superfun/horrorpets/ui/home/StatCard.kt` (the chip under the status tag), `HomeScreen.kt` (the hangover sway)
- Modify: `app/src/main/java/team/superfun/horrorpets/ui/fight/VersusScreen.kt` (the chip under the player's name), `FightHud.kt` (the chip first in the player's status row)
- Modify: `tools/synth_audio.py` (`perk_up`, `hangover` in `sfx_all`) and commit the two `.wav`
- Test: `app/src/test/java/team/superfun/horrorpets/ui/common/StatusChipTest.kt` (the pure parts)

**Interfaces:**
- `StatusChip.kt`: `fun statusChipColor(status: MealStatus): Color` — gold (`Amber`) for BEEFED_UP and a feast, pink
  `#FF7AB8` for SUGAR_RUSH, green `#8BE37A` for IRON_STOMACH, `BloodGlow` for HANGOVER; `fun statusBanner(status): Pair<String, String>`
  — the title in capitals with "!" ("BEEFED UP!", "FAVORITE FEAST!", "HANGOVER..." with dots, not a bang) and the
  meaning in capitals (a feast's: "<its flavor's meaning> · A THIRD POTION"); `fun statusSound(status): String` —
  `"hangover"` for HANGOVER, else `"perk_up"`. `@Composable fun StatusChip(status: MealStatus, modifier)`: the outlined
  tag drawn as `StatusTagBox` in StatCard.kt (make that drawing a shared `TagBox(text, color)` in `StatusChip.kt`
  and have `StatusTagBox` use it).

- [ ] **Step 1: Write the failing test** (`StatusChipTest`: the colours, the banner strings for all five, the sound).

- [ ] **Step 2: Run it to verify it fails**

- [ ] **Step 3: Implement**

- The reaction: `playFeedShow` sets `stage.status = fed.status` 400 ms after the gains land and plays its sound;
  `GainsReadout` draws the banner under the gains: the title at 34 sp in the status colour with the glow the
  level-up has, the meaning at 16 sp in `TextLo` beneath; it pops in like the level-up words. With both a level-up
  and a status, the level-up comes first, the status 400 ms later.
- Home: `StatCard` shows `StatusChip` right under the status tag when `mealStatusOf(pet, state.nowMs)` is not null.
  `HomeScreen`: while that status is HANGOVER and no gag is playing, the idle pose's `swing` rocks ±3° on a 2.4 s
  sine (`SpritePose.swing`, already a rotation about the head); it stops (snaps to 0) when a gag or a walk starts.
- VS card: the chip under the player's name lettering on the player's half, sized like the HUD's; nothing on the
  opponent's half, nothing in PvP (`Screen.Versus.pvp`).
- HUD: `SideHud` for the player puts `StatusChip` first in the status row (before combo/bleed/dazed), from
  `Fighter.status`; the CPU's row is unchanged.
- `UiState.nowMs`: the view model sets it from `clock.nowMs()` in `start()`, `onResume()`, `tick()` and when a meal
  is fed; the puppet's `status` prints the status name when there is one.
- Sounds: `perk_up` (0.6 s: three rising bell notes E5 G5 B5 at 90 ms apart with a soft noise sparkle) and
  `hangover` (0.8 s: a low saw wobbling down from 160 to 90 Hz with a 5 Hz tremolo and a burp of bandpassed noise at
  the end), in `sfx_all`; render with `--only sfx`, confirm only the two new files changed.

- [ ] **Step 4: Check on the TV emulator**

`feed`, `stock steak`, `serve steak`: "BEEFED UP!" with its line and chime under the gains; Home shows the chip;
`fight`: the chip beside the player's name in the HUD and on the VS card, never on the CPU's side. `stock
pile_of_slop`, `serve pile_of_slop`: "HANGOVER..." with the sour sound, the chip in red, the killer swaying on Home.
Screenshots of the banner, Home and the HUD.

- [ ] **Step 5: Run the whole suite, then commit**

```bash
./gradlew testDebugUnitTest
git add -A app/src tools/synth_audio.py audio-final/sfx/perk_up.wav audio-final/sfx/hangover.wav
git commit -m "feat(pantry): the meal's status on show: the reaction, Home, the VS card and the HUD"
```

### Task 4: The pantry teaches

**Files:**
- Modify: `app/src/main/java/team/superfun/horrorpets/game/pantry/Cookbook.kt` (`methodHint`, `flavorMark`)
- Create: `app/src/main/java/team/superfun/horrorpets/ui/pantry/Tastes.kt` (`tastesLine`)
- Modify: `app/src/main/java/team/superfun/horrorpets/ui/pantry/Board.kt` (`ItemName` takes an optional second line), `PantryScreen.kt` (passes the hint for a METHOD on the board and the focused shelf item's tag; the tastes line in the header), `Shelf.kt` (`ShelfLabel` draws the flavor mark)
- Test: `CookbookTest.kt` additions, `app/src/test/java/team/superfun/horrorpets/ui/pantry/TastesTest.kt`

**Interfaces:**
- `fun methodHint(item: PantryItem): String?` — GRILL "grills, fries and sears", CAMPFIRE "roasts, toasts and pops",
  POT "boils, poaches and simmers", CAULDRON "brews potions", SUN "dries, jerks and rots"; null for anything else.
- `enum class FlavorMark { DROP, STAR, BLOT }` and `fun flavorMark(item: PantryItem): FlavorMark?` — SAVORY → DROP,
  SWEET → STAR, GROSS → BLOT, for SNACK and MEAL only (a raw food has a flavor too, but its mark would spoil the
  two-foods snacks: no mark).
- `fun tastesLine(pet: PetState): String?` — from `tastesLearned ∩ {taste.loves, taste.hates}`: "LOVES SWEET",
  "HATES GROSS" or "LOVES SWEET · HATES GROSS"; null when neither is learned. The "fine" flavor is never named.

- [ ] **Step 1: Write the failing tests**

`CookbookTest`: `everyWayToCookHasAHintAndAtLeastFourRecipes`, `onlyDishesWearAFlavorMark`. `TastesTest`: the three
strings, null when nothing learned, and a `tastesLearned` holding only the fine flavor gives null.

- [ ] **Step 2: Run them to verify they fail**

- [ ] **Step 3: Implement**

- `ItemName(title, hint: String? = null, ...)`: the hint as a second line at 11 sp in `TrainingGold`, the plate
  grown to fit; `PantryScreen` passes `methodHint(item)` for the board's items, and the focused shelf item's label
  (TV focus) shows the hint as its second line in place of a long name's wrap (the label already wraps to two lines:
  for a METHOD, the title is one line, so the hint takes the second; keep the label's width rules).
- `ShelfLabel`: a 9 dp mark drawn at the plate's left edge, before the text (`drawBehind` in the label plate: a red
  teardrop, a pink four-point star, a green blot of three overlapping circles), the text inset by its width; faded
  with the label when on the board.
- The header: a fifth line under RECIPES, `tastesLine(pet)`, in `TrainingGold`, only when it is not null; it hides
  under the speech bubble as the RECIPES line does.

- [ ] **Step 4: Check on the TV emulator**: focus the cauldron: "CAULDRON / BREWS POTIONS" on its label; a steak on the
shelf wears a red drop; after feeding a loved dish the header reads "LOVES SAVORY". Screenshot.

- [ ] **Step 5: Run the whole suite, then commit**

```bash
./gradlew testDebugUnitTest
git add -A app/src
git commit -m "feat(pantry): ways to cook say what they do, dishes wear their flavor, tastes learned are remembered"
```

### Task 5: Docs

- [ ] README's pantry bullet (statuses; what the pantry now says) and the VS fights bullet (the status fights too);
  `audio-final/CREDITS.md` (the two sounds); the spec's A and G marked done. Commit `docs: meal perks and the pantry that teaches`.
