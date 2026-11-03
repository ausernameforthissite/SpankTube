import type { UserPlaylist } from "./types";

export function CardContextMenu({ onAdd, onArchive, onDownload }: {
  onAdd: () => void;
  onArchive: () => void;
  onDownload: () => void;
}) {
  return <div className="card-context-menu" role="menu">
    <button role="menuitem" onClick={onAdd}>Добавить в плейлист</button>
    <button role="menuitem" onClick={onArchive}>Архивировать</button>
    <button role="menuitem" onClick={onDownload}>Скачать</button>
  </div>;
}

export function PlaylistPicker({ playlists, query, onQuery, onSelect, onCreate, onClose }: {
  playlists: UserPlaylist[];
  query: string;
  onQuery: (value: string) => void;
  onSelect: (playlist: UserPlaylist) => void;
  onCreate: () => void;
  onClose: () => void;
}) {
  const normalized = query.trim().toLocaleLowerCase();
  const visible = playlists.filter(playlist => playlist.title.toLocaleLowerCase().includes(normalized));
  return <section className="playlist-picker" aria-label="Выбор плейлиста">
    <div className="popup-header"><strong>Выберите плейлист</strong>
      <button className="popup-close" aria-label="Закрыть выбор плейлиста" onClick={onClose}>×</button>
    </div>
    <input type="search" value={query} onChange={event => onQuery(event.target.value)}
      placeholder="Поиск плейлистов" aria-label="Поиск плейлистов" autoFocus />
    <div className="playlist-picker-list">
      {visible.map(playlist => <button className="playlist-picker-item" key={playlist.playlistId} onClick={() => onSelect(playlist)}>
        <span><strong>{playlist.title}</strong><span className="muted">Ограниченный доступ · {playlist.videoCount} видео</span></span>
        <span aria-hidden="true">▣</span>
      </button>)}
      {visible.length === 0 && <p className="muted">Плейлисты не найдены</p>}
    </div>
    <button className="secondary playlist-create-button" onClick={onCreate}>＋ Новый</button>
  </section>;
}

export function PlaylistCreateDialog({ title, onTitle, onCreate, onCancel, creating }: {
  title: string;
  onTitle: (value: string) => void;
  onCreate: () => void;
  onCancel: () => void;
  creating: boolean;
}) {
  return <section className="playlist-create-dialog" aria-label="Новый плейлист">
    <div className="popup-header"><strong>Новый</strong>
      <button className="popup-close" aria-label="Отменить создание плейлиста" onClick={onCancel}>×</button>
    </div>
    <label>Название
      <input value={title} onChange={event => onTitle(event.target.value)} maxLength={200}
        placeholder="Укажите название" aria-label="Название плейлиста" autoFocus />
    </label>
    <label>Доступ
      <select value="restricted" disabled aria-label="Доступ плейлиста"><option value="restricted">Ограниченный доступ</option></select>
    </label>
    <div className="playlist-create-actions">
      <button className="secondary" onClick={onCancel}>Отмена</button>
      <button disabled={creating || !title.trim()} onClick={onCreate}>{creating ? "Создание…" : "Создать"}</button>
    </div>
  </section>;
}
