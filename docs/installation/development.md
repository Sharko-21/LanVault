# Сборка и запуск для разработчика

Все команды выполняются из папки `lanvault`, если не указано другое. На Windows используйте PowerShell 7. Перед сборкой выполните `pnpm doctor`.

## Web

Установите Node.js 24 и pnpm 11.25.0. Возможен Corepack: `corepack prepare pnpm@11.25.0 --activate`, если Corepack установлен отдельно.

```powershell
pnpm install --frozen-lockfile
pnpm check
pnpm dev
```

Адрес разработки: <http://127.0.0.1:1420>. Продакшен-предпросмотр: `pnpm --filter @lanvault/desktop preview`, адрес <http://127.0.0.1:1421> после `pnpm build:web`.

## Native Windows

1. Установите Visual Studio Build Tools и workload `Desktop development with C++`, включая Windows SDK.
2. Установите Rust через официальный rustup, host `x86_64-pc-windows-msvc`. Файл `rust-toolchain.toml` фиксирует 1.94.0, rustfmt и clippy.
3. Убедитесь в наличии WebView2.
4. Установите npm-зависимости командой выше.

```powershell
node tooling/doctor.mjs --target=desktop --strict
cargo fmt --all --check
cargo test --locked -p lanvault-domain -p lanvault-protocol -p lanvault-storage -p lanvault-platform-windows -p lanvault-database -p lanvault-server
pnpm build:web
cargo clippy --locked --workspace --all-targets -- -D warnings
pnpm dev:desktop
pnpm build:desktop
```

`cargo clippy` для оболочки требует предварительного `pnpm build:web`, поскольку Tauri встраивает frontend. Файл приложения находится в `target/release/lanvault-desktop.exe`. Установщик, автозапуск и подпись релиза пока отсутствуют.

Выберите существующую обычную папку NTFS вне OneDrive. Настройка сохраняется в `%LOCALAPPDATA%/app.lanvault.desktop/server/catalog.sqlite`, ключ CA защищён DPAPI. После выбора папки выберите физическую частную сеть и нажмите «Включить приём». Порт: 43827/TCP, только выбранный адрес. Сеть без профиля Windows «Частная» не предлагается. Профиль и брандмауэр автоматически не меняются.

«Подключить телефон» показывает QR на 120 секунд. Доверие выдаётся только после сравнения кодов и подтверждения на ПК. Android MVP реализован на этапе 4: [инструкция](android-mvp.md). Для независимой проверки сервера также доступен `tooling/test-server.py`. Закрытие окна скрывает его в трей; меню позволяет открыть окно, приостановить/продолжить, остановить приём и выйти. На первом запуске приём выключен; после настройки приложение восстанавливает сохранённый приём в выбранной доступной частной сети.

```powershell
cargo build --locked -p lanvault-server
python tooling/test-server.py --exe target/debug/lanvaultd.exe
```

Проверка создаёт только синтетические файлы во временной папке, использует loopback и проверенный TLS, убирает свои процессы/файлы. `--loopback-test` — явный режим CLI для теста, он отсутствует в командах Desktop. При перезапуске CLI без `--backup-dir` восстанавливает сохранённую папку.

На текущей машине дополнительно подготовлен локальный GNU toolchain (Rust 1.94.0 и LLVM-MinGW 20261020 MSVCRT), без системной установки. Для него:

```powershell
. .\tooling\use-portable-rust.ps1
.\tooling\check.ps1 -Target Desktop -PortableRust
$env:LANVAULT_DEV_STATE_DIR = Join-Path $env:TEMP 'LanVault-Desktop-MVP-state'
& .\target\gnu\debug\lanvault-desktop.exe
```

Это отладочная сборка со встроенным frontend. `LANVAULT_DEV_STATE_DIR` — явное переопределение только debug-сборки: в управляемой среде AppData перенаправляется и отклоняется политикой no-reparse. Каталог должен быть отдельным, локальным и обычным; защита и DPAPI остаются включены. Release игнорирует отладочную переменную. Для явного постоянного каталога обе сборки поддерживают `--state-dir` и `LANVAULT_STATE_DIR`; см. [установку beta](beta.md). Рядом должен оставаться `WebView2Loader.dll`. Каталоги `.tools` и `.cache` игнорируются Git и не поставляются с исходниками; на другой машине используйте обычный MSVC-путь выше. GNU-проверка не заменяет приёмку релиза MSVC, установщика и подписи.

## Android

Установите JDK 17 и Android Studio, поддерживающую API 37/AGP 9.1.1. Через SDK Manager установите Android SDK Platform 37, Build Tools 36.0.0 и platform-tools, ознакомьтесь с лицензиями SDK.

Настройте переменные только для текущего PowerShell-сеанса, подставив реальные пути:

```powershell
$env:JAVA_HOME = 'C:\Path\To\jdk-17'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"
node tooling/doctor.mjs --target=android --strict
cd android
.\gradlew.bat --version
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
.\gradlew.bat :app:lintDebug testDebugUnitTest :core:model:test
```

Также можно открыть каталог `android` в Android Studio. Пользовательские абсолютные пути храните в игнорируемом `android/local.properties` (`sdk.dir`), не в Gradle-скриптах.

Для телефона/эмулятора Android 10+:

```powershell
adb devices
.\gradlew.bat :app:installDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```

В Android MVP доступны MediaStore, ограниченный/полный доступ к медиа, QR-подключение и ручная передача. [Порядок запуска и проверки](android-mvp.md). Диагностические классы и checkpoint-тест этапа 2 сохранены; основной экран больше не показывает заготовку проб.

На текущей машине подготовлен локальный JDK/SDK. Windows Gradle не загружает тестовые классы из кириллического пути, поэтому воспроизводимая проверка использует точную копию входных файлов в ASCII TEMP:

```powershell
.\tooling\check-android.ps1 -Portable -DesktopServerExe .\target\gnu\debug\lanvaultd.exe
```

Скрипт сверяет SHA-256 исходников, собирает APK и тестовый APK, запускает lint и 30 JVM-тестов, включая 9 против настоящего Desktop. Без `-DesktopServerExe` девять Windows interop-тестов пропускаются явно. Готовые APK находятся в `artifacts/android`; это debug-сборки, не подписанный релиз. Instrumentation требует доступного Android-устройства или рабочего эмулятора.

## Общий Windows-скрипт

```powershell
.\tooling\check.ps1 -Target Web
.\tooling\check.ps1 -Target Desktop
.\tooling\check.ps1 -Target Server
.\tooling\check.ps1 -Target Android
.\tooling\check.ps1 -Target All
```

`All` продолжает независимые проверки после ошибки одного toolchain, а в конце возвращает ненулевой код при любой ошибке. Если политика PowerShell блокирует запуск, ознакомьтесь со скриптом и запускайте согласно принятой на вашем компьютере политике; проект её не изменяет.

CI расположен в родительском Git-корне: `.github/workflows/lanvault-ci.yml`. Он не запускался автоматически из локальной папки. Для отдельного репозитория с содержимым `lanvault` в корне нужно перенести workflow и убрать префикс `lanvault/` из путей.
