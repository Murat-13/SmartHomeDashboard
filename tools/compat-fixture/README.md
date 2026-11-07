# Генератор образцов совместимости

Образцы `app/src/test/resources/compat/v*.json` (проверяет `DataCompatibilityTest`, см. docs/deep-testing.md)
записывает код **той версии**, данные которой нужны. Порядок:

```bash
git worktree add /tmp/compat-v0.9.X v0.9.X
cp tools/compat-fixture/CompatFixtureGenerator.kt /tmp/compat-v0.9.X/app/src/test/java/com/example/smarthomedashboard/
# в копии заменить "VERSION" на "v0.9.X"
cd /tmp/compat-v0.9.X && ./gradlew testDebugUnitTest --tests '*CompatFixtureGenerator*'
cp app/build/compat-fixture.json <repo>/app/src/test/resources/compat/v0.9.X.json
git worktree remove /tmp/compat-v0.9.X
```

Генератор создаёт плитки всех типов, которые есть в этой версии, её собственными шаблонами (`newTileConfig`),
с искусственными сущностями и адресом `ha.example.invalid`; реальных данных в образцах нет.
Файл лежит вне `app/src`, поэтому в сборку не входит.
