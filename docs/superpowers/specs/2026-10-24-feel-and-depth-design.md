# Feel and depth: meal perks, the Gauntlet, a living Home, fights that hit, and sound everywhere

**Date:** 2026-10-24. **Status:** approved in chat, two calls still open (see "Open questions"). Implemented in
phases, each shipped as a release.

## Intent

0.8.x made the game complete: eight killers, care, cooking, training, fights, PvP. It does not yet *feel* like a
fighting game or *play* like an RPG. The player's direction, in their words:

- "You actually get a lasting buff from the food you eat, or a hangover. I think that's funny... make sure it's very
  clear when it happens."
- The Gauntlet, "only whenever you've unlocked all characters... then every fight will be a gauntlet where it gets
  progressively harder on each character."
- A living Home: "dusk/night and the light... changing just the kind of lighting of the scene", and "having them
  sleep at one point, so I think that's pretty funny."
- No killer-specific fight AI: "it's hard enough to play this." No feints: "we added feints a while back where it
  shows you their upcoming move. That doesn't really work for me. Mostly, I'd like this to play like a classic RPG
  or like Pokémon."
- Hit-stop ("3 to 5 frames of pause") and a knockout in slow motion. "Whenever someone does a special, the lighting
  should also change, and the music should change. The scene should turn red."
- "We need sound across the board": weapon impact sounds, Chucky's voice at home and in fights (in character, swearing
  and all), more announcer "like in Mortal Kombat: Finish him", adaptive music, phone haptics.
- A fresh look at the pantry: "the pairings, what they cook, and how the user understands it could be improved."

## Goals

1. Feeding has consequences the player can see: one clear status from the last meal, carried into fights.
2. A late-game fight loop with stakes: the Gauntlet.
3. Home changes with the real day, and the killers sleep.
4. Fights hit: pause on impact, slow motion on the knockout, specials that take over the screen and the music.
5. Sound and voice where there is silence today, starting with the weapons, Chucky and the announcer.
6. The pantry teaches its own logic without giving the recipes away.

## Non-goals and anti-goals

- No per-killer fight styles or smarter AI. The CPU stays a dice roller.
- No new sprites or scenes. Everything here uses the art that ships (overlays, tints, timing, sound, text).
- Nothing that makes the fight harder to read: no new resources, meters or menus in a fight.
- PvP stays fair and simple: meal perks and the Gauntlet are CPU fights only.
- The secret killer is never named in notes, README or site (see docs/superpowers/specs/2026-10-24-challenger-jaws-design.md).

## Constraints

- Save: `GameState.version` stays 2; new fields go last with defaults, decoded tolerantly (as `revealed` and
  `recipesFound` are). Pure logic lives under `game/` and `fight/` with JVM tests; UI is checked on the emulators.
- Android TV (D-pad, 16:9 and 4:3) and phones (touch), the 540 dp design height, font scale capped at 1.15.
- Generated audio needs the ElevenLabs key, passed as `ELEVENLABS_API_KEY=...` inline, never written anywhere. Voices
  and sounds follow the existing tools (tools/generate_announcer.py, tools/generate_player_voice.py,
  tools/generate_game_sfx.py): takes, read-back checks, loudness, 44.1 kHz mono WAV in audio-final/.
- Profanity is fine for Chucky ("he says the F word, and then he says bitch and stuff like that, plenty"); the
  announcer stays clean apart from what he already shouts.

---

## A. Meal perks: you are what you last ate

The last thing a killer ate gives it one status, shown everywhere its stats are, until it eats again (or a day goes
by). Pokémon status conditions, but from dinner.

| Last meal | Status | In a CPU fight |
|---|---|---|
| A savory dish | **BEEFED UP** | damage dealt ×1.15 |
| A sweet dish | **SUGAR RUSH** | the special meter starts half full (50) |
| A gross dish | **IRON STOMACH** | crit chance +0.10 |
| The killer's favorite dish | **FAVORITE FEAST** | its flavor's effect, and a third potion |
| Any botch (slop, a fire, burnt, the named ones) | **HANGOVER** | miss chance +0.15 on STRIKE and HEAVY, damage dealt ×0.9 |
| A dish in the flavor it hates | none | it barely ate: the old status is cleared |
| A raw food or a way to cook | none | a plain meal settles the stomach: the old status is cleared |

- Model: `PetState.lastMeal: String? = null` (the `PantryItem.id`) and `lastMealAtMs: Long? = null`, both last in the
  class. `mealPerkOf(pet, nowMs): MealPerk?` in `game/pantry/MealPerk.kt` derives the status from the item's kind and
  flavor, the killer's taste (`tasteOf`), whether it is the favorite, and `Tuning.MEAL_PERK_HOURS = 24` (after which
  there is none). `feed()` sets both fields on every feeding.
- Fight: `Fighter` gets `perk: MealPerk? = null`, set by `buildFighter` for the player only (never for `buildOpponent`,
  never in PvP: `PvpMatch` builds fighters without it). The engine applies it in `damageOf` (×1.15 / ×0.9),
  `critChance` (+0.10), the miss roll (+0.15), the starting meter (50) and the potion count (3). Tests pin each number.
- Shown, so it is never a mystery:
  - the pantry's reaction: after the gains, a status line in its own colour with a short sound: "BEEFED UP!" (gold),
    "SUGAR RUSH!" (pink), "IRON STOMACH!" (green), "FAVORITE FEAST!" (gold with hearts), "HANGOVER..." (sick green);
    nothing when the meal gives none;
  - Home's stat card: a chip under the status tag with the status name (HANGOVER in red), and the killer's idle gets a
    hangover sway (a slow 3° rock) while it has one;
  - the VS card: the chip beside the player's name;
  - the fight HUD: the chip by the player's HP bar; the CPU's side never has one.
- Copy, in the killer's bubble as it lands: "Now I'm strong!", "I could run through a wall!", "Nothing can hurt me.",
  "My favorite. I'm unstoppable.", "Ugh... my head." (the builder may vary these per flavor; keep them short).

## B. The Gauntlet

Once the player has every killer, FIGHT becomes a run through the whole stable, each bout harder than the last.

- Open when `game.gauntletOpen()`: every killer, the secret one included, is in the stable ("unlocked all
  characters", taken literally). Until then FIGHT works exactly as today: the opponent switcher, with the secret
  killer a locked card in it until he is beaten, as the challenger design promised. So the last bout of a run is
  always him.
- FIGHT, once open (after the hunger and training gates, and a challenger entrance if one is due): the opponent
  switcher in a new mode, `SwitcherMode.ARENA`, with two spotlight cards: **THE GAUNTLET** (a bracket of the stable's
  heads) and **PVP BATTLE**. There are no single CPU fights any more.
- The run: the opponents are every visible killer but the player's, in roster order (the secret killer last when he is
  in it). Bout `i` (from 0) faces an opponent at level `player.level - 2 + 2i` (at least 1) and strength
  `player.strength - 5 + 5i` (clamped), hunger and mood as `buildOpponent` has them, the best kit for that level. The
  numbers are `FightTuning.GAUNTLET_LEVEL_STEP = 2`, `GAUNTLET_LEVEL_START = -2`, `GAUNTLET_STRENGTH_STEP = 5`.
- Carry-over between bouts: the player's fighter is built once, at the start of the run, and kept: its HP and meter
  stay where they were (hit points, not a share), its max HP, strength and mood are the run's; bleed and combo clear;
  the potions are `POTIONS_PER_FIGHT` for the whole run, not per bout. Opponents scale from the player's level and
  strength at the start of the run. The opponent is fresh each bout. The hunger and training gates are checked at
  FIGHT, once, not between bouts.
- Between bouts, `Screen.GauntletBracket(run)`: the row of heads with the beaten ones crossed in blood and the next
  one lit, "NEXT: FREDDY · LV 16", the player's HP bar and potions left, OK to go on (the VS card, then the fight) and
  Back to walk away (the run ends as a loss at that bout, nothing more paid). The announcer calls "BOUT TWO!" ... and
  "FINAL BOUT!" as the bracket comes up.
- Pay: each won bout pays its XP and counts its win as it ends (a run cut short keeps them), and the bracket shows it
  ("+40 XP", and the run's total so far). A win's hunger and mood effects (`fight/Rewards.kt`) are paid once, for the
  whole run, when it ends, so a run of seven does not starve the killer halfway. A level gained mid-run changes nothing
  until the run is over (the fighter is the run's); the Learn screen waits for Home as it does. A lost bout shows the
  results card as today, with the run's totals (XP across its bouts, levels gained) and "BEATEN AT BOUT 3 OF 7"; the
  rank is the run's (bouts won, HP left, potions used across the run). RUN forfeits the run: a loss. Back on the
  bracket walks away: the run is over, nothing more is paid, no loss is counted, straight Home. Winning the last bout
  shows a **CHAMPION** card before the results: "CHAMPION!" from the announcer, a bonus of half the run's XP, and the
  killer's best run kept in `PetState.gauntletBest: Int = 0` (bouts won; a new last field) and shown on the bracket
  ("BEST: 5 OF 7" / "CHAMPION").
- The run lives in the view model's UiState as `GauntletRun(fighter, bout, potionsLeft, xpSoFar, levelsSoFar)`, not
  in the save: a run interrupted (the app killed) is simply over, with what it paid kept.

## C. The living Home: light and sleep

- **Light.** `lightOf(hour: Int): SceneLight` in `game/DayLight.kt`, by the device's local hour: 5–7 DAWN (warm,
  `#FFB36B` at 0.16), 7–18 DAY (none), 18–21 DUSK (`#FF6A2A` at 0.22), 21–5 NIGHT (`#1B2A6B` at 0.38 with the vignette
  doubled). Home draws it as a tinted layer over the scene and under the killer and the HUD, cross-faded over 2 s when
  it changes (the hour is read on resume and once a minute with the tick). Only Home and the stable's badge: the
  pantry, training and fights keep their own light. Debug: the puppet's `hour N` fakes the hour.
- **Sleep.** From 23 to 6 local the killer is asleep unless something just woke it:
  - its idle holds on its first frame, slumped (rotated 12° toward the floor, lowered by 5% of its height), with Zzz
    rising from its head every 1.6 s (drawn text in the HUD's font, drifting up and fading) and a snore loop (new
    sound; silent until it exists);
  - the status tag reads ASLEEP (priority: STARVING > ASLEEP > GRUMPY > HUNGRY > BORED > CONTENT);
  - hunger grows and fun falls at half speed for the hours asleep: `elapsedHours` in `game/Care.kt` splits an away
    stretch into asleep and awake hours by the clock (`sleepHoursBetween(fromMs, toMs, zone)`, tested across midnight
    and a stretch longer than a day);
  - any press on the action bar, a walk, or opening the stable wakes it: it sits up (the LOOK_A animation), says a
    sleepy line ("Five more minutes.", "...what time is it?", "I was dreaming about camp.") and stays awake for 10
    minutes (`awakeUntilMs`, not saved), then dozes off again if it is still night. The press itself then goes
    through as normal (FEED opens the pantry awake).
- The welcome-back card is unchanged; the title screen is unchanged.

## D. Fights like a classic RPG (open question 1)

The tell bubble over the CPU and the feint go. The player picks a move; both moves resolve; the rock-paper-scissors
of STRIKE, HEAVY and BLOCK stays as it is, read from the result, not from a hint. With no tell, the CPU's habit
counter is an unfair edge: `CPU_COUNTER_CHANCE` drops from 0.4 to 0.25. `CpuPlan`, `cpuTell`, `TellBubble`,
`feintBonus` and `CPU_FEINT_CHANCE` are removed; the fight's help strip no longer mentions reading the tell. The debug
puppet's autoplay, which answers the tell today, falls back to STRIKE and HEAVY in turn.

The alternative, if the player prefers it: keep the tell but make it always honest (remove the feint only).

## E. Fights that hit

- **Hit-stop.** When a hit lands (the impact beat), the fight's animation clock pauses for `HIT_STOP_MS`: 70 ms for a
  STRIKE, 110 ms for a HEAVY, a crit, a counter or a special (about 4 and 7 frames). Both sprites, the marks and the
  damage numbers hold; the screen shake and the number's pop start as time resumes. The fight clock already drives
  the swings (`swingImpactMs`, `AFTER_IMPACT_MS`); the stop is a pause of that clock, so nothing else needs to know.
- **Knockout.** The blow that empties a health bar plays at 0.3× speed from its impact for 900 ms: the sprites, marks
  and the number, with the scene desaturated by 40% under a 10 ms white flash, and a low boom (new sound `ko_boom`;
  the existing `ko` plays as it does). Then "K.O." as today.
- **Specials.** From the special's wind-up (220 ms before impact, where the arena already darkens) the scene also turns
  red: a `#C1121F` overlay rising to 0.45 over the darkening, held until the special's last mark fades, then released
  over 400 ms. The music ducks to -12 dB over 150 ms and a special riff loop (`special_riff.ogg`, 8 s, at the fight
  track's 120 BPM) plays on top until the impact plus 1 s, then fades over 600 ms as the music comes back. The
  tier-3 cut-in stays as it is.

## F. Sound across the board

- **Weapon impacts.** Each killer's hits sound like its weapon: `hit_<pet>.wav` and `heavy_<pet>.wav` (16 sounds:
  machete chop and thunk, claw rake, chains crack, knife stab, a doll's knife slash, a kitchen knife, a chainsaw rip,
  a bite and a thrash), replacing `hit` and `heavy_hit` for that attacker; `crit` layers over as today. Generated with
  tools/generate_game_sfx.py's pipeline (a new table in it), picked by ear.
- **Chucky's voice.** ElevenLabs' "Angry Kid" / "Angry Doll" voice (found by name in the player's voice library; the
  id goes in the tool as the others' do). In character and foul-mouthed. Home, 7 lines: idle ×3 ("Hi, I'm Chucky.
  Wanna play?", "What the fuck are you lookin' at?", "Hey. HEY. I'm bored over here."), FEED ×2 ("About fuckin'
  time.", "You call this food, bitch?"), PLAY ×2 ("Quit it, asshole!", "Ha! Do that again!"). Fights, 10 lines:
  attack ×2 ("Eat this!", "Say hi to the knife!"), HEAVY ("Chucky's gonna play!"), hit taken ×2 ("Son of a bitch!",
  "That all you got?"), BLOCK ("Nice try, bitch."), SPECIAL ("Friends till the end... bitch!"), win ("Ha ha ha!
  Nobody fucks with the Chuck."), lose ("This isn't over, bitch!"), a miss ("Hold still, dammit!"). Playback: on
  Home an idle line every 25–45 s while nothing else plays (never during an action, a bubble or sleep), one line per
  FEED and PLAY; in a fight at most one line per turn, with a 50% chance, on the matching beat, never over the
  announcer. The hooks (`KillerVoice`, lines by beat per `PetId`) are built so the other killers can get voices one by
  one without touching the fight again.
- **The announcer.** New lines in the arena voice: "FINISH HIM!" (the CPU under 20% HP, as the player's menu opens,
  once a fight), "FLAWLESS VICTORY!" (a win without a scratch, in place of "YOU WIN"), "FIRST BLOOD!" (the first hit
  of a fight), "LAST CHANCE!" (the player under 15%, once), "COMEBACK!" (a win from under 15%), "BOUT TWO!" through
  "BOUT SEVEN!", "FINAL BOUT!" and "CHAMPION!" (the Gauntlet). Generated with tools/generate_announcer.py.
- **Adaptive music.** A second fight track, `fight_intense.ogg` (the same 120 BPM, key and 64 s as `fight.ogg`,
  harder: a doubled kick, a higher lead, more noise), cross-faded in over 1.5 s when either fighter first drops under
  30% HP, and kept to the end. `GameAudio.music` learns a cross-fade (it swaps hard today) that starts the new track
  at the old one's position, so the beat never drops.
- **Haptics (phones).** `GameAudio` buzzes with the sounds it plays, by name: `hit` 15 ms, `heavy_hit` and the heavy
  weapon sounds 35 ms, `crit` 60 ms, `ko` 120 ms, `reel_stop` 25 ms, `pantry_squelch` 40 ms, `pantry_poof` 20 ms,
  `levelup` two 40 ms pulses. Nothing on a TV (no vibrator). The NFC buzz stays as it is.
- **Synthesized, no key needed:** `fight_intense`, `special_riff`, `ko_boom` (a low boom with a sub drop) and the
  snore (a soft cartoon snore loop, 4 s) are made by tools/synth_audio.py, as `fight.ogg` is (`music_fight`, NumPy),
  which keeps the tempo and key by construction. They ship in phase 1.

## G. The pantry, understood

What the other agent built reads well; what it does not do is teach. Three additions, no recipe given away:

- **Ways to cook say what they do.** With a way to cook focused on the shelf (or on the board), the board's name tag
  gets a second line that says what the method does, not what goes in (the recipes are the discovery): GRILL "grills,
  fries and sears", CAMPFIRE "roasts, toasts and pops", POT "boils, poaches and simmers", CAULDRON "brews potions",
  SUN "dries, jerks and rots". A table in `game/pantry/Cookbook.kt`; the test is that every way to cook has a hint
  and at least four recipes.
- **Dishes wear their flavor.** Every snack and meal's shelf label carries a small flavor mark: savory a red drop,
  sweet a pink star, gross a green blot (drawn, no art). The flavor is the dish's; the killer's taste stays the secret.
- **Tastes, once learned, are remembered.** The first time a killer loves or hates a flavor, the pantry header gains
  "LOVES SWEET" / "HATES GROSS" for it: `PetState.tastesLearned: Set<String> = emptySet()` (flavor names, a new last
  field), set in `feed()` on a LOVED or HATED reaction. The favorite dish keeps its heart and its secret. (New
  `PetState` fields, in order: `lastMeal`, `lastMealAtMs`, `tastesLearned`, `gauntletBest`.)
- Left as is, on purpose: the two-foods snacks and the named botches (they are the discovery), the slop rule, the two
  guaranteed pairs, and the recipe marks from 0.8.2.

## Open questions for the player

1. **The tell.** Remove it entirely (my recommendation: pick a move, see what happens, like Pokémon), or keep it but
   always honest (no feints)?
2. **The ElevenLabs key.** The one from earlier in the session was to be rotated. Everything in F needs a current key,
   passed inline on the command line only.

Calls I have made that the player may want to change: the Gauntlet opens only once every killer, the secret one too,
is in the stable (until then FIGHT is as today); PvP stays as the second card beside the Gauntlet; a run's hunger and
mood effects are paid once at its end; walking away from a bracket costs nothing; meal perks and the Gauntlet are
CPU-only; sleep is 23 to 6 by the device's clock; a meal's status lasts until the next meal or 24 hours; the method
hints describe the cooking, not the ingredients.

## Phases

1. **Feel and perks** (no key needed): E (hit-stop, knockout, red specials with the riff and the music ducked), F's
   synthesized sounds and music (`fight_intense` with the cross-fade, `special_riff`, `ko_boom`, the snore) and
   haptics, A, C, G, and D once answered. Release 0.9.0.
2. **Voices and weapons** (key needed): weapon impacts, Chucky, the announcer's new lines. Release 0.9.1.
3. **The Gauntlet** (B). Release 0.10.0.

## Left to the builder

The exact overlay colours and durations above are starting values, tuned by eye on the TV emulator; the Zzz and
chip art; where the Gauntlet bracket's heads come from (the stable's portraits); how `GameAudio` mixes the riff over
the ducked track; the pure-function names.
