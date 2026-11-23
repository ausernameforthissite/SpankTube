# AGENTS.md — SpankTube

> Краткая проектная память для Kimi Code. Полный контекст, включая историю решений и приёмки, — в `docs/design.md` и тематических файлах `docs/design/` (v0.6). Если документации нет в репозитории — попроси пользователя добавить её и НЕ выдумывай архитектуру самостоятельно.

## Обязательное чтение проектной документации

1. Сначала прочитай `docs/design.md`: там навигация и правила выбора файлов по задаче.
2. Перед изменением кода или конфигурации прочитай **целиком** тематический файл для каждой затронутой области, не ограничиваясь поиском отдельных строк:
   - `docs/design/00-overview.md` — архитектура, этапы и принятые решения, включая раздел 18;
   - `docs/design/01-upstream-and-metadata.md` — upstream, NewPipe, метаданные, кэш и PO-токены;
   - `docs/design/02-media-and-playback.md` — media-service, DASH/HLS и воспроизведение;
   - `docs/design/03-archive-and-library.md` — архив, очередь, NFS, версии и библиотека;
   - `docs/design/04-web-and-clients.md` — web UI, сессии, плеер, навигация и SSE;
   - `docs/design/05-security-data-and-api.md` — аутентификация, данные, события и API;
   - `docs/design/06-infrastructure-and-operations.md` — egress, Docker/Kubernetes и эксплуатация.
3. При межсервисной задаче прочитай все связанные файлы, а при изменении принятых решений обязательно `00-overview.md`. Разделы 1–120 сохранены в тематических файлах ровно один раз; ссылки на их номера действительны.
4. При изменении поведения обновляй соответствующий тематический файл. Новые решения, детали реализации и результаты приёмки добавляй туда очередным пронумерованным разделом, не переписывая историю. В `AGENTS.md` вноси только изменения кратких действующих правил, навигации, окружения или команд; не копируй сюда журналы и отчёты.
5. При расследовании регрессий и старых инцидентов читай относящиеся к ним пронумерованные разделы тематических файлов. Более поздние разделы могут уточнять ранние; не восстанавливай историческое окружение по старому отчёту без проверки актуальной конфигурации.

## Что это

Самохостируемый прокси YouTube для личного использования. Все обращения к YouTube выполняет только серверная часть; клиенты (React web, Android/Kotlin) работают исключительно с API прокси. YouTube-трафик идёт через настраиваемый `egress-starter`: SOCKS5 danted либо direct через VPN-gateway.

Production web, gateway, user/catalog/archive-service, PostgreSQL и RabbitMQ работают в локальном Kubernetes, namespace `spanktube`. Upstream, media-service, Redis и bgutil остаются на CT 138 как egress-box с direct через b4 `192.168.88.175`; актуальная схема — разделы 119–120 design.

## Стек

- Backend: Java 25 (LTS), Spring Boot 4.x / Spring Framework 7, Gradle (Kotlin DSL), микросервисы в монорепозитории.
- Web: React 19 + Vite + TypeScript + Shaka Player.
- Android: Kotlin + Jetpack Compose + Media3/ExoPlayer (этап 4).
- Инфраструктура: PostgreSQL (БД на сервис), Redis, RabbitMQ, NFS PVC (RWX) под архив, Kubernetes + ingress с wildcard TLS.
- Контракт API: OpenAPI-first, кодогенерация клиентов.

## Структура монорепозитория

- `backend/gateway`, `user-service`, `youtube-upstream-service`, `catalog-service`, `media-service`, `archive-service`; `subscription-service` запланирован на этап 3.
- `backend/lib/egress-starter` — общий egress; `backend/lib/event-bus` — интерфейс событий и RabbitMQ-адаптер.
- `web/`, `android/`, `deploy/`, `docs/design/`; навигация по документации — `docs/design.md`.

## Жёсткие правила (принятые решения — не нарушать без явной команды пользователя)

1. **Egress.** Весь трафик к YouTube/Google — только через `egress-starter` (SOCKS5 с авторизацией/remote DNS либо direct через VPN-gateway). Сейчас upstream и media-service на CT 138 (`192.168.88.172`) используют direct через b4 `192.168.88.175`; на b4 включён `spanktube-vpn-forward.service` с source-routing и симметричным MASQUERADE для forwarded-трафика (разделы 87, 94, 119). CT 139 остановлен, его автозапуск отключён, профиль `legacy-socks5` не используется. Глобальные JVM-флаги `-DsocksProxyHost` / `-DsocksProxyPort` ЗАПРЕЩЕНЫ: они заворачивают в прокси соединения с БД и брокером. Внутрикластерные соединения (PostgreSQL, Redis, RabbitMQ, межсервисные) — всегда напрямую.
2. **Консистентность egress IP.** Резолв stream-URL и скачивание байтов googlevideo — строго через один и тот же egress и публичный IP: подписанные URL привязаны к IP запросившего. В direct-режиме upstream и media-service обязаны использовать один VPN-gateway. Ключ кэша URL в Redis включает идентификатор egress-режима; при смене режима кэш инвалидируется.
3. **Клиенты не ходят в YouTube.** Никогда и ни при каких условиях: все URL в ответах API ведут на media-service. Это относится и к превью/аватарам. Маршруты web-клиента повторяют YouTube (`/watch?v=`, `/channel/`, `/c/`, `/@`, `/playlist?list=`), но ведут на SpankTube.
4. **Стриминг — DASH для обычных видео и записей трансляций.** По явному решению пользователя HLS разрешён только для активного эфира (раздел 54 design), без перехода других видео на HLS.
5. **Аутентификация.** Свой JWT на Spring Security (BCrypt, RSA-ключи, access 15 мин + refresh с ротацией). Refresh-сессии хранятся в PostgreSQL со sliding TTL; поддерживаются persistent HttpOnly-cookie и JSON TokenPair/localStorage (разделы 117–118). Роли: `VIEWER`, `ADMIN`. Keycloak не используем. ACL-проверки личной библиотеки и истории — на сервере. Любой аутентифицированный клиент может воспроизводить и скачивать уже существующую глобальную архивную копию, не добавляя её в свою библиотеку или квоту.
6. **Событийная шина.** Доступ к брокеру — только через собственный интерфейс издателя/подписчика, без сырого RabbitMQ-клиента в бизнес-логике: после стабилизации запланирована миграция на Kafka.
7. **Архив и квоты.** MKV (`bv*+ba` + только созданные людьми субтитры, `ffmpeg -c copy -c:s srt`; автогенерированные не скачивать), сегменты SponsorBlock — главами, `info.json` рядом. Дедупликация активной версии по `video_id`: владельцы через `user_views`, физическое удаление активной версии только после удаления последней ссылки. Версии сохраняются в `video_versions` и не перезаписывают друг друга; в квоту входят все версии с `deleted_at IS NULL`. Квоты: `ADMIN` 50 ГиБ, `VIEWER` 10 ГиБ через env, персональные переопределения админом, LRU при 90% только для активной версии — история по LRU не удаляется, при нехватке места архивация останавливается с ошибкой. Ручное удаление активной версии разрешено. Кнопка «Скачать» — у видео, плейлистов и каналов: персистентная очередь, `ADMIN` приоритетнее `VIEWER`, FIFO внутри роли, случайные задержки между запросами. Обычные save/refresh/download для активного эфира запрещены; отдельная ручная запись эфира разрешена по разделу 56 design.
8. **Upstream.** Весь доступ к YouTube — в youtube-upstream-service за интерфейсом `UpstreamProvider`: NewPipeExtractor (in-process), yt-dlp (sidecar), Data API v3 (только аккаунтные операции, квота 10k ед./день). Поиск видео/каналов через Data API v3 не выполняем. PO-токены — через bgutil-pot-provider. Дизлайки — только Return YouTube Dislike, в API помечать как оценку.
9. **Секреты.** Учётные данные danted, Google OAuth client secret, JWT-ключи, куки — только Kubernetes Secrets / переменные окружения либо выделенный runtime-файл с ограниченными правами. Исходные YouTube cookies на CT 138 хранятся только как `/root/.secrets/youtube-cookies.txt` (`root:10001`, `640`, каталог `750`) и монтируются read-only. Compose `entrypoint` копирует их при запуске в `/tmp/youtube-cookies.txt` (`10001:10001`, `600`), потому что yt-dlp перезаписывает cookie jar при выходе (раздел 88). В коде, репозитории, образах и журналах секретов нет.

## Текущий этап: 2 — архив и история

Этап 2 реализован; production перенесён в Kubernetes (раздел 119). NFS/MKV, библиотека, ACL, LRU, поиск, превью, очередь с ADMIN-приоритетом и архивный DASH проверены. Автосубтитры для архива отключены. Отдельная приёмка полного MKV со SponsorBlock-главами не завершена; ошибки `429 Too Many Requests` не обходить.

Полный скоуп этапов — разделы 1 и 17 в `00-overview.md`. Подписки и гибридные рекомендации — этап 3, комментарии и Android не входят в этап 2.

Правила из этапа 1: redirect только на разрешённые YouTube/googlevideo-хосты через тот же egress; DASH для MP4 строится по настоящему `sidx`, искусственные byte-range сегменты запрещены; `play()` вызывается только после завершения `player.load()`.

## Окружение

- Параметры danted (IP, порт, логин, пароль) — у пользователя; брать из env/локального конфига, не коммитить.
- Production ingress: `spanktube.fulldungeon.duckdns.org` с wildcard TLS; отдельный SSE-ingress без proxy-buffering. Архивная NFS-шара существует. Детали Kubernetes, NodePort и загрузки образов — раздел 119.
- Upstream/media опубликованы на LAN `192.168.88.172:8081/:8082`. Старая WAN-цепочка через b4 `:8088` удалена; source-routing и MASQUERADE для egress сохранены.
- Действия с Authelia закреплены в проектных скиллах `.kimi-code/skills/`: `authelia-invite` (инвайты и пользователи), `authelia-config` (конфигурация, сессии, секрет), `authelia-protect-service` (публикация сервиса за 2FA). При любых правках на уровне Authelia и связанных с ней сервисов (ingress с auth-аннотациями, портал на базовом домене, code-server/code-pve, инвайты, манифесты в `/root/k8s/code-server/`) обязательно синхронно корректируй затронутые скиллы — они, а не устаревшие разделы design, первичная инструкция к действию.
- code-server с Authelia 2FA живёт в namespace `code-server` кластера (`code.fulldungeon.duckdns.org`), манифесты на kimi-code в `/root/k8s/code-server/`; воркспейсы — NFS `/home/human` с Proxmox и `/root` с kimi-code; SA `human` (kube-system) для kubectl. Второй code-server стоит на самом Proxmox (`code-pve.fulldungeon.duckdns.org`, systemd, порт 8083); портал входа со ссылками — на базовом домене `fulldungeon.duckdns.org`. SpankTube за Authelia не прячем (откат, раздел 123). Инвайты в Authelia — скрипт `/root/k8s/code-server/authelia-invite.sh` (add/remove/code); доступ к code/code-pve — только группа `admins` (human), портал — для всех с 2FA. Сессия Authelia: sliding, год бездействия. Remote-SSH-расширения из браузерного клиента не работают архитектурно. Детали — разделы 121–122 `docs/design/06-infrastructure-and-operations.md`, раздел 123 `05-security-data-and-api.md`.
- Если YouTube отвечает антибот-ошибкой — не маскировать её костылями: показать ошибку пользователю и предложить варианты (PO-token provider, обновление yt-dlp, смена адаптера).

## Команды

- Сборка: `./gradlew build`
- Тесты модуля: `./gradlew :backend:<module>:test`
- Web: `cd web && npm run dev`
- Web build: `npm run build --prefix web`
- OpenAPI client: `npx openapi-typescript docs/openapi/stage2.yaml -o web/src/api/schema.ts`
- Docker Compose: `docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d --build`
- После каждого Docker-деплоя: проверить dangling-образы `docker image ls --filter dangling=true` и удалить их `docker image prune -f`. При нехватке диска проверить `df -h /` и `docker system df`; при подтверждённой нехватке места разрешено очистить неиспользуемый Build Cache `docker builder prune -f` без отдельного запроса пользователя, затем проверить итоговый `df -h /`. Контейнеры, образы работающих сервисов, тома и NFS не затрагивать. После очистки не использовать offline-сборку, пока кэш зависимостей не восстановлен обычной сборкой.
- Offline-сборка при подготовленном BuildKit cache: `GRADLE_EXTRA_ARGS=--offline docker compose --env-file deploy/.env -f deploy/docker-compose.yml build`
