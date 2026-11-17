#!/usr/bin/env python3
"""Tests fuer scripts/ci/check-integration-results.py (nur Standardbibliothek).

Aufruf im Repository-Wurzelverzeichnis:
  python3 -m unittest discover -s scripts/ci -p 'test_*.py'

Die Berichte bilden das echte Failsafe-Verhalten nach: Hat eine aeussere Klasse eine
@Nested-Klasse, steht ihr eigener Bericht mit tests="0" ohne Testfaelle da, und ihre eigenen
Testfaelle (classname = aeussere Klasse) stehen zusammen mit denen der inneren Klasse im Bericht
der inneren Klasse (FlywayMigrationIT / FlywayMigrationIT$SeedAssertions).
"""

import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

GATE = Path(__file__).with_name("check-integration-results.py")
PACKAGE = "de.brasi.nova.taskmanagement.sample"


def write_report(directory: Path, suite: str, cases: dict[str, int] | None = None, *,
                 tests: int | None = None, skipped: int = 0, failures: int = 0,
                 errors: int = 0) -> None:
    """Schreibt einen Failsafe-Bericht TEST-<suite>.xml.

    cases: Testfaelle je deklarierender Klasse (einfacher Binaername, z. B. "FooIT$Inner").
    tests: Wert des Attributs tests (Standard: Summe der Testfaelle).
    """
    cases = cases or {}
    count = sum(cases.values()) if tests is None else tests
    body = "".join(
        f'<testcase name="t{unit}{i}" classname="{PACKAGE}.{unit}"/>'
        for unit, number in cases.items() for i in range(number)
    )
    (directory / f"TEST-{PACKAGE}.{suite}.xml").write_text(
        f'<testsuite name="{PACKAGE}.{suite}" tests="{count}" failures="{failures}" '
        f'errors="{errors}" skipped="{skipped}" time="1.5">{body}</testsuite>',
        encoding="utf-8",
    )


class GateTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.reports = Path(self._tmp.name) / "reports"
        self.sources = Path(self._tmp.name) / "src"
        self.reports.mkdir()
        (self.sources / "pkg").mkdir(parents=True)

    def write_source(self, name: str, body: str) -> None:
        (self.sources / "pkg" / f"{name}.java").write_text(textwrap.dedent(body), encoding="utf-8")

    def run_gate(self, expected: str, with_source: bool = True) -> subprocess.CompletedProcess:
        command = [sys.executable, str(GATE), "--reports", str(self.reports), "--expected", expected]
        if with_source:
            command += ["--source-dir", str(self.sources)]
        return subprocess.run(command, capture_output=True, text=True, check=False)


class ReportBasedChecksTest(GateTestCase):
    """Pruefungen ohne Quelltext-Analyse (nur --expected)."""

    def test_green(self) -> None:
        write_report(self.reports, "OtherIT", {"OtherIT": 3})
        self.assertEqual(self.run_gate("OtherIT", with_source=False).returncode, 0)

    def test_red_when_class_has_no_tests_at_all(self) -> None:
        write_report(self.reports, "EmptyIT", {})
        write_report(self.reports, "OtherIT", {"OtherIT": 3})
        result = self.run_gate("EmptyIT,OtherIT", with_source=False)
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("EmptyIT", result.stdout)

    def test_red_when_total_is_zero(self) -> None:
        write_report(self.reports, "OtherIT", {})
        self.assertEqual(self.run_gate("OtherIT", with_source=False).returncode, 1)

    def test_red_on_skipped_tests(self) -> None:
        write_report(self.reports, "OtherIT", {"OtherIT": 3}, skipped=3)
        result = self.run_gate("OtherIT", with_source=False)
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("uebersprungene", result.stdout)

    def test_red_on_failures_and_errors(self) -> None:
        write_report(self.reports, "FailIT", {"FailIT": 3}, failures=1)
        write_report(self.reports, "ErrorIT", {"ErrorIT": 3}, errors=1)
        result = self.run_gate("FailIT,ErrorIT", with_source=False)
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("Failure", result.stdout)

    def test_red_when_expected_class_has_no_report(self) -> None:
        write_report(self.reports, "OtherIT", {"OtherIT": 3})
        result = self.run_gate("OtherIT,MissingIT", with_source=False)
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("MissingIT", result.stdout)

    def test_red_when_unexpected_class_reported(self) -> None:
        write_report(self.reports, "OtherIT", {"OtherIT": 3})
        write_report(self.reports, "StrayIT", {"StrayIT": 3})
        result = self.run_gate("OtherIT", with_source=False)
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("StrayIT", result.stdout)

    def test_red_on_empty_report_directory(self) -> None:
        self.assertEqual(self.run_gate("OtherIT", with_source=False).returncode, 1)

    def test_red_when_outer_report_has_zero_tests_and_other_classes_ran(self) -> None:
        # Regression Review 1: aeusserer Bericht mit 0 Tests, andere Klassen liefern Tests.
        write_report(self.reports, "FooIT", {})
        write_report(self.reports, "OtherIT", {"OtherIT": 3})
        result = self.run_gate("FooIT,OtherIT", with_source=False)
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("FooIT", result.stdout)


class InnerClassChecksTest(GateTestCase):
    """Pruefungen mit Quelltext-Analyse: innere Testklassen brauchen berichtete Testfaelle."""

    FOO_WITH_NESTED = """
        package de.brasi.nova.taskmanagement.sample;
        class FooIT {
          @Test void outerA() {}
          @Test void outerB() {}
          @Nested
          class Inner {
            @Test void innerA() {}
          }
          static class ItConfig {}
        }
        """

    def setUp(self) -> None:
        super().setUp()
        self.write_source("FooIT", self.FOO_WITH_NESTED)
        self.write_source("OtherIT", "class OtherIT { @Test void a() {} }")

    def write_other(self) -> None:
        write_report(self.reports, "OtherIT", {"OtherIT": 1})

    def test_green_with_real_failsafe_layout(self) -> None:
        # Aeusserer Bericht leer, alle Testfaelle (aussen 2, innen 1) im Bericht der inneren Klasse.
        write_report(self.reports, "FooIT", {}, tests=0)
        write_report(self.reports, "FooIT$Inner", {"FooIT": 2, "FooIT$Inner": 1})
        self.write_other()
        result = self.run_gate("FooIT,OtherIT")
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_green_when_outer_and_inner_have_own_reports(self) -> None:
        write_report(self.reports, "FooIT", {"FooIT": 2})
        write_report(self.reports, "FooIT$Inner", {"FooIT$Inner": 1})
        self.write_other()
        self.assertEqual(self.run_gate("FooIT,OtherIT").returncode, 0)

    def test_red_when_outer_tests_ran_but_inner_report_missing(self) -> None:
        # Regression Review 2: aeussere Tests vorhanden (Summe > 0), Bericht der inneren Klasse fehlt.
        write_report(self.reports, "FooIT", {"FooIT": 2})
        self.write_other()
        result = self.run_gate("FooIT,OtherIT")
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("Testklasse(n): FooIT$Inner ", result.stdout)

    def test_red_when_inner_report_missing_in_failsafe_layout(self) -> None:
        # Echtes Failsafe-Layout ohne Bericht der inneren Klasse: nur der leere aeussere Bericht.
        write_report(self.reports, "FooIT", {}, tests=0)
        self.write_other()
        result = self.run_gate("FooIT,OtherIT")
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("Testklasse(n): FooIT, FooIT$Inner ", result.stdout)

    def test_red_when_inner_report_has_no_tests(self) -> None:
        write_report(self.reports, "FooIT", {"FooIT": 2})
        write_report(self.reports, "FooIT$Inner", {}, tests=0)
        self.write_other()
        result = self.run_gate("FooIT,OtherIT")
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("Testklasse(n): FooIT$Inner ", result.stdout)

    def test_red_when_outer_has_own_tests_but_none_reported(self) -> None:
        write_report(self.reports, "FooIT", {}, tests=0)
        write_report(self.reports, "FooIT$Inner", {"FooIT$Inner": 1})
        self.write_other()
        result = self.run_gate("FooIT,OtherIT")
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("Testklasse(n): FooIT ", result.stdout)

    def test_outer_without_own_tests_needs_no_outer_testcases(self) -> None:
        self.write_source(
            "BarIT",
            """
            class BarIT {
              @Nested class Seeds { @Test void a() {} @Test void b() {} }
            }
            """,
        )
        write_report(self.reports, "BarIT", {}, tests=0)
        write_report(self.reports, "BarIT$Seeds", {"BarIT$Seeds": 2})
        self.write_other()
        self.assertEqual(self.run_gate("BarIT,OtherIT").returncode, 0)

    def test_deeper_nesting_is_required_too(self) -> None:
        self.write_source(
            "DeepIT",
            """
            class DeepIT {
              @Nested class A { @Nested class B { @Test void leaf() {} } }
            }
            """,
        )
        write_report(self.reports, "DeepIT", {}, tests=0)
        self.write_other()
        result = self.run_gate("DeepIT,OtherIT")
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("Testklasse(n): DeepIT$A$B ", result.stdout)
        write_report(self.reports, "DeepIT$A$B", {"DeepIT$A$B": 1})
        self.assertEqual(self.run_gate("DeepIT,OtherIT").returncode, 0)

    def test_source_analysis_ignores_comments_strings_and_other_annotations(self) -> None:
        self.write_source(
            "NoisyIT",
            '''
            // @Test void inComment() {}
            /* @Test void inBlockComment() { */
            @TestPropertySource(properties = "a={")
            class NoisyIT {
              static final String TEXT = "@Test } {";
              static final char BRACE = '{';
              static final String BLOCK = """
                  @Test class Fake { @Test void x() {} }
                  """;
              @Test void real() {
                Runnable r = new Runnable() { @Test public void run() {} };
                Class<?> c = String.class;
              }
              @Nested class Quiet { @TestConfiguration static class Cfg {} }
            }
            ''',
        )
        write_report(self.reports, "NoisyIT", {"NoisyIT": 1})
        self.write_other()
        # Quiet hat keine Testmethoden und braucht keinen Bericht; Fake/Anonyme zaehlen nicht.
        result = self.run_gate("NoisyIT,OtherIT")
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_class_without_detected_test_methods_falls_back_to_report_total(self) -> None:
        self.write_source("InheritedIT", "class InheritedIT extends BaseSuite {}")
        write_report(self.reports, "InheritedIT", {"InheritedIT": 2})
        self.write_other()
        self.assertEqual(self.run_gate("InheritedIT,OtherIT").returncode, 0)
        write_report(self.reports, "InheritedIT", {}, tests=0)
        result = self.run_gate("InheritedIT,OtherIT")
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("InheritedIT", result.stdout)

    def test_red_when_source_of_expected_class_is_missing(self) -> None:
        write_report(self.reports, "GhostIT", {"GhostIT": 1})
        self.write_other()
        result = self.run_gate("GhostIT,OtherIT")
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("GhostIT", result.stdout)

    def test_expected_from_source_requires_inner_reports(self) -> None:
        write_report(self.reports, "FooIT", {"FooIT": 2})
        self.write_other()
        result = subprocess.run(
            [sys.executable, str(GATE), "--reports", str(self.reports),
             "--expected-from-source", str(self.sources)],
            capture_output=True, text=True, check=False,
        )
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("FooIT$Inner", result.stdout)


if __name__ == "__main__":
    unittest.main()
