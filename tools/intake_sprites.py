"""Build sprites-final/ from the hi-res generator rows, registered against the 192x208 sheets.

Usage: tools/.venv/bin/python -I tools/intake_sprites.py --out sprites-final --qa art-work/sprite-qa
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parent))
from spritekit import (  # noqa: E402
    alpha_bbox,
    fringe_ratio,
    iou_vs_reference,
    _resize_rgba,
    key_magenta,
    register_to_reference,
    split_components,
)

ROOT = Path(__file__).resolve().parents[1]
MIN_IOU = 0.88
MAX_FRINGE = 0.001
MAX_BASELINE_DRIFT = 4
MAX_BASELINE_FIX = 12


class IntakeError(Exception):
    pass


def load_rgba(path: Path) -> np.ndarray:
    return np.asarray(Image.open(path).convert("RGBA"))


def ref_cells(sheet: np.ndarray, row: int | None, frames: int, ref_cell: tuple[int, int]) -> list[np.ndarray]:
    w, h = ref_cell
    y = (row or 0) * h
    return [sheet[y:y + h, c * w:(c + 1) * w].copy() for c in range(frames)]


def foot_row(cell: np.ndarray) -> int:
    return alpha_bbox(cell, thresh=64)[3]


def build_candidate(path: Path, refs: list[np.ndarray], cell: tuple[int, int]) -> tuple[list[np.ndarray], list[float]]:
    src = np.asarray(Image.open(path).convert("RGBA"))
    if src[..., 3].min() < 255:
        # already cut out: keep its alpha, but still scrub any magenta spill from the colors
        keyed = key_magenta(src[..., :3])
        keyed[..., 3] = np.minimum(keyed[..., 3], src[..., 3])
    else:
        keyed = key_magenta(src[..., :3])
    crops = split_components(keyed, expected=len(refs))
    cells = register_to_reference(crops, refs, cell=cell)
    return cells, [iou_vs_reference(c, r) for c, r in zip(cells, refs)]


def upscale_fallback(ref: np.ndarray, cell: tuple[int, int]) -> np.ndarray:
    return _resize_rgba(ref, cell)


def process_anim(pet: str, name: str, spec: dict, sheet: np.ndarray, cell, ref_cell, done: dict, log: list) -> dict:
    if spec.get("reference"):
        ref_sheet = load_rgba(ROOT / spec["reference"])
    else:
        ref_sheet = sheet
    refs = ref_cells(ref_sheet, spec.get("row"), spec["frames"], ref_cell)

    if "derive" in spec:
        kind, src = spec["derive"].split(":")
        assert kind == "mirror", spec["derive"]
        cells = [c[:, ::-1].copy() for c in done[src]["cells"]]
        ious = [iou_vs_reference(c, r) for c, r in zip(cells, refs)]
        chosen = f"mirror of {src}"
    else:
        best = None
        for p in spec["hires"]:
            try:
                cells, ious = build_candidate(ROOT / p, refs, cell)
            except ValueError as e:
                log.append(f"  skip {pet}/{name} candidate {Path(p).name}: {e}")
                continue
            score = float(np.mean(ious))
            if best is None or score > best[0]:
                best = (score, cells, ious, Path(p).name)
        if best is None:
            log.append(f"  NO HIRES {pet}/{name}: every candidate failed, upscaling the whole row")
            cells = [upscale_fallback(r, cell) for r in refs]
            ious = [1.0] * len(refs)
            chosen = "fallback (all frames)"
        else:
            _, cells, ious, chosen = best

    fallback = []
    for i, (c, r, s) in enumerate(zip(cells, refs, ious)):
        if s < MIN_IOU:
            cells[i] = upscale_fallback(r, cell)
            fallback.append(i)
            log.append(f"  FALLBACK {pet}/{name}/{i}: iou {s:.3f}")
    for i, c in enumerate(cells):
        if not (c[..., 3] > 64).any():
            raise IntakeError(f"{pet}/{name}/{i} is empty")
        fr = fringe_ratio(c)
        if fr >= MAX_FRINGE:
            raise IntakeError(f"{pet}/{name}/{i} fringe ratio {fr:.4f}")
        a = c[..., 3] > 64
        if a[0].any() or a[:, 0].any() or a[:, -1].any() or a[-1].any():
            log.append(f"  EDGE {pet}/{name}/{i}: touches the cell edge (check for cropping)")
    log.append(f"  {pet}/{name}: {chosen}, mean iou {np.mean(ious):.3f}, fallback {len(fallback)}")
    return {"cells": cells, "ious": ious, "fallback": fallback, "spec": spec}


def ground_row(cells: list[np.ndarray]) -> int:
    """Lowest point reached across an animation: where the feet meet the floor (jumps included)."""
    return max(foot_row(c) for c in cells)


def align_baselines(pet: str, anims: dict, log: list) -> int:
    baseline = ground_row(anims["idle"]["cells"])
    for name, a in anims.items():
        drift = baseline - ground_row(a["cells"])
        if drift == 0:
            continue
        if abs(drift) > MAX_BASELINE_FIX:
            raise IntakeError(f"{pet}/{name} baseline drift {drift}px is too large to correct")
        a["cells"] = [np.roll(c, drift, axis=0) for c in a["cells"]]
        log.append(f"  {pet}/{name}: shifted {drift:+d}px onto baseline {baseline}")
    for name, a in anims.items():
        ground = ground_row(a["cells"])
        if abs(ground - baseline) > MAX_BASELINE_DRIFT:
            raise IntakeError(f"{pet}/{name} baseline {ground} vs {baseline}")
    return baseline


def write_outputs(pet: str, anims: dict, baseline: int, feed: str, cell, out: Path, qa: Path) -> None:
    pdir = out / pet
    pdir.mkdir(parents=True, exist_ok=True)
    manifest = {"pet": pet, "cell": list(cell), "baselineY": baseline, "anchorX": cell[0] // 2,
                "feed": feed, "animations": {}}
    for name, a in anims.items():
        strip = np.concatenate(a["cells"], axis=1)
        Image.fromarray(strip, "RGBA").save(pdir / f"{name}.png", optimize=True)
        spec = a["spec"]
        manifest["animations"][name] = {"file": f"{name}.png", "frames": len(a["cells"]),
                                        "durationsMs": spec["durationsMs"], "loop": spec["loop"]}
    (pdir / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")

    qa.mkdir(parents=True, exist_ok=True)
    cw, ch = cell[0] // 2, cell[1] // 2
    names = list(anims)
    sheet = Image.new("RGB", (8 * cw, len(names) * (ch + 18)), (96, 96, 100))
    draw = ImageDraw.Draw(sheet)
    for r, name in enumerate(names):
        a = anims[name]
        y = r * (ch + 18)
        draw.text((4, y + 2), name, fill=(255, 255, 255))
        for i, c in enumerate(a["cells"]):
            im = Image.fromarray(c, "RGBA").resize((cw, ch), Image.LANCZOS)
            sheet.paste(im, (i * cw, y + 18), im)
            draw.line([(i * cw, y + 18 + baseline // 2), ((i + 1) * cw - 1, y + 18 + baseline // 2)], fill=(0, 200, 255))
            color = (255, 60, 60) if i in a["fallback"] else (255, 255, 255)
            draw.text((i * cw + 4, y + 20), f"{i} {a['ious'][i]:.2f}", fill=color)
    sheet.save(qa / f"{pet}-contact.png")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="sprites-final")
    ap.add_argument("--qa", default="art-work/sprite-qa")
    ap.add_argument("--only", help="limit to one pet")
    args = ap.parse_args()

    cfg = json.loads((ROOT / "tools/sprite_sources.json").read_text())
    cell = tuple(cfg["cell"])
    ref_cell = tuple(cfg["refCell"])
    log: list[str] = []
    failed = False
    for pet, pcfg in cfg["pets"].items():
        if args.only and pet != args.only:
            continue
        print(f"{pet}:")
        try:
            sheet = load_rgba(ROOT / pcfg["reference"])
            anims: dict = {}
            for name, spec in pcfg["animations"].items():
                anims[name] = process_anim(pet, name, spec, sheet, cell, ref_cell, anims, log)
            baseline = align_baselines(pet, anims, log)
            write_outputs(pet, anims, baseline, pcfg["feed"], cell, ROOT / args.out, ROOT / args.qa)
        except IntakeError as e:
            log.append(f"  ERROR {pet}: {e}")
            failed = True
        print("\n".join(log))
        log.clear()
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
