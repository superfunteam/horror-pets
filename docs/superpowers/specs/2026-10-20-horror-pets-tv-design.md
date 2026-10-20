# Horror Pets for Android TV: Design

Date: 2026-10-20
Status: draft, awaiting approval

## Intent

Horror Pets is a Tamagotchi for the living room. Four chibi slasher icons (Jason, Freddy, Pinhead, Ghostface) live on your TV. You check in on them, feed them, play with them, train them, and make them fight each other in Street-Fighter-style VS bouts. Stats drain in real time while you're away, so coming back each night matters.

The art is glossy, 3D-rendered chibi sprites, and the app must look just as slick: cinematic, dark and polished. **Not pixel art and not retro.**

## Goals

- An Android TV app (D-pad remote, 10-foot UI) that sideloads as an APK.
- Four pets that live in the app at once (Ghostface was added after the first draft). Each pet keeps its own stats, which decay in real time based on time since the last visit.
- The app opens on the **last selected pet**, with stats already adjusted for time away and a short summary of what changed.
- Four actions: **Play, Feed, Train, Fight.**
- Turn-based fights against another pet, with a VS screen, floating damage numbers, crits and misses, and a signature special per character.
- Three movie-accurate scenes per character (12 total), generated with OpenAI's image model, that the sprites look at home in.
- Works on 16:9 and 4:3 displays, adapting to whatever the system reports.
- A clean repo. Pushing a release tag builds a signed APK and attaches it to a GitHub Release.

## Non-goals (v1)

- In-app auto-update. That comes later; v1 only publishes the APK to GitHub Releases.
- Play Store listing, multiplayer or 2-player fights, cloud save, accounts, notifications, in-app purchases.
- Any network access at runtime. The app is fully offline.
- More characters.

## Anti-goals (would make this a failure even if it "works")

- It looks retro, cheap or templated: pixel fonts, flat default Material, generic purple-gradient UI.
- Sprites look pasted on: halos, chroma fringe, wrong lighting, floating feet, jittery baselines between animations, or cropped characters.
- Any part of the UI can't be reached or understood with a D-pad remote, or important UI falls outside the TV safe area.
- 4:3 looks broken: letterboxed UI, cut-off characters, or a stretched background.
- The OpenAI API key appears anywhere in the repo, its history, CI config or any committed file. The repo is **public** (`superfunteam/horror-pets`).

## Constraints

- Platform: Android TV / Google TV (should also run on Fire TV). Remote input only: D-pad, OK, Back, Menu, and media keys where present.
- Source art lives in `sprites-source/` (236 MB of working files, zips, scripts and previews). It is **not committed**. The clean output goes to `sprites-final/`.
- Movie music, audio clips and logos may not be used. Audio must be original or CC0.
- OpenAI key: use it only through the `OPENAI_API_KEY` environment variable in the local generation shell. Never write it to a file. The user will rotate it after we're done.

---

## Decided with the user

### Core loop (Tamagotchi)
- On launch, the app goes straight to the last selected pet. All four pets' stats are recomputed from the elapsed time. A "welcome back" toast shows the time away and the biggest change (for example, "Away 2d 4h · Fun −38").
- **Fake death:** after a long absence, the pet appears dead: a tombstone, a sad sting and "R.I.P. JASON". Then a glitch and the reveal: **"GOTCHA. JASON NEVER DIES."** The pet pops back up, weak and grumpy with its stats near the floor. There is no real permadeath.

### Actions
Each action plays a sprite animation, changes stats and gives a sound or music response.

| Action | Animation | Stat effect (direction decided by user) |
|---|---|---|
| Feed | the pet's hand-to-mouth row (Jason: `review`; Freddy: row 7 `work`; Pinhead and Ghostface: whichever row reads best, chosen at intake and recorded in its manifest as `feed`) | **Hunger goes down** |
| Play | `jumping`, then `waving` | Fun up, **hunger goes up** |
| Train | `attack` | **Hunger goes up**, **strength goes up some** |
| Fight | see Fights | XP, mood and hunger changes |

Hunger is a "need" meter: 0 means full and 100 means starving. It rises over time.

### Scenes (3 per character, from the movies)
- **Jason (Friday the 13th):** Camp Crystal Lake dock at dusk, a rundown counselor cabin interior, foggy woods by the lake.
- **Freddy (A Nightmare on Elm Street):** the boiler room (dream world), a teen's Elm Street bedroom at night, the 1428 Elm St house front.
- **Pinhead (Hellraiser):** the attic with the puzzle box, the Labyrinth corridors, a chain-filled hell chamber.
- **Ghostface (Scream):** a high school; outside the house, with the news van parked in the driveway (a gray-green-trimmed Victorian two-story at dusk, plain white vintage van with the door open); inside the kitchen (white cabinets, glass-front cupboards, white tile, blue glass pendant lamps). The user's reference stills set the look only. Prompts describe the places in words, with no people and no logos or lettering on the van.

Press left or right to walk the pet (using the run sprites) into the next scene, which wraps around. The fight arena is a random scene belonging to the defender.

### Home screen: layout A (the layout, not the exact visual design)
- Stat card in the top-left: name, level, and Hunger / Fun / Strength / Mood bars.
- A row of action pills along the bottom center: Play · Feed · Train · Fight.
- Scene indicator dots at the top center, and the welcome-back toast in the top-right.
- The pet stands center-stage on the scene's floor.

### Pet switcher: full-screen "Choose your killer"
- All four pets stand **uncropped, idle, each in its own spotlight** on a dark stage. The selected pet gets a red-tinted beam and full color. The others are dimmed and desaturated in near-darkness.
- Each pet shows its name, level and a status tag (Content / Bored / Hungry / Starving / Grumpy).
- Left and right move the spotlight, OK selects and Back cancels.
- The same stage is used to **pick a fight opponent** (with the player's own pet excluded).
- Mockup: `.superpowers/brainstorm/*/content/pet-switcher-v3.html`.

### Fights
- Start: choose an opponent on the spotlight stage → **VS screen** → arena (the defender's scene).
- Every turn, both sides pick a move at the same time. The CPU picks in secret.
  - **Strike**: fast, normal damage.
  - **Heavy**: big damage and breaks Block, but a Strike interrupts it.
  - **Block**: cancels a Strike and lands a small counter.
  - **Run**: forfeit, with a small mood penalty.
  - The cycle: Strike beats Heavy, Heavy beats Block, Block beats Strike.
- Hits show **big floating damage numbers** in the classic RPG style. A **CRITICAL!** shows a big number and screen shake. A miss shows **MISS**.
- Landing hits charges a **signature special**: Jason's Machete Frenzy, Freddy's Dream Claws, Pinhead's Chains and Ghostface's Knife Flurry. When the meter is full, Special appears as a fifth move.
- Pet stats feed the fight: Strength raises damage, a lower Hunger means more effective HP, and Mood raises crit chance. Winning gives XP and mood. Opponents are CPU-controlled.

### Release
- Pushing a `v*` tag makes GitHub Actions build a signed release APK and attach it to a GitHub Release.
- Auto-update is **later**.

---

## My calls (the user hasn't weighed in on these; marked so they can be checked)

### Tech stack
- Native **Kotlin + Jetpack Compose** with `androidx.tv` (tv-material), in a single Activity. Sprites are drawn on a Compose `Canvas` from PNG strips, with bilinear filtering. The game state machine is plain Kotlin and has unit tests.
- Persistence: DataStore holding one JSON document (kotlinx.serialization).
- Audio: `SoundPool` for SFX and Media3/ExoPlayer for looping music.
- minSdk 22 (so older Fire TV on Fire OS 5/6 can install), compile/target the latest installed SDK (36). JDK 17. Gradle wrapper committed.
- Why native over a game engine: the app is mostly 2D UI plus sprite animation. Compose gives first-class D-pad focus handling, a small APK and a clean repo without engine boilerplate.

### Repo layout
```
app/                      Android app module
sprites-final/<pet>/      clean sprite strips + manifest.json (committed)
scenes-final/<pet>/       final scene images + scenes.json (committed)
audio-final/              music, sfx, announcer (committed)
tools/                    intake + generation scripts (Python), reproducible
docs/                     specs, plans
.github/workflows/        ci.yml (PRs/main), release.yml (tags)
```
`app` pulls `sprites-final/`, `scenes-final/` and `audio-final/` in as asset dirs through Gradle, so the art has one source of truth. `.gitignore` covers `sprites-source/`, `.superpowers/`, `.DS_Store`, build output, `local.properties`, keystores and any raw generation output.

### Sprite intake → `sprites-final/`
- **Use the largest originals (decided with user).** The 192×208 sheets are too small for a TV, so `sprites-final` is built from the full-resolution generator output (~724 px tall, on a magenta `#FF00FF` key):
  - Pinhead: `sprites-source/pinhead/decoded/` (attack: `sprites-source/pinhead/attack-v1/decoded/running.png`)
  - Ghostface: `sprites-source/ghostface/full-resolution/original-generation/` (attack: `sprites-source/ghostface/full-resolution/attack/attack-strip.png`). Some rows have several takes (`-v1`, `-v2`…); the intake tries every take and keeps the one that best matches the reference.
  - Jason: `../Picture Parrot/output/pets/jason-voorhees/decoded/` (attack: `sprites-source/jason/attack/decoded/attack.png`)
  - Freddy: `../Picture Parrot/Pets/Freddy Krueger/decoded/` (attack: `sprites-source/freddy/attack/decoded/attack.png`)
  - The intake script also searches for anything larger, and copies the chosen hi-res inputs into `sprites-source/` so everything lives in one (uncommitted) place.
  - The 192×208 sheets (`freddy/spritesheet-extended.png`, `jason/spritesheet-extended.png`, `pinhead/final/spritesheet-extended.png`, `ghostface/ghostface-spritesheet.png`; attack refs include row 7 of `ghostface/attack/ghostface-attack.png`) are the **reference for frame order, frame count, and registration**. Each hi-res frame must match its low-res counterpart when downscaled. A row that has no clean hi-res source falls back to an AI-upscaled low-res row, and the fallback is flagged in the QA report.
- Output cell size: **384×416 (2× the original grid)**, so the pet stays crisp at TV sizes with only a small downscale.
- Output per pet: one horizontal PNG strip per animation (`idle.png`, `run-right.png`, `run-left.png`, `wave.png`, `jump.png`, `fail.png`, `wait.png`, `work.png`, `review.png`, `look-a.png`, `look-b.png`, `attack.png`). A `manifest.json` per pet lists frames, per-frame durations, the loop flag, the **foot baseline y**, the horizontal anchor, and which strip plays `feed`.
- File names and manifests are identical across all four pets. No stray files.
- The intake script (`tools/intake_sprites.py`) keys out the magenta with despill so no pink fringe remains, splits the frames, and registers each one to the low-res reference. It validates and fails loudly on: chroma-key residue or fringe, empty cells, and baseline drift between animations of more than a few px. It corrects drift by translating whole frames so the feet sit on a shared baseline. It also writes a QA contact sheet for each pet to the scratch area (not committed), which gets reviewed by eye before anything moves on.

### Scenes → `scenes-final/`
- Model: the **latest `gpt-image-*` model** available on the account at generation time (check `/v1/models`). Use the largest landscape size and "high" quality.
- Style prompt shared by all twelve: cinematic 3D-rendered environment in the same glossy stylized look as the sprites, with soft front key light and moody rim/ambient light. **No characters or people**, no text or logos. A clear, readable floor plane in the lower third, and a calm center so the pet reads clearly. Prompts describe places, not character names.
- Composition: everything important stays inside the centered 4:3 region of a 16:9 frame, so both aspect ratios work by cropping, never by letterboxing.
- Each scene gets metadata in `scenes.json`: floor line y (normalized), pet x positions, a **sprite tint** color and strength (a 10–15% lerp toward the scene's ambient light so the sprites sit in the scene), shadow opacity, and accent color for that scene's UI glow.
- Review loop: composite every candidate with the pet's idle frame plus a contact shadow, look at it, and regenerate until the sprite sits naturally. Commit only the chosen images, converted to high-quality WebP.

### Aspect ratio and TV layout
- The app is full-bleed. The background scales to cover and crops whichever axis overflows, around a center anchor. The floor line is kept at the metadata's y.
- UI is anchored inside a 5% overscan-safe inset. Pet scale is based on screen height so pets look the same size on 4:3 and 16:9.
- Supported aspect ratios are whatever the window reports, tested at 16:9 (1920×1080) and 4:3 (1440×1080).
- **Target device (from user):** an Onn Google TV stick, modded globally to output 4:3 to a **CRT TV**. The system reports the 4:3 window, so automatic detection is enough and **no in-app aspect setting** is needed. Because it's a CRT, text and UI must stay legible on a soft, low-res picture: bold display type, no hairlines or thin strokes, minimum body text around 24 px at 1080p, high-contrast focus states. Small text sits inside the 5% safe inset. The app must also run on any other Android TV, 16:9 included.

### Focus and navigation on the home screen
- The action bar has focus by default. Its row is `◀ | PLAY FEED TRAIN FIGHT | ▶`, where the edge arrows are focusable and walk to the previous or next scene.
- Up from the action bar focuses the stat card, and OK on it opens the switcher. The Menu key also opens the switcher, and media rewind/fast-forward also walk scenes.
- Back from home exits the app. Back in a fight asks "Run?".

### Stat model (starting values; all are tunable constants in one file)
- Hunger 0–100, rises 4/hr.
- Fun 0–100, falls 3/hr.
- Strength 0–100, decays 1 per 6 hr.
- Mood is derived: it blends low hunger and high fun, plus a short-lived bonus from wins and play.
- XP/Level: level n→n+1 costs 100·n XP. Train +10, Play +5, fight win +40, loss +15.
- Feed: hunger −35. The pet refuses ("too full", fail anim) if hunger < 10.
- Play: fun +25, hunger +8.
- Train: strength +6, hunger +12, fun −5. The pet refuses if hunger ≥ 85.
- Fight: hunger +15. The pet refuses to fight if hunger ≥ 85. Win: mood bonus. Loss: fun −5.
- Elapsed time uses the wall clock, stored as the last-seen timestamp. If the clock goes backwards, elapsed counts as 0. Elapsed is capped at 30 days.
- **Fake death trigger:** away for 7 days or more. Afterward the pet resets to hunger 90, fun 10 and strength −20%, keeping its level. Each pet shows its fake death once, the first time it's viewed after the absence.
- First launch: all pets start at level 1 with good stats, and the switcher appears as onboarding ("Choose your killer").

### Fight numbers (starting values)
- HP = 60 + 8·level, multiplied by (1.0 → 0.7 as hunger goes 0 → 100).
- Base damage = 8 + strength/8, ±15% variance. Heavy is ×1.8, Block counter ×0.5, Special ×2.5 and unblockable.
- Crit chance 5% + mood/100·15%, at ×1.75. Miss chance 8% (Heavy 15%).
- Character passives: Jason +20% HP; Freddy +8% crit; Pinhead's block counters do ×1.0 instead of ×0.5; Ghostface is slippery, so attacks against him miss +7% more often.
- Special meter: +25 per landed hit, full at 100.
- CPU picks moves with weighted randomness and counters the player's most-used move 40% of the time. Opponent level is the player's level ±1, so fights stay fair even if the other pet has been neglected.
- RNG is seedable, so the fight engine can be unit-tested deterministically.

### Fight presentation
- **VS screen:** a diagonal split, with each side tinted with its fighter's accent color. Both fighters are large and idle, the names slam in and a big "VS" lands with screen shake. Then "FIGHT!".
- **One round, first KO wins** (my call: turn-based rounds already run long on a TV). The announcer set still includes "ROUND 2" and "FINAL ROUND" so best-of-three stays possible after the key is rotated.
- **Arena:** HP bars and names along the top in Street-Fighter style, with a special meter under each. The player stands on the left and the CPU on the right, mirrored so attacks face each other. The move menu runs along the bottom.
- **No new sprite animations in v1** (my call). The moves are told apart with timing, scale and VFX: Strike is a quick partial attack loop, Heavy is a held windup then the full attack plus impact flash, Special is the full attack with a character VFX overlay (Jason's red slash trails, Freddy's dream-warp/green glow, Pinhead's chains drawn as a VFX layer over the opponent, Ghostface's flurry of white knife streaks with a phone-ring sting), and Block is the `wait` pose plus a shield-flash ring. Attack sprites face the camera, so the CPU fighter is **not mirrored** (that would put Freddy's glove on the wrong hand). Fighters step toward each other with the run rows, then attack facing front.
- **A turn:** the attacker uses the run anim to step in, plays attack, and the defender flashes and shakes. The number floats up. On KO the loser plays `fail` and the winner plays `jump` then `wave`. A results card shows XP and stat changes.

### Audio (original/CC0 only)
- One original ambient music loop per character, generated or synthesized by a committed script where feasible: crickets and a whispery pulse for Jason, a music-box lullaby for Freddy, industrial drones and bells for Pinhead, tense pizzicato strings and an original phone-ring motif for Ghostface. Plus a separate fight track.
- SFX (chomp, swoosh, hit, crit, block, UI move/select, tombstone sting) come from CC0 packs or synthesis. Licenses are listed in `audio-final/CREDITS.md`.
- **Announcer** ("GHOSTFACE… VERSUS… FREDDY!", "FIGHT!", "K.O.!", "CRITICAL!", "GOTCHA!", "[NAME] NEVER DIES") is generated once with OpenAI's TTS model, using a deep, dramatic voice style, and committed as audio files.

### Visual design direction
- Dark and cinematic: frosted-glass panels over the scene, a condensed display face (OFL-licensed, bundled, such as Barlow Condensed) for names and numbers with a clean sans for small text, a blood-red accent with an amber warning color, and soft glows on focus.
- Focus states are big and obvious from 10 feet: scale plus a glow. Motion is smooth and short. Sprites get contact shadows and the scene tint.
- The TV launcher banner (320×180) and app icon are composed from the sprites and scenes.

### CI and release
- `ci.yml` runs on PRs and pushes to main: assemble, unit tests and lint.
- `release.yml` runs on `v*` tags. It builds a release APK, signs it with a keystore held in GitHub Actions secrets (base64 keystore plus passwords), and creates a GitHub Release with `horror-pets-<version>.apk` and auto-generated notes. versionName comes from the tag, and versionCode is derived from the tag (major·10000 + minor·100 + patch).
- The release keystore is generated once, kept outside the repo, and must stay the same forever, so future auto-updates can install over old versions. Setting the GitHub secrets needs the user's go-ahead.
- README: what it is, a screenshot, sideload instructions (adb, and the Downloader app on Fire TV / Google TV) and how to cut a release.

### Verification
- Unit tests: decay over elapsed time, clock-skew handling, the fake-death trigger, action effects and refusals, level-ups, fight resolution matrix, crit/miss, and specials with a seeded RNG.
- Run the app on an Android TV emulator at 16:9 and 4:3 and capture screenshots of home, switcher, VS, fight, fake death and results.

## Left to the builder
Exact tuning constants, animation timings within each manifest, particle and VFX details, which CC0 packs to use, font choice within the stated direction, and internal code structure.

## Ideas for later (not v1)
- Real-calendar events: Friday the 13th (Jason buff), Halloween (all pets), full moon.
- In-app update check against GitHub Releases.
- Ambient "TV aquarium" mode, where the UI fades out when idle.
- A 2-player hot-seat fight with two remotes.
