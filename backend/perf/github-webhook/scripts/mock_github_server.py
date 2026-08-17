#!/usr/bin/env python3
import json
import os
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


DELAY_MS = int(os.environ.get("MOCK_GITHUB_DELAY_MS", "100"))
PORT = int(os.environ.get("MOCK_GITHUB_PORT", "18081"))


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/health":
            self._respond(200, {"status": "UP"}, delay=False)
            return
        if self.path == "/repos/owner/repo":
            self._respond(200, {"permissions": {"admin": True}})
            return
        self._respond(404, {"message": "Not Found"})

    def do_POST(self):
        if self.path == "/repos/owner/repo/hooks":
            self._respond(201, {"id": 42})
            return
        self._respond(404, {"message": "Not Found"})

    def _respond(self, status, body, delay=True):
        if delay:
            time.sleep(DELAY_MS / 1000)
        encoded = json.dumps(body).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def log_message(self, format, *args):
        return


if DELAY_MS < 0:
    raise ValueError("MOCK_GITHUB_DELAY_MS must be zero or greater")

ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
