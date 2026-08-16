#!/usr/bin/env python3
"""Generate a visual contact sheet over the local scanner-feedback dataset.

For each sample: source.jpg with the model's predicted quad (red) and the
user-confirmed corrected quad (green) overlaid, labeled with sampleId, reason,
and maxDelta. Tiles are laid out in a grid and saved as out/contact_sheet.png.

Requires Pillow:  pip install pillow

Read-only over the dataset; writes only to --out.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

REASON_COLORS = {
    "USER_CORRECTED": (255, 215, 0),   # yellow
    "LOW_CONFIDENCE": (255, 140, 0),   # orange
    "MODEL_NOT_FOUND": (255, 60, 60),  # red
    "CLEAN_SUCCESS": (120, 200, 120),  # green
}

TILE = 384
LABEL_H = 44
COLUMNS = 4
GRID = (TILE, TILE + LABEL_H)


def default_dataset_root() -> Path:
    if os.environ.get("PHONELINK_DATA_DIR"):
        return Path(os.environ["PHONELINK_DATA_DIR"]) / "scanner-feedback"
    local = os.environ.get("LOCALAPPDATA")
    if not local:
        sys.exit("LOCALAPPDATA not set; pass --root explicitly")
    return Path(local) / "PhoneLink" / "scanner-feedback"


def quad_points(quad: dict, width: int, height: int) -> list[tuple[int, int]]:
    if not quad:
        return []
    return [
        (round(float(quad[corner][0]) * width), round(float(quad[corner][1]) * height))
        for corner in ("tl", "tr", "br", "bl")
    ]


def load_font(size: int) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    for candidate in ("C:/Windows/Fonts/segoeui.ttf", "C:/Windows/Fonts/arial.ttf"):
        if Path(candidate).exists():
            return ImageFont.truetype(candidate, size)
    return ImageFont.load_default()


def render_sample(sample_dir: Path) -> Image.Image | None:
    source_path = sample_dir / "source.jpg"
    meta_path = sample_dir / "metadata.json"
    if not source_path.is_file() or not meta_path.is_file():
        return None
    try:
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
        image = Image.open(source_path).convert("RGB")
    except (OSError, json.JSONDecodeError, ValueError):
        return None

    image.thumbnail((TILE, TILE))
    tile = Image.new("RGB", GRID, (24, 24, 24))
    tile.paste(image, (0, 0))
    draw = ImageDraw.Draw(tile)
    font = load_font(13)

    reason = meta.get("reason", "<missing>")
    color = REASON_COLORS.get(reason, (200, 200, 200))
    width, height = image.size

    predicted = quad_points(meta.get("predictedQuad"), width, height)
    corrected = quad_points(meta.get("correctedQuad"), width, height)
    if len(predicted) == 4:
        draw.line(predicted + [predicted[0]], fill=(255, 40, 40), width=3)
    if len(corrected) == 4:
        draw.line(corrected + [corrected[0]], fill=(60, 255, 90), width=3)
        for point in corrected:
            draw.ellipse((point[0] - 4, point[1] - 4, point[0] + 4, point[1] + 4), fill=(60, 255, 90))

    max_delta = meta.get("correction", {}).get("maxDelta", 0.0)
    label = f"{sample_dir.name}  {reason}  d={float(max_delta):.3f}"
    draw.rectangle((0, TILE, GRID[0], GRID[1]), fill=(16, 16, 16))
    draw.text((8, TILE + 6), label, fill=color, font=font)
    return tile


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=None, help="dataset root (default: LOCALAPPDATA/PhoneLink/scanner-feedback)")
    parser.add_argument("--out", type=Path, default=Path("out"), help="output directory (default: out/)")
    parser.add_argument("--columns", type=int, default=COLUMNS)
    args = parser.parse_args()

    root = args.root or default_dataset_root()
    if not root.is_dir():
        sys.exit(f"dataset root not found: {root}")

    samples = sorted(p for p in root.glob("*/*") if p.is_dir())
    rendered = []
    skipped = 0
    for sample_dir in samples:
        tile = render_sample(sample_dir)
        if tile is None:
            skipped += 1
        else:
            rendered.append(tile)

    if not rendered:
        sys.exit(f"no renderable samples under {root}")

    columns = args.columns
    rows = (len(rendered) + columns - 1) // columns
    sheet = Image.new("RGB", (columns * GRID[0], rows * GRID[1]), (12, 12, 12))
    for index, tile in enumerate(rendered):
        sheet.paste(tile, ((index % columns) * GRID[0], (index // columns) * GRID[1]))

    args.out.mkdir(parents=True, exist_ok=True)
    output = args.out / "contact_sheet.png"
    sheet.save(output)
    print(f"contact sheet: {output} ({len(rendered)} samples, {skipped} skipped)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())