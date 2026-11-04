#!/usr/bin/env python3
"""
keybridge.py — a localhost control-plane bridge that cannot wedge.

Replaces the single-threaded http.server.HTTPServer on 127.0.0.1:20261.

Three independent defects are fixed here; all three are needed:

  1. ACCEPT STARVATION (the observed failure).
     A single-threaded HTTPServer parks inside one handler. The kernel keeps
     completing handshakes -- which is why the browser side showed ESTABLISHED
     sockets nobody was reading -- until the listen backlog fills. After that
     SYNs are dropped, so new connections sit in SYN_SENT and fail at connect(),
     not at response. socketserver's default request_queue_size is 5, so a
     single 30 s handler costs you every keypress after the 5th.
     Fix: ThreadingHTTPServer + a much larger backlog.

  2. SLOW WORK ON THE REQUEST PATH.
     Threading alone only raises the ceiling; a handler that blocks for 30 s
     still holds a thread, and with HTTP/1.1 keep-alive each idle browser
     connection holds one too. Fix: handlers never block. Keypresses are
     enqueued and acked immediately; anything slow (the reset path's subprocess)
     goes to a single-slot worker with a hard timeout.

  3. NO VISIBILITY.
     GET /health exposes backlog depth, live threads and the running job, so the
     next wedge is diagnosable from the browser instead of from netstat.

Chrome note: since Chrome 142, a public https page reaching 127.0.0.1 is a
Local Network Access request and is permission-gated. Do the fetch from the MV3
service worker with "host_permissions": ["http://127.0.0.1:20261/*"], not from
the content script -- extensions holding the host permission are not gated.

Usage:
    from keybridge import serve
    srv = serve(on_key=supervisor.handle_key,
                reset_argv=["/usr/bin/python3", "reset_link.py"])
    # ... runs in a daemon thread; srv.shutdown() to stop.
"""

from __future__ import annotations

import json
import queue
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

HOST = "127.0.0.1"
PORT = 20261

# Slow actions run here, one at a time, never on a request thread.
_JOBS: "queue.Queue[tuple[str, list[str], float]]" = queue.Queue(maxsize=32)
_STATE = {"running": None, "last": None}
_STATE_LOCK = threading.Lock()

_CONFIG: dict = {"on_key": None, "reset_argv": None}


def _worker() -> None:
    while True:
        name, argv, timeout = _JOBS.get()
        with _STATE_LOCK:
            _STATE["running"] = {"name": name, "since": time.time()}
        try:
            cp = subprocess.run(argv, capture_output=True, timeout=timeout)
            outcome = {"rc": cp.returncode}
        except subprocess.TimeoutExpired:
            outcome = {"rc": "timeout", "after": timeout}
        except Exception as exc:  # never let the worker die
            outcome = {"rc": "error", "detail": repr(exc)}
        with _STATE_LOCK:
            _STATE["running"] = None
            _STATE["last"] = {"name": name, "at": time.time(), **outcome}
        _JOBS.task_done()


class _Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    # Bounds an idle or half-open keep-alive connection so it cannot hold a
    # thread forever. Without this, threading just moves the leak.
    timeout = 5

    def log_message(self, fmt, *args) -> None:  # keep the supervisor log clean
        pass

    def _reply(self, code: int, obj=None) -> None:
        body = b"" if obj is None else json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if body:
            self.wfile.write(body)

    def do_OPTIONS(self) -> None:
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def _route(self) -> None:
        url = urlparse(self.path)
        params = parse_qs(url.query)

        if url.path == "/key":
            key = (params.get("k") or [""])[0]
            if not key:
                return self._reply(400, {"error": "missing k"})
            cb = _CONFIG["on_key"]
            if cb is not None:
                # Dispatch off the request thread: a slow supervisor callback
                # must never be able to stall the accept loop again.
                threading.Thread(target=cb, args=(key,), daemon=True).start()
            return self._reply(202, {"queued": key})

        if url.path == "/reset":
            argv = _CONFIG["reset_argv"]
            if not argv:
                return self._reply(503, {"error": "reset not configured"})
            try:
                _JOBS.put_nowait(("reset", list(argv), 30.0))
            except queue.Full:
                return self._reply(429, {"error": "reset already pending"})
            return self._reply(202, {"accepted": "reset"})

        if url.path == "/health":
            with _STATE_LOCK:
                snapshot = dict(_STATE)
            return self._reply(200, {
                "ok": True,
                "pending_jobs": _JOBS.qsize(),
                "threads": threading.active_count(),
                "backlog": _Server.request_queue_size,
                **snapshot,
            })

        self._reply(404, {"error": "no such route"})

    do_GET = _route
    do_POST = _route


class _Server(ThreadingHTTPServer):
    # ThreadingHTTPServer already sets daemon_threads = True; stated here so a
    # future refactor to a bare ThreadingMixIn (default False) doesn't silently
    # reintroduce a shutdown hang.
    daemon_threads = True
    allow_reuse_address = True
    # The real fix for the SYN_SENT signature: 5 -> 128.
    request_queue_size = 128


def serve(on_key=None, reset_argv=None, host: str = HOST, port: int = PORT) -> _Server:
    """Start the bridge in a daemon thread and return the server object."""
    _CONFIG["on_key"] = on_key
    _CONFIG["reset_argv"] = reset_argv
    threading.Thread(target=_worker, daemon=True, name="bridge-worker").start()
    srv = _Server((host, port), _Handler)
    threading.Thread(target=srv.serve_forever, daemon=True, name="bridge-accept").start()
    return srv


if __name__ == "__main__":
    server = serve(on_key=lambda k: print("key:", k), reset_argv=["/bin/sleep", "30"])
    print(f"bridge on http://{HOST}:{PORT}  (GET /health)")
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        server.shutdown()