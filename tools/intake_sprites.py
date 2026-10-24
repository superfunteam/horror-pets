"""Build sprites-final/ from the hi-res generator rows, registered against the 192x208 sheets.

tools/sprite_sources.json lists each pet's reference sheet and, per animation, its row and hi-res candidates.
A few optional fields cover art that doesn't stand on feet or that was fitted to its cell on its own:
- a pet's "ground": "lowest" stands every animation on its lowest point (a swimmer's fin tips) rather than on
  the median foot row;
- an animation's "refScale" scales its reference row about the row's bottom centre before registering, for a row
  the sheet fitted at a different size from the rest of the pet;
- an animation's "groundOffset" is how far (cell px) the pet's own ground sits above the animation's lowest
  pixels, for effects that hang below him. Jaws's thrash uses 20, a hand-tuned compromise: his swooshes and bite
  marks fill almost the whole cell, so standing him lower or bigger would cut them off at its edge, and he sits a
  little high (about 23 px) instead.

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
    key_magenta,
    _paste,
    register_to_reference,
    resize_rgba,
    split_components,
)

ROOT = Path(__file__).resolve().parents[1]
MIN_IOU = 0.88
MAX_FRINGE = 0.001
MAX_BASELINE_FIX = 12  # largest vertical shift (px, in 2x cell space) applied to line an animation up with idle


class IntakeError(Exception):
    pass


def load_rgba(path: Path) -> np.ndarray:
    return np.asarray(Image.open(path).convert("RGBA"))


def ref_cells(sheet: np.ndarray, row: int | None, frames: int, ref_cell: tuple[int, int]) -> list[np.ndarray]:
    w, h = ref_cell
    y = (row or 0) * h
    return [sheet[y:y + h, c * w:(c + 1) * w].copy() for c in range(frames)]


def scale_refs(refs: list[np.ndarray], factor: float) -> list[np.ndarray]:
    """Scale a row of reference cells by `factor` about the bottom centre of the whole row's bbox, keeping each
    cell's size (whatever a larger factor pushes past the cell is cut).

    The bottom stays where it is, so the row keeps its ground; one shared anchor keeps every frame's place
    relative to the others (a bob stays a bob).
    """
    if factor == 1.0:
        return refs
    boxes = [alpha_bbox(r, thresh=64) for r in refs]
    cx = (min(b[0] for b in boxes) + max(b[2] for b in boxes)) / 2
    by = max(b[3] for b in boxes)
    out = []
    for r in refs:
        h, w = r.shape[:2]
        scaled = resize_rgba(r, (max(1, round(w * factor)), max(1, round(h * factor))))
        cell = np.zeros_like(r)
        _paste(cell, scaled, round(cx * (1 - factor)), round(by * (1 - factor)))
        out.append(cell)
    return out


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
    return resize_rgba(ref, cell)


def process_anim(pet: str, name: str, spec: dict, sheet: np.ndarray, cell, ref_cell, done: dict, log: list) -> dict:
    if spec.get("reference"):
        ref_sheet = load_rgba(ROOT / spec["reference"])
    else:
        ref_sheet = sheet
    refs = scale_refs(ref_cells(ref_sheet, spec.get("row"), spec["frames"], ref_cell), spec.get("refScale", 1.0))

    whole_row = False  # True when no hi-res candidate worked and every frame is the upscaled reference
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
            ious = [iou_vs_reference(c, r) for c, r in zip(cells, refs)]
            chosen = "fallback (all frames)"
            whole_row = True
        else:
            _, cells, ious, chosen = best

    fallback = []
    for i, (r, s) in enumerate(zip(refs, ious)):
        if whole_row:
            fallback.append(i)
            log.append(f"  FALLBACK {pet}/{name}/{i}: no hi-res candidate, iou {s:.3f}")
        elif s < MIN_IOU:
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


def ground_row(name: str, cells: list[np.ndarray], lowest: bool = False) -> int:
    """Row where the feet meet the floor: the lowest point across a jump (it is airborne in most
    frames), the median foot row for every other animation (robust to a single odd frame).
    With `lowest`, every animation's lowest point (a swimmer with no feet, fins flapping)."""
    rows = [foot_row(c) for c in cells]
    return max(rows) if lowest or name == "jump" else int(round(float(np.median(rows))))


def shift_down(cell: np.ndarray, dy: int) -> np.ndarray:
    """Move a cell `dy` rows down (negative = up), filling the vacated rows with transparency.

    Rows pushed past the edge are dropped; nothing wraps around to the opposite side.
    """
    out = np.zeros_like(cell)
    if dy > 0:
        out[dy:] = cell[:-dy]
    elif dy < 0:
        out[:dy] = cell[-dy:]
    else:
        out[:] = cell
    return out


def align_baselines(pet: str, anims: dict, log: list, lowest: bool = False) -> int:
    """Shift every animation so its ground row matches idle's; fail if that needs more than MAX_BASELINE_FIX px.

    An animation's ground is its ground row less its spec's "groundOffset" (effects hanging below the pet)."""
    def own_ground(name: str, a: dict) -> int:
        return ground_row(name, a["cells"], lowest) - a.get("spec", {}).get("groundOffset", 0)

    baseline = own_ground("idle", anims["idle"])
    for name, a in anims.items():
        drift = baseline - own_ground(name, a)
        if drift == 0:
            continue
        if abs(drift) > MAX_BASELINE_FIX:
            raise IntakeError(f"{pet}/{name} baseline drift {drift}px is too large to correct")
        shifted = [shift_down(c, drift) for c in a["cells"]]
        if any((s[..., 3] > 64).sum() != (c[..., 3] > 64).sum() for s, c in zip(shifted, a["cells"])):
            log.append(f"  CLIPPED {pet}/{name}: the {drift:+d}px shift pushed visible pixels off the cell")
        a["cells"] = shifted
        log.append(f"  {pet}/{name}: shifted {drift:+d}px onto baseline {baseline}")
    return baseline


def write_outputs(pet: str, anims: dict, baseline: int, feed: str, cell, out: Path, qa: Path,
                  display_scale: float = 1.0) -> None:
    pdir = out / pet
    pdir.mkdir(parents=True, exist_ok=True)
    manifest = {"pet": pet, "cell": list(cell), "baselineY": baseline, "anchorX": cell[0] // 2,
                "displayScale": display_scale, "feed": feed, "animations": {}}
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
    all_anims: list[dict] = []
    for pet, pcfg in cfg["pets"].items():
        if args.only and pet != args.only:
            continue
        print(f"{pet}:")
        try:
            sheet = load_rgba(ROOT / pcfg["reference"])
            anims: dict = {}
            for name, spec in pcfg["animations"].items():
                anims[name] = process_anim(pet, name, spec, sheet, cell, ref_cell, anims, log)
            ground = pcfg.get("ground", "feet")
            if ground not in ("feet", "lowest"):
                raise IntakeError(f"{pet}: unknown ground {ground!r}")
            baseline = align_baselines(pet, anims, log, lowest=ground == "lowest")
            all_anims.extend(anims.values())
            write_outputs(pet, anims, baseline, pcfg["feed"], cell, ROOT / args.out, ROOT / args.qa,
                          pcfg.get("displayScale", 1.0))
        except IntakeError as e:
            log.append(f"  ERROR {pet}: {e}")
            failed = True
        print("\n".join(log))
        log.clear()
    frames = sum(len(a["cells"]) for a in all_anims)
    fallbacks = sum(len(a["fallback"]) for a in all_anims)
    ious = [i for a in all_anims for i in a["ious"]]
    print(f"SUMMARY: {len(all_anims)} animations, {frames} frames, {fallbacks} fallback frames, "
          f"mean iou {np.mean(ious) if ious else 0:.3f}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
