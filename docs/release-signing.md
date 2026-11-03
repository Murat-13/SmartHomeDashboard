# Подпись release-ключом и версии (этап 09а)

Ключ создаёт и хранит владелец. Ключ, пароли и `keystore.properties` не попадают в Git, логи и чат.

## Версии

`app/build.gradle.kts`: строка `val appVersionName = "0.9.0"`. `versionCode` выводится из неё автоматически:
`мажор·10000 + минор·100 + патч` (0.9.0 → 900, 0.9.1 → 901, 1.0.0 → 10000). Для нового выпуска меняется
только `appVersionName`, номер всегда растёт. Минор и патч — 0–99, иначе сборка остановится с ошибкой.
0.9.x — до полной регрессии (этап 10), затем 1.0.0, 1.0.1…

## Как устроена подпись

- Нет файла `keystore.properties` в корне проекта (CI, другой компьютер) — release собирается неподписанным,
  debug подписывается обычным отладочным ключом `~/.android/debug.keystore`. Сборка не падает.
- Есть файл и ключ — **и release, и debug** подписываются release-ключом (решение владельца): после
  переустановки «Run» из Android Studio ставит приложение поверх, без удаления данных.
- Файл заполнен не полностью или ключ не найден — предупреждение в выводе Gradle, подпись как при отсутствии файла.
- CI на GitHub Actions не менялся и ключа не получает.
- В GitHub Releases допустимы **только сборки `assembleRelease`**. Debug подписан тем же ключом, но отладочный;
  самообновление (с 0.9.2) такой APK отвергает: «Отладочная сборка не устанавливается».

## 1. Создать ключ (один раз, в Терминале Mac)

```bash
mkdir -p ~/.android-keys && chmod 700 ~/.android-keys
"/Applications/Android Studio Preview.app/Contents/jbr/Contents/Home/bin/keytool" -genkeypair -v \
  -keystore ~/.android-keys/smarthome-release.jks \
  -alias smarthome -keyalg RSA -keysize 4096 -validity 36500 \
  -storetype PKCS12 \
  -dname "CN=SmartHomeDashboard"
chmod 600 ~/.android-keys/smarthome-release.jks
```

`keytool` спросит пароль хранилища дважды. Для PKCS12 пароль ключа совпадает с паролем хранилища.
Срок — 100 лет: ключ нельзя сменить без новой переустановки планшетов.

## 2. Резервная копия (обязательно)

Без этого файла и пароля обновить приложение на планшетах будет невозможно — только снова переустанавливать.
Сохраните `~/.android-keys/smarthome-release.jks` и пароль **в двух местах вне Mac**, например в менеджере паролей
и на зашифрованной флешке. Не в Git, не в облачной папке проекта, не в переписке.

## 3. Файл keystore.properties

В корне проекта (`/Users/murat/StudioProjects/SmartHomeDashboard/keystore.properties`), уже в `.gitignore`:

```properties
storeFile=~/.android-keys/smarthome-release.jks
storePassword=ВАШ_ПАРОЛЬ
keyAlias=smarthome
keyPassword=ВАШ_ПАРОЛЬ
```

```bash
chmod 600 keystore.properties
git check-ignore -v keystore.properties   # должна быть строка из .gitignore; пустой вывод — файл НЕ игнорируется, не коммитить
git status --short                        # keystore.properties в списке быть не должно
```

## 4. Собрать release и проверить подпись

```bash
cd /Users/murat/StudioProjects/SmartHomeDashboard
export JAVA_HOME="/Applications/Android Studio Preview.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"   # apksigner без этого не найдёт Java
./gradlew assembleRelease --offline
# APK: app/build/outputs/apk/release/app-release.apk
"$HOME/Library/Android/sdk/build-tools/$(ls $HOME/Library/Android/sdk/build-tools | sort -V | tail -1)/apksigner" \
  verify --print-certs app/build/outputs/apk/release/app-release.apk
```

В выводе должен быть `CN=SmartHomeDashboard` (не `CN=Android Debug`). Отпечаток SHA-256 сертификата можно
записать рядом с резервной копией ключа — по нему на этапе 09б приложение сверяет подпись обновлений.

## 5. Переустановка московского планшета (делает владелец)

Подписи старой и новой версии разные, поэтому установка поверх невозможна; данные приложения при удалении
стираются. Порядок:

1. На планшете: «Настройки → Устройство → Сделать копию сейчас» и/или «Экспорт». Убедиться, что файл лежит
   в папке, которая переживает удаление (выбранная папка SAF или «Загрузки»), а не внутри приложения.
2. Записать отдельно (не в файл копии): токен HA, ключ Яндекса, PIN — они в копию не входят.
3. Удалить приложение.
4. Установить новый APK: Android Studio «Run» (с `keystore.properties` debug тоже подписан новым ключом) или
   `adb install app/build/outputs/apk/release/app-release.apk`.
5. Импорт копии → ввести токен HA («Проверить и сохранить»), ключ Яндекса, задать PIN.
6. Проверить панель, кнопки, автокопии.

⚠ После появления `keystore.properties` «Run» из Android Studio на планшет со **старой** подписью
(чеченский, московский до переустановки) завершится ошибкой `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
Если Android Studio предложит «Uninstall and continue» — **отказаться**: это удалит приложение и все данные.
Чеченский планшет переустанавливается только при визите владельца; до этого его не трогать.
