import { useEffect, useMemo, useState } from "react";
import { CardContextMenu } from "./PlaylistMenus";
import { LazyThumbnail } from "./LazyThumbnail";
import type { ApiFetch, LibraryItem, LibraryPlaylist, LibraryResponse, WatchProgressMap } from "./types";

function formatDuration(seconds: number): string {
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  const rest = seconds % 60;
  return hours > 0 ? `${hours}:${String(minutes).padStart(2, "0")}:${String(rest).padStart(2, "0")}`
    : `${minutes}:${String(rest).padStart(2, "0")}`;
}

function formatMB(bytes: number): string {
  return `${(bytes / 1_000_000).toFixed(bytes >= 100_000_000 ? 0 : 1)} МБ`;
}

function formatGB(bytes: number): string {
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} ГиБ`;
}

type LibraryViewProps = {
  library: LibraryResponse;
  query: URLSearchParams;
  role: string;
  navigate: (url: string) => void;
  apiFetch: ApiFetch;
  sortMode: string;
  onSortModeChange: (mode: string) => void;
  onDownload: (item: LibraryItem) => void;
  onRemove: (item: LibraryItem) => Promise<boolean>;
  hideTechnicalDetails: boolean;
  progressMap: WatchProgressMap;
};

export function LibraryView({ library, query, role, navigate, apiFetch, sortMode, onSortModeChange,
  onDownload, onRemove, hideTechnicalDetails, progressMap }: LibraryViewProps) {
  const scope = query.get("scope") || "own";
  const channelId = query.get("channel");
  const playlistId = query.get("playlist");
  const videoId = query.get("video");
  const [playlistItems, setPlaylistItems] = useState<LibraryItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [menuItem, setMenuItem] = useState<LibraryItem | null>(null);

  useEffect(() => setMenuItem(null), [channelId, playlistId, videoId, scope]);

  useEffect(() => {
    if (!playlistId || playlistId === "none") {
      setPlaylistItems([]);
      return;
    }
    let cancelled = false;
    setLoading(true);
    apiFetch(`/api/v1/library/playlists/${encodeURIComponent(playlistId)}/videos?scope=${encodeURIComponent(scope)}`)
      .then(response => response.json())
      .then(items => {
        if (!cancelled) setPlaylistItems(items as LibraryItem[]);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => { cancelled = true; };
  }, [apiFetch, playlistId, scope]);

  const openPath = (channel: string | null, playlist: string | null, video: string | null, nextScope = scope) => {
    const params = new URLSearchParams();
    if (nextScope !== "own") params.set("scope", nextScope);
    if (channel) params.set("channel", channel);
    if (playlist) params.set("playlist", playlist);
    if (video) params.set("video", video);
    const suffix = params.toString();
    navigate(`/library${suffix ? `?${suffix}` : ""}`);
  };

  const removeItem = async (item: LibraryItem) => {
    if (await onRemove(item)) {
      setPlaylistItems(current => current.filter(candidate => candidate.videoId !== item.videoId));
    }
  };

  const selectedChannel = channelId ? library.channels.find(group => group.channel.channelId === channelId) : null;
  const items = playlistId && playlistId !== "none" ? playlistItems : selectedChannel?.videos || [];
  const visibleItems = videoId ? items.filter(item => item.videoId === videoId) : items;

  const renderVideos = (videos: LibraryItem[]) => <div className="library-list">
    {videos.map((item, index) => <div className="library-row" key={item.videoId}>
      <div className="library-thumbnail-frame">
        <LazyThumbnail className="library-thumbnail" src={item.thumbnailUrl} alt="" priority={index < 4} />
        {progressMap[item.videoId] && <progress className="watch-progress" max={100}
          value={Math.max(0, Math.min(100, progressMap[item.videoId].watchPercent))}
          aria-label={`Прогресс просмотра: ${Math.round(progressMap[item.videoId].watchPercent)}%`} />}
      </div>
      <div className="library-info">
        <a className="queue-video-link" href={`/watch?v=${encodeURIComponent(item.videoId)}`}
          onClick={event => { event.preventDefault(); navigate(`/watch?v=${encodeURIComponent(item.videoId)}`); }}>
          <strong>{item.title}</strong>
        </a>
        <div className="muted">{formatDuration(item.durationSeconds)} · {formatMB(item.sizeBytes)}</div>
        {!hideTechnicalDetails && <div className="muted">{item.height ? `${item.height}p` : "—"} · {item.videoCodec || "—"} · {item.audioCodec || "—"}</div>}
        <div className="filename">{item.filename}</div>
      </div>
      <div className="row-actions">
        <button className="icon-button row-icon-action" aria-label="Скачать MKV" title="Скачать MKV"
          onClick={() => onDownload(item)}>
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3v12m-5-5 5 5 5-5M4 16v4h16v-4" /></svg>
        </button>
        <button className="icon-button danger row-icon-action" aria-label="Удалить" title="Удалить"
          onClick={() => void removeItem(item)}>
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7h16M9 7V4h6v3m-9 0 1 14h10l1-14M10 11v6m4-6v6" /></svg>
        </button>
        <button className="card-menu-button row-menu-button" aria-label="Действия с видео" title="Действия с видео"
          onClick={() => setMenuItem(current => current?.videoId === item.videoId ? null : item)}>⋮</button>
        {menuItem?.videoId === item.videoId && <>
          <button className="library-menu-backdrop" aria-label="Закрыть меню" onClick={() => setMenuItem(null)} />
          <div className="library-row-menu">
            <CardContextMenu onDownloadMkv={() => { setMenuItem(null); onDownload(item); }}
              onRemove={() => { setMenuItem(null); void removeItem(item); }} />
          </div>
        </>}
      </div>
    </div>)}
  </div>;

  if (selectedChannel && playlistId) {
    const playlistTitle = playlistId === "none" ? "Видео без плейлиста"
      : selectedChannel.playlists.find(playlist => playlist.playlistId === playlistId)?.title || playlistId;
    return <section className="panel metadata">
      <div className="library-breadcrumb">
        <button className="secondary" onClick={() => openPath(channelId, null, null)}>← {selectedChannel.channel.title}</button>
        <span className="muted"> / {playlistTitle}</span>
      </div>
      <h2>{playlistTitle}</h2>
      <SortSelect value={sortMode} onChange={onSortModeChange} />
      {loading ? <p className="muted">Загрузка…</p> : renderVideos(visibleItems)}
    </section>;
  }

  if (selectedChannel) {
    return <section className="panel metadata">
      <div className="library-breadcrumb">
        <button className="secondary" onClick={() => openPath(null, null, null)}>← Каналы</button>
        <span className="muted"> / {selectedChannel.channel.title}</span>
      </div>
      <div className="channel-line">
        <img className="avatar" src={selectedChannel.channel.avatarUrl} alt="" />
        <h2><a className="channel-title-link" href={selectedChannel.channel.url}
          onClick={event => { event.preventDefault(); navigate(selectedChannel.channel.url); }}>
          {selectedChannel.channel.title}
        </a></h2>
      </div>
      <div className="library-collection-grid">
        {selectedChannel.playlists.map(playlist => <button className="library-collection-card library-playlist-card" key={playlist.playlistId}
          data-nav-url={`/library?scope=${scope}&channel=${channelId}&playlist=${playlist.playlistId}`}
          onClick={() => openPath(channelId, playlist.playlistId, null)}>
          <PlaylistArtwork playlist={playlist} videos={selectedChannel.videos} />
          <strong>{playlist.title}</strong><span className="muted">{playlist.videoCount} видео</span>
        </button>)}
        <button className="library-collection-card library-playlist-card" data-nav-url={`/library?scope=${scope}&channel=${channelId}&playlist=none`}
          onClick={() => openPath(channelId, "none", null)}>
          <RandomPlaylistArtwork videos={selectedChannel.videos} />
          <strong>Видео без плейлиста</strong><span className="muted">{selectedChannel.videos.length} видео</span>
        </button>
      </div>
    </section>;
  }

  return <section>
    {scope === "own" && <div className="panel stats">Использовано {formatGB(library.usedBytes)} из {formatGB(library.quotaBytes)} · LRU при {library.lruThresholdPercent}%</div>}
    {role === "ADMIN" && <div className="channel-tabs" role="tablist" aria-label="Область библиотеки">
      <button role="tab" className={scope === "own" ? "channel-tab active" : "channel-tab"}
        aria-selected={scope === "own"} onClick={() => openPath(null, null, null, "own")}>Мой архив</button>
      <button role="tab" className={scope === "all" ? "channel-tab active" : "channel-tab"}
        aria-selected={scope === "all"} onClick={() => openPath(null, null, null, "all")}>Общий архив</button>
    </div>}
    <div className="library-collection-grid">
      {library.channels.map(group => <button className="library-collection-card" key={group.channel.channelId}
        data-nav-url={`/library?scope=${scope}&channel=${group.channel.channelId}`}
        onClick={() => openPath(group.channel.channelId, null, null)}>
        <img className="avatar" src={group.channel.avatarUrl} alt="" />
        <strong>{group.channel.title}</strong>
        <span className="muted">{group.videos.length} видео · {group.playlists.length} плейлистов</span>
      </button>)}
    </div>
  </section>;
}

function PlaylistArtwork({ playlist, videos }: { playlist: LibraryPlaylist; videos: LibraryItem[] }) {
  const [failed, setFailed] = useState(false);

  useEffect(() => setFailed(false), [playlist.playlistId, playlist.thumbnailUrl]);

  if (playlist.thumbnailUrl && !failed) {
    return <LazyThumbnail className="library-playlist-thumbnail" src={playlist.thumbnailUrl}
      alt={`Превью плейлиста ${playlist.title}`} onError={() => setFailed(true)} />;
  }

  const previews = playlist.videoIds.map(id => videos.find(video => video.videoId === id))
    .filter((video): video is LibraryItem => video !== undefined).slice(0, 4);
  return <div className="library-playlist-collage" aria-hidden="true">
    {Array.from({ length: 4 }, (_, index) => {
      const video = previews[index];
      return video ? <LazyThumbnail key={video.videoId} src={video.thumbnailUrl} alt="" />
        : <span key={`empty-${index}`} />;
    })}
  </div>;
}

function RandomPlaylistArtwork({ videos }: { videos: LibraryItem[] }) {
  const videoIds = useMemo(() => videos.map(video => video.videoId).sort(() => Math.random() - 0.5), [videos]);
  return <PlaylistArtwork playlist={{ playlistId: "none", title: "Видео без плейлиста",
    videoCount: videos.length, thumbnailUrl: "", videoIds }} videos={videos} />;
}

function SortSelect({ value, onChange }: { value: string; onChange: (mode: string) => void }) {
  return <label className="panel">Сортировка: <select value={value} onChange={event => onChange(event.target.value)}>
    <option value="size:desc">Сначала большие</option>
    <option value="size:asc">Сначала маленькие</option>
    <option value="duration:desc">По длительности</option>
    <option value="title:asc">По названию</option>
  </select></label>;
}
