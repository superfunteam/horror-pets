<p align="center">
  <img src="docs/banner.png" alt="Virtual Pet Slasher 2 Turbo: Jason, Freddy, Pinhead, Ghostface, Chucky, Michael and Leatherface brawling under the title logo" width="720">
</p>

# Virtual Pet Slasher 2 Turbo

Virtual pets from your favorite horror films, right on your TV. It is a Tamagotchi for the living room:
seven chibi slasher icons live on your Android TV, and you feed them, play with them, train them and make
them fight. Everything runs offline, with a D-pad remote and nothing else.

<p align="center">
  <img src="docs/screenshot.png" alt="Jason on the Camp Crystal Lake dock, with his stat card and the Play, Feed, Train and Fight buttons" width="49%">
  <img src="docs/fight.png" alt="A VS fight in the attic: Jason against Pinhead, with a critical hit" width="49%">
</p>

## Features

- **Seven killers:** Jason, Freddy, Pinhead, Ghostface, Chucky, Michael and Leatherface, each with its own stats, three scenes from its movie, music, a fight passive and a signature special. Pick yours from a spotlight carousel. The newest, Leatherface, revs his Chainsaw passive (his heavy hits land 20% harder) and, for his special, the Saw Dance: he does his chainsaw dance while blades and sparks whirl around his opponent. Hit Play on Home and he dances for you too.
- **Real-time care:** Hunger, Fun, Strength and Mood keep moving while you are away. Play, feed and train to keep them happy.
- **Fake death:** leave a pet alone for a week and you get a tombstone and "R.I.P.". Then: "GOTCHA. NEVER DIES."
- **VS fights:** turn-based, Street-Fighter-style bouts with crits, blocks, specials and an announcer. Win XP, level up.
- **Made for TVs:** 16:9 and 4:3, remote-only controls, Android 5.1 and up (Google TV, Onn, Fire TV, Android TV).

## Install

Download `horror-pets-<version>.apk` from [Releases](https://github.com/superfunteam/horror-pets/releases/latest), then sideload it:

- **adb:** turn on developer options and network debugging on the TV, then
  `adb connect <tv-ip>:5555 && adb install horror-pets-<version>.apk`.
- **Downloader app** (Google TV, Onn, Fire TV): install Downloader (by AFTVnews, free in the app stores), allow it to install unknown apps,
  and enter the release asset URL, for example
  `https://github.com/superfunteam/horror-pets/releases/download/v0.1.0/horror-pets-0.1.0.apk`.

Later versions install over the top and keep your pets, because every release is signed with the same key.

## Develop

You need **JDK 17** (Temurin is fine; the build does not download one for you) and the Android SDK
(`ANDROID_HOME`, or `sdk.dir` in `local.properties`).

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
adb -s <device> install -r app/build/outputs/apk/debug/app-debug.apk
```

**Time travel (debug builds only).** Shift the game clock forward to watch decay and the fake death without waiting:

```sh
adb shell am force-stop team.superfun.horrorpets && \
adb shell am start -n team.superfun.horrorpets/.MainActivity --ef advance_hours 200
```

When you are done time travelling, wipe the app's data. The offset clock saved a "last seen" time in the
future, and with the real clock behind it no time passes, so the pets stop decaying until the real clock
catches up:

```sh
adb shell pm clear team.superfun.horrorpets
```

### Regenerating assets

The finished art and audio are committed (`sprites-final/`, `scenes-final/`, `audio-final/`) and Gradle bundles them into the APK.
The generators live in `tools/` and need Python 3, `ffmpeg` (with libopus) and `cwebp`:

```sh
python3 -m venv tools/.venv && tools/.venv/bin/pip install -r tools/requirements.txt
tools/.venv/bin/python -I -m pytest tools/tests -q
tools/.venv/bin/python -I tools/synth_audio.py                          # original music and sfx, no key needed
OPENAI_API_KEY=... tools/.venv/bin/python -I tools/generate_announcer.py   # announcer lines
OPENAI_API_KEY=... tools/.venv/bin/python -I tools/generate_scenes.py --n 2
```

The OpenAI key is read from the `OPENAI_API_KEY` environment variable only. Never write it to a file or commit it.

## Releasing

Releases are built and signed on the maintainer's machine, and the signing key never leaves it.
CI (`.github/workflows/ci.yml`) builds, tests and lints every PR; pushing a `v*` tag runs `release.yml`,
an unsigned verification build with no secrets.

One-time key setup (the key lives in `~/.horror-pets/`; `tools/release.sh --help` has the exact commands):

1. Generate `release.jks` with `keytool` and put its passwords in `release.properties` next to it (`storePassword`, `keyAlias`, `keyPassword`). Both files `chmod 600`, the folder `700`.
2. **Back up `~/.horror-pets/`.** Android only accepts updates signed with the same key, so if you lose it, installed copies can never be updated.

Then, from a clean `main` that equals `origin/main` (push it first), with the [GitHub CLI](https://cli.github.com/) installed and logged in (`gh auth login`) and push access to the repo:

```sh
tools/release.sh 0.2.0 --dry-run   # build, sign, verify, write dist/horror-pets-0.2.0.apk; publishes nothing
tools/release.sh 0.2.0             # the same, then tag v0.2.0, push it, and create the GitHub Release
```

Before building, the script checks the version, a clean tree, `main` equal to `origin/main`, a free tag, and that `gh` and push access work,
so a missing login stops the release before anything is tagged. It then runs the release tests and lint, verifies the signature
(v1 and v2, and the certificate matches your keystore) and `versionName`/`versionCode` (`versionCode = major*10000 + minor*100 + patch`),
and only then tags that exact commit, pushes the tag and publishes. If the push or the release step fails, it prints the commands to finish by hand.

## Credits and licenses

- Fonts: Barlow Condensed and Inter, under the SIL Open Font License 1.1 (text in `app/src/main/assets/licenses/OFL.txt`).
- Audio: music and sound effects are original and synthesized; the announcer is generated text-to-speech. Details in [`audio-final/CREDITS.md`](audio-final/CREDITS.md).
- Virtual Pet Slasher 2 Turbo is a fan project. Jason (*Friday the 13th*), Freddy (*A Nightmare on Elm Street*), Pinhead (*Hellraiser*), Ghostface (*Scream*), Chucky (*Child's Play*), Michael (*Halloween*) and Leatherface (*The Texas Chain Saw Massacre*), and the films they come from, belong to their respective rights holders. It is not affiliated with or endorsed by any of them, and it is not for sale.
