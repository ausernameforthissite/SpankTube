# SpankTube — дизайн-документ прокси для YouTube

**Дата:** 2026-11-01
**Статус:** v0.4 (этап 1 завершён и проверен; этап 2 — архив и история)

## 1. Цели и область

Самохостируемый прокси для YouTube, разворачиваемый в локальном кластере Kubernetes. Кластер смотрит в сеть с фильтрацией Роскомнадзора («чебурнет»), прямой доступ к YouTube невозможен — весь исходящий YouTube-трафик идёт через **единый настраиваемый SOCKS5-прокси** (существующий danted в локальной сети, проверен на Telegram; резервный режим — VM с VPN-клиентом в роли default gateway). Клиенты (веб и Android) работают исключительно с API прокси и сами к YouTube не обращаются.

Функциональные требования:

- Просмотр видео с полной метаинформацией: описание с таймкодами, лайки/дизлайки, комментарии с таймкодами, сегменты SponsorBlock, все субтитры, все варианты качества.
- Просмотр произвольных каналов и плейлистов, а также собственных подписок пользователя.
- Лента новых видео с каналов из подписок.
- Архивация каждого просмотренного видео на NFS-шару: максимальное качество, все субтитры, один контейнер.
- Библиотека просмотренного: зритель — только своё, администратор — всё (с разбиением по пользователям, каналам и их комбинации).
- Две роли: `VIEWER`, `ADMIN`. Обе могут привязать свой YouTube-аккаунт для действий от своего имени.
- Клиенты: браузерный (React) и Android (Kotlin).

## 2. Ключевые риски и ограничения

1. **Антибот-защита YouTube (главный риск).** YouTube требует PO-токены для видеопотоков и субтитров у ряда клиентов; токен привязан к videoId и генерируется на каждое видео. Нужен отдельный генератор токенов (bgutil-pot-provider) и частые обновления yt-dlp.
2. **Подписанные URL googlevideo** живут ~6 часов и привязаны к IP запросившего — клиентам они бесполезны, байты должен проксировать сервер. Следствие: резолв URL и скачивание байтов должны идти через **один и тот же egress** (см. раздел 7).
3. **Фильтрация РКН и подмена DNS.** Прямой выход к YouTube из кластера невозможен; DNS-ответы провайдера для блокируемых доменов недостоверны — резолвинг имён YouTube должен происходить на стороне SOCKS5-прокси или через доверенный DNS (см. раздел 7).
4. **danted — единая точка отказа и узкое место полосы.** Через него пойдёт весь YouTube-трафик, включая видеобайты (≈5 Мбит/с на зрителя 1080p, до ~25 Мбит/с 4K). Резервный режим — VM с VPN.
5. **Дизлайки** официально недоступны с 2021 года: Return YouTube Dislike даёт точные данные только для архива до 12.2021, дальше — экстраполяция. В UI показывать как оценку.
6. **Квота Data API v3** — 10 000 единиц/день на проект; поиск стоит 100 единиц за вызов. API — только для аккаунтных операций, поиск и просмотр — через неофициальные адаптеры.
7. **Привязка личного Google-аккаунта** к автоматизированному клиенту — риск ограничений со стороны Google. Привязка опциональна: просмотр работает анонимно.
8. **ToS YouTube** запрещает скачивание и рестриминг; риск принимает владелец инфраструктуры. Весь «серый» код изолирован в одном сервисе для быстрой замены/починки.

## 3. Технологический стек

| Слой | Выбор |
|---|---|
| Язык/фреймворк | Java 25 (LTS), Spring Boot 4.x / Spring Framework 7 |
| Вход | Spring Cloud Gateway (за существующим ingress) |
| Egress в YouTube | Единый SOCKS5 (danted в LAN), режимы `socks5` / `direct` (VPN-VM), конфиг на кластер |
| БД | PostgreSQL (БД на сервис) |
| Кэш | Redis (stream-URL, метаданные, сессии) |
| Брокер | RabbitMQ (события + очереди задач). После стабилизации — пилот Kafka; событийная шина прячется за интерфейсом издателя/подписчика для безболезненной миграции |
| Архив | NFS PVC (RWX), ffmpeg для mux |
| Веб-клиент | React 19 + Vite + TypeScript + Shaka Player |
| Android | Kotlin + Jetpack Compose + Media3/ExoPlayer |
| Контракт API | OpenAPI-first, кодогенерация обоих клиентов |
| Деплой | Kubernetes (локальный кластер), ingress с wildcard-сертификатом |

## 4. Архитектура: обзор

```
                        ┌─────────────┐
 web / android ──HTTPS──│   ingress   │──► gateway (Spring Cloud Gateway, JWT)
                        └─────────────┘          │
        ┌───────────────┬───────────────┬────────┴───────┬────────────────┐
        ▼               ▼               ▼                ▼                ▼
  user-service    catalog-service  subscription    media-service    archive-service
  (пользователи,  (метаданные,     -service        (проксирование    (скачивание в
   роли, привязка  SponsorBlock,   (подписки,      googlevideo,      MKV на NFS,
   YT-аккаунтов)   RYD, история)   лента новых)    DASH-manifests)   каталог, ACL)
        │               │               │                │                │
        └───────┬───────┴───────┬───────┴────────────────┴────────────────┘
                ▼               ▼
        youtube-upstream-service        RabbitMQ (события / задачи)
        (ЕДИНСТВЕННАЯ точка выхода в YouTube:
         NewPipeExtractor + yt-dlp sidecar + Data API v3)
                │
                │  весь YouTube-трафик (upstream, media, archive, RSS/WebSub)
                ▼
        ┌───────────────────┐
        │  SOCKS5 egress:   │  (ConfigMap spanktube-egress + Secret;
        │  danted в LAN,    │   режим direct = VPN-VM gateway)
        │  remote DNS       │
        └───────────────────┘
                │
        PostgreSQL │ Redis │ NFS PVC (/archive)
```

## 5. Сервисы

| Сервис | Ответственность | YouTube-трафик |
|---|---|---|
| gateway | Маршрутизация, валидация JWT, rate limiting, BFF-агрегация экрана «страница видео» | нет |
| user-service | Внутренние пользователи (VIEWER/ADMIN), JWT (access + refresh, ротация), привязка YouTube-аккаунтов | нет (OAuth-колбэк Google — через egress) |
| youtube-upstream-service | Единственная точка выхода в YouTube; интерфейс `UpstreamProvider` с тремя адаптерами | да, через egress |
| catalog-service | Нормализованные метаданные видео/каналов, SponsorBlock + RYD, история просмотров | только через upstream; SponsorBlock/RYD — напрямую (не заблокированы), но под тем же конфигом на случай изменений |
| subscription-service | Подписки пользователя, детект новых видео (WebSub + RSS), лента | RSS/WebSub — через egress |
| media-service | Проксирование googlevideo (Range, DASH-манифесты), горячий кэш сегментов, отдача архивного с NFS | да, через egress |
| archive-service | Воркеры скачивания: bestvideo+bestaudio+все субтитры → MKV на NFS; каталог с ACL; админские выборки | да, через egress |

## 6. Слой доступа к YouTube (upstream)

Три адаптера за единым интерфейсом `UpstreamProvider`, выбор по типу операции, с fallback'ами:

1. **NewPipeExtractor** (Java, in-process) — основной безлогинный провайдер: метаданные, форматы, субтитры, комментарии с пагинацией, поиск, вкладки каналов, плейлисты.
2. **yt-dlp sidecar-демон** (REST-обёртка в поде) — fallback и инструмент архивного скачивания. Рядом контейнер bgutil-pot-provider для PO-токенов (ему тоже нужен выход в Google за BotGuard-скриптом — через тот же egress). Версия yt-dlp обновляется автоматической джобой (еженедельно).
3. **YouTube Data API v3** — только аккаунтные операции от имени привязанного пользователя: `subscriptions.list`, плейлисты, лайки/комментарии.

Разрешённые stream-URL кэшируются в Redis (TTL ~5 ч, ключ `videoId+itag`).

Все адаптеры получают HTTP-клиент от общего egress-модуля (раздел 7) — ни один адаптер не создаёт соединений сам.

## 7. Выход в интернет: единый SOCKS5-egress

### Конфигурация

Одна точка настройки на весь кластер:

```yaml
# ConfigMap spanktube-egress
mode: socks5            # socks5 | direct (direct — резервный режим VPN-VM gateway)
host: 192.168.x.x       # danted в локальной сети
port: 1080
remoteDns: true         # резолвинг имён на стороне прокси (аналог socks5h)
# Secret spanktube-egress (обязателен): username/password — на danted включена авторизация
```

Конфиг читают: youtube-upstream-service, media-service, archive-service, subscription-service (RSS/WebSub), user-service (OAuth-колбэки Google). SponsorBlock и Return YouTube Dislike по умолчанию идут напрямую (не заблокированы), но тоже подчиняются конфигу отдельным флагом `proxyAuxServices: false`.

### Реализация

- Общий автоконфигурационный модуль **`spanktube-egress-starter`** (Spring Boot starter): поднимает отдельный, посвящённый YouTube-трафику HTTP-клиент (Reactor Netty с `proxy(type = SOCKS5)`), инжектируемый в адаптеры. Внутренние соединения — PostgreSQL, Redis, RabbitMQ, межсервисные вызовы — идут напрямую, без прокси.
- **Запрещено** использовать глобальные JVM-флаги `-DsocksProxyHost/-DsocksProxyPort`: они заворачивают в SOCKS вообще все соединения JVM, включая БД и брокер.
- yt-dlp: `--proxy socks5h://[user:pass@]host:1080` (именно `socks5h` — резолвинг на стороне прокси).
- NewPipeExtractor: ему подсовывается тот же egress HTTP-клиент (Downloader-интерфейс NewPipe позволяет подменить транспорт).

### DNS

РКН подменяет DNS-ответы, поэтому имена YouTube нельзя резолвить кластерным/провайдерским DNS. Два рабочих варианта, выбирается конфигом:

1. **Remote DNS через SOCKS5** (`remoteDns: true`) — клиент передаёт прокси неразрешённое имя хоста, резолвит danted со своей стороны. Основной режим; проверить, что egress HTTP-клиент действительно шлёт unresolved hostname (в Netty это зависит от способа создания адреса — покрыть тестом).
2. **Доверенный DNS в кластере** — CoreDNS форвардит зону YouTube/Google на резолвер, ходящий через тот же SOCKS (DoH/DoT), либо на DNS VM с VPN. Запасной вариант, если где-то remote DNS не сработает.

### Консистентность egress IP

Подписанные URL googlevideo привязаны к IP, с которого их запросили. Поэтому резолв stream-URL (upstream) и скачивание байтов (media-service, archive-service) обязаны идти через один и тот же egress. Смешивать «upstream через danted, media напрямую через VPN-VM» нельзя — URL перестанут работать. При смене `mode` кэш разрешённых URL в Redis инвалидируется (ключ включает идентификатор egress-режима).

### Пропускная способность и отказоустойчивость

- Через danted пойдёт весь видеотрафик: ≈5 Мбит/с на зрителя 1080p, до ~25 Мбит/с 4K, плюс архивные скачивания. Следить за NIC/CPU хоста с danted.
- Если danted станет узким местом, конфиг допускает **раздельные прокси**: `egress.api` (метаданные) и `egress.media` (видеобайты) — например, метаданные через danted, а байты через VPN-VM. По умолчанию оба указывают на один SOCKS5.
- Резерв при падении danted: переключение `mode: direct` на VM с VPN (rolling restart затронутых сервисов). Healthcheck ниже даёт алерт для такого переключения.

### Проверка связности

- В upstream-service — периодическая проба `GET https://www.youtube.com/generate_204` через egress, метрика `upstream_egress_ok{mode=...}` + алерт. При недоступности egress сервисы честно отвечают 503 с причиной, а не молча деградируют.
- Smoke-этап в CI/CD и этап 1 прототипа: проверка «видео резолвится и первый сегмент скачан через egress».

## 8. Стриминг медиа

- media-service генерирует **свой DASH-манифест** `/media/v/{videoId}/manifest.mpd` из списка форматов upstream'а: все качества, аудиодорожки, субтитры. URL сегментов указывают на прокси.
- Проброс **Range-заголовков** к googlevideo (перемотка), keep-alive/HTTP2-пул соединений — через egress HTTP-клиент (раздел 7).
- Горячий LRU-кэш сегментов на локальном SSD пода (emptyDir с лимитом): повторный зритель не дёргает YouTube (и не грузит danted).
- Архивное видео стримится с NFS через **ffmpeg remux на лету** (`-c copy`, без перекодирования) в fMP4/DASH — браузеры MKV нативно не играют; ExoPlayer на Android умеет MKV сам.
- **Решение: только DASH**, HLS не делаем (целевые платформы — десктопные браузеры и Android; Safari не в скоупе).
- Оценка нагрузки: 1080p ≈ 5 Мбит/с на зрителя; медиатрафик выносим на отдельный ingress-хост.

## 9. Архив видео на NFS

- Триггеры архивации: (1) автоматически — событие `watch.progress`, порог «просмотрено» 30 сек или 5% (зафиксировано); (2) вручную — **кнопка «Сохранить»** у каждого видео, плейлиста и канала. Сохранение плейлиста/канала ставит пакетную задачу: перебор видео с rate limiting (не душим danted и не провоцируем антибот YouTube), дедупликация по `video_id`, прогресс джобы виден в UI.
- Воркер: yt-dlp `bv*+ba` (максимум качества, с `--proxy` из egress-конфига) + **все субтитры** (включая автогенерацию) → mux в **MKV** (`ffmpeg -c copy -c:s srt`). MKV выбран, т.к. вмещает произвольное число текстовых дорожек. Сегменты SponsorBlock вшиваются главами (chapters). Рядом — `info.json` и превью.
- **Дедупликация**: файл один на кластер; `videos(video_id, …)` + связка `user_views(user_id, video_id, …)`.
- Layout: `/archive/{channel_id}/{video_id}/video.mkv`.
- Видимость: зритель — только свои `user_views`; админ — всё, фильтры `?user=`, `?channel=` и их комбинация. Фильтрация на сервере, не на клиенте.
- Retention: **храним всё, автоочистки нет** — удаление только ручное (зритель удаляет свои записи из `user_views`, админ — любые видео). Контроль объёма — через метрику занятого места и виджет в админке (см. раздел 16).

## 10. Подписки и лента

- При привязке аккаунта: `subscriptions.list(mine=true)` → таблица каналов пользователя (1 единица квоты за страницу из 50).
- Детект новых видео: **WebSub-пуши** (PubSubHubbub-хаб YouTube шлёт webhook при загрузке на канале — входящий трафик, egress не нужен; но подписка/отписка на хабе — исходящий вызов, идёт через egress) — основной механизм; **polling RSS-фидов** каналов раз в час (тоже через egress) — реконсиляция. Оба бесплатны, квоту не тратят.
- Событие `channel.new_video` → лента → бейдж «новое» в клиентах; опционально FCM-пуш в Android.

## 11. Аутентификация, роли, привязка YouTube-аккаунта

- Внутренние пользователи: Spring Security, BCrypt, JWT (RSA), access 15 мин + refresh с ротацией. Роли: `ROLE_VIEWER`, `ROLE_ADMIN`. Keycloak сознательно не берём — оверкилл для двух ролей.
- Привязка YouTube: Google OAuth2 authorization code flow, scopes `youtube.readonly` (+ `youtube.force-ssl` для лайков/комментариев). Обмен кода на токены — серверный вызов к Google, идёт через egress. Refresh-токены в БД с envelope-шифрованием (KEK из Kubernetes Secret).
- Альтернатива для yt-dlp-сценариев: загрузка `cookies.txt` через UI, шифрованное хранение.
- Gateway валидирует JWT и прокидывает роль downstream; ACL-проверки дублируются на уровне запросов в archive/catalog.

## 12. Модель данных (черновик)

```sql
users(id, login, password_hash, role, created_at)
youtube_accounts(id, user_id FK, google_subject, refresh_token_enc, scopes, linked_at)
videos(video_id PK, channel_id, title, duration_sec, archived_path, quality, size_bytes, archived_at)
user_views(user_id FK, video_id FK, first_watched_at, last_position_sec, watch_percent,
           PRIMARY KEY (user_id, video_id))
subscriptions(user_id FK, channel_id, added_at, PRIMARY KEY (user_id, channel_id))
channel_videos(channel_id, video_id, published_at, PRIMARY KEY (channel_id, video_id))
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
POST /auth/login            POST /auth/refresh
GET  /videos/{id}           # агрегат: метаданные + форматы + SponsorBlock + дизлайки
GET  /videos/{id}/comments?cursor=
GET  /media/v/{id}/manifest.mpd
GET  /media/v/{id}/seg/{itag}?...
GET  /channels/{id}         GET /channels/{id}/videos?cursor=
GET  /search?q=
GET  /feed/subscriptions
GET  /library               # мои просмотренные
GET  /admin/library?user=&channel=    # админ
GET  /playlists/{id}                  # плейлист с видео
POST /videos/{id}/save                # кнопка «Сохранить»: видео
POST /playlists/{id}/save             # кнопка «Сохранить»: плейлист (пакетная архивация)
POST /channels/{id}/save              # кнопка «Сохранить»: канал (пакетная архивация)
GET  /archive/jobs                    # прогресс пакетных задач архивации
POST /users/me/youtube-link           # старт OAuth
GET  /users/me/youtube-link/callback
GET  /admin/egress/status             # админ: состояние egress (проба, режим, задержка)
GET  /admin/storage                   # админ: занято/свободно на NFS
```

## 15. Клиенты

- **Web**: React 19 + Vite + TS, Shaka Player (DASH, качество/аудио/субтитры), маркеры SponsorBlock на таймлайне с автопропуском, таймкоды в описании/комментариях — клиентский парсинг `mm:ss` → seek. Кнопка «Сохранить» у видео, плейлистов и каналов; экран прогресса пакетной архивации.
- **Android**: Kotlin + Compose + Media3/ExoPlayer, Retrofit + Room (офлайн-кэш метаданных), FCM для новых видео. Те же кнопки «Сохранить»; у админа — виджет занятого/свободного места на NFS.
- Контракт OpenAPI-first, кодогенерация клиентов из одной спецификации.

## 16. Деплой в Kubernetes

- Хосты: `spanktube.<домен>` (web), `api.spanktube.<домен>`, отдельный `media.spanktube.<домен>` (жирный трафик не конкурирует с API).
- Wildcard-сертификат в namespace: копия через reflector / External Secrets, либо `default-ssl-certificate` на ingress-контроллере.
- **Egress-конфиг**: ConfigMap `spanktube-egress` + Secret `spanktube-egress` (логин/пароль danted), монтируются/env в сервисы с YouTube-трафиком. NetworkPolicy: подам upstream/media/archive/subscription разрешён egress только на `danted_host:1080` и внутрикластерные адреса — страховка от случайного прямого выхода (который всё равно сломан фильтрацией и только засветит DPI).
- NFS: PV + PVC (RWX) на `/archive`; у archive-воркеров RW, у media-service — RO.
- Секреты (Google OAuth client secret, JWT-ключи, куки) — sealed-secrets / External Secrets Operator.
- Масштабирование: HPA для media-service (CPU/полоса), archive-воркеры по длине очереди (KEDA).
- CI/CD: GitHub Actions → локальный registry → Argo CD/Flux.
- Observability: Prometheus + Grafana, Loki; метрики upstream-адаптеров (доля ошибок по провайдерам) — ранний сигнал поломки YouTube-клиентов; метрика `upstream_egress_ok` и задержка egress-пробы — состояние danted; `nfs_storage_bytes{used,free}` — archive-service периодически читает statfs смонтированного архива (источник данных для `/admin/storage`).

## 17. Этапы реализации

1. **Ядро просмотра**: egress-starter + проба связности через danted, user-service, upstream, media-service, веб-страница видео (качество, субтитры). Проверяются оба главных риска: антибот YouTube и работа через SOCKS5.
2. **Архив и история**: RabbitMQ, archive-service, NFS, «мои видео», кнопка «Сохранить» (видео/плейлист/канал) с пакетными задачами, SponsorBlock + RYD.
3. **Социальная часть**: привязка аккаунтов, подписки, лента новых, комментарии.
4. **Админка и Android**: админские выборки, egress-статус и виджет места на NFS в админке, мобильный клиент, пуши.

## 18. Принятые решения (2026-10-31)

1. **Порог архивации**: автоматически при 30 сек или 5% просмотра; плюс явная кнопка «Сохранить» у видео, плейлистов и каналов (пакетная архивация с rate limiting и прогрессом в UI).
2. **Retention**: автоочистки нет, всё хранится до ручного удаления; администратору в клиентских приложениях показываем занятое и свободное место на NFS (`/admin/storage`).
3. **Стриминг**: только DASH, HLS не делаем.
4. **Аутентификация внутренних пользователей**: свой JWT на Spring Security, без Keycloak.
5. **Брокер**: RabbitMQ для прототипа и первых версий; после стабилизации приложения — пилот Kafka, поэтому событийная шина изолируется за интерфейсом издателя/подписчика.
6. **danted**: авторизация включена — конфиг egress состоит из IP, порта, имени пользователя и пароля (учётные данные в Secret `spanktube-egress`).
7. **Полоса egress**: один SOCKS5 на весь YouTube-трафик + мониторинг задержки и доступности; раздельные `egress.api` / `egress.media` остаются опцией конфигурации на будущее.

## 19. Приложение: прикидки ресурсов

- Трафик: ~5 Мбит/с на зрителя 1080p; исходящий к YouTube дедуплицируется горячим кэшем; весь он проходит через хост с danted.
- Хранилище: 1080p ≈ 2–4 ГБ/час контента, 4K ≈ 10–15 ГБ/час — закладываться на NFS соответственно.
- Архивный mux — `ffmpeg -c copy`, CPU почти не ест; remux-on-the-fly при воспроизведении архива — тоже без перекодирования.

## 20. Итоги этапа 1 и правила для следующих этапов

Этап 1 развёрнут и проверен в Docker на тестовой VM: `upstream_egress_ok=1`, метаданные реального видео, DASH-манифест только с URL media-service, `206 Partial Content` для реального googlevideo и воспроизведение play/pause в Shaka. Секреты находятся только в runtime env с правами `600` и исключены из Docker context.

Зафиксированные практические правила:

- Redirect googlevideo выполняется только через тот же egress и только на HTTPS-хосты `youtube.com`/`googlevideo.com` с поддоменами.
- Для MP4 `initRange` и `indexRange` вычисляются по настоящему `sidx` из контейнера. Искусственное разбиение файла на независимые DASH-сегменты запрещено: оно приводит к `MEDIA_SOURCE_OPERATION_FAILED`. Для форматов без индекса допустим только single-file fallback, без выдачи произвольных кусков за полноценные медиасегменты.
- `play()` и native controls доступны только после успешного `player.load()`. Таймауты manifest/streaming настраиваются отдельно (`timeout`, `connectionTimeout`, `stallTimeout`).
- Docker Compose — поддерживаемое окружение для интеграционных проверок; Kubernetes остаётся целевым production-окружением. BuildKit cache используется для Gradle, при подготовленном кэше допускается `GRADLE_EXTRA_ARGS=--offline`.
- Для WebM и архивного remux нельзя повторять искусственное разбиение MP4: нужен корректный индекс/сегментация либо single-file режим.

План и критерии приёмки этапа 2:

1. `archive-service`: скачивание `bv*+ba` и всех субтитров через yt-dlp, MKV с `ffmpeg -c copy -c:s srt`, главы SponsorBlock, `info.json`, layout `/archive/{channel_id}/{video_id}/video.mkv`, NFS RWX, ручное удаление без автоочистки.
2. `catalog-service`: PostgreSQL, метаданные, SponsorBlock, Return YouTube Dislike с явной пометкой оценки, `videos` и `user_views`, серверные ACL-проверки для `VIEWER` и `ADMIN`.
3. События `watch.progress`, `video.archive.requested`, `video.archived` / `video.archive.failed` — через RabbitMQ, но только через собственный интерфейс издателя/подписчика. Автотриггер — 30 секунд или 5% просмотра.
4. Web: кнопка «Сохранить» у видео/плейлиста/канала, «мои видео», прогресс одиночных и пакетных задач, admin-выборки. Пакетная архивация ограничена rate limiting и дедупликацией по `video_id`.
5. Приёмка на реальном видео: MKV и `info.json` появляются на NFS; повторное сохранение не создаёт второй файл; зритель видит только свои записи, администратор — все; архивное видео воспроизводится через DASH без перекодирования видео/аудио; ошибки yt-dlp и недоступности NFS видны пользователю.
