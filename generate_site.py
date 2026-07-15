#!/usr/bin/env python3
"""
generate_site.py
Copie index.html dans website/ et vérifie la structure attendue.
Appelé automatiquement par run.sh après les simulations.
"""
import os, sys, json, shutil
from pathlib import Path
from datetime import datetime

ROOT      = Path(__file__).parent
WEBSITE   = ROOT / "website"
DATA_DIR  = WEBSITE / "data"
RESULTS   = DATA_DIR / "results.json"
TEMPLATE  = ROOT / "website" / "index.html"

def check_results():
    if not RESULTS.exists():
        print(f"[WARN] {RESULTS} introuvable — le site sera vide.")
        return []
    with open(RESULTS) as f:
        data = json.load(f)
    print(f"[OK]   results.json chargé : {len(data)} version(s)")
    for row in data:
        v = row.get("version", "?")
        geojson = DATA_DIR / v / "KEPLERGL.geojson"
        if geojson.exists():
            size_kb = geojson.stat().st_size // 1024
            print(f"[OK]   GeoJSON {v} : {size_kb} KB")
        else:
            print(f"[WARN] GeoJSON manquant pour {v} — la carte sera vide pour cette version")
    return data

def patch_build_date(html_path):
    """Injecte la date de génération dans un commentaire HTML."""
    content = html_path.read_text()
    tag = f"<!-- built: {datetime.utcnow().strftime('%Y-%m-%d %H:%M')} UTC -->"
    if "<!-- built:" not in content:
        content = content.replace("</head>", f"{tag}\n</head>", 1)
        html_path.write_text(content)

def main():
    print("=== NoiseModelling Benchmark — Site Generator ===")

    WEBSITE.mkdir(exist_ok=True)
    DATA_DIR.mkdir(exist_ok=True)

    # Vérifier index.html
    if not TEMPLATE.exists():
        print(f"[ERR]  index.html introuvable dans {WEBSITE}")
        sys.exit(1)

    patch_build_date(TEMPLATE)
    print(f"[OK]   index.html prêt dans {WEBSITE}")

    data = check_results()

    print("\n── Structure du site ──────────────────────────")
    for path in sorted(WEBSITE.rglob("*")):
        if path.is_file():
            rel = path.relative_to(WEBSITE)
            size = path.stat().st_size
            print(f"  {str(rel):<50} {size:>10,} bytes")

    print(f"\n[OK]   Site prêt dans : {WEBSITE.resolve()}")
    print(f"       Ouvre website/index.html dans ton navigateur pour prévisualiser.")
    print(f"       Pour GitHub Pages : git subtree push --prefix website origin gh-pages")

if __name__ == "__main__":
    main()
