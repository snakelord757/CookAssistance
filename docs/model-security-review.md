# Security review той же моделью

Рабочий запуск допускается только из [защищённой установки runtime](runtime-integrity.md).
Редактируемая source-копия не является доверенной; прямой CLI-запуск блокируется.


Пайплайн: генерация → тесты → отдельный security-запрос → проверка JSON →
commit либо исправление. Один backend/model/config используется для обоих
запросов; обязательного DeepSeek и отдельного review credential нет.

Каждый запрос проходит локальный gateway с собственным точным допуском.
Security-запрос содержит отдельный [security-промпт](security-review-prompt.md),
snapshot ID, base HEAD, исходные и текущие файлы из allowlist и правила нумерации
строк. История генерации, её task, feedback и предыдущие сообщения не копируются.
Chat Completions-адаптер каждый раз создаёт ровно system и user message.
Пользовательский адаптер обязан не хранить/не добавлять скрытую историю.

## Запуск

Требования установки hook, preflight и допуска:
[контракт харнесса](harness-contract.md), [release policy](context-release.md).
Конфигурация задачи и модели: [execution loop](execution-loop.md).

```powershell
python -I -S -B 'C:/Program Files/CookSecurity/runtime-v1/scripts/trusted_runtime.py' run --repo D:/isolated/task --config D:/private/task.json --adapter-config D:/private/model.json --release-policy D:/private/releases.json --audit D:/private/audit.jsonl
```

При `--commit` контроллер коммитит только прошедший gate снимок. Без него выдаёт
подписанное разрешение для обычного commit через установленный hook.

Для изменений от внешнего агента:

```powershell
python -I -S -B 'C:/Program Files/CookSecurity/runtime-v1/scripts/trusted_runtime.py' review --repo D:/isolated/task --config D:/private/task.json --adapter-config D:/private/model.json --harness-config D:/private/harness.json --release-policy D:/private/releases.json --audit D:/private/audit.jsonl
```

Этот режим не генерирует код. Интегратор задаёт тот же model/config, который
использовал внешний агент. Контроллер не может определить скрытую модель чужого
харнесса; без подтверждения соответствия и маршрута не заявляйте полную интеграцию.
Нативный OpenCode-коннектор по-прежнему не поставляется автоматически.

Python API: `run(repo, config, backend, audit, releases=policy)`; для отдельного
review добавьте `review_only=True`. `security_backend` и `--security-config`
больше не поддерживаются: старая настройка должна завершаться ошибкой, а не
молча переключать reviewer.

Critical/High запрещают commit и возвращают feedback в исправление, затем
повторяются тесты и review. Medium/Low логируются как warning. Пустые findings
дают PASS только при полном корректном ответе для текущего snapshot. Ошибка,
усечение, неверная схема, отсутствие допуска или изменение снимка — BLOCKED.

Используются credentials выбранного адаптера (для встроенных HTTP-адаптеров —
`LLM_UPSTREAM_TOKEN`), только для транспорта, не в prompt/audit. Не передавайте
ключи в чат. Live-проверка требует настроенного backend и локально разрешённого
экспорта; синтетические тесты не доказывают качество review реальной моделью.
