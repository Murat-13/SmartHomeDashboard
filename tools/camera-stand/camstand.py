#!/usr/bin/env python3
"""Стенд «камеры разных производителей»: RTSP-сервер на Mac без сторонних программ.

Каждая «камера» — свой порт, свои адреса потоков и свои привычки (как у Hikvision, Dahua, TP-Link Tapo): кодек,
размер, частота кадров, звук, вид описания потока (SDP), неровность отправки. Видео — заранее закодированные ролики
(gen.swift), идут по кругу в «живом» времени: все подключившиеся видят один и тот же момент, как у настоящей камеры.

Запуск: python3 -I camstand.py <папка с роликами> [--bind адрес]
В журнал пишутся только номера подключений и счётчики — без адресов.
"""
import base64
import os
import random
import re
import socket
import struct
import sys
import threading
import time

MTU = 1400
T0 = time.monotonic()
WALL0 = time.time()
NTP_EPOCH = 2208988800
LOCK = threading.Lock()
CONNECTIONS = [0]
ACTIVE = {}


def log(text):
    with LOCK:
        print(time.strftime("%H:%M:%S"), text, flush=True)


class Video:
    def __init__(self, path):
        data = open(path, "rb").read()
        if data[:4] != b"CAMS":
            raise ValueError("не ролик стенда: " + path)
        self.codec, self.fps, self.width, self.height, count = struct.unpack(">BHHHI", data[4:15])
        self.hevc = self.codec == 5
        self.packets = []  # на кадр: список (полезная нагрузка RTP, marker)
        self.keys = []
        self.params = {}
        pos = 15
        total = 0
        while pos < len(data):
            size, key = struct.unpack(">IB", data[pos:pos + 5])
            pos += 5
            end = pos + size
            nals = []
            while pos < end:
                length = struct.unpack(">I", data[pos:pos + 4])[0]
                pos += 4
                nals.append(data[pos:pos + length])
                pos += length
            if key and not self.params:
                for nal in nals:
                    kind = (nal[0] >> 1) & 0x3F if self.hevc else nal[0] & 0x1F
                    self.params.setdefault(kind, nal)
            payloads = []
            for nal in nals:
                payloads.extend(self._split(nal))
            self.packets.append([(p, i == len(payloads) - 1) for i, p in enumerate(payloads)])
            self.keys.append(bool(key))
            total += size
        if len(self.packets) != count:
            raise ValueError("ролик обрезан: " + path)
        self.kbps = total * 8 * self.fps / len(self.packets) / 1000

    def _split(self, nal):
        if len(nal) <= MTU:
            return [nal]
        out = []
        if self.hevc:
            kind = (nal[0] >> 1) & 0x3F
            head = bytes([(nal[0] & 0x81) | (49 << 1), nal[1]])
            body = nal[2:]
        else:
            kind = nal[0] & 0x1F
            head = bytes([(nal[0] & 0xE0) | 28])
            body = nal[1:]
        step = MTU - len(head) - 1
        for start in range(0, len(body), step):
            flags = (0x80 if start == 0 else 0) | (0x40 if start + step >= len(body) else 0)
            out.append(head + bytes([flags | kind]) + body[start:start + step])
        return out

    def sdp(self, payload_type, with_params):
        b64 = lambda kind: base64.b64encode(self.params[kind]).decode()
        if self.hevc:
            fmtp = "sprop-vps=%s;sprop-sps=%s;sprop-pps=%s" % (b64(32), b64(33), b64(34))
            return ["a=rtpmap:%d H265/90000" % payload_type] + (["a=fmtp:%d %s" % (payload_type, fmtp)] if with_params else [])
        sps = self.params[7]
        fmtp = "packetization-mode=1;profile-level-id=%02X%02X%02X" % (sps[1], sps[2], sps[3])
        if with_params:
            fmtp += ";sprop-parameter-sets=%s,%s" % (b64(7), b64(8))
        return ["a=rtpmap:%d H264/90000" % payload_type, "a=fmtp:%d %s" % (payload_type, fmtp)]


def alaw(sample):
    sign = 0x80 if sample >= 0 else 0
    s = min(abs(sample), 32635) >> 3
    if s < 32:
        return (sign | (s >> 1)) ^ 0x55
    exp = s.bit_length() - 5
    return (sign | (exp << 4) | ((s >> exp) & 0x0F)) ^ 0x55


def ulaw(sample):
    sign = 0x80 if sample < 0 else 0
    s = min(abs(sample), 32635) + 132
    exp = s.bit_length() - 8
    return ~(sign | (exp << 4) | ((s >> (exp + 3)) & 0x0F)) & 0xFF


class Audio:
    """Почти тишина (шум около −50 дБ): путь звука работает, а из динамика планшета ничего не слышно."""

    def __init__(self, kind, folder):
        self.kind = kind
        rnd = random.Random(7)
        if kind in ("PCMA", "PCMU"):
            enc = alaw if kind == "PCMA" else ulaw
            self.clock, self.step, self.pt = 8000, 160, 8 if kind == "PCMA" else 0
            self.frames = [bytes(enc(int(rnd.gauss(0, 90))) for _ in range(160)) for _ in range(250)]
            self.marker = False
        else:
            raw = open(os.path.join(folder, "aac16.adts"), "rb").read()
            self.clock, self.step, self.pt = 16000, 1024, 97
            self.frames = []
            pos = 0
            while pos + 7 <= len(raw) and raw[pos] == 0xFF and (raw[pos + 1] & 0xF0) == 0xF0:
                length = ((raw[pos + 3] & 3) << 11) | (raw[pos + 4] << 3) | (raw[pos + 5] >> 5)
                head = 7 if raw[pos + 1] & 1 else 9
                frame = raw[pos + head:pos + length]
                self.frames.append(struct.pack(">HH", 16, len(frame) << 3) + frame)
                pos += length
            if not self.frames:
                raise ValueError("нет кадров AAC")
            self.marker = True

    def sdp(self):
        if self.kind == "AAC":
            return ["a=rtpmap:97 mpeg4-generic/16000/1",
                    "a=fmtp:97 streamtype=5;profile-level-id=1;mode=AAC-hbr;sizelength=13;indexlength=3;indexdeltalength=3;config=1408"]
        return ["a=rtpmap:%d %s/8000" % (self.pt, self.kind)]


class Stream:
    def __init__(self, camera, path, video, audio, with_params=True):
        self.camera, self.path, self.video, self.audio, self.with_params = camera, path, video, audio, with_params
        self.video_base = random.getrandbits(32)
        self.audio_base = random.getrandbits(32)
        self.sent = 0

    def controls(self, base):
        style = self.camera.style
        if style == "hik":   # абсолютные адреса дорожек
            return [base + "trackID=1", base + "trackID=2"]
        if style == "dahua":
            return ["trackID=0", "trackID=1"]
        return ["track1", "track2"]

    def sdp(self, base):
        c = self.controls(base)
        lines = ["v=0", "o=- %d 1 IN IP4 0.0.0.0" % int(WALL0), "s=" + self.camera.title, "c=IN IP4 0.0.0.0", "t=0 0"]
        if self.camera.style == "hik":
            lines.append("a=control:" + base)
        elif self.camera.style == "dahua":
            lines += ["a=control:*", "a=range:npt=now-"]
        lines += ["m=video 0 RTP/AVP 96"] + self.video.sdp(96, self.with_params)
        if self.camera.style == "dahua":
            lines.append("a=framerate:%d.000000" % self.video.fps)
        lines.append("a=control:" + c[0])
        if self.audio:
            lines += ["m=audio 0 RTP/AVP %d" % self.audio.pt] + self.audio.sdp() + ["a=control:" + c[1]]
        return "\r\n".join(lines) + "\r\n"


class Camera:
    def __init__(self, title, style, port, key_late_ms=0, jitter_ms=0, rtp_info=True):
        self.title, self.style, self.port = title, style, port
        self.key_late_ms, self.jitter_ms, self.rtp_info = key_late_ms, jitter_ms, rtp_info
        self.streams = []

    def add(self, path, video, audio, with_params=True):
        self.streams.append(Stream(self, path, video, audio, with_params))
        return self

    def find(self, url):
        """Поток по адресу запроса. У «Dahua» плеер может вставить имя дорожки перед параметрами — ищем по subtype."""
        rest = url.split("://", 1)[-1]
        rest = rest[rest.find("/"):] if "/" in rest else "/"
        if self.style == "dahua":
            m = re.search(r"subtype=(\d)", rest)
            return (self.streams[int(m.group(1))] if m and int(m.group(1)) < len(self.streams) and "/cam/realmonitor" in rest else None)
        for s in sorted(self.streams, key=lambda s: -len(s.path)):
            if rest.startswith(s.path):
                return s
        return None

    def track(self, url):
        """Дорожка по адресу SETUP: 0 — видео, 1 — звук."""
        m = re.search(r"track(?:ID=)?(\d)", url)
        number = int(m.group(1)) if m else -1
        return {"hik": {1: 0, 2: 1}, "dahua": {0: 0, 1: 1}, "tapo": {1: 0, 2: 1}}[self.style].get(number)


class Client(threading.Thread):
    def __init__(self, camera, sock):
        super().__init__(daemon=True)
        self.camera, self.sock = camera, sock
        with LOCK:
            CONNECTIONS[0] += 1
            self.id = CONNECTIONS[0]
        self.send_lock = threading.Lock()
        self.session = "%08d" % random.randrange(10 ** 8)
        self.channels = {}  # 0 — видео, 1 — звук → канал interleaved
        self.stream = None
        self.open = True
        self.sequence = [random.getrandbits(16), random.getrandbits(16)]
        self.ssrc = [random.getrandbits(32), random.getrandbits(32)]
        self.counts = [[0, 0], [0, 0]]

    def write(self, data):
        with self.send_lock:
            self.sock.sendall(data)

    def reply(self, cseq, status, headers=(), body=""):
        raw = body.encode()
        text = "RTSP/1.0 %s\r\nCSeq: %s\r\nServer: %s\r\n" % (status, cseq, self.camera.title)
        for k, v in headers:
            text += "%s: %s\r\n" % (k, v)
        if raw:
            text += "Content-Length: %d\r\n" % len(raw)
        self.write(text.encode() + b"\r\n" + raw)

    def run(self):
        f = self.sock.makefile("rb")
        try:
            while self.open:
                first = f.read(1)
                if not first:
                    break
                if first == b"$":  # отчёты RTCP плеера
                    head = f.read(3)
                    f.read(struct.unpack(">H", head[1:3])[0])
                    continue
                line = (first + f.readline()).decode("latin1").strip()
                if not line:
                    continue
                headers = {}
                while True:
                    h = f.readline().decode("latin1").strip()
                    if not h:
                        break
                    k, _, v = h.partition(":")
                    headers[k.strip().lower()] = v.strip()
                if int(headers.get("content-length", "0") or 0):
                    f.read(int(headers["content-length"]))
                parts = line.split(" ")
                if len(parts) >= 2:
                    self.handle(parts[0], parts[1], headers)
        except (OSError, ValueError, struct.error):
            pass
        finally:
            self.close()

    def handle(self, method, url, headers):
        cseq = headers.get("cseq", "")
        stream = self.camera.find(url) or self.stream
        if method == "OPTIONS":
            self.reply(cseq, "200 OK", [("Public", "OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN, GET_PARAMETER, SET_PARAMETER")])
        elif stream is None:
            self.reply(cseq, "404 Not Found")
        elif method == "DESCRIBE":
            base = url.rstrip("/") + "/"
            self.base = base
            self.reply(cseq, "200 OK", [("Content-Type", "application/sdp"), ("Content-Base", base)], stream.sdp(base))
            log("#%d %s%s: описание потока запрошено" % (self.id, self.camera.title, stream.path))
        elif method == "SETUP":
            transport = headers.get("transport", "")
            if "TCP" not in transport.upper() or "interleaved=" not in transport:
                self.reply(cseq, "461 Unsupported Transport")
                return
            channel = int(transport.split("interleaved=")[1].split("-")[0].split(";")[0])
            track = self.camera.track(url)
            if track is None or (track == 1 and not stream.audio):
                self.reply(cseq, "404 Not Found")
                return
            self.channels[track] = channel
            self.stream = stream
            self.reply(cseq, "200 OK", [("Transport", "RTP/AVP/TCP;unicast;interleaved=%d-%d;ssrc=%08X" % (channel, channel + 1, self.ssrc[track])),
                                        ("Session", self.session + ";timeout=60")])
        elif method == "PLAY":
            headers_out = [("Session", self.session), ("Range", "npt=0.000-")]
            v, a = stream.video, stream.audio
            self.first_frame = int((time.monotonic() - T0) * v.fps) + 2
            self.first_audio = int((time.monotonic() - T0) * a.clock / a.step) + 2 if a else 0
            if self.camera.rtp_info:
                base = getattr(self, "base", url.rstrip("/") + "/")
                names = stream.controls(base)
                info = []
                if 0 in self.channels:
                    info.append("url=%s;seq=%d;rtptime=%d" % (names[0] if "://" in names[0] else base + names[0], self.sequence[0],
                                                              (stream.video_base + self.first_frame * 90000 // v.fps) & 0xFFFFFFFF))
                if 1 in self.channels and a:
                    info.append("url=%s;seq=%d;rtptime=%d" % (names[1] if "://" in names[1] else base + names[1], self.sequence[1],
                                                              (stream.audio_base + self.first_audio * a.step) & 0xFFFFFFFF))
                headers_out.append(("RTP-Info", ",".join(info)))
            self.reply(cseq, "200 OK", headers_out)
            self.start_tracks()
        elif method in ("GET_PARAMETER", "SET_PARAMETER", "PAUSE"):
            self.reply(cseq, "200 OK", [("Session", self.session)])
        elif method == "TEARDOWN":
            self.reply(cseq, "200 OK", [("Session", self.session)])
            self.close()
        else:
            self.reply(cseq, "405 Method Not Allowed")

    def start_tracks(self):
        s = self.stream
        if s is None or getattr(self, "playing", False):
            return
        self.playing = True
        with LOCK:
            ACTIVE[(self.camera.title, s.path)] = ACTIVE.get((self.camera.title, s.path), 0) + 1
            now = ACTIVE[(self.camera.title, s.path)]
        log("#%d %s%s: показ начат (подключений к потоку: %d)" % (self.id, self.camera.title, s.path, now))
        if 0 in self.channels:
            threading.Thread(target=self.video_loop, daemon=True).start()
        if 1 in self.channels and s.audio:
            threading.Thread(target=self.audio_loop, daemon=True).start()

    def rtp(self, track, payload_type, marker, timestamp, payload):
        channel = self.channels[track]
        seq = self.sequence[track]
        self.sequence[track] = (seq + 1) & 0xFFFF
        packet = struct.pack(">BBHII", 0x80, payload_type | (0x80 if marker else 0), seq, timestamp & 0xFFFFFFFF, self.ssrc[track]) + payload
        self.counts[track][0] += 1
        self.counts[track][1] += len(payload)
        return b"$" + struct.pack(">BH", channel, len(packet)) + packet

    def report(self, track, clock, base):
        """Отчёт отправителя RTCP: «этой метке RTP соответствует такое-то время» — по нему приложение меряет доставку."""
        wall = time.time()
        stamp = (base + int((time.monotonic() - T0) * clock)) & 0xFFFFFFFF
        sec = int(wall) + NTP_EPOCH
        frac = int((wall - int(wall)) * (1 << 32))
        body = struct.pack(">BBHIIIIII", 0x80, 200, 6, self.ssrc[track], sec, frac, stamp, self.counts[track][0], self.counts[track][1])
        self.write(b"$" + struct.pack(">BH", self.channels[track] + 1, len(body)) + body)

    def video_loop(self):
        s, v, cam = self.stream, self.stream.video, self.camera
        n = self.first_frame
        next_report = 0.0
        rnd = random.Random(self.id)
        try:
            while self.open:
                due = T0 + n / v.fps
                index = n % len(v.packets)
                if v.keys[index]:
                    due += cam.key_late_ms / 1000.0
                if cam.jitter_ms:
                    due += rnd.random() * cam.jitter_ms / 1000.0
                wait = due - time.monotonic()
                if wait > 0:
                    time.sleep(wait)
                stamp = s.video_base + n * 90000 // v.fps
                out = bytearray()
                for payload, marker in v.packets[index]:
                    out += self.rtp(0, 96, marker, stamp, payload)
                self.write(bytes(out))
                s.sent += len(out)
                if time.monotonic() >= next_report:
                    self.report(0, 90000, s.video_base)
                    next_report = time.monotonic() + 5
                n += 1
        except OSError:
            pass
        finally:
            self.close()

    def audio_loop(self):
        s, a = self.stream, self.stream.audio
        k = self.first_audio
        next_report = 0.0
        try:
            while self.open:
                wait = T0 + k * a.step / a.clock - time.monotonic()
                if wait > 0:
                    time.sleep(wait)
                self.write(self.rtp(1, a.pt, a.marker, s.audio_base + k * a.step, a.frames[k % len(a.frames)]))
                if time.monotonic() >= next_report:
                    self.report(1, a.clock, s.audio_base)
                    next_report = time.monotonic() + 5
                k += 1
        except OSError:
            pass
        finally:
            self.close()

    def close(self):
        with LOCK:
            was, self.open = self.open, False
            if was and getattr(self, "playing", False) and self.stream is not None:
                key = (self.camera.title, self.stream.path)
                ACTIVE[key] = ACTIVE.get(key, 1) - 1
        if was:
            if getattr(self, "playing", False):
                log("#%d %s%s: отключился, отправлено видео %d пак., звука %d пак." % (
                    self.id, self.camera.title, self.stream.path, self.counts[0][0], self.counts[1][0]))
            try:
                self.sock.close()
            except OSError:
                pass


def serve(camera, bind):
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((bind, camera.port))
    server.listen(16)
    while True:
        sock, _ = server.accept()
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        Client(camera, sock).start()


def cameras(folder):
    video = lambda name: Video(os.path.join(folder, name + ".cams"))
    audio = lambda kind: Audio(kind, folder)
    return [
        # «Hikvision 4 Мп»: оба потока H.265, звук G.711 A-law, адреса дорожек абсолютные, отправка ровная.
        Camera("StandA", "hik", 8554)
            .add("/Streaming/Channels/101", video("a_main"), audio("PCMA"))
            .add("/Streaming/Channels/102", video("a_sub"), audio("PCMA")),
        # «Dahua 2 Мп»: H.264 1920×1080, звук AAC 16 кГц, адрес с параметрами (?channel=1&subtype=0), небольшая неровность.
        Camera("StandB", "dahua", 8555, jitter_ms=30)
            .add("/cam/realmonitor?channel=1&subtype=0", video("b_main"), audio("AAC"))
            .add("/cam/realmonitor?channel=1&subtype=1", video("b_sub"), audio("AAC")),
        # «TP-Link Tapo 3 Мп»: H.264 2304×1296, 15 к/с, ключевой кадр раз в 4 с и приходит с опозданием (как у домофона),
        # субпоток без SPS/PPS в описании, звук G.711 μ-law, без RTP-Info в ответе на PLAY.
        Camera("StandC", "tapo", 8556, key_late_ms=150, jitter_ms=20, rtp_info=False)
            .add("/stream1", video("c_main"), audio("PCMU"))
            .add("/stream2", video("c_sub"), audio("PCMU"), with_params=False),
    ]


def main():
    folder = sys.argv[1]
    bind = sys.argv[sys.argv.index("--bind") + 1] if "--bind" in sys.argv else "0.0.0.0"
    cams = cameras(folder)
    for cam in cams:
        for s in cam.streams:
            v = s.video
            log("%s:%d%s — %s %dx%d, %d к/с, %.0f кбит/с, ключевой раз в %.1f с, звук %s" % (
                cam.title, cam.port, s.path, "H.265" if v.hevc else "H.264", v.width, v.height, v.fps, v.kbps,
                len(v.keys) / max(1, sum(v.keys)) / v.fps, s.audio.kind if s.audio else "нет"))
        threading.Thread(target=serve, args=(cam, bind), daemon=True).start()
    log("стенд слушает")
    last = {}
    while True:
        time.sleep(60)
        with LOCK:
            state = dict(ACTIVE)
        parts = []
        for cam in cams:
            for s in cam.streams:
                sent, last[s] = s.sent - last.get(s, 0), s.sent
                parts.append("%s%s ×%d %.0f кбит/с" % (cam.title, s.path[-12:], state.get((cam.title, s.path), 0), sent * 8 / 60 / 1000))
        log("за минуту: " + "; ".join(parts))


if __name__ == "__main__":
    main()
