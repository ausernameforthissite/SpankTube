import { FormEvent, MouseEvent as ReactMouseEvent, PointerEvent as ReactPointerEvent, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import shaka from "shaka-player/dist/shaka-player.compiled.js";
import type { components } from "./api/schema";
import { DownloadMenu, type DownloadOptions } from "./DownloadMenu";
import { HistoryMenu } from "./HistoryMenu";
import { PlaybackLog } from "./PlaybackLog";
import { QueuePanel } from "./QueuePanel";
import { SettingsMenu } from "./SettingsMenu";
import { usePlaybackLogs } from "./usePlaybackLogs";
import { useQueue } from "./useQueue";
import type { ApiFetch, ArchiveAvailability, ArchiveVersion, QueueJob, UserSettings, VideoFormat, WatchProgress, WatchProgressMap } from "./types";

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
type Video = Omit<BaseVideo, "formats"> & {
  formats: VideoFormat[];
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
  thumbnailUrl: string;
  width: number | null;
  height: number | null;
  videoCodec: string | null;
  audioCodec: string | null;
  audioBitrate: number | null;
  filename: string;
};
type LibraryChannel = { channel: Channel; videos: LibraryItem[]; totalBytes: number };
type LibraryResponse = { channels: LibraryChannel[]; usedBytes: number; quotaBytes: number; lruThresholdPercent: number };
type UserQuota = {
  username: string;
  role: string;
  quotaBytes: number | null;
  usedBytes: number;
  effectiveQuotaBytes: number;
  lruThresholdPercent: number;
};
type Popup = "queue" | "download" | "settings" | "history" | "playbackLog" | null;

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

function archiveFilename(job: QueueJob): string {
  const extension = job.options.videoFormatId === null && job.options.audioFormatId !== null ? "mp3" : "mkv";
  const resolution = job.videoCodec ? (job.height ? `${job.height}p` : "unknown") : "audio";
  const audioBitrate = job.audioBitrate && job.audioBitrate > 0
    ? `${Math.max(1, Math.round(job.audioBitrate / 1000))}kbps` : "unknown";
  const title = job.title.replace(/[\\/:*?"<>|]/g, "_").trim().slice(0, 160) || job.targetId;
  return `${title} [${job.targetId}] (${resolution}, ${job.videoCodec || "none"}, ${audioBitrate}, ${job.audioCodec || "none"}).${extension}`;
}

function formatGB(bytes: number): string {
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} ГиБ`;
}

function publicationDate(value: string | null | undefined): string {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value
    : new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" }).format(date);
}

function archiveVideo(version: ArchiveVersion): Video {
  return {
    videoId: version.videoId,
    title: version.title,
    description: version.description,
    channelId: version.channelId,
    channelName: version.channelName,
    channelAvatarUrl: version.channelAvatarUrl,
    channelUrl: version.channelUrl,
    thumbnailUrl: `/media/v/${version.videoId}/thumbnail`,
    durationSeconds: version.durationSeconds,
    viewCount: null,
    likeCount: null,
    publishedAt: version.publishedAt || "",
    manifestUrl: `/media/v/${version.videoId}/manifest.mpd`,
    formats: [],
    subtitles: [],
    sponsorBlock: [],
  };
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
  const [archiveFile, setArchiveFile] = useState<{ downloadUrl: string; filename: string } | null>(null);
  const [archiveChecked, setArchiveChecked] = useState(false);
  const [playbackVersion, setPlaybackVersion] = useState<string | null>(null);
  const [historyVersions, setHistoryVersions] = useState<ArchiveVersion[]>([]);
  const refreshJobRef = useRef<string | null>(null);
  const activeArchiveDurationRef = useRef<number | null>(null);
  const historySelectionRef = useRef(false);
  const [channel, setChannel] = useState<ChannelPage | null>(null);
  const [playlist, setPlaylist] = useState<Playlist | null>(null);
  const [results, setResults] = useState<SearchItem[]>([]);
  const [searchNextCursor, setSearchNextCursor] = useState<string | null>(null);
  const [library, setLibrary] = useState<LibraryResponse | null>(null);
  const [users, setUsers] = useState<UserQuota[]>([]);
  const [quotaDrafts, setQuotaDrafts] = useState<Record<string, string>>({});
  const [settings, setSettings] = useState<UserSettings>({ autoplay: true, hideTechnicalDetails: false, preferBestQuality: true });
  const [watchProgress, setWatchProgress] = useState<WatchProgressMap>({});
  const watchProgressRef = useRef(watchProgress);
  watchProgressRef.current = watchProgress;
  const progressRequestRef = useRef<Promise<void> | null>(null);
  const autoplayRef = useRef(settings.autoplay);
  autoplayRef.current = settings.autoplay;
  const preferBestQualityRef = useRef(settings.preferBestQuality);
  preferBestQualityRef.current = settings.preferBestQuality;
  const [sortMode, setSortMode] = useState("size:desc");
  const [videoElement, setVideoElement] = useState<HTMLVideoElement | null>(null);
  const [playerReady, setPlayerReady] = useState(false);
  const [mediaReady, setMediaReady] = useState(false);
  const controlsReady = playerReady || mediaReady;
  const authenticated = session !== null;
  const watchId = location.path === "/watch" ? location.query.get("v") : null;
  const playbackSession = useMemo(() => `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`, [watchId]);
  const [playbackStatus, setPlaybackStatus] = useState("Плеер не запущен");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [actions, setActions] = useState<string[]>([]);
  const [popup, setPopup] = useState<Popup>(null);
  const [popupPosition, setPopupPosition] = useState<{ left: number; top: number; width: number; maxHeight: number } | null>(null);
  const popupWindowRef = useRef<HTMLDivElement>(null);
  const popupAnchorRef = useRef<HTMLElement | null>(null);
  const queueButtonRef = useRef<HTMLDivElement>(null);
  const pulseTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const toastTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const pendingDownloads = useRef(new Set<string>());
  const downloadedJobs = useRef(new Set<string>());
  const playerDestroyRef = useRef<Promise<void> | null>(null);
  const lastTouchTapRef = useRef<{ timeStamp: number; x: number; y: number } | null>(null);
  const lastTouchSeekRef = useRef(0);

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

  const apiFetch = useCallback<ApiFetch>(async (path, init = {}) => {
    const token = await ensureAccessToken();
    const headers = new Headers(init.headers);
    headers.set("Authorization", `Bearer ${token}`);
    if (init.body && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");
    const response = await fetch(`${API_BASE_URL}${path}`, { ...init, headers });
    if (!response.ok) throw await responseError(response);
    return response;
  }, [ensureAccessToken]);

  const reportProgress = useCallback(async () => {
    if (!videoElement || !watchId || !Number.isFinite(videoElement.duration) || videoElement.duration <= 0) return;
    const duration = Math.floor(videoElement.duration);
    const position = Math.max(0, Math.min(Math.floor(videoElement.currentTime), duration));
    const progress: WatchProgress = {
      videoId: watchId,
      positionSeconds: position,
      watchPercent: Math.min(100, position * 100 / duration),
      lastAccessedAt: new Date().toISOString(),
    };
    setWatchProgress(current => {
      const next = { ...current, [watchId]: progress };
      watchProgressRef.current = next;
      return next;
    });
    try {
      await apiFetch(`/api/v1/videos/${watchId}/progress`, {
        method: "POST",
        body: JSON.stringify({ positionSeconds: position, durationSeconds: duration }),
      });
    } catch {
    }
  }, [videoElement, watchId, apiFetch]);

  const syncMediaReady = useCallback(() => {
    if (videoElement?.currentSrc && videoElement.readyState >= HTMLMediaElement.HAVE_METADATA) {
      setMediaReady(true);
    }
  }, [videoElement]);

  useEffect(() => {
    if (!videoElement) return;
    syncMediaReady();
    document.addEventListener("visibilitychange", syncMediaReady);
    window.addEventListener("pageshow", syncMediaReady);
    return () => {
      document.removeEventListener("visibilitychange", syncMediaReady);
      window.removeEventListener("pageshow", syncMediaReady);
    };
  }, [videoElement, syncMediaReady]);

  const playbackLogs = usePlaybackLogs(watchId, playbackSession,
    Boolean(session) && !controlsReady, apiFetch);

  const notify = useCallback((message: string) => {
    document.getElementById("spanktube-toast")?.remove();
    const element = document.createElement("div");
    element.id = "spanktube-toast";
    element.className = "toast";
    element.style.cssText = "position:fixed;top:40px;left:50%;transform:translateX(-50%);z-index:1100;max-width:min(520px,calc(100vw - 32px));padding:13px 18px;border:1px solid #586c9b;border-radius:10px;color:#edf2ff;background:#263452;box-shadow:0 8px 32px #0008;margin:0;display:block;visibility:visible;opacity:1;";
    element.setAttribute("role", "status");
    element.textContent = message;
    document.body.appendChild(element);
    if (toastTimer.current) clearTimeout(toastTimer.current);
    toastTimer.current = setTimeout(() => element.remove(), 5000);
    const queue = queueButtonRef.current;
    if (queue) {
      queue.style.animation = "queue-glow 0.425s ease-in-out infinite alternate";
      queue.querySelectorAll("button").forEach(button => {
        button.style.animation = "queue-glow 0.425s ease-in-out infinite alternate";
      });
    }
    if (pulseTimer.current) clearTimeout(pulseTimer.current);
    pulseTimer.current = setTimeout(() => {
      if (queue) {
        queue.style.animation = "";
        queue.querySelectorAll("button").forEach(button => {
          button.style.animation = "";
        });
      }
    }, 5000);
  }, []);

  const { jobs, beforeYou } = useQueue(Boolean(session), apiFetch);

  const navigate = useCallback((url: string, preservePopup = false) => {
    window.history.pushState({}, "", url);
    setLocation(readLocation());
    if (!preservePopup) setPopup(null);
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

  const loadUsers = useCallback(async () => {
    const response = await apiFetch("/api/v1/admin/users");
    const data = await response.json() as UserQuota[];
    setUsers(data);
    setQuotaDrafts(Object.fromEntries(data.map(user => [user.username, String(Math.round(user.effectiveQuotaBytes / 1024 / 1024 / 1024))])));
  }, [apiFetch]);

  useEffect(() => {
    if (!session) {
      setWatchProgress({});
      progressRequestRef.current = null;
      return;
    }
    progressRequestRef.current = Promise.all([
      apiFetch("/api/v1/users/me/settings"),
      apiFetch("/api/v1/library/progress"),
    ]).then(async ([settingsResponse, progressResponse]) => {
      setSettings(await settingsResponse.json());
      const entries = await progressResponse.json() as WatchProgress[];
      setWatchProgress(Object.fromEntries(entries.map(entry => [entry.videoId, entry])));
    }).catch(showError);
  }, [session, apiFetch, showError]);

  const updateSettings = async (patch: Partial<UserSettings>) => {
    const previous = settings;
    setSettings(current => ({ ...current, ...patch }));
    try {
      const response = await apiFetch("/api/v1/users/me/settings", { method: "PATCH", body: JSON.stringify(patch) });
      setSettings(await response.json());
    } catch (reason) {
      setSettings(previous);
      showError(reason);
    }
  };

  const togglePopup = useCallback((name: Exclude<Popup, null>, anchor: HTMLElement) => {
    popupAnchorRef.current = anchor;
    setPopup(current => current === name ? null : name);
  }, []);

  const updatePopupPosition = useCallback(() => {
    const element = popupWindowRef.current;
    const anchor = popupAnchorRef.current;
    if (!element || !anchor) return;
    const margin = 24;
    const gap = 8;
    const rect = anchor.getBoundingClientRect();
    const width = Math.min(540, window.innerWidth - margin * 2);
    const maxHeight = window.innerHeight - margin * 2;
    const height = Math.min(element.offsetHeight || maxHeight, maxHeight);
    let top = rect.bottom + gap;
    if (top + height > window.innerHeight - margin) {
      const above = rect.top - height - gap;
      top = above >= margin ? above : window.innerHeight - height - margin;
    }
    top = Math.max(margin, Math.min(top, window.innerHeight - height - margin));
    const left = Math.max(margin, Math.min(rect.left, window.innerWidth - width - margin));
    const next = { left, top, width, maxHeight };
    setPopupPosition(current => current && current.left === next.left && current.top === next.top
      && current.width === next.width && current.maxHeight === next.maxHeight ? current : next);
  }, []);

  useLayoutEffect(() => {
    updatePopupPosition();
  }, [popup, updatePopupPosition]);

  useEffect(() => {
    if (!popup) return;
    const observer = new ResizeObserver(updatePopupPosition);
    if (popupWindowRef.current) observer.observe(popupWindowRef.current);
    window.addEventListener("resize", updatePopupPosition);
    window.addEventListener("scroll", updatePopupPosition, true);
    return () => {
      observer.disconnect();
      window.removeEventListener("resize", updatePopupPosition);
      window.removeEventListener("scroll", updatePopupPosition, true);
    };
  }, [popup, updatePopupPosition]);

  const applyArchive = useCallback((availability: ArchiveAvailability) => {
    const version = availability.version;
    setArchiveFile(availability.archived && availability.downloadUrl && availability.filename
      ? { downloadUrl: availability.downloadUrl, filename: availability.filename } : null);
    setPlaybackVersion(version?.versionId || null);
    activeArchiveDurationRef.current = version?.durationSeconds ?? null;
    if (version) setVideo(archiveVideo(version));
    historySelectionRef.current = false;
    setArchiveChecked(true);
  }, []);

  useEffect(() => {
    if (!authenticated) return;
    setLoading(true);
    setError(null);
    setActions([]);
    setVideo(null);
    setArchiveFile(null);
    setArchiveChecked(false);
    setPlaybackVersion(null);
    setHistoryVersions([]);
    refreshJobRef.current = null;
    historySelectionRef.current = false;
    setChannel(null);
    setPlaylist(null);
    setResults([]);
    setPlayerReady(false);
    setMediaReady(false);
    const path = location.path;
    const query = location.query;
    const load = async () => {
      if (path === "/watch") {
        const id = query.get("v") || "";
        const youtubeRequest = apiFetch(`/api/v1/videos/${id}`);
        try {
          const archiveResponse = await apiFetch(`/api/v1/videos/${id}/archive`);
          applyArchive(await archiveResponse.json() as ArchiveAvailability);
        } catch {
          setArchiveChecked(true);
        }
        const response = await youtubeRequest;
        const youtubeVideo = await response.json() as Video;
        if (!historySelectionRef.current) setVideo(youtubeVideo);
        const archivedDuration = activeArchiveDurationRef.current;
        if (archivedDuration !== null && youtubeVideo.durationSeconds !== null
          && archivedDuration !== youtubeVideo.durationSeconds && !refreshJobRef.current) {
          const refreshResponse = await apiFetch(`/api/v1/videos/${id}/refresh`, { method: "POST" });
          const refreshJob = await refreshResponse.json() as QueueJob;
          refreshJobRef.current = refreshJob.id;
          setPlaybackStatus("Обновление архивной копии…");
        }
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
          setSearchNextCursor(data.nextCursor);
        }
      } else if (path === "/library") {
        await loadLibrary();
      } else if (path === "/admin") {
        await loadUsers();
      }
    };
    load().catch(showError).finally(() => setLoading(false));
  }, [location, authenticated, apiFetch, loadLibrary, loadUsers, showError, applyArchive]);

  useEffect(() => {
    if (!watchId || !videoElement || !archiveChecked) return;
    let disposed = false;
    let player: shaka.Player | null = null;
    setPlayerReady(false);
    setMediaReady(false);
    setPlaybackStatus("Подготовка видео…");
    const start = async () => {
      shaka.polyfill.installAll();
      if (!shaka.Player.isBrowserSupported()) throw new Error("Этот браузер не поддерживается Shaka Player");
      if (playerDestroyRef.current) {
        const previousDestroy = playerDestroyRef.current;
        playerDestroyRef.current = null;
        await previousDestroy;
      }
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
      const manifestParams = new URLSearchParams({ playbackSession });
      if (playbackVersion) manifestParams.set("version", playbackVersion);
      await player.load(`${MEDIA_BASE_URL}/media/v/${watchId}/manifest.mpd?${manifestParams}`);
      if (disposed) {
        await player.destroy();
        return;
      }
      await progressRequestRef.current;
      const resume = watchProgressRef.current[watchId];
      if (resume && resume.positionSeconds > 0 && resume.positionSeconds < videoElement.duration - 1) {
        videoElement.currentTime = resume.positionSeconds;
      }
      player.configure("abr.enabled", !preferBestQualityRef.current);
      if (preferBestQualityRef.current) {
        const tracks = player.getVariantTracks();
        if (tracks.length > 0) {
          const best = tracks.reduce((current, candidate) => {
            const candidateHeight = candidate.height || 0;
            const currentHeight = current.height || 0;
            return candidateHeight > currentHeight
              || candidateHeight === currentHeight && candidate.bandwidth > current.bandwidth ? candidate : current;
          });
          player.selectVariantTrack(best, true);
        }
      }
      setPlayerReady(true);
      setPlaybackStatus("Готово к воспроизведению");
      if (autoplayRef.current) {
        try {
          await videoElement.play();
        } catch (reason) {
          if (reason instanceof DOMException && reason.name === "NotAllowedError") {
            setPlaybackStatus("Нажмите «Играть»");
          } else {
            showError(reason);
          }
        }
      }
    };
    start().catch(showError);
    return () => {
      disposed = true;
      setPlayerReady(false);
      if (player) playerDestroyRef.current = player.destroy();
    };
  }, [watchId, videoElement, ensureAccessToken, showError, playbackSession, archiveChecked, playbackVersion]);

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
      if (!videoElement.paused) void reportProgress();
    }, 15_000);
    return () => {
      window.clearInterval(timer);
      void reportProgress();
    };
  }, [video, videoElement, reportProgress]);

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

  const downloadBlob = useCallback(async (url: string, filename: string) => {
    const response = await apiFetch(url);
    const blobUrl = URL.createObjectURL(await response.blob());
    const anchor = document.createElement("a");
    anchor.href = blobUrl;
    anchor.download = filename;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    window.setTimeout(() => URL.revokeObjectURL(blobUrl), 30_000);
  }, [apiFetch]);

  const downloadJob = useCallback(async (job: QueueJob) => {
    if (!job.downloadUrl) return;
    try {
      await downloadBlob(job.downloadUrl, job.fileName || archiveFilename(job));
    } catch (reason) {
      showError(reason);
    }
  }, [downloadBlob, showError]);

  const archive = async () => {
    if (!video) return;
    setError(null);
    try {
      const response = await apiFetch(`/api/v1/videos/${video.videoId}/save`, { method: "POST" });
      const job = await response.json() as QueueJob;
      if (job.status === "completed") notify("Видео уже заархивировано");
      else if (response.status === 202) notify("Архивация поставлена в очередь");
      else notify("Архивация этого видео уже идёт");
    } catch (reason) {
      showError(reason);
    }
  };

  const download = async (options: DownloadOptions) => {
    if (!video) return;
    setError(null);
    try {
      const response = await apiFetch(`/api/v1/videos/${video.videoId}/download`, {
        method: "POST",
        body: JSON.stringify(options),
      });
      const job = await response.json() as QueueJob;
      setPopup(null);
      if (job.status === "completed" && job.downloadUrl) {
        downloadedJobs.current.add(job.id);
        await downloadJob(job);
        notify("Файл готов к скачиванию");
      } else {
        pendingDownloads.current.add(job.id);
        notify("Задание на скачивание создано");
      }
    } catch (reason) {
      showError(reason);
    }
  };

  useEffect(() => {
    for (const job of jobs) {
      if (job.kind === "DOWNLOAD" && job.status === "completed" && job.downloadUrl
        && pendingDownloads.current.has(job.id) && !downloadedJobs.current.has(job.id)) {
        downloadedJobs.current.add(job.id);
        pendingDownloads.current.delete(job.id);
        void downloadJob(job);
      }
    }
  }, [jobs, downloadJob]);

  useEffect(() => {
    const refreshId = refreshJobRef.current;
    if (!refreshId || !watchId) return;
    const job = jobs.find(candidate => candidate.id === refreshId && candidate.targetId === watchId);
    if (!job) return;
    if (job.status === "failed") {
      refreshJobRef.current = null;
      showError(new RequestError(job.error || "Не удалось обновить архивную копию"));
      return;
    }
    if (job.status !== "completed") return;
    refreshJobRef.current = null;
    void apiFetch(`/api/v1/videos/${watchId}/archive`)
      .then(response => response.json() as Promise<ArchiveAvailability>)
      .then(availability => {
        applyArchive(availability);
        setPlaybackStatus("Архивная копия обновлена");
      })
      .catch(showError);
  }, [jobs, watchId, apiFetch, applyArchive, showError]);

  const saveCollection = async (path: string) => {
    setError(null);
    try {
      const response = await apiFetch(path, { method: "POST" });
      if (response.status === 202) notify("Пакетная архивация поставлена в очередь");
      else notify("Эта архивация уже идёт");
    } catch (reason) {
      showError(reason);
    }
  };

  const openHistory = async (anchor: HTMLElement) => {
    if (!watchId) return;
    setError(null);
    try {
      const response = await apiFetch(`/api/v1/videos/${watchId}/archive/versions`);
      setHistoryVersions(await response.json() as ArchiveVersion[]);
      togglePopup("history", anchor);
    } catch (reason) {
      showError(reason);
    }
  };

  const selectHistory = (version: ArchiveVersion) => {
    historySelectionRef.current = true;
    setArchiveFile({ downloadUrl: version.downloadUrl, filename: version.filename });
    setVideo(archiveVideo(version));
    setPlaybackVersion(version.versionId);
    setArchiveChecked(true);
    setPopup(null);
  };

  const togglePlayback = async () => {
    if (!videoElement || !controlsReady) return;
    setError(null);
    try {
      if (videoElement.paused) await videoElement.play();
      else videoElement.pause();
    } catch (reason) {
      showError(reason);
    }
  };

  const seekFromPointer = useCallback((clientX: number) => {
    if (!videoElement || !controlsReady || !Number.isFinite(videoElement.duration)) return;
    const bounds = videoElement.getBoundingClientRect();
    const offset = clientX < bounds.left + bounds.width / 2 ? -10 : 10;
    videoElement.currentTime = Math.max(0, Math.min(videoElement.duration, videoElement.currentTime + offset));
  }, [videoElement, controlsReady]);

  const doubleClickSeek = useCallback((event: ReactMouseEvent<HTMLVideoElement>) => {
    const bounds = event.currentTarget.getBoundingClientRect();
    if (event.clientY >= bounds.bottom - 48) return;
    event.preventDefault();
    if (Date.now() - lastTouchSeekRef.current < 500) return;
    seekFromPointer(event.clientX);
  }, [seekFromPointer]);

  const touchSeek = useCallback((event: ReactPointerEvent<HTMLVideoElement>) => {
    if (event.pointerType !== "touch" || !event.isPrimary) return;
    if (!controlsReady) {
      lastTouchTapRef.current = null;
      return;
    }
    const bounds = event.currentTarget.getBoundingClientRect();
    if (event.clientY >= bounds.bottom - 48) {
      lastTouchTapRef.current = null;
      return;
    }
    const previous = lastTouchTapRef.current;
    if (previous && event.timeStamp - previous.timeStamp <= 350
      && Math.abs(event.clientX - previous.x) <= 40 && Math.abs(event.clientY - previous.y) <= 40) {
      event.preventDefault();
      lastTouchSeekRef.current = Date.now();
      lastTouchTapRef.current = null;
      seekFromPointer(event.clientX);
    } else {
      lastTouchTapRef.current = { timeStamp: event.timeStamp, x: event.clientX, y: event.clientY };
    }
  }, [controlsReady, seekFromPointer]);

  const dismissJob = useCallback(async (job: QueueJob) => {
    setError(null);
    try {
      await apiFetch(`/api/v1/archive/jobs/${job.id}`, { method: "DELETE" });
    } catch (reason) {
      showError(reason);
    }
  }, [apiFetch, showError]);

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
        <div className="split-button" ref={queueButtonRef}>
          <button className="secondary" onClick={() => navigate("/jobs")}>Очередь</button>
          <button className="secondary split-arrow" aria-label="Показать очередь" onClick={event => togglePopup("queue", event.currentTarget)}>▼</button>
        </div>
        <button className="secondary" onClick={event => togglePopup("settings", event.currentTarget)}>Настройки ▼</button>
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
        <ResultGrid items={results} navigate={navigate} progressMap={watchProgress} />
        {searchNextCursor && <button className="secondary" disabled={loading} onClick={() => navigate(`/search?q=${encodeURIComponent(searchQuery)}&type=${searchType}&cursor=${encodeURIComponent(searchNextCursor)}`)}>Следующая страница</button>}
      </>}

      {watchId && (
        <article>
          <section className="player panel">
            <div className="video-frame">
              <video ref={setVideoElement} controls={controlsReady} playsInline
                aria-label="Видеоплеер. Двойной клик слева — на 10 секунд назад, справа — вперёд"
                onDoubleClickCapture={doubleClickSeek} onPointerUp={touchSeek}
                onLoadedMetadata={syncMediaReady}
                onCanPlay={() => {
                  syncMediaReady();
                  setPlaybackStatus("Готово к воспроизведению");
                }}
                onPlaying={() => {
                  syncMediaReady();
                  setPlaybackStatus("Воспроизведение");
                }}
                onWaiting={() => setPlaybackStatus("Буферизация")}
                onEmptied={() => setMediaReady(false)}
                onPause={() => {
                  syncMediaReady();
                  setPlaybackStatus("Пауза");
                  void reportProgress();
                }} />
              {!controlsReady && <PlaybackLog lines={playbackLogs} />}
            </div>
            <div className="playback">
              <button type="button" className="secondary" onClick={togglePlayback} disabled={!videoElement || !controlsReady}>
                {videoElement?.paused === false ? "Пауза" : "Играть"}
              </button>
              {video && <>
                {archiveFile
                  ? <button type="button" className="secondary" onClick={() => void downloadBlob(archiveFile.downloadUrl, archiveFile.filename)}>Скачать из архива</button>
                  : <button type="button" className="secondary" onClick={archive}>Архивировать</button>}
                {archiveFile && <button type="button" className="secondary" onClick={event => void openHistory(event.currentTarget)}>История ▼</button>}
                <div className="split-button">
                  <button type="button" className="secondary" onClick={() => download({ videoFormatId: null, audioFormatId: null, subtitles: true, sponsorBlock: true })}>Скачать</button>
                  <button type="button" className="secondary split-arrow" aria-label="Выбрать формат скачивания" onClick={event => togglePopup("download", event.currentTarget)}>▼</button>
                </div>
              </>}
              {playbackLogs.length > 0 && <button type="button" className="playback-log-latest"
                aria-label="Открыть журнал подготовки видео" title={playbackLogs[playbackLogs.length - 1].text}
                onClick={event => togglePopup("playbackLog", event.currentTarget)}>
                {playbackLogs[playbackLogs.length - 1].text}
              </button>}
              <span className="playback-status">{playbackStatus}</span>
            </div>
          </section>
          {video && <section className="panel metadata">
            <div className="channel-line">
              {video.channelAvatarUrl && <img className="avatar" src={video.channelAvatarUrl} alt="" />}
              <button className="link" onClick={() => navigate(video.channelUrl)}>{video.channelName}</button>
            </div>
            <h2>{video.title}</h2>
            <p className="stats">Публикация: {publicationDate(video.publishedAt)} · {formatDuration(video.durationSeconds)} · {video.viewCount == null ? "—" : `${number.format(video.viewCount)} просмотров`} · {video.likeCount == null ? "—" : `${number.format(video.likeCount)} лайков`}{video.dislikeCountEstimate == null ? "" : ` · ≈${number.format(video.dislikeCountEstimate)} дизлайков`}</p>
            <p className="description">{video.description || "Описание отсутствует"}</p>
            <details>
              <summary>Форматы ({video.formats.length}), субтитры ({video.subtitles.length}), SponsorBlock ({video.sponsorBlock?.length || 0})</summary>
              <div className="formats">
                {video.formats.map(format => <div key={format.formatId}><code>{format.formatId}</code> {format.height ? `${format.height}p` : "audio"} · {format.mimeType} · {format.codecs}</div>)}
              </div>
            </details>
          </section>}
        </article>
      )}

      {location.path === "/playlist" && playlist && (
        <section className="panel metadata">
          <div className="channel-line">
            {playlist.channel.avatarUrl && <img className="avatar" src={playlist.channel.avatarUrl} alt="" />}
            <button className="link" onClick={() => navigate(playlist.channel.url)}>{playlist.channel.title}</button>
          </div>
          <h2>{playlist.title}</h2>
          <button className="secondary" onClick={() => saveCollection(`/api/v1/playlists/${playlist.playlistId}/save`)}>Архивировать плейлист</button>
          <ResultGrid items={playlist.items} navigate={navigate} progressMap={watchProgress} />
          {playlist.nextCursor && <button className="secondary" disabled={loading} onClick={() => navigate(`/playlist?list=${playlist.playlistId}&cursor=${encodeURIComponent(playlist.nextCursor!)}`)}>Следующая страница</button>}
        </section>
      )}

      {(location.path.startsWith("/channel/") || location.path.startsWith("/c/") || location.path.startsWith("/@")) && channel && (
        <section className="panel metadata">
          <div className="channel-line">
            <img className="avatar large" src={channel.avatarUrl} alt="" />
            <div><h2>{channel.title}</h2><p className="muted">{channel.subscriberCount == null ? "" : `${number.format(channel.subscriberCount)} подписчиков`}</p></div>
          </div>
          <button className="secondary" onClick={() => saveCollection(`/api/v1/channels/${channel.channelId}/save`)}>Архивировать канал</button>
          {channel.videos.length > 0 && <h3>Видео</h3>}
          <ResultGrid items={channel.videos} navigate={navigate} progressMap={watchProgress} />
          {channel.nextCursor && <button className="secondary" disabled={loading} onClick={() => navigate(`${channel.url}?cursor=${encodeURIComponent(channel.nextCursor!)}`)}>Следующие видео</button>}
          {channel.playlists.length > 0 && <h3>Плейлисты</h3>}
          <ResultGrid items={channel.playlists} navigate={navigate} progressMap={watchProgress} />
        </section>
      )}

      {location.path === "/library" && library && (
        <section>
          <div className="panel stats">Использовано {formatGB(library.usedBytes)} из {formatGB(library.quotaBytes)} · LRU при {library.lruThresholdPercent}%</div>
          <label className="panel">Сортировка: <select value={sortMode} onChange={event => setSortMode(event.target.value)}>
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
                  <div className="library-thumbnail-frame"><img className="library-thumbnail" src={item.thumbnailUrl} alt="" loading="lazy" /><WatchProgressBar progress={watchProgress[item.videoId]} /></div>
                  <div className="library-info"><a className="queue-video-link" href={`/watch?v=${encodeURIComponent(item.videoId)}`} onClick={event => {
                    event.preventDefault();
                    navigate(`/watch?v=${encodeURIComponent(item.videoId)}`);
                  }}><strong>{item.title}</strong></a><div className="muted">{formatDuration(item.durationSeconds)} · {formatMB(item.sizeBytes)}</div>
                    {!settings.hideTechnicalDetails && <div className="muted">{item.height ? `${item.height}p` : "—"} · {item.videoCodec || "—"} · {item.audioCodec || "—"}</div>}
                    <div className="filename">{item.filename}</div>
                  </div>
                  <div className="row-actions"><button className="secondary" onClick={() => void downloadBlob(item.downloadUrl, item.filename)}>MKV</button><button className="danger" onClick={() => remove(item)}>Удалить</button></div>
                </div>
              ))}
            </section>
          ))}
        </section>
      )}

      {location.path === "/jobs" && <QueuePanel jobs={jobs} beforeYou={beforeYou} onDownload={downloadJob} onDismiss={dismissJob} onNavigate={navigate} />}

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

      {popup && <div className="popup-backdrop" onPointerDown={() => setPopup(null)} />}
      <div className="popup-window" ref={popupWindowRef} style={popupPosition || undefined} onClick={event => event.stopPropagation()}>
        {popup === "queue" && <QueuePanel jobs={jobs} beforeYou={beforeYou} onDownload={downloadJob} onDismiss={dismissJob} onNavigate={url => navigate(url, true)} onClose={() => setPopup(null)} compact />}
        {popup === "download" && video && <DownloadMenu formats={video.formats} onDownload={download} onClose={() => setPopup(null)} />}
        {popup === "settings" && <SettingsMenu settings={settings} onChange={updateSettings} onClose={() => setPopup(null)} />}
        {popup === "history" && <HistoryMenu versions={historyVersions} onSelect={selectHistory} onClose={() => setPopup(null)} />}
        {popup === "playbackLog" && <section className="playback-log-options" aria-label="Журнал подготовки видео">
          <div className="popup-header"><button className="popup-close" aria-label="Закрыть журнал" onClick={() => setPopup(null)}>×</button></div>
          <PlaybackLog lines={playbackLogs} overlay={false} />
        </section>}
      </div>
    </main>
  );
}

function WatchProgressBar({ progress }: { progress?: WatchProgress }) {
  const percent = progress ? Math.max(0, Math.min(100, progress.watchPercent)) : 0;
  return <progress className="watch-progress" max={100} value={percent} aria-label={`Прогресс просмотра: ${Math.round(percent)}%`} />;
}

function ResultGrid({ items, navigate, progressMap }: {
  items: SearchItem[];
  navigate: (url: string) => void;
  progressMap: WatchProgressMap;
}) {
  return (
    <div className="result-grid">
      {items.map(item => (
        <button className="result-card" key={`${item.kind}:${item.id}`} onClick={() => navigate(item.url)}>
          <div className="result-thumbnail">
            {item.thumbnailUrl && <img src={item.thumbnailUrl} alt="" loading="lazy" onError={event => { event.currentTarget.style.visibility = "hidden"; }} />}
            <WatchProgressBar progress={progressMap[item.id]} />
          </div>
          <div className="result-kind-row"><span className="result-kind">{item.kind}</span>{item.durationSeconds != null && <span className="result-duration">{formatDuration(item.durationSeconds)}</span>}</div>
          <strong>{item.title}</strong>
          <span className="muted">{item.channelName || ""}{item.videoCount != null ? ` · ${item.videoCount} видео` : ""}</span>
        </button>
      ))}
    </div>
  );
}
