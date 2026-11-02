import type { UserSettings } from "./types";

export function SettingsMenu({ settings, onChange, onClose, avatarUrl, onAvatarUpload, isAdmin, invite, onGenerateInvite }: {
  settings: UserSettings;
  onChange: (patch: Partial<UserSettings>) => void;
  onClose: () => void;
  avatarUrl: string;
  onAvatarUpload: (file: File) => void;
  isAdmin: boolean;
  invite: { code: string; expiresAt: string; usedBy: string | null } | null;
  onGenerateInvite: () => void;
}) {
  return (
    <section className="settings-options" aria-label="Настройки пользователя">
      <div className="popup-header"><button className="popup-close" aria-label="Закрыть настройки" onClick={onClose}>×</button></div>
      <label className="switch-row">
        <span>Тема</span>
        <select value={settings.theme} onChange={event => onChange({ theme: event.target.value as UserSettings["theme"] })}>
          <option value="system">Системная</option><option value="light">Светлая</option><option value="dark">Тёмная</option>
        </select>
      </label>
      <label className="switch-row">
        <span>Воспроизводить видео автоматически</span>
        <input type="checkbox" role="switch" checked={settings.autoplay}
          onChange={event => onChange({ autoplay: event.target.checked })} />
      </label>
      <label className="switch-row">
        <span>Всегда выбирать лучшее качество</span>
        <input type="checkbox" role="switch" checked={settings.preferBestQuality}
          onChange={event => onChange({ preferBestQuality: event.target.checked })} />
      </label>
      <label className="switch-row">
        <span>Скрывать кодеки в библиотеке</span>
        <input type="checkbox" role="switch" checked={settings.hideTechnicalDetails}
          onChange={event => onChange({ hideTechnicalDetails: event.target.checked })} />
      </label>
      <div className="settings-avatar">
        <img className="avatar" src={avatarUrl} alt="Аватар" />
        <label>PNG, JPEG или WEBP до 256×256, 200 КиБ
          <input type="file" accept="image/png,image/jpeg,image/webp" onChange={event => {
            const file = event.target.files?.[0];
            if (file) onAvatarUpload(file);
            event.currentTarget.value = "";
          }} />
        </label>
      </div>
      {isAdmin && <div className="settings-invites">
        <button className="secondary" onClick={onGenerateInvite}>Создать инвайт</button>
        {invite && <div><span className="muted">Инвайт действует до {new Date(invite.expiresAt).toLocaleString()}</span><code>{invite.code}</code></div>}
      </div>}
    </section>
  );
}
