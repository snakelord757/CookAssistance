# Первый шаг execution loop: bootstrap gateway

Точка входа — `bootstrap_gateway.py`, запускаемая **локально до приватного
контекста и любых LLM-вызовов**. Первый bootstrap должен быть проверенным
оператором артефактом; нельзя давать агенту произвольный код для запуска с UAC.
На время первоначальной установки агент не изменяет её исходники.

Последовательность:

`bootstrap/install-or-check → harness preflight → generation → tests → security review → commit gate`

## Первый запуск на Windows

Под ограниченной учётной записью выполните:

```powershell
python -I -S -B scripts/bootstrap_gateway.py -- install-hook --repo D:/isolated/task
```

Если установки нет, bootstrap запросит подтверждение оператора через UAC.
Отдельный установщик создаёт `%ProgramFiles%/CookSecurity/runtime-v1`, копирует
проверяемый набор файлов, создаёт seal и задаёт права: SYSTEM/Administrators —
полный доступ, Users — чтение/выполнение, владелец — Administrators. Исходный
артефакт закреплён digest до запроса UAC и проверяется после копирования.
Права назначаются только новым объектам, не чужим существующим каталогам.

Повышенные права остаются у установщика. Исходный процесс заново проверяет
готовность **с правами агента** и только после PASS запускает выбранное действие
защищённого loop. Сам installer никогда не запускает генерацию или review.
Отказ/отмена UAC, незащищённый Python, ошибки ACL/копирования или проверки дают
BLOCKED; продолжения в обход gateway нет.

Bootstrap не создаёт аккаунты. Агент должен уже запускаться ограниченным
пользователем, без административных привилегий. Запуск всего loop под
администратором запрещён прежними проверками.

## Следующие запуски

Используйте уже установленный, защищённый bootstrap, а не изменяемую копию:

```powershell
python -I -S -B 'C:/Program Files/CookSecurity/runtime-v1/scripts/bootstrap_gateway.py' -- run --repo D:/isolated/task --config C:/ProgramData/CookSecurity/task.json --adapter-config C:/ProgramData/CookSecurity/model.json --release-policy C:/ProgramData/CookSecurity/releases.json --audit D:/runtime-audit/loop.jsonl
```

Существующая установка только проверяется: состав, hashes, владелец и права.
Нет копирования, пересборки seal, изменения ACL, скачивания или повторного UAC.
Постоянного фонового сервиса нет. Отдельная проверка без установки/работы:

```powershell
python -I -S -B 'C:/Program Files/CookSecurity/runtime-v1/scripts/bootstrap_gateway.py' --check
```

Повреждённая установка не переустанавливается автоматически. Частичная установка
или оставшийся контейнер без runtime тоже дают BLOCKED; их разбор — задача
оператора. Обновление версии — отдельная проверенная операция, не часть startup.

Bootstrap устанавливает gateway, но не создаёт credentials/release approvals,
не ослабляет ACL конфигов и не подменяет отсутствующий коннектор харнесса.
Настройка модели/политики и остальные gates остаются по
[контракту](harness-contract.md). `install-hook` в первом примере отдельно
включает прежний commit guard, не перезаписывая чужие hooks.

## Unix

Автоматического sudo из агента нет. Оператор получает digest проверенного
артефакта командой `python -I -S -B scripts/bootstrap_gateway.py --artifact-digest`,
затем один раз запускает этот же bootstrap с `--install --expected-digest <digest>`
под root. Путь — `/opt/cook-security/runtime-v1`, владелец root, каталоги 755,
файлы 644. Агент затем запускает установленный bootstrap без root. Повторные
проверки и запрет автоматического ремонта одинаковы для обеих платформ.

## Что проверено

Тесты используют временные каталоги: первая установка ровно один раз, повторная
проверка без изменения файлов/mtime, повреждение/незавершённая установка без
ремонта, отмена установки без продолжения, отказ неоператору и произвольным
путям. Положительная установка имитирует выдачу привилегий/ACL; настоящий UAC и
успешный запуск под отдельной учётной записью в этой сессии не выполнялись.

Windows-механизмы: [Start-Process](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.management/start-process?view=powershell-5.1), [icacls](https://learn.microsoft.com/en-us/windows-server/administration/windows-commands/icacls).
