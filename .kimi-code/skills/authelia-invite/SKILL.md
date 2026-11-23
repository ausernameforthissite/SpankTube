---
name: authelia-invite
description: Инвайты и управление пользователями Authelia — создание пользователя с временным паролем, удаление, ретрансляция одноразовых кодов
type: prompt
whenToUse: Когда нужно выдать или отозвать доступ к сервисам за Authelia (code, code-pve, портал), создать пользователя Authelia или помочь приглашённому пройти регистрацию TOTP/смену пароля
---

# Инвайты и пользователи Authelia

Authelia 4.38, file backend, namespace `code-server` кластера. Штатных инвайтов нет — весь жизненный цикл закрыт скриптом **`/root/k8s/code-server/authelia-invite.sh`** (на kimi-code, исполняемый). Команды:

```bash
/root/k8s/code-server/authelia-invite.sh add <username> [display name]   # инвайт
/root/k8s/code-server/authelia-invite.sh code                            # последний одноразовый код
/root/k8s/code-server/authelia-invite.sh remove <username>               # отзыв доступа
```

## Как работает add

За один вызов: генерирует временный пароль (16 символов), хеширует argon2 через `docker run docker.io/authelia/authelia:4.38 authelia crypto hash generate argon2` (образ уже в кэше kimi-code), дописывает блок пользователя в `users_database.yml` секрета `authelia-config`, пересоздаёт секрет и делает `rollout restart` Authelia с ожиданием готовности. Печатает логин/пароль и инструкцию.

## Обязательный ручной шаг — ретрансляция кодов

Notifier у Authelia — filesystem: «письма» с одноразовыми кодами пишутся в `/tmp/notification.txt` **внутри пода** и никуда не отправляются. Когда приглашённый проходит регистрацию TOTP или меняет пароль, Authelia покажет диалог «код отправлен на email» — код берётся так:

```bash
/root/k8s/code-server/authelia-invite.sh code   # печатает последний код вида NTNDJBGF
```

Код живёт 5 минут (`identity_validation.elevated_session.code_lifespan`). Если протух — приглашённый закрывает диалог и открывает заново, коды берутся свежие. Подскажи это приглашённому заранее.

## Правила

1. Временный пароль отдавай только приглашённому (лично/в доверенный канал), в логи и репозиторий он не попадает (скрипт его не сохраняет — запомни из вывода при первом запуске; потерялся → `remove` + `add` заново).
2. После `add` проверь, что Authelia перезапустилась (`kubectl -n code-server rollout status deployment/authelia`), и при желании проверь первый фактор через API (см. скилл `authelia-config`, раздел проверки).
3. Удаление (`remove`) немедленно отрубает доступ: сессии пользователя аннулируются при перезапуске.
4. Имя пользователя: `^[a-z0-9_-]+$`. Почта фиктивная (`<user>@localhost`) — реальной почтовой инфраструктуры нет.
5. Пользователь `human` — основной административный, состоит в группе `admins` (см. скилл `authelia-protect-service`): доступ к code/code-pve только у группы `admins`, портал — у всех. Не удалять и не менять без явной команды.
6. Группы: используется только `admins`. Новый «админ» = добавить `groups: ['admins']` в его блок в секрете `authelia-config` (fetch/правка/push по аналогии с `remove`), затем rollout restart.
7. Regulation — про баны: 3 неудачных 1FA подряд = бан пользователя на 10 минут. Автотесты с неверным паролем быстро банят тестового юзера — при тестировании не ошибайся паролем, иначе жди бан. Реальный приглашённый при обычном входе в бан не попадает.
8. Одноразовые коды из notifier'а — 8 символов A-Z0-9 (включая 0/1/8/9); команда `code` извлекает их по разделителю письма, а не по алфавиту.

## Синхронизация скилла

При изменениях в Authelia, затрагивающих пользователей или notifier (смена backend аутентификации с file, подключение SMTP, смена `code_lifespan`, переезд скрипта) — сразу обнови этот скилл. Источники правды: раздел 123 `docs/design/05-security-data-and-api.md`, `/root/k8s/code-server/authelia-invite.sh`, `/root/k8s/code-server/users_database.yml.template`.
