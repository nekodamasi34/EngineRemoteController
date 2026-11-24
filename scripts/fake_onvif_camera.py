"""Serve a JPEG through a minimal ONVIF fixture on localhost for Android emulator tests."""

import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("image", type=Path)
    parser.add_argument("--port", type=int, default=18765)
    args = parser.parse_args()
    jpeg = args.image.read_bytes()
    if not (jpeg.startswith(b"\xff\xd8") and jpeg.endswith(b"\xff\xd9")):
        parser.error("A JPEG fixture is required")
    url = f"http://10.0.2.2:{args.port}"

    class Handler(BaseHTTPRequestHandler):
        def reply(self, status, content_type, data, headers=None):
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(data)))
            for key, value in (headers or {}).items():
                self.send_header(key, value)
            self.end_headers()
            self.wfile.write(data)

        def do_POST(self):
            data = self.rfile.read(int(self.headers.get("Content-Length", "0")))
            if b"GetCapabilities" in data:
                result = f"<Media><XAddr>{url}/onvif/media</XAddr></Media>"
            elif b"GetProfiles" in data:
                result = '<Profiles token="default"/>'
            elif b"GetSnapshotUri" in data:
                result = f"<Uri>{url}/snapshot.jpg</Uri>"
            else:
                self.send_error(400)
                return
            self.reply(200, "application/soap+xml", f"<Envelope><Body>{result}</Body></Envelope>".encode())

        def do_GET(self):
            if self.path != "/snapshot.jpg":
                self.send_error(404)
            elif self.headers.get("Authorization") != "Basic YWktdGVzdDo=":
                self.reply(401, "text/plain", b"", {"WWW-Authenticate": 'Basic realm="fixture"'})
            else:
                self.reply(200, "image/jpeg", jpeg)

    with ThreadingHTTPServer(("127.0.0.1", args.port), Handler) as server:
        server.serve_forever()


if __name__ == "__main__":
    main()
