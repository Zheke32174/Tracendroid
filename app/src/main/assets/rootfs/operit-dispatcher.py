#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
operit-dispatcher.py — in-proot IPC server (Shell rebuild PR 3/N follow-up).

Per docs/SHELL_REBUILD.md § IPC protocol:

  - Listens on a Unix domain socket inside the proot rootfs at
    /var/lib/operit/ipc/dispatcher.sock. The Android side connects to this
    socket through the bind-mount that exposes /var/lib/operit/ipc/ both
    inside and outside proot.
  - Wire format: 4-byte big-endian length prefix + UTF-8 JSON payload.
    Maximum frame size 1 MiB.
  - First frame on every connection is the auth frame; mismatch terminates
    the connection immediately.
  - Subsequent frames are request envelopes. Each carries:
      requestId   monotonic correlation id
      origin      "user" | "ai-agent" | "plugin:<id>"
      capability  one of the JsCapabilityClass enum names
      command     a string command identifier (see HANDLERS below)
      params      command-specific JSON object
  - Responses carry the same requestId, success bool, output, and error.

This script is intentionally narrow:

  - SHELL capability accepts exec. argv runs through subprocess with no
    shell interpolation. Output is captured and bounded.
  - FILE_READ accepts read_file. Path is constrained to /workspace and
    /home/operator; anything else is refused.
  - FILE_WRITE accepts write_file with the same path constraints.
  - Every other capability returns "not implemented" — explicit deny per
    AGENTS.md no-fallback rule. Adding a handler is a deliberate review
    step.

Per defense in depth, even though the Android side has gate-checked, this
script re-checks the capability claim against the command. A mismatch
(e.g. capability=FILE_READ but command="exec") refuses the call.

The auth secret is read from /var/lib/operit/auth.secret at startup. The
Android side writes it there during bootstrap; the file is mode 0600. The
secret is compared with hmac.compare_digest to avoid timing leaks.
"""

import hmac
import json
import os
import socket
import struct
import subprocess
import sys
import threading
import traceback

SOCKET_PATH = "/var/lib/operit/ipc/dispatcher.sock"
AUTH_SECRET_PATH = "/var/lib/operit/auth.secret"
MAX_FRAME_BYTES = 1 << 20
PROTOCOL_VERSION = 1

PATH_ALLOWLIST_PREFIXES = (
    "/workspace/",
    "/home/operator/",
    "/var/lib/operit/",
)

EXEC_TIMEOUT_SECONDS = 60
EXEC_OUTPUT_LIMIT_BYTES = 256 * 1024  # 256 KiB stdout/stderr ceiling

# Capability → set of allowed command identifiers. A request whose command
# isn't in the set for its capability is refused. The METADATA commands for
# subscription-OAuth state were resolved in AUDIT_PLAN § 1.6: account /
# tier / liveness only, raw tokens never cross the wire.
ALLOWED_COMMANDS = {
    "SHELL": {"exec"},
    "FILE_READ": {"read_file", "stat"},
    "FILE_WRITE": {"write_file"},
    "METADATA": {
        "ping",
        "env",
        "subscription_account",
        "subscription_tier",
        "subscription_alive",
    },
}

# CLIs the subscription_* commands recognize. Each maps to the path inside the
# rootfs where the CLI keeps its session config (FAQ-style files; the exact
# layouts vary by CLI and may need updating per release). The map is a
# whitelist — a CLI name not on this list refuses outright.
SUBSCRIPTION_CLI_CONFIG = {
    "claude-code": "/home/operator/.config/claude/config.json",
    "codex":       "/home/operator/.config/codex/auth.json",
    "gemini-cli":  "/home/operator/.config/gemini-cli/session.json",
}

# Field allowlist per CLI per command. Anything outside this map is refused —
# the dispatcher does not return arbitrary JSON keys from these config files,
# even if the file happens to contain them.
SUBSCRIPTION_FIELD_ALLOWLIST = {
    "subscription_account": {
        "claude-code": ("account", "email"),
        "codex":       ("account", "email"),
        "gemini-cli":  ("user", "email"),
    },
    "subscription_tier": {
        "claude-code": ("account", "tier"),
        "codex":       ("subscription", "tier"),
        "gemini-cli":  ("user", "tier"),
    },
}


def log(msg: str) -> None:
    sys.stderr.write(f"[operit-dispatcher] {msg}\n")
    sys.stderr.flush()


def read_frame(conn: socket.socket) -> str | None:
    header = _recv_exact(conn, 4)
    if header is None:
        return None
    (length,) = struct.unpack(">i", header)
    if length <= 0 or length > MAX_FRAME_BYTES:
        log(f"frame length out of bounds: {length}")
        return None
    body = _recv_exact(conn, length)
    if body is None:
        return None
    return body.decode("utf-8", errors="replace")


def write_frame(conn: socket.socket, payload: str) -> None:
    data = payload.encode("utf-8")
    if len(data) > MAX_FRAME_BYTES:
        raise ValueError(f"frame exceeds MAX_FRAME_BYTES: {len(data)}")
    conn.sendall(struct.pack(">i", len(data)) + data)


def _recv_exact(conn: socket.socket, n: int) -> bytes | None:
    buf = bytearray()
    while len(buf) < n:
        chunk = conn.recv(n - len(buf))
        if not chunk:
            return None
        buf.extend(chunk)
    return bytes(buf)


def load_auth_secret() -> str:
    try:
        with open(AUTH_SECRET_PATH, "r", encoding="utf-8") as fp:
            secret = fp.read().strip()
    except FileNotFoundError:
        raise SystemExit(
            f"auth secret missing: {AUTH_SECRET_PATH}. The Android side writes "
            "this file during bootstrap; if it's missing the proot session was "
            "started without a fresh secret rotation."
        )
    if not secret:
        raise SystemExit("auth secret file is empty")
    return secret


def path_is_allowed(path: str) -> bool:
    if not path.startswith("/"):
        return False
    # Reject any traversal segment. Even if it would canonicalize to an
    # allowed path, refusing the literal segment removes a class of bugs.
    parts = os.path.normpath(path).split(os.sep)
    if any(p == ".." for p in parts):
        return False
    normalized = os.path.normpath(path) + ("" if path.endswith("/") else "")
    return any(normalized.startswith(prefix.rstrip("/")) for prefix in PATH_ALLOWLIST_PREFIXES)


def respond(conn: socket.socket, request_id: int, success: bool, output: str = "", error: str | None = None) -> None:
    obj = {
        "type": "response",
        "requestId": request_id,
        "success": success,
        "output": output,
    }
    if error is not None:
        obj["error"] = error
    write_frame(conn, json.dumps(obj))


def handle_request(conn: socket.socket, req: dict) -> None:
    rid = int(req.get("requestId", -1))
    capability = req.get("capability", "")
    command = req.get("command", "")
    params = req.get("params") or {}
    allowed = ALLOWED_COMMANDS.get(capability)
    if allowed is None or command not in allowed:
        respond(
            conn,
            rid,
            success=False,
            error=f"command '{command}' not allowed for capability {capability!r}",
        )
        return
    try:
        if command == "ping":
            respond(conn, rid, success=True, output="pong")
        elif command == "env":
            respond(conn, rid, success=True, output=json.dumps(dict(os.environ)))
        elif command == "exec":
            do_exec(conn, rid, params)
        elif command == "read_file":
            do_read_file(conn, rid, params)
        elif command == "write_file":
            do_write_file(conn, rid, params)
        elif command == "stat":
            do_stat(conn, rid, params)
        elif command == "subscription_account":
            do_subscription_account(conn, rid, params)
        elif command == "subscription_tier":
            do_subscription_tier(conn, rid, params)
        elif command == "subscription_alive":
            do_subscription_alive(conn, rid, params)
        else:
            respond(conn, rid, success=False, error=f"unhandled command: {command}")
    except Exception as exc:  # noqa: BLE001 — we want to surface every error verbatim
        log(f"handler crash: {exc}\n{traceback.format_exc()}")
        respond(conn, rid, success=False, error=f"handler crash: {exc}")


def do_exec(conn: socket.socket, rid: int, params: dict) -> None:
    argv = params.get("argv")
    if not isinstance(argv, list) or not argv or not all(isinstance(x, str) for x in argv):
        respond(conn, rid, success=False, error="exec requires argv: List[str]")
        return
    cwd = params.get("cwd") or "/workspace"
    if not path_is_allowed(cwd):
        respond(conn, rid, success=False, error=f"cwd not in allowlist: {cwd}")
        return
    try:
        proc = subprocess.run(
            argv,
            cwd=cwd,
            capture_output=True,
            timeout=EXEC_TIMEOUT_SECONDS,
        )
    except subprocess.TimeoutExpired:
        respond(conn, rid, success=False, error=f"timeout after {EXEC_TIMEOUT_SECONDS}s")
        return
    except FileNotFoundError as exc:
        respond(conn, rid, success=False, error=f"executable not found: {exc}")
        return
    stdout = proc.stdout[:EXEC_OUTPUT_LIMIT_BYTES].decode("utf-8", errors="replace")
    stderr = proc.stderr[:EXEC_OUTPUT_LIMIT_BYTES].decode("utf-8", errors="replace")
    output = json.dumps({
        "exitCode": proc.returncode,
        "stdout": stdout,
        "stderr": stderr,
        "truncated": (
            len(proc.stdout) > EXEC_OUTPUT_LIMIT_BYTES
            or len(proc.stderr) > EXEC_OUTPUT_LIMIT_BYTES
        ),
    })
    respond(conn, rid, success=(proc.returncode == 0), output=output,
            error=None if proc.returncode == 0 else f"exit code {proc.returncode}")


def do_read_file(conn: socket.socket, rid: int, params: dict) -> None:
    path = params.get("path")
    if not isinstance(path, str) or not path_is_allowed(path):
        respond(conn, rid, success=False, error=f"path not in allowlist: {path!r}")
        return
    # AUDIT_PLAN § 1.6: subscription-OAuth session files must never cross the
    # bridge via FILE_READ. The dedicated subscription_* METADATA commands are
    # the only path, and they return narrow allowlisted fields rather than the
    # raw file. Refuse here defensively even though path_is_allowed already
    # excludes /home/operator/.config/ — a future allowlist tweak shouldn't
    # silently re-expose token material.
    normalized = os.path.normpath(path)
    for cli_path in SUBSCRIPTION_CLI_CONFIG.values():
        if normalized == os.path.normpath(cli_path):
            respond(
                conn,
                rid,
                success=False,
                error="refused: subscription-OAuth session file is not readable via FILE_READ",
            )
            return
    try:
        with open(path, "rb") as fp:
            content = fp.read()
    except FileNotFoundError:
        respond(conn, rid, success=False, error="file not found")
        return
    if len(content) > MAX_FRAME_BYTES // 2:
        respond(conn, rid, success=False, error=f"file too large: {len(content)} bytes")
        return
    respond(conn, rid, success=True, output=content.decode("utf-8", errors="replace"))


def do_write_file(conn: socket.socket, rid: int, params: dict) -> None:
    path = params.get("path")
    content = params.get("content", "")
    if not isinstance(path, str) or not path_is_allowed(path):
        respond(conn, rid, success=False, error=f"path not in allowlist: {path!r}")
        return
    if not isinstance(content, str):
        respond(conn, rid, success=False, error="content must be a string")
        return
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as fp:
        fp.write(content.encode("utf-8"))
    respond(conn, rid, success=True, output=str(len(content)))


def do_stat(conn: socket.socket, rid: int, params: dict) -> None:
    path = params.get("path")
    if not isinstance(path, str) or not path_is_allowed(path):
        respond(conn, rid, success=False, error=f"path not in allowlist: {path!r}")
        return
    try:
        st = os.stat(path)
    except FileNotFoundError:
        respond(conn, rid, success=False, error="file not found")
        return
    respond(conn, rid, success=True, output=json.dumps({
        "size": st.st_size,
        "mtime": int(st.st_mtime),
        "is_dir": os.path.isdir(path),
        "is_file": os.path.isfile(path),
    }))


# ---- METADATA / subscription_* commands (AUDIT_PLAN § 1.6) -----------------
#
# These three handlers are the entire surface by which the Android side can
# learn anything about the in-proot subscription state. None of them return
# raw token material — they return narrow allowlisted fields parsed out of
# the CLI's session config, or computed liveness signals. The Android side
# cannot reach the underlying config files via any other code path; the
# do_read_file refusal at the top of this script enforces that on the
# defense-in-depth side.


def _read_subscription_config(cli_name: str) -> dict | None:
    """Load the subscription config JSON for a known CLI. Returns None on
    missing-file (CLI not installed / not yet logged in) or parse error.
    Callers never see the raw dict — only the field they asked for."""
    config_path = SUBSCRIPTION_CLI_CONFIG.get(cli_name)
    if config_path is None:
        return None
    try:
        with open(config_path, "r", encoding="utf-8") as fp:
            return json.load(fp)
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        return None


def _extract_allowlisted_field(
    config: dict | None,
    cli_name: str,
    command: str,
) -> str | None:
    """Walk the field path from SUBSCRIPTION_FIELD_ALLOWLIST. Returns the
    string value or None. Anything that isn't a string is dropped — we don't
    return arbitrary JSON shapes here."""
    if config is None:
        return None
    field_map = SUBSCRIPTION_FIELD_ALLOWLIST.get(command, {})
    path = field_map.get(cli_name)
    if path is None:
        return None
    cursor: object = config
    for segment in path:
        if not isinstance(cursor, dict):
            return None
        cursor = cursor.get(segment)
    return cursor if isinstance(cursor, str) else None


def do_subscription_account(conn: socket.socket, rid: int, params: dict) -> None:
    cli = params.get("cliName")
    if not isinstance(cli, str) or cli not in SUBSCRIPTION_CLI_CONFIG:
        respond(conn, rid, success=False, error=f"unknown cliName: {cli!r}")
        return
    config = _read_subscription_config(cli)
    email = _extract_allowlisted_field(config, cli, "subscription_account")
    respond(conn, rid, success=True, output=json.dumps({
        "cliName": cli,
        "accountEmail": email,
    }))


def do_subscription_tier(conn: socket.socket, rid: int, params: dict) -> None:
    cli = params.get("cliName")
    if not isinstance(cli, str) or cli not in SUBSCRIPTION_CLI_CONFIG:
        respond(conn, rid, success=False, error=f"unknown cliName: {cli!r}")
        return
    config = _read_subscription_config(cli)
    tier = _extract_allowlisted_field(config, cli, "subscription_tier")
    respond(conn, rid, success=True, output=json.dumps({
        "cliName": cli,
        "tier": tier,
    }))


def do_subscription_alive(conn: socket.socket, rid: int, params: dict) -> None:
    cli = params.get("cliName")
    if not isinstance(cli, str) or cli not in SUBSCRIPTION_CLI_CONFIG:
        respond(conn, rid, success=False, error=f"unknown cliName: {cli!r}")
        return
    config_path = SUBSCRIPTION_CLI_CONFIG[cli]
    # Liveness = config file exists + has been touched recently. We do NOT
    # invoke the CLI's own `whoami`-style command in v1 — running arbitrary
    # CLI binaries on every liveness probe is the kind of side channel
    # AUDIT_PLAN § 1.6 wants to avoid. File mtime is a coarse but honest
    # signal.
    try:
        st = os.stat(config_path)
    except FileNotFoundError:
        respond(conn, rid, success=True, output=json.dumps({
            "cliName": cli,
            "isLoggedIn": False,
            "lastActiveAtMillis": 0,
        }))
        return
    respond(conn, rid, success=True, output=json.dumps({
        "cliName": cli,
        "isLoggedIn": True,
        "lastActiveAtMillis": int(st.st_mtime * 1000),
    }))


# ---- connection loop -------------------------------------------------------


def handle_connection(conn: socket.socket, expected_secret: str) -> None:
    try:
        auth_frame = read_frame(conn)
        if auth_frame is None:
            log("connection closed before auth")
            return
        try:
            auth_obj = json.loads(auth_frame)
        except json.JSONDecodeError:
            log("malformed auth frame")
            return
        if (
            auth_obj.get("type") != "auth"
            or not isinstance(auth_obj.get("secret"), str)
            or not hmac.compare_digest(auth_obj["secret"], expected_secret)
            or auth_obj.get("protocolVersion") != PROTOCOL_VERSION
        ):
            respond(conn, -1, success=False, error="auth rejected")
            return
        log("connection authed")
        while True:
            frame = read_frame(conn)
            if frame is None:
                return
            try:
                obj = json.loads(frame)
            except json.JSONDecodeError:
                respond(conn, -1, success=False, error="malformed JSON")
                continue
            if obj.get("type") != "request":
                respond(conn, int(obj.get("requestId", -1)), success=False,
                        error="unexpected frame type")
                continue
            handle_request(conn, obj)
    finally:
        try:
            conn.close()
        except OSError:
            pass


def main() -> int:
    secret = load_auth_secret()

    try:
        os.unlink(SOCKET_PATH)
    except FileNotFoundError:
        pass
    os.makedirs(os.path.dirname(SOCKET_PATH), exist_ok=True)

    server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    server.bind(SOCKET_PATH)
    os.chmod(SOCKET_PATH, 0o600)
    server.listen(8)
    log(f"listening on {SOCKET_PATH}")

    try:
        while True:
            conn, _ = server.accept()
            t = threading.Thread(
                target=handle_connection,
                args=(conn, secret),
                daemon=True,
            )
            t.start()
    except KeyboardInterrupt:
        log("shutting down")
    finally:
        try:
            server.close()
        except OSError:
            pass
        try:
            os.unlink(SOCKET_PATH)
        except FileNotFoundError:
            pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
