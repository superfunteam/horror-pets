# A new VS screen, and Jaws: the secret eighth killer

## Why

Two requests from the player, built together because the second one enters through the first:

1. The VS card before a fight is plain: white text names, a flat orange VS, a dim split. Make it "a lil more Street
   Fighter Alpha nuts": fast streaks and colors, anime style, with each killer's name drawn in their own film's style
   and "a VS logo for each character in style".
2. Another agent made a Jaws pet (a glossy toy great white). Add him as a secret eighth killer: no mention of him
   anywhere until the player has unlocked the other seven; then "the next time you hit fight, have a full screen
   announcement where he enters". He gets extra moves: a thrash "with swooshes and teeth marks", "the three barrels
   pop out from behind him", and a custom special where he spits a silver scuba oxygen tank that "explodes like in
   the movie".

The player approved the mockup (`art-work/vs/` candidates; the mockup and VS options were shown) and the plan below
("Yes go! Do it all and ship").

## Goals

- The VS card: per-side anime backgrounds with fast, moving speed streaks in each killer's colors; each killer's name
  as film-style lettering (a wordmark); a VS logo in the style of the killer the player is facing.
- Jaws: a full eighth killer (Home, care, PLAY, training, the pantry, fights, moves, PvP, the stable), hidden until
  all seven others are unlocked, revealed by a full-screen "new challenger" entrance on the next FIGHT, unlocked by
  beating him like any killer.
- His attacks include the ultra thrash, the three barrels and the oxygen tank special.

## Non-goals

- No change to how the other seven unlock, to fights' rules, or to the pantry beyond Jaws's taste.
- The website, the launch video, the README and the credits are not changed for Jaws (he's secret). The website and
  release notes may describe the new VS card.

## Anti-goals (a failure even if it works)

- Jaws visible, named or counted anywhere before the reveal: title art, the starter pick, the stable, the opponent
  switcher, the "UNLOCKED n / 7" counter, the puppet's usage text in release builds, the README, the site, the
  credits, the release notes.
- A player who already unlocked everyone before this update never meets him (the reveal must work for existing saves).
- A VS card that's slower to get through than today's (OK still skips it).
- The secret leaking through save data an old build would choke on.

## What's decided

### The VS card

- Same timeline and skip rules as today's card (`VersusScreen`: panels, fighters slide in, names slam, VS slams with
  a shake, FIGHT!, OK skips, Back backs out).
- **Backgrounds:** each half is its killer's colors (a per-killer palette: deep, mid, hot and streak colors), hottest
  behind the fighter, with long diagonal speed streaks racing across (animated, fast), a radial burst behind the
  fighter, and halftone dots near the white-hot diagonal seam. Code-drawn, not images, so they move. Nothing runs per
  frame once the card is gone.
- **Names:** each killer's wordmark (film-style lettering; the seven made for the launch video, `video/wordmarks/`,
  plus a new one for Jaws), slammed in at the top of its half, tilted a little toward the middle.
- **VS:** one VS logo per killer, each in that killer's film style (eight generated images). The card shows the
  **opponent's** VS: the killer you're facing (in PvP, the other player's). *(My reading of "it always shows the
  challenger VS": the challenger is the one stepping up to fight you.)*
- Art: wordmarks and VS logos ship as `sprites-final/vs/<pet>-name.webp` and `sprites-final/vs/<pet>-vs.webp`.

### Jaws

- **Identity:** `PetId.JAWS`, eighth, display name "JAWS". Sprites from the other agent's work
  (`~/Documents/Codex/2026-10-23/c`, "great-white-v2" and "great-white-ultra"), brought in through the existing sprite
  intake (`tools/intake_sprites.py`, `tools/sprite_sources.json`) like the other killers.
- **Animations:** the standard set (idle, run both ways, wave, jump, fail, review, look-a, look-b), plus:
  - **attack**: the chomp-and-thrash row (v2's "running" row);
  - **thrash** (new, optional): the six-frame ultra thrash with swooshes and bite marks (great-white-ultra);
  - **barrels** (new, optional): the three yellow barrels popping up behind him (v2's "waiting" row);
  - wait and work: his idle frames (v2 reused those rows for the new moves). *(My call.)*
- **Moves** (same slots and tiers as everyone): Light — Fin Swipe, Tail Slap, Nibble, Bump the Boat. Heavy — Chomp,
  Death Roll, **Barrel Breach** (plays *barrels*), **Ultra Thrash** (plays *thrash*). Special — **Oxygen Tank** (his
  signature special, tier 0), Bigger Boat, Amity Island, Smile!. Heavies and specials that don't name an animation swing with
  *attack*, as today. *(Names are my call.)*
- **Oxygen Tank (his special style):** he lunges and a silver scuba tank flies from his mouth in an arc to the other
  killer, and on impact it explodes: a big flash, a fireball, smoke and debris, a screen shake, and the special's
  sound. Its lasting effect is DAZE. *(My call.)* The tank is a sprite (`sprites-final/fight/oxygen_tank.webp`); the
  explosion is drawn in code.
- **Passive:** "Apex": his heavies hit harder (`heavyMult`, a new tuning constant around 1.15). *(My call.)*
- **Home:** three scenes in the game's scene style: an Amity Island beach at dusk, the deck of the Orca, a moonlit
  underwater shark cage.
- **Sound:** a menacing low-strings music track, synthesized like the others (`tools/synth_audio.py`, appended so no
  existing sound changes; not the film's theme); his special's sound (a hiss, a whoosh, a big explosion) synthesized
  too; the announcer's "JAWS!!!", "JAWS... NEVER DIES!!!" and a new "HERE COMES A NEW CHALLENGER!!!" (ElevenLabs, same
  voice and tool as the others).
- **Pantry:** loves Gross, fine with Savory, hates Sweet; favorite Foot & Potatoes. *(My call.)*

### The secret and the reveal

- A killer can be **secret** (Jaws is the only one). A secret killer exists nowhere in the game — not in the starter
  pick, the stable, the opponent switcher, the "UNLOCKED n / N" counter (N counts only what's visible), Home's stable
  badge, training mates, debug usage text — until the save says he's **revealed**.
- **The reveal:** when every non-secret killer is unlocked and Jaws isn't revealed, the next FIGHT on Home (with a pet
  able to fight) opens the **challenger entrance** instead of the opponent switcher: a full-screen moment in the new
  VS style — the screen cuts to black, a deep two-note bass hit, water bursts, Jaws lunges toward the camera, his
  wordmark slams in, and "HERE COMES A NEW CHALLENGER!" with the announcer. Then straight into the VS card against
  him, then the fight. The reveal is saved the moment the entrance starts, so it plays once.
- **Unlocking him:** beat him like any killer. Lose (or back out), and he's now in the opponent switcher as a locked
  card until you beat him. The counter becomes "n / 8" once he's revealed.
- **Existing saves:** a player who had all seven before this update gets the entrance on their next FIGHT.
- **Save:** `GameState.revealed: Set<String>` (killer names), read on its own like `pantry` so a damaged value can't
  sink the save; unknown names dropped. `GameState.version` stays 2. An older build reading a save with Jaws in it must
  not crash: its `PetId` decode skips unknown keys already (`pets`), and `revealed` is ignored.
- **PvP:** the other player may bring Jaws; the card and fight show him (he's theirs). Nothing else reveals him. The PvP
  relay (`netlify/functions/pvp.mjs`) only accepts the seven killers' names today: it learns JAWS too, and is deployed
  with the site when this ships. *(My call: "ship" covers it, and without it his PvP fights fail.)*
- **How far the secret goes:** the game, the README, the website, the credits and the release notes (written by hand,
  not generated from commits). The source repository is public and its code must name him (`PetId.JAWS`,
  `sprites-final/jaws/`); commit subjects call him "the secret killer". *(My call.)*

### Assets already made (commit 7e7ad90)

- `sprites-final/vs/<pet>-vs.webp` × 8, `sprites-final/vs/<pet>-name.webp` × 8, `sprites-final/vs/challenger.webp`
  (the "HERE COMES A NEW CHALLENGER!" banner, 1400×495, used by the entrance), `sprites-final/fight/oxygen_tank.webp`.
- `scenes-final/jaws/{beach,orca,cage}.webp` and their `scenes.json` entries (floor 0.7531 like every scene).
- `audio-final/voice/jaws.wav`, `jaws_never_dies.wav`, `new_challenger.wav`.
- Still to make (no API keys needed): his sprites through the intake, his music and his special's sound (and the
  entrance's bass hit) with `tools/synth_audio.py`.

## Constraints (from the codebase)

- A new `PetId` touches every exhaustive `when` over it (tastes, movesets, rosters) and every `PetId.entries` loop —
  each loop must decide: all killers, or only visible ones.
- `RosterAssetsTest` requires per killer: the sprite manifest and every required animation, three scenes in
  `scenes.json`, `music/<pet>.ogg`, `sfx/special_<pet>.wav`, `voice/<pet>.wav`, `voice/<pet>_never_dies.wav`, a special
  hit time measured from the clip (`specialHitMs`).
- Sounds: 16-bit PCM mono 44.1 kHz WAV (music: Opus in Ogg). Existing sounds must not change; `synth_audio.py`
  additions go at the end of their list (shared RNG).
- Pure-JVM tests; TV remote and touch; 540 dp design height; never touch the player's real TV.
- API keys never leave the controller (generated art and voice lines are the controller's job).

## Left to the builder

- Exact palettes, streak counts and speeds, the entrance's choreography and timing (around 3–4 s, skippable with OK
  after a short guard).
- How the fight picks an attack's animation (a field on the attack or a lookup), and how the oxygen tank's arc and
  explosion are drawn.
- Jaws's sprite registration details (cell, baseline, scale) through the intake tool.
