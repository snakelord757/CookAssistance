# CookAssistance — mock-сервер рецептов

Локальный Ktor-сервер, отдающий моковые рецепты по фиксированному JSON-контракту (задача CA-01 из TASKS.md).

## Запуск

Одна команда из корня проекта:

```
./gradlew :server:run
```

Сервер слушает `http://127.0.0.1:8080` (порт `8080`). Для Android-эмулятора адрес — `http://10.0.2.2:8080`.

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
