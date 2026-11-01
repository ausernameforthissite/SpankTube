import { FormEvent, useCallback, useEffect, useRef, useState } from "react";
import shaka from "shaka-player/dist/shaka-player.compiled.js";
import type { components } from "./api/schema";

type TokenPair = components["schemas"]["TokenPair"];
type BaseVideo = components["schemas"]["Video"];
type Session = TokenPair & { username: string; accessExpiresAt: number };
type Channel = {
  channelId: string;
  title: string;
  handle: string | null;
  customPath: string | null;
  url: string;
  avatarUrl: string;
  subscriberCount: number | null;
  nextCursor: string | null;
};
type Video = BaseVideo & {
  channelAvatarUrl: string | null;
  channelUrl: string;
  thumbnailUrl: string | null;
  sponsorBlock?: { startSeconds: number; endSeconds: number; category: string }[];
  dislikeCountEstimate?: number;
};
type SearchItem = {
  kind: "video" | "channel" | "playlist";
  id: string;
  title: string;
  url: string;
  thumbnailUrl: string;
  channelName: string | null;
  durationSeconds: number | null;
  videoCount: number | null;
};
type SearchResults = { items: SearchItem[]; nextCursor: string | null };
type ChannelPage = Channel & { videos: SearchItem[]; playlists: SearchItem[] };
type Playlist = {
  playlistId: string;
  title: string;
  channel: Channel;
  items: SearchItem[];
  nextCursor: string | null;
  thumbnailUrl: string;
};
type LibraryItem = {
  videoId: string;
  title: string;
  durationSeconds: number;
  sizeBytes: number;
  sizeMB: number;
  downloadUrl: string;
  lastAccessedAt: string;
};
type LibraryChannel = { channel: Channel; videos: LibraryItem[]; totalBytes: number };
type LibraryResponse = { channels: LibraryChannel[]; usedBytes: number; quotaBytes: number; lruThresholdPercent: number };
type ArchiveJob = {
  id: string;
  kind: "VIDEO" | "PLAYLIST" | "CHANNEL";
  targetId: string;
  ownerUsername: string;
  ownerRole: string;
  priority: number;
  status: string;
  totalCount: number;
  completedCount: number;
  failedCount: number;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  nextAttemptAt: string | null;
  error: string | null;
};
type UserQuota = {
  username: string;
  role: string;
  quotaBytes: number | null;
  usedBytes: number;
  effectiveQuotaBytes: number;
  lruThresholdPercent: number;
};

const STORAGE_KEY = "spanktube.session";
const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || "").replace(/\/$/, "");
const MEDIA_BASE_URL = (import.meta.env.VITE_MEDIA_BASE_URL || API_BASE_URL).replace(/\/$/, "");

class RequestError extends Error {
  constructor(message: string, readonly actions: string[] = []) {
    super(message);
  }
}

function loadSession(): Session | null {
  const stored = sessionStorage.getItem(STORAGE_KEY);
  if (!stored) return null;
  try {
    return JSON.parse(stored) as Session;
  } catch {
    sessionStorage.removeItem(STORAGE_KEY);
    return null;
  }
}

async function responseError(response: Response): Promise<RequestError> {
  try {
    const body = await response.json();
    return new RequestError(body.message || `HTTP ${response.status}`, body.actions || []);
  } catch {
    return new RequestError(`HTTP ${response.status}`);
  }
}

function formatDuration(seconds: number | null | undefined): string {
  if (seconds == null) return "—";
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  const rest = seconds % 60;
  return hours > 0
    ? `${hours}:${String(minutes).padStart(2, "0")}:${String(rest).padStart(2, "0")}`
    : `${minutes}:${String(rest).padStart(2, "0")}`;
}

function formatMB(bytes: number): string {
  return `${(bytes / 1_000_000).toFixed(bytes >= 100_000_000 ? 0 : 1)} МБ`;
}

function formatGB(bytes: number): string {
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} ГиБ`;
}

function readLocation() {
  return { path: window.location.pathname, query: new URLSearchParams(window.location.search) };
}

export default function App() {
  const [session, setSession] = useState<Session | null>(loadSession);
  const sessionRef = useRef(session);
  const refreshRef = useRef<Promise<string> | null>(null);
  const [username, setUsername] = useState("viewer");
  const [password, setPassword] = useState("");
  const [location, setLocation] = useState(readLocation);
  const [videoId, setVideoId] = useState("");
  const [searchQuery, setSearchQuery] = useState("");
  const [searchType, setSearchType] = useState("videos");
  const [video, setVideo] = useState<Video | null>(null);
  const [channel, setChannel] = useState<ChannelPage | null>(null);
  const [playlist, setPlaylist] = useState<Playlist | null>(null);
  const [results, setResults] = useState<SearchItem[]>([]);
  const [searchNextCursor, setSearchNextCursor] = useState<string | null>(null);
  const [library, setLibrary] = useState<LibraryResponse | null>(null);
  const [jobs, setJobs] = useState<ArchiveJob[]>([]);
  const [users, setUsers] = useState<UserQuota[]>([]);
  const [quotaDrafts, setQuotaDrafts] = useState<Record<string, string>>({});
  const [sortMode, setSortMode] = useState("size:desc");
  const [videoElement, setVideoElement] = useState<HTMLVideoElement | null>(null);
  const [playerReady, setPlayerReady] = useState(false);
  const [playbackStatus, setPlaybackStatus] = useState("Плеер не запущен");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [actions, setActions] = useState<string[]>([]);

  const saveSession = useCallback((next: Session | null) => {
    sessionRef.current = next;
    setSession(next);
    if (next) sessionStorage.setItem(STORAGE_KEY, JSON.stringify(next));
    else sessionStorage.removeItem(STORAGE_KEY);
  }, []);

  const acceptTokens = useCallback((tokens: TokenPair, name: string) => {
    saveSession({ ...tokens, username: name, accessExpiresAt: Date.now() + tokens.expiresIn * 1000 });
  }, [saveSession]);

  const ensureAccessToken = useCallback(async (): Promise<string> => {
    const current = sessionRef.current;
    if (!current) throw new RequestError("Сначала войдите в аккаунт");
    if (Date.now() < current.accessExpiresAt - 30_000) return current.accessToken;
    if (refreshRef.current) return refreshRef.current;
    refreshRef.current = (async () => {
      const response = await fetch(`${API_BASE_URL}/api/v1/auth/refresh`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ refreshToken: current.refreshToken }),
      });
      if (!response.ok) {
        saveSession(null);
        throw await responseError(response);
      }
      const tokens = (await response.json()) as TokenPair;
      acceptTokens(tokens, current.username);
      return tokens.accessToken;
    })();
    try {
      return await refreshRef.current;
    } finally {
      refreshRef.current = null;
    }
  }, [acceptTokens, saveSession]);

  const showError = useCallback((reason: unknown) => {
    if (reason instanceof RequestError) {
      setError(reason.message);
      setActions(reason.actions);
    } else if (typeof reason === "object" && reason && "message" in reason) {
      const data = "data" in reason && reason.data != null ? Array.from(reason.data as ArrayLike<unknown>) : [];
      const details = data.length > 0
        ? ` · ${data.slice(0, 3).map(item => item instanceof Error ? `${item.name}: ${item.message}` : String(item)).join(" · ").slice(0, 500)}`
        : "";
      setError(`Плеер: ${String(reason.message)}${details}`);
      setActions([]);
    } else if (reason instanceof Error) {
      setError(reason.message);
      setActions([]);
    } else {
      setError(String(reason));
      setActions([]);
    }
  }, []);

  const apiFetch = useCallback(async (path: string, init: RequestInit = {}): Promise<Response> => {
    const token = await ensureAccessToken();
    const headers = new Headers(init.headers);
    headers.set("Authorization", `Bearer ${token}`);
    if (init.body && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");
    const response = await fetch(`${API_BASE_URL}${path}`, { ...init, headers });
    if (!response.ok) throw await responseError(response);
    return response;
  }, [ensureAccessToken]);

  const navigate = useCallback((url: string) => {
    window.history.pushState({}, "", url);
    setLocation(readLocation());
    window.scrollTo(0, 0);
  }, []);

  useEffect(() => {
    const listener = () => setLocation(readLocation());
    window.addEventListener("popstate", listener);
    return () => window.removeEventListener("popstate", listener);
  }, []);

  const loadLibrary = useCallback(async () => {
    const [sort, direction] = sortMode.split(":");
    const response = await apiFetch(`/api/v1/library?sort=${sort}&direction=${direction}`);
    setLibrary(await response.json());
  }, [apiFetch, sortMode]);

  const loadJobs = useCallback(async () => {
    const response = await apiFetch("/api/v1/archive/jobs");
    setJobs(await response.json());
  }, [apiFetch]);

  const loadUsers = useCallback(async () => {
    const response = await apiFetch("/api/v1/admin/users");
    const data = await response.json() as UserQuota[];
    setUsers(data);
    setQuotaDrafts(Object.fromEntries(data.map(user => [user.username, String(Math.round(user.effectiveQuotaBytes / 1024 / 1024 / 1024))])));
  }, [apiFetch]);

  useEffect(() => {
    if (!session) return;
    setLoading(true);
    setError(null);
    setActions([]);
    setVideo(null);
    setChannel(null);
    setPlaylist(null);
    setResults([]);
    setPlayerReady(false);
    const path = location.path;
    const query = location.query;
    const load = async () => {
      if (path === "/watch") {
        const id = query.get("v") || "";
        const response = await apiFetch(`/api/v1/videos/${id}`);
        setVideo(await response.json());
      } else if (path === "/playlist") {
        const id = query.get("list") || "";
        const cursor = query.get("cursor");
        const response = await apiFetch(`/api/v1/playlists/${id}${cursor ? `?cursor=${encodeURIComponent(cursor)}` : ""}`);
        setPlaylist(await response.json());
      } else if (path.startsWith("/channel/") || path.startsWith("/c/") || path.startsWith("/@")) {
        const id = path.startsWith("/channel/")
          ? path.substring("/channel/".length)
          : path.startsWith("/c/") ? path.substring(3) : path.substring(1);
        const cursor = query.get("cursor");
        const response = await apiFetch(`/api/v1/channels/${encodeURIComponent(id)}${cursor ? `?cursor=${encodeURIComponent(cursor)}` : ""}`);
        setChannel(await response.json());
      } else if (path === "/search") {
        const q = query.get("q") || "";
        const type = query.get("type") || "videos";
        const cursor = query.get("cursor");
        setSearchQuery(q);
        setSearchType(type);
        if (q) {
          const response = await apiFetch(`/api/v1/search?q=${encodeURIComponent(q)}&type=${type}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ""}`);
          const data = await response.json() as SearchResults;
          setResults(data.items);
          setSearchNextCursor(data.nextCursor!);
        }
      } else if (path === "/library") {
        await loadLibrary();
      } else if (path === "/jobs") {
        await loadJobs();
      } else if (path === "/admin") {
        await loadUsers();
      }
    };
    load().catch(showError).finally(() => setLoading(false));
  }, [location, session, apiFetch, loadJobs, loadLibrary, loadUsers, showError]);

  useEffect(() => {
    if (!video || !videoElement) return;
    let disposed = false;
    let player: shaka.Player | null = null;
    setPlayerReady(false);
    const start = async () => {
      shaka.polyfill.installAll();
      if (!shaka.Player.isBrowserSupported()) throw new Error("Этот браузер не поддерживается Shaka Player");
      player = new shaka.Player();
      await player.attach(videoElement);
      player.configure("streaming.retryParameters.timeout", 120_000);
      player.configure("streaming.retryParameters.connectionTimeout", 60_000);
      player.configure("streaming.retryParameters.stallTimeout", 30_000);
      player.configure("manifest.retryParameters.timeout", 120_000);
      player.configure("manifest.retryParameters.connectionTimeout", 60_000);
      player.configure("manifest.retryParameters.stallTimeout", 30_000);
      const networking = player.getNetworkingEngine();
      if (!networking) throw new Error("Shaka networking engine недоступен");
      const mediaOrigin = new URL(MEDIA_BASE_URL || window.location.href, window.location.href).origin;
      networking.registerRequestFilter(async (_type, request) => {
        for (const uri of request.uris) {
          if (new URL(uri, window.location.href).origin !== mediaOrigin) {
            throw new Error("Запрос вне media-service запрещён");
          }
        }
        request.headers.Authorization = `Bearer ${await ensureAccessToken()}`;
      });
      player.addEventListener("error", event => showError((event as CustomEvent<unknown>).detail));
      await player.load(`${MEDIA_BASE_URL}${video.manifestUrl}`);
      if (disposed) await player.destroy();
      else {
        setPlayerReady(true);
        setPlaybackStatus("Готово к воспроизведению");
      }
    };
    start().catch(showError);
    return () => {
      disposed = true;
      setPlayerReady(false);
      if (player) void player.destroy();
    };
  }, [video, videoElement, ensureAccessToken, showError]);

  useEffect(() => {
    if (!video || !videoElement || !video.sponsorBlock?.length) return;
    const skip = () => {
      for (const segment of video.sponsorBlock!) {
        if (videoElement.currentTime >= segment.startSeconds && videoElement.currentTime < segment.endSeconds - 0.2) {
          videoElement.currentTime = segment.endSeconds;
          break;
        }
      }
    };
    videoElement.addEventListener("timeupdate", skip);
    return () => videoElement.removeEventListener("timeupdate", skip);
  }, [video, videoElement]);

  useEffect(() => {
    if (!video || !videoElement) return;
    const timer = window.setInterval(() => {
      if (!videoElement.paused && videoElement.duration > 0) {
        void apiFetch(`/api/v1/videos/${video.videoId}/progress`, {
          method: "POST",
          body: JSON.stringify({
            positionSeconds: Math.floor(videoElement.currentTime),
            durationSeconds: Math.floor(videoElement.duration),
          }),
        }).catch(() => undefined);
      }
    }, 15_000);
    return () => window.clearInterval(timer);
  }, [video, videoElement, apiFetch]);

  const login = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    try {
      const response = await fetch(`${API_BASE_URL}/api/v1/auth/login`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username, password }),
      });
      if (!response.ok) throw await responseError(response);
      acceptTokens((await response.json()) as TokenPair, username);
      setPassword("");
    } catch (reason) {
      showError(reason);
    }
  };

  const watchVideo = (event: FormEvent) => {
    event.preventDefault();
    navigate(`/watch?v=${encodeURIComponent(videoId)}`);
  };

  const search = (event: FormEvent) => {
    event.preventDefault();
    navigate(`/search?q=${encodeURIComponent(searchQuery)}&type=${searchType}`);
  };

  const save = async (path: string) => {
    setError(null);
    try {
      await apiFetch(path, { method: "POST" });
      navigate("/jobs");
    } catch (reason) {
      showError(reason);
    }
  };

  const togglePlayback = async () => {
    if (!videoElement || !playerReady) return;
    setError(null);
    try {
      if (videoElement.paused) await videoElement.play();
      else videoElement.pause();
    } catch (reason) {
      showError(reason);
    }
  };

  const download = async (item: LibraryItem) => {
    try {
      const response = await apiFetch(item.downloadUrl);
      const url = URL.createObjectURL(await response.blob());
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = `${item.videoId}.mkv`;
      anchor.click();
      window.setTimeout(() => URL.revokeObjectURL(url), 30_000);
    } catch (reason) {
      showError(reason);
    }
  };

  const remove = async (item: LibraryItem) => {
    if (!window.confirm(`Удалить «${item.title}» из своей библиотеки?`)) return;
    try {
      await apiFetch(`/api/v1/library/${item.videoId}`, { method: "DELETE" });
      await loadLibrary();
    } catch (reason) {
      showError(reason);
    }
  };

  const updateQuota = async (name: string) => {
    const gib = Number(quotaDrafts[name]);
    if (!Number.isFinite(gib) || gib <= 0) return;
    try {
      await apiFetch(`/api/v1/admin/users/${name}/quota`, {
        method: "PUT",
        body: JSON.stringify({ quotaBytes: Math.round(gib * 1024 * 1024 * 1024) }),
      });
      await loadUsers();
    } catch (reason) {
      showError(reason);
    }
  };

  const number = new Intl.NumberFormat("ru-RU");

  if (!session) {
    return (
      <main>
        <header><h1>SpankTube</h1><p>Вход в личный прокси YouTube</p></header>
        <section className="panel auth">
          <form onSubmit={login}>
            <input value={username} onChange={event => setUsername(event.target.value)} autoComplete="username" aria-label="Логин" required />
            <input type="password" value={password} onChange={event => setPassword(event.target.value)} autoComplete="current-password" placeholder="Пароль" aria-label="Пароль" required />
            <button type="submit">Войти</button>
          </form>
        </section>
        {error && <section className="error" role="alert"><strong>{error}</strong></section>}
      </main>
    );
  }

  return (
    <main>
      <header className="topline">
        <div><h1>SpankTube</h1><p>Просмотр, поиск и архив YouTube</p></div>
        <div className="userbox">{session.username} ({session.role}) <button className="secondary" onClick={() => saveSession(null)}>Выйти</button></div>
      </header>
      <nav className="panel nav">
        <button className="secondary" onClick={() => navigate("/")}>Видео</button>
        <button className="secondary" onClick={() => navigate("/search")}>Поиск</button>
        <button className="secondary" onClick={() => navigate("/library")}>Библиотека</button>
        <button className="secondary" onClick={() => navigate("/jobs")}>Очередь</button>
        {session.role === "ADMIN" && <button className="secondary" onClick={() => navigate("/admin")}>Квоты</button>}
      </nav>

      {location.path === "/" && (
        <section className="panel">
          <form className="video-search" onSubmit={watchVideo}>
            <input value={videoId} onChange={event => setVideoId(event.target.value.trim())} placeholder="YouTube videoId (11 символов)" pattern="[A-Za-z0-9_-]{11}" aria-label="YouTube videoId" required />
            <button type="submit" disabled={loading}>Смотреть</button>
          </form>
        </section>
      )}

      {location.path !== "/watch" && location.path !== "/playlist" && (
        <section className="panel">
          <form className="video-search" onSubmit={search}>
            <input value={searchQuery} onChange={event => setSearchQuery(event.target.value)} placeholder="Поиск на YouTube" aria-label="Поиск на YouTube" />
            <select value={searchType} onChange={event => setSearchType(event.target.value)} aria-label="Тип поиска">
              <option value="videos">Видео</option>
              <option value="channels">Каналы</option>
              <option value="playlists">Плейлисты</option>
            </select>
            <button type="submit" disabled={loading}>Найти</button>
          </form>
        </section>
      )}

      {error && (
        <section className="error" role="alert">
          <strong>{error}</strong>
          {actions.length > 0 && <ul>{actions.map(action => <li key={action}>{action}</li>)}</ul>}
        </section>
      )}
      {loading && <p className="muted">Загрузка…</p>}

      {location.path === "/search" && <>
        <ResultGrid items={results} navigate={navigate} />
        {searchNextCursor && <button className="secondary" disabled={loading} onClick={() => navigate(`/search?q=${encodeURIComponent(searchQuery)}&type=${searchType}&cursor=${encodeURIComponent(searchNextCursor)}`)}>Следующая страница</button>}
      </>}

      {location.path === "/watch" && video && (
        <article>
          <section className="player panel">
            <video ref={setVideoElement} controls={playerReady} playsInline
              onCanPlay={() => setPlaybackStatus("Готово к воспроизведению")}
              onPlaying={() => setPlaybackStatus("Воспроизведение")}
              onWaiting={() => setPlaybackStatus("Буферизация")}
              onPause={() => setPlaybackStatus("Пауза")} />
            <div className="playback">
              <button type="button" className="secondary" onClick={togglePlayback} disabled={!videoElement || !playerReady}>
                {videoElement?.paused === false ? "Пауза" : "Играть"}
              </button>
              <button type="button" className="secondary" onClick={() => save(`/api/v1/videos/${video.videoId}/save`)}>Скачать</button>
              <span>{playbackStatus}</span>
            </div>
          </section>
          <section className="panel metadata">
            <div className="channel-line">
              {video.channelAvatarUrl && <img className="avatar" src={video.channelAvatarUrl} alt="" />}
              <button className="link" onClick={() => navigate(video.channelUrl)}>{video.channelName}</button>
            </div>
            <h2>{video.title}</h2>
            <p className="stats">Публикация: {video.publishedAt || "—"} · {formatDuration(video.durationSeconds)} · {video.viewCount == null ? "—" : `${number.format(video.viewCount)} просмотров`} · {video.likeCount == null ? "—" : `${number.format(video.likeCount)} лайков`}{video.dislikeCountEstimate == null ? "" : ` · ≈${number.format(video.dislikeCountEstimate)} дизлайков`}</p>
            <p className="description">{video.description || "Описание отсутствует"}</p>
            <details>
              <summary>Форматы ({video.formats.length}), субтитры ({video.subtitles.length}), SponsorBlock ({video.sponsorBlock?.length || 0})</summary>
              <div className="formats">
                {video.formats.map(format => <div key={format.formatId}><code>{format.formatId}</code> {format.height ? `${format.height}p` : "audio"} · {format.mimeType} · {format.codecs}</div>)}
              </div>
            </details>
          </section>
        </article>
      )}

      {location.path === "/playlist" && playlist && (
        <section className="panel metadata">
          <div className="channel-line">
            {playlist.channel.avatarUrl && <img className="avatar" src={playlist.channel.avatarUrl} alt="" />}
            <button className="link" onClick={() => navigate(playlist.channel.url)}>{playlist.channel.title}</button>
          </div>
          <h2>{playlist.title}</h2>
          <button className="secondary" onClick={() => save(`/api/v1/playlists/${playlist.playlistId}/save`)}>Скачать плейлист</button>
          <ResultGrid items={playlist.items} navigate={navigate} />
          {playlist.nextCursor && <button className="secondary" disabled={loading} onClick={() => navigate(`/playlist?list=${playlist.playlistId}&cursor=${encodeURIComponent(playlist.nextCursor!)}`)}>Следующая страница</button>}
        </section>
      )}

      {(location.path.startsWith("/channel/") || location.path.startsWith("/c/") || location.path.startsWith("/@")) && channel && (
        <section className="panel metadata">
          <div className="channel-line">
            <img className="avatar large" src={channel.avatarUrl} alt="" />
            <div><h2>{channel.title}</h2><p className="muted">{channel.subscriberCount == null ? "" : `${number.format(channel.subscriberCount)} подписчиков`}</p></div>
          </div>
          <button className="secondary" onClick={() => save(`/api/v1/channels/${channel.channelId}/save`)}>Скачать канал</button>
          {channel.videos.length > 0 && <h3>Видео</h3>}
          <ResultGrid items={channel.videos} navigate={navigate} />
          {channel.nextCursor && <button className="secondary" disabled={loading} onClick={() => navigate(`${channel.url}?cursor=${encodeURIComponent(channel.nextCursor!)}`)}>Следующие видео</button>}
          {channel.playlists.length > 0 && <h3>Плейлисты</h3>}
          <ResultGrid items={channel.playlists} navigate={navigate} />
        </section>
      )}

      {location.path === "/library" && library && (
        <section>
          <div className="panel stats">Использовано {formatGB(library.usedBytes)} из {formatGB(library.quotaBytes)} · LRU при {library.lruThresholdPercent}%</div>
          <label className="panel">Сортировка: <select value={sortMode} onChange={event => { setSortMode(event.target.value); setTimeout(loadLibrary, 0); }}>
            <option value="size:desc">Сначала большие</option><option value="size:asc">Сначала маленькие</option><option value="duration:desc">По длительности</option><option value="title:asc">По названию</option>
          </select></label>
          {library.channels.map(group => (
            <section className="panel metadata" key={group.channel.channelId}>
              <div className="channel-line">
                {group.channel.avatarUrl && <img className="avatar" src={group.channel.avatarUrl} alt="" />}
                <button className="link" onClick={() => navigate(group.channel.url)}>{group.channel.title}</button>
                <span className="muted">{formatMB(group.totalBytes)}</span>
              </div>
              {group.videos.map(item => (
                <div className="library-row" key={item.videoId}>
                  <div><strong>{item.title}</strong><div className="muted">{formatDuration(item.durationSeconds)} · {formatMB(item.sizeBytes)}</div></div>
                  <div className="row-actions"><button className="secondary" onClick={() => download(item)}>MKV</button><button className="danger" onClick={() => remove(item)}>Удалить</button></div>
                </div>
              ))}
            </section>
          ))}
        </section>
      )}

      {location.path === "/jobs" && (
        <section className="panel metadata">
          <button className="secondary" onClick={loadJobs}>Обновить</button>
          {jobs.map(job => (
            <div className="job-row" key={job.id}>
              <div><strong>{job.kind} · {job.targetId}</strong><div className="muted">{job.ownerUsername} · приоритет {job.priority} · {job.status} · {job.completedCount + job.failedCount}/{job.totalCount}</div></div>
              {job.error && <div className="error-inline">{job.error}</div>}
            </div>
          ))}
        </section>
      )}

      {location.path === "/admin" && users && (
        <section className="panel metadata">
          <h2>Квоты пользователей</h2>
          {users.map(user => (
            <div className="library-row" key={user.username}>
              <div><strong>{user.username}</strong><div className="muted">{formatGB(user.usedBytes)} / {formatGB(user.effectiveQuotaBytes)}</div></div>
              <div className="row-actions"><input className="quota-input" type="number" min="1" value={quotaDrafts[user.username] || ""} onChange={event => setQuotaDrafts({ ...quotaDrafts, [user.username]: event.target.value })} /><span>ГиБ</span><button className="secondary" onClick={() => updateQuota(user.username)}>Сохранить</button></div>
            </div>
          ))}
        </section>
      )}
    </main>
  );
}

function ResultGrid({ items, navigate }: { items: SearchItem[]; navigate: (url: string) => void }) {
  return (
    <div className="result-grid">
      {items.map(item => (
        <button className="result-card" key={`${item.kind}:${item.id}`} onClick={() => navigate(item.url)}>
          {item.thumbnailUrl && <img src={item.thumbnailUrl} alt="" loading="lazy" />}
          <span className="result-kind">{item.kind}</span>
          <strong>{item.title}</strong>
          <span className="muted">{item.channelName || ""}{item.durationSeconds != null ? ` · ${formatDuration(item.durationSeconds)}` : ""}{item.videoCount != null ? ` · ${item.videoCount} видео` : ""}</span>
        </button>
      ))}
    </div>
  );
}
