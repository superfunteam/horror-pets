# Audio credits

- **Music** (`music/`) and **sound effects** (`sfx/`): original, synthesized from scratch by
  `tools/synth_audio.py` (NumPy), the meal-status sounds `perk_up` and `hangover` among them. No samples or
  third-party recordings.
- **Training sounds** (`sfx/`): the eight dot tones `tone_1` to `tone_8` are synthesized like the rest. `reel_spin`,
  `reel_stop`, `slash`, `stone_slide`, `squish`, `splat` and `game_over` were generated with ElevenLabs sound generation
  by `tools/generate_game_sfx.py`, then trimmed and normalized.
- **Weapon impacts** (`sfx/hit_*` and `sfx/heavy_*`): generated the same way by `tools/generate_game_sfx.py`, then
  trimmed and normalized.
- **Announcer** (`voice/`): generated with ElevenLabs text-to-speech (Eleven v4, voice "David - Sports
  Arena Announcer", shouting) by `tools/generate_announcer.py`, then trimmed and given a short slap-back echo. Its fight
  calls (FIRST BLOOD, FINISH HIM, LAST CHANCE, FLAWLESS VICTORY, COMEBACK) and the Gauntlet's bout calls (BOUT TWO to
  BOUT SEVEN, FINAL BOUT, CHAMPION) were generated the same way.
- **Chucky** (`voice/chucky_*`, except the announcer's `chucky_never_dies`): generated with ElevenLabs text-to-speech
  (Eleven v4, voice "Angry Kid") by `tools/generate_killer_voice.py`, then trimmed and loudness-normalized.

Formats: music is Opus audio in an Ogg container (`.ogg`), played by ExoPlayer. Sound effects and
announcer lines are 16-bit PCM mono WAV at 44.1 kHz (`.wav`), played by SoundPool, which cannot decode
Opus-in-Ogg below Android 10 (the app supports Android 5.1 and up).
