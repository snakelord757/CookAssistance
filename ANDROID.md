# Android: стандарт проекта

Этот документ задаёт стандарт Android-приложения; фактическую готовность подтверждают код и результаты проверок. Scope, последовательные переходы профилей и исключение для документации определяются AGENTS.md. При первом ТЗ `/product` планирует bootstrap, затем функциональные задачи. Python-валидатор и LOOP-001/002 проверяют инструкции, не Android-приложение.

## Стек

| Область | Выбор |
| --- | --- |
| Язык/сборка | Kotlin, Gradle Kotlin DSL, Wrapper, `gradle/libs.versions.toml`; совместимые стабильные AGP/JDK/Kotlin/Compose |
| UI | Jetpack Compose, Material 3, Material 3 Adaptive, single Activity |
| Навигация | Navigation 3, типизированные маршруты, сохранение back stack |
| Состояние | ViewModel, immutable UiState, StateFlow, Coroutines, UDF, `collectAsStateWithLifecycle` |
| Архитектура | UI + data/repositories; domain/use cases при реальной сложности, модули по мере роста |
| Сеть | По необходимости Ktor Client + kotlinx.serialization, таймауты/отмена/обработка ошибок |
| Хранение | Room с Flow/suspend и KSP; DataStore для настроек |
| Изображения | Coil Compose по необходимости |
| DI | Constructor injection для небольшой основы; при росте Koin Kotlin DSL. Hilt с KSP — обоснованная `/tech` альтернатива, не второй контейнер рядом |
| Фоновые операции | Coroutines; WorkManager/CoroutineWorker для гарантированной отложенной работы |
| Тесты | kotlin.test/JUnit с совместимым Android runner, kotlinx-coroutines-test, fakes; Turbine при необходимости, AndroidX Compose UI tests |

Не добавлять весь стек заранее: хранение, сеть, DI, фоновые операции вводятся по ТЗ. Kotlin-first — приоритет API, а не запрет необходимых Android SDK/AndroidX. Gson/RxJava/SharedPreferences не являются выбором для нового кода.

Новые экраны полностью на Compose: без XML layouts, Fragment, RecyclerView, ViewBinding/DataBinding и AndroidView. XML manifest/resources и платформенная Activity допустимы. Для SDK без Compose `/tech` ищет альтернативу, `/product` выбирает совместимый способ реализации. Не вводить View interoperability в обход явного требования Compose-only; если обязательную интеграцию выполнить невозможно, сохранить конкретный blocker без вопроса пользователю.

Перед bootstrap `/tech` проверяет стабильные версии по официальной документации и фиксирует совместимость AGP–Gradle–JDK–Kotlin–Compose compiler–KSP. Compose BOM применяется для библиотек Compose. Не использовать динамические версии `+`, SNAPSHOT и preview ради новизны. Если выбранный AGP предоставляет встроенный Kotlin, не подключать дублирующий `org.jetbrains.kotlin.android`. Рабочий стек не обновлять автоматически на каждой задаче.

`minSdk` выбирается по аудитории и зависимостям; compileSdk/targetSdk — по актуальной стабильной платформе и условиям распространения. Точные версии фиксируются при bootstrap, а не угадываются в профилях.

## Адаптивность

- Layout определяется размером доступного окна/window size class: compact/medium/expanded, landscape, resize. Не привязывать UI к модели устройства и не блокировать ориентацию ради обхода проблем.
- Использовать adaptive navigation, list-detail/supporting pane где уместно, вместо растягивания телефонного экрана.
- Учитывать edge-to-edge, system bars, IME insets, fold/hinge при применимости, шрифт до 200%, touch targets, контраст, TalkBack, светлую/тёмную тему.
- Сохранять состояние при rotate/resize через подходящие rememberSaveable/SavedStateHandle/хранилище. Background/foreground и process recreation проверять по сценарию.
- Loading/empty/error/offline, retry, Back и permissions входят в критерии фичи; пользовательские тексты через ресурсы.
- Выбранный UI-драйвер должен видеть реальные semantics/идентификаторы. `testTag` не гарантирует видимость через UIAutomator: при resource-id настроить экспорт tags и проверить UI-дерево. Не подменять accessibility-текст техническими ID.

## Подготовка и проверки

В `workflow/project.md` сохранить фактические module/variant, applicationId/activity, JDK/JAVA_HOME, SDK/adb/emulator, Gradle-команды/APK output, AVD/API/ABI/serial, возможности выбранного UI-драйвера и Git remote/merge policy. Runtime-кэш не заменяет versioned build-конфигурацию.

Проверить `java -version`, Wrapper, SDK packages и acceleration, `emulator -list-avds`, доступ к Android UI через выбранный драйвер. В Windows определять SDK также из найденного `adb.exe`: sandbox может переопределять LOCALAPPDATA. Наличие Android Studio не доказывает работоспособность JDK.

Если приложения нет, bootstrap создаёт Compose app, wrapper/version catalog, минимальный экран, unit/instrumentation основу и Android smoke. Настройка недостающего SDK/AVD выполняется штатными средствами в рамках доступных разрешений; недоступная инфраструктура — BLOCKED, не PASS.

Примеры для app/debug, после проверки реальных Gradle tasks:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```

Unix-подобная среда (Linux/macOS/CI) использует `./gradlew`. Build/lint/unit и предусмотренные instrumentation обязательны до публикации. Compose UI tests не заменяют Android UI smoke по AGENTS.md. MCP необязателен: используй доступный UI-драйвер с необходимыми наблюдениями и действиями. Не запускать instrumentation и UI smoke одновременно на одном устройстве.

Android smoke выполняется на выделенном AVD. Для task/fix запускается назначенное планом подмножество, для milestone — полный утверждённый реестр. Основной путь проверяется в базовой compact-конфигурации; адаптивность — в representative medium/expanded, landscape/IME и крупном шрифте по области изменения. Для API-специфического fix обязательны проблемная и одна контрольная API; другие API-конфигурации назначаются до запуска по рискам и требованиям milestone. Полное произведение параметров не требуется. Отсутствие обязательной конфигурации блокирует соответствующий критерий; физический телефон не заменяет AVD без явного требования.

Источники: [Android architecture](https://developer.android.com/topic/architecture/recommendations), [адаптивный Compose](https://developer.android.com/develop/ui/compose/layouts/adaptive/get-started-with-adaptive-apps), [AGP Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin), [Ktor serialization](https://ktor.io/docs/client-serialization.html), [эмулятор](https://developer.android.com/studio/run/emulator-commandline). Остальные выборы — политика проекта; версии подтверждает `/tech` при bootstrap.
