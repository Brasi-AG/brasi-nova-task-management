#!/usr/bin/env python3
"""Prueft Failsafe-Berichte der Integrationstests als CI-Gate.

Ein gruener Maven-Lauf beweist bei diesen Tests nichts: Ohne Datenbank-Zugang ueberspringen
sich die ITs (RefSchemaItEnabled), und eine falsche Testauswahl faehrt still keinen einzigen
Test. Das Gate scheitert deshalb, wenn

- keine Berichte vorliegen oder insgesamt 0 Tests liefen,
- ein Test fehlgeschlagen ist (failures/errors) oder uebersprungen wurde (skipped),
- fuer eine erwartete IT-Klasse kein Bericht existiert (nicht ausgefuehrt),
- eine Testklasse laut Quelltext keinen eigenen Bericht mit mehr als 0 Tests hat: Aus den
  Quellen (--expected-from-source bzw. --source-dir) werden die Klassen mit direkten
  Testmethoden (@Test, @ParameterizedTest, ...) ermittelt, das sind die aeussere Klasse und
  jede innere Klasse (@Nested), die selbst Tests enthaelt. Jede davon braucht einen eigenen
  Bericht (A bzw. A$B) mit Tests. So faellt auch ein fehlender Bericht einer inneren Klasse
  auf, obwohl die aeussere Klasse Tests meldet. Ohne erkannte Testmethoden gilt als
  Rueckfall: Summe der Tests aller Berichte der Klasse groesser 0,
- ein Bericht zu einer nicht erwarteten IT-Klasse existiert (Auswahl zu breit).

Aufruf:
  check-integration-results.py --reports <verzeichnis> --expected A,B,C --source-dir <test-quellen>
  check-integration-results.py --reports <verzeichnis> --expected-from-source <test-quellen>

Berichte werden rekursiv unter --reports gesucht (TEST-*.xml), damit der Aufruf auch ueber die
heruntergeladenen Artefakte mehrerer Shards laeuft. Innere Klassen (A$B) zaehlen fuer
Vollstaendigkeit und Laufzeit zur aeusseren Klasse. Bei gesetzter Umgebungsvariable
GITHUB_STEP_SUMMARY wird eine Zusammenfassung als Markdown angehaengt, inklusive der
gemessenen Laufzeiten im Format der Gewichtsdatei scripts/ci/integration-test-weights.txt.
"""

import argparse
import os
import re
import sys
from pathlib import Path
from xml.etree import ElementTree

# Annotationen, die eine Testmethode kennzeichnen (JUnit 5); @TestPropertySource u. a. zaehlen nicht.
_TEST_ANNOTATION = (
    r"@(?:[A-Za-z_]\w*\.)*(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b"
)
_TOKEN = re.compile(
    r"(?<![.\w])(?:class|interface|enum|record)\s+(?P<type>[A-Za-z_]\w*)"
    rf"|(?P<test>{_TEST_ANNOTATION})"
    r"|(?P<open>\{)|(?P<close>\})"
)


def top_level_name(class_name: str) -> str:
    """Einfacher Name der aeusseren Klasse (ohne Paket und ohne innere Klasse)."""
    return class_name.rsplit(".", 1)[-1].split("$", 1)[0]


def expected_from_source(source_dir: Path) -> set[str]:
    """Alle IT-Klassen der Test-Quellen (Dateien *IT.java, wie die Failsafe-Include-Regel)."""
    return {path.stem for path in source_dir.rglob("*IT.java")}


def strip_noise(source: str) -> str:
    """Ersetzt Kommentare, Zeichenketten, Textbloecke und Zeichenliterale durch Leerzeichen."""
    out: list[str] = []
    i, n = 0, len(source)
    while i < n:
        two = source[i:i + 2]
        if two == "//":
            end = source.find("\n", i)
            i = n if end < 0 else end
            out.append(" ")
        elif two == "/*":
            end = source.find("*/", i + 2)
            i = n if end < 0 else end + 2
            out.append(" ")
        elif source.startswith('"""', i):
            j = i + 3
            while j < n and not source.startswith('"""', j):
                j += 2 if source[j] == "\\" else 1
            i = min(n, j + 3)
            out.append(" ")
        elif source[i] in "\"'":
            quote = source[i]
            j = i + 1
            while j < n and source[j] != quote and source[j] != "\n":
                j += 2 if source[j] == "\\" else 1
            i = min(n, j + 1)
            out.append(" ")
        else:
            out.append(source[i])
            i += 1
    return "".join(out)


def test_method_counts(source: str) -> dict[str, int]:
    """Direkte Testmethoden je Klasse: Binaername (Aussen$Innen, ohne Paket) -> Anzahl.

    Zaehlt nur Annotationen im Klassenrumpf selbst (nicht in Methoden oder anonymen Klassen).
    """
    counts: dict[str, int] = {}
    stack: list[tuple[str, int]] = []  # (Binaername, Tiefe des Klassenrumpfs)
    pending: str | None = None
    depth = 0
    for match in _TOKEN.finditer(strip_noise(source)):
        if match.group("type"):
            pending = match.group("type")
        elif match.group("open"):
            depth += 1
            if pending is not None:
                name = f"{stack[-1][0]}${pending}" if stack else pending
                stack.append((name, depth))
                pending = None
        elif match.group("close"):
            if stack and stack[-1][1] == depth:
                stack.pop()
            depth -= 1
        elif match.group("test") and stack and stack[-1][1] == depth:
            counts[stack[-1][0]] = counts.get(stack[-1][0], 0) + 1
    return counts


def required_reports(source_dir: Path, expected: set[str]) -> tuple[set[str], set[str], list[str]]:
    """Ermittelt aus den Quellen, welche Berichte mit Tests erwartet werden.

    Rueckgabe: (Berichtsklassen mit direkten Testmethoden, aeussere Klassen ohne erkannte
    Testmethoden (Rueckfall: Summe ueber alle Berichte der Klasse), Probleme).
    """
    units: set[str] = set()
    fallback: set[str] = set()
    problems: list[str] = []
    for name in sorted(expected):
        files = sorted(source_dir.rglob(f"{name}.java"))
        if not files:
            problems.append(f"Quelle der erwarteten IT-Klasse {name} nicht gefunden ({source_dir}).")
            continue
        counts = test_method_counts(files[0].read_text(encoding="utf-8"))
        found = {
            unit for unit, count in counts.items()
            if count > 0 and (unit == name or unit.startswith(name + "$"))
        }
        if found:
            units |= found
        else:
            fallback.add(name)
    return units, fallback, problems


def read_reports(reports_dir: Path) -> list[dict]:
    """Liest alle TEST-*.xml unter dem Verzeichnis."""
    suites = []
    for path in sorted(reports_dir.rglob("TEST-*.xml")):
        root = ElementTree.parse(path).getroot()
        skipped_messages = [
            (skipped.get("message") or "").strip()
            for skipped in root.iter("skipped")
        ]
        # Testfaelle je deklarierender Klasse (classname). Failsafe schreibt die Testfaelle einer
        # aeusseren Klasse mit @Nested-Klasse in den Bericht der inneren Klasse (Bericht der
        # aeusseren Klasse: tests="0"); die Zuordnung zur Klasse steht nur im classname.
        cases: dict[str, int] = {}
        for case in root.iter("testcase"):
            unit = (case.get("classname") or "").rsplit(".", 1)[-1]
            if unit:
                cases[unit] = cases.get(unit, 0) + 1
        suites.append(
            {
                "file": str(path),
                "cases": cases,
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
    parser.add_argument(
        "--source-dir", type=Path,
        help="Test-Quellverzeichnis fuer die Pruefung innerer Testklassen (bei --expected)",
    )
    args = parser.parse_args()
    source_dir = args.expected_from_source or args.source_dir

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

    # Testzahl je deklarierender Klasse laut Quelltext: Die aeussere Klasse und jede innere Klasse
    # mit eigenen Testmethoden muss mit mehr als 0 Testfaellen berichtet sein. Ein Bericht allein
    # (z. B. der aeusseren Klasse FlywayMigrationIT mit tests="0") oder die gemeinsame Summe der
    # Klasse beweist nicht, dass die inneren Tests liefen. Gezaehlt wird je Testfall nach classname,
    # weil Failsafe die Testfaelle einer aeusseren Klasse in den Bericht der inneren Klasse schreibt.
    tests_by_unit: dict[str, int] = {}
    tests_by_top: dict[str, int] = {}
    for suite in suites:
        if suite["cases"]:
            for unit, count in suite["cases"].items():
                tests_by_unit[unit] = tests_by_unit.get(unit, 0) + count
        else:
            unit = suite["class"].rsplit(".", 1)[-1]
            tests_by_unit[unit] = tests_by_unit.get(unit, 0) + suite["tests"]
        tests_by_top[suite["top"]] = tests_by_top.get(suite["top"], 0) + suite["tests"]
    required_units: set[str] = set()
    fallback_tops: set[str] = set(expected)
    if source_dir is not None:
        required_units, fallback_tops, source_problems = required_reports(source_dir, expected)
        problems.extend(source_problems)
    without_tests = sorted(
        {unit for unit in required_units if tests_by_unit.get(unit, 0) == 0}
        | {top for top in fallback_tops & reported if tests_by_top.get(top, 0) == 0}
    )
    if without_tests:
        problems.append(
            "Kein Bericht oder 0 Tests fuer Testklasse(n): "
            f"{', '.join(without_tests)} (jede Klasse mit eigenen Testmethoden, auch eine innere, "
            "braucht berichtete Testfaelle; der Bericht der aeusseren Klasse allein genuegt nicht)."
        )
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
