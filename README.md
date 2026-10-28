# SmartHomeDashboard

[![Android CI](https://github.com/Murat-13/SmartHomeDashboard/actions/workflows/android-ci.yml/badge.svg?branch=main)](https://github.com/Murat-13/SmartHomeDashboard/actions/workflows/android-ci.yml)

Настенная панель умного дома для [Home Assistant](https://www.home-assistant.io/):
Android-приложение на Kotlin для планшета, который постоянно висит на стене.

## Рабочая ветка

Актуальная общая версия проекта находится в `main`. Агенты работают с ней
по очереди; правила сохранения данных, проверок и публикации — в [AGENTS.md](AGENTS.md).

## Возможности

- Плитки датчиков: сеть, АКБ с заливкой по проценту заряда, температуры.
- Часы и погода (из Home Assistant или Яндекс Погоды).
- Кнопки управления (свет, бойлер).
- Подключение к Home Assistant по WebSocket: сначала локальный адрес,
  при недоступности внешний; автоматическое переподключение.
- PIN-код на настройки, режим киоска.
- Экспорт и импорт настроек (без токенов и ключей).

## Безопасность

- Токен Home Assistant и ключ Яндекса хранятся зашифрованно
  (AES-GCM, ключ в Android Keystore) и не попадают в резервные копии.
- PIN хранится только в виде хэша (PBKDF2), после нескольких ошибок ввод блокируется.
- Файл экспорта настроек не содержит секретов.

## Сборка

Нужны JDK 21 и Android SDK (platform 34). Минимальная версия Android: 7.0 (API 24).

```bash
./gradlew assembleDebug
```

Готовый APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Тесты

Юнит-тесты (Robolectric) и сборка инструментальных тестов, как в CI:

```bash
./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest
```

Инструментальные тесты (`app/src/androidTest`) запускаются на подключённом устройстве:

```bash
./gradlew connectedDebugAndroidTest
```

## CI

Workflow [`android-ci.yml`](.github/workflows/android-ci.yml) запускается на каждом
pull request и при каждом push в `main`: юнит-тесты, сборка debug-APK и сборка
инструментальных тестов. Если что-то упало, отчёты тестов прикладываются
к запуску как артефакт `test-reports`.

## Архитектура

Границы Activity, ViewModel, контроллеров панели и виджетов, жизненный цикл,
поток HA-событий и проверки описаны в [docs/architecture.md](docs/architecture.md).
