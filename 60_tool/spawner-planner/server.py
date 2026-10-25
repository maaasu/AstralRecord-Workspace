"""ローカル専用の配置案プレビュー。ファイル変更・サーバー反映APIは持たない。"""
from __future__ import annotations

import argparse
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

from planner import Planner


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--terrain", type=Path, required=True)
    parser.add_argument("--filebase", type=Path, required=True)
    parser.add_argument("--policy", type=Path, default=Path(__file__).parent / "policies/eriva-southwest.json")
    parser.add_argument("--existing", type=Path)
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--export", type=Path, help="配置案JSONを保存して終了")
    args = parser.parse_args()
    planner = Planner(args.terrain, args.filebase, args.policy, args.existing)
    if args.export:
        args.export.parent.mkdir(parents=True, exist_ok=True)
        args.export.write_text(json.dumps(planner.plan(), ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"Preview exported: {args.export}")
        return
    assets = Path(__file__).parent / "web"
    public = json.dumps(planner.public_data(), ensure_ascii=False).encode()
    cache = {}
    lock = threading.Lock()

    class Handler(BaseHTTPRequestHandler):
        def response(self, status, data, mime="application/json; charset=utf-8"):
            self.send_response(status)
            self.send_header("Content-Type", mime)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'")
            self.end_headers()
            self.wfile.write(data)

        def allowed(self):
            hosts = {f"127.0.0.1:{args.port}", f"localhost:{args.port}"}
            origin = self.headers.get("Origin")
            return self.headers.get("Host") in hosts and (not origin or origin in {f"http://{v}" for v in hosts})

        def do_GET(self):
            if not self.allowed():
                self.response(403, b'{"error":"Local origin required"}')
                return
            route = urlsplit(self.path).path
            if route == "/api/data":
                self.response(200, public)
            elif route in {"/", "/app.js", "/style.css"}:
                name, mime = {"/": ("index.html", "text/html"), "/app.js": ("app.js", "text/javascript"), "/style.css": ("style.css", "text/css")}[route]
                self.response(200, (assets / name).read_bytes(), mime + "; charset=utf-8")
            else:
                self.response(404, b'{"error":"Not found"}')

        def do_POST(self):
            if not self.allowed() or self.path != "/api/plan":
                self.response(403, b'{"error":"Local plan endpoint required"}')
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= 4096:
                    raise ValueError("Invalid request size")
                request = json.loads(self.rfile.read(length))
                if not isinstance(request, dict) or set(request) - {"radiusMeters", "maxSpawners"}:
                    raise ValueError("Invalid parameters")
                radius, maximum = request.get("radiusMeters", 48), request.get("maxSpawners", 200)
                # 未検証入力をcacheキーに使用しない。
                if type(radius) is not int or type(maximum) is not int:
                    raise ValueError("Parameters must be integers")
                if radius not in (32, 48, 64) or not 1 <= maximum <= 500:
                    raise ValueError("Parameters outside supported bounds")
                with lock:
                    key = (radius, maximum)
                    if key not in cache:
                        if len(cache) >= 12:
                            cache.clear()
                        cache[key] = json.dumps(planner.plan(radius, maximum), ensure_ascii=False).encode()
                    result = cache[key]
                self.response(200, result)
            except (ValueError, TypeError) as error:
                self.response(400, json.dumps({"error": str(error)}).encode())

    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"Spawner preview: http://127.0.0.1:{args.port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
