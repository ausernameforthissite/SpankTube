# Безопасность, данные и API

> Тематический справочник по части приложения. Разделы: 10–14, 59, 101, 108, 117.

## 10. Подписки и лента

- При привязке аккаунта: `subscriptions.list(mine=true)` → таблица каналов пользователя (1 единица квоты за страницу из 50).
- Детект новых видео: **WebSub-пуши** (PubSubHubbub-хаб YouTube шлёт webhook при загрузке на канале — входящий трафик, egress не нужен; но подписка/отписка на хабе — исходящий вызов, идёт через egress) — основной механизм; **polling RSS-фидов** каналов раз в час (тоже через egress) — реконсиляция. Оба бесплатны, квоту не тратят.
- Событие `channel.new_video` → лента → бейдж «новое» в клиентах; опционально FCM-пуш в Android.
- Главная страница индивидуальна: список подписок + рекомендации. При привязанном YouTube-аккаунте используются рекомендации YouTube, иначе локальные — по истории просмотров и подпискам; fallback обязателен и не требует аккаунта.

## 11. Аутентификация, роли, привязка YouTube-аккаунта

- Внутренние пользователи: Spring Security, BCrypt, JWT (RSA), access 15 мин + refresh с ротацией. Роли: `ROLE_VIEWER`, `ROLE_ADMIN`. Keycloak сознательно не берём — оверкилл для двух ролей.
- Привязка YouTube: Google OAuth2 authorization code flow, scopes `youtube.readonly` (+ `youtube.force-ssl` для лайков/комментариев). Обмен кода на токены — серверный вызов к Google, идёт через egress. Refresh-токены в БД с envelope-шифрованием (KEK из Kubernetes Secret).
- Альтернатива для yt-dlp-сценариев: загрузка `cookies.txt` через UI, шифрованное хранение.
- Gateway валидирует JWT и прокидывает роль downstream; ACL-проверки дублируются на уровне запросов в archive/catalog.
- `user-service` хранит персональные квоты. Изменять их может только `ADMIN`; archive/catalog получают действующее значение через внутренний API, кэшируя его на короткое время.

## 12. Модель данных (черновик)

```sql
users(id, login, password_hash, role, quota_bytes, created_at)
youtube_accounts(id, user_id FK, google_subject, refresh_token_enc, scopes, linked_at)
channels(channel_id PK, title, handle, custom_path, avatar_path)
videos(video_id PK, channel_id FK, title, published_at, duration_sec,
       archived_path, quality, size_bytes, archived_at)
user_views(user_id FK, video_id FK, first_watched_at, last_accessed_at,
           last_position_sec, watch_percent, created_at,
           PRIMARY KEY (user_id, video_id))
subscriptions(user_id FK, channel_id, added_at, PRIMARY KEY (user_id, channel_id))
channel_videos(channel_id, video_id, published_at, PRIMARY KEY (channel_id, video_id))
archive_jobs(id, owner_id FK, kind, target_id, priority, status,
             total_count, completed_count, failed_count, created_at, started_at, finished_at)
archive_job_items(id, job_id FK, video_id, status, attempts, next_attempt_at, error)
CREATE INDEX user_views_lru_idx ON user_views(user_id, last_accessed_at);
CREATE INDEX archive_jobs_order_idx ON archive_jobs(status, priority, created_at);
```

## 13. События (RabbitMQ)

| Событие | Продюсер → потребитель | Назначение |
|---|---|---|
| `watch.progress` | media/catalog → archive, catalog | Триггер архивации, история, позиция |
| `video.archive.requested` | archive → archive-workers | Очередь задач скачивания |
| `video.archived` / `video.archive.failed` | archive → catalog | Обновление каталога, уведомления |
| `subscription.synced` | user/subscription → subscription | После привязки аккаунта |
| `channel.new_video` | subscription → subscription, notifications | Лента «новое», FCM |

## 14. API (черновик, префикс /api/v1)

```
POST /auth/login                         POST /auth/refresh
GET  /videos/{id}                        # publishedAt + канал/аватар + форматы + SponsorBlock + дизлайки
GET  /videos/{id}/comments?cursor=
GET  /search?q=&type=videos|channels|playlists&cursor=
GET  /channels/{id}                      # канал и аватар
GET  /channels/{id}/videos?cursor=       GET /channels/{id}/playlists?cursor=
GET  /playlists/{id}                     # плейлист с видео и превью
GET  /home                               # подписки + персональные рекомендации
GET  /feed/subscriptions
GET  /library?channelId=&sort=size|duration|title&direction=asc|desc
GET  /library/{videoId}/file             # MKV на устройство пользователя
DELETE /library/{videoId}                # удалить ссылку; физически — при последнем владельце
GET  /admin/library?user=&channel=       # админ: все библиотеки
GET  /admin/users                        # админ: пользователи и действующие квоты
PUT  /admin/users/{username}/quota       # админ: персональная квота
GET  /media/v/{id}/manifest.mpd          GET  /media/v/{id}/seg/{itag}?...
GET  /media/v/{id}/thumbnail             GET  /media/channel/{id}/avatar
GET  /media/playlist/{id}/thumbnail
POST /videos/{id}/save                   # одиночная архивация
POST /playlists/{id}/save                # пакетная архивация
POST /channels/{id}/save                 # пакетная архивация
GET  /archive/jobs?status=               # очередь и прогресс, ADMIN выше VIEWER
POST /users/me/youtube-link              GET  /users/me/youtube-link/callback
GET  /admin/egress/status                # админ: проба, режим, задержка
GET  /admin/storage                      # админ: NFS и квоты пользователей
```

## 59. Пустые архивные плейлисты в библиотеке

- У `viewer` после удаления всех видео остались архивные плейлисты без элементов. `LEFT JOIN` в `CatalogRepository.playlists` возвращал `{NULL}` в `video_ids`, то есть Java-список `[NULL]` после `getArray()`, а `List.of((String[]) ids.getArray())` запрещает null-элементы, поэтому `GET /api/v1/library` падал с `NullPointerException` и HTTP `500`.
- SQL теперь использует `coalesce(array_agg(distinct lpi.video_id) filter (where lpi.video_id is not null), '{}')`, поэтому пустой плейлист получает пустой массив. Маппер дополнительно фильтрует null-ID перед `toList`, чтобы не допустить повторение ошибки при любом массиве с null. Пустые плейлисты, их названия, thumbnails и права доступа не изменяются, записи пользователей не удаляются.
- `CatalogRepositoryTest.emptyArchivePlaylistDoesNotContainNullVideoIds` моделирует ответ `{NULL}` и проверяет пустой список видео и сохранение thumbnail. Полный `./gradlew build` проходит. Новая SQL-выборка на данных `viewer` возвращает `{}` для обоих проблемных плейлистов.


## 101. Ясные ошибки инвайтов и сброс пароля администратором

- `invite_tokens` получает `kind=REGISTRATION|PASSWORD_RESET` и `target_username`; существующие записи имеют `REGISTRATION` через миграцию по умолчанию. Коды разных назначений невзаимозаменяемы. Истёкший registration/reset-код возвращает HTTP `410` с конкретным `message`, использованный — `409`, неизвестный — `400`. `UserErrorHandler` превращает причину `ResponseStatusException` в JSON `message`, который показывает существующий web `responseError`.
- Администратор создаёт одноразовый код сброса через `POST /api/v1/admin/users/{username}/password-reset-invite` с `@PreAuthorize("hasRole('ADMIN')")`. Код генерируется из UUID, действует один час и привязан к выбранной учётной записи. Публичный `POST /api/v1/auth/password-reset` разрешён и в gateway, и в user-service; принимает только `invite` и новый `password`; имя пользователя не требуется, поскольку оно хранится в токене. Успешный ответ — `204`.
- Сброс доступен и для встроенных configured-аккаунтов: `AuthService` сохраняет BCrypt-хэш в `local_users` как override и при последующих входах сначала читает локальную запись, затем конфигурацию. Роль не меняется. Все refresh-сессии этого пользователя в `RefreshTokenService` удаляются; уже выданные access JWT сохраняют обычный срок жизни 15 минут. Неизвестный пользователь получает `404`, транзакция не расходует токен при неуспешном обновлении.
- `InviteServiceTest`, `AuthServiceTest` и `UserErrorHandlerTest` проверяют истёкший код, разделение назначений, привязку аккаунта, часовой TTL, override и отзыв refresh-токена. После реальной ошибки PostgreSQL `Can't infer the SQL type ... java.time.Instant` оба создания инвайта передают `Timestamp.from(expiresAt)`; тесты требуют JDBC `Timestamp` и для registration, и для reset. Полный `./gradlew build`, генерация OpenAPI TypeScript и production web build проходят.

## 108. Встроенный feedback tracker и доступ к тикетам

- `user-service` хранит тикеты в `feedback_tickets`, доступы — в `feedback_ticket_access`, хронологию комментариев и системных действий — в `feedback_ticket_entries`. Короткий ID — первые восемь заглавных символов UUID; полный UUID используется в API и ссылках. Создание принимает subject до 160 и description до 8000 символов, типы `Question/Bug/Feature/Improvement` и приоритеты `Minor/Major/Critical/Blocker`; значения по умолчанию — `Question/Major`, начальный статус — `Accepted`.
- Рабочий статус отделён от resolution. `ADMIN` переводит `Accepted/Testing → In Progress`, `In Progress → Testing` и закрывает открытый тикет с `Answered/Rejected/Duplicate`. Пользователь с access-строкой подтверждает только `Testing → Done + Completed`, а `Done + Completed/Answered` возвращает в `Accepted` с обязательной причиной и очисткой resolution; прежняя история сохраняется. Каждый переход проверяется сервером и получает системную запись. Администратор изменяет тип/приоритет, отвечает и ищет очередь по subject, author и full/partial UUID с фильтром статуса.
- Закрытие `Duplicate` требует UUID либо уникальный prefix существующего оригинала, запрещает self-reference и выбор другого duplicate. В одной транзакции duplicate закрывается, его author/participants получают access к оригиналу, а оригинал получает системную запись о присоединившихся. Собственная карточка и обсуждение duplicate не удаляются; ответ содержит `duplicateOf` и `duplicateSubject`. Неадминистратор без access получает `404`.
- Комментарии принимают клиентский UUID `requestId`. Уникальный `client_request_id` и `insert ... on conflict do nothing` делают повтор после потери ответа идемпотентным: тот же author/ticket/text возвращает текущий тикет без второго комментария и события, а reuse с другим содержимым получает `409`. `user_settings.feedback_notifications=true` — отдельная настройка уведомлений.
- `FeedbackEvents` отправляет `ready/ticket/ping` по SSE после commit, только участникам и администраторам; gateway отключает response timeout для `/api/v1/feedback/events`, nginx отключает buffering. `FeedbackServiceTest` покрывает ACL, недопустимые переходы, подтверждение, reopen, duplicate grants/self-reference и отсутствие повторного события при retry. Все feedback/admin endpoints и модели добавлены в `stage2.yaml`.
- Реальная PostgreSQL-приёмка выявила в close SQL nullable UUID inference и неправильный порядок параметров. Исправлены `duplicate_of = cast(? as uuid)` и порядок `resolution, original, id`; тест дополнительно фиксирует оба. Через публичный gateway с двумя временными VIEWER и временным ADMIN подтверждены полный `Done/Completed`, reopen в `Accepted` со старой историей, изоляция до Duplicate и доступ обоих к оригиналу после него, новый admin reply у обоих, SSE и Answered/null. Комментарий отправлен raw TCP с разрывом после записи, затем повторён тем же `requestId` дважды: в истории ровно одна запись; reuse с другим текстом получил `409`. Отдельная notification setting выключается/включается через API. Все временные пользователи, инвайты, тикеты, ACL и history удалены; контрольные counts равны нулю.

## 117. Персистентные сессии со sliding expiration на один год

- Refresh-сессии больше не находятся только в памяти: `refresh_sessions` в users PostgreSQL содержит SHA-256 `token_hash`, username, created_at, last_used_at и expires_at. Сырой refresh token в БД не записывается. `RefreshTokenService` сохраняет/читает записи через JDBC Timestamp, поэтому restart/recreate user-service не отзывает сессию.
- `REFRESH_TOKEN_TTL` по умолчанию `P365D`. При issue и rotation новая запись получает год; `POST /auth/activity` без смены token обновляет last_used_at и устанавливает expires_at на год от текущей активности. Rotation транзакционно удаляет старый hash перед выдачей нового, неизвестные/expired получают 401. Expired записи удаляются при issue, password reset по-прежнему вызывает revokeUser.
- Login/register/refresh возвращают прежний TokenPair и дополнительно persistent `spanktube_session` cookie с refresh token, `Max-Age=31536000`, `Path=/api/v1/auth`, HttpOnly и SameSite=Lax. Cookie работает на текущем HTTP deployment; Secure не добавляется до HTTPS. Refresh/activity/logout принимают JSON refreshToken либо cookie fallback. Logout удаляет текущую серверную сессию и отвечает cookie `Max-Age=0`; password reset также очищает cookie. Gateway разрешает auth activity/logout и уже имеет allowCredentials=true.
- `RefreshTokenServiceTest`, `SessionCookiesTest` и `AuthControllerTest` проверяют hash-only persistence, год, sliding renewal, expired/unknown rejection, cookie attributes, cookie-only refresh/activity и logout. OpenAPI обновлён для optional refresh body и новых endpoint.
- Production Chrome-приёмка подтвердила `Max-Age=31536000`, persistent/HttpOnly cookie, sliding `expires_at - last_used_at = 31536000` в PostgreSQL и renewal при page reload. После реального `restart user-service` refresh только по cookie вернул `200`, refresh token сменился, повторный reload сохранил пользователя. «Выйти» очистил cookie. Временный пользователь, invite и refresh_sessions удалены.
