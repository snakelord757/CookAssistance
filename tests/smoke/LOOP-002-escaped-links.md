# LOOP-002-S1 — экранирование opening bracket

Задача: [LOOP-002](https://trello.com/c/BFwWwgt2). Дефект: [LOOP-002-BUG-1](https://trello.com/c/5HriuQGj). Цель: через реальный CLI подтвердить, что литеральный пример с экранированной `[` не считается ссылкой, а чётное экранирование и настоящая соседняя ссылка остаются проверяемыми.

Этот сценарий дополняет, а не заменяет LOOP-001-S1–S4. Его подготовка не является результатом прогона.

## Предусловия и окружение

1. `/bugfix` передал итоговый `candidate_sha` после интеграции unit-тестов и сценариев и независимого `APPROVE`. Не привязывай прогон к промежуточному SHA из истории задачи.
2. Через MCP `mcp__node_repl__js` проверь `git rev-parse HEAD`, `git status --porcelain` и `python --version` в `D:/CookAssistance`. Ожидается заданный SHA и отсутствие незакоммиченных кандидатных файлов; игнорируемые `reports/`, `workflow/` и Python cache допустимы.
3. Доступны MCP-импорты `node:child_process`, `node:fs`, `node:path`. Запускай процесс через `spawnSync` с массивом аргументов, `shell:false`, `encoding:'utf8'`, `timeout:15000`. Каждый MCP-вызов ограничен 30 секундами. Timeout, signal или ошибка запуска не считаются ожидаемой ошибкой валидации.
4. Создай уникальный каталог прогона внутри `D:/CookAssistance/reports/LOOP-002/smoke-fixtures/`. Для каждого варианта ниже создай отдельную копию только `AGENTS.md` и `profiles/` из проверяемого checkout. Сначала базовый CLI каждой копии должен вернуть exit 0. Системный TEMP и настоящие профили не изменяй.
5. Дополнительный файл фикстуры — `profiles/escaping-fixture.md`. Цели `missing.md`, `literal.md` и `active.md` в этой копии `profiles/` должны отсутствовать. Проверяй это до запуска варианта.

## Точные входные данные

Генерируй обратные косые черты через код символа, чтобы JSON/JavaScript-экранирование не меняло количество символов в тестовом файле:

```javascript
var slash = String.fromCharCode(92);
var variants = [
  { id: 'odd-1', text: slash.repeat(1) + '[literal](missing.md)\n', exit: 0 },
  { id: 'odd-3', text: slash.repeat(3) + '[literal](missing.md)\n', exit: 0 },
  { id: 'even-0', text: slash.repeat(0) + '[active](missing.md)\n', exit: 1 },
  { id: 'even-2', text: slash.repeat(2) + '[active](missing.md)\n', exit: 1 },
  { id: 'even-4', text: slash.repeat(4) + '[active](missing.md)\n', exit: 1 },
  { id: 'mixed', text: slash + '[literal](literal.md) and [active](active.md)\n', exit: 1 },
  { id: 'unclosed-prefix', text: slash + '[literal prefix [active](missing.md)\n', exit: 1 },
];
```

Все семь вариантов обязательны; это один сценарий с отдельным результатом каждого варианта. Сохрани фактическое содержимое файла или его JSON-представление в отчёте, а не только имя варианта.

## Шаги и ожидаемые результаты

1. После успешного базового запуска запиши `variant.text` в `profiles/escaping-fixture.md` соответствующей независимой копии (UTF-8). Проверь сохранённый текст и ожидаемое число начальных backslash для odd/even вариантов.
2. Через MCP запусти:

   ```javascript
   var cp = await import('node:child_process');
   var result = cp.spawnSync('python', [
     'D:/CookAssistance/scripts/validate_profiles.py', '--root', fixtureRoot
   ], { cwd: 'D:/CookAssistance', encoding: 'utf8', timeout: 15000, shell: false });
   nodeRepl.write({ status: result.status, signal: result.signal,
     stdout: result.stdout, stderr: result.stderr, error: result.error?.message });
   ```

3. Для каждого результата обязательны отсутствие `error`/`signal`, пустой stderr и отсутствие `Traceback`. Дополнительно проверь:

   | Вариант | Exit | Обязательный результат stdout |
   | --- | --- | --- |
   | odd-1, odd-3 | 0 | Штатный `PASS: AGENTS.md, 9 required profiles and local inline links are valid`; нет `ERROR:` и `FAIL:` |
   | even-0, even-2, even-4 | 1 | Ровно одна ошибка `ERROR: Broken link in profiles/escaping-fixture.md: missing.md` и `FAIL: 1 problem(s)`; нет `PASS:` |
   | mixed | 1 | Ровно одна ошибка `ERROR: Broken link in profiles/escaping-fixture.md: active.md` и `FAIL: 1 problem(s)`; нет диагностики `literal.md` и нет `PASS:` |
   | unclosed-prefix | 1 | Ровно одна ошибка `ERROR: Broken link in profiles/escaping-fixture.md: missing.md` и `FAIL: 1 problem(s)`; нет `PASS:` |

4. Сохрани каждый результат: ID сценария и варианта, время UTC, candidate SHA, MCP-инструмент, executable/argv/cwd, фактический текст фикстуры, status/signal/error, stdout/stderr и результаты проверок ожиданий.
5. Не трактуй ожидаемые exit 1 для настоящих битых ссылок как дефект. Реальный FAIL — несовпадение с таблицей, например exit 1 для odd-1 или exit 0 для unclosed-prefix.

## Очистка и завершение

В `finally` удали только уникальный каталог этого прогона. Перед рекурсивным удалением разреши абсолютный путь и через `path.relative` подтверди, что он вложен в `D:/CookAssistance/reports/LOOP-002/smoke-fixtures`; relative должен быть непустым, не абсолютным и не начинаться с `..`. Не удаляй общий каталог фикстур или реальные профили. Если очистка невозможна, запиши оставшийся путь и причину.

После исполнения через MCP повтори `git rev-parse HEAD` и `git status --porcelain`: SHA должен совпасть с preflight, кандидатные файлы должны оставаться чистыми. Итог `PASS` только если все семь вариантов прошли и реальные файлы не изменены. Сохрани отчёт в `reports/LOOP-002/<run-id>/` вместе с результатами прежних четырёх сценариев.

При реальном падении той же регрессии обнови существующий LOOP-002-BUG-1 после проверки дубликатов, не создавай повторную карточку. Недоступность MCP или ошибку окружения отмечай как `BLOCKED`, без подмены обычным shell-прогоном и без ложного `PASS`.
