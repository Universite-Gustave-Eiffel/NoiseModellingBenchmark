#!/usr/bin/env python3
import argparse
import csv
import hashlib
import json
import math
import random
import re
import sys
from itertools import combinations
from pathlib import Path

ROOT     = Path(__file__).parent
OUTPUT   = ROOT / "output"
DATA_DIR = ROOT / "website" / "data"
SCATTER_MAX_POINTS = 29411


def load_receivers(version: str, directory=None) -> dict[str, float]:

    geojson_path = (Path(directory) if directory else OUTPUT / version) / "RECEIVERS_LEVEL.geojson"
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
            key  = props.get("IDRECEIVER")
            if key is None or laeq is None:
                skipped += 1
                continue

            coords = None
            if geometry:
                gtype = geometry.get("type", "")
                c = geometry.get("coordinates")
                if c is not None:
                    if gtype == "Point":
                        coords = [round(c[0], 6), round(c[1], 6)]
                    elif gtype in ("MultiPoint", "LineString") and c:
                        coords = [round(c[0][0], 6), round(c[0][1], 6)]


            receivers[key] = {"laeq": float(laeq), "coords": coords}


        else:
            props    = feature.get("properties", {})
            geometry = feature.get("geometry")
            period   = props.get("PERIOD", "")
            if period != "D":
                continue
            laeq = props.get("LAEQ") or props.get("laeq")
            key  = props.get("IDRECEIVER")
            if key is None or laeq is None:
                skipped += 1
                continue
            coords = None
            if geometry:
                gtype = geometry.get("type", "")
                c = geometry.get("coordinates")
                if c is not None:
                    if gtype == "Point":
                        coords = [round(c[0], 6), round(c[1], 6)]
                    elif gtype in ("MultiPoint", "LineString") and c:
                        coords = [round(c[0][0], 6), round(c[0][1], 6)]


            receivers[key] = {"laeq": float(laeq), "coords": coords}

    return receivers


def compare(v_a: str, data_a: dict, v_b: str, data_b: dict) -> dict:

    common_keys = list(set(data_a.keys()) & set(data_b.keys()))
    n = len(common_keys)

    if n == 0:
        return None

    deltas    = [abs(data_a[k]["laeq"] - data_b[k]["laeq"]) for k in common_keys]
    max_delta   = max(deltas)
    mean_delta  = sum(deltas) / n
    std_delta  = math.sqrt(sum((d - mean_delta) ** 2 for d in deltas) / n)

    sample_keys = random.sample(common_keys, min(SCATTER_MAX_POINTS, n))

    scatter = []
    for k in sample_keys:
        if data_a[k]["laeq"] <= -89.00:
            data_a[k]["laeq"] = 0.00
        if data_b[k]["laeq"] <= -89.00:
            data_b[k]["laeq"] = 0.00
        scatter.append([round(data_a[k]["laeq"],2), round(data_b[k]["laeq"],2)])
    deltas_signed = [round(data_b[k]["laeq"] - data_a[k]["laeq"], 2) for k in common_keys]

    diff_map = []
    for k in sample_keys:
        coords = data_a[k].get("coords")
        if coords is None:
            coords = data_b[k].get("coords")
        if coords:
            delta_val = round(data_b[k]["laeq"] - data_a[k]["laeq"], 2)
            diff_map.append([coords[0], coords[1], delta_val])


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
        "std_delta"    : round(std_delta, 4),
        "diff_map"     : diff_map,
    }
    return result


def compare_receiver_csv(csv_a, csv_b=None, identity_a=None, identity_b=None):
    """Validate complete native exports, then compare their unmodified bytes.

    Sidecar identities bind the fixture/configuration and the scripts actually
    loaded by the simulation. Different engine versions may use different
    scripts; repeated runs of one engine version must have identical pins.
    """

    def unique_object(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"Duplicate identity field: {key}")
            result[key] = value
        return result

    def bad_constant(value):
        raise ValueError("Nonfinite identity number")

    def finite_number(value):
        number = float(value)
        if not math.isfinite(number):
            raise ValueError("Nonfinite identity number")
        return number

    def validate(path, identity_path):
        path = Path(path)
        identity_path = (
            Path(identity_path) if identity_path else path.parent / "IDENTITY.json"
        )
        with identity_path.open(encoding="utf-8") as stream:
            identity = json.load(
                stream,
                object_pairs_hook=unique_object,
                parse_constant=bad_constant,
                parse_float=finite_number,
            )
        if not isinstance(identity, dict):
            raise ValueError("Identity must be an object")
        required = (
            "schema",
            "case",
            "height_m",
            "engine_version",
            "source_resource_sha256",
            "stock_script_sha256",
            "configuration",
            "screen_wkt",
            "screen_id",
            "srid",
            "counts",
            "row_count",
            "columns",
            "complete_native_export",
        )
        if any(key not in identity for key in required):
            raise ValueError("Missing complete receiver-export identity")
        if type(identity["schema"]) is not int or identity["schema"] != 1:
            raise ValueError("Unsupported receiver-export identity schema")
        if (
            identity["case"] != "tutorial-barrier"
            or type(identity["height_m"]) is not int
            or identity["height_m"] not in (1, 3, 5)
        ):
            raise ValueError("Unsupported tutorial case/height")
        if (
            not isinstance(identity["engine_version"], str)
            or not identity["engine_version"]
        ):
            raise ValueError("Missing engine version")
        if identity["complete_native_export"] is not True:
            raise ValueError("Export is not declared complete")
        for key in ("source_resource_sha256", "stock_script_sha256"):
            pins = identity[key]
            if not isinstance(pins, dict) or not pins:
                raise ValueError(f"Missing {key}")
            if key == "stock_script_sha256" and len(pins) != 3:
                raise ValueError("Expected three loaded stock-script pins")
            for name, digest in pins.items():
                if (
                    not isinstance(name, str)
                    or not name
                    or Path(name).is_absolute()
                    or ".." in Path(name).parts
                    or not isinstance(digest, str)
                    or not re.fullmatch(r"[0-9a-f]{64}", digest)
                ):
                    raise ValueError(f"Invalid {key}")
        if (
            not isinstance(identity["configuration"], dict)
            or not identity["configuration"]
        ):
            raise ValueError("Missing calculation configuration")
        if (
            not isinstance(identity["screen_wkt"], str)
            or not identity["screen_wkt"].strip()
        ):
            raise ValueError("Missing screen footprint")
        if (
            type(identity["screen_id"]) is not int
            or identity["screen_id"] != 1702
            or type(identity["srid"]) is not int
            or identity["srid"] != 2154
        ):
            raise ValueError("Wrong screen identifier or SRID")
        counts = identity["counts"]
        if (
            not isinstance(counts, dict)
            or type(counts.get("receivers")) is not int
            or counts["receivers"] < 1
            or any(type(value) is not int or value < 0 for value in counts.values())
        ):
            raise ValueError("Invalid source-table counts")
        columns = identity["columns"]
        if (
            not isinstance(columns, list)
            or not columns
            or any(not isinstance(name, str) or not name for name in columns)
            or len({name.upper() for name in columns}) != len(columns)
        ):
            raise ValueError("Missing or duplicate native columns")
        upper = [name.upper() for name in columns]
        required_columns = {"IDRECEIVER", "PERIOD", "THE_GEOM", "LAEQ", "LEQ"}
        if not required_columns.issubset(upper):
            missing = ", ".join(sorted(required_columns.difference(upper)))
            raise ValueError(f"Missing required native columns: {missing}")
        if type(identity["row_count"]) is not int or identity["row_count"] < 1:
            raise ValueError("Invalid complete native row count")
        id_index = upper.index("IDRECEIVER")
        period_index = upper.index("PERIOD") if "PERIOD" in upper else None
        identities, receivers = set(), set()
        count = 0
        with path.open(encoding="utf-8", newline="") as stream:
            rows = csv.reader(stream, strict=True)
            if next(rows, None) != columns:
                raise ValueError("CSV header does not match all native columns")
            for row in rows:
                count += 1
                if len(row) != len(columns) or any(not cell.strip() for cell in row):
                    raise ValueError(
                        f"Missing field or invalid CSV shape at row {count}"
                    )
                try:
                    receiver = int(row[id_index])
                    for index, name in enumerate(upper):
                        if name in ("THE_GEOM", "GEOM", "GEOMETRY"):
                            if re.search(
                                r"(?i)(?<![a-z])(?:nan|[+-]?inf(?:inity)?)(?![a-z])",
                                row[index],
                            ):
                                raise ValueError("Nonfinite geometry")
                            coordinates = re.findall(
                                r"[-+]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][-+]?[0-9]+)?",
                                row[index],
                            )
                            if any(
                                not math.isfinite(float(value)) for value in coordinates
                            ):
                                raise ValueError("Nonfinite geometry")
                        elif name not in ("IDRECEIVER", "PERIOD"):
                            if not math.isfinite(float(row[index])):
                                raise ValueError("Nonfinite receiver level")
                except (ValueError, OverflowError) as error:
                    raise ValueError(
                        f"Nonfinite or invalid field at row {count}"
                    ) from error
                key = (
                    receiver,
                    row[period_index] if period_index is not None else None,
                )
                if key in identities:
                    raise ValueError(
                        f"Duplicate receiver/period identity at row {count}"
                    )
                identities.add(key)
                receivers.add(receiver)
        # Native propagation can legitimately omit source receivers. row_count
        # binds the complete result table, not a presumed four-period product.
        if count != identity["row_count"] or len(receivers) > counts["receivers"]:
            raise ValueError("CSV count does not match the native export contract")
        with path.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        return identity, {
            "bytes": path.stat().st_size,
            "sha256": digest,
            "row_count": count,
            "receiver_count": len(receivers),
            "columns": columns,
            "engine_version": identity["engine_version"],
        }

    left_identity, left = validate(csv_a, identity_a)
    result = {
        "case": "tutorial-barrier",
        "height_m": left_identity["height_m"],
        "full_observer_status": "VALIDATED_FULL_EXPORT",
        "left": left,
    }
    if csv_b is None:
        return result
    right_identity, right = validate(csv_b, identity_b)
    for key in (
        "case",
        "height_m",
        "source_resource_sha256",
        "configuration",
        "screen_wkt",
        "screen_id",
        "srid",
        "counts",
    ):
        if left_identity[key] != right_identity[key]:
            raise ValueError(f"Different calculation identity: {key}")
    same_version = left_identity["engine_version"] == right_identity["engine_version"]
    if (
        same_version
        and left_identity["stock_script_sha256"]
        != right_identity["stock_script_sha256"]
    ):
        raise ValueError("Same engine version has different loaded stock-script pins")
    equal = left["bytes"] == right["bytes"]
    if equal:
        with Path(csv_a).open("rb") as first, Path(csv_b).open("rb") as second:
            while True:
                a, b = first.read(65536), second.read(65536)
                if a != b:
                    equal = False
                    break
                if not a:
                    break
    result.update(
        full_observer_status="COMPARED_FULL_EXPORTS",
        right=right,
        byte_equal=equal,
        comparison_kind="same-version-repeat" if same_version else "cross-version",
    )
    return result


def aggregate_tutorial():
    """Keep tutorial cases separate from the original Clisson data files."""
    destination = DATA_DIR / "tutorial-barrier"
    rows, comparisons = [], []
    for height in (1, 3, 5):
        cases = {}
        for version in sorted(OUTPUT.iterdir()) if OUTPUT.exists() else ():
            folder = version / "tutorial-barrier" / f"H{height}"
            export = folder / "RECEIVERS_LEVEL.csv"
            if not export.exists():
                continue
            observer = compare_receiver_csv(export)
            if observer["left"]["engine_version"] != version.name:
                raise ValueError("Tutorial directory and engine identity differ")
            stats_path = folder / f"stats_{version.name}.json"
            with stats_path.open(encoding="utf-8") as stream:
                stats = json.load(stream)
            rows.append(
                {
                    **stats,
                    "version": version.name,
                    "height_m": height,
                    "case": "tutorial-barrier",
                    "full_csv_observer": observer,
                }
            )
            cases[version.name] = folder
        for first, second in combinations(cases, 2):
            observer = compare_receiver_csv(
                cases[first] / "RECEIVERS_LEVEL.csv",
                cases[second] / "RECEIVERS_LEVEL.csv",
            )
            # The existing LAEQ comparator remains the numerical comparison.
            data_a = load_receivers(first, cases[first])
            data_b = load_receivers(second, cases[second])
            numerical = (
                compare(first, data_a, second, data_b) if data_a and data_b else None
            )
            comparisons.append(
                {
                    "height_m": height,
                    "version_a": first,
                    "version_b": second,
                    "full_csv_observer": observer,
                    "laeq_comparison": numerical,
                }
            )
    destination.mkdir(parents=True, exist_ok=True)
    (destination / "results.json").write_text(json.dumps(rows, indent=2))
    (destination / "comparisons.json").write_text(json.dumps(comparisons, indent=2))


def main():
    aggregate_tutorial()
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
    parser = argparse.ArgumentParser(
        description="Compare NoiseModelling receiver outputs"
    )
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check-receivers-csv", metavar="CSV")
    mode.add_argument("--compare-receivers-csv", nargs=2, metavar=("LEFT", "RIGHT"))
    parser.add_argument("--identity-a", type=Path)
    parser.add_argument("--identity-b", type=Path)
    args = parser.parse_args()
    try:
        if args.check_receivers_csv or args.compare_receivers_csv:
            paths = args.compare_receivers_csv or [args.check_receivers_csv]
            result = compare_receiver_csv(
                *paths, identity_a=args.identity_a, identity_b=args.identity_b
            )
            print(json.dumps(result, indent=2))
            sys.exit(0 if result.get("byte_equal", True) else 1)
        main()
    except (ValueError, OSError, csv.Error, UnicodeError) as error:
        print(json.dumps({"full_observer_status": "REFUSED", "reason": str(error)}))
        sys.exit(2)
