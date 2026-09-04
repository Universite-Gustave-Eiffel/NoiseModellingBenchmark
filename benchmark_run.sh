#!/usr/bin/env bash
#set -euo pipefail

declare -A NM_VERSIONS
NM_VERSIONS["v4.0.0"]="https://github.com/Universite-Gustave-Eiffel/NoiseModelling/releases/download/v4.0.0/NoiseModelling_4.0.0_without_gui.zip"
NM_VERSIONS["v4.0.1"]="https://github.com/Universite-Gustave-Eiffel/NoiseModelling/releases/download/v4.0.1/NoiseModelling_4.0.1_without_gui.zip"
NM_VERSIONS["v4.0.2"]="https://github.com/Universite-Gustave-Eiffel/NoiseModelling/releases/download/v4.0.2/NoiseModelling_without_gui.zip"
NM_VERSIONS["v4.0.4"]="https://github.com/Universite-Gustave-Eiffel/NoiseModelling/releases/download/v4.0.4/NoiseModelling_without_gui.zip"
NM_VERSIONS["v4.0.5"]="https://github.com/Universite-Gustave-Eiffel/NoiseModelling/releases/download/v4.0.5/NoiseModelling_without_gui.zip"
NM_VERSIONS["v5.0.0"]="https://github.com/Universite-Gustave-Eiffel/NoiseModelling/releases/download/v5.0.0/NoiseModelling_without_gui.zip"
NM_VERSIONS["v5.0.1"]="https://github.com/Universite-Gustave-Eiffel/NoiseModelling/releases/download/v5.0.1/NoiseModelling_without_gui-5.0.1.zip"
NM_VERSIONS["v6.0.0"]="https://github.com/Universite-Gustave-Eiffel/NoiseModelling/releases/download/v6.0.0/NoiseModelling_6.0.0.zip"

GROOVY_SCRIPT="nm_version/src/main/groovy/runscriptV5.0.groovy"
GROOVY_SCRIPT_v6="nm_version/src/main/groovy/runscriptV6.0.groovy"

INPUT_DIR="input"
OUTPUT_DIR="output"
WEBSITE_DIR="website"
DATA_DIR="$WEBSITE_DIR/data"

mkdir -p "$INPUT_DIR" "$OUTPUT_DIR" "$WEBSITE_DIR" "$DATA_DIR"

download_clisson() {
    local clisson_dir="$INPUT_DIR/clisson"
    if [ -d "$clisson_dir/clisson" ]; then
        return 0
    fi
    cp -r "clisson/" "$clisson_dir/"
}

download_nm_version() {
    local version="$1"
    local url="$2"
    local zip_name
    zip_name="$INPUT_DIR/NoiseModelling_without_gui_${version}.zip"
    local extract_dir="$INPUT_DIR/NoiseModelling_without_gui_${version}"

    if [ -d "$extract_dir" ]; then
        return 0
    fi

    curl -fL "$url" -o "$zip_name" --progress-bar || { return 1; }

    local tmp_dir
    tmp_dir=$(mktemp -d)

    unzip -q "$zip_name" -d "$tmp_dir" || {
        rm -rf "$tmp_dir"
        return 1
    }
    local entries
    entries=($(find "$tmp_dir" -mindepth 1 -maxdepth 1))

    if [ "${#entries[@]}" -eq 1 ] && [ -d "${entries[0]}" ]; then
        mv "${entries[0]}" "$extract_dir"
    else
        mkdir -p "$extract_dir"
        shopt -s dotglob
        mv "$tmp_dir"/* "$extract_dir"/
    fi

    rm -rf "$tmp_dir"
}

find_wps_binary() {
    local nm_dir="$1"
    local bin
    bin=$(find "$nm_dir" -name "wps_scripts" -type f 2>/dev/null | head -n 1)
    if [ -z "$bin" ]; then
        return 1
    fi
    chmod +x "$bin"
    echo "$bin"
}

find_wps_binary_v6() {
    local nm_dir="$1"
    local bin
    bin=$(find "$nm_dir" -name "ScriptRunner" -type f 2>/dev/null | head -n 1)
    if [ -z "$bin" ]; then
        return 1
    fi
    chmod +x "$bin"
    echo "$bin"
}

run_simulation() {
    local version="$1"
    local nm_dir="$INPUT_DIR/NoiseModelling_without_gui_${version}"
    local out_dir="$OUTPUT_DIR/$version"
    local stats_file="$out_dir/stats_${version}.json"

    mkdir -p "$out_dir"

    local wps_bin
    if [[ "$version" == v6* ]]; then
        wps_bin=$(find_wps_binary_v6 "$nm_dir") || return 1
    else
        wps_bin=$(find_wps_binary "$nm_dir") || return 1
    fi

    local workspace="$out_dir/workspace"
    mkdir -p "$workspace"

    if [ "$version" = "v4.0.0" ] || [ "$version" = "v4.0.1" ]; then
        "$wps_bin" \
            -w"$workspace" \
            -s"$GROOVY_SCRIPT" \
            NM_version="$version" \
            > "$out_dir/simulation.log" 2>&1
    elif [[ "$version" == v6* ]]; then
        "$wps_bin" \
            -w "$workspace" \
            -s "$GROOVY_SCRIPT_v6" \
            -NM_version "$version" \
            > "$out_dir/simulation.log" 2>&1
    else
        "$wps_bin" \
            -w "$workspace" \
            -s "$GROOVY_SCRIPT" \
            -NM_version "$version" \
            > "$out_dir/simulation.log" 2>&1
    fi
    local exit_code=$?

    if [ $exit_code -ne 0 ]; then
        return 1
    fi

    if [ ! -f "$stats_file" ]; then
        local groovy_out="output/${version}/stats_${version}.json"
        if [ -f "$groovy_out" ]; then
            cp "$groovy_out" "$stats_file"
        fi
    fi
}

aggregate_results() {
    local agg_file="$DATA_DIR/results.json"

    echo "[" > "$agg_file"
    local first=true

    for version_dir in "$OUTPUT_DIR"/*/; do
        [ -d "$version_dir" ] || continue

        local version
        version="$(basename "$version_dir")"

        local stats="$version_dir/stats_${version}.json"

        if [ ! -f "$stats" ]; then
            continue
        fi

        if [ "$first" = false ]; then
            echo "," >> "$agg_file"
        fi

        python3 - "$version" "$stats" >> "$agg_file" <<'EOF'
import json, sys

version = sys.argv[1]

with open(sys.argv[2]) as f:
    data = json.load(f)

data["version"] = version

print(json.dumps(data, indent=2))
EOF

        first=false
    done

    echo "]" >> "$agg_file"
}


copy_geojson() {
    local CLISSON_DIR="$INPUT_DIR/clisson/clisson"

    declare -A COMMON_LAYERS=(
        ["BUILDINGS.geojson"]="BUILDINGS.geojson"
        ["RECEIVERS.geojson"]="RECEIVERS.geojson"
        ["DEM.geojson"]="DEM.geojson"
        ["ROADS.geojson"]="ROADS.geojson"
        ["GROUNDS.geojson"]="GROUNDS.geojson"
    )

    for version_dir in "$OUTPUT_DIR"/*/; do
        [ -d "$version_dir" ] || continue

        local version
        version="$(basename "$version_dir")"

        mkdir -p "$DATA_DIR/$version"

        local receivgeojson="$version_dir/RECEIVERS_LEVEL.geojson"

        if [ -f "$receivgeojson" ]; then
            cp "$receivgeojson" \
               "$DATA_DIR/$version/RECEIVERS_LEVEL.geojson"
        fi

        local iso_src="$version_dir/ISO_CONTOUR.geojson"

        if [ -f "$iso_src" ]; then
            cp "$iso_src" \
               "$DATA_DIR/$version/ISO_CONTOUR.geojson"
        fi

        for dest_name in "${!COMMON_LAYERS[@]}"; do
            local src_name="${COMMON_LAYERS[$dest_name]}"
            local src="$CLISSON_DIR/$src_name"

            if [ -f "$src" ]; then
                cp "$src" "$DATA_DIR/$version/$dest_name"
            fi
        done
    done
}


run_one_version() {
    local version="$1"
    local nm_dir="$INPUT_DIR/NoiseModelling_without_gui_${version}"
    
    if [ -d "$nm_dir" ]; then
        echo " NM déjà présent : $nm_dir — skip download.:"
        ls $nm_dir

    elif [ -n "${NM_VERSIONS[$version]+x}" ]; then
        download_nm_version "$version" "${NM_VERSIONS[$version]}" || {
            echo "Echec download pour $version"
            exit 1
        }

    else
        echo "Version inconnue et aucun dossier ni URL disponible : $version"
        echo "Dossier cherché : $nm_dir"
        exit 1
    fi

    download_clisson

    echo "Lancement simulation $version..."
    run_simulation "$version" || {
        echo "Echec simulation pour $version"
        exit 1
    }
}


run_aggregate_only() {
    aggregate_results
    copy_geojson
    python3 generate_site.py
}


run_all_sequential() {
    download_clisson
    local failed_versions=()
    for version in "${!NM_VERSIONS[@]}"; do
        download_nm_version "$version" "${NM_VERSIONS[$version]}" || {
            failed_versions+=("$version")
            continue
        }
        echo " version $version"
        run_simulation "$version" || {
            failed_versions+=("$version")
            continue
        }
    done
    run_aggregate_only
}

main() {
    case "${1:-}" in
        --version)
            run_one_version "$2"
            ;;
        --aggregate-only)
            run_aggregate_only
            ;;
        *)
            run_all_sequential
            ;;
    esac
}

main "$@"
