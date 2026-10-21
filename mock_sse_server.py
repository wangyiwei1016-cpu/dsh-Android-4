"""本地假的 OpenAI 兼容流式服务，用来验证旧机上的 SSE 渲染链路。"""
import json
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

CHUNKS = ["旧", "机", "也", "能", "跑", " AI", "。"]


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
            time.sleep(0.25)
        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()

    def log_message(self, fmt, *args):
        print("[mock]", fmt % args, flush=True)


if __name__ == "__main__":
    print("mock SSE server on 0.0.0.0:8787", flush=True)
    HTTPServer(("0.0.0.0", 8787), Handler).serve_forever()
