#!/usr/bin/env python3
"""Prueft Failsafe-Berichte der Integrationstests als CI-Gate.

Ein gruener Maven-Lauf beweist bei diesen Tests nichts: Ohne Datenbank-Zugang ueberspringen
sich die ITs (RefSchemaItEnabled), und eine falsche Testauswahl faehrt still keinen einzigen
Test. Das Gate scheitert deshalb, wenn

- keine Berichte vorliegen oder insgesamt 0 Tests liefen,
- ein Test fehlgeschlagen ist (failures/errors) oder uebersprungen wurde (skipped),
- fuer eine erwartete IT-Klasse kein Bericht existiert (nicht ausgefuehrt),
- ein Bericht zu einer nicht erwarteten IT-Klasse existiert (Auswahl zu breit).

Aufruf:
  check-integration-results.py --reports <verzeichnis> --expected A,B,C
  check-integration-results.py --reports <verzeichnis> --expected-from-source <test-quellen>

Berichte werden rekursiv unter --reports gesucht (TEST-*.xml), damit der Aufruf auch ueber die
heruntergeladenen Artefakte mehrerer Shards laeuft. Innere Klassen (A$B) zaehlen zur
aeusseren Klasse. Bei gesetzter Umgebungsvariable GITHUB_STEP_SUMMARY wird eine
Zusammenfassung als Markdown angehaengt, inklusive der gemessenen Laufzeiten im Format der
Gewichtsdatei scripts/ci/integration-test-weights.txt.
"""

import argparse
import os
import sys
from pathlib import Path
from xml.etree import ElementTree


def top_level_name(class_name: str) -> str:
    """Einfacher Name der aeusseren Klasse (ohne Paket und ohne innere Klasse)."""
    return class_name.rsplit(".", 1)[-1].split("$", 1)[0]


def expected_from_source(source_dir: Path) -> set[str]:
    """Alle IT-Klassen der Test-Quellen (Dateien *IT.java, wie die Failsafe-Include-Regel)."""
    return {path.stem for path in source_dir.rglob("*IT.java")}


def read_reports(reports_dir: Path) -> list[dict]:
    """Liest alle TEST-*.xml unter dem Verzeichnis."""
    suites = []
    for path in sorted(reports_dir.rglob("TEST-*.xml")):
        root = ElementTree.parse(path).getroot()
        skipped_messages = [
            (skipped.get("message") or "").strip()
            for skipped in root.iter("skipped")
        ]
        suites.append(
            {
                "file": str(path),
                "class": root.get("name", path.stem),
                "top": top_level_name(root.get("name", path.stem)),
                "tests": int(root.get("tests", "0")),
                "failures": int(root.get("failures", "0")),
                "errors": int(root.get("errors", "0")),
                "skipped": int(root.get("skipped", "0")),
                "time": float(root.get("time", "0").replace(",", "")),
                "skipped_messages": [m for m in skipped_messages if m],
            }
        )
    return suites


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    parser.add_argument("--reports", required=True, type=Path)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--expected", help="kommagetrennte erwartete IT-Klassen")
    group.add_argument("--expected-from-source", type=Path, help="Test-Quellverzeichnis")
    args = parser.parse_args()

    if args.expected is not None:
        expected = {name.strip() for name in args.expected.split(",") if name.strip()}
    else:
        expected = expected_from_source(args.expected_from_source)
    if not expected:
        print("::error::Keine erwarteten IT-Klassen ermittelt.")
        return 1
    if not args.reports.is_dir():
        print(f"::error::Berichtsverzeichnis fehlt: {args.reports}")
        return 1

    suites = read_reports(args.reports)
    problems: list[str] = []

    if not suites:
        problems.append(f"Keine Failsafe-Berichte (TEST-*.xml) unter {args.reports}.")

    totals = {key: sum(suite[key] for suite in suites)
              for key in ("tests", "failures", "errors", "skipped")}
    if suites and totals["tests"] == 0:
        problems.append("Es wurde kein einziger Test ausgefuehrt (0 Tests).")
    if totals["failures"] or totals["errors"]:
        failing = sorted({s["top"] for s in suites if s["failures"] or s["errors"]})
        problems.append(
            f"{totals['failures']} Failure(s), {totals['errors']} Error(s) in: {', '.join(failing)}"
        )
    if totals["skipped"]:
        skipped = sorted({s["top"] for s in suites if s["skipped"]})
        messages = sorted({m for s in suites for m in s["skipped_messages"]})
        hint = f" (Grund: {'; '.join(messages[:3])})" if messages else ""
        problems.append(
            f"{totals['skipped']} uebersprungene Test(s) in: {', '.join(skipped)}{hint}. "
            "Ein Skip bedeutet meist fehlenden Datenbank-Zugang (BRASI_INT_DB_*)."
        )

    reported = {suite["top"] for suite in suites}
    missing = sorted(expected - reported)
    unexpected = sorted(reported - expected)
    if missing:
        problems.append(f"Kein Bericht fuer erwartete IT-Klasse(n): {', '.join(missing)}")
    if unexpected:
        problems.append(f"Bericht(e) zu nicht erwarteten IT-Klasse(n): {', '.join(unexpected)}")

    # Laufzeit je aeusserer Klasse (Summe der inneren Klassen).
    seconds: dict[str, float] = {}
    for suite in suites:
        seconds[suite["top"]] = seconds.get(suite["top"], 0.0) + suite["time"]
    wall = sum(seconds.values())

    print(
        f"IT-Gate: {len(reported)} von {len(expected)} erwarteten Klassen berichtet, "
        f"{totals['tests']} Tests, {totals['failures']} Failures, {totals['errors']} Errors, "
        f"{totals['skipped']} Skips, Summe {wall:.0f}s"
    )
    for name, value in sorted(seconds.items(), key=lambda item: (-item[1], item[0]))[:5]:
        print(f"  {value:7.1f}s  {name}")

    write_summary(totals, len(reported), len(expected), seconds, wall, problems)

    if problems:
        for problem in problems:
            print(f"::error::{problem}")
        return 1
    return 0


def write_summary(totals, reported, expected, seconds, wall, problems) -> None:
    """Haengt eine Markdown-Zusammenfassung an GITHUB_STEP_SUMMARY an (falls gesetzt)."""
    target = os.environ.get("GITHUB_STEP_SUMMARY")
    if not target:
        return
    status = "FEHLER" if problems else "gruen"
    lines = [
        f"### Integrationstests: {status}",
        "",
        f"- Klassen: {reported} von {expected} erwarteten berichtet",
        f"- Tests: {totals['tests']} (Failures {totals['failures']}, Errors {totals['errors']}, "
        f"Skips {totals['skipped']})",
        f"- Summe der Klassenlaufzeiten: {wall:.0f}s",
    ]
    for problem in problems:
        lines.append(f"- **{problem}**")
    lines += [
        "",
        "<details><summary>Gemessene Laufzeiten (Format scripts/ci/integration-test-weights.txt)"
        "</summary>",
        "",
        "```",
    ]
    for name, value in sorted(seconds.items(), key=lambda item: (-item[1], item[0])):
        lines.append(f"{name} {max(1, round(value))}")
    lines += ["```", "", "</details>", ""]
    with open(target, "a", encoding="utf-8") as handle:
        handle.write("\n".join(lines))


if __name__ == "__main__":
    sys.exit(main())
