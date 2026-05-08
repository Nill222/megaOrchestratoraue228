#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import select
import struct
import subprocess
from pathlib import Path

MAGIC = b"IMLB"
VERSION = 1
MSG_COMMAND = 1
MSG_RESPONSE = 2
MSG_ERROR = 3


def _send(proc: subprocess.Popen, header: dict) -> None:
    hb = json.dumps(header, separators=(",", ":")).encode("utf-8")
    proc.stdin.write(MAGIC + bytes([VERSION, MSG_COMMAND, 0, 0]) + struct.pack(">I", len(hb)) + struct.pack(">I", 0) + hb)
    proc.stdin.flush()


def _read_exact(stream, n: int, timeout_s: float = 2.0) -> bytes:
    buf = bytearray()
    fd = stream.fileno()
    while len(buf) < n:
        r, _, _ = select.select([fd], [], [], timeout_s)
        if not r:
            raise TimeoutError(f"read timeout while waiting for {n} bytes")
        chunk = os.read(fd, n - len(buf))
        if not chunk:
            raise RuntimeError("unexpected EOF")
        buf.extend(chunk)
    return bytes(buf)


def _recv(proc: subprocess.Popen) -> tuple[int, dict]:
    p = _read_exact(proc.stdout, 16)
    t = p[5]
    hl = struct.unpack(">I", p[8:12])[0]
    pl = struct.unpack(">I", p[12:16])[0]
    h = json.loads(_read_exact(proc.stdout, hl).decode("utf-8"))
    if pl:
        _read_exact(proc.stdout, pl)
    return t, h


def _run_fault_ops(repo: Path) -> None:
    worker = subprocess.Popen(
        [str(repo / "camera-worker" / "build" / "camera_worker"), str(repo / "config" / "config.json"), "0", "--binary-stdio"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        cwd=str(repo),
    )
    _send(worker, {"op": "inject_timeout_ms", "timeout_ms": 50})
    t, h = _recv(worker)
    if t != MSG_RESPONSE or h.get("status") != "timeout_injected":
        raise AssertionError("worker timeout fault failed")
    _send(worker, {"op": "inject_broken_response"})
    # Expect protocol break: next recv should fail.
    try:
        _recv(worker)
        raise AssertionError("worker broken response should break protocol")
    except Exception:
        pass
    worker.stdin.close()
    worker.stdout.close()
    worker.wait(timeout=5)

    env = os.environ.copy()
    env["PYTHONPATH"] = str(repo / "python-detectors" / "src")
    py = str(repo / "python-detectors" / ".venv" / "bin" / "python")
    if not os.path.exists(py):
        py = "python3"
    runner = subprocess.Popen(
        [py, "-m", "iml_detectors.runner"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        cwd=str(repo / "python-detectors"),
        env=env,
    )
    _send(runner, {"op": "inject_timeout_ms", "timeout_ms": 50})
    t, h = _recv(runner)
    if t != MSG_RESPONSE or h.get("status") != "timeout_injected":
        raise AssertionError("python timeout fault failed")
    _send(runner, {"op": "inject_exit"})
    runner.wait(timeout=5)
    if runner.returncode == 0:
        raise AssertionError("python inject_exit failed")


def _run_geometry_fault_ops(repo: Path) -> None:
    candidates = [
        repo / "java-geometry-service" / "target" / "java-geometry-service-0.1.0-SNAPSHOT.jar",
        repo / "java-geometry-service" / "target" / "geometry-service-0.1.0-SNAPSHOT.jar",
    ]
    jar = next((p for p in candidates if p.exists()), None)
    if jar is None:
        print("SKIP: geometry fault ops (jar not found)")
        return

    geom = subprocess.Popen(
        ["java", "-cp", str(jar), "com.example.iml.service.GeometryRunnerMain"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        cwd=str(repo),
    )
    _send(geom, {"op": "inject_timeout_ms", "timeout_ms": 50})
    t, h = _recv(geom)
    if t != MSG_RESPONSE or h.get("status") != "timeout_injected":
        raise AssertionError("geometry timeout fault failed")
    _send(geom, {"op": "inject_exit"})
    geom.wait(timeout=5)
    if geom.returncode == 0:
        raise AssertionError("geometry inject_exit failed")


def main() -> int:
    repo = Path(__file__).resolve().parents[1]
    _run_fault_ops(repo)
    _run_geometry_fault_ops(repo)
    print("OK: fault-injection suite passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
