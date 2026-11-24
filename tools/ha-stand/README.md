# Стенд «настоящий Home Assistant»

Чистый Home Assistant на Mac — проверять поиск, вход и первые плитки, не трогая настоящий дом (docs/setup-tz.md). Сам
Home Assistant в репозиторий не входит: папка `~/StudioProjects/SmartHomeDashboard-ha-stand` (uv, Python 3.14.10,
homeassistant 2026.11.3), в систему ничего не ставится, удаляется целиком.

```bash
H=~/StudioProjects/SmartHomeDashboard-ha-stand
. $H/env.sh && $H/venv/bin/hass -c $H/config
```

- `configuration.yaml` — настройки; в папке стенда добавлен `server_host: 127.0.0.1` (наружу не виден). Для планшета на
  время проверки строка убирается — и только после `prepare.py`: ненастроенный Home Assistant в домашней сети может
  занять любой.
- `house.py <папка стенда>/config/house.yaml` — выдуманный дом: 6 комнат, 31 сущность поверх `input_boolean` (подключён в
  `configuration.yaml`).
- `prepare.py <папка стенда> <адрес>` — первый запуск: администратор, обычный пользователь, этажи, комнаты, сущности по комнатам. Пароли случайные, в
  `secrets.json` рядом со стендом (права 600), в репозиторий не входят и не печатаются. Повтор с нуля — остановить стенд
  и удалить `config/.storage`.
