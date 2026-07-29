#!/usr/bin/env python3
import json
import math
import random
from pathlib import Path
from itertools import combinations

ROOT     = Path(__file__).parent
OUTPUT   = ROOT / "output"
DATA_DIR = ROOT / "website" / "data"
SCATTER_MAX_POINTS = 29323

def geom_key(geometry: dict) -> str | None:

    if geometry is None:
        return None
    gtype = geometry.get("type", "")
    coords = geometry.get("coordinates")
    if coords is None:
        return None

    if gtype == "Point":
        x, y = coords[0], coords[1]

    return f"{x, y}"


def load_receivers(version: str) -> dict[str, float]:

    geojson_path = OUTPUT / version / "RECEIVERS_LEVEL.geojson"
    if not geojson_path.exists():
        return {}

    with open(geojson_path) as f:
        data = json.load(f)

    receivers = {}
    skipped = 0
    for feature in data.get("features", []):
        if version.startswith("v4."):
            props    = feature.get("properties", {})
            geometry = feature.get("geometry")
            laeq = props.get("LAEQ") or props.get("laeq")
            key  = geom_key(geometry)
            if key is None or laeq is None:
                skipped += 1
                continue
            receivers[key] = float(laeq)

        else:
            props    = feature.get("properties", {})
            geometry = feature.get("geometry")
            period   = props.get("PERIOD", "")
            if period != "D":
                continue
            laeq = props.get("LAEQ") or props.get("laeq")
            key  = geom_key(geometry)
            if key is None or laeq is None:
                skipped += 1
                continue
            receivers[key] = float(laeq)

    return receivers


def compare(v_a: str, data_a: dict, v_b: str, data_b: dict) -> dict:

    common_keys = list(set(data_a.keys()) & set(data_b.keys()))
    n = len(common_keys)

    if n == 0:
        return None

    deltas = [abs(data_a[k] - data_b[k]) for k in common_keys]
    max_delta   = max(deltas)
    mean_delta  = sum(deltas) / n

    sample_keys = random.sample(common_keys, min(SCATTER_MAX_POINTS, n))

    scatter = [
        [round(data_a[k], 2), round(data_b[k], 2)]
        for k in sample_keys
    ]

    deltas_signed = [round(data_b[k] - data_a[k], 2) for k in common_keys]

    result = {
        "version_a"   : v_a,
        "version_b"   : v_b,
        "n_common"    : n,
        "n_only_a"    : len(data_a) - n,
        "n_only_b"    : len(data_b) - n,
        "max_delta"   : round(max_delta, 4),
        "mean_delta"  : round(mean_delta, 4),
        "scatter"     : scatter,
        "deltas_signed": deltas_signed,
    }
    return result


def main():
    versions = sorted([
        d.name for d in OUTPUT.iterdir()
        if d.is_dir() and (d / "RECEIVERS_LEVEL.geojson").exists()
    ])

    if len(versions) < 2:
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        (DATA_DIR / "comparisons.json").write_text("[]")
        return

    all_data = {v: load_receivers(v) for v in versions}

    comparisons = []
    for v_a, v_b in combinations(versions, 2):
        result = compare(v_a, all_data[v_a], v_b, all_data[v_b])
        if result:
            comparisons.append(result)

    DATA_DIR.mkdir(parents=True, exist_ok=True)
    out_path = DATA_DIR / "comparisons.json"
    out_path.write_text(json.dumps(comparisons, indent=2))


if __name__ == "__main__":
    main()

