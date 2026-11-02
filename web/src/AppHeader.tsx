import type { FormEvent } from "react";

export type HeaderSearchType = "all" | "videos" | "channels" | "playlists" | "archive";

export function AppHeader({ searchQuery, searchType, onQueryChange, onTypeChange, onSearch,
  onNavigate, onQueue, onQueuePage, onUser, avatarUrl, queueButtonRef }: {
  searchQuery: string;
  searchType: HeaderSearchType;
  onQueryChange: (value: string) => void;
  onTypeChange: (value: HeaderSearchType) => void;
  onSearch: (event: FormEvent) => void;
  onNavigate: (url: string) => void;
  onQueue: (anchor: HTMLElement) => void;
  onQueuePage: () => void;
  onUser: (anchor: HTMLElement) => void;
  avatarUrl: string;
  queueButtonRef: React.RefObject<HTMLDivElement | null>;
}) {
  return (
    <header className="topline">
      <a className="brand" href="/" data-nav-url="/" title="SpankTube — Просмотр, поиск и архив YouTube"
        onClick={event => { event.preventDefault(); onNavigate("/"); }}>SpankTube</a>
      <form className="header-search" onSubmit={onSearch}>
        <input value={searchQuery} onChange={event => onQueryChange(event.target.value)}
          placeholder="Поиск видео, каналов, плейлистов или videoId" aria-label="Поиск" />
        <select value={searchType} aria-label="Режим поиска"
          onChange={event => onTypeChange(event.target.value as HeaderSearchType)}>
          <option value="all">Весь YouTube</option>
          <option value="videos">Видео</option>
          <option value="channels">Каналы</option>
          <option value="playlists">Плейлисты</option>
          <option value="archive">Архив</option>
        </select>
        <button type="submit">Найти</button>
      </form>
      <div className="header-actions">
        <div className="split-button" ref={queueButtonRef}>
          <button className="secondary queue-icon" aria-label="Очередь" title="Очередь" data-nav-url="/jobs" onClick={onQueuePage}>
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01" /></svg>
          </button>
          <button className="secondary split-arrow" aria-label="Показать очередь" onClick={event => onQueue(event.currentTarget)}>▼</button>
        </div>
        <button className="user-avatar-button" aria-label="Меню пользователя" title="Меню пользователя"
          onClick={event => onUser(event.currentTarget)}>
          <img className="avatar user-avatar" src={avatarUrl} alt="Аватар пользователя" />
        </button>
      </div>
    </header>
  );
}
