# Review — CA-LM (интеграция локальной модели)

review_mode: self · base: 1e2a58f (feat/CA-MVP) · candidate: 7fcc2e9 · scope: 1e2a58f..7fcc2e9

## Прочитанный diff
- `local-model/server.py`, `requirements.txt`, `README.md` (CA-LM-01: 998907b, 571c1e7).
- `server/.../LocalModelAssistant.kt`, `LocalModelAssistantTest.kt` (новые), `Server.kt`, `server/build.gradle.kts` (CA-LM-02: 34b6235, 7fcc2e9).
- app не изменён (ноль изменений; временный SERVER_URL для smoke откачен).

## Проверки
- Модель не менялась (используются готовые база + адаптер V2; переобучения нет).
- API модели приведён к OpenAI-совместимому `/v1/chat/completions` (рецепт в system-сообщении, вопрос в user) — совпадает с прежним DeepSeek-форматом. Фиксированный промпт ассистента и `enable_thinking=False`/greedy как при обучении.
- `recipeText` воспроизводит формат обучения (числа `:g`+запятая, нумерованные шаги). Live подтверждено.
- Контракт `/assist` (AssistRequest/AssistReply) не изменён; app-клиент не тронут.
- Backend по умолчанию DeepSeek; `local` только при `ASSIST_BACKEND=local` — регрессий нет.
- Ошибки: 404/502/InvalidUpstream как у DeepSeek. Секретов нет; веса вне git. `:server:test` зелёные (18 тестов).

## Smoke (live)
- Python `/v1/chat/completions`: порции 4 (200 г/800 мл) PASS; ингредиенты PASS; офтопик-отказ PASS.
- Ktor `/assist` (ASSIST_BACKEND=local): ингредиенты борща 10/10 PASS; пересчёт ×2 — 9/10 (см. замечание).
- Android UI (эмулятор API37, `adb reverse`): каталог → деталка → «Приступить к готовке» → вопрос → ответ модели показан в UI + TTS «Говорю…» PASS.

## Замечания (неблокирующие, P3)
- Пересчёт «Вода — 2 л» на 12 порций модель дала «2,4 л» вместо «4 л» — известная арифметическая ошибка Qwen3-0.6B (не баг интеграции; 9/10 ингредиентов верно).
- ADB `input text` ASCII-only: в UI-smoke вопрос задан по-английски («list all ingredients…»), модель ответила корректно по-русски (мультиязычная база Qwen3).
- `LocalChatReply.finish_reason`/`usage` не пробрасываются в AssistReply — контракт без этих полей, ок для v1.

## Результат
APPROVE
