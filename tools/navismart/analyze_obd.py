"""Local quality review of NaviSmart schema-1 OBD CSV files; no vehicle calibration is inferred."""

from __future__ import annotations

import argparse
import csv
from dataclasses import dataclass
from datetime import datetime, timezone, timedelta
import hashlib
import io
import json
import math
from pathlib import Path
import statistics

HEADER = ["schema", "utc", "elapsed_ms", "pid", "signal", "value", "unit", "state"]
# Standard Mode 01 decoded ranges, not thresholds learned from a particular vehicle.
SIGNALS = {
    "LOAD": ("04", "%", 0, 100), "COOLANT": ("05", "°C", -40, 215),
    "SHORT_TRIM": ("06", "%", -100, 99.21875), "LONG_TRIM": ("07", "%", -100, 99.21875),
    "MANIFOLD_PRESSURE": ("0B", "kPa", 0, 255), "RPM": ("0C", "rpm", 0, 16383.75),
    "SPEED": ("0D", "km/h", 0, 255), "INTAKE_TEMPERATURE": ("0F", "°C", -40, 215),
    "MAF": ("10", "g/s", 0, 655.35), "THROTTLE": ("11", "%", 0, 100),
    "FUEL_LEVEL": ("2F", "%", 0, 100), "ETHANOL": ("52", "%", 0, 100),
    "FUEL_RATE": ("5E", "L/h", 0, 3276.75),
}
STATES = {"AVAILABLE", "UNAVAILABLE", "INVALID", "AMBIGUOUS"}
MAX_BYTES = 6 * 1024 * 1024


@dataclass(frozen=True)
class Sample:
    utc: datetime
    elapsed_ms: int
    signal: str
    value: float | None
    state: str


def read_session(path: Path) -> tuple[list[Sample], str]:
    with path.open("rb") as stream:
        raw = stream.read(MAX_BYTES + 1)
    if len(raw) > MAX_BYTES:
        raise ValueError("Session exceeds the size limit")
    reader = csv.DictReader(io.StringIO(raw.decode("utf-8-sig", errors="strict"), newline=""), strict=True)
    if reader.fieldnames != HEADER:
        raise ValueError("Unsupported CSV header; export a NaviSmart schema-1 session")
    samples: list[Sample] = []
    latest_by_signal: dict[str, int] = {}
    for row in reader:
        if len(samples) >= 200_000 or None in row or any(v is None for v in row.values()):
            raise ValueError("Invalid row or session row limit exceeded")
        signal = row["signal"]
        if row["schema"] != "1" or signal not in SIGNALS or row["state"] not in STATES:
            raise ValueError("Unsupported schema, signal or state")
        pid, unit, lower, upper = SIGNALS[signal]
        if row["pid"] != pid or row["unit"] != unit:
            raise ValueError("PID, signal and unit do not agree")
        utc = datetime.fromisoformat(row["utc"].replace("Z", "+00:00"))
        if utc.tzinfo is None or utc.utcoffset() != timedelta(0):
            raise ValueError("An explicit UTC timestamp is required")
        elapsed = int(row["elapsed_ms"])
        if not 0 <= elapsed <= 2**63 - 1 or (samples and elapsed < samples[-1].elapsed_ms):
            raise ValueError("Session monotonic time moves backwards")
        if signal in latest_by_signal and elapsed <= latest_by_signal[signal]:
            raise ValueError("Duplicate or backwards time for a sensor")
        value = float(row["value"]) if row["value"] else None
        if (row["state"] == "AVAILABLE") != (value is not None):
            raise ValueError("Missing data is not a numeric measurement")
        if value is not None and (not math.isfinite(value) or value < lower - 1e-7 or value > upper + 1e-7):
            raise ValueError("Nonfinite or out-of-range decoded sensor value")
        samples.append(Sample(utc.astimezone(timezone.utc), elapsed, signal, value, row["state"]))
        latest_by_signal[signal] = elapsed
    if not samples:
        raise ValueError("Session contains no measurements")
    return samples, hashlib.sha256(raw).hexdigest()


def integral(samples: list[Sample], max_gap_seconds: float) -> dict:
    """Trapezoids over consecutive available responses only. Gaps and missing values break coverage."""
    total, covered, intervals = 0.0, 0.0, 0
    for first, last in zip(samples, samples[1:]):
        seconds = (last.elapsed_ms - first.elapsed_ms) / 1000
        if first.value is None or last.value is None or seconds <= 0 or seconds > max_gap_seconds:
            continue
        total += (first.value + last.value) / 2 * seconds / 3600
        covered += seconds
        intervals += 1
    return {"nominalIntegral": total if intervals else None, "coveredSeconds": covered, "intervals": intervals}


def analyze(path: Path, max_gap_seconds: float = 3.0) -> dict:
    if not math.isfinite(max_gap_seconds) or not 0 < max_gap_seconds <= 60:
        raise ValueError("Maximum accepted sample gap must be between 0 and 60 seconds")
    samples, digest = read_session(path)
    span = (samples[-1].elapsed_ms - samples[0].elapsed_ms) / 1000
    clock_jumps = sum(abs((b.utc - a.utc).total_seconds() - (b.elapsed_ms - a.elapsed_ms) / 1000) > 1
                      for a, b in zip(samples, samples[1:]))
    result = {
        "reportSchema": 1, "file": path.name, "sha256": digest,
        "firstUtc": samples[0].utc.isoformat(), "lastUtc": samples[-1].utc.isoformat(),
        "monotonicSpanSeconds": span, "rows": len(samples), "wallClockDiscontinuities": clock_jumps,
        "maximumAcceptedGapSeconds": max_gap_seconds, "sensors": {},
        "vehicleCalibrationValidated": False, "decelerationModelFitted": False,
        "notices": ["Coverage and integrals describe reported ECU measurements, not physical accuracy.",
                    "Fuel level percentages are not litres. MAF is not converted to fuel consumption.",
                    "Distance and fuel integrals have separate coverage; their ratio is not a trip consumption estimate."],
    }
    for signal in SIGNALS:
        selected = [sample for sample in samples if sample.signal == signal]
        if not selected:
            continue
        values = [sample.value for sample in selected if sample.value is not None]
        gaps = [(b.elapsed_ms - a.elapsed_ms) / 1000 for a, b in zip(selected, selected[1:])]
        info = {
            "unit": SIGNALS[signal][1], "requests": len(selected),
            "states": {state: sum(sample.state == state for sample in selected) for state in sorted(STATES)},
            "minimum": min(values) if values else None, "maximum": max(values) if values else None,
            "medianIntervalSeconds": statistics.median(gaps) if gaps else None,
            "maximumIntervalSeconds": max(gaps) if gaps else None,
        }
        if signal in {"SPEED", "FUEL_RATE"}:
            info["reportedIntegral"] = integral(selected, max_gap_seconds)
            info["reportedIntegral"]["unit"] = "km" if signal == "SPEED" else "L"
            info["reportedIntegral"]["coverageFractionOfSession"] = info["reportedIntegral"]["coveredSeconds"] / span if span > 0 else 0
        result["sensors"][signal] = info
    if "FUEL_RATE" not in result["sensors"] or not result["sensors"]["FUEL_RATE"]["states"]["AVAILABLE"]:
        result["notices"].append("Direct fuel rate is unavailable; real fuel consumption remains unknown.")
    if clock_jumps:
        result["notices"].append("Wall-clock changes detected: use monotonic time for intervals and review UTC session boundaries.")
    return result


def check_separation(calibration: Path, validation: Path, max_gap_seconds: float = 3.0) -> dict:
    first, second = analyze(calibration, max_gap_seconds), analyze(validation, max_gap_seconds)
    start_a, end_a = datetime.fromisoformat(first["firstUtc"]), datetime.fromisoformat(first["lastUtc"])
    start_b, end_b = datetime.fromisoformat(second["firstUtc"]), datetime.fromisoformat(second["lastUtc"])
    different = first["sha256"] != second["sha256"]
    nonoverlap = end_a < start_b or end_b < start_a
    clocks = not first["wallClockDiscontinuities"] and not second["wallClockDiscontinuities"]
    return {"reportSchema": 1, "calibration": first, "validation": second,
            "differentFiles": different, "nonoverlappingUtcIntervals": nonoverlap,
            "utcBoundariesReliable": clocks,
            "separationChecksPassed": different and nonoverlap and clocks,
            "physicalIndependenceValidated": False,
            "notice": "Separate files and times do not prove independent driving conditions or validate a vehicle model."}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("session", type=Path, nargs="?")
    parser.add_argument("--calibration", type=Path)
    parser.add_argument("--validation", type=Path)
    parser.add_argument("--max-gap-seconds", type=float, default=3.0)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    if bool(args.session) == bool(args.calibration) or bool(args.calibration) != bool(args.validation):
        parser.error("Provide one session, or both --calibration and --validation")
    try:
        result = (analyze(args.session, args.max_gap_seconds) if args.session else
                  check_separation(args.calibration, args.validation, args.max_gap_seconds))
        rendered = json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + "\n"
        if args.report:
            sources = [args.session] if args.session else [args.calibration, args.validation]
            if any(args.report.resolve() == source.resolve() for source in sources):
                raise ValueError("The report cannot overwrite a source CSV")
            with args.report.open("x", encoding="utf-8") as output:
                output.write(rendered)
        else:
            print(rendered, end="")
    except (OSError, UnicodeError, ValueError, csv.Error) as error:
        parser.exit(2, f"OBD review failed: {error}\n")


if __name__ == "__main__":
    main()
