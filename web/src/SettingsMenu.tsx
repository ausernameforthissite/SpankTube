import { useState } from "react";
import { ArchiveQualitySelect } from "./ArchiveQualitySelect";
import type { UserSettings } from "./types";

type SettingsView = "main" | "profile" | "archive";

function SettingsNavigation({ label, onOpen }: { label: string; onOpen: () => void }) {
  return <button type="button" className="link settings-navigation" onClick={onOpen}>
    <span>{label}</span>
    <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 18 6-6-6-6" /></svg>
  </button>;
}

export function SettingsMenu({ settings, onChange, onClose, onBack, onNavigate, avatarUrl, onAvatarUpload, isAdmin, invite, onGenerateInvite }: {
  settings: UserSettings;
  onChange: (patch: Partial<UserSettings>) => void;
  onClose: () => void;
  onBack: () => void;
  onNavigate: (url: string) => void;
  avatarUrl: string;
  onAvatarUpload: (file: File) => void;
  isAdmin: boolean;
  invite: { code: string; expiresAt: string; usedBy: string | null } | null;
  onGenerateInvite: () => void;
}) {
  const [view, setView] = useState<SettingsView>("main");

  return (
    <section className="settings-options" aria-label={view === "profile" ? "Настройки профиля" : "Настройки пользователя"}>
      <div className="popup-header"><button className="popup-close" aria-label="Закрыть настройки" onClick={onClose}>×</button></div>
      {view === "main" && <>
        <button className="link settings-back-link" onClick={onBack}>← Меню пользователя</button>
        <SettingsNavigation label="Профиль" onOpen={() => setView("profile")} />
        <SettingsNavigation label="Архивация" onOpen={() => setView("archive")} />
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
          <span>Уведомления об обращениях</span>
          <input type="checkbox" role="switch" checked={settings.feedbackNotifications}
            aria-label="Уведомления об обращениях"
            onChange={event => onChange({ feedbackNotifications: event.target.checked })} />
        </label>
        <label className="switch-row">
          <span>Воспроизводить видео в лучшем качестве</span>
          <input type="checkbox" role="switch" checked={settings.preferBestQuality}
            onChange={event => onChange({ preferBestQuality: event.target.checked })} />
        </label>
        {isAdmin && <div className="settings-invites">
          <button className="secondary" onClick={onGenerateInvite}>Создать инвайт</button>
          {invite && <div><span className="muted">Инвайт действует до {new Date(invite.expiresAt).toLocaleString()}</span><code>{invite.code}</code></div>}
        </div>}
      </>}

      {view !== "main" && <button className="link settings-back-link" onClick={() => setView("main")}>← Настройки</button>}
      {view === "profile" && <>
        <h3>Профиль</h3>
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
        <a className="link settings-profile-link" href="/feedback" onClick={event => {
          event.preventDefault();
          onNavigate("/feedback");
        }}>Обратная связь</a>
      </>}

      {view === "archive" && <>
        <h3>Архивация</h3>
        <label className="switch-row">
          <span>Автоматически архивировать видео</span>
          <input type="checkbox" role="switch" checked={settings.autoArchive}
            onChange={event => onChange({ autoArchive: event.target.checked })} />
        </label>
        <label className="settings-quality">
          <span>Целевое качество видео</span>
          <ArchiveQualitySelect value={settings.archiveTargetHeight}
            onChange={archiveTargetHeight => onChange({ archiveTargetHeight })}
            ariaLabel="Целевое качество видео" />
          <small className="muted">Выбирается ближайшее доступное качество для одиночных видео и каналов.</small>
        </label>
        <label className="switch-row">
          <span>Скрывать кодеки в библиотеке</span>
          <input type="checkbox" role="switch" checked={settings.hideTechnicalDetails}
            onChange={event => onChange({ hideTechnicalDetails: event.target.checked })} />
        </label>
      </>}
    </section>
  );
}
