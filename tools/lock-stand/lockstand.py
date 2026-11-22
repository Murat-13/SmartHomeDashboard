#!/usr/bin/env python3
"""Стенд «замки и ворота»: поддельные реле Shelly Gen2 на Mac для проверки калиток и ворот (docs/gate-tz.md, этап 4).

Без сторонних программ. Отвечает тем же HTTP RPC, что настоящие Shelly Gen2 (Plus 1, Plus 2PM в режиме ворот):
  - реле калитки (порт 8091): Switch.Set с toggle_after — импульс, выключает само реле; вход 0 — датчик двери:
    через 0,8 с после импульса дверь «открывается» на 6 с и закрывается (доводчик);
  - привод ворот (порт 8092): Cover.Open / Close / Stop / GetStatus, ворота честно едут 12 с с положением в процентах;
    вход 0 — датчик «ворота не закрыты».
Пароль (как у Shelly: пользователь admin, HTTP Digest SHA-256): --password <пароль> (по умолчанию без пароля).

Запуск: python3 -I lockstand.py [--bind адрес] [--password пароль]
Команды в журнал — без адресов. `kill -USR1 <pid>` — стенд «пропадает из сети» на 20 с (отвечать не будет).
"""
import hashlib
import json
import os
import signal
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

LOCK = threading.Lock()
OFFLINE_UNTIL = [0.0]
PASSWORD = [None]
NONCES = set()


def log(message):
    print(time.strftime("%H:%M:%S") + " " + message, flush=True)


def offline(*_):
    OFFLINE_UNTIL[0] = time.monotonic() + 20
    log("стенд пропал из сети на 20 с")


class Relay:
    """Реле калитки: импульс включения выключает само реле (toggle_after), датчик двери — следом."""
    name = "реле калитки"
    device_id = "shellyplus1-lockstand"
    model = "SNSW-001X16EU"

    def __init__(self):
        self.on = False
        self.off_at = None
        self.door_open_at = None
        self.door_close_at = None

    def tick(self):
        now = time.monotonic()
        if self.off_at and now >= self.off_at:
            self.on, self.off_at = False, None
            log("реле калитки: выключилось само (toggle_after)")
        if self.door_open_at and now >= self.door_open_at:
            self.door_open_at = None
            self.door_close_at = now + 6
            log("датчик двери: открыта")
        if self.door_close_at and now >= self.door_close_at:
            self.door_close_at = None
            log("датчик двери: закрыта")

    @property
    def door(self):
        return self.door_close_at is not None

    def rpc(self, method, params):
        if method == "Switch.Set":
            was = self.on
            self.on = str(params.get("on", "")).lower() in ("true", "1")
            after = params.get("toggle_after")
            self.off_at = time.monotonic() + float(after) if (self.on and after) else None
            if self.on:
                self.door_open_at = time.monotonic() + 0.8
            log("реле калитки: %s%s" % ("включено" if self.on else "выключено", " на %s с" % after if after else ""))
            return {"was_on": was}
        if method == "Switch.GetStatus":
            return {"id": 0, "source": "http", "output": self.on, "apower": 0.0}
        if method == "Input.GetStatus":
            return {"id": 0, "state": self.door}
        return None


class Drive:
    """Привод ворот: ход 12 с, положение 0–100 %, «Стоп» останавливает на месте."""
    name = "привод ворот"
    device_id = "shellyplus2pm-lockstand"
    model = "SNSW-102P16EU"
    TRAVEL = 12.0

    def __init__(self):
        self.pos = 0.0
        self.target = None
        self.last = time.monotonic()

    def tick(self):
        now = time.monotonic()
        dt, self.last = now - self.last, now
        if self.target is None:
            return
        step = 100.0 / self.TRAVEL * dt
        if abs(self.target - self.pos) <= step:
            self.pos = float(self.target)
            log("привод ворот: %s" % ("открыты" if self.pos >= 100 else "закрыты"))
            self.target = None
        else:
            self.pos += step if self.target > self.pos else -step

    @property
    def state(self):
        if self.target is not None:
            return "opening" if self.target > self.pos else "closing"
        if self.pos >= 100:
            return "open"
        if self.pos <= 0:
            return "closed"
        return "stopped"

    def rpc(self, method, params):
        if method == "Cover.Open":
            self.target = 100; log("привод ворот: открываются"); return None
        if method == "Cover.Close":
            self.target = 0; log("привод ворот: закрываются"); return None
        if method == "Cover.Stop":
            self.target = None; log("привод ворот: стоп на %d %%" % self.pos); return None
        if method == "Cover.GetStatus":
            return {"id": 0, "source": "http", "state": self.state, "current_pos": int(round(self.pos)),
                    "pos_control": True}
        if method == "Input.GetStatus":
            return {"id": 0, "state": self.pos > 0}
        return None


def make_handler(device):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def reply(self, code, body, headers=()):
            data = json.dumps(body, ensure_ascii=False).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            for k, v in headers:
                self.send_header(k, v)
            self.end_headers()
            self.wfile.write(data)

        def authorized(self, method):
            if PASSWORD[0] is None:
                return True
            header = self.headers.get("Authorization", "")
            if header.startswith("Digest "):
                fields = {}
                for part in header[7:].split(","):
                    if "=" in part:
                        k, v = part.strip().split("=", 1)
                        fields[k] = v.strip('"')
                h = lambda s: hashlib.sha256(s.encode()).hexdigest()
                ha1 = h("admin:%s:%s" % (device.device_id, PASSWORD[0]))
                ha2 = h("%s:%s" % (method, fields.get("uri", "")))
                good = h(":".join([ha1, fields.get("nonce", ""), fields.get("nc", ""), fields.get("cnonce", ""),
                                   fields.get("qop", ""), ha2]))
                if fields.get("username") == "admin" and fields.get("nonce") in NONCES and fields.get("response") == good:
                    return True
            nonce = hashlib.sha256(os.urandom(16)).hexdigest()[:32]
            NONCES.add(nonce)
            self.reply(401, {"code": 401, "message": "unauthorized"}, [("WWW-Authenticate",
                'Digest qop="auth", realm="%s", nonce="%s", algorithm=SHA-256' % (device.device_id, nonce))])
            # Первый запрос клиента всегда без пароля (так устроен Digest) — в журнал только неверный пароль.
            if header.startswith("Digest "):
                log("%s: неверный пароль — 401" % device.name)
            return False

        def handle_rpc(self, http_method, method, params):
            if time.monotonic() < OFFLINE_UNTIL[0]:
                self.close_connection = True
                return
            if not self.authorized(http_method):
                return
            with LOCK:
                device.tick()
                if method == "Shelly.GetDeviceInfo":
                    result = {"id": device.device_id, "model": device.model, "gen": 2, "auth_en": PASSWORD[0] is not None}
                else:
                    result = device.rpc(method, params)
                    if result is None and method not in ("Cover.Open", "Cover.Close", "Cover.Stop"):
                        self.reply(404, {"code": 404, "message": "No handler for %s" % method})
                        return
            self.reply(200, result)

        def do_GET(self):
            url = urlparse(self.path)
            if url.path == "/shelly":
                if time.monotonic() < OFFLINE_UNTIL[0]:
                    return
                self.reply(200, {"id": device.device_id, "model": device.model, "gen": 2, "auth_en": PASSWORD[0] is not None})
                return
            if not url.path.startswith("/rpc/"):
                self.reply(404, {"code": 404, "message": "not found"})
                return
            params = {k: v[0] for k, v in parse_qs(url.query).items()}
            self.handle_rpc("GET", url.path[5:], params)

        def do_POST(self):
            length = int(self.headers.get("Content-Length", "0") or 0)
            try:
                body = json.loads(self.rfile.read(length) or b"{}")
            except ValueError:
                self.reply(400, {"code": 400, "message": "bad json"})
                return
            path = urlparse(self.path).path
            method = path[5:] if path.startswith("/rpc/") else body.get("method", "")
            self.handle_rpc("POST", method, body.get("params", body if path.startswith("/rpc/") else {}))

    return Handler


def main():
    bind = "0.0.0.0"
    args = sys.argv[1:]
    if "--bind" in args:
        bind = args[args.index("--bind") + 1]
    if "--password" in args:
        PASSWORD[0] = args[args.index("--password") + 1]
    signal.signal(signal.SIGUSR1, offline)
    devices = [(8091, Relay()), (8092, Drive())]
    for port, device in devices:
        server = ThreadingHTTPServer((bind, port), make_handler(device))
        threading.Thread(target=server.serve_forever, daemon=True).start()
        log("%s слушает порт %d%s" % (device.name, port, " (с паролем)" if PASSWORD[0] else ""))

    def ticker():
        while True:
            with LOCK:
                for _, device in devices:
                    device.tick()
            time.sleep(0.2)

    threading.Thread(target=ticker, daemon=True).start()
    log("стенд готов; pid %d" % os.getpid())
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
