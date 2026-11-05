# Подпись и выпуск beta

Репозиторий предназначен для самостоятельного размещения в корне GitHub: `.github/`, `android/`, `desktop/` и `Cargo.toml` находятся на одном уровне. Рабочие ключи, состояние и `artifacts/` не включаются в Git.

## Постоянная Android-подпись

Публичные данные находятся в `android/signing/`: сертификаты, `lineage.bin` и `signing-policy.json`. Они не содержат приватных ключей. Release-подпись: `8e72d5335512cc240d3eb8804b802e636b894e747161e27becbc8471c88bb4b4`.

В beta использована ротация с прежнего сертификата через APK Signature Scheme v3. `rotation-min-sdk-version=28`, minSdk приложения — 29. Все поддерживаемые версии Android умеют проверять эту цепочку. Alias Android Keystore и applicationId `app.lanvault.android` сохранены. Старый приватный ключ для будущих выпусков не нужен; храните постоянный release-ключ и полную публичную цепочку. Не пересоздавайте release-ключ для каждой сборки.

Подпись выполняется после `:app:assembleRelease`; Gradle создаёт `app-release-unsigned.apk`. Пароли поступают из окружения процесса, а не из аргументов команд или файлов исходников:

```powershell
./tooling/sign-android.ps1 -UnsignedApk android/app/build/outputs/apk/release/app-release-unsigned.apk -OutputApk artifacts/candidate.apk -KeyStore <private-release.p12> -Lineage android/signing/lineage.bin
```

Нужны `LANVAULT_KEYSTORE_PASSWORD` и, если отличается, `LANVAULT_KEY_PASSWORD`; alias по умолчанию `lanvault`. Скрипт запрещает подпись debuggable APK. Проверка `CheckApk.java` использует библиотеку apksig из Android Build Tools и проверяет SDK 29, 32, 33, 37, fingerprint, прежний сертификат в цепочке и право сохранения installed data:

```powershell
java --class-path "$env:ANDROID_HOME/build-tools/36.0.0/lib/apksigner.jar" tooling/CheckApk.java artifacts/candidate.apk 8e72d5335512cc240d3eb8804b802e636b894e747161e27becbc8471c88bb4b4 android/signing/previous-certificate.der
```

Для локального хранилища с DPAPI-паролем используйте `backup-signing-key.ps1`: он интерактивно запрашивает отдельную длинную фразу и создаёт переносимый зашифрованный PKCS12. Сохраните резервную копию офлайн, а фразу — отдельно. DPAPI-файл сам по себе привязан к пользователю Windows и не является переносимой резервной копией пароля.

Не публикуйте `.p12`, `.keystore`, `.dpapi` или значения секретов. Разработчик форка должен использовать собственный ключ; его APK не является обновлением официальной установленной сборки.

## GitHub Actions

Включите Actions и Private vulnerability reporting. Настройте следующие Actions secrets для workflow выпуска:

| Secret                         | Содержание                                               |
| ------------------------------ | -------------------------------------------------------- |
| `ANDROID_KEYSTORE_BASE64`      | Base64 постоянного приватного PKCS12                     |
| `ANDROID_KEYSTORE_PASSWORD`    | Пароль хранилища                                         |
| `ANDROID_KEY_PASSWORD`         | Пароль ключа; допускается совпадение с паролем хранилища |
| `WINDOWS_CERTIFICATE_BASE64`   | Необязательный Authenticode PFX                          |
| `WINDOWS_CERTIFICATE_PASSWORD` | Пароль необязательного PFX                               |

Секреты подписи используются только в ручном workflow `Release candidate`, не в проверках pull request. Ключ разворачивается во временном каталоге runner вне исходников и удаляется после подписи. Actions закреплены по commit SHA, permissions ограничены; отдельная задача создания черновика имеет `contents:write`.

`CI` собирает и проверяет Windows, Android, реальную HTTPS-совместимость, lifecycle загрузок, зависимости, секреты и instrumentation на Android API 29/35. Локальные проверки workflow не являются фактом его выполнения на GitHub.

Ручной `Release candidate` проверяет код, собирает приложения, подписывает Android, упаковывает исходники и Windows, проверяет состав архивов и создаёт **draft prerelease**. Перед публикацией просмотрите файлы, результаты CI и матрицу испытаний. Первая загрузка в GitHub и настройка secrets выполняются владельцем репозитория.

## Windows и состав выпуска

Без Authenticode-сертификата допускается только явно обозначенная неподписанная beta с SHA-256. Не подменяйте это самоподписанным сертификатом, выдавая его за доверенную подпись. Если сертификат имеется, применяется `sign-windows.ps1` и обязательная проверка `signtool verify /pa`.

Пакет Windows включает `lanvault-desktop.exe`, `WebView2Loader.dll`, `INSTALL.md`, `LICENSE`, `THIRD_PARTY_NOTICES.txt` и узкий ручной скрипт брандмауэра. GNU-сборка использует системный MSVCRT; дополнительные зависимости проверяются по PE imports. WebView2 Runtime устанавливается отдельно, если его нет.

Уведомления зависимостей также включены в APK как `assets/third-party-notices.txt`. При изменении зависимостей обновляйте инвентарь и лицензии через `tooling/generate-notices.py`; происхождение дополнительных текстов с SHA-256 записано в `licenses/third-party/sources.json`. Лицензия MIT проекта не заменяет лицензии зависимостей.

```powershell
python tooling/package-beta.py --output artifacts/release --windows-directory target/release --android-apk artifacts/candidate.apk
python tooling/verify-beta.py artifacts/release
```

Создаются source ZIP, Windows ZIP, APK и `SHA256SUMS.txt`. Source ZIP содержит только Git-кандидаты, проходит проверку приватных файлов и собственный manifest SHA-256. Подготовленный снимок дополнительно проверяется Gitleaks. `package-beta.py` не отправляет ничего в GitHub.

## Совместимость существующих установок

Android обновляется поверх старой версии. Подпись с корректной цепочкой уже проверена Android verifier; ручная установка на исходный Pixel также подтвердила сохранение компьютера и начало копирования. Тестирование иных старых подписей не заявляется.

Windows identity и таблицы доверия не пересоздаются. `upload_leases` — добавочная таблица в совместимой schema 1. При переносе состояния используйте согласованный SQLite backup и сохраняйте `identity.dpapi` в том же пользовательском контексте. Уже подтверждённые файлы не участвуют в удалении незавершённых заданий.

В управляемой среде с перенаправлением AppData можно явно задать постоянный локальный путь: `lanvault-desktop.exe --state-dir "C:\LanVault-data"`, либо `LANVAULT_STATE_DIR`. Защита от reparse points сохраняется. Ярлык существующей установки может закрепить этот путь. Для ASTER и брандмауэра путь EXE также следует сохранять либо явно перерегистрировать исключение.
