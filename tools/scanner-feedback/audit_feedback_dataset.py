#!/usr/bin/env python3
"""Audit the local scanner-feedback dataset (Phase 4B-D2).

Reads %LOCALAPPDATA%/PhoneLink/scanner-feedback (or PHONELINK_DATA_DIR/scanner-feedback)
and reports:
  - total samples, per-reason distribution
  - model name + sha256 used
  - correction stats (mean/max delta, per-corner adjustment counts, predictionMissing)
  - structural problems: missing source.jpg / metadata.json, bad JSON, non-JPEG
    source, duplicate source SHA-256, schemaVersion != 1
  - per-month totals

Stdlib only. Read-only; never modifies the dataset.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from collections import Counter, defaultdict
from pathlib import Path

REASONS = ("USER_CORRECTED", "LOW_CONFIDENCE", "MODEL_NOT_FOUND", "CLEAN_SUCCESS")
CORNERS = ("TL", "TR", "BR", "BL")


def default_dataset_root() -> Path:
    if os.environ.get("PHONELINK_DATA_DIR"):
        return Path(os.environ["PHONELINK_DATA_DIR"]) / "scanner-feedback"
    local = os.environ.get("LOCALAPPDATA")
    if not local:
        sys.exit("LOCALAPPDATA not set; pass --root explicitly")
    return Path(local) / "PhoneLink" / "scanner-feedback"


def load_metadata(sample_dir: Path) -> dict | None:
    meta_path = sample_dir / "metadata.json"
    try:
        return json.loads(meta_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=None, help="dataset root (default: LOCALAPPDATA/PhoneLink/scanner-feedback)")
    parser.add_argument("--json", action="store_true", help="emit machine-readable JSON summary")
    args = parser.parse_args()

    root = args.root or default_dataset_root()
    if not root.is_dir():
        sys.exit(f"dataset root not found: {root}")

    total = 0
    reasons = Counter()
    models = Counter()
    months = Counter()
    corners = Counter()
    duplicate_sha = Counter()
    problems: list[str] = []
    delta_max_values: list[float] = []
    delta_mean_values: list[float] = []
    prediction_missing = 0

    for sample_dir in sorted(root.glob("*/*")):  # yyyy-MM / sampleId
        if not sample_dir.is_dir():
            continue
        total += 1
        month = sample_dir.parent.name
        months[month] += 1

        source_path = sample_dir / "source.jpg"
        if not source_path.is_file():
            problems.append(f"{sample_dir.name}: missing source.jpg")
            continue
        header = source_path.read_bytes()[:3]
        if header != b"\xff\xd8\xff":
            problems.append(f"{sample_dir.name}: source.jpg is not a JPEG")

        meta = load_metadata(sample_dir)
        if meta is None:
            problems.append(f"{sample_dir.name}: unreadable metadata.json")
            continue

        if meta.get("schemaVersion") != 1:
            problems.append(f"{sample_dir.name}: unexpected schemaVersion {meta.get('schemaVersion')}")

        reason = meta.get("reason", "<missing>")
        reasons[reason] += 1

        model = meta.get("model", {})
        models[f"{model.get('name', '<missing>')}@{model.get('sha256', '<missing>')}"] += 1

        source = meta.get("source", {})
        if source.get("sha256"):
            duplicate_sha[source["sha256"]] += 1

        correction = meta.get("correction", {})
        if correction.get("maxDelta") is not None:
            delta_max_values.append(float(correction["maxDelta"]))
        if correction.get("meanDelta") is not None:
            delta_mean_values.append(float(correction["meanDelta"]))
        if correction.get("predictionMissing"):
            prediction_missing += 1
        for corner in correction.get("adjustedCorners", []):
            corners[str(corner).upper()] += 1

    if args.json:
        print(json.dumps({
            "root": str(root),
            "total_samples": total,
            "per_month": dict(sorted(months.items())),
            "reasons": dict(reasons),
            "models": dict(models),
            "corner_adjustments": dict(corners),
            "prediction_missing": prediction_missing,
            "mean_delta": {"mean": avg(delta_mean_values), "min": minmax(delta_mean_values)[0], "max": minmax(delta_mean_values)[1]},
            "max_delta": {"mean": avg(delta_max_values), "min": minmax(delta_max_values)[0], "max": minmax(delta_max_values)[1]},
            "duplicate_source_shas": {sha: n for sha, n in duplicate_sha.items() if n > 1},
            "problems": problems,
        }, indent=2))
        return 0

    print(f"dataset root : {root}")
    print(f"total samples: {total}")
    print("\nper month:")
    for month, n in sorted(months.items()):
        print(f"  {month}: {n}")
    print("\nreasons:")
    for reason in REASONS:
        print(f"  {reason:16s} {reasons.get(reason, 0)}")
    print("\nmodels:")
    for model, n in models.items():
        print(f"  {model}: {n}")
    print("\ncorner adjustments:")
    for corner in CORNERS:
        print(f"  {corner:4s} {corners.get(corner, 0)}")
    print(f"\npredictionMissing (model failed to find document): {prediction_missing}")
    if delta_mean_values:
        print(f"meanDelta: mean={avg(delta_mean_values):.4f} min={min(delta_mean_values):.4f} max={max(delta_mean_values):.4f}")
    if delta_max_values:
        print(f"maxDelta : mean={avg(delta_max_values):.4f} min={min(delta_max_values):.4f} max={max(delta_max_values):.4f}")
    dupes = {sha: n for sha, n in duplicate_sha.items() if n > 1}
    print(f"\nduplicate source SHA-256: {len(dupes)}")
    for sha, n in dupes.items():
        print(f"  {sha[:16]}… x{n}")
    print(f"\nproblems: {len(problems)}")
    for problem in problems:
        print(f"  {problem}")

    if problems:
        return 2
    return 1 if total == 0 else 0


def avg(values: list[float]) -> float:
    return sum(values) / len(values) if values else 0.0


def minmax(values: list[float]) -> tuple[float, float]:
    return (min(values), max(values)) if values else (0.0, 0.0)


if __name__ == "__main__":
    raise SystemExit(main())