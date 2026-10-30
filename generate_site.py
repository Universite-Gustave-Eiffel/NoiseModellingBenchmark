#!/usr/bin/env python3

import json
import subprocess
import sys
from datetime import datetime
from html import escape
from pathlib import Path

ROOT      = Path(__file__).parent
WEBSITE   = ROOT / "website"
DATA_DIR  = WEBSITE / "data"
RESULTS   = DATA_DIR / "results.json"
TEMPLATE  = ROOT / "website" / "index.html"

def patch_build_date(html_path):
    content = html_path.read_text()
    tag = f"<!-- built: {datetime.utcnow().strftime('%Y-%m-%d %H:%M')} UTC -->"
    if "<!-- built:" not in content:
        content = content.replace("</head>", f"{tag}\n</head>", 1)
        html_path.write_text(content)

def tutorial_page():
    """Render the additional family without mixing it into the Clisson charts."""
    folder = DATA_DIR / "tutorial-barrier"
    results = json.loads((folder / "results.json").read_text())
    comparisons = json.loads((folder / "comparisons.json").read_text())
    sections = []
    for height in (1, 3, 5):
        body = []
        for row in sorted(results, key=lambda item: item["version"]):
            if row["height_m"] != height:
                continue
            observer = row["full_csv_observer"]["left"]
            values = (
                row["version"],
                row.get("receivers", ""),
                observer["row_count"],
                row.get("calculation_seconds", ""),
                row.get("script_seconds", ""),
                observer["sha256"],
            )
            body.append(
                "<tr>"
                + "".join("<td>" + escape(str(value)) + "</td>" for value in values)
                + "</tr>"
            )
        table = (
            (
                "<table><thead><tr><th>Engine</th><th>Source receivers</th><th>Exported rows</th>"
                "<th>Calculation (s)</th><th>Script (s)</th><th>Complete CSV SHA-256</th>"
                "</tr></thead><tbody>" + "".join(body) + "</tbody></table>"
            )
            if body
            else "<p>Not run.</p>"
        )
        sections.append(f"<section><h2>Screen height {height} m</h2>{table}</section>")
    comparison_rows = []
    for item in comparisons:
        observer = item["full_csv_observer"]
        values = (
            item["height_m"],
            item["version_a"],
            item["version_b"],
            "identical bytes" if observer["byte_equal"] else "different bytes",
        )
        comparison_rows.append(
            "<tr>"
            + "".join("<td>" + escape(str(value)) + "</td>" for value in values)
            + "</tr>"
        )
    if comparison_rows:
        sections.append(
            "<section><h2>Complete exports across versions</h2><table><thead><tr>"
            "<th>Height (m)</th><th>Engine A</th><th>Engine B</th><th>CSV comparison</th>"
            "</tr></thead><tbody>"
            + "".join(comparison_rows)
            + "</tbody></table></section>"
        )
    page = (
        """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>NoiseModelling — tutorial barrier family</title>
<style>body{font:16px sans-serif;max-width:1100px;margin:2rem auto;padding:0 1rem;color:#222}table{border-collapse:collapse;width:100%;margin-bottom:2rem}th,td{border:1px solid #ccc;padding:.5rem;text-align:left}td:last-child{overflow-wrap:anywhere}section{margin-top:2rem}</style>
</head><body><p><a href="index.html">Clisson benchmark</a></p><h1>Tutorial barrier family</h1>
<p>The NoiseModelling 5.0.1 tutorial scene, EPSG:2154, with the same screen footprint at 1, 3 and 5 metres.
Each height is a separate case. Compare like heights and configurations across engine versions.</p>
<p>Script time includes preparation, native calculation and export; it excludes JVM startup and shutdown.
Complete CSV validation checks every native row and field; cross-version byte differences can be expected.</p>
"""
        + "".join(sections)
        + '<p><a href="data/tutorial-barrier/results.json">Results JSON</a> · <a href="data/tutorial-barrier/comparisons.json">Comparisons JSON</a></p></body></html>\n'
    )
    (WEBSITE / "tutorial-barrier.html").write_text(page)


def main():

    WEBSITE.mkdir(exist_ok=True)
    DATA_DIR.mkdir(exist_ok=True)

    if not TEMPLATE.exists():
        sys.exit(1)

    patch_build_date(TEMPLATE)
    subprocess.run([sys.executable, str(ROOT / "compare_versions.py")], check=True)
    tutorial_page()

if __name__ == "__main__":
    main()

