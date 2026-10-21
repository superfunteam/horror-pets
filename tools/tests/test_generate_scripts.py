import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import generate_announcer  # noqa: E402
import generate_scenes  # noqa: E402

KEY = "sk-test-0123456789"


def test_redact_removes_the_key_in_both_scripts():
    text = f"401: Incorrect API key provided: {KEY}. Check {KEY}."
    for mod in (generate_scenes, generate_announcer):
        out = mod.redact(text, KEY)
        assert KEY not in out
        assert out.count("[redacted]") == 2


def test_generate_scenes_exits_1_on_failed_job_without_leaking_the_key(monkeypatch, capsys, tmp_path):
    def boom(*args, **kwargs):
        raise RuntimeError(f"401: Incorrect API key provided: {KEY}")

    monkeypatch.setenv("OPENAI_API_KEY", KEY)
    monkeypatch.setattr(generate_scenes, "generate", boom)
    monkeypatch.setattr(sys, "argv", ["generate_scenes.py", "--only", "jason/dock", "--n", "2",
                                      "--model", "fake-model", "--out", str(tmp_path)])
    assert generate_scenes.main() == 1
    captured = capsys.readouterr()
    assert "FAIL jason/dock" in captured.out
    assert KEY not in captured.out + captured.err
    assert "[redacted]" in captured.out
    assert not any(tmp_path.iterdir())
