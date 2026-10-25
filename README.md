# CookAssistance

Android-помощник по готовке: каталог DummyJSON без авторизации, карточки, локальное избранное, голосовые вопросы о выбранном рецепте через DeepSeek.

Рецепты источника на английском. Количества ингредиентов источник не предоставляет — приложение их не придумывает. Избранное хранит полный текст рецепта для чтения без сети.

## Разработка

JDK 21, Android SDK 37, minSdk 29. Kotlin/Compose, Navigation3, Room, Ktor. Версии закреплены в `gradle/libs.versions.toml`; Wrapper проверяет SHA-256 Gradle.

```powershell
$env:JAVA_HOME='C:/Users/Admin/.jdks/ms-21.0.9'
$env:ANDROID_HOME='C:/Users/Admin/AppData/Local/Android/Sdk'
.\gradlew.bat :app:assembleDebug :app:lintDebug :app:testDebugUnitTest :server:test
.\gradlew.bat :app:connectedDebugAndroidTest
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Запуск Activity: `com.cookassistance.app/.MainActivity`.

## Помощник

Сервер слушает только `127.0.0.1:8080`. Задайте `DEEPSEEK_API_KEY` в окружении серверного процесса через безопасный локальный способ и запустите `./gradlew :server:run`. Не сохраняйте ключ в Git, Android ресурсах или логах. Необязательная `DEEPSEEK_MODEL` выбирает модель; по умолчанию `deepseek-flash`.

Debug Android обращается к `http://10.0.2.2:8080` из эмулятора. Без ключа сервер отвечает HTTP 503; это реальная ошибка конфигурации, а не демонстрационный ответ. Release собирается с `-PassistUrl=https://your-approved-host`; без endpoint помощник сообщает о недоступности. Production-развёртывание требует отдельной политики доступа и rate limiting; локальный сервер не предназначен для публичного размещения.

При нажатии «Приготовить» приложение запрашивает микрофон. Распознавание зависит от службы Android, озвучка — от установленного русского TTS. При отказе или недоступности остаётся текстовый ввод. В фоне микрофон и запрос останавливаются. Ответы за пределами рецепта отбрасываются серверным классификатором и второй проверкой; LLM-фильтр не гарантирует абсолютной защиты и требует live adversarial проверки.

## Проверки

Android smoke registry: `tests/smoke/android-index.md` (добавляется отдельным профилем после ревью). Unit tests с fake upstream не подтверждают реальный DeepSeek/voice. Приёмка требует независимого ревью, build/lint/unit, instrumentation и MCP на API29/37, medium/expanded, landscape и шрифте 200%. APK и runtime-отчёты в Git не входят.
