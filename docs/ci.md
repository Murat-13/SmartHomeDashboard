# CI и Android Lint (0.9.2, этап 7)

Workflow: [.github/workflows/android-ci.yml](../.github/workflows/android-ci.yml) — на каждый PR и push в `main`.

Глубокие проверки (эмулятор с фальшивым HA, RTSP-стенд, ночной soak, хаос-тесты) — отдельный workflow
[.github/workflows/deep-tests.yml](../.github/workflows/deep-tests.yml), см. [deep-testing.md](deep-testing.md).

| Шаг | Команда | При падении |
|---|---|---|
| Тесты и сборка | `testDebugUnitTest assembleDebug assembleDebugAndroidTest assembleRelease` | артефакт `test-reports` |
| Lint (выполняется и при упавших тестах) | `lintDebug` | артефакт `lint-report` (html/xml/txt) |

- **Release в CI неподписанный**: `keystore.properties` и ключа в CI нет и не будет; `app/build.gradle.kts` без файла
  подпись не настраивает. Схема подписи на Mac владельца не менялась.
- **Lint роняет CI только ошибками** (решение владельца 03.11.2026). `abortOnError` не отключён, категории не
  выключены, `lint-baseline.xml` не создавался: после исправления двух настоящих ошибок ошибок lint нет.
- Исправлено: `ModernPowerEditor` — `importantForAutofill` (API 26) вызывался без проверки версии при minSdk 24
  (`NewApi`, на Android 7 — `NoSuchMethodError`); теперь только на Android 8+.
- Проверено, что новая ошибка lint роняет проверку: временный файл с тем же вызовом без проверки версии →
  `lintDebug` завершается ошибкой `NewApi`; файл удалён.

## Предупреждения (317, не блокируют)

HardcodedText 123, SetTextI18n 48, UnusedResources 44, UseKtx 35, DrawAllocation 14, Overdraw 10, UseTomlInstead 8,
ClickableViewAccessibility 8, ViewConstructor 6, ApplySharedPref 4, GradleDependency 2, DisableBaselineAlignment 2,
UsableSpace 2, ButtonStyle 2 и по одному: OldTargetApi, CustomSplashScreen, UseSwitchCompatOrMaterialCode,
InsecureBaseConfiguration, NotifyDataSetChanged, NestedWeights, TooManyViews, Autofill, ContentDescription.

Большинство — сознательный стиль проекта (русские тексты прямо в разметке и коде) или темы вне 0.9.2:
`OldTargetApi`/`GradleDependency` — обновление targetSdk и библиотек (отдельная задача владельца),
`InsecureBaseConfiguration` — HTTP разрешён ради локального адреса HA (загрузка обновлений ограничена HTTPS в коде),
`UnusedResources` — кандидаты на чистку отдельным этапом.

## Память JVM-тестов (20.11.2026)

Robolectric копит память от класса к классу; к ~1450-му тесту куча 512 МБ (по умолчанию Gradle) кончалась на CI —
`OutOfMemoryError`, процесс тестов терял потоки, и падали следующие тесты (обычно погода, по тайм-ауту — похоже на
нестабильность, но причина — память). Теперь в `app/build.gradle.kts`: куча 1 ГБ и новый процесс каждые 300 тестов.
Переопределение: `-Ptest.maxHeap=…`, `-Ptest.forkEvery=…` (0 — один процесс), снимок кучи — `-Ptest.heapDump=<папка>`.
Проверено локально: 384 МБ одним процессом — процесс тестов падает (как на CI); 512 МБ с процессом на 300 тестов —
1523 теста, 0 падений.

detekt запускать так же, как Deep tests: с `--build-upon-default-config` (без флага часть правил не включается).

## Известные уязвимости библиотек (0.9.72)

Шаг «Известные уязвимости библиотек (OSV)» в Deep tests: `.github/osv.py` читает дерево зависимостей рабочей сборки
(`releaseRuntimeClasspath`) и спрашивает базу [OSV](https://osv.dev) — в запросах только открытые имена и версии библиотек. Новая запись
роняет проверку; принятые (с объяснением, почему пути атаки нет) — в `.github/osv-ignore.txt`. Локально:

```bash
./gradlew :app:dependencies --configuration releaseRuntimeClasspath | python3 .github/osv.py
```
