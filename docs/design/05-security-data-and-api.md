# Безопасность, данные и API

> Тематический справочник по части приложения. Исходные разделы: 10, 11, 12, 13, 14, 59.

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

- У `viewer` после удаления всех видео остались архивные плейлисты без элементов. `LEFT JOIN` в `CatalogRepository.playlists` возвращал `{NULL}` в `video_ids`, а `List.of((String[]) ids.getArray())` запрещает null-элементы, поэтому `GET /api/v1/library` падал с `NullPointerException` и HTTP `500`.
- SQL теперь использует `coalesce(array_agg(distinct lpi.video_id) filter (where lpi.video_id is not null), '{}')`, поэтому пустой плейлист получает пустой массив. Маппер дополнительно фильтрует null-ID перед `toList`, чтобы не допустить повторение ошибки при любом массиве с null. Пустые плейлисты, их названия, thumbnails и права доступа не изменяются, записи пользователей не удаляются.
- `CatalogRepositoryTest.emptyArchivePlaylistDoesNotContainNullVideoIds` моделирует ответ `{NULL}` и проверяет пустой список видео и сохранение thumbnail. Полный `./gradlew build` проходит. Новая SQL-выборка на данных `viewer` возвращает `{}` для обоих проблемных плейлистов.
