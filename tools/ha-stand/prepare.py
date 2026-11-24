#!/usr/bin/env python3
"""Стенд Home Assistant: первый запуск (пользователь, комнаты, сущности по комнатам). Запускать python из venv стенда
(нужен aiohttp). Пароли стенда — случайные, лежат рядом со стендом в secrets.json, в репозиторий не входят.

    venv/bin/python prepare.py <папка стенда> <адрес, например http://127.0.0.1:8124>
"""
import asyncio
import json
import pathlib
import secrets
import sys

import aiohttp

ROOT = pathlib.Path(sys.argv[1])
BASE = sys.argv[2].rstrip("/")
CLIENT = BASE + "/"
ROOMS = {  # комната → какие сущности demo в неё положить (по началу имени)
    "Гостиная": ["light.living_room", "light.ceiling", "climate.heatpump", "media_player.living_room", "sensor.outside_temperature"],
    "Кухня": ["light.kitchen", "switch.decorative_lights", "sensor.carbon_monoxide", "binary_sensor.basement_floor_wet"],
    "Спальня": ["light.bed_light", "climate.ecobee", "cover.living_room_window", "sensor.outside_humidity"],
    "Двор": ["lock.", "cover.garage_door", "camera.", "binary_sensor.movement_backyard"],
}


class Ws:
    def __init__(self, ws):
        self.ws, self.n = ws, 0

    async def call(self, type_, **data):
        self.n += 1
        await self.ws.send_json({"id": self.n, "type": type_, **data})
        while True:
            m = await self.ws.receive_json()
            if m.get("id") == self.n and m.get("type") == "result":
                return m


async def connect(session, token):
    ws = await session.ws_connect(BASE.replace("http", "ws", 1) + "/api/websocket")
    await ws.receive_json()
    await ws.send_json({"type": "auth", "access_token": token})
    assert (await ws.receive_json())["type"] == "auth_ok"
    return Ws(ws)


async def main():
    users = {"owner": secrets.token_urlsafe(12), "guest": secrets.token_urlsafe(12)}
    (ROOT / "secrets.json").write_text(json.dumps(users))
    (ROOT / "secrets.json").chmod(0o600)
    async with aiohttp.ClientSession() as s:
        async with s.post(BASE + "/api/onboarding/users", json={"client_id": CLIENT, "name": "Владелец стенда",
                          "username": "owner", "password": users["owner"], "language": "ru"}) as r:
            code = (await r.json())["auth_code"]
        async with s.post(BASE + "/auth/token", data={"grant_type": "authorization_code", "code": code, "client_id": CLIENT}) as r:
            tokens = await r.json()
        head = {"Authorization": "Bearer " + tokens["access_token"]}
        for step in ("core_config", "analytics"):
            await s.post(BASE + "/api/onboarding/" + step, headers=head, json={})
        await s.post(BASE + "/api/onboarding/integration", headers=head, json={"client_id": CLIENT, "redirect_uri": CLIENT})
        ws = await connect(s, tokens["access_token"])
        # Обычный пользователь (не администратор): что панель может с его доступом.
        user = (await ws.call("config/auth/create", name="Гость стенда", group_ids=["system-users"], local_only=False))["result"]["user"]
        await ws.call("config/auth_provider/homeassistant/create", user_id=user["id"], username="guest", password=users["guest"])
        # При первом запуске Home Assistant сам создаёт «Гостиная», «Кухня», «Спальня».
        areas = {a["name"]: a["area_id"] for a in (await ws.call("config/area_registry/list"))["result"]}
        for room in ROOMS:
            if room not in areas:
                areas[room] = (await ws.call("config/area_registry/create", name=room))["result"]["area_id"]
        entities = [e["entity_id"] for e in (await ws.call("config/entity_registry/list"))["result"]]
        placed = 0
        for room, starts in ROOMS.items():
            for e in entities:
                if any(e.startswith(x) for x in starts):
                    ok = await ws.call("config/entity_registry/update", entity_id=e, area_id=areas[room])
                    placed += ok.get("success", False)
        print("сущностей в реестре:", len(entities), "· разложено по комнатам:", placed)


asyncio.run(main())
