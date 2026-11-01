import type { UserSettings } from "./types";

export function SettingsMenu({ settings, onChange, onClose }: {
  settings: UserSettings;
  onChange: (patch: Partial<UserSettings>) => void;
  onClose: () => void;
}) {
  return (
    <section className="settings-options" aria-label="Настройки пользователя">
      <button className="popup-close" aria-label="Закрыть настройки" onClick={onClose}>×</button>
      <label className="switch-row">
        <span>Воспроизводить видео автоматически</span>
        <input type="checkbox" role="switch" checked={settings.autoplay}
          onChange={event => onChange({ autoplay: event.target.checked })} />
      </label>
      <label className="switch-row">
        <span>Скрывать кодеки в библиотеке</span>
        <input type="checkbox" role="switch" checked={settings.hideTechnicalDetails}
          onChange={event => onChange({ hideTechnicalDetails: event.target.checked })} />
      </label>
    </section>
  );
}
