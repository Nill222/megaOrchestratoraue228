#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
import subprocess
from datetime import datetime
from pathlib import Path


def pct(values: list[float], q: float) -> float:
    if not values:
        return 0.0
    s = sorted(values)
    if len(s) == 1:
        return float(s[0])
    k = (len(s) - 1) * q
    i = int(k)
    j = min(i + 1, len(s) - 1)
    return float(s[i] + (s[j] - s[i]) * (k - i))


def compute_capture_to_decision(log_paths: list[Path]) -> dict[str, float]:
    pat_cap = re.compile(r"^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}).*worker cam=(\d+) current capture")
    pat_dec = re.compile(r"^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}).*decision cam=(\d+)")
    fmt = "%Y-%m-%d %H:%M:%S.%f"
    samples: list[float] = []
    for path in log_paths:
        starts: dict[int, datetime] = {}
        for line in path.read_text(encoding="utf-8", errors="ignore").splitlines():
            m1 = pat_cap.search(line)
            if m1:
                starts[int(m1.group(2))] = datetime.strptime(m1.group(1), fmt)
                continue
            m2 = pat_dec.search(line)
            if m2:
                cam = int(m2.group(2))
                if cam in starts:
                    t = datetime.strptime(m2.group(1), fmt)
                    samples.append((t - starts[cam]).total_seconds() * 1000.0)
    return {
        "samples": len(samples),
        "p50_ms": round(pct(samples, 0.5), 3),
        "p95_ms": round(pct(samples, 0.95), 3),
        "min_ms": round(min(samples), 3) if samples else 0.0,
        "max_ms": round(max(samples), 3) if samples else 0.0,
    }


def main() -> int:
    ap = argparse.ArgumentParser(description="Stage G correctness gate")
    ap.add_argument("--runs", type=int, default=3)
    ap.add_argument("--cameras", type=int, default=5)
    ap.add_argument("--profile", default="normal", choices=["normal", "stress"])
    ap.add_argument("--ref", default="testimage/ref.jpg")
    ap.add_argument("--cur", default="testimage/cur.jpg")
    args = ap.parse_args()

    repo = Path(__file__).resolve().parents[1]
    geom_jar = repo / "java-geometry-service" / "target" / "java-geometry-service-0.1.0-SNAPSHOT.jar"
    orch_jar = repo / "orchestrator-java" / "target" / "orchestrator-0.1.0-SNAPSHOT.jar"
    if not geom_jar.exists() or not orch_jar.exists():
        raise SystemExit("missing jar(s): build java-geometry-service and orchestrator-java first")

    # 1) ref->ref / ref->cur correctness strict compare
    g = subprocess.run(
        [
            "java",
            "-cp",
            str(geom_jar),
            "com.example.iml.service.GeometryCorrectnessGateMain",
            str((repo / args.ref).resolve()),
            str((repo / args.cur).resolve()),
        ],
        cwd=str(repo),
        text=True,
        capture_output=True,
        check=False,
    )
    if g.returncode != 0:
        print(g.stdout.strip())
        print(g.stderr.strip())
        raise SystemExit("Stage G failed: geometry correctness mismatch")
    correctness = json.loads(g.stdout.strip())

    # 2) timeout/restart gate on harness
    h = subprocess.run(
        [
            "python3",
            "scripts/load_harness.py",
            "--profile",
            args.profile,
            "--runs",
            str(args.runs),
            "--cameras",
            str(args.cameras),
            "--output-dir",
            "logs/harness",
        ],
        cwd=str(repo),
        text=True,
        capture_output=True,
        check=False,
    )
    if h.returncode != 0:
        print(h.stdout)
        print(h.stderr)
        raise SystemExit("Stage G failed: harness run failed")
    m = re.search(r"JSON report: (.+)", h.stdout)
    if not m:
        raise SystemExit("Stage G failed: cannot find harness JSON path")
    report_path = Path(m.group(1).strip())
    report = json.loads(report_path.read_text(encoding="utf-8"))
    ts = report_path.stem.split("-")[-1]
    run_logs = sorted((repo / "logs" / "harness").glob(f"load-harness-{ts}-run-*.log"))
    cap_dec = compute_capture_to_decision(run_logs)

    out = {
        "correctness": correctness,
        "harness_summary": report["summary"],
        "capture_to_decision": cap_dec,
        "report_path": str(report_path),
        "gate_passed": bool(
            correctness.get("strict_compare_passed")
            and report["summary"].get("timeouts_detected", 0) == 0
            and report["summary"].get("restart_counts", {}).get("all_total", 0) == 0
            and report["summary"].get("non_zero_exit_runs", 0) == 0
        ),
    }
    out_path = repo / "logs" / "harness" / f"stage-g-gate-{datetime.now().strftime('%Y%m%d-%H%M%S')}.json"
    out_path.write_text(json.dumps(out, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(out, ensure_ascii=False))
    print(f"stage_g_report: {out_path}")
    if not out["gate_passed"]:
        raise SystemExit("Stage G failed: one or more gates are red")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

