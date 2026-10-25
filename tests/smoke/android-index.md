# Android smoke registry

Версия 1.0, AND-001 (план 1.0). Спецификация: [CookAssistance MVP](AND-001-android-mvp.md). Старый [index.md](index.md) относится к CLI-инструментам и не закрывает Android quality gates.

Пути этого реестра согласованы планом AND-001 и контрактом /dev, чтобы не перезаписывать пользовательский CLI index. Создание документов не означает успешного smoke. Исполнять на финальном SHA после интеграции и независимого review; отчёты вне candidate tree.

| ID | Критерий | Обязательные конфигурации | Зависимости |
| --- | --- | --- | --- |
| S01 | Чистый старт, live каталог без auth, empty избранное | P37, T29-M | Реальный DummyJSON |
| S02 | Правильный recipeId/источник/ингредиенты/шаги/Back | P37, T29-M, T29-E | Два реальных рецепта |
| S03 | Save/remove, reinstall, offline full snapshot | P37, T29-M, T29-E | Room, управляемая сеть |
| S04 | Catalog/detail error и retry | P37, T29-M | Управляемая сеть |
| S05 | Cooking context, отказ mic, text/IME | P37, T29-M, T29-E | Permission UI |
| S06 | Недоступный backend/ключ: честная ошибка | P37 | Локальный server, без key |
| S07 | Реальное распознавание и слышимая TTS | P37, API29 | Voice/audio path + реальный DeepSeek |
| S08 | Live recipe answers, substitutions, off-topic/injection | P37 | Реальный DeepSeek; controlled recipe-data fixture для отдельной подстроки |
| S09 | Background/Back/cancel/stale response | P37, T29-M, T29-E | Live success подстрока требует DeepSeek |
| S10 | Rotate/resize/process recreation | P37, T29-M, T29-E | Сохранённый task, фактический новый PID |
| S11 | Font200%, light/dark, semantics/accessibility | P37, T29-M, T29-E | Фактические window bounds, TalkBack если доступен |
| S12 | Min/current API permission/voice/error parity | P37, API29 | Платформенные speech/TTS services |

P37: CookAssistance_API37 / API37 / x86_64 / 16 KB, compact portrait + landscape. T29-M: Medium_Tablet / API29 / x86 portrait medium. T29-E: тот же планшет landscape expanded. Размеры, настройка, cleanup и evidence подробно в спецификации. Pixel_7/API36.1 — только дополнительная диагностика.

Все строки обязательны; невозможность live AI/audio или определённой конфигурации фиксируется BLOCKED, а не исключением из матрицы. Unit/Compose tests и CLI smoke не заменяют Android MCP. Инструментальные тесты не должны выполняться одновременно с этим реестром на одном serial.
