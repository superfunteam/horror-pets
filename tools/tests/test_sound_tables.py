"""Check that the sound tools' tables name every file the app loads (weapon impacts, Chucky's voice, the announcer's calls).

Plain asserts, no pytest, no network and no key: importing the tools must not call the API.
Run: tools/.venv/bin/python -I tools/tests/test_sound_tables.py (or pytest tools/tests -q)
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))  # the tools dir
import generate_announcer  # noqa: E402
import generate_game_sfx  # noqa: E402
import generate_killer_voice  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
CHUCKY = [
    "chucky_idle_1", "chucky_idle_2", "chucky_idle_3", "chucky_feed_1", "chucky_feed_2", "chucky_play_1", "chucky_play_2",
    "chucky_attack_1", "chucky_attack_2", "chucky_heavy", "chucky_hurt_1", "chucky_hurt_2", "chucky_block", "chucky_special",
    "chucky_win", "chucky_lose", "chucky_miss",
]
ANNOUNCER = [
    "finish_him", "flawless_victory", "first_blood", "last_chance", "comeback",
    "bout_2", "bout_3", "bout_4", "bout_5", "bout_6", "bout_7", "final_bout", "champion",
]


def pet_ids() -> list[str]:
    """The PetId names in the app's model, lowercase: a ninth killer fails this test until it has its weapon sounds."""
    source = (ROOT / "app/src/main/java/team/superfun/horrorpets/model/Model.kt").read_text()
    body = re.search(r"enum class PetId\(.*?\) \{(.*?)\n\}", source, re.S).group(1)
    return [name.lower() for name in re.findall(r"^\s+([A-Z]+)\(", body, re.M)]


def test_every_killer_has_a_hit_and_a_heavy() -> None:
    PETS = pet_ids()
    assert len(PETS) == 8 and "jaws" in PETS  # the parse found the whole enum
    weapons = generate_game_sfx.WEAPONS
    assert set(weapons) == {f"{kind}_{pet}" for pet in PETS for kind in ("hit", "heavy")}
    assert len(weapons) == 16
    for name, (prompt, seconds) in weapons.items():
        assert prompt.endswith(generate_game_sfx.DRY), name  # dry and close, so no tail rings under the music
        assert seconds == (0.5 if name.startswith("hit_") else 0.8), name
    assert not set(weapons) & set(generate_game_sfx.SOUNDS)  # a weapon never overwrites an existing sound
    assert not set(weapons) & generate_game_sfx.TONAL_SOUNDS  # a blow must not ring


def test_the_sfx_tool_covers_both_tables() -> None:
    assert set(generate_game_sfx.ALL) == set(generate_game_sfx.SOUNDS) | set(generate_game_sfx.WEAPONS)


def test_chucky_has_his_seventeen_lines() -> None:
    voice, lines = generate_killer_voice.VOICES["chucky"]
    assert voice == "Angry Kid"
    assert list(lines) == CHUCKY
    assert all(text.strip() for text in lines.values())
    assert not any("[shouting]" in text for text in lines.values())  # he is in the room, not on the PA
    assert generate_killer_voice.VOICE_SETTINGS == {"stability": 0.35, "similarity_boost": 0.8}


def test_the_announcer_has_the_big_calls() -> None:
    lines = generate_announcer.LINES
    assert set(ANNOUNCER) <= set(lines)
    for name in ANNOUNCER:
        text = lines[name]
        assert text.startswith("[shouting] ") and text.endswith("!!!"), name
        assert text == text.upper().replace("[SHOUTING]", "[shouting]"), name  # capitals, like the others
    # the bout numbers are spelled out in the text; generate_announcer.NUMBERS maps the digits the speech-to-text may
    # write, so the transcript check still matches "Bout 5!" to BOUT FIVE
    assert lines["bout_2"] == "[shouting] BOUT TWO!!!"
    assert lines["bout_7"] == "[shouting] BOUT SEVEN!!!"
    assert generate_announcer.match(lines["bout_5"], "Bout five!") == 1.0
    assert generate_announcer.match(lines["bout_5"], "Bout 5!") == 1.0


def main() -> int:
    tests = [(n, f) for n, f in sorted(globals().items()) if n.startswith("test_") and callable(f)]
    for name, test in tests:
        test()
    print("ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
