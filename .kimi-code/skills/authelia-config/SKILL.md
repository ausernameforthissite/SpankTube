---
name: authelia-config
description: Изменение конфигурации Authelia — правка шаблона, пересоздание секрета authelia-config, перезапуск, проверки; параметры сессии, домены access_control
type: prompt
whenToUse: При любых правках конфигурации Authelia (сессии, regulation, TOTP/WebAuthn, access_control, правила доменов) или диагностике её работы
---

# Конфигурация Authelia

Authelia 4.38 в namespace `code-server` кластера (образ `ghcr.io/authelia/authelia:4.38`). Конфиг поставляется секретом `authelia-config` (ключи `configuration.yml` и `users_database.yml`), смонтированным в под по `/config`.

## Правило редактирования — только через шаблон

Источник правды — **не** секрет и не файлы в поде, а шаблоны на kimi-code:

- `/root/k8s/code-server/authelia-configuration.yml.template` — основной конфиг, плейсхолдеры `${AUTHELIA_JWT}`, `${AUTHELIA_SESSION}`, `${AUTHELIA_STORAGE_KEY}` подставляются `envsubst` (значения в `/root/.secrets/code-server-setup.env`, 600 — секреты никогда не коммитить и не печатать без необходимости);
- `/root/k8s/code-server/users_database.yml.template` — плейсхолдер `${AUTHELIA_PASSWORD_HASH}`.

Цикл применения изменений:

```bash
source /root/.secrets/code-server-setup.env
export AUTHELIA_JWT AUTHELIA_SESSION AUTHELIA_STORAGE_KEY
envsubst '$AUTHELIA_JWT $AUTHELIA_SESSION $AUTHELIA_STORAGE_KEY' \
  < /root/k8s/code-server/authelia-configuration.yml.template \
  > /root/k8s/code-server/.rendered/configuration.yml
# пользователей НЕ перерисовывай из шаблона — они живут в секрете (см. authelia-invite)
kubectl -n code-server get secret authelia-config -o jsonpath='{.data.users_database\.yml}' | base64 -d \
  > /root/k8s/code-server/.rendered/users_database.yml
kubectl -n code-server delete secret authelia-config
kubectl -n code-server create secret generic authelia-config \
  --from-file=configuration.yml=/root/k8s/code-server/.rendered/configuration.yml \
  --from-file=users_database.yml=/root/k8s/code-server/.rendered/users_database.yml
kubectl -n code-server rollout restart deployment/authelia
kubectl -n code-server rollout status deployment/authelia --timeout=120s
```

Важно: не трогай пользователей через шаблон — иначе затрёшь выданные инвайты (шаблон содержит только `human`). Пользователей меняет только `authelia-invite.sh`.

## Проверки после применения

1. Логи: `kubectl -n code-server logs deploy/authelia` — должно быть `Startup complete`, без `level=fatal`/`level=error` (chown на read-only /config — безобидный шум entrypoint'а).
2. Портал: `curl -s -o /dev/null -w '%{http_code}' --resolve auth.fulldungeon.duckdns.org:443:192.168.88.64 https://auth.fulldungeon.duckdns.org/` → 200. Все защищённые хосты без сессии → 302 на `https://auth.fulldungeon.duckdns.org/?rd=…` (список доменов — в `access_control`).
3. Первый фактор: POST `/api/firstfactor` (как в разделе 121 design) → `{"status":"OK"}`.
4. OpenAPI пода (`/api/openapi.yml` внутри пода) — источник актуальных эндпоинтов API; названия меняются между минорными версиями.

## Ключевые текущие параметры

- Сессия: `session.cookies[]`: `inactivity: 8760h` (sliding, год бездействия), `expiration: 17520h` (потолок 2 года), `authelia_url` обязателен. Формат домена через `cookies[]` — с 4.38 ключ `domain` deprecated.
- Regulation: 3 попытки / 2 мин / бан 10 мин. TOTP issuer `fulldungeon`. `default_2fa_method: totp`.
- Storage: SQLite на PVC `authelia-storage` (SC `bulk`) — там живут TOTP-секреты и журнал, при потере PVC все пере-привязывают 2FA.
- Notifier: filesystem → `/tmp/notification.txt` (см. скилл `authelia-invite` про ретрансляцию кодов).
- `enableServiceLinks: false` в поде — **обязательно**: иначе k8s внедряет `AUTHELIA_PORT`, который env-парсер мапит в deprecated `port` и Authelia падает с конфликтом `server.address`.
- В поде code-server initContainer `vscode-product` пропатчивает `product.json` code-server (`extensionEnabledApiProposals` для open-remote-ssh) — при обновлении/пересборке этого initContainer'а убедись, что патч сохранился.

## Частые регрессии

- Authelia не стартует после правки — почти всегда YAML-синтаксис/deprecated-ключи; читай первый `level=fatal` в логах.
- Всё `*.fulldungeon.duckdns.org` недоступно — это kube-vip (хроника кластера, лечится `kubectl -n kube-vip rollout restart daemonset/kube-vip-ds`), к Authelia отношения не имеет.

## Синхронизация скилла

При изменении любого параметра выше, механизма поставки конфига, namespace, имён секретов/файлов — обнови этот скилл. Источники правды: разделы 121–123 (`docs/design/06-infrastructure-and-operations.md`, `05-security-data-and-api.md`), шаблоны в `/root/k8s/code-server/`, манифест `/root/k8s/code-server/authelia.yaml`.
