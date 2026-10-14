import csv
import importlib.util
import io
import os
from pathlib import Path
import sys
import tempfile
import subprocess
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "analyze_obd.py"
SPEC = importlib.util.spec_from_file_location("analyze_obd", SCRIPT)
analyzer = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = analyzer
SPEC.loader.exec_module(analyzer)


class ObdAnalysisTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(dir=os.environ.get("NAVISMART_TEST_TMPDIR"))
        self.addCleanup(self.directory.cleanup)

    def session(self, rows, name="session.csv"):
        path = Path(self.directory.name) / name
        with path.open("w", newline="", encoding="utf-8") as output:
            writer = csv.writer(output)
            writer.writerow(analyzer.HEADER)
            writer.writerows(rows)
        return path

    @staticmethod
    def row(signal="SPEED", value="36", elapsed=0, state="AVAILABLE", utc="2026-10-14T12:00:00Z"):
        pid, unit, *_ = analyzer.SIGNALS[signal]
        return ["1", utc, elapsed, pid, signal, value, unit, state]

    def test_units_signed_values_and_unknown_states(self):
        path = self.session([self.row("COOLANT", "-10"), self.row("SHORT_TRIM", "-20", 1), self.row("RPM", "", 2, "UNAVAILABLE")])
        report = analyzer.analyze(path)
        self.assertEqual(-10, report["sensors"]["COOLANT"]["minimum"])
        self.assertIsNone(report["sensors"]["RPM"]["minimum"])
        self.assertFalse(report["vehicleCalibrationValidated"])
        self.assertTrue(any("unknown" in message for message in report["notices"]))

    def test_trapezoids_keep_separate_coverage_and_units(self):
        rows = [self.row("SPEED", "36", 0), self.row("FUEL_RATE", "3.6", 100),
                self.row("SPEED", "36", 1000, utc="2026-10-14T12:00:01Z"),
                self.row("FUEL_RATE", "3.6", 1100, utc="2026-10-14T12:00:01.1Z")]
        report = analyzer.analyze(self.session(rows))
        self.assertAlmostEqual(.01, report["sensors"]["SPEED"]["reportedIntegral"]["nominalIntegral"])
        self.assertAlmostEqual(.001, report["sensors"]["FUEL_RATE"]["reportedIntegral"]["nominalIntegral"])
        self.assertEqual("L", report["sensors"]["FUEL_RATE"]["reportedIntegral"]["unit"])
        self.assertLess(report["sensors"]["FUEL_RATE"]["reportedIntegral"]["coverageFractionOfSession"], 1)
        self.assertNotIn("litresPer100Km", report)

    def test_missing_and_large_gaps_break_integrals(self):
        rows = [self.row(), self.row(value="", elapsed=1000, state="INVALID"), self.row(elapsed=2000), self.row(elapsed=10000)]
        report = analyzer.analyze(self.session(rows))
        integral = report["sensors"]["SPEED"]["reportedIntegral"]
        self.assertIsNone(integral["nominalIntegral"])
        self.assertEqual(0, integral["coveredSeconds"])

    def test_known_zero_fuel_rate_is_not_missing(self):
        report = analyzer.analyze(self.session([self.row("FUEL_RATE", "0"), self.row("FUEL_RATE", "0", 1000)]))
        self.assertEqual(0, report["sensors"]["FUEL_RATE"]["reportedIntegral"]["nominalIntegral"])

    def test_bad_schema_units_pid_state_and_numeric_values_rejected(self):
        for index, bad in [(0, "2"), (3, "5E"), (4, "CUSTOM"), (6, "mph"), (7, "FRESH"), (5, "NaN"), (5, "Infinity"), (5, "-1"), (5, "256")]:
            row = self.row(); row[index] = bad
            with self.subTest(index=index, bad=bad), self.assertRaises(ValueError):
                analyzer.analyze(self.session([row]))
        with self.assertRaises(ValueError):
            analyzer.analyze(self.session([self.row(value="1", state="UNAVAILABLE")]))

    def test_backward_monotonic_time_and_duplicates_rejected(self):
        for rows in [[self.row(elapsed=2), self.row(elapsed=1)], [self.row(), self.row()], [self.row(elapsed=2**63)]]:
            with self.assertRaises(ValueError):
                analyzer.analyze(self.session(rows))

    def test_clock_jumps_are_flagged_and_do_not_change_integral_timebase(self):
        report = analyzer.analyze(self.session([self.row(), self.row(elapsed=1000, utc="2026-10-14T13:00:00Z")]))
        self.assertEqual(1, report["wallClockDiscontinuities"])
        self.assertAlmostEqual(.01, report["sensors"]["SPEED"]["reportedIntegral"]["nominalIntegral"])

    def test_same_or_overlapping_sessions_fail_separation(self):
        path = self.session([self.row(), self.row(elapsed=1000, utc="2026-10-14T12:00:01Z")])
        self.assertFalse(analyzer.check_separation(path, path)["separationChecksPassed"])
        other = self.session([self.row(value="35"), self.row(value="35", elapsed=1000, utc="2026-10-14T12:00:01Z")], "other.csv")
        report = analyzer.check_separation(path, other)
        self.assertTrue(report["differentFiles"])
        self.assertFalse(report["separationChecksPassed"])

    def test_disjoint_sessions_pass_only_the_file_and_time_checks(self):
        first = self.session([self.row(), self.row(elapsed=1000, utc="2026-10-14T12:00:01Z")])
        last = self.session([self.row(utc="2026-10-15T12:00:00Z"), self.row(elapsed=1000, utc="2026-10-15T12:00:01Z")], "validation.csv")
        report = analyzer.check_separation(first, last)
        self.assertTrue(report["separationChecksPassed"])
        self.assertFalse(report["physicalIndependenceValidated"])

    def test_empty_invalid_utf8_oversize_and_naive_dates_rejected(self):
        with self.assertRaises(ValueError):
            analyzer.analyze(self.session([]))
        path = Path(self.directory.name) / "bad.csv"
        path.write_bytes(b"\xff")
        with self.assertRaises(UnicodeError):
            analyzer.analyze(path)
        path.write_bytes(b"x" * (analyzer.MAX_BYTES + 1))
        with self.assertRaises(ValueError):
            analyzer.analyze(path)
        with self.assertRaises(ValueError):
            analyzer.analyze(self.session([self.row(utc="2026-10-14T12:00:00")]))
        path = self.session([self.row()]); path.write_bytes(path.read_bytes().replace(b"AVAILABLE", b'"AVAILABLE'))
        with self.assertRaises(csv.Error):
            analyzer.analyze(path)

    def test_cli_refuses_overwriting_sources_and_existing_reports(self):
        path = self.session([self.row()]); original = path.read_bytes()
        process = subprocess.run([sys.executable, "-X", "utf8", str(SCRIPT), str(path), "--report", str(path)], capture_output=True, timeout=10)
        self.assertEqual(2, process.returncode); self.assertEqual(original, path.read_bytes())
        report = path.with_suffix(".json"); report.write_text("existing report", encoding="utf-8")
        process = subprocess.run([sys.executable, "-X", "utf8", str(SCRIPT), str(path), "--report", str(report)], capture_output=True, timeout=10)
        self.assertEqual(2, process.returncode); self.assertEqual("existing report", report.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
