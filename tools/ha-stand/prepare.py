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

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import house  # noqa: E402

ROOT = pathlib.Path(sys.argv[1])
BASE = sys.argv[2].rstrip("/")
CLIENT = BASE + "/"
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
        # Настройки http из файла Home Assistant 2026.11 держит «на пробу» 5 минут и без подтверждения откатывает.
        await ws.call("http/config/promote")
        # Обычный пользователь (не администратор): что панель может с его доступом.
        user = (await ws.call("config/auth/create", name="Гость стенда", group_ids=["system-users"], local_only=False))["result"]["user"]
        await ws.call("config/auth_provider/homeassistant/create", user_id=user["id"], username="guest", password=users["guest"])
        # При первом запуске Home Assistant сам создаёт «Гостиная», «Кухня», «Спальня».
        floors = {}
        for name, level in (("Первый этаж", 0), ("Второй этаж", 1)):
            floors[level] = (await ws.call("config/floor_registry/create", name=name, level=level))["result"]["floor_id"]
        areas = {a["name"]: a["area_id"] for a in (await ws.call("config/area_registry/list"))["result"]}
        for room, r in house.ROOMS.items():
            extra = {"floor_id": floors[r["floor"]]} if r["floor"] is not None else {}
            if room in areas:
                await ws.call("config/area_registry/update", area_id=areas[room], **extra)
            else:
                areas[room] = (await ws.call("config/area_registry/create", name=room, **extra))["result"]["area_id"]
        known = {e["entity_id"] for e in (await ws.call("config/entity_registry/list"))["result"]}
        placed, missing = 0, []
        for room, entity, _ in house.ids():
            if entity not in known:
                missing.append(entity)
                continue
            placed += (await ws.call("config/entity_registry/update", entity_id=entity, area_id=areas[room])).get("success", False)
        print("сущностей дома:", len(house.ids()), "· разложено по комнатам:", placed, "· нет в реестре:", missing)


asyncio.run(main())
