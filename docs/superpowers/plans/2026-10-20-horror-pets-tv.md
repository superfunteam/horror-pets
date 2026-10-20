# Horror Pets for Android TV: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a sideloadable Android TV virtual-pet game starring Jason, Freddy, Pinhead and Ghostface, with real-time stat decay, care actions, turn-based VS fights, movie scenes, and a tag-driven GitHub Release APK.

**Architecture:** Python tools turn raw art into committed, clean asset folders (`sprites-final/`, `scenes-final/`, `audio-final/`). A single-module Kotlin/Compose-for-TV app syncs those folders into its assets at build time. All game rules live in plain, unit-tested Kotlin (`game/`, `fight/`). Compose screens are driven by one `GameViewModel` state machine.

**Tech Stack:** Python 3 (Pillow, NumPy, SciPy, pytest, requests), ffmpeg, cwebp, OpenAI Images + TTS APIs, Kotlin, Jetpack Compose + `androidx.tv:tv-material`, kotlinx.serialization, DataStore, Media3 ExoPlayer, SoundPool, JUnit4, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-10-20-horror-pets-tv-design.md`

## Global Constraints

- Package / applicationId: `team.superfun.horrorpets`. App label: `Horror Pets`.
- minSdk 22; compileSdk/targetSdk 36; JDK 17. Dependency versions are the latest stable at execution time and pinned in `gradle/libs.versions.toml`.
- Manifest: `android.software.leanback` required=false, `android.hardware.touchscreen` required=false, launcher intent with `LEANBACK_LAUNCHER` (plus `LAUNCHER` so it also opens on non-TV devices), `android:banner` set.
- The OpenAI key is only ever passed inline as the `OPENAI_API_KEY` env var on the command that needs it. It is never written to any file, committed, echoed, or put in CI. The repo is public.
- `sprites-source/`, `.superpowers/`, `art-work/` (raw generation output), `tools/.venv/`, keystores, `local.properties`, build dirs and `.DS_Store` are gitignored.
- No pixel art, pixel fonts or retro styling. Sprites are drawn with bilinear filtering.
- No movie audio, music, logos or names in image prompts. Audio is original (synthesized) or TTS.
- UI stays inside a 5% safe inset on every edge. Minimum body text is 24sp-equivalent at 1080p. No hairlines (strokes ≥ 2dp). Everything is reachable by D-pad.
- Hunger is a need meter: 0 = full, 100 = starving. **Feed lowers hunger; Play and Train raise it.**
- Fake-death copy: `R.I.P. {NAME}` → `GOTCHA.` → `{NAME} NEVER DIES.` (NAME uppercase: JASON, FREDDY, PINHEAD, GHOSTFACE).
- Python tools run with `tools/.venv/bin/python -I`. Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

- **Clock wrong at boot** (TV sticks often read 1970 or a stale date before network time syncs): a backwards clock must never cause a bogus fake death or a big decay later. `lastSeen` never moves backwards. Covered in Task 5.
- **TV left on for hours with the app open** (or screensaver, then resume): stats must keep decaying while the app is foregrounded and on resume, and applying 10h once must equal 5h twice. Covered in Tasks 5 and 10.
- **Remote key-repeat / mashing OK during an animation**: an action applies exactly once, and presses during the animation are ignored. Covered in Task 10.
- **Missing, corrupt or old-version save file**: the app loads defaults (first-launch onboarding) and never crashes. Covered in Task 7.
- **Low-res 4:3 output on the CRT** (e.g. 720×540 or 640×480): text stays legible, nothing is clipped, and pets stand on the floor line. Covered in Tasks 9 and 18.

---

## File Map

```
.gitignore, README.md
tools/requirements.txt                    pillow numpy scipy pytest requests
tools/spritekit.py                        keying, despill, component split, registration (pure functions)
tools/intake_sprites.py                   CLI: sources → sprites-final/<pet>/ + QA sheets
tools/sprite_sources.json                 per-pet, per-animation source paths + frame counts + timings
tools/scene_prompts.json                  12 scene prompts + shared style prompt
tools/generate_scenes.py                  OpenAI image gen → art-work/scenes/, sprite composites
tools/finalize_scenes.py                  chosen PNG → scenes-final/<pet>/<id>.webp
tools/synth_audio.py                      NumPy synth → audio-final/music, audio-final/sfx (ogg)
tools/generate_announcer.py               OpenAI TTS → audio-final/voice (ogg)
tools/make_branding.py                    banner + launcher icons from sprites/scenes
tools/tests/test_spritekit.py
sprites-final/<pet>/{idle,run-right,run-left,wave,jump,fail,wait,work,review,look-a,look-b,attack}.png + manifest.json
scenes-final/<pet>/<id>.webp, scenes-final/scenes.json
audio-final/{music,sfx,voice}/*.ogg, audio-final/CREDITS.md
settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml, gradlew*, gradle/wrapper/*
app/build.gradle.kts                      asset sync task, signing from env, version from -P props
app/src/main/AndroidManifest.xml
app/src/main/java/team/superfun/horrorpets/
  MainActivity.kt
  model/Model.kt                          PetId, PetState, GameState
  game/Tuning.kt                          every tunable constant
  game/Decay.kt, game/Mood.kt, game/Status.kt, game/Leveling.kt, game/Care.kt
  fight/FightModel.kt, fight/FightEngine.kt, fight/CpuBrain.kt, fight/Rewards.kt
  data/Clock.kt, data/SaveCodec.kt, data/SaveRepository.kt
  assets/SpriteManifest.kt, assets/SpriteAnimator.kt, assets/SceneCatalog.kt, assets/CoverCrop.kt, assets/AssetLoader.kt
  audio/GameAudio.kt
  ui/GameViewModel.kt, ui/Screen.kt, ui/HorrorPetsApp.kt
  ui/theme/{Color,Type,Theme}.kt
  ui/common/{SpriteView,SceneBackdrop,GlassPanel,FocusPill}.kt
  ui/home/{HomeScreen,StatCard,ActionBar,WelcomeToast}.kt
  ui/switcher/SwitcherScreen.kt
  ui/fakedeath/FakeDeathScreen.kt
  ui/fight/{VersusScreen,FightScreen,FloatingNumbers,FightVfx,ResultsCard}.kt
app/src/main/res/font/{barlow_condensed_bold,barlow_condensed_extrabold,inter_regular,inter_semibold}.ttf
app/src/main/res/drawable-xhdpi/banner.png, mipmap-*/ic_launcher*.png
app/src/test/java/team/superfun/horrorpets/...   JVM unit tests
.github/workflows/ci.yml, .github/workflows/release.yml
```

---

### Task 1: Repo hygiene + sprite intake → `sprites-final/`

**Files:**
- Create: `.gitignore`, `tools/requirements.txt`, `tools/spritekit.py`, `tools/sprite_sources.json`, `tools/intake_sprites.py`, `tools/tests/test_spritekit.py`, `sprites-final/**`

**Interfaces:**
- Produces (consumed by Task 9): `sprites-final/<pet>/manifest.json`:
  ```json
  {"pet":"jason","cell":[384,416],"baselineY":<int>,"anchorX":192,"feed":"review",
   "animations":{"idle":{"file":"idle.png","frames":6,"durationsMs":[...],"loop":true}, ...}}
  ```
  The animation keys are exactly: `idle, run-right, run-left, wave, jump, fail, wait, work, review, look-a, look-b, attack`. Each strip is `frames×384` wide and 416 tall, RGBA.
- `spritekit.py` functions (pure, NumPy arrays HxWx4 uint8):
  - `key_magenta(rgb: ndarray) -> ndarray` returns RGBA with soft alpha from the distance to `#FF00FF`, with magenta spill removed.
  - `split_components(rgba: ndarray, expected: int, min_area: int = 2000) -> list[ndarray]` returns `expected` crops ordered left→right. Small blobs merge into the frame whose x-range they overlap. Raises `ValueError` if the large-blob count ≠ `expected`.
  - `alpha_bbox(rgba, thresh=16) -> tuple[int,int,int,int]`
  - `register_to_reference(hi: list[ndarray], ref_cells: list[ndarray], cell=(384,416)) -> list[ndarray]` uses one uniform scale per row (the median of ref-bbox-height×2 / hi-bbox-height), places each frame's bbox bottom-center on its 2× reference bbox bottom-center, then refines the translation within ±8px by maximizing alpha IoU against the 2× upscaled reference alpha.
  - `iou_vs_reference(cell_2x: ndarray, ref_cell: ndarray) -> float` downscales to 192×208 and returns the alpha IoU.
  - `fringe_ratio(rgba) -> float` is the fraction of pixels with alpha>0 where R−G>40 and B−G>40.

- [ ] **Step 1: Hygiene.** Write `.gitignore` covering the Global Constraints list plus `.gradle/`, `build/`, `*.jks`, `*.keystore`, `__pycache__/`. Write `tools/requirements.txt`. Create the venv with `python3 -m venv tools/.venv && tools/.venv/bin/pip install -r tools/requirements.txt`.

- [ ] **Step 2: Write the failing tests** in `tools/tests/test_spritekit.py`:
  - `test_key_magenta_pure_key_is_transparent`: a 4×4 image of `(255,0,255)` → all alpha == 0.
  - `test_key_magenta_keeps_subject_opaque`: a `(40,60,30)` pixel → alpha == 255, RGB unchanged.
  - `test_key_magenta_despills_edge`: a `(200,90,200)` pixel → output `R−G < 40` and `B−G < 40`.
  - `test_split_components_orders_left_to_right_and_merges_specks`: three 60×120 blocks at x=10, 200, 400, plus a 5×5 speck at x=215, `expected=3` → 3 crops, and the second crop's bbox includes the speck.
  - `test_split_components_wrong_count_raises`: two blocks with `expected=3` → `ValueError`.
  - `test_register_matches_reference`: build a reference 192×208 cell with a 40×150 opaque block at x=70, y=40. Build the hi-res source as the same block ×3.6. `register_to_reference` → `iou_vs_reference ≥ 0.95`, and the output bbox bottom == 2×(ref bbox bottom) ±1.

- [ ] **Step 3: Run the tests to make sure they fail.** Run `tools/.venv/bin/python -I -m pytest tools/tests -q`. Expected: FAIL (module missing).

- [ ] **Step 4: Implement `tools/spritekit.py`** to the signatures above. Use `scipy.ndimage.label` for the components. Soft alpha is a linear ramp on the RGB distance to the key: ≤60 → 0, ≥140 → 255. Despill: `spill = max(0, min(R,B) − G)`, subtracted from R and B.

- [ ] **Step 5: Run the tests to make sure they pass.** Run `tools/.venv/bin/python -I -m pytest tools/tests -q`. Expected: all PASS.

- [ ] **Step 6: Write `tools/sprite_sources.json`.** For each pet, give the low-res reference sheet (8×11 grid of 192×208) plus each animation's `row`, `frames`, `hires` path and `durationsMs`. Low-res references: `sprites-source/freddy/spritesheet-extended.png`, `sprites-source/jason/spritesheet-extended.png`, `sprites-source/pinhead/final/spritesheet-extended.png`. Row map (same for all pets): idle 0 (6), run-right 1 (8), run-left 2 (8), wave 3 (4), jump 4 (5), fail 5 (8), wait 6 (6), work 7 (6), review 8 (6), look-a 9 (8), look-b 10 (8). Hi-res rows (the decoded file whose name matches the state; `look-row-9.png` → look-a, `look-row-10.png` → look-b):
  - Jason: `../Picture Parrot/output/pets/jason-voorhees/decoded/`
  - Freddy: `../Picture Parrot/Pets/Freddy Krueger/decoded/`
  - Pinhead: `sprites-source/pinhead/decoded/`. Its run-left has no hi-res row, so mark it `"derive": "mirror:run-right"`.
  - Ghostface: `sprites-source/ghostface/full-resolution/original-generation/`, with the low-res reference `sprites-source/ghostface/ghostface-spritesheet.png`. Some rows have several takes (`running-right.png`, `-v1`, `-v2`; `look-row-10.png`, `-v1`…`-v4`), so `hires` may be a **list of candidates**. The intake registers each one and keeps the take with the highest mean IoU, logging which won.
  - Never use files named `*rejected*`.
  - Attack: Jason `sprites-source/jason/attack/decoded/running.png`, 6 frames, reference = `sprites-source/jason/attack/attack-strip.png`. Freddy `sprites-source/freddy/attack/decoded/attack.png`, 8 frames, reference = `sprites-source/freddy/attack/attack-strip.png`. Pinhead `sprites-source/pinhead/attack-v1/decoded/running.png`, 6 frames, reference = row 7 of `sprites-source/pinhead/attack-v1/final/spritesheet-extended.png`. Ghostface `sprites-source/ghostface/full-resolution/attack/attack-strip.png`, 6 frames, reference = row 7 of `sprites-source/ghostface/attack/ghostface-attack.png`.
  - Attack durations come from each `animation.json`. Pinhead and Ghostface copy Jason's if they have none.
  - Defaults otherwise, in ms per frame: idle 150, run 90, wave 160, jump 120, fail 140, wait 160, work 150, review 150, look 140.
  - Loop = true for idle, run-*, wait and look-*. Everything else is one-shot.
  - `feed`: Jason `review`, Freddy `work`. Pinhead and Ghostface are decided in Step 8.
  - Before using a path, copy the Picture Parrot `decoded/` folders into `sprites-source/<pet>/hires/`, and point the JSON at the copies so every source lives under `sprites-source/`.

- [ ] **Step 7: Implement `tools/intake_sprites.py`** (`--out sprites-final --qa art-work/sprite-qa`). For each pet and animation it keys, splits, registers and validates the frames, then writes the strip and the manifest. Validation is per frame:
  - `iou_vs_reference ≥ 0.88`. Otherwise it falls back to a Lanczos 2× upscale of the reference cell and logs `FALLBACK pet/anim/frame`.
  - `fringe_ratio < 0.001`.
  - Not empty.

  Then it computes `baselineY` as the median lowest opaque row across the idle frames, and fails if any animation's median foot row differs from it by more than 4px. It writes a contact sheet per pet (all strips on a mid-grey background, scaled 50%) to the QA folder. The script exits non-zero on any failure.

- [ ] **Step 8: Run and review.** Run `tools/.venv/bin/python -I tools/intake_sprites.py --out sprites-final --qa art-work/sprite-qa`. Expected: exit 0 and a printed summary with a fallback count (target 0; investigate any fallback by viewing it). Open each QA contact sheet with the Read tool and check four things: no pink fringe, the feet are steady across frames, nothing is cropped, and every frame shows the same character. Pick Pinhead's and Ghostface's `feed` (the row that best reads as hand-to-mouth, `review` or `work`), record it, and re-run.

- [ ] **Step 9: Commit.**
  ```bash
  git add .gitignore tools/requirements.txt tools/spritekit.py tools/sprite_sources.json tools/intake_sprites.py tools/tests sprites-final
  git commit -m "feat(art): clean hi-res sprite intake into sprites-final"
  ```

### Task 2: Scenes → `scenes-final/`

**Files:**
- Create: `tools/scene_prompts.json`, `tools/generate_scenes.py`, `tools/finalize_scenes.py`, `scenes-final/<pet>/<id>.webp`, `scenes-final/scenes.json`

**Interfaces:**
- Produces (consumed by Task 9): `scenes-final/scenes.json`:
  ```json
  {"jason":[{"id":"dock","title":"Crystal Lake Dock","file":"jason/dock.webp","floorY":0.80,
             "tint":"#6A5A8C","tintStrength":0.12,"shadowOpacity":0.55,"accent":"#E0262F"}, ...], ...}
  ```
  The scene ids, in order, are: jason `dock, cabin, woods`; freddy `boiler, bedroom, house`; pinhead `attic, labyrinth, chamber`; ghostface `school, house, kitchen`. `floorY` is the normalized y in the source image where the feet go.

- [ ] **Step 1: Write `tools/scene_prompts.json`.** It holds one shared `style` string and twelve `{pet,id,title,prompt}` entries.
  - Style: a cinematic stylized 3D-rendered environment matching glossy collectible-figure renders, with soft frontal key light, moody rim light and shallow atmospheric haze. **No people, no characters, no creatures, no text, no logos.** A wide, clear, flat floor area across the lower third. The center is uncluttered and the important detail sits in the central 4:3 region. Landscape.
  - Prompts describe places only:
    - Jason: a 1980 summer-camp lake dock at dusk with canoes; a rundown wooden camp counselor cabin interior with bunk beds and lantern light; a foggy pine forest at night by a lake.
    - Freddy: an industrial boiler room with steam, pipes and red-orange glow, dreamlike; a 1984 teenager's suburban bedroom at night; a two-story suburban house front at night with an overgrown lawn.
    - Pinhead: a dusty attic with a small ornate gold-and-black puzzle box glowing on the floorboards; endless stone labyrinth corridors with cold blue light; a vast hell chamber with hanging chains and hooks, with bluish industrial light.
    - Ghostface (from the user's reference stills, described only in words): a 1990s California suburban high school at night (the front walkway or a locker-lined hallway, whichever gives the cleaner floor); a two-story gray clapboard Victorian house with dark green trim and glowing windows at foggy dusk, with a plain white vintage van with no markings and its door open, parked on the driveway; a bright 1990s suburban kitchen at night with white cabinets, glass-front cupboards, a white tile backsplash and blue glass pendant lamps over the island.

- [ ] **Step 2: Implement `tools/generate_scenes.py`** (`--only pet/id` optional, `--n 2`).
  - It reads `OPENAI_API_KEY` from the environment and exits if it is missing.
  - It lists `/v1/models`, picks the newest id starting with `gpt-image`, and prints it.
  - It requests the largest supported landscape size at `quality: high`.
  - It saves `art-work/scenes/<pet>/<id>-<k>.png`.
  - For each candidate it writes `<id>-<k>-composite.png`: the 16:9 center crop with that pet's idle frame 0 from `sprites-final` composited at center. The feet go at a guessed `floorY=0.80`, with a soft elliptical shadow, and the sprite is scaled so the cell is 46% of the crop height.
  - It also overlays a 4:3 safe-frame outline.

- [ ] **Step 3: Generate.** Run `OPENAI_API_KEY=<key from chat> tools/.venv/bin/python -I tools/generate_scenes.py --n 2`. Expected: 24 candidates plus composites, and the model id printed.

- [ ] **Step 4: Review and iterate.** View every composite with the Read tool. Reject anything that has people or creatures, text, a floor that's too busy or too low, lighting that clashes with the sprite, or important detail outside the 4:3 frame. Rewrite the prompt and regenerate with `--only` until each scene works. For each keeper, record `floorY` (read off the composite), `tint` (sample the dominant ambient color), `tintStrength` 0.10–0.15, `shadowOpacity` and `accent`.

- [ ] **Step 5: Implement `tools/finalize_scenes.py`** (`--pick jason/dock=2 ...`). It converts each pick to `scenes-final/<pet>/<id>.webp` with `cwebp -q 88` and writes `scenes-final/scenes.json` from a `tools/scene_meta.json` that holds the recorded metadata. Run it for all twelve.

- [ ] **Step 6: Verify.** Run `ls scenes-final/*/*.webp | wc -l`. Expected: `12`. `git grep -n "sk-" -- . ':!sprites-source'` must return nothing.

- [ ] **Step 7: Commit** `tools/scene_prompts.json tools/scene_meta.json tools/generate_scenes.py tools/finalize_scenes.py scenes-final` with the message `feat(art): twelve movie scenes`.

### Task 3: Audio → `audio-final/`

**Files:**
- Create: `tools/synth_audio.py`, `tools/generate_announcer.py`, `audio-final/{music,sfx,voice}/*.ogg`, `audio-final/CREDITS.md`

**Interfaces:**
- Produces (consumed by Task 15). The exact file names:
  - `music/`: `jason.ogg, freddy.ogg, pinhead.ogg, ghostface.ogg, fight.ogg, switcher.ogg`. These are seamless loops of 60–90s.
  - `sfx/`: `ui_move, ui_select, ui_back, chomp, play, train, hit, heavy_hit, crit, block, miss, special_jason, special_freddy, special_pinhead, special_ghostface, ko, tombstone, gotcha, levelup, refuse` (`.ogg`).
  - `voice/`: `jason, freddy, pinhead, ghostface, versus, fight, ko, critical, gotcha, jason_never_dies, freddy_never_dies, pinhead_never_dies, ghostface_never_dies, round_2, final_round, you_win, you_lose, perfect, choose_your_killer` (`.ogg`).

- [ ] **Step 1: Implement `tools/synth_audio.py`.** It is NumPy synthesis at 44.1kHz, written to WAV in `art-work/audio/` and then encoded with `ffmpeg -c:a libvorbis -q:a 5` into `audio-final/`.
  - Music, all original:
    - Jason: crickets (band-passed noise chirps), lake ambience, and a slow breathy pulse.
    - Freddy: an original minor-key music-box melody (sine plus decaying harmonics) over a low pad.
    - Pinhead: detuned low drones, distant bell strikes, and metallic creaks.
    - Ghostface: tense pizzicato strings and an original two-tone phone-ring motif.
    - Fight: a driving percussive loop at about 120 BPM.
    - Switcher: a low ominous drone.
  - Each loop is crossfaded at its seam.
  - SFX are short, peak-normalized to −1 dBFS, with a 5ms fade in and out.

- [ ] **Step 2: Implement `tools/generate_announcer.py`.** It reads `OPENAI_API_KEY`, picks the newest TTS model from `/v1/models` (one that supports `instructions`; otherwise `tts-1-hd`), and uses a deep male voice with the instruction "Over-the-top fighting-game announcer, booming, dramatic, slight reverb-ready delivery". It speaks each line from a dict in the script (for example `"jason_never_dies": "Jason... never dies."`), then trims silence and encodes with ffmpeg to ogg.

- [ ] **Step 3: Generate.** Run `tools/.venv/bin/python -I tools/synth_audio.py`, then `OPENAI_API_KEY=<key> tools/.venv/bin/python -I tools/generate_announcer.py`. Expected: 6 + 20 + 19 files. Check them with `ffprobe` (the durations print without error). Listen to a sample of music and voice via `afplay` and adjust anything harsh.

- [ ] **Step 4: Write `audio-final/CREDITS.md`.** Music and SFX are original and synthesized by `tools/synth_audio.py`. Voice is generated with the OpenAI TTS model `<id>`.

- [ ] **Step 5: Commit** the tools and `audio-final` with the message `feat(audio): synthesized music/sfx and announcer`.

### Task 4: Android scaffold, asset sync, theme, CI

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `gradlew`, `gradlew.bat`, `gradle/wrapper/*`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `MainActivity.kt`, `ui/theme/{Color,Type,Theme}.kt`, `res/font/*.ttf`, `res/values/strings.xml`, `app/src/test/.../SmokeTest.kt`, `.github/workflows/ci.yml`

**Interfaces:**
- Produces: the asset paths at runtime are `sprites/<pet>/...`, `scenes/<pet>/...`, `scenes/scenes.json` and `audio/{music,sfx,voice}/...`.
- Produces: theme tokens in `ui/theme/Color.kt`:
  - `Blood = Color(0xFFC1121F)`
  - `BloodGlow = Color(0xFFFF4D57)`
  - `Amber = Color(0xFFE0A326)`
  - `Ink = Color(0xFF07070A)`
  - `Glass = Color(0x990A0A0E)`
  - `TextHi = Color(0xFFF2F2F2)`
  - `TextLo = Color(0xB3F2F2F2)`

  Type in `Type.kt`: `Display` (Barlow Condensed ExtraBold, uppercase usage) and `Body` (Inter).

- [ ] **Step 1: Tooling.** Run `brew install gradle`, then `gradle wrapper --gradle-version <latest stable>` at the repo root. Install the TV image: `~/Library/Android/sdk/cmdline-tools/latest/bin/sdkmanager "system-images;android-34;android-tv;arm64-v8a"` (or the newest android-tv arm64 image listed by `sdkmanager --list`). Create an AVD named `HorrorPets_TV` (device `tv_1080p`).

- [ ] **Step 2: Gradle config.** Set up a version catalog with AGP, Kotlin, the Compose compiler plugin, kotlinx-serialization, the Compose BOM, `androidx.tv:tv-material`, `androidx.activity:activity-compose`, `androidx.lifecycle:lifecycle-viewmodel-compose`, `androidx.datastore:datastore-preferences`, `androidx.media3:media3-exoplayer`, `kotlinx-coroutines`, `junit:junit`, and `kotlinx-coroutines-test`. In `app/build.gradle.kts`:
  - A `Sync` task `syncGameAssets` copies `../sprites-final` → `sprites/`, `../scenes-final` → `scenes/` and `../audio-final` → `audio/` (excluding `CREDITS.md`) into `build/generated/gameAssets`. It is registered through `androidComponents.onVariants { it.sources.assets?.addGeneratedSourceDirectory(task, SyncTask::outputDir) }` (or an equivalent wiring that tracks inputs).
  - `androidResources.noCompress += listOf("ogg", "webp")`.
  - `versionName = providers.gradleProperty("versionName").orElse("0.0.0-dev")`. `versionCode` = major·10000 + minor·100 + patch, parsed from versionName, with 1 for dev.
  - The release `signingConfig` reads the env vars `HP_KEYSTORE_PATH`, `HP_KEYSTORE_PASSWORD`, `HP_KEY_ALIAS` and `HP_KEY_PASSWORD`. If the path is unset, release builds are unsigned (CI on PRs never needs secrets).
  - `buildConfig = true`.

- [ ] **Step 3: Manifest and Activity.** Follow the Global Constraints. `MainActivity` is a `ComponentActivity` with `screenOrientation="landscape"` and a fullscreen immersive theme with a black window background. For now `setContent { HorrorPetsTheme { Text("Horror Pets") } }`.

- [ ] **Step 4: Fonts.** Download the OFL TTFs into `res/font` from the google/fonts GitHub repo: Barlow Condensed Bold and ExtraBold, and Inter Regular and SemiBold (the static instances). Add `app/src/main/assets/licenses/OFL.txt`.

- [ ] **Step 5: Smoke test.** `SmokeTest.versionCodeFromName()`: `versionCodeOf("1.2.3") == 10203` and `versionCodeOf("0.0.0-dev") == 1`. `versionCodeOf` is a pure helper in `buildSrc` or duplicated in `app/src/main/.../BuildInfo.kt`; if duplicated, the test covers the app copy.

- [ ] **Step 6: Verify.** Run `./gradlew :app:assembleDebug :app:testDebugUnitTest`. Expected: BUILD SUCCESSFUL. `unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -c "assets/sprites/jason/idle.png"` → `1`.

- [ ] **Step 7: CI.** In `.github/workflows/ci.yml`, run on `pull_request` and on `push` to `main`: checkout, setup-java 17 (temurin), setup-gradle, then `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`. Use the latest major versions of the official actions.

- [ ] **Step 8: Commit** with the message `feat(app): Android TV scaffold, asset sync, theme, CI`.

### Task 5: Game model, decay, mood, status, leveling

**Files:**
- Create: `model/Model.kt`, `game/Tuning.kt`, `game/Decay.kt`, `game/Mood.kt`, `game/Status.kt`, `game/Leveling.kt`
- Test: `app/src/test/java/team/superfun/horrorpets/game/{DecayTest,MoodStatusTest,LevelingTest}.kt`

**Interfaces:**
- Produces:
  ```kotlin
  enum class PetId(val displayName: String) { JASON("Jason"), FREDDY("Freddy"), PINHEAD("Pinhead"), GHOSTFACE("Ghostface") }
  @Serializable data class PetState(
      val id: PetId, val hunger: Double = 20.0, val funLevel: Double = 80.0,   // `fun` is a Kotlin keyword; UI label stays "Fun"
      val strength: Double = 20.0,
      val level: Int = 1, val xp: Int = 0, val moodBonus: Double = 0.0,
      val wins: Int = 0, val losses: Int = 0, val sceneIndex: Int = 0, val fakeDeathPending: Boolean = false)
  @Serializable data class GameState(
      val version: Int = 1, val pets: Map<PetId, PetState> = PetId.entries.associateWith { PetState(it) },
      val selected: PetId? = null, val lastSeenMs: Long? = null)
  data class AwaySummary(val elapsedMs: Long, val biggestStat: String?, val biggestDelta: Int, val fakeDeath: Set<PetId>)
  fun applyElapsed(state: GameState, nowMs: Long): Pair<GameState, AwaySummary>   // game/Decay.kt
  fun mood(p: PetState): Int                                                     // game/Mood.kt, 0..100
  enum class StatusTag { CONTENT, BORED, HUNGRY, STARVING, GRUMPY }
  fun statusOf(p: PetState): StatusTag                                           // game/Status.kt
  data class XpResult(val pet: PetState, val levelsGained: Int)
  fun addXp(p: PetState, amount: Int): XpResult                                  // game/Leveling.kt
  ```
- `Tuning` object constants:
  - Decay: `HUNGER_PER_HOUR=4.0`, `FUN_PER_HOUR=3.0`, `STRENGTH_PER_HOUR=1.0/6`, `MOOD_BONUS_DECAY_PER_HOUR=5.0`
  - Fake death: `FAKE_DEATH_AFTER_MS=7 days`, `MAX_ELAPSED_MS=30 days`, `FAKE_DEATH_HUNGER=90.0`, `FAKE_DEATH_FUN=10.0`, `FAKE_DEATH_STRENGTH_FACTOR=0.8`
  - Status thresholds: `STARVING_AT=85`, `HUNGRY_AT=60`, `BORED_AT=30`, `GRUMPY_BELOW=30`
  - Toast: `TOAST_MIN_AWAY_MS=10 min`
  - Clock sanity: `MIN_VALID_EPOCH_MS=1_704_067_200_000` (2024-01-01). A `now` below this is a not-yet-synced boot clock: no decay, and `lastSeenMs` is not written.
  - Every stat is clamped to 0..100 and moodBonus to −30..30.

- [ ] **Step 1: Write the failing tests.**
  - `DecayTest.firstRunSetsLastSeenWithoutDecay`: `lastSeenMs=null`, now=1000 → pets unchanged, `lastSeenMs==1000`, `elapsedMs==0`.
  - `DecayTest.tenHoursDecays`: 10h elapsed → hunger 20→60, fun 80→50, strength 20→(20−10/6) ±0.001.
  - `DecayTest.splitIntervalsEqualSingle`: applying 5h twice equals 10h once, for every field ±1e-9.
  - `DecayTest.clampsAtBounds`: 48h → hunger 100.0, fun 0.0.
  - `DecayTest.backwardsClockIsZeroAndKeepsLastSeen`: lastSeen=T0+10h, now=T0 → state unchanged, `lastSeenMs==T0+10h`. (In these tests, T0 = 1_760_000_000_000, a valid 2025 timestamp.)
  - `DecayTest.clockBeforeFloorIsIgnored`: lastSeen=null, now=5_000 (a 1970 boot clock) → pets unchanged and `lastSeenMs` stays null. With lastSeen=T0 and now=5_000 → unchanged, `lastSeenMs==T0`.
  - `DecayTest.hugeJumpIsCapped`: lastSeen=T0, now=T0+400 days → elapsed treated as 30 days, and the fake death triggers for all pets.
  - `DecayTest.sevenDaysTriggersFakeDeath`: 7d → every pet has `fakeDeathPending=true`, hunger 90, fun 10, strength = old×0.8, level unchanged; `summary.fakeDeath == all`.
  - `DecayTest.sixDaysNoFakeDeath`.
  - `DecayTest.summaryBiggestChange`: 2h → `biggestStat=="Hunger"`, `biggestDelta==8` (hunger +8 beats fun −6). The summary is computed for the `selected` pet (or JASON if none is selected).
  - `MoodStatusTest`:
    - `mood = round(clamp(0.4*(100−hunger) + 0.6*fun + moodBonus))`. hunger 20, fun 80, bonus 0 → 80.
    - Status priority: STARVING (hunger≥85) > GRUMPY (mood<30) > HUNGRY (hunger≥60) > BORED (fun≤30) > CONTENT. Write one test per tag plus one priority test (hunger 90 and fun 0 → STARVING).
  - `LevelingTest`: level 1 xp 90 + 20 → level 2 xp 10. Level 1 xp 0 + 350 → level 3 xp 50, since it costs 100 for 1→2 and 200 for 2→3 (`levelsGained==2`).

- [ ] **Step 2: Run the tests to make sure they fail.** Run `./gradlew :app:testDebugUnitTest --tests "*game*"`. Expected: compile FAIL.

- [ ] **Step 3: Implement** `Model.kt`, `Tuning.kt`, `Decay.kt`, `Mood.kt`, `Status.kt` and `Leveling.kt` to the signatures above. In `applyElapsed`:
  - elapsed = clamp(now − lastSeen, 0, MAX).
  - `lastSeenMs = max(lastSeen ?: now, now)`.
  - moodBonus decays toward 0.
  - The fake death is applied after the decay.

- [ ] **Step 4: Run the tests to make sure they pass.** Run the same command. Expected: PASS.

- [ ] **Step 5: Commit** with the message `feat(game): pet model, real-time decay, mood, status, leveling`.

### Task 6: Care actions

**Files:**
- Create: `game/Care.kt`
- Test: `app/src/test/.../game/CareTest.kt`

**Interfaces:**
- Consumes: `PetState`, `addXp`, `Tuning`.
- Produces:
  ```kotlin
  enum class CareAction { FEED, PLAY, TRAIN }
  enum class RefuseReason { TOO_FULL, TOO_HUNGRY }
  sealed interface CareResult {
      data class Done(val pet: PetState, val levelsGained: Int) : CareResult
      data class Refused(val reason: RefuseReason) : CareResult }
  fun perform(action: CareAction, p: PetState): CareResult
  fun canFight(p: PetState): Boolean   // hunger < 85
  ```
- New `Tuning` constants:
  - Feed: `FEED_HUNGER=-35`, `FEED_REFUSE_BELOW=10`
  - Play: `PLAY_FUN=25`, `PLAY_HUNGER=8`, `PLAY_MOOD_BONUS=5`
  - Train: `TRAIN_STRENGTH=6`, `TRAIN_HUNGER=12`, `TRAIN_FUN=-5`, `TRAIN_REFUSE_AT=85`
  - XP: `XP_TRAIN=10`, `XP_PLAY=5`, `XP_FEED=0`

- [ ] **Step 1: Write the failing tests** in `CareTest`:
  - `feedLowersHunger`: 60 → 25.
  - `feedRefusesWhenFull`: hunger 9 → `Refused(TOO_FULL)`.
  - `playRaisesFunAndHunger`: fun 50→75, hunger 20→28, moodBonus +5, xp +5.
  - `trainRaisesStrengthAndHunger`: strength 20→26, hunger 20→32, fun 80→75, xp +10.
  - `trainRefusesWhenStarving`: hunger 85 → `Refused(TOO_HUNGRY)`.
  - `statsClamp`: feed at hunger 20 → 0.0, not negative.
  - `canFightFalseAt85`.

- [ ] **Step 2: Run the tests to make sure they fail.** Run `./gradlew :app:testDebugUnitTest --tests "*CareTest*"`. Expected: FAIL.

- [ ] **Step 3: Implement `perform` and `canFight`** to the signatures above.

- [ ] **Step 4: Run the tests to make sure they pass.** Expected: PASS.

- [ ] **Step 5: Commit** with the message `feat(game): feed, play, train actions`.

### Task 7: Persistence + clock

**Files:**
- Create: `data/Clock.kt`, `data/SaveCodec.kt`, `data/SaveRepository.kt`
- Test: `app/src/test/.../data/SaveCodecTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  fun interface Clock { fun nowMs(): Long }
  class OffsetClock(private val base: Clock, var offsetMs: Long = 0) : Clock      // debug time travel
  object SaveCodec { fun encode(s: GameState): String; fun decode(json: String?): GameState }
  interface SaveStore { suspend fun load(): GameState; suspend fun save(s: GameState) }
  class SaveRepository(context: Context) : SaveStore
  ```
  `SaveCodec` uses `Json { ignoreUnknownKeys = true; encodeDefaults = true }`. `decode` returns `GameState()` for null, blank, malformed JSON or `version > 1`, and fills in any missing pets with defaults. `SaveRepository` stores the JSON string under one Preferences DataStore key, `save_json`.

- [ ] **Step 1: Write the failing tests:** `roundTrip`, `nullGivesDefaults`, `garbageGivesDefaults` (`"{not json"`), `unknownFieldsIgnored`, `missingPetFilledWithDefault` (JSON with only JASON → FREDDY and PINHEAD present at defaults), and `futureVersionGivesDefaults`.

- [ ] **Step 2: Run the tests to make sure they fail.** Expected: FAIL.

- [ ] **Step 3: Implement.**

- [ ] **Step 4: Run the tests to make sure they pass.** Expected: PASS.

- [ ] **Step 5: Commit** with the message `feat(data): save codec, DataStore repository, clock`.

### Task 8: Fight engine + CPU

**Files:**
- Create: `fight/FightModel.kt`, `fight/FightEngine.kt`, `fight/CpuBrain.kt`, `fight/Rewards.kt`
- Test: `app/src/test/.../fight/{FightEngineTest,CpuBrainTest,RewardsTest}.kt`

**Interfaces:**
- Consumes: `PetState`, `PetId`, `mood()`, `addXp`, `Tuning`.
- Produces:
  ```kotlin
  enum class Side { PLAYER, CPU }
  enum class Move { STRIKE, HEAVY, BLOCK, SPECIAL, RUN }
  data class Fighter(val id: PetId, val level: Int, val maxHp: Int, val hp: Int, val strength: Double, val mood: Int, val meter: Int)
  data class FightState(val player: Fighter, val cpu: Fighter, val turn: Int = 1, val playerHistory: List<Move> = emptyList(), val winner: Side? = null, val playerRan: Boolean = false)
  sealed interface FightEvent {
      data class Hit(val attacker: Side, val move: Move, val damage: Int, val crit: Boolean, val counter: Boolean = false) : FightEvent
      data class Miss(val attacker: Side, val move: Move) : FightEvent
      data class Blocked(val blocker: Side) : FightEvent
      data class Interrupted(val side: Side) : FightEvent
      data class SpecialReady(val side: Side) : FightEvent
      data class Ko(val loser: Side) : FightEvent }
  data class TurnResult(val state: FightState, val events: List<FightEvent>)
  fun buildFighter(p: PetState): Fighter
  fun buildOpponent(id: PetId, player: PetState, rng: Random): Fighter
  fun startFight(player: PetState, opponent: PetId, rng: Random): FightState
  fun resolveTurn(s: FightState, playerMove: Move, cpuMove: Move, rng: Random): TurnResult
  fun cpuPick(s: FightState, rng: Random): Move                                    // CpuBrain.kt
  data class FightReward(val pet: PetState, val xpGained: Int, val levelsGained: Int)
  fun applyFightResult(p: PetState, s: FightState): FightReward                    // Rewards.kt
  ```
- Rules (constants go in `Tuning`):
  - **HP** = `(60 + 8·level) × lerp(1.0, 0.7, hunger/100)`, then ×1.2 for JASON, rounded.
  - **Base damage** = `8 + strength/8`, × uniform(0.85, 1.15).
  - **Multipliers:** STRIKE ×1.0, HEAVY ×1.8, counter ×0.5 (PINHEAD ×1.0), SPECIAL ×2.5.
  - **Crit chance** = 0.05 + mood/100·0.15, plus 0.08 for FREDDY. Crit damage ×1.75.
  - **Miss chance:** STRIKE 0.08, HEAVY 0.15, plus 0.07 when the defender is GHOSTFACE. SPECIAL and counters never miss.
  - **Meter:** +25 per landed hit by that side, capped at 100. SPECIAL requires 100 and resets the meter to 0. `SpecialReady` is emitted when the meter reaches 100.
  - **Matrix**, resolved simultaneously, PLAYER's events first:
    - S/S: both hit.
    - H/H: both hit.
    - B/B: nothing.
    - S vs H: the striker hits, and the heavy side gets `Interrupted`.
    - H vs B: the heavy lands in full (block broken).
    - B vs S: `Blocked` plus a counter hit from the blocker.
    - SPECIAL always lands, and the other side's STRIKE or HEAVY also land (no interrupt). BLOCK vs SPECIAL does nothing.
    - RUN (player only): `winner=CPU`, `playerRan=true`, no other events.
  - **KO:** when hp ≤ 0. If both reach 0, the side that dealt more damage this turn wins; on a tie, PLAYER wins.
  - **Opponent:** level = clamp(player.level + rng(−1..1), min 1). Strength = clamp(player.strength + rng(−10..10)). Hunger 30, mood 50, meter 0.
  - **CPU:** if its meter is 100 → SPECIAL with probability 0.7. Otherwise, with probability 0.4 and a non-empty history, it counters the player's most-used move (STRIKE→BLOCK, HEAVY→STRIKE, BLOCK→HEAVY). Otherwise it picks by weight: STRIKE 40, HEAVY 30, BLOCK 30. The CPU never picks RUN.
  - **Rewards:** always hunger +15, except on RUN. Win: xp +40 and moodBonus +15, wins+1. Loss: xp +15, fun −5, losses+1. Run: moodBonus −10, losses+1, no xp.

- [ ] **Step 1: Write the failing tests** with a fixed `Random(seed)`, or with a stub `Random` subclass returning scripted values for the crit, miss and variance rolls:
  - `strikeInterruptsHeavy`: events contain the PLAYER Hit and `Interrupted(CPU)`, and the player's hp is unchanged.
  - `heavyBreaksBlock`: the CPU takes ≈1.8× base damage.
  - `blockCountersStrike`: `Blocked(PLAYER)` plus a counter Hit at ×0.5. With PINHEAD as the player, ×1.0.
  - `bothBlockNothingHappens`.
  - `specialUnblockableAndResetsMeter`.
  - `specialRejectedWithoutFullMeter`: an `IllegalArgumentException` is thrown.
  - `critMultiplies`: the scripted crit roll gives damage ≈ 1.75× (±1).
  - `missEmitsMiss`.
  - `meterFillsAndSignalsReady`: after 4 landed strikes, `SpecialReady(PLAYER)` has been emitted.
  - `doubleKoGoesToBiggerHitter` and `doubleKoTieGoesToPlayer`.
  - `runForfeits`.
  - `ghostfaceIsHarderToHit`: a scripted miss roll of 0.10 on a STRIKE → Hit vs JASON, Miss vs GHOSTFACE.
  - `jasonHasMoreHp`: at level 1 and hunger 0 → 82 vs 68.
  - `hungerReducesHp`: level 1, hunger 100 → round(68×0.7) = 48.
  - `CpuBrainTest.neverRuns` (1000 picks), `CpuBrainTest.prefersSpecialWhenFull` (≥60% of 1000), and `CpuBrainTest.countersMostUsed` (history of all STRIKE → BLOCK ≥ 40% of picks).
  - `RewardsTest`: win, loss and run deltas as specified.

- [ ] **Step 2: Run the tests to make sure they fail.** Run `./gradlew :app:testDebugUnitTest --tests "*fight*"`. Expected: FAIL.

- [ ] **Step 3: Implement** to the signatures and rules above.

- [ ] **Step 4: Run the tests to make sure they pass.** Expected: PASS.

- [ ] **Step 5: Commit** with the message `feat(fight): turn engine, CPU brain, rewards`.

### Task 9: Asset loading, sprite timing, cover-crop math

**Files:**
- Create: `assets/SpriteManifest.kt`, `assets/SpriteAnimator.kt`, `assets/SceneCatalog.kt`, `assets/CoverCrop.kt`, `assets/AssetLoader.kt`
- Test: `app/src/test/.../assets/{SpriteManifestTest,SpriteAnimatorTest,CoverCropTest,SceneCatalogTest}.kt`

**Interfaces:**
- Consumes: the `sprites-final` manifest schema (Task 1) and the `scenes.json` schema (Task 2).
- Produces:
  ```kotlin
  enum class Anim(val key: String) { IDLE("idle"), RUN_RIGHT("run-right"), RUN_LEFT("run-left"), WAVE("wave"), JUMP("jump"),
      FAIL("fail"), WAIT("wait"), WORK("work"), REVIEW("review"), LOOK_A("look-a"), LOOK_B("look-b"), ATTACK("attack") }
  @Serializable data class AnimSpec(val file: String, val frames: Int, val durationsMs: List<Int>, val loop: Boolean)
  @Serializable data class SpriteManifest(val pet: String, val cell: List<Int>, val baselineY: Int, val anchorX: Int, val feed: String, val animations: Map<String, AnimSpec>)
  fun parseManifest(json: String): SpriteManifest
  /** frame index at elapsed time; null when a one-shot has finished */
  fun frameAt(spec: AnimSpec, elapsedMs: Long): Int?
  @Serializable data class SceneSpec(val id: String, val title: String, val file: String, val floorY: Float, val tint: String, val tintStrength: Float, val shadowOpacity: Float, val accent: String)
  fun parseScenes(json: String): Map<PetId, List<SceneSpec>>
  data class Crop(val scale: Float, val offsetX: Float, val offsetY: Float)
  fun coverCrop(imgW: Int, imgH: Int, viewW: Int, viewH: Int): Crop
  fun floorYOnScreen(c: Crop, imgH: Int, floorY: Float): Float
  class AssetLoader(context: Context) {
      fun manifest(pet: PetId): SpriteManifest
      fun strip(pet: PetId, anim: Anim): ImageBitmap        // cached, decoded ARGB_8888
      fun scenes(): Map<PetId, List<SceneSpec>>
      fun sceneBitmap(spec: SceneSpec): ImageBitmap }       // cached, at most 2 scenes in memory
  ```
  The `coverCrop` scale is max(viewW/imgW, viewH/imgH), centered on both axes. The floor line is pushed up if needed so that `floorYOnScreen ≤ 0.90·viewH`, which keeps the pet out of the bottom safe inset.

- [ ] **Step 1: Write the failing tests:**
  - `SpriteAnimatorTest.loopWraps`: durations [100,100,100], 350ms → frame 0 (wraps).
  - `SpriteAnimatorTest.oneShotEndsNull`: 300ms on 3×100 one-shot → `null`. 299ms → 2.
  - `CoverCropTest.wide16x9From3x2`: img 1536×1024, view 1920×1080 → scale 1.25, offsetY −100, offsetX 0.
  - `CoverCropTest.fourThreeCropsSides`: img 1536×1024, view 1440×1080 → scale 1.0547 (±0.001), offsetX < 0, offsetY 0.
  - `CoverCropTest.crt640x480FloorStaysAboveInset`: img 1536×1024, floorY 0.97, view 640×480 → `floorYOnScreen ≤ 432`.
  - `SpriteManifestTest.parsesAllTwelveAnims`: a fixture JSON copied from `sprites-final/jason/manifest.json` → 12 animations and `feed=="review"`.
  - `SceneCatalogTest.threeScenesPerPet`: a fixture from `scenes-final/scenes.json` → each of the 4 PetIds has 3 entries.

- [ ] **Step 2: Run the tests to make sure they fail.** Expected: FAIL.

- [ ] **Step 3: Implement.** `AssetLoader` decodes with `BitmapFactory` from `assets.open`. It keeps an LRU of strips (at most 24) and drops scene bitmaps that aren't in use.

- [ ] **Step 4: Run the tests to make sure they pass.** Expected: PASS.

- [ ] **Step 5: Commit** with the message `feat(assets): manifests, sprite timing, scene cover-crop`.

### Task 10: GameViewModel state machine

**Files:**
- Create: `ui/Screen.kt`, `ui/GameViewModel.kt`
- Test: `app/src/test/.../ui/GameViewModelTest.kt`

**Interfaces:**
- Consumes: Tasks 5–8 (game functions, `SaveStore`, `Clock`).
- Produces:
  ```kotlin
  sealed interface Screen {
      data object Loading : Screen
      data class Switcher(val mode: SwitcherMode) : Screen      // enum SwitcherMode { SELECT, ONBOARD, OPPONENT }
      data class FakeDeath(val pet: PetId) : Screen
      data object Home : Screen
      data class Versus(val opponent: PetId) : Screen
      data class Fight(val fight: FightState, val lastEvents: List<FightEvent>) : Screen
      data class Results(val reward: FightReward, val won: Boolean, val ran: Boolean) : Screen }
  data class PetAnimRequest(val anim: Anim, val token: Long)    // UI plays this and then calls onAnimFinished(token)
  data class UiState(val screen: Screen, val game: GameState, val busy: Boolean, val petAnim: PetAnimRequest?,
                     val toast: String?, val refuse: RefuseReason?, val sfx: List<String>, val voice: List<String>)
  class GameViewModel(store: SaveStore, clock: Clock, rng: Random, scope: CoroutineScope?) {
      val state: StateFlow<UiState>
      fun start(); fun onResume(); fun tick()                   // tick() every 60s while foregrounded
      fun onCare(a: CareAction); fun onWalk(dir: Int)           // dir = -1 or +1
      fun openSwitcher(); fun onSwitcherPick(p: PetId); fun onSwitcherBack()
      fun onFightPressed(); fun onVersusDone(); fun onMove(m: Move); fun onResultsDone()
      fun onFakeDeathDone(); fun onAnimFinished(token: Long); fun consumeToast(); fun consumeSounds() }
  ```
  `sfx` and `voice` are queues of file base names from Task 3 (for example `"chomp"`, `"versus"`).
- Behavior:
  - **`start()`:** loads the save and runs `applyElapsed`.
    - If no pet is selected → `Switcher(ONBOARD)` + voice `choose_your_killer`.
    - Else if the selected pet has `fakeDeathPending` → `FakeDeath`.
    - Else → `Home`, with a toast `"Away {d}d {h}h · {Stat} {±n}"` when elapsed ≥ 10 min (omit `0d`).
  - **Saving:** the game saves after every state change.
  - **`busy`:** `true` from any action start until its `onAnimFinished(token)`. While busy, `onCare`, `onWalk`, `onFightPressed` and `onMove` are ignored. Stats change **once**, at the start of the action.
  - **`onCare`:** a refusal plays `FAIL` + sfx `refuse` and sets `refuse`. Success plays FEED → the manifest's `feed` anim, PLAY → `JUMP` then `WAVE`, TRAIN → `ATTACK` ×2, with sfx `chomp`/`play`/`train`, plus `levelup` if leveled.
  - **`onWalk`:** plays RUN_RIGHT/RUN_LEFT, then `sceneIndex = (i + dir) mod 3`, saved per pet.
  - **`onFightPressed`:** if `!canFight` → a refusal. Otherwise → `Switcher(OPPONENT)`, excluding the selected pet.
  - **`onSwitcherPick`:**
    - In SELECT or ONBOARD: sets `selected`, then goes to FakeDeath if that pet is pending, else Home.
    - In OPPONENT: → `Versus` + voice `[player, versus, opponent]`.
  - **`onSwitcherBack`:** Home (ignored in ONBOARD).
  - **`onFakeDeathDone`:** clears `fakeDeathPending` for that pet → Home.
  - **`onMove`:** `cpuPick` → `resolveTurn` → the `Fight` screen with events. It adds sfx per event (`hit`/`heavy_hit`/`crit`/`block`/`miss`/`special_<pet>`) and voice `critical` on crits and `ko` on KO. When the fight has a winner, after its animation → `Results` with `applyFightResult` applied and saved.
  - **`tick()` and `onResume()`:** run `applyElapsed` with no toast. If a fake death triggers (resume after 7 days), route to FakeDeath when on Home.
  - **Debug:** `MainActivity` reads the debug-only intent extra `advance_hours` (Float) and sets `OffsetClock.offsetMs`.

- [ ] **Step 1: Write the failing tests** (`FakeStore` in memory, mutable `FakeClock`, `StandardTestDispatcher`):
  - `firstLaunchOnboards`.
  - `returningGoesHomeWithToast`: lastSeen = now − (2d4h) → the toast starts with `"Away 2d 4h"`.
  - `shortAbsenceNoToast`: 5 min.
  - `sevenDaysShowsFakeDeathThenHome`.
  - `feedTwiceWhileBusyAppliesOnce`: onCare(FEED) ×2 before onAnimFinished → hunger down 35 exactly once.
  - `walkWrapsScenes`: index 2, +1 → 0, persisted.
  - `fightRefusedWhenStarving`.
  - `opponentSwitcherExcludesSelf` (the screen mode is OPPONENT, and the UI gets its exclusion from `game.selected`).
  - `fullFightReachesResultsAndSaves`: a scripted rng, repeated `onMove(STRIKE)` + `onAnimFinished` until Results; the saved state has wins or losses incremented.
  - `tickDecaysWhileOpen`: advance the clock 3h, `tick()` → hunger +12.
  - `resumeAfterWeekTriggersFakeDeath`.

- [ ] **Step 2: Run the tests to make sure they fail.** Expected: FAIL.

- [ ] **Step 3: Implement.** The VM is plain Kotlin, Android-free except for the `ViewModel` base. The factory is created in `MainActivity`.

- [ ] **Step 4: Run the tests to make sure they pass.** Expected: PASS.

- [ ] **Step 5: Commit** with the message `feat(ui): game view model state machine`.

### Task 11: Shared visuals + Home screen

**Files:**
- Create: `ui/HorrorPetsApp.kt`, `ui/common/{SpriteView,SceneBackdrop,GlassPanel,FocusPill}.kt`, `ui/home/{HomeScreen,StatCard,ActionBar,WelcomeToast}.kt`
- Modify: `MainActivity.kt` (VM wiring, a 60s ticker via `repeatOnLifecycle(RESUMED)`, `onResume`, key handling for MENU / MEDIA_FAST_FORWARD / MEDIA_REWIND)

**Interfaces:**
- Consumes: `UiState`, `GameViewModel`, `AssetLoader`, `coverCrop`, `floorYOnScreen`, `frameAt`, theme tokens.
- Produces:
  ```kotlin
  @Composable fun SpriteView(loader: AssetLoader, pet: PetId, anim: Anim, heightPx: Float, tint: Color?, tintStrength: Float,
                             dim: Float = 0f, flashWhite: Float = 0f, onFinished: (() -> Unit)? = null, modifier: Modifier)
  @Composable fun SceneBackdrop(loader: AssetLoader, scene: SceneSpec, content: @Composable BoxScope.(floorY: Float) -> Unit)
  @Composable fun GlassPanel(modifier: Modifier, content: @Composable () -> Unit)
  @Composable fun FocusPill(label: String, onClick: () -> Unit, modifier: Modifier)   // scale 1.1 + BloodGlow on focus
  ```
  `SpriteView` draws one cell with `drawImage(srcOffset, srcSize, dstSize, filterQuality = High)`, bottom-anchored at `baselineY`. It applies the tint as a `ColorFilter.lighting`/blend lerp of `tintStrength`, and draws a contact shadow ellipse (width 0.45 × cell width, `shadowOpacity`) under the feet. Frame timing comes from `frameAt` driven by `withFrameMillis`.
- Layout, which follows the spec's layout A:
  - The pet cell height is 46% of the screen height, centered, with the feet on the floor line.
  - StatCard sits top-left inside the safe inset: name (Display 40sp), `LV n`, and four bars at least 10dp tall. The Hunger bar fills as hunger rises (Amber ≥60, Blood ≥85). Fun, Strength and Mood fill normally and turn Amber when ≤30.
  - Scene dots are at top center. WelcomeToast is top-right and auto-dismisses after 6s.
  - ActionBar is bottom center: `◀ | PLAY FEED TRAIN FIGHT | ▶`. The arrows show the neighbor scene's title on focus. PLAY gets the initial focus.
  - Up from the bar focuses the StatCard, and OK on the StatCard opens the switcher.
  - A refusal shows a speech-bubble chip above the pet ("Too full!", "Too hungry to train", "Too hungry to fight") for 2s.
  - When idle, the pet plays a random LOOK_A/LOOK_B every 12–20s, or WAIT if hunger ≥ 60.
  - Back on Home → `finish()`.

- [ ] **Step 1: Implement** the composables and wiring above.

- [ ] **Step 2: Verify on the emulator.** Run `./gradlew :app:installDebug`, then `adb shell am start -n team.superfun.horrorpets/.MainActivity`. Pick Jason in onboarding (the switcher may be a placeholder until Task 12; a temporary list is fine). Screenshot with `adb exec-out screencap -p > art-work/shots/home-16x9.png` and view it. Then `adb shell wm size 1440x1080`, restart, screenshot `home-4x3.png`, and run `adb shell wm size reset`. Check that the feet sit on the floor, nothing is clipped, the focus is obvious, and FEED/PLAY/TRAIN animate and change the bars. Use `adb shell input keyevent DPAD_RIGHT/DPAD_CENTER` to drive it.

- [ ] **Step 3: Commit** with the message `feat(ui): home screen, sprite renderer, scene backdrop`.

### Task 12: Spotlight switcher

**Files:**
- Create: `ui/switcher/SwitcherScreen.kt`

**Interfaces:**
- Consumes: `Screen.Switcher(mode)`, `statusOf`, `SpriteView`, the VM callbacks.
- Spec (from the approved mockup `pet-switcher-v3`):
  - A dark stage on `Ink`. The title is `CHOOSE YOUR KILLER` in SELECT/ONBOARD mode and `CHOOSE YOUR OPPONENT` in OPPONENT mode (Display 56sp, letter-spaced).
  - Four slots sit at x = 14/38/62/86% (three slots at 20/50/80% in OPPONENT mode). Each slot has a vertical light cone, a floor pool, a contact shadow, and the **uncropped** idle sprite with its cell height at 36% of the screen height.
  - The focused slot gets a red-tinted cone and pool and full color. The others are drawn at `dim=0.62` and desaturated.
  - Under each pet: name, `LV n`, and a status chip (CONTENT grey, BORED/HUNGRY Amber, STARVING/GRUMPY Blood).
  - Left/right moves the spotlight, which animates its position over 250ms. OK picks, and Back calls `onSwitcherBack`.
  - Music is `switcher`.

- [ ] **Step 1: Implement.**

- [ ] **Step 2: Verify.** Clear the app data (`adb shell pm clear team.superfun.horrorpets`), launch, and screenshot `switcher-16x9.png` and `switcher-4x3.png`. Check that no figure is cropped at 4:3 and the spotlight moves.

- [ ] **Step 3: Commit** with the message `feat(ui): spotlight pet switcher`.

### Task 13: Fake death screen

**Files:**
- Create: `ui/fakedeath/FakeDeathScreen.kt`

- [ ] **Step 1: Implement the timeline**, about 6.5s, OK-skippable after 2s, then `onFakeDeathDone`:
  - 0–3.0s: the pet's current scene darkened to 35% with a tombstone drawn in Compose (a rounded slab, Display `R.I.P.` / `{NAME}`) and sfx `tombstone`. Music fades out.
  - 3.0–3.6s: a glitch (horizontal slice offsets, RGB split and flicker) with sfx `gotcha`.
  - 3.6–6.5s: `GOTCHA.` slams in (scale 1.6→1.0 with a shake) and voice `gotcha`. Then `{NAME} NEVER DIES.` with voice `<pet>_never_dies`, while the pet plays JUMP then WAIT in front of the tombstone.

- [ ] **Step 2: Verify.** Run `adb shell am start -n team.superfun.horrorpets/.MainActivity --ef advance_hours 200` and screenshot each phase (use `screencap` at ~1s, 3.3s and 5s). Expected: the tombstone, the glitch, then GOTCHA with the pet visible. The home screen afterwards shows hunger near 90.

- [ ] **Step 3: Commit** with the message `feat(ui): fake death gotcha`.

### Task 14: VS, Fight, Results

**Files:**
- Create: `ui/fight/{VersusScreen,FightScreen,FloatingNumbers,FightVfx,ResultsCard}.kt`

**Interfaces:**
- Consumes: `Screen.Versus/Fight/Results`, `FightEvent`, `SpriteView`, `SceneBackdrop`, the VM callbacks.
- VS (about 3s, OK skips):
  - A diagonal split where each half is tinted with the fighter's scene accent.
  - Each fighter's idle sprite slides in from its side, with its cell height at 70% of the screen height.
  - The names slam in, then `VS` slams in at center with a 300ms screen shake. Then `FIGHT!` and voice `fight` → `onVersusDone`.
- Fight:
  - The arena is a random scene of the opponent (chosen once per fight).
  - The player stands at x=30% and the CPU at x=70%. Their cell height is 38% of the screen, and **neither is mirrored**.
  - The top bar has SF-style HP bars (player fills from the left, CPU from the right), names, and a special meter under each bar that pulses BloodGlow when full.
  - The bottom has FocusPills `STRIKE HEAVY BLOCK [SPECIAL] RUN`. SPECIAL appears only when the meter is 100. Back → a "Run away?" Yes/No dialog.
- Turn playback runs the `lastEvents` sequentially, then `onAnimFinished`:
  - Both chosen moves pop as callouts above the fighters for 600ms.
  - `Hit`: the attacker steps 12% toward the opponent with its RUN anim, plays ATTACK and steps back. The defender gets `flashWhite` 1→0 over 250ms plus a ±8px shake. A `FloatingNumbers` damage number (Display 64sp, white with a dark stroke) floats up 120px and fades over 900ms. A crit uses 96sp in Amber→Blood with `CRITICAL!` above it and a 250ms screen shake.
  - `Miss`: grey `MISS`.
  - `Blocked`: a shield ring (`FightVfx`) plus `BLOCK`.
  - `Interrupted`: `INTERRUPTED` on that side.
  - Special VFX overlays on the defender:
    - Jason: red diagonal slash streaks.
    - Freddy: a green dream-warp ripple and claw streaks.
    - Pinhead: chains drawn as lines from the screen edges, converging.
    - Ghostface: a rapid flurry of white knife streaks with a quick dark vignette pulse.
  - `Ko`: the loser plays FAIL and `K.O.` slams in; the winner plays JUMP then WAVE.
- ResultsCard: a GlassPanel at center with `YOU WIN` / `YOU LOSE` / `YOU RAN`, XP gained, `LEVEL UP!` if any, and the stat deltas. It has one focused `CONTINUE` pill → `onResultsDone`. Voice `you_win`/`you_lose`.

- [ ] **Step 1: Implement** VersusScreen and FightScreen, then FloatingNumbers, FightVfx and ResultsCard.

- [ ] **Step 2: Verify.** Play a full fight with key events from adb. Screenshot the VS, a crit (repeat until one appears), the special and Results at 16:9 and 4:3. Check that the HP numbers match the bars and nothing overlaps the safe inset.

- [ ] **Step 3: Commit** with the message `feat(ui): VS screen, turn-based fight, results`.

### Task 15: Audio integration

**Files:**
- Create: `audio/GameAudio.kt`
- Modify: `ui/HorrorPetsApp.kt` (drain `state.sfx`/`state.voice` via `consumeSounds()`, pick the music per screen)

**Interfaces:**
- Produces: `class GameAudio(context) { fun playSfx(name: String); fun playVoice(names: List<String>); fun music(track: String?); fun pause(); fun resume(); fun release() }`
  - SFX and voice are preloaded with `SoundPool` from `assets.openFd("audio/sfx/$name.ogg")`. Voice lines in a list play back-to-back with a 150ms gap, and voice ducks the music to 30% while it plays.
  - Music: one ExoPlayer with `REPEAT_MODE_ONE` and a 600ms crossfade when switching (via a second player).
  - Track by screen: Home → the selected pet's track; Switcher → `switcher`; Versus/Fight/Results → `fight`; FakeDeath → none.
  - It pauses in `onPause` and resumes in `onResume`.
  - Focus moves play `ui_move`, OK plays `ui_select`, and Back plays `ui_back`.

- [ ] **Step 1: Implement and wire.**

- [ ] **Step 2: Verify.** On the emulator, check with `adb logcat -s GameAudio` (log each play call) while walking through Home → Feed → Fight → Results. Expected: the correct names are logged, with no `SoundPool` load errors. Listen through the emulator speaker.

- [ ] **Step 3: Commit** with the message `feat(audio): music, sfx, announcer playback`.

### Task 16: Branding

**Files:**
- Create: `tools/make_branding.py`, `app/src/main/res/drawable-xhdpi/banner.png`, `app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png`
- Modify: `AndroidManifest.xml` (`android:banner`, `android:icon`)

- [ ] **Step 1: Implement `make_branding.py`.**
  - The banner is 320×180 and uses the Pinhead chamber scene, cropped and darkened, with all four idle frame-0 sprites in a row and `HORROR PETS` in Barlow Condensed ExtraBold from `res/font`, with a red glow.
  - The icon is Jason's idle frame 0 on a dark radial vignette at 48/72/96/144/192 px.

- [ ] **Step 2: Run it and view the outputs** with the Read tool. On the emulator, the TV home screen shows the banner in the apps row; take a screenshot.

- [ ] **Step 3: Commit** with the message `feat(app): TV banner and launcher icon`.

### Task 17: Signed release workflow + README

**Files:**
- Create: `.github/workflows/release.yml`, `README.md` (rewrite)

- [ ] **Step 1: Write `release.yml`.** It triggers on `push: tags: ['v*']` with `permissions: contents: write`.
  - Steps: checkout, setup-java 17, setup-gradle. Decode `secrets.HP_KEYSTORE_B64` to `$RUNNER_TEMP/release.jks`, and export `HP_KEYSTORE_PATH`, `HP_KEYSTORE_PASSWORD`, `HP_KEY_ALIAS` and `HP_KEY_PASSWORD` from secrets.
  - Run `./gradlew :app:testReleaseUnitTest :app:assembleRelease -PversionName=${GITHUB_REF_NAME#v}`.
  - Verify the signature with `$ANDROID_HOME/build-tools/<ver>/apksigner verify`, and copy the APK to `horror-pets-${GITHUB_REF_NAME#v}.apk`.
  - Run `gh release create "$GITHUB_REF_NAME" horror-pets-*.apk --generate-notes` with `GH_TOKEN: ${{ github.token }}`.

- [ ] **Step 2: Keystore.** Run `keytool -genkeypair -v -keystore ~/.horror-pets/release.jks -alias horrorpets -keyalg RSA -keysize 4096 -validity 36500` with generated passwords. Tell the user where it lives and that it must be backed up and never changed. **Ask the user before** running `gh secret set HP_KEYSTORE_B64 / HP_KEYSTORE_PASSWORD / HP_KEY_ALIAS / HP_KEY_PASSWORD -R superfunteam/horror-pets`. Pipe the values from stdin and never echo them.

- [ ] **Step 3: Local release check.** Run `HP_KEYSTORE_PATH=~/.horror-pets/release.jks ... ./gradlew :app:assembleRelease -PversionName=0.1.0`, then `apksigner verify --print-certs`. Expected: verified, and `aapt2 dump badging` shows `versionCode='100'` and `versionName='0.1.0'`.

- [ ] **Step 4: README.**
  - What the app is, with a screenshot (`docs/screenshot.png`, copied from a 16:9 home shot).
  - Install: download the APK from Releases, then either `adb install`, or on Google TV / Onn use the Downloader app pointed at the release URL.
  - Develop: the tools venv, regenerating assets (the OpenAI key comes from the env var only), and `./gradlew installDebug`. Include the debug time-travel extra.
  - Release: `git tag v0.1.0 && git push origin v0.1.0`.
  - Credits/licenses (fonts OFL, audio CREDITS). Also note that the characters belong to their respective rights holders, as a fan project.

- [ ] **Step 5: Commit** with the message `ci: signed APK release on tags; README`.

### Task 18: Full verification + first release

- [ ] **Step 1:** Run `./gradlew clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` and `tools/.venv/bin/python -I -m pytest tools/tests -q`. Expected: everything passes, with no lint errors.
- [ ] **Step 2: Emulator matrix.** Capture home, switcher, VS, fight with a crit, results and fake death at 1920×1080, 1440×1080 and 720×540 (`adb shell wm size`, `wm density 160` for 720×540). View each one. Check that text is readable at 720×540, nothing is clipped, the pets are on the floor and the focus is visible. Run `wm size reset` / `wm density reset` afterwards.
- [ ] **Step 3: Secret scan.** Run `git log -p | grep -E "sk-(proj-)?[A-Za-z0-9_-]{20,}"`. Expected: no output.
- [ ] **Step 4: Whole-branch review** by a fresh reviewer (superpowers:requesting-code-review). Fix what it finds.
- [ ] **Step 5: Push and release (ask the user first).** Push `main`. Once the user says go, run `git tag v0.1.0 && git push origin v0.1.0`. Confirm that the Actions release run succeeds and the Release has `horror-pets-0.1.0.apk`.
