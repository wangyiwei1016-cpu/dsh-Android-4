"""固定内容的 SSE 服务，用于 A/B 性能对比：200 个片段，每 20ms 一块。"""
import json
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

TEXT = "黄昏的街道把光拉得很长，行人影子在地上缓慢移动。" * 10
CHUNKS = [TEXT[i:i + 3] for i in range(0, len(TEXT), 3)][:200]


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        self.rfile.read(length)
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        for c in CHUNKS:
            payload = {"id": "mock", "choices": [{"index": 0, "delta": {"content": c}}]}
            self.wfile.write(("data: " + json.dumps(payload, ensure_ascii=False) + "\n\n").encode("utf-8"))
            self.wfile.flush()
            time.sleep(0.02)
        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()

    def log_message(self, fmt, *args):
        pass


if __name__ == "__main__":
    print("bench SSE server on 0.0.0.0:8788", flush=True)
    HTTPServer(("0.0.0.0", 8788), Handler).serve_forever()
