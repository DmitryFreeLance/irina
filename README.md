# VK Lead Magnet Bot (Java + SQLite)

## Быстрый старт

### Переменные окружения
- `VK_GROUP_ID` — числовой ID сообщества (например, 123456789).
- `VK_TOKEN` — токен сообщества с правами на сообщения и доступ к документам.
- `ADMIN_IDS` — список ID администраторов через запятую (например, `111,222`).
- `DB_PATH` — путь к SQLite (по умолчанию `./bot.db`, в Docker `/data/bot.db`).
- `LONGPOLL_WAIT` — таймаут longpoll (по умолчанию `25`).
- `PAGE_SIZE` — размер страницы списка материалов (по умолчанию `8`).

### Локальный запуск
```bash
mvn -q -DskipTests package
java -jar target/vk-leadbot-1.0.0-shaded.jar
```

### Docker
```bash
docker build -t vk-leadbot .

docker run -d --name vk-leadbot \
  -e VK_GROUP_ID=123456789 \
  -e VK_TOKEN=YOUR_TOKEN \
  -e ADMIN_IDS=111,222 \
  -v "$PWD/data:/data" \
  vk-leadbot
```

## Команды
- `/start` — пользовательский вход.
- `/admin` — админ-панель.

## Админ-панель
- Добавление/редактирование/удаление лид-магнитов.
- Генерация уникальной ссылки на материал.
- Статистика.
- Мгновенная рассылка по базе пользователей.

## Примечания
- Для выдачи файлов бот принимает документы от администратора и хранит `attachment`.
- Уникальные ссылки формируются как `https://vk.com/club{GROUP_ID}?ref=...`.
