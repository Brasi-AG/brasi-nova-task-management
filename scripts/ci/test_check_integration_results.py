#!/usr/bin/env python3
"""Tests fuer scripts/ci/check-integration-results.py (nur Standardbibliothek).

Aufruf im Repository-Wurzelverzeichnis:
  python3 -m unittest discover -s scripts/ci -p 'test_*.py'
"""

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

GATE = Path(__file__).with_name("check-integration-results.py")
PACKAGE = "de.brasi.nova.taskmanagement.flyway.it"


def write_report(directory: Path, class_name: str, tests: int, skipped: int = 0,
                 failures: int = 0, errors: int = 0, package: str = PACKAGE) -> None:
    """Schreibt einen minimalen Failsafe-Bericht (TEST-<klasse>.xml)."""
    cases = "".join(f'<testcase name="t{i}" classname="{package}.{class_name}"/>'
                    for i in range(tests))
    (directory / f"TEST-{package}.{class_name}.xml").write_text(
        f'<testsuite name="{package}.{class_name}" tests="{tests}" failures="{failures}" '
        f'errors="{errors}" skipped="{skipped}" time="1.5">{cases}</testsuite>',
        encoding="utf-8",
    )


class CheckIntegrationResultsTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.reports = Path(self._tmp.name)

    def run_gate(self, expected: str) -> subprocess.CompletedProcess:
        return subprocess.run(
            [sys.executable, str(GATE), "--reports", str(self.reports), "--expected", expected],
            capture_output=True, text=True, check=False,
        )

    def test_green_with_outer_and_inner_report(self) -> None:
        write_report(self.reports, "FlywayMigrationIT", 0)
        write_report(self.reports, "FlywayMigrationIT$SeedAssertions", 26)
        write_report(self.reports, "OtherIT", 3)
        result = self.run_gate("FlywayMigrationIT,OtherIT")
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_red_when_inner_report_missing_although_other_classes_ran(self) -> None:
        # Regression: Bericht der aeusseren Klasse mit tests="0", innerer Bericht fehlt,
        # andere Klassen liefern Tests -> die Summe ist > 0, das Gate muss trotzdem rot sein.
        write_report(self.reports, "FlywayMigrationIT", 0)
        write_report(self.reports, "OtherIT", 3)
        result = self.run_gate("FlywayMigrationIT,OtherIT")
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("FlywayMigrationIT", result.stdout)
        self.assertIn("0 Tests", result.stdout)
        self.assertNotIn("OtherIT. Fehlt", result.stdout)

    def test_red_when_class_has_no_tests_at_all(self) -> None:
        write_report(self.reports, "EmptyIT", 0)
        write_report(self.reports, "OtherIT", 3)
        result = self.run_gate("EmptyIT,OtherIT")
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("EmptyIT", result.stdout)

    def test_red_when_total_is_zero(self) -> None:
        write_report(self.reports, "OtherIT", 0)
        result = self.run_gate("OtherIT")
        self.assertEqual(result.returncode, 1, result.stdout)

    def test_red_on_skipped_tests(self) -> None:
        write_report(self.reports, "OtherIT", 3, skipped=3)
        result = self.run_gate("OtherIT")
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("uebersprungene", result.stdout)

    def test_red_on_failures_and_errors(self) -> None:
        write_report(self.reports, "FailIT", 3, failures=1)
        write_report(self.reports, "ErrorIT", 3, errors=1)
        result = self.run_gate("FailIT,ErrorIT")
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("Failure", result.stdout)

    def test_red_when_expected_class_has_no_report(self) -> None:
        write_report(self.reports, "OtherIT", 3)
        result = self.run_gate("OtherIT,MissingIT")
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("MissingIT", result.stdout)

    def test_red_when_unexpected_class_reported(self) -> None:
        write_report(self.reports, "OtherIT", 3)
        write_report(self.reports, "StrayIT", 3)
        result = self.run_gate("OtherIT")
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("StrayIT", result.stdout)

    def test_red_on_empty_report_directory(self) -> None:
        result = self.run_gate("OtherIT")
        self.assertEqual(result.returncode, 1, result.stdout)


if __name__ == "__main__":
    unittest.main()
