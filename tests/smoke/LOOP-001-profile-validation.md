# LOOP-001: CLI-проверка профилей

Связанная задача: https://trello.com/c/OldqcwKM. Контракт: `python scripts/validate_profiles.py --root PATH`; корректный проект возвращает 0, ошибка валидации — 1 с понятной диагностикой. Сценарии проверяют реальный процесс CLI, а не вызов функции внутри тестов.

## Общие предусловия

1. `/dev` передал итоговый `candidate_sha`, который прошёл независимое ревью. В `D:/CookAssistance` команды `git rev-parse HEAD` и `git status --porcelain` подтверждают этот SHA и отсутствие незакоммиченных кандидатных файлов. Игнорируемые `workflow/`, `reports/` и Python cache допустимы.
2. Python доступен; фиксируется результат `python --version`. Исполнитель не меняет исходники, unit-тесты, сценарии или HEAD во время прогона.
3. Доступен MCP `mcp__node_repl__js` с импортами `node:child_process`, `node:fs` и `node:path`. Команды запускаются через `spawnSync` с массивом аргументов, `shell: false`, `encoding: 'utf8'`, `timeout: 15000`, `cwd: 'D:/CookAssistance'`. Каждый вызов MCP ограничен 30 секундами. Ошибка запуска, `signal` либо timeout означает BLOCKED/FAIL по причине, а не ожидаемый exit code.
4. Создаётся уникальный каталог данного прогона внутри `D:/CookAssistance/reports/LOOP-001/smoke-fixtures/`. Системный TEMP не используется. Для S2–S4 создаются независимые копии только `AGENTS.md` и `profiles/` из проверяемого checkout. Реальные профили никогда не изменяются. У каждой фикстуры сначала проверяется базовый exit 0, затем вносится единственное изменение сценария.

Образец вызова через MCP (переменная `fixtureRoot` заменяется реальным абсолютным путём):

```javascript
var cp = await import('node:child_process');
var result = cp.spawnSync('python', [
  'D:/CookAssistance/scripts/validate_profiles.py', '--root', fixtureRoot
], { cwd: 'D:/CookAssistance', encoding: 'utf8', timeout: 15000, shell: false });
nodeRepl.write({ status: result.status, signal: result.signal,
  stdout: result.stdout, stderr: result.stderr, error: result.error?.message });
```

В отчёте для каждого шага сохраняются ID, UTC-время, `candidate_sha`, имя MCP-инструмента, точный executable/argv/cwd, status/signal/error, stdout/stderr, ожидаемые проверки и итог PASS/FAIL/BLOCKED. Недоступность MCP не разрешает подменять прогон обычным shell.

## LOOP-001-S1 — корректный реальный проект

Цель: подтвердить, что существующий комплект из AGENTS.md и девяти профилей проходит проверку.

1. Через MCP запусти CLI с `--root D:/CookAssistance`.
2. Ожидается `status=0`, `stderr` пуст, `error` отсутствует; stdout содержит `PASS: AGENTS.md, 9 required profiles and local inline links are valid` и не содержит `ERROR:`.
3. Сохрани фактический вывод. Реальные файлы остаются неизменными; очистка не требуется.

## LOOP-001-S2 — отсутствующий обязательный профиль

Цель: подтвердить критерий обязательности файлов и полезную диагностику.

1. Создай копию профилей в уникальной фикстуре S2 и проверь базовый CLI: ожидается `status=0`.
2. Удали только `profiles/tech.md` внутри S2 после проверки, что абсолютный путь находится внутри каталога фикстур данного прогона. Ожидается, что файл фикстуры отсутствует, а настоящий `D:/CookAssistance/profiles/tech.md` существует.
3. Через MCP запусти CLI с `--root` на S2. Ожидается `status=1`, stdout содержит `Missing required file: profiles/tech.md` и `FAIL:`, не содержит `Traceback`. Дополнительная диагностика ссылки на отсутствующий профиль допустима; точное число ошибок не фиксируется.
4. Сохрани вывод и результат проверки сохранности настоящего файла. Очисти S2 по общему правилу ниже.

## LOOP-001-S3 — битая локальная ссылка

Цель: подтвердить диагностику с файлом-источником и относительной целью.

1. Создай независимую фикстуру S3 и проверь базовый CLI: ожидается `status=0`.
2. Создай дополнительный `profiles/smoke-fixture.md` только в S3 со следующим содержимым. Такой дополнительный Markdown-файл входит в область проверки:

```markdown
# Smoke fixture
[Missing local target](missing-smoke-target.md)
```

3. Убедись, что `profiles/missing-smoke-target.md` в S3 не существует. Через MCP запусти CLI с `--root` на S3.
4. Ожидается `status=1`, stdout содержит `Broken link in profiles/smoke-fixture.md: missing-smoke-target.md` и `FAIL:`, не содержит `Traceback`. Сохрани вывод. Очисти S3 по общему правилу ниже.

## LOOP-001-S4 — внешние ссылки без сети

Цель: подтвердить пропуск внешних целей, включая синтаксически некорректный URL со схемой, без сетевой проверки.

1. Создай независимую фикстуру S4 и проверь базовый CLI: ожидается `status=0`.
2. Создай `profiles/external-fixture.md` только в S4:

```markdown
# External targets
[External](https://example.invalid/not-fetched)
[Malformed external](https://[invalid)
[Protocol relative](//example.invalid/not-fetched)
```

3. Через MCP запусти Python CLI под сетевым tripwire. `spawnSync('python', ['-c', guard, scriptPath, '--root', fixtureRoot], options)`, где `scriptPath` — абсолютный путь реального валидатора, а `guard` — код ниже. Он сохраняет обычный CLI через `runpy`, но запрещает создание сетевого соединения; попытка сети завершилась бы ошибкой и была видна в stderr.

```python
import runpy, socket, sys, urllib.request
def deny_network(*args, **kwargs):
    raise RuntimeError("SMOKE_NETWORK_ATTEMPT")
socket.socket = deny_network
socket.create_connection = deny_network
urllib.request.urlopen = deny_network
script = sys.argv[1]
sys.argv = sys.argv[1:]
runpy.run_path(script, run_name="__main__")
```

4. Ожидается `status=0`, stderr пуст, stdout содержит штатный `PASS:`; ни один поток не содержит `SMOKE_NETWORK_ATTEMPT`, `Invalid IPv6 URL` или `ERROR:`. Сохрани код guard и результаты как доказательство этого прогона. Это проверка используемых стандартных сетевых API, а не общий сетевой sandbox.
5. Очисти S4 по общему правилу ниже.

## Очистка и итог

В `finally` удаляй только уникальный каталог фикстур данного прогона. Перед рекурсивным удалением разреши абсолютный путь, проверь через `path.relative` его вложенность в `D:/CookAssistance/reports/LOOP-001/smoke-fixtures`, исключи пустой relative, `..` и абсолютный relative. Не удаляй корневой каталог проекта или общий каталог чужих прогонов. Если очистка не выполнена, зафиксируй оставшийся путь в отчёте.

После всех сценариев повторно проверь HEAD и чистоту кандидатных файлов через MCP. Итог PASS только если S1–S4 выполнены и прошли, SHA не изменился, реальные профили целы и кандидатные файлы не изменены. При FAIL оформи отчёт и дефект по `AGENTS.md`; при внешнем блокере не объявляй успешный smoke. Сами сценарии и этот документ — подготовка проверки, не доказательство её исполнения.
