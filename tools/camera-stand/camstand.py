#!/usr/bin/env python3
"""Стенд «камеры разных производителей»: RTSP-сервер на Mac без сторонних программ.

Каждая «камера» — свой порт, свои адреса потоков и свои привычки (как у Hikvision, Dahua, TP-Link Tapo): кодек,
размер, частота кадров, звук, вид описания потока (SDP), неровность отправки. Видео — заранее закодированные ролики
(gen.swift), идут по кругу в «живом» времени: все подключившиеся видят один и тот же момент, как у настоящей камеры.

Запуск: python3 -I camstand.py <папка с роликами> [--bind адрес]
В журнал пишутся только номера подключений и счётчики — без адресов.

Сбой сети по команде: `kill -USR1 <pid>` задерживает все потоки на 1,2 с (кадры потом приходят пачкой, как после
провала Wi-Fi), `kill -USR2 <pid>` — на 0,15 с (обычная короткая заминка).

Камера D (если есть ролики d_main/d_sub) — «плохие условия» для диагностики (docs/diagnostics-tz.md): линия
10 Мбит/с (пакеты идут не быстрее), потеря 0,5 % пакетов видео, 20 к/с с датчика 25 к/с (шаги меток 40 и 80 мс).
У D есть поддельный ONVIF (порт 8580, без проверки пароля): профили, возможности кодировщика, чтение и изменение
настроек (этап 4 диагностики — «Применить»). Изменение перезапускает потоки D. Файл `onvif_mode` в папке роликов:
`refuse` — камера отказывает, `ignore` — отвечает «да», но не меняет; иначе — меняет.
"""
import base64
import os
import random
import re
import signal
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
STALL_UNTIL = [0.0]


def stall(ms):
    STALL_UNTIL[0] = time.monotonic() + ms / 1000.0
    log("сбой сети по команде: потоки задержаны на %d мс" % ms)


def wait_stall():
    while time.monotonic() < STALL_UNTIL[0]:
        time.sleep(0.005)


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
    def __init__(self, title, style, port, key_late_ms=0, jitter_ms=0, rtp_info=True, line_kbps=0, loss=0.0, sensor_fps=0):
        self.title, self.style, self.port = title, style, port
        self.key_late_ms, self.jitter_ms, self.rtp_info = key_late_ms, jitter_ms, rtp_info
        # Линия (кбит/с; 0 — без ограничения), доля потерянных пакетов видео, частота датчика (0 — как у ролика).
        self.line_kbps, self.loss, self.sensor_fps = line_kbps, loss, sensor_fps
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


CLIENTS = []


class Client(threading.Thread):
    def __init__(self, camera, sock):
        super().__init__(daemon=True)
        self.camera, self.sock = camera, sock
        with LOCK:
            CONNECTIONS[0] += 1
            self.id = CONNECTIONS[0]
            CLIENTS.append(self)
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
        line_free = time.monotonic()
        try:
            while self.open:
                # Датчик быстрее потока: кадр берётся с ближайшего кадра датчика, лишние выбрасываются неравномерно.
                sensor = n * cam.sensor_fps // v.fps if cam.sensor_fps else n
                rate = cam.sensor_fps or v.fps
                due = T0 + sensor / rate
                index = n % len(v.packets)
                if v.keys[index]:
                    due += cam.key_late_ms / 1000.0
                if cam.jitter_ms:
                    due += rnd.random() * cam.jitter_ms / 1000.0
                wait = due - time.monotonic()
                if wait > 0:
                    time.sleep(wait)
                wait_stall()
                stamp = s.video_base + sensor * 90000 // rate
                if cam.line_kbps:
                    # Медленная линия: пакет уходит не раньше, чем линия освободилась от предыдущего.
                    for payload, marker in v.packets[index]:
                        packet = self.rtp(0, 96, marker, stamp, payload)
                        if cam.loss and rnd.random() < cam.loss:
                            continue
                        line_free = max(line_free, time.monotonic()) + len(packet) * 8 / (cam.line_kbps * 1000.0)
                        ahead = line_free - time.monotonic()
                        if ahead > 0.002:
                            time.sleep(ahead)
                        self.write(packet)
                        s.sent += len(packet)
                else:
                    out = bytearray()
                    for payload, marker in v.packets[index]:
                        packet = self.rtp(0, 96, marker, stamp, payload)
                        if cam.loss and rnd.random() < cam.loss:
                            continue
                        out += packet
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
                wait_stall()
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
            if self in CLIENTS:
                CLIENTS.remove(self)
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


# ---- Поддельный ONVIF камеры D (этап 4 диагностики) ----

TT = "http://www.onvif.org/ver10/schema"
ONVIF_CONFIG = {
    "v0": {"name": "mainVideo", "enc": "H264", "w": 2560, "h": 1920, "fps": 20, "gov": 20, "kbps": 6300, "sizes": [(2560, 1920), (1920, 1080), (1280, 720)]},
    "v1": {"name": "subVideo", "enc": "H264", "w": 640, "h": 480, "fps": 15, "gov": 15, "kbps": 525, "sizes": [(640, 480), (640, 360)]},
}


def soap(body):
    return ('<?xml version="1.0" encoding="UTF-8"?><s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" '
            'xmlns:tds="http://www.onvif.org/ver10/device/wsdl" xmlns:trt="http://www.onvif.org/ver10/media/wsdl" '
            'xmlns:tt="%s"><s:Body>%s</s:Body></s:Envelope>' % (TT, body)).encode("utf-8")


def encoder_xml(token, tag="trt:Configuration"):
    c = ONVIF_CONFIG[token]
    return ('<%s token="%s"><tt:Name>%s</tt:Name><tt:UseCount>1</tt:UseCount><tt:Encoding>%s</tt:Encoding>'
            '<tt:Resolution><tt:Width>%d</tt:Width><tt:Height>%d</tt:Height></tt:Resolution><tt:Quality>4</tt:Quality>'
            '<tt:RateControl><tt:FrameRateLimit>%d</tt:FrameRateLimit><tt:EncodingInterval>1</tt:EncodingInterval>'
            '<tt:BitrateLimit>%d</tt:BitrateLimit></tt:RateControl><tt:H264><tt:GovLength>%d</tt:GovLength>'
            '<tt:H264Profile>High</tt:H264Profile></tt:H264><tt:Multicast><tt:Address><tt:Type>IPv4</tt:Type>'
            '<tt:IPv4Address>0.0.0.0</tt:IPv4Address></tt:Address><tt:Port>0</tt:Port><tt:TTL>0</tt:TTL>'
            '<tt:AutoStart>false</tt:AutoStart></tt:Multicast><tt:SessionTimeout>PT60S</tt:SessionTimeout></%s>') % (
        tag, token, c["name"], c["enc"], c["w"], c["h"], c["fps"], c["kbps"], c["gov"], tag)


def onvif_reply(operation, root, host, folder, camera):
    """Ответ на запрос ONVIF (тело SOAP) или (код, тело) ошибки."""
    import xml.etree.ElementTree as ET
    if operation == "GetSystemDateAndTime":
        t = time.gmtime()
        return soap('<tds:GetSystemDateAndTimeResponse><tds:SystemDateAndTime><tt:UTCDateTime><tt:Date><tt:Year>%d</tt:Year>'
                    '<tt:Month>%d</tt:Month><tt:Day>%d</tt:Day></tt:Date><tt:Time><tt:Hour>%d</tt:Hour><tt:Minute>%d</tt:Minute>'
                    '<tt:Second>%d</tt:Second></tt:Time></tt:UTCDateTime></tds:SystemDateAndTime></tds:GetSystemDateAndTimeResponse>'
                    % (t.tm_year, t.tm_mon, t.tm_mday, t.tm_hour, t.tm_min, t.tm_sec))
    if operation == "GetDeviceInformation":
        return soap("<tds:GetDeviceInformationResponse><tds:Manufacturer>Stand</tds:Manufacturer><tds:Model>D</tds:Model>"
                    "<tds:FirmwareVersion>1.0</tds:FirmwareVersion></tds:GetDeviceInformationResponse>")
    if operation == "GetCapabilities":
        return soap('<tds:GetCapabilitiesResponse><tds:Capabilities><tt:Media><tt:XAddr>http://%s/onvif/media_service</tt:XAddr>'
                    '</tt:Media></tds:Capabilities></tds:GetCapabilitiesResponse>' % host)
    if operation == "GetProfiles":
        return soap("<trt:GetProfilesResponse>" + "".join(
            '<trt:Profiles token="00%d" fixed="true"><tt:Name>%s</tt:Name>%s</trt:Profiles>' % (
                i, ONVIF_CONFIG[t]["name"], encoder_xml(t, "tt:VideoEncoderConfiguration")) for i, t in enumerate(["v0", "v1"]))
            + "</trt:GetProfilesResponse>")
    text = ET.tostring(root, encoding="unicode")
    profile = re.search(r"ProfileToken[^>]*>([^<]+)<", text)
    token = "v1" if profile and profile.group(1).endswith("1") else "v0"
    if operation == "GetStreamUri":
        path = "/Streaming/Channels/10%d" % (2 if token == "v1" else 1)
        return soap("<trt:GetStreamUriResponse><trt:MediaUri><tt:Uri>rtsp://%s:%d%s</tt:Uri></trt:MediaUri></trt:GetStreamUriResponse>"
                    % (host.split(":")[0], camera.port, path))
    if operation == "GetVideoEncoderConfigurationOptions":
        c = ONVIF_CONFIG[token]
        sizes = "".join("<tt:ResolutionsAvailable><tt:Width>%d</tt:Width><tt:Height>%d</tt:Height></tt:ResolutionsAvailable>" % s for s in c["sizes"])
        return soap("<trt:GetVideoEncoderConfigurationOptionsResponse><trt:Options><tt:QualityRange><tt:Min>1</tt:Min><tt:Max>6</tt:Max>"
                    "</tt:QualityRange><tt:H264>%s<tt:GovLengthRange><tt:Min>1</tt:Min><tt:Max>100</tt:Max></tt:GovLengthRange>"
                    "<tt:FrameRateRange><tt:Min>1</tt:Min><tt:Max>30</tt:Max></tt:FrameRateRange><tt:EncodingIntervalRange><tt:Min>1</tt:Min>"
                    "<tt:Max>1</tt:Max></tt:EncodingIntervalRange><tt:H264ProfilesSupported>High</tt:H264ProfilesSupported></tt:H264>"
                    "<tt:Extension><tt:H264><tt:BitrateRange><tt:Min>256</tt:Min><tt:Max>8192</tt:Max></tt:BitrateRange></tt:H264>"
                    "</tt:Extension></trt:Options></trt:GetVideoEncoderConfigurationOptionsResponse>" % sizes)
    if operation == "GetVideoEncoderConfiguration":
        token = re.search(r"ConfigurationToken[^>]*>([^<]+)<", text).group(1)
        return soap("<trt:GetVideoEncoderConfigurationResponse>%s</trt:GetVideoEncoderConfigurationResponse>" % encoder_xml(token))
    if operation == "SetVideoEncoderConfiguration":
        config = next(e for e in root.iter() if e.tag.endswith("}Configuration") or e.tag == "Configuration")
        token = config.get("token")
        value = lambda name: next((e.text for e in config.iter() if e.tag.split("}")[-1] == name), None)
        mode = ""
        try:
            mode = open(os.path.join(folder, "onvif_mode")).read().strip()
        except OSError:
            pass
        if mode == "refuse" or token not in ONVIF_CONFIG:
            log("ONVIF: изменение настроек %s отклонено (режим %s)" % (token, mode or "нет такого"))
            return 400, soap('<s:Fault><s:Code><s:Value>s:Sender</s:Value><s:Subcode><s:Value>ter:InvalidArgVal</s:Value>'
                             '</s:Subcode></s:Code><s:Reason><s:Text xml:lang="en">ConfigModify</s:Text></s:Reason></s:Fault>')
        if mode != "ignore":
            c = ONVIF_CONFIG[token]
            before = dict(c)
            c.update(enc=value("Encoding") or c["enc"], w=int(value("Width") or c["w"]), h=int(value("Height") or c["h"]),
                     fps=int(value("FrameRateLimit") or c["fps"]), kbps=int(value("BitrateLimit") or c["kbps"]),
                     gov=int(value("GovLength") or c["gov"]))
            changed = ", ".join("%s %s → %s" % (k, before[k], c[k]) for k in ("enc", "w", "h", "fps", "kbps", "gov") if before[k] != c[k])
            log("ONVIF: настройки %s изменены: %s — потоки D перезапускаются" % (token, changed or "без изменений"))
            with LOCK:
                kick = [cl for cl in CLIENTS if cl.camera is camera]
            for cl in kick:
                cl.close()
        else:
            log("ONVIF: изменение %s принято, но не применено (режим ignore)" % token)
        return soap("<trt:SetVideoEncoderConfigurationResponse/>")
    return 400, soap('<s:Fault><s:Code><s:Value>s:Receiver</s:Value></s:Code><s:Reason><s:Text xml:lang="en">ActionNotSupported</s:Text>'
                     '</s:Reason></s:Fault>')


def serve_onvif(camera, bind, port, folder):
    import http.server
    import xml.etree.ElementTree as ET

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_POST(self):
            body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
            try:
                root = ET.fromstring(body)
                envelope_body = next(e for e in root if e.tag.endswith("}Body"))
                operation = list(envelope_body)[0].tag.split("}")[-1]
                reply = onvif_reply(operation, list(envelope_body)[0], self.headers.get("Host", "127.0.0.1:%d" % port), folder, camera)
            except Exception as e:  # noqa: BLE001 — стенд: любой разбор не удался — ошибка ONVIF
                log("ONVIF: запрос не разобран: %s" % e.__class__.__name__)
                reply = (400, soap("<s:Fault/>"))
            code, data = reply if isinstance(reply, tuple) else (200, reply)
            self.send_response(code)
            self.send_header("Content-Type", "application/soap+xml; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

    http.server.ThreadingHTTPServer((bind, port), Handler).serve_forever()


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
    ] + ([
        # «Плохие условия» (диагностика): как домофон 24.11 до починки — линия 10 Мбит/с, 20 к/с с датчика 25, потери.
        Camera("StandD", "hik", 8557, line_kbps=9500, loss=0.005, sensor_fps=25)
            .add("/Streaming/Channels/101", video("d_main"), audio("PCMA"))
            .add("/Streaming/Channels/102", video("d_sub"), audio("PCMA")),
    ] if os.path.exists(os.path.join(folder, "d_main.cams")) else [])


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
        if cam.title == "StandD":
            threading.Thread(target=serve_onvif, args=(cam, bind, 8580, folder), daemon=True).start()
            log("StandD: поддельный ONVIF на порту 8580")
    signal.signal(signal.SIGUSR1, lambda *_: stall(1200))
    signal.signal(signal.SIGUSR2, lambda *_: stall(150))
    log("стенд слушает (pid %d)" % os.getpid())
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
