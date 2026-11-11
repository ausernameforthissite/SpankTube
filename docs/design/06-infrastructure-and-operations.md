# Инфраструктура и эксплуатация

> Тематический справочник по части приложения. Исходные разделы: 7, 16, 26, 42, 49, 60, 77.

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

## 16. Деплой в Kubernetes

- Хосты: `spanktube.<домен>` (web), `api.spanktube.<домен>`, отдельный `media.spanktube.<домен>` (жирный трафик не конкурирует с API).
- Wildcard-сертификат в namespace: копия через reflector / External Secrets, либо `default-ssl-certificate` на ingress-контроллере.
- **Egress-конфиг**: ConfigMap `spanktube-egress` + Secret `spanktube-egress` (логин/пароль danted), монтируются/env в сервисы с YouTube-трафиком. NetworkPolicy: подам upstream/media/archive/subscription разрешён egress только на `danted_host:1080` и внутрикластерные адреса — страховка от случайного прямого выхода (который всё равно сломан фильтрацией и только засветит DPI).
- NFS: PV + PVC (RWX) на `/archive`; у archive-воркеров RW, у media-service — RO.
- Секреты (Google OAuth client secret, JWT-ключи, куки) — sealed-secrets / External Secrets Operator.
- Масштабирование: HPA для media-service (CPU/полоса), archive-воркеры по длине очереди (KEDA).
- CI/CD: GitHub Actions → локальный registry → Argo CD/Flux.
- Observability: Prometheus + Grafana, Loki; метрики upstream-адаптеров (доля ошибок по провайдерам) — ранний сигнал поломки YouTube-клиентов; метрика `upstream_egress_ok` и задержка egress-пробы — состояние danted; `nfs_storage_bytes{used,free}` — archive-service периодически читает statfs смонтированного архива (источник данных для `/admin/storage`).

## 26. Инцидент с PostgreSQL и неполным архивным DASH

- Ошибка `500` для `TM0qeOHg1oI` возникла во время recovery PostgreSQL: корневая ФС была заполнена на 100%. После восстановления БД internal endpoint снова вернул `200`. Удалены dangling-образы и, с подтверждения пользователя, `5,378 ГиБ` Docker Build Cache; зависимости и PostgreSQL вернулись в `healthy`. Правило регулярной очистки dangling-образов записано в `AGENTS.md`.
- Прерванный нехваткой места `ffmpeg` оставил свежий `manifest.mpd` и `.tmp`-сегменты, которые раньше ошибочно считались готовым кэшем и приводили к бесконечной буферизации. Теперь кэш недействителен при наличии `.tmp` или устаревании относительно MKV. Новый DASH создаётся в отдельном `.building`-каталоге и публикуется только после успешного `ffmpeg`; при ошибке временный каталог удаляется.
- Регрессионный тест `ArchivePlaybackServiceTest` проходит. Для `TM0qeOHg1oI` кэш пересобран: 152 видеофрагмента, 230 аудиофрагментов, `.tmp` нет. В браузере подтверждён статус «Воспроизведение».
- Исходная причина скачивания зависимостей при деплое — таймаут HTTPS из Docker-процессов — на уровне сети Docker не устранена. Для этого исправления образ собран offline из локально проверенного `bootJar` с временным Dockerfile, который затем удалён; постоянные Dockerfile и runtime egress не изменялись.

## 42. Исправление `405` для archive locations

- Плашка `HTTP 405` над работающим плеером не была связана с YouTube или видеопотоком. Её вызывал фоновый `GET /api/v1/library/locations?scope=global`, который заполняет значки «есть в архиве» на карточках.
- В репозитории endpoint существовал, но на VM оказалась старая версия `LibraryController.java` без него. Проверка checksum выявила рассинхронизацию также `LibraryService`, `LibraryModels`, `CatalogRepository` и `schema.sql`. Весь исходник catalog-service синхронизирован с репозиторием и пересобран; при последующих Docker-деплоях нельзя полагаться на частичную синхронизацию отдельных файлов.
- `LibraryControllerTest` через standalone MockMvc проверяет `GET /api/v1/library/locations?scope=global` с identity пользователя и вызов `LibraryService.locations(null)` без выдачи админских данных.
- После деплоя endpoint возвращает `200` напрямую и через `8088`, глобальный список содержит 36 позиций. На `6smfoDKverI` DASH продолжает воспроизводиться, а `HTTP 405` в DOM отсутствует. Полный `./gradlew build` и backend health-check проходят; временные пользователь, инвайт и прогресс удалены.

## 49. Восстановление прогрессивного MP4 и архивации после сбоев egress

- `EgressProbe` выполняет не более двух повторов с backoff 1/2 секунды при сетевом сбое или обрыве соединения. Одиночный таймаут `generate_204` больше не должен мгновенно переводить последующие Range-запросы в `503`; `upstream_egress_ok=0` устанавливается после исчерпания повторов. HTTP-ошибки самой пробы не повторяются.
- `ProgressiveDashService` скачивает источник чанками по 8 МиБ с `Range`, начиная с размера уже существующего `source.mp4`. Перед каждой попыткой незавершённый чанк обрезается до его начального смещения, а запись ведётся через `FileChannel.position(offset)` на `boundedElastic`, без платформенно зависимого `APPEND` в asynchronous channel. Размер тела сверяется с `Content-Length`; попытка ограничена двумя минутами, сетевые обрывы получают не более двух повторов. HTTP `403/404/429` не повторяются. Вывод ffmpeg читается асинхронно.
- Web для одиночной совмещённой MP4-дорожки использует `player.load()` с manifest timeout 15 минут и видимым журналом, не делая отдельный prefetch перед вторым `player.load()`. Это исключает `Shaka Error 1003` на двухминутном таймауте манифеста во время длинной подготовки и повторный HTTP-запрос MPD. Обычные адаптивные форматы и архивный `OnDemandDashService` по этому пути не изменены.
- `MediaClient` архивации повторяет только текущий чанк при сетевом обрыве и ответах `502/503/504`, максимум два повтора с backoff 1/2 секунды. Уже завершённые диапазоны не скачиваются заново, частичные байты неудавшегося чанка отбрасываются, фактический размер сверяется с `Content-Length`. Прочие ошибки и тела upstream-ошибок не маскируются.
- `EgressProbeTest` проверяет восстановление пробы после обрыва; `ProgressiveDashServiceTest` — продолжение частичного MP4 и повтор прерванного тела без дублирования байтов на Windows и Linux; `MediaClientTest` — повтор `503` и обрезание частичного чанка. Архивный DASH из раздела 48 не изменялся и проверяется отдельно.
- В web nginx для `GET /media/v/{id}/manifest.mpd` задан `proxy_read_timeout=900s`: прежний предел 60 секунд обрывал первую длительную подготовку с `504` ещё до готовности MPD. Он согласован с внешним пределом prefetch, а не заменяет собой таймауты чанков и повторы backend.
- Docker-приёмка `h07SpmhcWUE`: частичный `source.mp4` продолжен с `336852480` до `518013910` байт, MPD создан, Shaka перешёл в фактическое состояние «Воспроизведение» и запросил последовательные DASH-фрагменты. `MQziBPCMEL8` также воспроизводится в том же сеансе. Архивная задача завершилась со статусом `COMPLETED`; MKV имеет размер `519598747` байт, длительность `8339,609` с, дорожки H.264 и AAC. Повторная проверка `80o75wemHb8` подтвердила фактическое воспроизведение большого архивного MKV и паузу.

## 60. Повторное разрешение gateway в web nginx

- При пересоздании gateway его IP в Docker-сети изменился с `172.18.0.8` на `172.18.0.11`. Прямой `POST /api/v1/auth/login` на gateway возвращал `200`, а тот же запрос через web nginx на `8088` — `502`: nginx продолжал использовать старый адрес upstream, полученный при старте. Это не ошибка пароля или `user-service`.
- `web/nginx.conf` теперь содержит `resolver 127.0.0.11 valid=5s ipv6=off` и переменную `$gateway=http://gateway:8080`. Все три `proxy_pass` используют переменную, поэтому Docker DNS повторно разрешает hostname каждые пять секунд вместо бессрочного кэширования старого IP. `Host`, 900-секундный таймаут MPD и настройки streaming/SSE не изменены.
- Регрессионная проверка состоит из `nginx -t`, повторного перезапуска gateway и запросов `POST /api/v1/auth/login` и `GET /api/v1/library` через порт `8088` от имени `viewer`. Production web build проходит.


## 77. Сброс proxy на Android: основные и legacy-ключи

- После временного HTTP→SOCKS5 bridge для LibreTube Chrome перестал открывать SpankTube: DevTools показал `net::ERR_TIMED_OUT`, а nginx не получал HTTP-запрос устройства. При этом `settings get global http_proxy` возвращал `null`, `dumpsys wifi` — `Proxy settings: NONE`, а `curl` с телефона успешно получал `200 OK`. Это не было ошибкой web-приложения или отсутствием сети.
- `/proc/net/tcp` показал исходящие соединения в `SYN_SENT` на старый bridge `192.168.88.188:8888`. `settings list global` выявил отдельные legacy-ключи `global_http_proxy_host=192.168.88.188` и `global_http_proxy_port=8888`, которые не исчезли после `http_proxy=null`. Поэтому проверять только обычное значение `http_proxy` и Wi-Fi proxy недостаточно.
- Для полного сброса `global_http_proxy_host` установлен в пустую строку, `global_http_proxy_port` — в `0`; основной `http_proxy` временно установлен в `:0`, затем восстановлен в `null`. `http_proxy_host` и `http_proxy_port` также очищены. `global_http_proxy_exclusion_list` и `global_proxy_pac_url` были пустыми, Private DNS не изменялся.
- После сброса `:0` Chrome установил прямое соединение с `192.168.88.172:8088` и открыл страницу входа SpankTube. `http_proxy` восстановлен в `null`, bridge остановлен, DevTools forwarding удалён, временные сетевые логи и скриншоты удалены. При последующих Android proxy-тестах проверять `settings list global` и фактический TCP-путь, а не только `settings get http_proxy`.
