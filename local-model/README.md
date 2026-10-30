# Local model — inference server

Отдельный HTTP-сервер, который грузит уже обученную модель **Recipe Assistant V2**
(Qwen3-0.6B + LoRA-адаптер V2) и отвечает на вопросы по рецепту. Переобучение не
выполняется: используются готовые веса с тренировочной машины.

## Файлы весов (внешние, не в git)

| Компонент | Путь по умолчанию | env |
|---|---|---|
| База Qwen3-0.6B | `D:\ModelTraining\PretrainedLocalModel\models\qwen-local` | `MODEL_BASE_DIR` |
| Адаптер V2 | `D:\ModelTraining\PretrainedLocalModel\runs\v2\adapter` | `MODEL_ADAPTER_DIR` |

Пути переопределяются переменными окружения — сервер переносим между машинами.

## Окружение

Проверенная конфигурация: Windows x64, Python 3.12, PyTorch 2.7.1+cu128,
Transformers 4.57.6, PEFT 0.18.1, NVIDIA GPU (CUDA 12.8).

```powershell
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install --upgrade pip
.\.venv\Scripts\python.exe -m pip install "https://download.pytorch.org/whl/cu128/torch-2.7.1%2Bcu128-cp312-cp312-win_amd64.whl"
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
```

## Запуск

```powershell
$env:PYTHONUTF8 = "1"
.\.venv\Scripts\python.exe -m uvicorn server:app --host 127.0.0.1 --port 8000 --workers 1
```

Один worker обязателен: несколько процессов загрузили бы отдельные копии модели.

## Проверка

```powershell
# состояние
Invoke-RestMethod http://127.0.0.1:8000/health

# вопрос (пересчёт порций)
$payload = @{
  recipe = "Каша. Порций: 2.`nИнгредиенты: овсяные хлопья — 100 г; молоко — 400 мл.`n1. Нагрейте молоко.`n2. Добавьте хлопья.`n3. Варите 5 минут."
  question = "Пересчитай все ингредиенты на 4 порции."
} | ConvertTo-Json

Invoke-RestMethod -Uri "http://127.0.0.1:8000/answer" -Method Post `
  -ContentType "application/json; charset=utf-8" `
  -Body ([System.Text.Encoding]::UTF8.GetBytes($payload)) | Select-Object -ExpandProperty answer
```

Ожидаемый результат: **200 г хлопьев и 800 мл молока** (формулировка модели может
отличаться).

## API

- `GET /health` → `{status, model, device}`
- `POST /answer` → тело `{recipe, question, max_new_tokens?}` → `{answer, input_tokens, output_tokens, truncated}`

Это обычный HTTP API, не OpenAI-совместимый. Рецепт передаётся с каждым вопросом.
