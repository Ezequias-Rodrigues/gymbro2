"""
Jumping Jack Cloud Relay
Minimal HTTP relay — phone POSTs jack data, ESP32 GETs it.

Deploy on Render (free):
  1. Push this folder to a Git repo
  2. On Render, create a new "Web Service"
  3. Set: Start Command = "python app.py"
  4. Deploy
"""

import os
import json
from http.server import HTTPServer, BaseHTTPRequestHandler

DATA = {
    "isJacking": False,
    "repCount": 0,
    "formQuality": "REST",
    "confidence": 0.0,
    "timestamp": 0,
}


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, body):
        body_bytes = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()
        self.wfile.write(body_bytes)

    def do_OPTIONS(self):
        self._send(204, {})

    def do_GET(self):
        if self.path == "/api/jackresult":
            self._send(200, DATA)
        elif self.path == "/":
            self._send(200, {"status": "ok", "endpoints": ["GET /api/jackresult", "POST /api/jackresult"]})
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):
        global DATA
        if self.path == "/api/jackresult":
            length = int(self.headers.get("Content-Length", 0))
            if length > 0:
                body = json.loads(self.rfile.read(length))
                # Merge: only update keys that exist
                for k in ("isJacking", "repCount", "formQuality", "confidence", "timestamp"):
                    if k in body:
                        DATA[k] = body[k]
                DATA["timestamp"] = DATA.get("timestamp", 0)
            self._send(200, {"ok": True, "relay": DATA})
        else:
            self._send(404, {"error": "not found"})


if __name__ == "__main__":
    port = int(os.environ.get("PORT", 8080))
    server = HTTPServer(("0.0.0.0", port), Handler)
    print(f"Relay running on port {port}")
    server.serve_forever()
