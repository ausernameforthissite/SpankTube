# Инфраструктура и эксплуатация

> Тематический справочник по части приложения. Исходные разделы: 7, 16, 26, 42, 49, 60, 77, 85, 86, 87, 88, 89, 94.

## 7. Выход в интернет: SOCKS5 и direct egress

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


## 85. Direct VPN-egress, YouTube cookies и сравнение с LibreTube

- Создан privileged CT 139 `spanktube-upstream` на основе CT 118: 4 vCPU, 4096 МБ RAM, rootfs 20 ГБ, MAC `F6:DF:0D:11:0A:1B`, `192.168.88.174/24`, default gateway `192.168.88.162`. Root SSH по ключу работает с Proxmox и из CT 400 `kimi-code`. На CT 139 запущены отдельные Redis, bgutil-pot-provider 2.0.2 и youtube-upstream-service с `EGRESS_MODE=direct` и пустыми SOCKS-полями. CT 138 использует тот же default gateway; media-service работает с `MEDIA_EGRESS_MODE=direct`, поэтому resolver и googlevideo-байты выходят через один публичный IP. Внутренние `UPSTREAM_SERVICE_URL` указывают на `192.168.88.174:8081`. Старый локальный upstream и его bgutil остановлены и оставлены только под профилем `legacy-socks5`.
- Анонимные запросы через этот VPN-egress получали `LOGIN_REQUIRED` у всех проверенных видео, поэтому direct-режим не выдаётся за решение антибота. По решению пользователя добавлен `YOUTUBE_COOKIES_FILE`: `YouTubeCookieStore` читает Netscape-файл, принимает `#HttpOnly_`, фильтрует записи по host/path/secure/expires и объединяет account-cookies с NewPipe consent-cookie в `ReactorNettyDownloader`. yt-dlp получает тот же файл через `--cookies`. Секрет находится только в `/root/.secrets/youtube-cookies.txt` на CT 139 (`root:10001`, `640`, каталог `750`), монтируется read-only как `/run/secrets/youtube-cookies.txt` и не копируется в образ/репозиторий/журналы. Некорректный или отсутствующий заданный файл останавливает запуск явно.
- `YouTubeCookieStoreTest` и `ReactorNettyDownloaderTest` проверяют фильтрацию и объединение cookies. Полный `:backend:youtube-upstream-service:test` прошёл на Linux JDK 25. `docker compose config` для обоих стеков проходит. После включения cookies upstream возвращает `200` и форматы, MPD — `200`, реальный сегмент — `206` и 1024 байта; `upstream_egress_ok{mode="direct"}=1` у upstream и media. Исходники полностью синхронизированы на оба хоста без секретов, существующий `deploy/.env` сохранён с режимом `600`.
- Финальная методика замера: cold удаляет только `spanktube:streams:v3:direct:{videoId}` в Redis CT 139 и перезапускает media-service, после чего ждёт `upstream_egress_ok=1`; hot повторяет видео без очистки/перезапуска. Chrome остаётся запущенным, замер идёт по `performance.now()` от navigation: readiness — `readyState>=2`, фактический запуск — `paused=false` после trusted CDP Play. LibreTube на Galaxy S25 Ultra перезапускается каждый раз без очистки данных, замер — первый Media3 `PLAYING`. Телефон и серверы используют один default gateway и публичный egress, Android proxy не меняется. SpankTube использует предоставленные cookies, LibreTube — анонимный доступ, поэтому это не полностью одинаковые условия аутентификации.

| Видео | SpankTube cold ready / playing, с | SpankTube hot ready / playing, с | LibreTube cold `PLAYING`, с | LibreTube hot `PLAYING`, с |
|---|---:|---:|---:|---:|
| `3TYZjVzWazE` | 19,669 / 20,012 | 0,210 / 0,567 | 19,241 | 14,716 |
| `5JVXD1w6KYw` | 16,731 / 17,136 | 0,535 / 0,874 | 16,061 | 15,627 |
| `gTGdQ9mfJL4` | 7,263 / 8,010 | 0,435 / 0,753 | 15,911 | 15,192 |
| `6smfoDKverI` | 16,294 / 16,703 | 0,420 / 0,705 | 14,975 | 14,763 |

- Hot SpankTube примерно на 94–96% быстрее LibreTube по фактическому запуску. Cold не показывает общего преимущества: `gTGdQ9mfJL4` примерно на 50% быстрее, остальные три на 4–12% медленнее LibreTube. Прежние SOCKS-замеры из разделов 76/78 относятся к другому времени и условиям и не являются контрольной A/B-парой для утверждения об ускорении или замедлении direct-режима.
- Временный benchmark-пользователь, его прогресс и браузерная сессия удалены; `autoArchive=false`, новых заданий архивации нет. Proxy/Private DNS телефона не изменялись, DevTools forwarding удалён. Dangling-образы удалены, health-check всех backend-сервисов проходит.


## 86. Переход direct-egress на b4 и повторный benchmark

- По решению пользователя CT 138 и CT 139 используют default gateway b4 `192.168.88.175` вместо `192.168.88.162`. На b4 таблица 185 ведёт через `192.168.88.162`, но до настройки только собственные marked-пакеты b4 попадали в VPN: forwarded-трафик уходил через `192.168.88.1`. Добавлен и включён `spanktube-vpn-forward.service` с правилами `from 192.168.88.172` и `from 192.168.88.174` в таблицу 185. NAT не используется: подмена source-адреса на b4 вызывала reset, а исходные адреса хостов принимаются маршрутом корректно. Конфигурация b4 сохраняется при перезагрузке.
- После смены gateway удалены все ключи `spanktube:streams:v3:direct:*` (шесть записей) и перезапущены upstream/media, чтобы не переиспользовать signed URL прежнего egress. Оба `upstream_egress_ok` равны `1`; upstream видео `200`, MPD `200`, реальный сегмент `206`/1024 байта за 4,525 с. Публичный egress IP совпадает на CT 138, CT 139 и телефоне, поскольку b4 сам маршрутизируется через `.162`.
- Методика повторяет раздел 85: cold удаляет direct-ключ видео и перезапускает media с ожиданием probe `1`, hot не очищает кэши; SpankTube измеряет readiness и фактическое `paused=false` через CDP, LibreTube — Media3 `PLAYING` после перезапуска приложения. Телефон остаётся на `.162`: Android запрещает смену маршрута через ADB, но публичный egress совпадает с b4. Android proxy/Private DNS не изменяются. SpankTube использует cookies, LibreTube — анонимный доступ.

| Видео | SpankTube b4 cold ready / playing, с | SpankTube b4 hot ready / playing, с | LibreTube cold `PLAYING`, с | LibreTube hot `PLAYING`, с |
|---|---:|---:|---:|---:|
| `3TYZjVzWazE` | 16,017 / 16,314 | 0,487 / 0,808 | 16,157 | 15,241 |
| `5JVXD1w6KYw` | 12,326 / 14,713 | 0,527 / 0,814 | 15,032 | 14,983 |
| `gTGdQ9mfJL4` | 12,340 / 12,831 | 0,656 / 1,047 | 15,221 | 15,134 |
| `6smfoDKverI` | 8,195 / 8,865 | 0,532 / 0,822 | 13,116 | 15,300 |

- Против предыдущего direct-gateway из раздела 85 по фактическому cold playing: `3TYZjVzWazE` быстрее на 3,698 с (18,5%), `5JVXD1w6KYw` — на 2,423 с (14,1%), `gTGdQ9mfJL4` — медленнее на 4,821 с (60,2%), `6smfoDKverI` — быстрее на 7,838 с (46,9%). Hot b4 чуть медленнее предыдущего direct (0,808–1,047 с против 0,567–0,874 с), но всё ещё на 93–95% быстрее LibreTube.
- Против LibreTube b4 cold либо почти совпадает (`3TYZjVzWazE` +1,0%), либо быстрее: `5JVXD1w6KYw` на 2,1%, `gTGdQ9mfJL4` на 15,7%, `6smfoDKverI` на 32,4%. Это отдельные прогоны, а не статистически значимая серия; `5JVXD1w6KYw` cold потребовал два CDP Play, остальные b4 cold — один.
- Временный benchmark-пользователь, четыре записи его прогресса и браузерная сессия удалены; новых архивных заданий нет. b4-правило для телефона было проверено и удалено, маршрут телефона не изменился. DevTools forwarding отключён, dangling-образов нет, health-check всех сервисов проходит.


## 87. Симметричный NAT на b4 и устранение `EGRESS_UNAVAILABLE`

- После первоначального b4 benchmark в Firefox на мобильном воспроизведено `Shaka Error 1001` / `503 EGRESS_UNAVAILABLE` для `NWALHu-Ue3o`. Оба direct probe в этот момент были `0`, логи JVM показывали TLS connection errors и `RetryExhaustedException: Retries exhausted: 2/2`. Прямой маршрут CT 139 через `.162` при этом работал, а путь через b4 зависал после ClientHello.
- `tcpdump` на b4 показал повторные ClientHello без ServerHello. Conntrack на `.162` подтвердил, что transparent proxy `ss-redir` принимает соединение, но ответные пакеты идут напрямую клиенту, минуя b4. Это асимметричный путь: b4 видит только исходящий трафик. Первый вариант с source-routing без NAT из раздела 86 оказался недостаточным для устойчивого TLS.
- В `spanktube-vpn-forward.service` добавлен `POSTROUTING ! -d 192.168.88.0/24 -o eth0 -j MASQUERADE` вместе с прежними source-routing правилами CT 138/139. Теперь ответ возвращается на b4 и затем симметрично пересылается клиенту. Правило проверяется идемпотентно и сохраняется при перезапуске/перезагрузке; phone-specific правил нет, настройки Firefox/Android не менялись.
- После включения NAT оба probe вернулись к `1`. Для исходного `NWALHu-Ue3o`: upstream `200` с 22 форматами, MPD `200`, сегмент itag 137 `206` и 1024 байта за 0,552 с. Все backend health-check проходят. Повторный benchmark из раздела 86 после этой точечной сетевой правки не выполнялся.


## 88. Writable-копия cookies для yt-dlp

- При оценке канала `UC__pB6x64-xQM6lma8lBM6g` yt-dlp завершался с `OSError: [Errno 30] Read-only file system: '/run/secrets/youtube-cookies.txt'`. Причина не в доступе YouTube: `YoutubeDL.close()` вызывает `save_cookies()`, а смонтированный read-only secret не разрешает перезапись cookie jar.
- В `deploy/docker-compose.yml` (до переноса upstream на CT 138 — `deploy/upstream/docker-compose.yml`) исходный `/root/.secrets/youtube-cookies.txt` по-прежнему монтируется read-only. Теперь `entrypoint` перед запуском Java копирует его в `/tmp/youtube-cookies.txt`, ставит владельца/группу `10001:10001` и режим `600`; `YOUTUBE_COOKIES_FILE` указывает на эту копию. NewPipe читает её при старте, yt-dlp может перезаписывать cookie jar при завершении. Исходный секрет не изменяется, в репозиторий/образ/журналы содержимое не попадает. При пересоздании контейнера копия обновляется из исходного файла. Используется именно `entrypoint`, а не `command`: `command` лишь добавляет аргументы к существующему `java -jar` образа.
- Проверено, что копия имеет режим `600`, владельца `10001` и доступна на запись. Health-check и `upstream_egress_ok=1` сохраняются.
- Повторная оценка через реальный gateway завершилась `COMPLETED` (`33b8517f-20a1-4270-a4a8-66ade91d64cf`): 41 видео обработано, 39 новых, 1 уже в архиве, 1 пропущен возрастной блокировки `BKl4gZDWP34`, `totalBytes=2967124562`, `estimatedSeconds=944`, `error=null`. Ошибки записи cookies больше нет. Подтверждение скачивания не выполнялось, архивных заданий нет. Временный пользователь и его настройки удалены, dangling-образы очищены.


## 89. Диагностика таймаута чтения yt-dlp вместо ошибки `null`

- После исправления read-only cookies повторная оценка канала один раз завершилась `Unable to execute yt-dlp: null`. Прямой запрос `BKl4gZDWP34` показал ожидаемую возрастную блокировку, а параллельные контрольные запросы не воспроизвели `null`. В `YtDlpUpstreamProvider.execute` после завершения процесса stdout и stderr читались с жёстким таймаутом две секунды; `TimeoutException.getMessage()` в таком случае возвращает `null`, скрывая фактический этап сбоя.
- Добавлен `readOutput`: stdout и stderr ждут до десяти секунд (не больше общего timeout процесса), а при превышении явно сообщают `yt-dlp stdout/stderr read timed out after process exit`. `TimeoutException` обрабатывается внутри helper, поэтому общий catch больше не выдаёт пустую ошибку. `YtDlpUpstreamProviderTest` проверяет успешное чтение и название потока при таймауте; весь модульный test-suite и Docker build проходят на Linux JDK 25.
- Повторная оценка `UC__pB6x64-xQM6lma8lBM6g` через gateway завершилась `COMPLETED` (`a5141837-db52-4836-afec-569162204b10`): 41 обработано, 39 новых, 1 уже в архиве, 1 пропущен по возрастной блокировке, `totalBytes=2967124562`, `estimatedSeconds=944`, `error=null`. `BKl4gZDWP34` корректно классифицирован как возрастное ограничение, а не как неизвестная ошибка. Подтверждение скачивания не выполнялось, архивных заданий нет, временный пользователь удалён.


## 94. Консолидация upstream на CT 138 и отключение CT 139

- Поскольку upstream и остальные сервисы уже используют b4 `192.168.88.175`, отдельный CT 139 больше не нужен. `youtube-upstream-service` и `bgutil-pot-provider` запущены в основном `deploy/docker-compose.yml` на CT 138 вместе с общим Redis. Профиль `legacy-socks5` и отдельный `deploy/upstream/docker-compose.yml` удалены. Upstream использует `EGRESS_MODE=direct`, пустые SOCKS-поля и доступен только на `127.0.0.1:8081`; media/catalog/archive/gateway получают `UPSTREAM_SERVICE_URL=http://youtube-upstream-service:8081` и зависимости от локального upstream. Существующий `deploy/.env` не редактировался и сохраняет режим `600`; прежняя внешняя переменная `UPSTREAM_SERVICE_URL` больше не влияет на статические внутренние адреса и удалена из `.env.example`.
- Cookie-файл перемещён между остановленными rootfs CT 139 и CT 138 через `pct mount`/`pct unmount`, без чтения или вывода содержимого. Теперь источник находится только на CT 138: `/root/.secrets/youtube-cookies.txt`, `root:10001`, `640`, каталог `750`. Read-only mount и writable-копия `/tmp/youtube-cookies.txt` (`10001:10001`, `600`) сохранены. После ошибочного старта с maintenance mount блокировка `mounted` устранена чистой остановкой, unmount и повторным запуском; финальная конфигурация CT 138 не содержит maintenance lock.
- CT 139 остановлен штатно, `onboot=0`, rootfs и находящиеся на нём данные не удалялись. CT 138 работает с прежним default gateway b4; `spanktube-vpn-forward.service` активен, source-routing и симметричный MASQUERADE не менялись. После перезапуска direct stream cache не содержал записей; media-service дополнительно перезапущен для очистки in-memory state. Проверенные env всех клиентов указывают на локальный Docker DNS, `upstream_egress_ok{mode="direct"}=1` у upstream и media, health-check всех backend — `UP`.
- Полный backend `gradle build` завершился `BUILD SUCCESSFUL` (52 actionable tasks) на Linux JDK 25; production web build прошёл на Node 22. Через реальный web gateway с временным JWT выполнены все 22 playback-сценария из раздела 75: обычные DASH, архивные MKV, progressive remux и активный HLS. Каждый вернул манифест и фактический ненулевой сегмент/Range. `rFZHOHl-L8A` подтвердил HLS video+audio; `kJQP7kiw5Fk` — 6 субтитровых дорожек и человеческий `WEBVTT` размером 6194 байта. Контрольный `gop-v-00024.m4s` вернул `206`, два байта и `Content-Range: bytes 1048576-1048577/6137680`.
- `YcVx5lkmEQk` вернул `SponsorBlock=1` и `RYD=226`; `vDmjw-c_R2k` — `SponsorBlock=3` и `RYD=1549`. `wQzNzrJuL6E` отдельно вернул ожидаемый `502 ANTIBOT` с `Sign in to confirm your age` и actions, без маскировки. Канал Optie вернул четыре плейлиста, существующая библиотека `viewer` — те же четыре записи с HTTP `200`.
- Временный regression-пользователь создавался через gateway с `autoArchive=false`; архивные задания не запускались. Его пользователь, invite, настройки, история, членства и любые test-owned задания удалены, контрольные SQL-запросы возвращают ноль. ADB к телефону ответил `Connection refused`, поэтому визуальное воспроизведение Shaka в браузере не проверялось; подтверждены реальные authenticated HTTP-вызовы, MPD/HLS и байты сегментов.
