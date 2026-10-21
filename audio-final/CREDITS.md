# Audio credits

- **Music** (`music/`) and **sound effects** (`sfx/`): original, synthesized from scratch by
  `tools/synth_audio.py` (NumPy). No samples or third-party recordings.
- **Announcer** (`voice/`): generated with ElevenLabs text-to-speech (Eleven v4, voice "David - Sports
  Arena Announcer", shouting) by `tools/generate_announcer.py`, then trimmed and given a short slap-back echo.

Formats: music is Opus audio in an Ogg container (`.ogg`), played by ExoPlayer. Sound effects and
announcer lines are 16-bit PCM mono WAV at 44.1 kHz (`.wav`), played by SoundPool, which cannot decode
Opus-in-Ogg below Android 10 (the app supports Android 5.1 and up).
