#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import struct
import subprocess
import tempfile
from pathlib import Path

MAGIC = b"IMLB"
VERSION = 1
MSG_COMMAND = 1
MSG_RESPONSE = 2


def _send(proc: subprocess.Popen, header: dict, payload: bytes = b"") -> None:
    h = json.dumps(header, separators=(",", ":")).encode("utf-8")
    proc.stdin.write(MAGIC + bytes([VERSION, MSG_COMMAND, 0, 0]) + struct.pack(">I", len(h)) + struct.pack(">I", len(payload)))
    proc.stdin.write(h)
    if payload:
        proc.stdin.write(payload)
    proc.stdin.flush()


def _recv(proc: subprocess.Popen) -> tuple[int, dict, bytes]:
    prefix = proc.stdout.read(16)
    if len(prefix) < 16:
        raise RuntimeError("short prefix")
    msg_type = prefix[5]
    header_len = struct.unpack(">I", prefix[8:12])[0]
    payload_len = struct.unpack(">I", prefix[12:16])[0]
    header_raw = proc.stdout.read(header_len)
    if len(header_raw) != header_len:
        raise RuntimeError("short header")
    payload = proc.stdout.read(payload_len) if payload_len else b""
    if len(payload) != payload_len:
        raise RuntimeError("short payload")
    return msg_type, json.loads(header_raw.decode("utf-8")), payload


def _assert_required_fields(obj: dict, required: list[str], label: str) -> None:
    missing = [k for k in required if k not in obj]
    if missing:
        raise AssertionError(f"{label}: missing fields {missing}")


def _golden_frame_smoke() -> None:
    header = {"op": "health", "camera_id": 1}
    payload = b"abc"
    header_bytes = json.dumps(header, separators=(",", ":")).encode("utf-8")
    frame = MAGIC + bytes([VERSION, MSG_COMMAND, 0, 0]) + struct.pack(">I", len(header_bytes)) + struct.pack(">I", len(payload)) + header_bytes + payload
    assert frame[:4] == MAGIC, "golden: bad magic"
    assert frame[4] == VERSION, "golden: bad version"
    assert frame[5] == MSG_COMMAND, "golden: bad type"
    assert struct.unpack(">I", frame[8:12])[0] == len(header_bytes), "golden: bad header len"
    assert struct.unpack(">I", frame[12:16])[0] == len(payload), "golden: bad payload len"


def _run_stdio(worker_bin: Path, config_json: Path) -> None:
    proc = subprocess.Popen(
        [str(worker_bin), str(config_json), "0", "--binary-stdio"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        cwd=str(config_json.parent.parent),
    )
    try:
        _send(proc, {"op": "health"})
        msg_type, health, _ = _recv(proc)
        assert msg_type == MSG_RESPONSE, "stdio health not response"
        _assert_required_fields(
            health,
            [
                "status",
                "service",
                "camera_id",
                "ring_slots",
                "capture_total",
                "capture_dropped",
                "capture_source",
                "capture_backend_ready",
            ],
            "stdio health",
        )
        _send(proc, {"op": "capture"})
        msg_type, capture, _ = _recv(proc)
        assert msg_type == MSG_RESPONSE, "stdio capture not response"
        _assert_required_fields(
            capture,
            [
                "camera_id",
                "frame_id",
                "slot_index",
                "width",
                "height",
                "stride",
                "format",
                "timestamp_ns",
                "shm_name",
                "shm_offset",
                "frame_bytes",
                "ring_slots",
                "detector_result",
            ],
            "stdio capture",
        )
        _send(proc, {"op": "stop"})
        _recv(proc)
    finally:
        proc.stdin.close()
        proc.wait(timeout=5)


def _run_named_pipe(worker_bin: Path, config_json: Path) -> None:
    with tempfile.TemporaryDirectory(prefix="iml-pipe-") as tmp:
        base = os.path.join(tmp, "camera-0")
        proc = subprocess.Popen(
            [str(worker_bin), str(config_json), "0", "--named-pipe", base],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            cwd=str(config_json.parent.parent),
        )
        cmd_path = base + ".cmd"
        resp_path = base + ".resp"
        for _ in range(200):
            if os.path.exists(cmd_path) and os.path.exists(resp_path):
                break
            import time

            time.sleep(0.01)

        cmd = open(cmd_path, "wb", buffering=0)
        resp = open(resp_path, "rb", buffering=0)
        try:
            # small local wrappers around the same protocol
            def send(h: dict) -> None:
                hb = json.dumps(h, separators=(",", ":")).encode("utf-8")
                cmd.write(MAGIC + bytes([VERSION, MSG_COMMAND, 0, 0]) + struct.pack(">I", len(hb)) + struct.pack(">I", 0) + hb)

            def recv() -> tuple[int, dict]:
                p = resp.read(16)
                if len(p) < 16:
                    raise RuntimeError("named pipe short prefix")
                t = p[5]
                hl = struct.unpack(">I", p[8:12])[0]
                pl = struct.unpack(">I", p[12:16])[0]
                h = json.loads(resp.read(hl).decode("utf-8"))
                if pl:
                    resp.read(pl)
                return t, h

            send({"op": "health"})
            t, health = recv()
            assert t == MSG_RESPONSE, "named_pipe health not response"
            _assert_required_fields(health, ["status", "service", "camera_id", "capture_source"], "named_pipe health")
            send({"op": "capture"})
            t, capture = recv()
            assert t == MSG_RESPONSE, "named_pipe capture not response"
            _assert_required_fields(capture, ["frame_id", "shm_name", "shm_offset"], "named_pipe capture")
            send({"op": "stop"})
            recv()
        finally:
            cmd.close()
            resp.close()
            proc.wait(timeout=5)


def main() -> int:
    repo = Path(__file__).resolve().parents[1]
    worker_bin = repo / "camera-worker" / "build" / "camera_worker"
    config_json = repo / "config" / "config.json"
    if not worker_bin.exists():
        raise SystemExit(f"worker binary not found: {worker_bin}")

    _golden_frame_smoke()
    _run_stdio(worker_bin, config_json)
    _run_named_pipe(worker_bin, config_json)
    print("OK: protocol regression suite passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
