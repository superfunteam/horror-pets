# New VS Card and the Secret Challenger Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Street Fighter Alpha-style VS card for every fight, and Jaws as a secret eighth killer revealed by a
full-screen "new challenger" entrance once the other seven are unlocked.

**Architecture:** Jaws is an ordinary `PetId` with a `secret` flag; one visibility rule in the model
(`GameState.isVisible`) decides everywhere whether he exists yet, and the save records his reveal. The VS card and the
entrance are composables under `ui/fight/` driven by pure timeline/palette code. His extra moves are sprite
animations named on the attack, and his special is a new `SpecialStyle` with its own effect.

**Tech Stack:** Kotlin, Jetpack Compose (TV + phone), kotlinx.serialization, JUnit4 pure-JVM tests, Python tools
(sprite intake, NumPy synthesis), a Netlify function (the PvP relay).

**Spec:** `docs/superpowers/specs/2026-10-24-challenger-jaws-design.md` (read it first).

## Global Constraints

- Paths are relative to `app/src/main/java/team/superfun/horrorpets/` unless they start with `app/`, `tools/`,
  `sprites-final/`, `sprites-source/`, `scenes-final/`, `audio-final/`, `netlify/`, `README` or `docs/`.
- `GameState.version` stays 2. New fields go last with defaults; a new top-level `GameState` field is read on its own
  in `SaveCodec.decodeOrNull` (like `pets`, `selected`, `pantry`) so a bad value can't sink the save.
- The secret: before the reveal Jaws must not appear, be named or be counted in any player-facing place (starter pick,
  stable, opponent switcher, counters, Home's stable badge, training mates, credits, README, site). Commit subjects call
  him "the secret killer".
- Existing sounds and scenes must not change; `tools/synth_audio.py` additions go at the end of their lists.
- Pure-JVM tests; TV remote and touch; 540 dp design; nothing runs per frame once a screen settles.
- Test only on the emulators (`emulator-5554` TV, `emulator-5556` phone); never `192.168.1.42:5555`.
- Tests: `./gradlew -q testDebugUnitTest testReleaseUnitTest`; compile: `./gradlew -q assembleDebug compileReleaseKotlin`;
  CI also runs `./gradlew :app:lintDebug`.
- Commit on branch `challenger-jaws`, messages ending `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`; never
  commit `audio/` or `art-work/`.

## Review Focus

1. A save from 0.7.0 where all seven are already unlocked: the next FIGHT plays the entrance (Task 4 test
   `anOldSaveWithEveryoneGetsTheChallenger`).
2. The entrance plays once: Back, a lost fight, an app restart after it began — never again; Jaws is then a locked card
   (Task 4 tests `theRevealIsSavedWhenTheEntranceStarts`, `losingLeavesHimAsALockedCard`).
3. A starving pet presses FIGHT with the reveal due: refused as today, and the reveal waits (Task 4 test
   `aStarvingPetDoesNotTriggerTheReveal`).
4. Every counter and list before the reveal: 7 killers; after: 8 (Task 3 test `visibilityBeforeAndAfterTheReveal`).
5. A damaged `revealed` value in a save: the pets survive and nothing is revealed (Task 3 SaveCodec test).

---

### Task 1: Jaws's sprites

**Files:**
- Create: `sprites-source/jaws/` (reference sheet + hi-res rows copied from `~/Documents/Codex/2026-10-23/c`),
  `sprites-final/jaws/*` (strips, `manifest.json`, `head.webp`)
- Modify: `tools/sprite_sources.json`, `assets/SpriteManifest.kt` (two optional animations), tools as needed
- Test: `app/src/test/java/team/superfun/horrorpets/assets/SpriteManifestTest.kt` (or the existing manifest test)

**Interfaces:**
- Produces: `Anim.THRASH("thrash", optional = true)`, `Anim.BARRELS("barrels", optional = true)`;
  `sprites-final/jaws/manifest.json` with every required animation plus `thrash` and `barrels`, `"pet": "jaws"`, the
  standard 384×416 cell, and a `feed` animation.

- [ ] Bring Jaws in through `tools/intake_sprites.py` like the others: reference sheet and hi-res rows from
  great-white-v2 (`outputs/great-white-v2/spritesheet.png`, `work/great-white-v2/decoded/*.png`; v2's rows are idle,
  running-right, running-left, waving, jumping, failed, waiting = barrels, running = chomp-and-thrash, review, look ×2).
  Map: attack ← the chomp-and-thrash row; barrels ← the barrels row; thrash ← great-white-ultra's six frames
  (`work/great-white-ultra/`); wait and work ← his idle frames; feed ← review (as Jason's). Keep the intake's QA
  (IoU, fringe) and register thrash/barrels so he doesn't jump between animations.
- [ ] Head: `tools/make_heads.py` → `sprites-final/jaws/head.webp`.
- [ ] Test that a manifest may ship `thrash`/`barrels` and that both are optional (others don't have them).
- [ ] Look at every animation on a contact sheet; commit (`feat: the secret killer's sprites`).

### Task 2: Jaws's sounds

**Files:**
- Modify: `tools/synth_audio.py` (append only)
- Create: `audio-final/music/jaws.ogg`, `audio-final/sfx/special_jaws.wav`, `audio-final/sfx/challenger_hit.wav`

**Interfaces:**
- Produces: `music/jaws.ogg` (a menacing low-strings track, loopable like the others, not the film's theme);
  `sfx/special_jaws.wav` (a tank's hiss and whoosh, then a big explosion — its loudest moment is the hit, reported as
  milliseconds for Task 3's `specialHitMs`); `sfx/challenger_hit.wav` (a deep two-note bass hit for the entrance).

- [ ] Add them at the END of the synth lists; verify every existing file is byte-identical after a full regenerate
  (`git status` shows only the three new files).
- [ ] Report the special's measured hit time (RosterAssetsTest's `strongestAttackMs` method). Commit
  (`feat: the secret killer's music and special sound`).

### Task 3: Jaws in the roster, and the secret

**Files:**
- Modify: `model/Model.kt` (`PetId`, `GameState`), `model/Roster.kt`, `model/Attacks.kt`, `game/pantry/Cookbook.kt`,
  `fight/FightTuning.kt`, `data/SaveCodec.kt`, `ui/home/Stable.kt`, `ui/home/HomeScreen.kt`,
  `ui/switcher/SwitcherScreen.kt`, `ui/fight/UnlockedScreen.kt`, `ui/title/Credits.kt` (+ test),
  `netlify/functions/pvp.mjs`, `app/src/debug/.../puppet/Shortcuts.kt`
- Test: `app/src/test/.../model/SecretTest.kt` (new), `RosterAssetsTest`, `SaveCodecTest`, `CookbookTest`, `CreditsTest`

**Interfaces:**
- Consumes: Tasks 1–2's assets; the committed scenes, VS art and voice lines.
- Produces:
  - `PetId.JAWS` (last) and `val PetId.secret: Boolean` (true only for JAWS).
  - `GameState.revealed: Set<String> = emptySet()` (last field).
  - `fun GameState.isVisible(pet: PetId): Boolean` = not secret, or revealed, or in the stable.
  - `fun GameState.visiblePets(): List<PetId>` (in `PetId` order).
  - `fun GameState.challengerDue(): PetId?` = a secret, not-yet-visible killer when every non-secret killer is in the
    stable; else null.
  - `PetSpec(JAWS, Passive("Apex", heavyMult = FightTuning.JAWS_HEAVY_MULT /* 1.15 */), SpecialStyle.OXYGEN_TANK,
    specialHitMs = <Task 2's measurement>)`; `SpecialStyle.OXYGEN_TANK("Oxygen Tank", SpecialEffect.DAZE)`.
  - Moveset: Light Fin Swipe, Tail Slap, Nibble, Bump the Boat; Heavy Chomp, Death Roll, Barrel Breach, Ultra Thrash;
    Special (tiers 1–3) Bigger Boat, Amity Island, Smile!. `Attack.anim: String? = null` (a sprite animation key):
    Barrel Breach → "barrels", Ultra Thrash → "thrash".
  - `tasteOf(JAWS) = Taste(GROSS, SAVORY, SWEET, FOOT_AND_POTATOES)`.

- [ ] **Tests:** `visibilityBeforeAndAfterTheReveal` (a stable of seven: `visiblePets()` has 7 and `challengerDue()` is
  JAWS; after revealing: 8 and null; with JAWS in the stable: visible); `aFreshGameHasNoChallengerDue`;
  `revealedRoundTrips`, `aWronglyTypedRevealedKeepsThePets`, `unknownRevealedNamesAreDropped` (SaveCodec);
  RosterAssetsTest covers JAWS's sprites, scenes, sounds and voices and his `specialHitMs`; Cookbook favorites are
  still distinct; Credits lists only non-secret killers.
- [ ] Every `PetId.entries` loop in player-facing code switches to `visiblePets()` (starter pick: non-secret only, even
  revealed); counters read "n / visible". The debug puppet's `unlock_all` unlocks only the seven (so the next FIGHT
  plays the entrance); `pet jaws`/`fight jaws` still work.
- [ ] The relay accepts `JAWS` (`netlify/functions/pvp.mjs`), and its tests if any.
- [ ] Run all, compile, lint; commit (`feat: the secret killer joins the roster, hidden`).

### Task 4: The reveal

**Files:**
- Modify: `ui/Screen.kt`, `ui/GameViewModel.kt`, `ui/HorrorPetsApp.kt`, `MainActivity.kt`, puppet `DebugPuppet.kt`
- Create: `ui/fight/ChallengerScreen.kt`
- Test: `GameViewModelTest`, a pure test of the entrance timeline

**Interfaces:**
- Consumes: Task 3's `challengerDue()`, `revealed`; assets `sprites/vs/challenger.webp`, `sprites/vs/jaws-name.webp`,
  `sfx/challenger_hit`, voices `new_challenger`, `jaws`.
- Produces: `Screen.Challenger(val pet: PetId, val session: Long)`; `fun onChallengerDone(session: Long)` (→ the VS
  card against `pet`, as `onSwitcherPick` would make it).

- [ ] **VM tests:** `anOldSaveWithEveryoneGetsTheChallenger` (seven in the stable, FIGHT → `Screen.Challenger(JAWS)`);
  `theRevealIsSavedWhenTheEntranceStarts` (revealed contains JAWS and is persisted before `onChallengerDone`);
  `doneGoesToTheVsCardAgainstHim`; `aStarvingPetDoesNotTriggerTheReveal`; `losingLeavesHimAsALockedCard` (after a
  lost fight, FIGHT opens the switcher and JAWS is a locked card); `beatingHimUnlocksHim`; `aStaleSessionIsIgnored`.
- [ ] The entrance (about 3–4 s, OK skips after a 400 ms guard, Back = OK): cut to black, `challenger_hit`, a water burst
  and Jaws lunging toward the camera (his jump or attack frames scaled up), the banner slams in with
  `new_challenger`, then his name with `jaws`; nothing per frame after it ends. Music and key ticks as the VS card.
- [ ] Wire `HorrorPetsApp`, `musicTrackFor`, the puppet's `cueOf`/`goHome`/`describe`. Play it on the TV emulator
  (`unlock_all`, then FIGHT). Commit (`feat: here comes a new challenger`).

### Task 5: The new VS card

**Files:**
- Modify: `ui/fight/VersusScreen.kt`, `assets/AssetLoader.kt`
- Create: `ui/fight/VsBackdrop.kt` (palettes + streak drawing), test for the pure parts

**Interfaces:**
- Produces: `AssetLoader.vsName(pet: PetId)`, `AssetLoader.vsLogo(pet: PetId)`, `AssetLoader.challengerBanner()`;
  `data class VsPalette(deep, mid, hot, streak: Color)` and `fun vsPalette(pet: PetId): VsPalette` (all 8).

- [ ] Same timeline, skip and Back rules as today. Each half: its killer's palette, hottest behind the fighter, fast
  animated diagonal streaks, a radial burst, halftone near the white-hot seam (the approved mockup:
  `art-work/vs/approved-mockup.jpg`, local only). Names: each
  killer's wordmark slammed in at the top of its half, tilted toward the middle. VS: the **opponent's** logo (PvP: the
  other player's). Streak animation runs only while the card is up.
- [ ] Tests for `vsPalette` covering every `PetId` and any pure streak/timeline math. Look at it on the TV and phone
  emulators. Commit (`feat(vs): the Street Fighter Alpha VS card`).

### Task 6: His moves in a fight

**Files:**
- Modify: `ui/fight/FightScreen.kt`, `ui/fight/FightVfx.kt` (or a new `OxygenTank.kt`), tests

**Interfaces:**
- Consumes: `Attack.anim`, `Anim.THRASH`/`BARRELS`, `SpecialStyle.OXYGEN_TANK`, `sprites/fight/oxygen_tank.webp`,
  `sfx/special_jaws`.

- [ ] An attack with `anim` plays that animation instead of `attack` when the attacker's manifest has it (else attack).
- [ ] Oxygen Tank: the tank flies from his mouth in an arc to the target; on impact a big flash, fireball, smoke and
  debris, a screen shake, timed to `specialHitMs`; nothing per frame afterwards. Tests for the arc/timeline math.
- [ ] Play Barrel Breach, Ultra Thrash and Oxygen Tank on the TV emulator (`fight` with Jaws via the puppet, `move`).
  Commit (`feat: the secret killer's moves`).

### Task 7: Play-test and ship (controller)

- [ ] Full flow on the TV and phone emulators: `unlock_all`, FIGHT → entrance → VS → fight → lose → locked card →
  win → unlocked; VS cards for several pairs; his moves; Home, PLAY, train, cook with him.
- [ ] README: the new VS card only (no Jaws). Final review, merge, release 0.8.0 with hand-written notes (pantry + VS,
  no Jaws), site copy (pantry, VS) and deploy with the relay.
