# Audio credits

- **Music** (`music/`) and **synthesized sound effects** (`sfx/`): original, synthesized from scratch by
  `tools/synth_audio.py` (NumPy). The synthesized sounds are every track of music; the menu blips (`ui_*`); the
  Play hand's gags (`play`, `hand_whoosh`, `boop`, `tickle`, `jingle`); the training whoosh and `train`; the fight's
  hits, blocks, misses, crits, knockouts and each killer's special move; the potion, `unlock`, `levelup`, `gotcha`,
  `refuse` and `tombstone` stings; and the meal and sleep sounds (`chomp`, `food_drop`, `bite`, `perk_up`, `hangover`,
  `snore`). No samples or third-party recordings.
- **Training sounds** (`sfx/`): the eight dot tones `tone_1` to `tone_8` are synthesized like the rest. `reel_spin`,
  `reel_stop`, `slash`, `stone_slide`, `squish`, `splat` and `game_over` were generated with ElevenLabs sound generation
  by `tools/generate_game_sfx.py`, then trimmed and normalized.
- **Pantry sounds** (`sfx/`): `pantry_pick`, `pantry_poof`, `pantry_squelch`, `cook_grill`, `cook_campfire`, `cook_pot`,
  `cook_cauldron`, `cook_sun`, `eat_happy`, `eat_gross` and `favorite_fanfare` were generated with ElevenLabs sound
  generation by `tools/generate_game_sfx.py`, then trimmed and normalized.
- **Weapon impacts** (`sfx/hit_*` and `sfx/heavy_*`): generated the same way by `tools/generate_game_sfx.py`, then
  trimmed and normalized.
- **Player voice** (`voice/play_*`): the cartoon glove's baby talk for Play, generated with ElevenLabs text-to-speech
  (Eleven v4, voice "Jessica - Playful, Bright, Warm") by `tools/generate_player_voice.py`, then trimmed and
  loudness-normalized.
- **Announcer** (`voice/`): generated with ElevenLabs text-to-speech (Eleven v4, voice "David - Sports
  Arena Announcer", shouting) by `tools/generate_announcer.py`, then trimmed and given a short slap-back echo. Its fight
  calls (FIRST BLOOD, FINISH HIM, LAST CHANCE, FLAWLESS VICTORY, COMEBACK) and the Gauntlet's bout calls (BOUT TWO to
  BOUT SEVEN, FINAL BOUT, CHAMPION) were generated the same way.
- **Chucky** (`voice/chucky_*`, except the announcer's `chucky_never_dies`): generated with ElevenLabs text-to-speech
  (Eleven v4, voice "Angry Kid") by `tools/generate_killer_voice.py`, then trimmed and loudness-normalized.

Formats: music is Opus audio in an Ogg container (`.ogg`), played by ExoPlayer. Sound effects and
voice lines are 16-bit PCM mono WAV at 44.1 kHz (`.wav`), played by SoundPool, which cannot decode
Opus-in-Ogg below Android 10 (the app supports Android 5.1 and up).
