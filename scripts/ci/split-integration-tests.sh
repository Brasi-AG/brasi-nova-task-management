#!/usr/bin/env bash
#
# Verteilt die Integrationstests (*IT) deterministisch auf CI-Shards.
#
# Aufruf (im Repository-Wurzelverzeichnis):
#   scripts/ci/split-integration-tests.sh <shard> <anzahl-shards>          # Klassenliste, kommagetrennt
#   scripts/ci/split-integration-tests.sh <shard> <anzahl-shards> --plan   # Verteilung aller Shards (Tabelle)
#
# Verfahren: Die IT-Klassen werden aus den Test-Quellen ermittelt (Dateien *IT.java, wie die
# Failsafe-Include-Regel), nach Gewicht (Sekunden, scripts/ci/integration-test-weights.txt)
# absteigend und bei Gleichstand nach Name sortiert und der Reihe nach dem Shard mit der
# geringsten Summe zugeteilt (Longest-Processing-Time). Gleiche Eingabe liefert immer dieselbe
# Verteilung. Klassen ohne Eintrag in der Gewichtsdatei erhalten das Standardgewicht.
#
# Umgebungsvariablen (nur fuer Tests und Abweichungen):
#   IT_SOURCE_DIR      Test-Quellverzeichnis (Standard: brasi-nova-task-management-rt/src/test/java)
#   IT_WEIGHTS_FILE    Gewichtsdatei (Standard: scripts/ci/integration-test-weights.txt)
#   IT_DEFAULT_WEIGHT  Gewicht unbekannter Klassen in Sekunden (Standard: 35)

set -euo pipefail
export LC_ALL=C

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source_dir="${IT_SOURCE_DIR:-brasi-nova-task-management-rt/src/test/java}"
weights_file="${IT_WEIGHTS_FILE:-$script_dir/integration-test-weights.txt}"
default_weight="${IT_DEFAULT_WEIGHT:-35}"

usage() {
  echo "Aufruf: $0 <shard> <anzahl-shards> [--plan]" >&2
  exit 2
}

if (( $# < 2 || $# > 3 )); then
  usage
fi
shard="$1"
total="$2"
mode="${3:-list}"
if [[ "$mode" != "list" && "$mode" != "--plan" ]]; then
  usage
fi
if ! [[ "$shard" =~ ^[0-9]+$ && "$total" =~ ^[0-9]+$ ]] || (( total < 1 || shard < 1 || shard > total )); then
  echo "Ungueltiger Shard '$shard' von '$total' (erwartet: 1 <= shard <= anzahl)." >&2
  exit 2
fi
if [[ ! -d "$source_dir" ]]; then
  echo "Test-Quellverzeichnis nicht gefunden: $source_dir" >&2
  exit 2
fi

mapfile -t classes < <(find "$source_dir" -type f -name '*IT.java' | sed 's#.*/##; s#\.java$##' | sort)
if (( ${#classes[@]} == 0 )); then
  echo "Keine Integrationstests (*IT.java) unter $source_dir gefunden." >&2
  exit 3
fi

duplicates="$(printf '%s\n' "${classes[@]}" | uniq -d)"
if [[ -n "$duplicates" ]]; then
  echo "Doppelte IT-Klassennamen (die Auswahl ueber den einfachen Namen wird mehrdeutig):" >&2
  echo "$duplicates" >&2
  exit 3
fi

# Gewichte einlesen (Kommentare und Leerzeilen ueberspringen).
declare -A weight_of=()
if [[ -f "$weights_file" ]]; then
  while read -r name seconds _; do
    # Windows-Zeilenende (CRLF) tolerieren.
    name="${name%$'\r'}"
    seconds="${seconds%$'\r'}"
    if [[ -z "${name:-}" || "$name" == \#* ]]; then
      continue
    fi
    if ! [[ "${seconds:-}" =~ ^[0-9]+$ ]]; then
      echo "Ungueltiges Gewicht fuer '$name' in $weights_file: '${seconds:-}'" >&2
      exit 3
    fi
    weight_of["$name"]="$seconds"
  done < "$weights_file"
else
  echo "Hinweis: keine Gewichtsdatei ($weights_file), alle Klassen mit Standardgewicht." >&2
fi

unknown=0
weighted=""
for name in "${classes[@]}"; do
  if [[ -n "${weight_of[$name]:-}" ]]; then
    weighted+="${weight_of[$name]} $name"$'\n'
  else
    weighted+="$default_weight $name"$'\n'
    unknown=$((unknown + 1))
  fi
done
if (( unknown > 0 )); then
  echo "Hinweis: $unknown Klasse(n) ohne Gewicht, Standardgewicht ${default_weight}s." >&2
fi
for name in "${!weight_of[@]}"; do
  found=0
  for known in "${classes[@]}"; do
    if [[ "$known" == "$name" ]]; then
      found=1
      break
    fi
  done
  if (( found == 0 )); then
    echo "Hinweis: Gewicht fuer nicht (mehr) vorhandene Klasse '$name' wird ignoriert." >&2
  fi
done

# Longest-Processing-Time: schwerste Klasse zuerst auf den bisher leichtesten Shard.
assignment="$(
  printf '%s' "$weighted" | sort -k1,1nr -k2,2 | awk -v n="$total" '
    BEGIN { for (i = 1; i <= n; i++) load[i] = 0 }
    {
      best = 1
      for (i = 2; i <= n; i++) if (load[i] < load[best]) best = i
      load[best] += $1
      print best, $1, $2
    }'
)"

if [[ "$mode" == "--plan" ]]; then
  printf '%s\n' "$assignment" | awk -v n="$total" '
    { load[$1] += $2; count[$1]++; names[$1] = names[$1] (names[$1] == "" ? "" : ", ") $3 }
    END {
      printf "%-6s %-8s %-8s %s\n", "Shard", "Klassen", "Sekunden", "Klassen"
      for (i = 1; i <= n; i++) printf "%-6d %-8d %-8d %s\n", i, count[i], load[i], names[i]
    }'
  exit 0
fi

selected="$(printf '%s\n' "$assignment" | awk -v s="$shard" '$1 == s { print $3 }' | sort | paste -sd, -)"
if [[ -z "$selected" ]]; then
  echo "Shard $shard von $total hat keine Integrationstests (mehr Shards als Klassen?)." >&2
  exit 3
fi
printf '%s\n' "$selected"
