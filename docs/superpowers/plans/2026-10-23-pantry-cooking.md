# Pantry Cooking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** FEED opens a horror-kitchen pantry where the player cooks items two at a time (Elements-style) into snacks
and meals and feeds one to the killer, who reacts to its taste and pays out by tier, flavor and secret favorite.

**Architecture:** The cookbook (102 items, recipes, tastes) and the feeding rules (payouts, reactions, top-up) are
plain Kotlin under `model/` and `game/pantry/`, unit tested. The pantry's contents are saved in `GameState`; the view
model opens a new `Screen.Pantry`, applies each cook and feed the moment it happens, and the screen under `ui/pantry/`
only animates what the view model already decided. Assets come from Python tools run by the controller.

**Tech Stack:** Kotlin, Jetpack Compose (TV + phone), kotlinx.serialization (save), JUnit4 pure-JVM tests, Python
tools (OpenAI images, ElevenLabs sounds, Pillow) for assets.

**Spec:** `docs/superpowers/specs/2026-10-23-pantry-cooking-design.md` (read it first: the cookbook tables, the
payout table and the reaction lines are there, exactly; mockup A in `art-work/cooking/mockups/A-stovetop.jpg`).

## Global Constraints

- Paths below are relative to `app/src/main/java/team/superfun/horrorpets/` unless they start with `app/`, `tools/`,
  `sprites-final/`, `scenes-final/`, `audio-final/`, `README` or `docs/`.
- Do not bump `GameState.version` (2). New fields go last in their data class with a default.
- A top-level `GameState` field that fails to decode makes `SaveCodec` drop the whole save: the pantry must be read
  on its own (like `pets`), so a damaged pantry becomes an empty one and nothing else is lost.
- Item ids are save keys and art names: `PantryItem.name.lowercase()` (`raw_steak`, `eye_of_newt_potion`, …), the
  same ids `tools/generate_pantry_art.py` uses. Never rename one once shipped.
- Unit tests are pure JVM JUnit4 (no Robolectric, no Compose test rule). Rules live outside composables.
- Playable with a TV remote (D-pad, OK, Back) and touch; `isTouchInput()` decides help text only, never which inputs
  work. Screens lay out in dp on a 540 dp-tall design; HUD text and the help strip respect `safeInset()`.
- Sounds: 16-bit PCM mono 44.1 kHz WAV in `audio-final/sfx/`, loaded by name; existing sounds must not change.
- API keys never reach a file, a commit or a subagent: the asset tasks (1) are the controller's.
- Tests: `./gradlew -q testDebugUnitTest testReleaseUnitTest`; compile: `./gradlew -q assembleDebug compileReleaseKotlin`.
- Commit after each task on branch `pantry-cooking`, ending the message with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Never commit the untracked `audio/` folder.
- Test on the emulators only (`-s emulator-5554` TV, `emulator-5556`/`5558` phones); never touch the real TV.

## Review Focus

1. COOK pressed twice, or OK held, on the same board: the cook happens once and uses its inputs once — Task 4 test
   `cookTwiceCooksOnce`.
2. A stale pantry session acting on a newer one (FEED → Back → FEED within a frame; the puppet does this) — Task 4
   test `aStaleSessionCannotCookOrFeed`.
3. A damaged save: pantry of the wrong type, unknown ids, too many items — the pets survive and the pantry is cleaned
   — Task 3 tests.
4. Shelf focus after the shelf shrinks (cooking two into one, feeding the last item, the focused item cooked away):
   focus lands on a real item, never past the end — Task 5 test `focusStaysOnTheShelfWhenItShrinks`.
5. A level-up from feeding: after the reaction, Home offers the new attack (Learn) as any level-up does — Task 4 test
   `aLevelUpFromFeedingOffersTheNewAttack`.

---

### Task 1: Assets (controller)

**Files:**
- Create: `tools/generate_pantry_art.py` (written), `tools/finalize_pantry_art.py`,
  `sprites-final/pantry/<id>.webp` × 102, `scenes-final/pantry/kitchen.webp`, `sprites-final/pantry/board.webp`
- Modify: `tools/generate_game_sfx.py` (append), `audio-final/sfx/` (new files only)

**Interfaces:**
- Produces: one sprite per item at `sprites/pantry/<id>.webp` (longer side 384 px, trimmed, transparent); the kitchen
  backdrop at `scenes/pantry/kitchen.webp` (1920×1280 like the scenes) and its floor line as a fraction of the art's
  height, reported for Task 5 as `KITCHEN_FLOOR`; the cutting board at `sprites/pantry/board.webp`.
- Produces sounds (names Tasks 5–6 play): `pantry_pick`, `pantry_poof`, `pantry_squelch`, `cook_grill`,
  `cook_campfire`, `cook_pot`, `cook_cauldron`, `cook_sun`, `eat_happy`, `eat_gross`, `favorite_fanfare`.

- [ ] Generate every item (`--n 1`), review contact sheets, regenerate any that read badly or carry text.
- [ ] `finalize_pantry_art.py` (like `finalize_food.py`: trim to alpha, 3 % margin, longer side 384, WEBP q90).
- [ ] Kitchen backdrop with `tools/generate_scenes.py`'s style and size: a horror kitchen at night, an open floor in
  front for the killer and the board, nothing on the floor; pick, finalize, measure `KITCHEN_FLOOR`.
- [ ] The cutting board (a chunky wooden board, top-down three-quarter view, transparent).
- [ ] Sounds via ElevenLabs, appended to `generate_game_sfx.py`; check levels against `food_drop` and `slash`.
- [ ] Commit (`feat(pantry): art and sounds`).

### Task 2: The cookbook

**Files:**
- Create: `model/Pantry.kt`, `game/pantry/Cookbook.kt`
- Test: `app/src/test/java/team/superfun/horrorpets/game/pantry/CookbookTest.kt`

**Interfaces:**
- Produces (`model/Pantry.kt`):
  - `enum class ItemKind { METHOD, FOOD, SNACK, MEAL, BOTCHED }`, `enum class Flavor { SAVORY, SWEET, GROSS }`
  - `enum class PantryItem(val title: String, val kind: ItemKind, val flavor: Flavor?)` — all 102 items of the spec's
    cookbook in its order (ways to cook, foods, snacks, meals, botches), titles exactly as the spec writes them
    ("Caviar Canapés", "Witch's Supper", "Campfire S'mores"); flavor null for METHOD and BOTCHED. `val id: String`
    (`name.lowercase()`), `val raw: Boolean` (METHOD or FOOD).
  - `fun pantryItemById(id: String): PantryItem?`
- Produces (`game/pantry/Cookbook.kt`):
  - `sealed interface CookResult { data class Cooked(val item: PantryItem); data object Refused }`
  - `fun cook(a: PantryItem, b: PantryItem): CookResult` — in this order: Refused if either is BOTCHED; the recipe or
    named botch for the unordered pair; KITCHEN_FIRE if both are METHOD; BURNT_TO_A_CRISP if one is METHOD and the other
    SNACK or MEAL; else PILE_OF_SLOP.
  - `val RECIPES: Map<Set<PantryItem>, PantryItem>` (the spec's snacks, meals and named botches; read by tests)
  - `data class Taste(val loves: Flavor, val fine: Flavor, val hates: Flavor, val favorite: PantryItem)`,
    `fun tasteOf(pet: PetId): Taste` (the spec's table).

- [ ] **Step 1: Failing tests** in `CookbookTest`:
  - `theCookbookHasEveryItem`: counts by kind 5/21/41/23/12; ids unique; every METHOD and BOTCHED has no flavor,
    every other item has one.
  - `everyRecipeMatchesTheSpec`: a table in the test of all 64 snack/meal recipes and the 9 pair-named botches
    (inputs → output) from the spec, each equal to `cook(a, b)` and `cook(b, a)`.
  - `methodsChangeTheDish`: Egg + Grill = Fried Egg, Egg + Pot = Boiled Egg, Egg + Sun = Rotten Egg; Raw Steak + Grill
    = Steak, Raw Steak + Sun = Jerky.
  - `theRulesForEverythingElse`: Grill + Sun = Kitchen Fire; Steak + Campfire = Burnt to a Crisp; Batter + Grill =
    Pancake Stack (a recipe beats the burnt rule); Batter + Cauldron = Cursed Cake; Egg + Egg = Pile of Slop;
    Cheese + Worms = Pile of Slop; Steak Dinner + Pizza = Pile of Slop.
  - `nothingCooksWithABotch`: every BOTCHED item with every item → `Refused`.
  - `everySnackAndMealCanBeMadeFromRawIngredients`: each SNACK/MEAL is some recipe's output, reachable from raw items.
  - `everyFavoriteTakesExactlyFourIngredients`: for each pet, `tasteOf(pet).favorite` is a MEAL whose recipe tree has
    exactly four raw leaves, and the favorites match the spec's table.
- [ ] **Step 2: Run** `./gradlew -q testDebugUnitTest --tests '*CookbookTest'`, expect FAIL.
- [ ] **Step 3: Implement** the Interfaces.
- [ ] **Step 4: Run**, expect PASS. **Step 5: Commit** (`feat(pantry): the cookbook`).

### Task 3: Feeding, the top-up and the save

**Files:**
- Create: `game/pantry/Feeding.kt`
- Modify: `game/Tuning.kt`, `model/Model.kt` (`GameState.pantry`, `PetState.favoriteFound`), `data/SaveCodec.kt`
- Test: `app/src/test/java/team/superfun/horrorpets/game/pantry/FeedingTest.kt`, `…/data/SaveCodecTest.kt`

**Interfaces:**
- Consumes: Task 2.
- Produces (`Tuning`): the spec's payout table as constants — hunger `FEED_RAW_HUNGER -20.0`, `FEED_SNACK_HUNGER -35.0`,
  `FEED_MEAL_HUNGER -55.0`, `FEED_BOTCH_HUNGER -10.0`, `FEED_FAVORITE_HUNGER -70.0`; XP `XP_FEED_SNACK 5`,
  `XP_FEED_MEAL 15`, `XP_FEED_FAVORITE 40`; flavor stat `FLAVOR_RAW 1.0`, `FLAVOR_SNACK 3.0`, `FLAVOR_MEAL 6.0`,
  `FLAVOR_FAVORITE 10.0`; mood `MOOD_LOVED 10.0`, `MOOD_HATED -10.0`, `MOOD_BOTCH -15.0`, `MOOD_RAW_METHOD -10.0`,
  `MOOD_FAVORITE 25.0`; `PANTRY_MAX 40`, `PANTRY_MIN_RAW 4`, `PANTRY_MIN_METHODS 1`, `PANTRY_MIN_FOODS 2`.
- Produces (`model/Model.kt`): `GameState.pantry: List<String> = emptyList()` (item ids, shelf order) and
  `fun GameState.pantryItems(): List<PantryItem>`; `PetState.favoriteFound: Boolean = false`.
- Produces (`game/pantry/Feeding.kt`):
  - `enum class Reaction { SHRUG, CONTENT, LOVED, HATED, YUCK, FAVORITE }`
  - `data class FeedOutcome(val pet: PetState, val reaction: Reaction, val line: String, val xpGained: Int, val levelsGained: Int, val firstFavorite: Boolean)`
  - `fun feed(pet: PetState, item: PantryItem): FeedOutcome` — the spec's table. The flavor stat is `strength` for
    SAVORY, `funLevel` for SWEET, `moodBonus` for GROSS; "mood" is `moodBonus`. Loved: XP ×2, stat ×2, mood +10. Hated:
    hunger half, XP 0, stat 0, mood −10. Raw foods ignore taste (SHRUG). Favorite overrides taste. XP through `addXp`.
    Everything clamps as elsewhere.
  - Lines: LOVED "Mmm, savory!" / "Mmm, sweet!" / "Gross! I love it!"; HATED "Too salty. Yuck." / "Too sweet. Yuck." /
    "Gross. And not the good kind."; CONTENT "Not bad!"; SHRUG "It's... raw."; FAVORITE "MY FAVORITE!!"; YUCK "Ugh,
    slop." for a botch (a named botch may have its own) and each way to cook its spec line ("HOT! HOT!" …).
  - `fun topUp(pantry: List<PantryItem>, rng: Random): List<PantryItem>` — appends random raw items until the shelf
    holds at least `PANTRY_MIN_RAW` raw, `PANTRY_MIN_METHODS` ways to cook and `PANTRY_MIN_FOODS` foods; an empty
    pantry gets a starting handful of six (two ways to cook, four foods). Never removes or reorders.

- [ ] **Step 1: Failing tests** in `FeedingTest`, one per payout row (raw food, snack, meal, loved, fine, hated,
  botch, raw way to cook, favorite) with a mid-stat pet, plus: `theFlavorStatFollowsTheFlavor`,
  `aFavoritePaysTheFavoriteRowNotTheLovedOne` (Jason fed the Camp Crystal Fish Fry: hunger −70, XP +40, strength +10,
  mood +25 — not the loved meal's −55/+30/+12/+10), `firstFavoriteOnlyOnce`, `aMealCanLevelUp`,
  `theLinesNameTheFlavor`, `topUpMeetsTheMinimums` (1000 seeds: empty and partial shelves), `topUpKeepsWhatIsThere`.
- [ ] **Step 2: SaveCodec** — read `pantry` on its own like `pets`; drop unknown ids; keep the first `PANTRY_MAX`.
  Tests: `pantryRoundTrips`, `aSaveWithoutAPantryLoads`, `unknownPantryIdsAreDropped`, `anOversizedPantryIsTrimmed`,
  `aWronglyTypedPantryKeepsThePets`, `favoriteFoundRoundTrips` (old save → false).
- [ ] **Step 3: Run**, expect FAIL; **implement**; **run all**, expect PASS.
- [ ] **Step 4: Commit** (`feat(pantry): feeding, the top-up and the save`).

### Task 4: Screen, view model, app wiring and the puppet

**Files:**
- Modify: `ui/Screen.kt`, `ui/GameViewModel.kt`, `ui/HorrorPetsApp.kt`, `MainActivity.kt`
- Create: `ui/pantry/PantryScreen.kt` (stub; Task 5 builds it)
- Modify: `app/src/debug/java/team/superfun/horrorpets/puppet/{PuppetCommand.kt,DebugPuppet.kt}`, `README.md`
- Test: `…/ui/GameViewModelTest.kt` (FEED tests change), `app/src/testDebug/…/puppet/PuppetCommandTest.kt`

**Interfaces:**
- Consumes: Tasks 2–3.
- Produces (`ui/Screen.kt`):
  - `data class CookShow(val result: PantryItem, val inputs: List<PantryItem>, val at: Int, val token: Long)`
  - `data class FedShow(val item: PantryItem, val reaction: Reaction, val line: String, val xp: Int, val levelsGained: Int)`
  - `data class Pantry(val session: Long, val board: List<Int> = emptyList(), val cook: CookShow? = null, val refusedCook: Long = 0, val tooFull: Long = 0, val fed: FedShow? = null) : Screen`
    — `board` holds shelf indices (at most two, in the order placed); `cook` is the last cook, for the screen to
    animate; `refusedCook`/`tooFull` are tokens that change on each refusal; `fed` is set once the killer has eaten.
  - `ownsKeySounds` is also true for `Screen.Pantry` (the screen plays its own ticks).
- Produces (`GameViewModel`): `fun onPantryPick(session: Long, index: Int)` (toggles a shelf item on/off the board;
  a third pick is ignored), `fun onPantryCook(session: Long)`, `fun onPantryFeed(session: Long)`,
  `fun onPantryDone(session: Long)`, `fun onPantryBack(session: Long)`; debug `fun debugStock(items: List<PantryItem>)`.
  All guard on the session (as training does) and ignore input once `fed` is set, except Done/Back.
- Produces (`ui/pantry/PantryScreen.kt`), final signature:
  `@Composable fun PantryScreen(screen: Screen.Pantry, pet: PetState, shelf: List<PantryItem>, loader: AssetLoader, onPick: (Long, Int) -> Unit, onCook: (Long) -> Unit, onFeed: (Long) -> Unit, onDone: (Long) -> Unit, onBack: (Long) -> Unit, focusEntry: Any?)`

- [ ] **Step 1: VM tests** (the `Rig`; replace the FEED-drops-food tests, keep refusal coverage where it still applies):
  `feedOpensThePantry` (tops up, saves, `Screen.Pantry`, pet unchanged); `pickTogglesTheBoard`; `aThirdPickIsIgnored`;
  `cookUsesBothAndShelvesTheResult` (inputs gone, result at the first input's index, saved, `cook` set, board empty,
  `cook_<method>`/`pantry_poof` sfx); `cookTwiceCooksOnce`; `aBotchOnTheBoardRefusesTheCook` (nothing used,
  `refusedCook` changes, `pantry_squelch`); `feedPaysAndShowsTheReaction` (item gone, pet paid per Task 3, `fed` set,
  saved); `aFullPetCanCookButNotFeed` (hunger below `FEED_REFUSE_BELOW`: cook works, feed changes only `tooFull` and
  plays `refuse`); `doneGoesHome`; `backLeavesWithTheShelfKept`; `aStaleSessionCannotCookOrFeed`;
  `aLevelUpFromFeedingOffersTheNewAttack`; `theFavoriteIsRemembered`.
- [ ] **Step 2: Run**, expect FAIL. **Step 3: Implement**: FEED in `onCare` opens the pantry (sessions as training's);
  Home no longer drops food (the FEED branch of the old path goes; `perform(FEED)` and `Food`/`nextFood` are removed
  once nothing uses them — Task 6 retires `FoodDrop`'s `Food` parameter).
- [ ] **Step 4: Wire**: `HorrorPetsApp` branch; `musicTrackFor` → the pet's track; `ownsKeySounds`. Stub screen: lists
  the shelf, OK/tap picks, buttons call cook/feed, Back calls back, any key after `fed` calls done.
- [ ] **Step 5: Puppet**: `feed` opens the pantry; `stock <id> [<id>…]` adds items (any screen; refused for unknown
  ids); `cook <id> <id>` and `serve <id>` act on the pantry by id (refused when not on the shelf); `cueOf`, `goHome`,
  `describe` (`"pantry: <n> items[, fed <id>]"`). Parse tests; usage text; README puppet rows.
- [ ] **Step 6: Run all and compile**, expect PASS. **Step 7: Commit** (`feat(pantry): FEED opens the pantry`).

### Task 5: The pantry screen: kitchen, board, shelf and input

**Files:**
- Modify: `ui/pantry/PantryScreen.kt`, `assets/AssetLoader.kt`
- Create: `ui/pantry/Shelf.kt` (the carousel), `ui/pantry/Board.kt` (board, COOK, FEED), `ui/pantry/ShelfFocus.kt`
  (pure focus logic)
- Test: `app/src/test/java/team/superfun/horrorpets/ui/pantry/ShelfFocusTest.kt`

**Interfaces:**
- Consumes: Task 1's sprites, kitchen and `KITCHEN_FLOOR`; Task 4's signature.
- Produces: `AssetLoader.pantryItem(item: PantryItem): ImageBitmap`, `AssetLoader.kitchen(): ImageBitmap`;
  `ShelfFocus` (pure): `fun focusAfter(old: Int, oldSize: Int, newSize: Int, removedAt: List<Int>, insertedAt: Int?): Int`
  and the carousel's visible window for a given width.

- [ ] Layout per mockup A, polished: kitchen backdrop (cover-fit, floor at `KITCHEN_FLOOR`), the killer idle on the
  left (the training actor), the board centred with its two places and COOK/FEED under it (each enabled only when it
  can act), the shelf carousel along the bottom (items on the board shown dimmed in place), a help strip (key caps on
  TV, words on touch), the found-favorite heart on that dish while that killer is in.
- [ ] Input: Left/Right moves along the shelf (scrolling the carousel), Up/Down between shelf and buttons, OK picks or
  presses, Back leaves; touch taps items and buttons, drags the carousel. No key repeats place an item twice. Input held
  while the screen is paused (as training).
- [ ] **Tests** `ShelfFocusTest`: `focusStaysOnTheShelfWhenItShrinks` (cook two into one at every pair of positions,
  feed the last item, empty shelf → 0), `focusFollowsTheNewDish`, `theWindowKeepsFocusVisible`.
- [ ] Run all, compile, look at it on the TV emulator; **commit** (`feat(pantry): the kitchen, board and shelf`).

### Task 6: Cooking and feeding on screen

**Files:**
- Modify: `ui/pantry/*`, `ui/home/FoodDrop.kt` (move to `ui/common/` if shared), `ui/home/HomeScreen.kt`
- Create: `ui/pantry/CookShow.kt`, `ui/pantry/Reaction.kt`

**Interfaces:**
- Consumes: Task 4's `CookShow`/`FedShow`/tokens and sounds from Task 1.
- Produces: `FoodDrop` takes the item's image and crumb colors read from its pixels (`crumbColorsOf(image)`), not `Food`.

- [ ] COOK: the two items converge from the board to its middle, a poof, the result pops up with its name, then flies
  to its shelf slot; the method's sound on start, `pantry_poof` on the poof. A refused cook: the board shakes,
  `pantry_squelch`. Input held during the show.
- [ ] FEED: the item drops beside the killer and is munched (`FoodDrop`, the pet's feed animation), then the reaction:
  JUMP for LOVED/CONTENT (`eat_happy`), FAIL for HATED/YUCK (`eat_gross`), a celebration for FAVORITE (repeated jumps,
  `favorite_fanfare`), SHRUG a small look; a speech bubble with the line and a short gains readout (+XP, the stat);
  OK/tap (or a few seconds) calls `onDone`. `tooFull` shows FAIL and Home's "Too full" refusal text.
- [ ] Home's FEED drop is gone; remove what nothing uses (`Food`, `nextFood`, Home's drop path); keep the munch.
- [ ] Run all, compile, play it on the TV emulator; **commit** (`feat(pantry): cooking and feeding on screen`).

### Task 7: Play-test and polish (controller)

- [ ] Play every favorite path, a botch of each kind and a raw way to cook on the TV emulator (remote keys) and a
  phone emulator (touch); fix what the play shows; README features line; spec status. Then the final review.
