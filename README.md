# Учитель языков — локальный AI-преподаватель английского

Архитектура и план — в `../ARCHITECTURE.md`. Здесь — как запустить и что уже готово.

## Состояние по плану (раздел 15 архитектуры)

| Шаг | Что | Статус |
|---|---|---|
| 1 | Compose: postgres, ollama, nginx, health | готово |
| 2 | Текстовый чат: LLM + CEFR-промпт + JSON-контракт + экран чата | готово |
| 3 | LanguageTool + журнал ошибок + экран «Мои ошибки» | готово |
| 4 | Карточки правил + pgvector + FSRS | — |
| 5 | STT (нажми и скажи) | — |
| 6 | TTS, озвучка ответа | — |
| 7 | Дуплексный голос: WebSocket + VAD + barge-in | — |
| 8 | Экспорт в Anki, ролевые сценарии | — |

## Запуск

```bash
cp .env.example .env        # если .env ещё нет; задайте пароль Postgres
docker compose up -d --build
```

Первый запуск долгий: собирается jar и контейнер `ollama-pull` скачивает модель (~2.5 ГБ).
Следить за загрузкой: `docker compose logs -f ollama-pull`.

Когда `http://localhost/api/health` отвечает `"status":"up"` — открывайте `http://localhost`.

Нужна видеокарта NVIDIA и NVIDIA Container Toolkit (на Windows — через WSL2). Без GPU уберите блок
`deploy:` у сервиса `ollama` в `docker-compose.yaml` — модель пойдёт на CPU, медленно.

## Запуск без Docker (разработка)

Профиль `memory` хранит данные в памяти, Postgres не нужен. Ollama должна быть запущена локально
(`ollama pull qwen3:4b-instruct-2507-q4_K_M`).

```bash
./gradlew bootRun --args='--spring.profiles.active=memory'
# http://localhost:8080
```

## API

| Метод | Путь | Назначение |
|---|---|---|
| GET | `/api/health` | состояние Ollama, хранилища и LanguageTool; 503, если что-то не отвечает |
| GET | `/api/errors` | журнал ошибок: правило, пример, сколько раз |
| GET / PUT | `/api/profile` | профиль: имя, уровень CEFR |
| POST | `/api/sessions` | начать урок `{cefr?, topic?, mode?}` |
| GET | `/api/sessions`, `/api/sessions/{id}` | последние уроки / один урок |
| GET | `/api/sessions/{id}/messages` | история урока |
| PUT | `/api/sessions/{id}/level` | сменить уровень урока `{cefr}` |
| POST | `/api/sessions/{id}/end` | завершить урок |
| POST | `/api/chat` | ход урока `{sessionId, text?, hint?}`, ответ — SSE (`delta`, `final`, `error`) |

Режимы урока: `conversation`, `explain`, `correct`, `drill`.

## Как решается, что показать в «Исправлении»

1. Реплику ученика сначала проверяет LanguageTool. Его находки показываются всегда и передаются модели как факты.
2. Исправление от самой модели принимается, только если она указала ошибочный фрагмент дословно из текста ученика
   и действительно его изменила. Укороченная или пересказанная фраза исправлением не считается.
3. Всё, что прошло, попадает в журнал ошибок (`error_events`).

Логика — в `session/CorrectionPolicy.java`, тесты — в `CorrectionPolicyTest`.
Если LanguageTool недоступен, урок продолжается без него, а `/api/health` показывает `down`.

## Как устроен код

```
src/main/java/study/ai_foreign_language_teacher/
  api/      REST-контроллеры и DTO
  session/  LessonService — педагогический контур одного хода
  nlp/      клиент LanguageTool (проверка грамматики до LLM)
  llm/      клиент Ollama (1 запрос за раз), промпт, JSON-схема, разбор ответа
  memory/   хранилище: jdbc/ (Postgres) и inmemory/ (профиль memory)
  domain/   CEFR, режимы, контракт ответа учителя
  config/   настройки tutor.* из application.properties
web/        интерфейс: чистый HTML/CSS/JS без сборки, раздаёт nginx
```

Основные настройки — `src/main/resources/application.properties` (`tutor.ollama.*`, `tutor.lesson.*`);
в Docker они переопределяются переменными окружения из `docker-compose.yaml`.
