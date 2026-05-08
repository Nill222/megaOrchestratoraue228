#!/usr/bin/env python3
from __future__ import annotations

import argparse
import csv
import json
import re
import socket
import statistics
import subprocess
import tempfile
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any


@dataclass
class Profile:
    name: str
    runs: int
    cameras: int
    client_delay_ms: int


PROFILES: dict[str, Profile] = {
    "normal": Profile(name="normal", runs=6, cameras=3, client_delay_ms=200),
    "stress": Profile(name="stress", runs=20, cameras=3, client_delay_ms=2000),
}


class RobotSink:
    def __init__(self, host: str, port: int) -> None:
        self.host = host
        self.port = port
        self._events: list[dict[str, Any]] = []
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._loop, name="harness-robot-sink", daemon=True)

    def start(self) -> None:
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        self._thread.join(timeout=2)

    def events_since(self, idx: int) -> list[dict[str, Any]]:
        with self._lock:
            return list(self._events[idx:])

    def count(self) -> int:
        with self._lock:
            return len(self._events)

    def _loop(self) -> None:
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind((self.host, self.port))
        srv.listen(32)
        srv.settimeout(0.5)
        try:
            while not self._stop.is_set():
                try:
                    conn, _ = srv.accept()
                except socket.timeout:
                    continue
                with conn:
                    conn.settimeout(0.5)
                    chunks: list[bytes] = []
                    while True:
                        try:
                            chunk = conn.recv(65535)
                        except socket.timeout:
                            break
                        if not chunk:
                            break
                        chunks.append(chunk)
                if not chunks:
                    continue
                now_ms = int(time.time() * 1000)
                data = b"".join(chunks).decode("utf-8", errors="ignore")
                parsed: list[dict[str, Any]] = []
                for line in data.splitlines():
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        payload = json.loads(line)
                    except json.JSONDecodeError:
                        continue
                    if isinstance(payload, dict):
                        payload["_recv_ms"] = now_ms
                        parsed.append(payload)
                if parsed:
                    with self._lock:
                        self._events.extend(parsed)
        finally:
            srv.close()


def pct(values: list[float], q: float) -> float:
    if not values:
        return 0.0
    if len(values) == 1:
        return float(values[0])
    s = sorted(values)
    k = (len(s) - 1) * q
    i = int(k)
    j = min(i + 1, len(s) - 1)
    if i == j:
        return float(s[i])
    return float(s[i] + (s[j] - s[i]) * (k - i))


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, payload: dict[str, Any]) -> None:
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def prepare_config(base_config: dict[str, Any], cameras: int, client_delay_ms: int, worker_mode: str) -> dict[str, Any]:
    cfg = json.loads(json.dumps(base_config))
    cfg["cameras"] = list(cfg.get("cameras", []))[:cameras]
    fanout = cfg.setdefault("fanout", {})
    client_http = fanout.setdefault("client_http", {})
    client_http["artificial_delay_ms"] = client_delay_ms
    integration = cfg.setdefault("integration", {})
    integration["worker_ipc_mode"] = worker_mode
    return cfg


def metrics_files(logs_dir: Path) -> list[Path]:
    return sorted(logs_dir.glob("camera-worker-metrics-cam-*.jsonl"))


def parse_new_metrics(paths: list[Path], line_offsets: dict[Path, int]) -> tuple[dict[int, dict[str, Any]], dict[Path, int]]:
    per_camera: dict[int, dict[str, Any]] = {}
    new_offsets = dict(line_offsets)
    for path in paths:
        old = line_offsets.get(path, 0)
        lines = path.read_text(encoding="utf-8", errors="ignore").splitlines()
        new_offsets[path] = len(lines)
        for line in lines[old:]:
            try:
                obj = json.loads(line)
            except json.JSONDecodeError:
                continue
            cam = int(obj.get("camera_id", -1))
            if cam < 0:
                continue
            prev = per_camera.get(cam)
            if prev is None or int(obj.get("ts_ns", 0)) >= int(prev.get("ts_ns", 0)):
                per_camera[cam] = obj
    return per_camera, new_offsets


def run_once(orchestrator_jar: Path, config_path: Path, cwd: Path, timeout_s: int) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["java", "-jar", str(orchestrator_jar), str(config_path)],
        cwd=str(cwd),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        timeout=timeout_s,
        check=False,
    )


def extract_restart_counts(output: str) -> dict[str, int]:
    worker = [int(x) for x in re.findall(r"worker supervisor camera restarts=(\d+)", output)]
    py = [int(x) for x in re.findall(r"python supervisor restarts=(\d+)", output)]
    geom = [int(x) for x in re.findall(r"geometry supervisor restarts=(\d+)", output)]
    return {
        "worker_total": sum(worker),
        "python_total": sum(py),
        "geometry_total": sum(geom),
        "all_total": sum(worker) + sum(py) + sum(geom),
    }


def count_timeouts(output: str) -> int:
    patterns = [
        r"command timeout",
        r"timeout after \d+ ms",
        r"Camera Timeout",
        r"TimeoutException",
    ]
    return sum(len(re.findall(p, output, flags=re.IGNORECASE)) for p in patterns)


def main() -> int:
    ap = argparse.ArgumentParser(description="Stage 9.4 load harness")
    ap.add_argument("--profile", choices=sorted(PROFILES.keys()), default="normal")
    ap.add_argument("--runs", type=int, default=None, help="Override runs count")
    ap.add_argument("--cameras", type=int, default=None, help="Override camera count")
    ap.add_argument("--worker-ipc-mode", choices=["stdio", "named_pipe"], default="stdio")
    ap.add_argument("--timeout-sec", type=int, default=40)
    ap.add_argument("--output-dir", default="logs/harness")
    args = ap.parse_args()

    repo = Path(__file__).resolve().parents[1]
    base_cfg_path = repo / "config" / "config.json"
    base_cfg = load_json(base_cfg_path)
    profile = PROFILES[args.profile]
    runs = args.runs if args.runs is not None else profile.runs
    cameras = args.cameras if args.cameras is not None else profile.cameras
    cameras = max(1, min(cameras, len(base_cfg.get("cameras", []))))
    out_dir = repo / args.output_dir
    out_dir.mkdir(parents=True, exist_ok=True)
    runtime_cfg_dir = repo / "config"

    orchestrator_jar = repo / "orchestrator-java" / "target" / "orchestrator-0.1.0-SNAPSHOT.jar"
    if not orchestrator_jar.exists():
        raise SystemExit(f"orchestrator jar not found: {orchestrator_jar}")

    cfg = prepare_config(base_cfg, cameras=cameras, client_delay_ms=profile.client_delay_ms, worker_mode=args.worker_ipc_mode)
    ts = time.strftime("%Y%m%d-%H%M%S")
    config_run_path = runtime_cfg_dir / f"harness-config-{ts}.json"
    config_saved_path = out_dir / f"harness-config-{ts}.json"
    write_json(config_run_path, cfg)
    write_json(config_saved_path, cfg)

    robot = RobotSink(host=str(cfg["fanout"]["robot_tcp"].get("host", "127.0.0.1")), port=int(cfg["fanout"]["robot_tcp"].get("port", 9999)))
    robot.start()

    logs_dir = repo / "logs"
    metric_paths = metrics_files(logs_dir)
    line_offsets: dict[Path, int] = {}
    for p in metric_paths:
        line_offsets[p] = len(p.read_text(encoding="utf-8", errors="ignore").splitlines())

    run_rows: list[dict[str, Any]] = []
    all_robot_latencies: list[float] = []
    total_timeouts = 0
    total_restarts = {"worker_total": 0, "python_total": 0, "geometry_total": 0, "all_total": 0}

    try:
        for run_id in range(1, runs + 1):
            before_idx = robot.count()
            t0 = time.time()
            proc = run_once(orchestrator_jar, config_run_path, repo, args.timeout_sec)
            elapsed_ms = (time.time() - t0) * 1000.0
            time.sleep(0.25)

            metric_paths = metrics_files(logs_dir)
            for p in metric_paths:
                if p not in line_offsets:
                    line_offsets[p] = 0
            per_cam, line_offsets = parse_new_metrics(metric_paths, line_offsets)

            events = robot.events_since(before_idx)
            latencies = []
            ok_count = 0
            nok_count = 0
            for e in events:
                ts_ms = e.get("timestamp_ms")
                recv_ms = e.get("_recv_ms")
                if isinstance(ts_ms, int) and isinstance(recv_ms, int):
                    latencies.append(float(recv_ms - ts_ms))
                action = str(e.get("action", "")).upper()
                if action == "ACCEPT":
                    ok_count += 1
                elif action == "REJECT":
                    nok_count += 1
            all_robot_latencies.extend(latencies)

            cap_total = 0
            cap_dropped = 0
            cap_latency_avg_ms_vals: list[float] = []
            for obj in per_cam.values():
                cap_total += int(obj.get("capture_total", 0))
                cap_dropped += int(obj.get("capture_dropped", 0))
                avg_ns = int(obj.get("latency_ns_avg", 0))
                if avg_ns > 0:
                    cap_latency_avg_ms_vals.append(avg_ns / 1_000_000.0)

            restarts = extract_restart_counts(proc.stdout)
            timeouts = count_timeouts(proc.stdout)
            total_timeouts += timeouts
            for k in total_restarts:
                total_restarts[k] += restarts[k]

            run_rows.append(
                {
                    "run_id": run_id,
                    "exit_code": proc.returncode,
                    "elapsed_ms": round(elapsed_ms, 2),
                    "robot_msgs": len(events),
                    "robot_ok": ok_count,
                    "robot_nok": nok_count,
                    "robot_latency_ms_p50": round(pct(latencies, 0.5), 3) if latencies else 0.0,
                    "robot_latency_ms_p95": round(pct(latencies, 0.95), 3) if latencies else 0.0,
                    "capture_total": cap_total,
                    "capture_dropped": cap_dropped,
                    "capture_drop_rate": round((cap_dropped / cap_total) if cap_total > 0 else 0.0, 6),
                    "capture_latency_avg_ms_mean": round(statistics.fmean(cap_latency_avg_ms_vals), 3) if cap_latency_avg_ms_vals else 0.0,
                    "timeouts_detected": timeouts,
                    "worker_restarts": restarts["worker_total"],
                    "python_restarts": restarts["python_total"],
                    "geometry_restarts": restarts["geometry_total"],
                    "all_restarts": restarts["all_total"],
                }
            )
            (out_dir / f"load-harness-{ts}-run-{run_id}.log").write_text(proc.stdout, encoding="utf-8")
    finally:
        robot.stop()
        try:
            config_run_path.unlink(missing_ok=True)
        except Exception:
            pass

    capture_total_all = sum(int(r["capture_total"]) for r in run_rows)
    capture_dropped_all = sum(int(r["capture_dropped"]) for r in run_rows)
    summary = {
        "profile": args.profile,
        "runs": runs,
        "cameras": cameras,
        "worker_ipc_mode": args.worker_ipc_mode,
        "generated_at": time.strftime("%Y-%m-%d %H:%M:%S"),
        "robot_latency_ms": {
            "count": len(all_robot_latencies),
            "p50": round(pct(all_robot_latencies, 0.5), 3) if all_robot_latencies else 0.0,
            "p95": round(pct(all_robot_latencies, 0.95), 3) if all_robot_latencies else 0.0,
            "p99": round(pct(all_robot_latencies, 0.99), 3) if all_robot_latencies else 0.0,
            "max": round(max(all_robot_latencies), 3) if all_robot_latencies else 0.0,
        },
        "capture": {
            "total": capture_total_all,
            "dropped": capture_dropped_all,
            "drop_rate": round((capture_dropped_all / capture_total_all) if capture_total_all > 0 else 0.0, 6),
        },
        "timeouts_detected": total_timeouts,
        "restart_counts": total_restarts,
        "non_zero_exit_runs": sum(1 for r in run_rows if int(r["exit_code"]) != 0),
    }

    # SLA gates from docs/STAGE-9.3-SLA.md (adapted to currently available harness metrics).
    sla_fail_reasons: list[str] = []
    robot_p95 = float(summary["robot_latency_ms"]["p95"])
    if robot_p95 > 100.0:
        sla_fail_reasons.append(f"robot_latency_ms.p95={robot_p95} > 100")
    drop_rate = float(summary["capture"]["drop_rate"])
    if drop_rate > 0.005:
        sla_fail_reasons.append(f"capture.drop_rate={drop_rate} > 0.005")
    timeouts = int(summary["timeouts_detected"])
    total_commands_approx = max(1, sum(int(r["robot_msgs"]) for r in run_rows))
    timeout_rate = timeouts / total_commands_approx
    if timeout_rate > 0.001:
        sla_fail_reasons.append(f"timeout_rate={timeout_rate:.6f} > 0.001")
    restarts_total = int(summary["restart_counts"]["all_total"])
    # Per-hour normalization for variable test durations.
    total_elapsed_h = max(1e-6, sum(float(r["elapsed_ms"]) for r in run_rows) / 1000.0 / 3600.0)
    restarts_per_hour = restarts_total / total_elapsed_h
    if restarts_per_hour > 6.0:
        sla_fail_reasons.append(f"restarts_per_hour={restarts_per_hour:.3f} > 6")
    non_zero_exit_runs = int(summary["non_zero_exit_runs"])
    if non_zero_exit_runs > 0:
        sla_fail_reasons.append(f"non_zero_exit_runs={non_zero_exit_runs} > 0")

    summary["timeout_rate"] = round(timeout_rate, 6)
    summary["restarts_per_hour"] = round(restarts_per_hour, 3)
    summary["sla_decision"] = "READY" if not sla_fail_reasons else "NOT_READY"
    summary["sla_fail_reasons"] = sla_fail_reasons

    report_json = {
        "summary": summary,
        "runs": run_rows,
        "config_path": str(config_saved_path),
    }
    out_json = out_dir / f"load-harness-{args.profile}-{ts}.json"
    out_csv = out_dir / f"load-harness-{args.profile}-{ts}.csv"
    write_json(out_json, report_json)

    with out_csv.open("w", newline="", encoding="utf-8") as f:
        if run_rows:
            writer = csv.DictWriter(f, fieldnames=list(run_rows[0].keys()))
            writer.writeheader()
            writer.writerows(run_rows)
        else:
            f.write("run_id\n")

    print(f"OK: load harness finished profile={args.profile} runs={runs} cameras={cameras}")
    print(f"JSON report: {out_json}")
    print(f"CSV report: {out_csv}")
    print(
        "Summary: "
        f"robot_p95={summary['robot_latency_ms']['p95']}ms "
        f"drop_rate={summary['capture']['drop_rate']} "
        f"timeouts={summary['timeouts_detected']} "
        f"restarts={summary['restart_counts']['all_total']} "
        f"sla={summary['sla_decision']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
