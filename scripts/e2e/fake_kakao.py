#!/usr/bin/env python3
"""Private-network Kakao-compatible fixture for Issue #24; never contacts Kakao."""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs
import json
import threading


COUNTS = {"tokenExchanges": 0, "userInfo": 0}
COUNTS_LOCK = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    server_version = "Issue24FakeOAuth/1.0"

    def log_message(self, _format, *_args):
        return

    def _respond(self, status, body):
        encoded = json.dumps(body, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def do_GET(self):
        if self.path == "/__e2e/health":
            self._respond(200, {"status": "ok"})
            return
        if self.path == "/__e2e/counters":
            with COUNTS_LOCK:
                counters = dict(COUNTS)
            self._respond(200, counters)
            return
        if self.path == "/api/v1/internal/identity/claims/00000000-0000-4000-8000-000000000024":
            if (
                self.headers.get("X-Service-Id") != "platform-identity"
                or self.headers.get("X-Service-Secret") != "issue24-e2e-synthetic-claims-secret-material"
            ):
                self._respond(403, {"error": "forbidden"})
                return
            self._respond(200, {
                "organizationId": "11111111-1111-1111-1111-111111111111",
                "role": "MANAGER",
                "publicId": "22222222-2222-2222-2222-222222222222",
            })
            return
        if self.path == "/v2/user/me":
            if self.headers.get("Authorization") != "Bearer issue24-synthetic-provider-access":
                self._respond(401, {"error": "unauthorized"})
                return
            with COUNTS_LOCK:
                COUNTS["userInfo"] += 1
            self._respond(200, {"id": 240024})
            return
        self._respond(404, {"error": "not_found"})

    def do_POST(self):
        if self.path != "/oauth/token":
            self._respond(404, {"error": "not_found"})
            return
        length = int(self.headers.get("Content-Length", "0"))
        form = parse_qs(self.rfile.read(length).decode("utf-8"), keep_blank_values=True)
        if (
            form.get("code") != ["issue24-synthetic-oauth-code"]
            or form.get("client_id") != ["issue24-synthetic-client"]
            or form.get("client_secret") != ["issue24-synthetic-client-secret"]
        ):
            self._respond(400, {"error": "invalid_fixture_request"})
            return
        with COUNTS_LOCK:
            COUNTS["tokenExchanges"] += 1
        self._respond(200, {"access_token": "issue24-synthetic-provider-access", "expires_in": 3600})


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8099), Handler).serve_forever()
