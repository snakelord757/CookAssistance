# CA-LM-02 — Подключение локальной модели к /assist

run_id: CA-LM-20261030-01 · parent: CA-LM · base: CA-LM-01 · depends_on: [CA-08, CA-LM-01]

## Цель
Добавить `LocalModelAssistant` в Ktor `:server` с выбором backend через `ASSIST_BACKEND`. Контракт `/assist` (`AssistRequest` → `AssistReply`) и Android-клиент не меняются (ноль изменений в app).

## Non-goals
- Изменения в app.
- Структурированный таймер/ignored от локальной модели.

## Изменения
- server: `LocalModelAssistant.kt` — `recipeText(Recipe)` (формат обучения), `num()` (десятичная запятая), `LocalModelAssistant` (HTTP → Python-сервер), `selectAssistant(...)`.
- server: `Server.kt` — выбор backend (`local` → LocalModelAssistant, иначе DeepSeek, как сейчас).
- server: `LocalModelAssistantTest.kt` — формат рецепта, маппинг ответа, выбор backend.
- server: `build.gradle.kts` — `testImplementation(libs.ktor.client.mock)`.

## Критерии (проверка)
- [ ] `recipeText` воспроизводит формат обучения (`Название. Порций: N.`, `Ингредиенты: имя — кол-во ед.; ...`, нумерованные шаги, десятичная запятая).
- [ ] `ASSIST_BACKEND=local` → `/assist` возвращает ответ локальной модели.
- [ ] Офтопик → вежливый отказ (answer), таймер → null (без структуры).
- [ ] `:server:test` зелёные; default backend остаётся DeepSeek.

## validation_scope
`task`. `:server:test` + live-проверка `/assist` при запущенном локальном сервере и `ASSIST_BACKEND=local`.

## Риски
- Локальный сервер недоступен → 502 assistant_unavailable (как при сетевой ошибке DeepSeek).
