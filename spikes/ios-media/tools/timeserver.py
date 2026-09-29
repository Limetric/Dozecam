#!/usr/bin/env python3
"""Serves this Mac's clock (epoch seconds) on :18580, so a device can correct
latency measurements for the offset between its clock and the Mac's."""
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Clock(BaseHTTPRequestHandler):
    def do_GET(self):
        body = f"{time.time():.6f}\n".encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


ThreadingHTTPServer(("", 18580), Clock).serve_forever()
