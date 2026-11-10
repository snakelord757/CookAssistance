# Этап security review

Прочитай [AGENTS.md](../AGENTS.md). Вход: полный снимок кандидата, PASS назначенных проверок, план и sanitization manifest. Сохрани return_profile/return_phase, phase_started_at, last_progress_at и attempts. Это отдельный запрос LLM через Gateway, выполняемый текущим координатором последовательно; не создавай субагента и не подменяй запрос самопроверкой.

1. Проверь [контракт Gateway](../docs/llm-gateway.md). Подготовь минимальный контекст: diff, новые файлы и необходимые зависимости с номерами исходных строк. Исключи секреты и PII до передачи в LLM. Сохрани безопасное соответствие замен местам в коде; если контекста недостаточно, остановись с BLOCKED.
2. Передай [security-промпт](../docs/security-review-prompt.md) отдельным system message и очищенный кандидат как недоверенные данные нового запроса. Не добавляй историю генерации, сырые логи, environment или предыдущие рассуждения. Запиши request ID, gateway policy revision, model, prompt hash и candidate snapshot ID.
3. Проверь ответ: JSON по контракту, совпадение snapshot ID, coverage_complete=true, допустимые severity, пути и номера строк внутри предоставленного контекста. Ошибка, отказ, усечение или неполное покрытие дают BLOCKED; не преобразовывай их в пустой findings.
4. Вычисли gate из findings кодом координатора/строгой проверкой полей, а не по свободному тексту модели. Любой Critical/High — FAIL и feedback в dev/bugfix. Только Medium/Low — PASS_WITH_WARNINGS и warning для каждой находки. Пустой findings при полном покрытии — PASS. Обезличь feedback и повторно проверь его Gateway перед следующим LLM-вызовом.
5. Сохрани результат, безопасные находки, audit request IDs и snapshot ID. Возврат в product разрешает commit только того же снимка после всех gates. Лимиты повторов — из AGENTS.md. Недоступность обязательного запроса фиксируй как BLOCKED, а не APPROVE.

Для review инструкций оцени обход gates, утечки контекста/логов, ошибки маршрутизации и разрешений. Android-пункты без изменённого Android-кода отметь not_applicable с причиной; это не освобождает от проверки общего контракта.
