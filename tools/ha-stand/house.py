"""Выдуманный дом стенда: сущности template поверх input_boolean (ничего настоящего). Пишет house.yaml рядом с configuration.yaml."""
import pathlib
import sys

ROOMS = {
    "Гостиная": {"floor": 0, "lights": ["Люстра", "Торшер", "Бра левое", "Бра правое", "Подсветка"], "switches": ["Розетка ТВ"], "temp": 22.4, "hum": 41},
    "Кухня": {"floor": 0, "lights": ["Люстра", "Рабочая зона"], "switches": ["Чайник", "Вытяжка"], "temp": 23.1, "hum": 47},
    "Прихожая": {"floor": 0, "lights": ["Свет в прихожей"], "switches": [], "temp": None, "hum": None},
    "Спальня": {"floor": 1, "lights": ["Ночник", "Верхний свет"], "switches": ["Обогреватель"], "fan": "Вентилятор", "temp": 20.8, "hum": 50},
    "Детская": {"floor": 1, "lights": ["Ночник"], "switches": [], "temp": 21.9, "hum": 44},
    "Двор": {"floor": None, "lights": ["Фонарь", "Прожектор"], "switches": ["Насос полива"], "gate": "Ворота", "lock": "Калитка", "temp": 7.5, "hum": 80},
}
TRANSLIT = str.maketrans(dict(zip("абвгдеёжзийклмнопрстуфхцчшщыэюя ", "abvgdeejzijklmnoprstufhccssyeua_")) | {"ъ": "", "ь": ""})


def slug(text):
    return "".join(c for c in text.lower().translate(TRANSLIT) if c.isalnum() or c == "_")


def ids():
    """(room, entity_id, kind) всех сущностей дома."""
    out = []
    for room, r in ROOMS.items():
        for n in r["lights"]:
            out.append((room, f"light.{slug(room)}_{slug(n)}", "light"))
        for n in r["switches"]:
            out.append((room, f"switch.{slug(room)}_{slug(n)}", "switch"))
        if r.get("fan"):
            out.append((room, f"fan.{slug(room)}_{slug(r['fan'])}", "fan"))
        if r.get("gate"):
            out.append((room, f"cover.{slug(room)}_{slug(r['gate'])}", "cover"))
        if r.get("lock"):
            out.append((room, f"lock.{slug(room)}_{slug(r['lock'])}", "lock"))
        if r.get("temp") is not None:
            out.append((room, f"sensor.{slug(room)}_temperature", "sensor"))
            out.append((room, f"sensor.{slug(room)}_humidity", "sensor"))
    return out


def yaml():
    booleans, blocks = [], {"light": [], "switch": [], "fan": [], "cover": [], "lock": [], "sensor": []}

    def toggle(key):
        booleans.append(key)
        on = f"{{action: input_boolean.turn_on, target: {{entity_id: input_boolean.{key}}}}}"
        off = f"{{action: input_boolean.turn_off, target: {{entity_id: input_boolean.{key}}}}}"
        return f"{{{{ is_state('input_boolean.{key}','on') }}}}", on, off

    for room, r in ROOMS.items():
        for n in r["lights"]:
            uid = f"{slug(room)}_{slug(n)}"
            state, on, off = toggle("l_" + uid)
            name = n if room.lower()[:4] in n.lower() else f"{room} {n}"
            blocks["light"].append(f"""      - name: "{name}"\n        default_entity_id: light.{uid}\n        unique_id: light_{uid}\n        state: "{state}"\n        turn_on: [{on}]\n        turn_off: [{off}]""")
        for n in r["switches"]:
            uid = f"{slug(room)}_{slug(n)}"
            state, on, off = toggle("s_" + uid)
            blocks["switch"].append(f"""      - name: "{n}"\n        default_entity_id: switch.{uid}\n        unique_id: switch_{uid}\n        state: "{state}"\n        turn_on: [{on}]\n        turn_off: [{off}]""")
        if r.get("fan"):
            uid = f"{slug(room)}_{slug(r['fan'])}"
            state, on, off = toggle("f_" + uid)
            blocks["fan"].append(f"""      - name: "{r['fan']}"\n        default_entity_id: fan.{uid}\n        unique_id: fan_{uid}\n        state: "{state}"\n        turn_on: [{on}]\n        turn_off: [{off}]""")
        if r.get("gate"):
            uid = f"{slug(room)}_{slug(r['gate'])}"
            state, on, off = toggle("c_" + uid)
            blocks["cover"].append(f"""      - name: "{r['gate']}"\n        default_entity_id: cover.{uid}\n        unique_id: cover_{uid}\n        device_class: gate\n        state: "{state}"\n        open_cover: [{on}]\n        close_cover: [{off}]\n        stop_cover: []""")
        if r.get("lock"):
            uid = f"{slug(room)}_{slug(r['lock'])}"
            state, on, off = toggle("k_" + uid)
            blocks["lock"].append(f"""      - name: "{r['lock']}"\n        default_entity_id: lock.{uid}\n        unique_id: lock_{uid}\n        state: "{{{{ not is_state('input_boolean.k_{uid}','on') }}}}"\n        lock: [{off}]\n        unlock: [{on}]""")
        if r.get("temp") is not None:
            for kind, unit, value, dc, title in (("temperature", "°C", r["temp"], "temperature", "Температура"), ("humidity", "%", r["hum"], "humidity", "Влажность")):
                uid = f"{slug(room)}_{kind}"
                blocks["sensor"].append(f"""      - name: "{title}"\n        default_entity_id: sensor.{uid}\n        unique_id: sensor_{uid}\n        device_class: {dc}\n        unit_of_measurement: "{unit}"\n        state: "{value}" """.rstrip())
    out = ["input_boolean:"] + [f"  {b}: {{}}" for b in booleans] + ["template:"]
    for kind, items in blocks.items():
        if items:
            out += [f"  - {kind}:"] + items
    return "\n".join(out) + "\n"


if __name__ == "__main__":
    pathlib.Path(sys.argv[1]).write_text(yaml())
