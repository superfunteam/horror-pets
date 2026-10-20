# Audio credits

- **Music** (`music/`) and **sound effects** (`sfx/`): original, synthesized from scratch by
  `tools/synth_audio.py` (NumPy). No samples or third-party recordings.
- **Announcer** (`voice/`): generated with OpenAI text-to-speech (`gpt-4o-mini-tts-2025-12-15`,
  voice "onyx") by `tools/generate_announcer.py`, then trimmed and given a short slap-back echo.

All files are Opus audio in an Ogg container (`.ogg`).
