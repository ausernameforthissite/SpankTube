---
name: authelia-protect-service
description: Прячет новый сервис за Authelia 2FA — аннотации forward-auth на ingress, правило access_control, обновление портала входа, проверки
type: prompt
whenToUse: Когда нужно опубликовать сервис под новым поддоменом *.fulldungeon.duckdns.org за Authelia, либо снять такую защиту
---

# Публикация сервиса за Authelia 2FA

Паттерн: forward-auth у ingress-nginx → Authelia (namespace `code-server`, svc `authelia:9091`). Проверка сессии происходит на установку соединения; long-lived SSE/WebSocket не дёргают `auth-url` повторно. TLS — wildcard `main-wildcard-at-duckdns` (автокопия репликатором по лейблу namespace `copy-wild-cert: main` — у нового namespace должен быть этот лейбл).

## Чек-лист защиты нового домена

1. **Ingress сервиса** — аннотации:

```yaml
    nginx.ingress.kubernetes.io/auth-url: "http://authelia.code-server.svc.cluster.local:9091/api/verify"
    nginx.ingress.kubernetes.io/auth-signin: "https://auth.fulldungeon.duckdns.org/?rd=$scheme://$http_host$request_uri"
    nginx.ingress.kubernetes.io/auth-response-headers: "Remote-User,Remote-Groups,Remote-Name,Remote-Email"
```

   Для длинных соединений добавь `proxy-read-timeout: "3600"` / `proxy-send-timeout: "3600"`. Если сервис вне кластера — Service `type: ExternalName` (пример: `code-pve-external` → `192.168.88.100:8083` в `/root/k8s/code-server/code-pve.yaml`).

2. **Правило доступа** — добавь домен в `access_control.rules` шаблона `/root/k8s/code-server/authelia-configuration.yml.template` (`policy: two_factor`) и примени по циклу из скилла `authelia-config`.

3. **Портал входа** — сервис должен появиться на `https://fulldungeon.duckdns.org`: правишь ConfigMap `portal` (namespace `code-server`, `/root/k8s/code-server/portal.yaml`), `kubectl apply -f` и `rollout status deployment/portal`. Без этого пункта задача не считается выполненной.

4. **Проверки** (подставляя нужный хост):

```bash
curl -s -o /dev/null -w '%{http_code} %{redirect_url}\n' \
  --resolve <host>:443:192.168.88.64 https://<host>/
# ожидание: 302 -> https://auth.fulldungeon.duckdns.org/?rd=https://<host>/
```

   Затем залогинься через firstfactor-API (скилл `authelia-config`) и убедись, что с сессией сервис открывается.

## Снятие защиты

Обратный порядок: убрать три auth-аннотации из ingress, удалить домен из `access_control`, убрать/пометить пункт портала. Пример отката — SpankTube (раздел 123 `05-security-data-and-api.md`): его за Authelia не прячем, чтобы не смущать его пользователей собственным логином.

## Ограничения и заметки

- SpankTube и подобные сервисы со своей регистрацией пользователей за Authelia не прячем без явного решения пользователя (прецедент-откат — раздел 123 `05-security-data-and-api.md`).
- Доступ по группам: правила `code`/`code-pve` имеют `subjects: ["group:admins"]` — доступ только у группы `admins` (в ней `human`). Портал (`fulldungeon.duckdns.org`) — для всех аутентифицированных с 2FA. Добавление «админа»: `groups: ['admins']` в блоке пользователя секрета `authelia-config` + rollout restart (см. скилл `authelia-invite`).
- Authelia не умеет кастомные страницы: свой контент «на странице Authelia» не разместить, для этого и существует портал на базовом домене.
- Доступ к списку правил: `kubectl -n code-server get secret authelia-config -o jsonpath='{.data.configuration\.yml}' | base64 -d | grep -A20 access_control`.

## Синхронизация скилла

При изменении шаблона аннотаций, имени/namespace Authelia, механизма публикации (ExternalName, NodePort), структуры портала или списка защищаемых доменов — обнови этот скилл. Источники правды: разделы 121–123 design, манифесты `/root/k8s/code-server/` (особенно `code-pve.yaml` как эталон защищённой публикации и `portal.yaml`).
