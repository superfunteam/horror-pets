# Training mini games

## Why

TRAIN on Home is a button press today: the killer swings twice and strength and XP go up. The player wants training
to be a little more fun, and a way to use the stable (the killers they've beaten) that isn't another fight. So TRAIN
becomes four short arcade games, each played until you mess up, each getting harder as it goes.

Mockups (made from the game's real scenes and sprites, shown to and shaped by the player):
`art-work/minigames/mockups/` — the reel spinning and landed, Slash Says on a phone and on a TV, Quick Draw's D-pad
ring, Hide and Seek, Organ Drop.

## Goals

- TRAIN spins a film reel of the training games, slot-machine style, and lands on one at random; that game starts.
- Four games, each short, each escalating until the first mistake: **Slash Says**, **Quick Draw**, **Hide and Seek**,
  **Organ Drop**.
- Two of them (Quick Draw, Hide and Seek) put a stable mate on screen.
- Every game plays with a TV remote (D-pad + OK) and with touch on a phone, as the rest of the game does.
- Doing better earns more: strength and XP scale with the score; a per-killer best score per game.

## Non-goals

- No game picker: the reel chooses (the player asked for a random, slot-machine spin).
- No new fight mechanics; the stable mate isn't fought, nothing is won or lost but strength and XP.
- No change to feeding, playing, fighting or the stable itself.
- No online or two-player mini games.

## Anti-goals (a failure even if it works)

- Training that pays less than today's TRAIN: a score of 0 still earns today's reward.
- A game a TV remote can't play comfortably from the couch.
- Losses that feel unfair: a dot you couldn't reach, an organ you couldn't get to, a call before you could see it.
- Gore. The organs are chunky, glossy toys (see the art), cute-gross, never realistic.

## What's decided

### TRAIN and the reel

- TRAIN with a starving pet (hunger at or above `Tuning.TRAIN_REFUSE_AT`) is refused exactly as today (FAIL, "Too
  hungry to train").
- Otherwise the view model picks the game and opens a new full-screen `Screen.Training`. Eligible games: Slash Says
  and Organ Drop always; Quick Draw and Hide and Seek only when the player has another killer in the stable. The
  pick is random among the eligible ones, never the same game twice in a row when there's a choice.
- The stable mate, for the games that use one, is a random other killer from the stable.
- The screen opens on a film reel (the trailer's environments reel look: black film edges with sprocket holes,
  grain, flicker): each training game is a frame (the game's look on the pet's scene, its name). It spins fast,
  blurred, decelerates and lands on the chosen game with a clunk; "GET READY..." and a beat later the game starts.
  The reel only shows eligible games. No buttons on the reel.
- The film stays: the frame the reel lands on *is* the game. Every game plays inside that film frame, the black
  sprocket edges down both sides (still now), a light grain and a projector flicker over the picture, through to the
  results card. It sets training apart from the rest of the game (the player's call).
- Back during the reel leaves training with nothing gained or spent.

### Every game

- Played on the selected pet's current Home scene, inside the film frame (the play area is the frame between the
  sprocket edges), with the game's HUD: the game's name top left, its live status
  under it (round, streak, caught...), BEST top right, and a help strip at the bottom (key caps on a TV, a line of
  words under a finger, like the rest of the game).
- Ends on the first mistake. Back during a game ends it there, with the score so far.
- Leaving the app mid-game (the TV's Home button, a call, the screen going off) pauses it; coming back resumes it
  after a "GET READY" beat. A paused game's clock stops. If the app is killed while away, the run is lost and nothing
  is paid (the reward is only paid when a game ends). *(My call.)*
- Then a results card in the game's window style: the score, the best (NEW BEST! when beaten), what training earned
  (+strength, +XP, a level-up). OK / a tap goes home (where a level-up's new attack, if any, is offered as usual).
- Rewards are applied the moment the game ends (not when the card is dismissed), and saved.

### Rewards

- Hunger and fun change as today's TRAIN does (`TRAIN_HUNGER`, `TRAIN_FUN`).
- Strength gained = `TRAIN_STRENGTH × m` and XP = `round(XP_TRAIN × m)`, where `m = 1 + 2 × min(score, par) / par`
  (1× at 0, up to 3× at the game's par). Par: Slash Says 8 rounds, Quick Draw 15 draws, Hide and Seek 8 rounds,
  Organ Drop 30 organs.
- Best scores are kept per killer, per game, in the save (a new `PetState` field with an empty default; the save
  version doesn't change).

### Slash Says

- Dots ring the killer (an ellipse around the pet, standing in the middle of the screen). Round 1 has 2 dots; each
  round adds one, up to 8; from then on every round has 8. Score = rounds completed.
- Each round, the dots take new random places (distinct slots on the ring, never behind the killer's body) and a new
  random order, every dot once.
- Each dot has its own color and its own tone (the tone belongs to the dot's place on the ring, so the melody is
  different every round). The round is shown: each dot in order lights and sounds. Faster as rounds go on.
- Then the player repeats it. Phone: swipe through the dots (one stroke or several; a tap counts too); each correct
  dot lights, sounds, and the slash trail joins it to the last. TV: the arrows move an aim ring to the nearest dot
  in that direction (starting from the killer), OK slashes the aimed dot. Every dot must be reachable by the
  arrows from anywhere.
- A wrong dot ends it: the right one flashes, a buzz, the killer's FAIL. A finished round: the killer swings
  (ATTACK) with a slash sound, then the next round is shown.

### Quick Draw (needs a stable mate)

- Your killer on the left, the mate on the right, facing each other. Between them, a segmented ring like a remote's
  D-pad: four wedges (up, down, left, right) around a center OK button, and a thin time ring around it.
- After a random wait (so it can't be anticipated), one part lights: a wedge or the center. Match it before the time
  ring runs out. TV: the D-pad direction or OK. Phone: tap that part of the ring.
- The window starts generous and shrinks every success, to a floor. Wrong part, too slow, or pressing before the call
  ends it. Score = successful draws (the streak).
- A success: your killer slashes (ATTACK), the mate flinches (FAIL). The losing call: the mate slashes, yours flinches.

### Hide and Seek (needs a stable mate)

- Three tombstones in a row, all three the same design in a round, so the shuffle has to be followed; the design
  changes from round to round (the new art: rounded, cross, slab). *(My call: three different stones would let the
  player remember "behind the cross" and ignore the shuffle.)* The mate ducks behind one, then they shuffle (pairs
  swap places, one passing in front of the other). Each round: more swaps, faster. Score = rounds won.
- Pick: TV, Left/Right moves a pointer over the tombstones, OK checks; phone, tap one.
- Right: the mate pops out (JUMP), the next round starts. Wrong: the picked one is empty, then the mate peeks out of
  the right one; game over.
- Your killer watches from the side.

### Organ Drop

- Body parts (heart, brain, eyeball, bone, hand, kidney) fall from the top, one at a time at first, then several in
  the air at once as it speeds up (falling faster, dropping more often). Your killer runs along the floor to catch
  them. Score = organs caught. One that hits the floor ends it (a splat, the killer's FAIL). *(My call.)*
- TV: hold Left/Right to run (the run animations), let go to stop. Phone: hold a finger anywhere and the killer runs
  toward it.
- Every drop must be catchable: the drops form a schedule, and each organ's landing spot is limited to how far the
  killer can run from the previous organ's landing spot in the time between the two landings.

### Art and sound

- Art (done, generated in the game's toy style): `tools/generate_minigame_art.py` → `art-work/minigames/`, picks
  trimmed into `sprites-final/minigames/` like the food (a finalize script).
- Sounds: the dot tones are generated (8 tones; `tools/synth_audio.py`, appended at the end so the other sounds don't
  change); the rest made with ElevenLabs sound generation into `audio-final/sfx/`: the reel's spin and its stop
  clunk, a slash swish, a stone scrape for the shuffle, a squish for a catch, a splat for a drop, a game-over sting.
  Existing sounds are reused where they fit (hit, crit, miss, refuse, levelup).
- Music: the pet's own track, as on Home.

## Constraints (from the codebase)

- Kotlin + Jetpack Compose for TV and phones; screens lay out in dp on a 540 dp-tall design (see `ScreenDensity`).
- A new `Screen` must be handled in the exhaustive `when`s: `HorrorPetsApp`, `MainActivity.musicTrackFor`, and the
  debug puppet's `cueOf`, `goHome` and `describe`.
- `MainActivity.dispatchKeyEvent` ticks `ui_move` on every D-pad press and `ui_select` on OK on every screen except
  the Switcher; the training screen needs the same exemption (a game's presses make their own sounds).
- Don't bump `GameState.version` (it would read every existing save as a fresh game). New per-pet fields get a
  default and a clamp in `SaveCodec.sanitize`.
- Unit tests are pure JVM (no Robolectric, no Compose test rule): game rules live in plain Kotlin, separate from the
  composables, and are tested there; the view model is tested with the existing `GameViewModelTest` rig.
- Today's TRAIN tests change: TRAIN now opens training instead of applying the reward at once.
- The debug puppet's `train` keeps working (it opens training); add a way to force a game and to end it with a score,
  for footage and tests.

## Left to the builder

- Exact timings and curves (show speed, windows, swap speed, fall speed, spawn spacing), within the shapes above,
  tuned by playing.
- Animation details (reel physics, swap arcs, catch pop, trails), the HUD's exact type sizes.
- Slash Says' ring: 8 fixed slots (matching the 8 tones), the dots taking a random subset each round.
- The art picks: the first candidate of each (the mockups' picks) unless one reads badly in the game.
- The stable mate gains nothing from a game (it's training the selected killer).
- How the game-rule state machines are structured, as long as they are plain Kotlin and tested.
- The ElevenLabs prompts and which existing sounds to reuse.
