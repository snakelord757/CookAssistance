# CA-LM-01 — Локальный inference-сервер модели

run_id: CA-LM-20261030-01 · parent: CA-LM · base: 1e2a58f (feat/CA-MVP) · depends_on: []

## Цель
Добавить в репозиторий Python FastAPI-сервер, который загружает уже обученную базу Qwen3-0.6B + существующий адаптер V2 (`runs/v2/adapter`) и отвечает на вопросы по рецепту. Переобучение НЕ выполняется — используются готовые веса с тренировочной машины.

## Non-goals
- Подключение к Ktor `/assist` (это CA-LM-02).
- Коммит весов (база/адаптер остаются внешними).
- Таймер / структурированный вывод.

## Изменения
- `local-model/server.py` — FastAPI `/health`, `/answer` (recipe+question → answer); загрузка base+adapter V2 через `PeftModel.from_pretrained(...).merge_and_unload()`; SYSTEM-промпт из обучения; `enable_thinking=False`; greedy (`do_sample=False`).
- `local-model/requirements.txt` — закреплённые версии (torch wheel cu128, transformers 4.57.6, peft 0.18.1, accelerate, fastapi, uvicorn).
- `local-model/README.md` — пути весов через env `MODEL_BASE_DIR`/`MODEL_ADAPTER_DIR`, команда запуска, пример запроса.

## Критерии (проверка)
- [ ] `/health` возвращает ok + model/device (live запуск).
- [ ] `/answer` пересчитывает порции (овсяная каша 2→4 = 200 г хлопьев / 800 мл молока).
- [ ] `/answer` отвечает на замену и вежливо отказывает на офтопик.
- [ ] Команда запуска задокументирована; веса не в git.

## validation_scope
`task`. Live-проверка: запуск сервера на 127.0.0.1:8000, `/health` + 2–3 `/answer` (порции/замена/офтопик).

## Риски
- GPU недоступен → CPU-путь медленный (не измерялся). Среда подтверждена: CUDA True (RTX 5060 Ti).
- Пути весов на другой машине — задаются env.
