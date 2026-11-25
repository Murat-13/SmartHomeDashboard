#!/usr/bin/env python3
"""Известные уязвимости библиотек рабочей сборки (база OSV, https://osv.dev).

Читает дерево зависимостей Gradle (stdin: вывод `./gradlew :app:dependencies --configuration releaseRuntimeClasspath`),
спрашивает OSV по каждой библиотеке и версии и завершается с ошибкой, если есть запись, которой нет в .github/osv-ignore.txt.
В запросах — только открытые имена и версии библиотек.
"""
import json, os, re, sys, time, urllib.request

def coordinates(text):
    found = set()
    for line in text.splitlines():
        m = re.search(r"[+\\]--- ([\w.\-]+):([\w.\-]+):([^\s]+)(?: -> ([^\s]+))?", line)
        if not m or "(n)" in line: continue
        version = (m.group(4) or m.group(3)).rstrip("(*c)").strip()
        version = re.sub(r"^\{strictly (.+)\}$", r"\1", version)
        if version and not version.startswith("project"): found.add((m.group(1) + ":" + m.group(2), version))
    return sorted(found)

def ask(packages):
    body = json.dumps({"queries": [{"package": {"ecosystem": "Maven", "name": n}, "version": v} for n, v in packages]}).encode()
    for attempt in range(6):
        try:
            request = urllib.request.Request("https://api.osv.dev/v1/querybatch", body, {"Content-Type": "application/json"})
            with urllib.request.urlopen(request, timeout=60) as answer: return json.load(answer)["results"]
        except Exception as e:
            if attempt == 5: raise
            print("OSV не ответила (%s) — повтор" % type(e).__name__); time.sleep(10)

def main():
    packages = coordinates(sys.stdin.read())
    if len(packages) < 20: sys.exit("Зависимостей найдено %d — дерево не прочитано" % len(packages))
    here = os.path.dirname(os.path.abspath(__file__))
    ignored = set()
    with open(os.path.join(here, "osv-ignore.txt"), encoding="utf-8") as f:
        for line in f:
            line = line.split("#")[0].strip()
            if line: ignored.add(line)
    bad = []
    for i in range(0, len(packages), 100):
        part = packages[i:i + 100]
        for (name, version), result in zip(part, ask(part)):
            for vuln in result.get("vulns", []):
                if vuln["id"] not in ignored: bad.append("%s %s — %s (https://osv.dev/vulnerability/%s)" % (name, version, vuln["id"], vuln["id"]))
    print("Проверено библиотек: %d" % len(packages))
    if bad:
        print("Известные уязвимости:\n" + "\n".join(bad))
        sys.exit(1)
    print("Известных уязвимостей нет (кроме принятых в .github/osv-ignore.txt)")

if __name__ == "__main__": main()
