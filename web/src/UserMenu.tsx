import type { UserSettings } from "./types";

export function UserMenu({ username, role, avatarUrl, settings, onChange, onNavigate, onLogout, onClose }: {
  username: string;
  role: string;
  avatarUrl: string;
  settings: UserSettings;
  onChange: (patch: Partial<UserSettings>) => void;
  onNavigate: (url: string) => void;
  onLogout: () => void;
  onClose: () => void;
}) {
  return (
    <section className="user-menu" aria-label="Меню пользователя">
      <div className="popup-header"><button className="popup-close" aria-label="Закрыть меню пользователя" onClick={onClose}>×</button></div>
      <div className="user-menu-profile">
        <img className="avatar user-avatar-large" src={avatarUrl} alt="" />
        <div><strong>{username}</strong><div className="muted">{role}</div></div>
      </div>
      <a className="link" href="/user-playlists" onClick={event => {
        event.preventDefault();
        onNavigate("/user-playlists");
      }}>Плейлисты</a>
      <a className="link" href="/library" onClick={event => {
        event.preventDefault();
        onNavigate("/library");
      }}>Библиотека</a>
      <button className="link" onClick={() => onNavigate("/settings")}>Настройки</button>
      {role === "ADMIN" && <button className="link" data-nav-url="/admin" onClick={() => onNavigate("/admin")}>Квоты</button>}
      <label className="switch-row"><span>Тема</span>
        <select value={settings.theme} onChange={event => onChange({ theme: event.target.value as UserSettings["theme"] })}>
          <option value="system">Системная</option><option value="light">Светлая</option><option value="dark">Тёмная</option>
        </select>
      </label>
      <div className="user-menu-footer"><button className="link" onClick={onLogout}>Выйти</button></div>
    </section>
  );
}
