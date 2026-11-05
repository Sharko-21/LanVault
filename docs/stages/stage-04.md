# Этап 4 — Android MVP

Реализован Android-клиент, совместимый с Desktop этапа 3. Проверки выполнены 4 ноября 2026. Готовые файлы:

- Основной debug APK: `artifacts/android/app-debug.apk`, пакет `app.lanvault.android`, версия `0.1.0-stage4`, minSdk 29, targetSdk 37.
- Тестовый APK: `artifacts/android/app-debug-androidTest.apk`.
- [Инструкция установки и запуска](../installation/android-mvp.md).
- [Список изменённых файлов](stage-04-files.txt).

## Реализовано

MediaStore показывает доступные фото и видео по страницам, исключает pending/trash, различает полный и ограниченный доступ, позволяет выбрать до 100 файлов. Читаются content URI, исходные метаданные и оригинальный формат через MediaStore API. Отсутствие требуемого разрешения явно блокирует отправку оригинала.

Подключение: офлайн-сканер QR, проверка CA/SPKI/hostname/версии/TTL и private IP, локальное подтверждение совпадающих кодов на Desktop. Токен отдельный для телефона. Trust/pending claim защищены AES-256-GCM с ключом Android Keystore и AtomicFile в noBackupFilesDir. Сохранение credentials предшествует confirm-received, незавершённая выдача восстанавливается.

Транспорт привязан к одному Wi-Fi Network и его SocketFactory, проверяет private IPv4 той же подсети, отклоняет VPN/cellular, не использует внешний DNS, proxy или HTTP redirects. Интернет-валидация сети не требуется. Android 17 запрашивает ACCESS_LOCAL_NETWORK до соединения. Потеря сети закрывает запрос; запасного маршрута через мобильную сеть нет.

Ручная отправка выполняется последовательно: полный SHA-256 → проверка версии источника → Room-журнал с устойчивыми UUID → create → потоковый PUT → complete → проверка всех полей receipt → локальная COMMITTED-запись. Буфер 128 КиБ, счётчики Long, wire-размер строкой. Есть прогресс, скорость, история, остановка и повтор. При уходе с экрана coroutine и HTTP call отменяются; источник на телефоне не удаляется. После перезапуска незавершённые SQL-стадии получают INTERRUPTED. Повтор сохраняет request UUID и не пересылает уже подтверждённые байты; исчезнувшая на ПК копия восстанавливается новым request по явному ответу сервера.

Вспомогательные модули и probes этапа 2 сохранены; основной экран теперь использует настоящее ядро. Добавлен JVM-модуль `core:transport` для независимой проверки протокола. [Решения и границы](../adr/0014-android-mvp.md).

## Результаты проверок

| Проверка                                                    | Результат                                                                          |
| ----------------------------------------------------------- | ---------------------------------------------------------------------------------- |
| `:app:assembleDebug`                                        | PASS, настоящий APK собран                                                         |
| `:app:assembleDebugAndroidTest`                             | PASS, instrumentation APK собран                                                   |
| `:app:lintDebug`                                            | PASS: 0 ошибок, 20 предупреждений о новых версиях pinned-инструментов/зависимостей |
| `:core:model:test`                                          | PASS: 2 теста, включая общие wire fixtures и точность больших чисел                |
| `:core:security:testDebugUnitTest`                          | PASS: 3 checkpoint/SHA-256 теста                                                   |
| `:core:transport:test`                                      | PASS: 16 тестов доверия/адресов/подтверждений + 9 настоящих HTTPS interop-тестов   |
| Все JVM-тесты                                               | **30 tests, 0 failures, 0 errors, 0 skipped**                                      |
| `tooling/check-android.ps1 -Portable -DesktopServerExe ...` | PASS, сборка/lint/тесты/проверка входов и выдача APK                               |
| APK manifest через `aapt2 dump badging`                     | PASS: версия stage4, minSdk 29, targetSdk 37, необходимые разрешения               |
| ktfmt 0.64                                                  | Kotlin-файлы MVP приведены к Kotlin language style                                 |
| `pnpm check`                                                | PASS: 24 JS-теста, TypeScript, production build и Prettier                         |
| `tooling/validate-project.py`                               | PASS: 12 JSON, 22 TOML, 12 XML, 9 Android-модулей, SHA-256 Gradle Wrapper          |
| Python helpers                                              | PASS: компиляция исходников Python                                                 |
| Instrumentation / камера / живой Android UI                 | **НЕ ВЫПОЛНЕНО**, см. ограничения ниже                                             |
| GitHub CI                                                   | Workflow обновлён; удалённый запуск в этой работе не выполнялся                    |

Девять interop-сценариев используют **те же DesktopClient, PairingWorkflow и ManualTransfer, что входят в Android**, и настоящий Windows `lanvaultd.exe`. Только тестовая SocketFactory направляет фиктивный private IP в его loopback listener; в приложении этой подмены нет. TLS, CA, hostname, авторизация, HTTP и запись оригиналов остаются реальными.

1. QR с локальным подтверждением, передача оригинала 2 МиБ + 19 байт, receipt и точное совпадение файла на диске.
2. Повтор с тем же request UUID без повторной передачи тела.
3. Восстановление внешне удалённой копии новым upload, без ложного cached success.
4. Изменение исходных байтов между хешированием и отправкой не даёт COMMITTED.
5. Отмена coroutine во время передачи 4 МиБ, INTERRUPTED и повтор с тем же request UUID.
6. Отзыв телефона на Desktop приводит к 401.
7. Другой CA отвергается даже с корректным pin этого другого CA.
8. Неверный TLS hostname отвергается.
9. Сбой локального сохранения credentials не закрывает выдачу; повтор с сохранённым claim/request восстанавливает её, в том числе после истечения QR в пределах серверного окна.

16 transport unit-тестов дополнительно проверяют диапазоны private IPv4, запрет DNS/URL/loopback/неоднозначных адресов, TTL/версию QR, CA/pin, ограничение размера, случайность claim, сокрытие секретов в диагностических строках, точность receipt >4 ГиБ, все поля identity/hash/size, traversal и запрет создания сокета при закрытой политике.

## Среда и воспроизводимость

Локально подготовлены официальный Temurin JDK 17.0.21+9, Android command-line tools 15859902, SDK `platforms;android-37.0` revision 2, Build Tools 36.0.0, platform-tools; архивы JDK/tools проверены по опубликованным SHA-256. Gradle 9.3.1, AGP 9.1.1, Kotlin 2.2.10, OkHttp 4.12.0 и ZXing Embedded 4.3.0. Системная установка и настройки Windows не менялись.

Windows Gradle WorkerMain не загрузился из кириллического пути. Для испытаний создана копия Android/protocol и инструментов в `%TEMP%/LanVault-android-build`; исходники не перенесены из репозитория. Проверены SHA-256 **61 входного файла** перед/после итогового прогона. Затем KSP-экспорты двух Room-схем скопированы в исходники. Ledger: `artifacts/android-build-inputs.json`. Схемы Room сохраняют формат генератора и отдельно проходят JSON-проверку; Prettier их не переписывает.

Локальные журналы: `artifacts/android-final-check.log`, `android-apk-manifest.log`, `android/lint-results-debug.xml`, `stage04-web-check.log`, `stage04-project-validation.log`. APK SHA-256:

```text
app-debug.apk
47293467865ede4f64a811b3c12ee2537ba0951b63e4c26c08e732a96c958ece

app-debug-androidTest.apk
4057fe069d1d4a0dbac0610106bc1f5c603c0ad4659aaae019d58ab1124c3942
```

Команда повторной проверки из `lanvault`:

```powershell
.\tooling\check-android.ps1 -Portable -DesktopServerExe .\target\gnu\debug\lanvaultd.exe
```

При отсутствии параметра Desktop EXE девять interop-тестов пропускаются явно, остальные JVM-тесты и сборки доступны. `.tools`/`.cache`/APK/логи не входят в Git; инструкции для обычного JDK/SDK и устройства приведены отдельно.

## Ограничения результата

Подключённого телефона нет. Подготовлен Android 17 AOSP ATD x86_64 и эмулятор 37.2.13, но аппаратная виртуализация/драйвер гипервизора недоступны. Две попытки программного запуска (в том числе без GPU/Vulkan) завершились `0xC0000005` до появления ADB-устройства. Настройки виртуализации и драйверы не устанавливались. Логи: `artifacts/android-emulator*.log`.

Шесть instrumentation-тестов подготовлены и скомпилированы: checkpoint Room, Keystore round trip, изменение ciphertext, восстановление transfer journal, MediaStore original/pending и Android → Desktop через Wi-Fi. **Их успешное выполнение не заявляется.** `tooling/test-android-device.py` подготовлен для воспроизведения на подходящем эмуляторе, но здесь не был запущен на устройстве. Камера QR, системные диалоги разрешений, UI/lifecycle и реальные Android Network callbacks требуют отдельной приёмки на устройстве. JVM-тест с SocketFactory не доказывает Android-привязку к Wi-Fi.

Не проверены физическая LAN/Wi-Fi без WAN, переходы на cellular/VPN на устройстве, Android 10/13/14, SD-карта, HEIC/HDR на реальном телефоне, файл >4 ГиБ, отключение питания и release/R8. Максимальный тестовый поток Kotlin — 4 МиБ; большие числовые размеры проверены отдельно, прежний серверный 64 МиБ сценарий относится к этапу 3.

Полнофайловый MVP сообщает `resumable:false`. Нет частей/offset, автоматической синхронизации, фонового foreground service/UIDT, mDNS discovery, автоматической смены адреса при DHCP и периодической очереди. Для нового адреса можно выполнить новое сопряжение; старый доступ отзывается в Desktop. Release-подпись, обновления и полировка Android UI остаются следующими этапами. Основные предупреждения lint — доступность обновлений зависимостей; Kotlin также отмечает deprecated `allNetworks`, используемый для выбора уже существующего Wi-Fi без требования WAN.
