# CookAssistance — mock-сервер рецептов

Локальный Ktor-сервер, отдающий моковые рецепты по фиксированному JSON-контракту (задача CA-01 из TASKS.md).

## Security execution loop

[CLI для генерации, тестов, отдельного security review и коммита](docs/execution-loop.md)
содержит обязательный локальный гейт с фильтрацией секретов/PII и JSONL-аудитом.
Отдельный Gateway-сервис и привязка гейта к модели для запуска цикла не нужны.
Внешняя передача требует [явного допуска точного обезличенного контекста](docs/context-release.md);
отсутствие срабатываний regex не разрешает отправку.
Critical/High блокируют коммит и возвращают кандидата на исправление.
В руководстве приведены настройка upstream, запуск и границы защиты.

## Запуск

Одна команда из корня проекта:

```
./gradlew :server:run
```

Сервер слушает `http://127.0.0.1:8080` (порт `8080`). Для Android-эмулятора адрес — `http://10.0.2.2:8080`.

На Android 17 / API 37 debug-приложению нужно разрешить локальную сеть после
установки APK (подставьте serial своего эмулятора):

```powershell
adb -s emulator-5560 shell pm grant com.cookassistance.app android.permission.ACCESS_LOCAL_NETWORK
```

Разрешение объявлено только в debug manifest для локального backend. Release
использует настроенный HTTPS endpoint. После выдачи разрешения нажмите
«Повторить» или перезапустите приложение. Требование платформы описано в
[Android local network permission](https://developer.android.com/privacy-and-security/local-network-permission).

Альтернативно собрать дистрибутив и запустить скрипт:

```
./gradlew :server:installDist
server/build/install/server/bin/server
```

## Endpoint'ы

| Метод | Путь | Назначение |
|-------|------|------------|
| GET | `/health` | liveness |
| GET | `/recipes` | список рецептов |
| GET | `/recipes?type={type}` | фильтр по типу блюда |
| GET | `/recipes/dish-of-the-day` | один рецепт дня |
| GET | `/recipes/{id}` | полный рецепт |
| GET | `/collections` | список праздничных подборок |
| GET | `/collections/{id}/recipes` | рецепты подборки |
| POST | `/feedback` | приём обратной связи `{"message":"...","contact":"..."}` |

Типы блюд: `soup | salad | main | side | dessert | drink | baking | breakfast`.

## Тесты

```
./gradlew :server:test
```

Данные хранятся в памяти (14 рецептов, 8 типов блюд, 3 подборки); изменение данных не требует пересборки Android-приложения.
