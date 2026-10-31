# AGENTS.md — SpankTube

> Проектная память для Kimi Code. Полный контекст — в `docs/design.md` (дизайн-документ v0.3). Если этого файла нет в репозитории — попроси пользователя добавить его и НЕ выдумывай архитектуру самостоятельно.

## Что это

Самохостируемый прокси YouTube для личного использования. Все обращения к YouTube выполняет только серверная часть; клиенты (React web, Android/Kotlin) работают исключительно с API прокси. Кластер Kubernetes находится за фильтрацией РКН — весь YouTube-трафик идёт через единый SOCKS5-прокси (danted в локальной сети, с авторизацией логин/пароль).

## Стек

- Backend: Java 25 (LTS), Spring Boot 4.x / Spring Framework 7, Gradle (Kotlin DSL), микросервисы в монорепозитории
- Web: React 19 + Vite + TypeScript + Shaka Player
- Android: Kotlin + Jetpack Compose + Media3/ExoPlayer (этап 4)
- Инфраструктура: PostgreSQL (БД на сервис), Redis, RabbitMQ, NFS PVC (RWX) под архив, Kubernetes + ingress с wildcard TLS
- Контракт API: OpenAPI-first, кодогенерация клиентов

## Целевая структура монорепозитория

```
spanktube/
├── backend/
│   ├── gateway/                   # Spring Cloud Gateway: JWT, маршрутизация, BFF-агрегация
│   ├── user-service/              # пользователи, роли, JWT, привязка YouTube-аккаунтов
│   ├── youtube-upstream-service/  # ЕДИНСТВЕННАЯ точка доступа к YouTube
│   ├── catalog-service/           # метаданные, SponsorBlock, RYD, история
│   ├── subscription-service/      # подписки, лента новых видео
│   ├── media-service/             # DASH-манифесты, проксирование googlevideo
│   ├── archive-service/           # скачивание в MKV на NFS, каталог, ACL
│   └── lib/
│       └── egress-starter/        # общий модуль SOCKS5-egress (см. правило 1)
├── web/
├── android/
├── deploy/                        # манифесты K8s / helm
├── docs/design.md
└── AGENTS.md
```

## Жёсткие правила (принятые решения — не нарушать без явной команды пользователя)

1. **Egress.** Весь трафик к YouTube/Google — только через `egress-starter` (SOCKS5 с авторизацией, remote DNS в духе `socks5h`). Глобальные JVM-флаги `-DsocksProxyHost` / `-DsocksProxyPort` ЗАПРЕЩЕНЫ: они заворачивают в прокси соединения с БД и брокером. Внутрикластерные соединения (PostgreSQL, Redis, RabbitMQ, межсервисные) — всегда напрямую.
2. **Консистентность egress IP.** Резолв stream-URL и скачивание байтов googlevideo — строго через один и тот же egress: подписанные URL привязаны к IP запросившего. Ключ кэша URL в Redis включает идентификатор egress-режима; при смене режима кэш инвалидируется.
3. **Клиенты не ходят в YouTube.** Никогда и ни при каких условиях: все URL в ответах API ведут на media-service.
4. **Стриминг — только DASH.** HLS не реализуем (Safari вне скоупа).
5. **Аутентификация.** Свой JWT на Spring Security (BCrypt, RSA-ключи, access 15 мин + refresh с ротацией). Роли: `VIEWER`, `ADMIN`. Keycloak не используем. ACL-проверки (зритель видит только свои просмотренные видео) — на сервере.
6. **Событийная шина.** Доступ к брокеру — только через собственный интерфейс издателя/подписчика, без сырого RabbitMQ-клиента в бизнес-логике: после стабилизации запланирована миграция на Kafka.
7. **Архив.** MKV (`bv*+ba` + все субтитры, `ffmpeg -c copy -c:s srt`), сегменты SponsorBlock — главами, `info.json` рядом. Дедупликация по `video_id`: файл один на кластер, видимость через `user_views`. Автоочистки нет, удаление ручное. Кнопка «Сохранить» — у видео, плейлистов и каналов (пакетная архивация с rate limiting).
8. **Upstream.** Весь доступ к YouTube — в youtube-upstream-service за интерфейсом `UpstreamProvider`: NewPipeExtractor (in-process), yt-dlp (sidecar), Data API v3 (только аккаунтные операции, квота 10k ед./день). PO-токены — через bgutil-pot-provider. Дизлайки — только Return YouTube Dislike, в API помечать как оценку.
9. **Секреты.** Учётные данные danted, Google OAuth client secret, JWT-ключи, куки — только Kubernetes Secrets / переменные окружения. В коде и репозитории секретов нет.

## Текущий этап: 1 — ядро просмотра

Цель этапа — на реальном видео проверить два главных риска: антибот YouTube (PO-токены) и стриминг через SOCKS5.

Скоуп: `egress-starter` (SOCKS5-клиент + проба `GET https://www.youtube.com/generate_204` с метрикой `upstream_egress_ok`), `user-service` (логин/JWT), `youtube-upstream-service` (метаданные + stream-URL), `media-service` (DASH-манифест + проксирование сегментов), минимальная веб-страница просмотра. Архив, подписки, комментарии и Android — НЕ входят в этап.

## Окружение

- Параметры danted (IP, порт, логин, пароль) — у пользователя; брать из env/локального конфига, не коммитить.
- Кластер: локальный Kubernetes, ingress с wildcard-сертификатом уже настроен; NFS-шара для архива существует.
- Если YouTube отвечает антибот-ошибкой — не маскировать её костылями: показать ошибку пользователю и предложить варианты (PO-token provider, обновление yt-dlp, смена адаптера).

## Команды

_Заполняется по мере появления каркаса:_

- Сборка: `./gradlew build`
- Тесты модуля: `./gradlew :backend:<module>:test`
- Web: `cd web && npm run dev`
