#!/usr/bin/env python3
"""Tests fuer scripts/ci/split-integration-tests.sh (nur Standardbibliothek, braucht bash).

Aufruf im Repository-Wurzelverzeichnis:
  python3 -m unittest discover -s scripts/ci -p 'test_*.py'

Das Skript wird per Subprozess gegen ein temporaeres Test-Quellverzeichnis und eine eigene
Gewichtsdatei aufgerufen (IT_SOURCE_DIR, IT_WEIGHTS_FILE, IT_DEFAULT_WEIGHT).
"""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("split-integration-tests.sh")
BASH = shutil.which("bash")


@unittest.skipIf(BASH is None, "bash nicht gefunden")
class SplitIntegrationTestsTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.root = Path(self._tmp.name)
        self.sources = self.root / "src"
        self.sources.mkdir()
        self.weights = self.root / "weights.txt"
        self.weights.write_text("", encoding="utf-8")

    def add_classes(self, *names: str, package: str = "pkg") -> None:
        directory = self.sources / package
        directory.mkdir(parents=True, exist_ok=True)
        for name in names:
            (directory / f"{name}.java").write_text(f"class {name} {{}}", encoding="utf-8")

    def set_weights(self, text: str) -> None:
        self.weights.write_text(text, encoding="utf-8")

    def run_split(self, *args: str, default_weight: str | None = None,
                  source: Path | None = None) -> subprocess.CompletedProcess:
        env = dict(os.environ)
        env["IT_SOURCE_DIR"] = (source or self.sources).as_posix()
        env["IT_WEIGHTS_FILE"] = self.weights.as_posix()
        if default_weight is not None:
            env["IT_DEFAULT_WEIGHT"] = default_weight
        return subprocess.run(
            [BASH, SCRIPT.as_posix(), *args], env=env, capture_output=True, text=True, check=False
        )

    def shard(self, shard: int, total: int, **kwargs) -> list[str]:
        result = self.run_split(str(shard), str(total), **kwargs)
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout.strip().split(",")

    # --- Auswahl -----------------------------------------------------------------------------

    def test_union_of_all_shards_is_all_classes_without_duplicates(self) -> None:
        names = [f"C{i:02d}IT" for i in range(23)]
        self.add_classes(*names[:10], package="a")
        self.add_classes(*names[10:], package="b/c")
        self.set_weights("\n".join(f"{name} {5 + (i * 7) % 40}" for i, name in enumerate(names)))
        for total in (1, 2, 3, 5, 8, 23):
            selected = [name for shard in range(1, total + 1) for name in self.shard(shard, total)]
            self.assertEqual(len(selected), len(set(selected)), f"Doppelte bei N={total}")
            self.assertEqual(sorted(selected), sorted(names), f"Vereinigung bei N={total}")

    def test_only_it_files_are_selected(self) -> None:
        self.add_classes("RealIT", "OtherIT")
        (self.sources / "pkg" / "HelperTest.java").write_text("class HelperTest {}", encoding="utf-8")
        (self.sources / "pkg" / "ItConfig.java").write_text("class ItConfig {}", encoding="utf-8")
        (self.sources / "pkg" / "RealIT.txt").write_text("", encoding="utf-8")
        self.assertEqual(self.shard(1, 1), ["OtherIT", "RealIT"])

    def test_deterministic(self) -> None:
        self.add_classes(*[f"C{i:02d}IT" for i in range(17)])
        first = [self.shard(s, 4) for s in range(1, 5)]
        second = [self.shard(s, 4) for s in range(1, 5)]
        self.assertEqual(first, second)

    def test_heavy_class_gets_its_own_shard(self) -> None:
        self.add_classes("HeavyIT", "AIT", "BIT", "CIT", "DIT", "EIT")
        self.set_weights("HeavyIT 100\nAIT 10\nBIT 10\nCIT 10\nDIT 10\nEIT 10\n")
        self.assertEqual(self.shard(1, 2), ["HeavyIT"])
        self.assertEqual(self.shard(2, 2), ["AIT", "BIT", "CIT", "DIT", "EIT"])

    def test_plan_lists_all_shards(self) -> None:
        self.add_classes("AIT", "BIT", "CIT")
        self.set_weights("AIT 30\nBIT 20\nCIT 10\n")
        result = self.run_split("1", "2", "--plan")
        self.assertEqual(result.returncode, 0, result.stderr)
        lines = result.stdout.strip().splitlines()
        self.assertEqual(len(lines), 3, result.stdout)  # Kopfzeile + 2 Shards
        self.assertIn("AIT", lines[1])
        self.assertIn("BIT", lines[2])
        self.assertIn("CIT", lines[2])

    # --- Gewichte ----------------------------------------------------------------------------

    def test_unknown_class_gets_default_weight(self) -> None:
        self.add_classes("KnownIT", "NewIT")
        self.set_weights("KnownIT 100\n")
        # Standardgewicht 10 < 100: die neue Klasse liegt im anderen Shard als die schwere.
        self.assertEqual(self.shard(1, 2, default_weight="10"), ["KnownIT"])
        self.assertEqual(self.shard(2, 2, default_weight="10"), ["NewIT"])
        # Standardgewicht 500 > 100: die neue Klasse wird zuerst verteilt, die bekannte folgt.
        result = self.run_split("1", "2", "--plan", default_weight="500")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertRegex(result.stdout, r"1\s+1\s+500\s+NewIT")
        self.assertRegex(result.stdout, r"2\s+1\s+100\s+KnownIT")
        self.assertIn("ohne Gewicht", result.stderr)

    def test_stale_weight_entry_is_ignored_with_hint(self) -> None:
        self.add_classes("AIT")
        self.set_weights("AIT 10\nGoneIT 99\n")
        result = self.run_split("1", "1")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "AIT")
        self.assertIn("GoneIT", result.stderr)

    def test_comments_and_blank_lines_in_weights_are_ignored(self) -> None:
        self.add_classes("AIT", "BIT")
        self.set_weights("# Kommentar\n\nAIT 50\n# BIT 99\n")
        result = self.run_split("1", "2", "--plan", default_weight="7")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertRegex(result.stdout, r"1\s+1\s+50\s+AIT")
        self.assertRegex(result.stdout, r"2\s+1\s+7\s+BIT")

    # --- Fehlerpfade -------------------------------------------------------------------------

    def test_invalid_arguments_exit_2(self) -> None:
        self.add_classes("AIT", "BIT")
        for args in (("0", "2"), ("3", "2"), ("a", "2"), ("1", "0"), ("1", "x"), ("1",),
                     ("1", "2", "--unknown"), ("1", "2", "--plan", "extra")):
            with self.subTest(args=args):
                self.assertEqual(self.run_split(*args).returncode, 2, args)

    def test_missing_source_directory_exits_2(self) -> None:
        result = self.run_split("1", "1", source=self.root / "gibt-es-nicht")
        self.assertEqual(result.returncode, 2, result.stderr)

    def test_no_it_classes_exits_3(self) -> None:
        self.assertEqual(self.run_split("1", "1").returncode, 3)

    def test_duplicate_class_name_exits_3(self) -> None:
        self.add_classes("AIT", package="a")
        self.add_classes("AIT", package="b")
        result = self.run_split("1", "2")
        self.assertEqual(result.returncode, 3, result.stderr)
        self.assertIn("AIT", result.stderr)

    def test_more_shards_than_classes_exits_3(self) -> None:
        self.add_classes("AIT", "BIT")
        self.assertEqual(self.run_split("3", "3").returncode, 3)

    def test_invalid_weight_exits_3(self) -> None:
        self.add_classes("AIT")
        self.set_weights("AIT schwer\n")
        self.assertEqual(self.run_split("1", "1").returncode, 3)


if __name__ == "__main__":
    unittest.main()
