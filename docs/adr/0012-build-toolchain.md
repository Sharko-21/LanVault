# ADR-0012 — фиксированная сборочная база

Статус: **Accepted**, решение реализации этапа 2.

| Область                      | Версия                                                 |
| ---------------------------- | ------------------------------------------------------ |
| Node.js / pnpm               | Node 24 / pnpm 11.25.0                                 |
| React / Vite / TypeScript    | 19.3.1 / 8.3.5 / 7.0.2                                 |
| Rust                         | 1.90.0, edition 2021, target MSVC                      |
| Tauri / tauri-build / dialog | 2.12.5 / 2.7.4 / 2.8.4                                 |
| Java / Gradle / AGP          | JDK 17 / Gradle 9.3.1 / AGP 9.1.1                      |
| Android                      | minSdk 29, compileSdk/targetSdk 37, build-tools 36.0.0 |
| Kotlin / Compose compiler    | 2.2.10, встроенный Kotlin AGP                          |
| KSP / Compose BOM            | 2.3.13 / 2025.10.01                                    |
| Room / WorkManager           | 2.8.3 / 2.10.1                                         |
| SHA-256 checkpoint           | Bouncy Castle 1.85                                     |

AGP 9.1.1 поддерживает API 37 и требует Gradle 9.3.1 и JDK 17. Он подключает Kotlin 2.2.10. Android-модули не применяют `org.jetbrains.kotlin.android`; JVM-модуль model применяет Kotlin JVM. [Совместимость AGP](https://developer.android.com/build/releases/agp-9-1-0-release-notes), [встроенный Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin).

Версии npm и доступность Android-артефактов сверены с официальными реестрами. Суммы Gradle Wrapper и дистрибутива проверены по services.gradle.org. Lock-файлы фиксируют транзитивные зависимости. Rust resolver выбирает зависимости, совместимые с Rust 1.90.

Нативная совместимость должна подтверждаться сборками: успешное разрешение зависимостей не является успешной компиляцией. Установщики/сертификаты релиза не создаются на этом этапе. `bundle.active=false`, сборка Tauri выполняется с `--no-bundle`.
