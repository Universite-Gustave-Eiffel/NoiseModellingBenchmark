#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Stage tutorial resources from the official release; never redistribute them."""

import argparse
import hashlib
import json
import shutil
from pathlib import Path

HERE = Path(__file__).resolve().parent


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def prepare(stock, source, output, engine_version="v5.0.1"):
    recipe = json.loads((HERE / "fixture.json").read_text())
    engine = recipe["engines"][engine_version]
    resources = source / "resources/org/noise_planet/noisemodelling/wps"
    # Validate every source before creating the local fixture. The benchmark's
    # existing downloader obtains the official engine distribution, including data.
    for name, expected in recipe["source_resource_sha256"].items():
        if digest(resources / name) != expected:
            raise ValueError(
                f"Tutorial resource differs from the declared fixture: {name}"
            )
    for name, expected in engine["stock_script_sha256"].items():
        if digest(stock / name) != expected:
            raise ValueError(
                f"Loaded stock script differs from the declared release: {name}"
            )
    destination = output / "resources"
    destination.mkdir(parents=True, exist_ok=True)
    for name, expected in recipe["source_resource_sha256"].items():
        path = destination / name
        if path.exists():
            if digest(path) != expected:
                raise ValueError(f"Existing local fixture changed: {name}")
        else:
            shutil.copyfile(resources / name, path)
        if digest(path) != expected:
            raise ValueError(f"Copied fixture hash mismatch: {name}")
    # This is a preparation receipt, not independent authentication of all engine
    # jars. The Groovy case rechecks resource/script bytes before any calculation.
    pins = {
        "schema": 1,
        "case": recipe["case"],
        "engine_version": engine_version,
        "source_resource_sha256": recipe["source_resource_sha256"],
        "stock_script_sha256": engine["stock_script_sha256"],
        "configuration": recipe["configuration"],
        "screen_wkt": recipe["screen_wkt"],
        "screen_id": recipe["screen_id"],
        "srid": recipe["srid"],
        "data_source": recipe["data_source"],
        "data_license": recipe["data_license"],
    }
    (output / "PINS.json").write_text(json.dumps(pins, indent=2) + "\n")
    return output / "PINS.json"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stock-dir", type=Path, required=True)
    parser.add_argument(
        "--engine-version", choices=("v5.0.0", "v5.0.1"), default="v5.0.1"
    )
    parser.add_argument("--fixture-stock-dir", type=Path)
    parser.add_argument("--output", type=Path, default=HERE)
    args = parser.parse_args()
    print(
        prepare(
            args.stock_dir.resolve(),
            (args.fixture_stock_dir or args.stock_dir).resolve(),
            args.output.resolve(),
            args.engine_version,
        )
    )
