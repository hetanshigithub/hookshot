#!/usr/bin/env python3
"""A tiny webhook receiver that verifies Hookshot signatures. Standard library only.

    python3 scripts/demo_receiver.py --secret whsec_xxx [--port 9000] [--fail-first N]

This is also a reference implementation of what a customer's endpoint should do:
  1. Parse the X-Hookshot-Signature header: "t=<unix>,v1=<hex>".
  2. Recompute HMAC_SHA256(secret, f"{t}.{raw_body}") over the *raw* request body.
  3. Compare in constant time, and reject timestamps older than a few minutes (replay protection).
  4. Return 2xx quickly. Anything else makes Hookshot retry with backoff.
"""
from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

TOLERANCE_SECONDS = 300


def verify(secret: str, header: str, body: bytes, now: float | None = None) -> bool:
    now = time.time() if now is None else now
    parts = dict(p.split("=", 1) for p in header.split(",") if "=" in p)
    try:
        timestamp = int(parts["t"])
        signature = parts["v1"]
    except (KeyError, ValueError):
        return False
    if abs(now - timestamp) > TOLERANCE_SECONDS:
        return False
    expected = hmac.new(secret.encode(), f"{timestamp}.".encode() + body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, signature)


def make_handler(secret: str, fail_first: int):
    state = {"seen": 0}

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
            ok = verify(secret, self.headers.get("X-Hookshot-Signature", ""), body)
            state["seen"] += 1
            attempt = self.headers.get("X-Hookshot-Attempt")
            event = json.loads(body or b"{}").get("type")
            print(f"[{time.strftime('%X')}] event={event} attempt={attempt} signature={'VALID' if ok else 'INVALID'}")
            if not ok:
                self.send_response(401)
            elif state["seen"] <= fail_first:
                print("   -> simulating an outage: returning 503")
                self.send_response(503)
            else:
                self.send_response(200)
            self.end_headers()

        def log_message(self, *_):
            pass

    return Handler


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--secret", required=True)
    parser.add_argument("--port", type=int, default=9000)
    parser.add_argument("--fail-first", type=int, default=0, help="return 503 for the first N requests")
    args = parser.parse_args()
    print(f"Listening on http://localhost:{args.port}/hook")
    HTTPServer(("127.0.0.1", args.port), make_handler(args.secret, args.fail_first)).serve_forever()
