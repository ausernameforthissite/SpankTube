import { FormEvent, MouseEvent as ReactMouseEvent, PointerEvent as ReactPointerEvent, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import shaka from "shaka-player/dist/shaka-player.compiled.js";
import { AppHeader, type HeaderSearchType } from "./AppHeader";
import { animalAvatar } from "./animalAvatar";
import type { components } from "./api/schema";
import { DownloadMenu, type DownloadOptions } from "./DownloadMenu";
import { HistoryMenu } from "./HistoryMenu";
import { LibraryView } from "./LibraryView";
import { Pagination } from "./Pagination";
import { PlayerControls } from "./PlayerControls";
import { PlaybackLog } from "./PlaybackLog";
import { QueuePanel } from "./QueuePanel";
import { SettingsMenu } from "./SettingsMenu";
import { UserMenu } from "./UserMenu";
import { usePlaybackLogs } from "./usePlaybackLogs";
import { useQueue } from "./useQueue";
import type { ApiFetch, ArchiveAvailability, ArchiveVersion, LibraryItem, LibraryLocation, LibraryResponse, QueueJob, UserSettings, VideoFormat, WatchProgress, WatchProgressMap } from "./types";

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
  videoCount: number | null;
  totalPages: number | null;
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
type QualityTrack = ReturnType<shaka.Player["getVariantTracks"]>[number];
type ChannelPage = Channel & { videos: SearchItem[]; playlists: SearchItem[] };
type Playlist = {
  playlistId: string;
  title: string;
  channel: Channel;
  items: SearchItem[];
  nextCursor: string | null;
  thumbnailUrl: string;
  videoCount: number | null;
  totalPages: number | null;
};
type UserQuota = {
  username: string;
  role: string;
  quotaBytes: number | null;
  usedBytes: number;
  effectiveQuotaBytes: number;
  lruThresholdPercent: number;
};
type Popup = "queue" | "download" | "settings" | "history" | "playbackLog" | "user" | null;

const STORAGE_KEY = "spanktube.session";
const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || "").replace(/\/$/, "");
const MEDIA_BASE_URL = (import.meta.env.VITE_MEDIA_BASE_URL || API_BASE_URL).replace(/\/$/, "");
const ANTIBOT_ACTIONS = ["Enable the bgutil PO-token provider for yt-dlp",
  "Update yt-dlp to the latest release", "Switch the UpstreamProvider adapter"];

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

function parseTimecode(value: string | null): number | null {
  if (!value) return null;
  const match = /^(?:(\d+)h)?(?:(\d+)m)?(\d+)s$/.exec(value);
  if (!match) return null;
  const hours = Number(match[1] || 0);
  const minutes = Number(match[2] || 0);
  const seconds = Number(match[3]);
  const total = hours * 3600 + minutes * 60 + seconds;
  return Number.isSafeInteger(total) ? total : null;
}

function pageNumber(value: string | null): number {
  const page = Number(value);
  return Number.isSafeInteger(page) && page >= 1 ? page : 1;
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

function youtubeVideoId(input: string): string | null {
  const value = input.trim();
  if (/^[A-Za-z0-9_-]{11}$/.test(value)) return value;
  try {
    const url = new URL(value);
    const id = url.hostname === "youtu.be" ? url.pathname.slice(1) : url.searchParams.get("v");
    return id && /^[A-Za-z0-9_-]{11}$/.test(id) ? id : null;
  } catch {
    return null;
  }
}

function videoSearchItem(video: Video): SearchItem {
  return { kind: "video", id: video.videoId, title: video.title, url: `/watch?v=${video.videoId}`,
    thumbnailUrl: video.thumbnailUrl || `/media/v/${video.videoId}/thumbnail`, channelName: video.channelName,
    durationSeconds: video.durationSeconds ?? null, videoCount: null };
}

function readLocation() {
  return { path: window.location.pathname, query: new URLSearchParams(window.location.search) };
}

function auxiliaryNavigation(event: ReactMouseEvent<HTMLElement>) {
  if (event.button !== 1) return;
  const target = event.target as HTMLElement;
  const url = target.closest("a[href]")?.getAttribute("href")
    || target.closest<HTMLElement>("[data-nav-url]")?.dataset.navUrl;
  if (!url) return;
  event.preventDefault();
  window.open(url, "_blank", "noopener,noreferrer");
}

export default function App() {
  const [session, setSession] = useState<Session | null>(loadSession);
  const sessionRef = useRef(session);
  const refreshRef = useRef<Promise<string> | null>(null);
  const [username, setUsername] = useState("viewer");
  const [password, setPassword] = useState("");
  const [registration, setRegistration] = useState(false);
  const [inviteCode, setInviteCode] = useState("");
  const [location, setLocation] = useState(readLocation);
  const [searchQuery, setSearchQuery] = useState("");
  const [searchType, setSearchType] = useState<HeaderSearchType>("all");
  const [searchMessage, setSearchMessage] = useState<string | null>(null);
  const [avatarObjectUrl, setAvatarObjectUrl] = useState<string | null>(null);
  const [avatarVersion, setAvatarVersion] = useState(0);
  const [invite, setInvite] = useState<{ code: string; expiresAt: string; usedBy: string | null } | null>(null);
  const [video, setVideo] = useState<Video | null>(null);
  const [archiveFile, setArchiveFile] = useState<{ downloadUrl: string; filename: string } | null>(null);
  const [archiveChecked, setArchiveChecked] = useState(false);
  const [playbackVersion, setPlaybackVersion] = useState<string | null>(null);
  const [historyVersions, setHistoryVersions] = useState<ArchiveVersion[]>([]);
  const refreshJobRef = useRef<string | null>(null);
  const activeArchiveDurationRef = useRef<number | null>(null);
  const historySelectionRef = useRef(false);
  const [channel, setChannel] = useState<ChannelPage | null>(null);
  const [channelTab, setChannelTab] = useState<"videos" | "playlists">(
    location.query.get("tab") === "playlists" ? "playlists" : "videos");
  const [playlist, setPlaylist] = useState<Playlist | null>(null);
  const pageCursors = useRef<Record<string, Record<number, string | null>>>({});
  const [results, setResults] = useState<SearchItem[]>([]);
  const [searchNextCursor, setSearchNextCursor] = useState<string | null>(null);
  const [library, setLibrary] = useState<LibraryResponse | null>(null);
  const [archiveLocations, setArchiveLocations] = useState<Record<string, LibraryLocation>>({});
  const [users, setUsers] = useState<UserQuota[]>([]);
  const [quotaDrafts, setQuotaDrafts] = useState<Record<string, string>>({});
  const [settings, setSettings] = useState<UserSettings>({ autoplay: true, hideTechnicalDetails: false, preferBestQuality: true, theme: "system" });
  const [settingsLoaded, setSettingsLoaded] = useState(false);
  const [watchProgress, setWatchProgress] = useState<WatchProgressMap>({});
  const watchProgressRef = useRef(watchProgress);
  watchProgressRef.current = watchProgress;
  const progressRequestRef = useRef<Promise<void> | null>(null);
  const autoplayRef = useRef(settings.autoplay);
  autoplayRef.current = settings.autoplay;
  const [sortMode, setSortMode] = useState("size:desc");
  const [videoElement, setVideoElement] = useState<HTMLVideoElement | null>(null);
  const [playerReady, setPlayerReady] = useState(false);
  const [mediaReady, setMediaReady] = useState(false);
  const [theater, setTheater] = useState(false);
  const [qualityTracks, setQualityTracks] = useState<QualityTrack[]>([]);
  const [selectedQuality, setSelectedQuality] = useState<"best" | "auto" | number>("best");
  const playerRef = useRef<shaka.Player | null>(null);
  const controlsReady = playerReady || mediaReady;
  const authenticated = session !== null;
  const watchId = location.path === "/watch" ? location.query.get("v") : null;
  const requestedStart = watchId ? parseTimecode(location.query.get("t")) : null;
  const currentPage = pageNumber(location.query.get("page"));
  const libraryScope = location.path === "/library" ? location.query.get("scope") || "own" : "own";
  const metadataSupported = Boolean(watchId) || location.path === "/playlist"
    || location.path.startsWith("/channel/") || location.path.startsWith("/c/") || location.path.startsWith("/@");
  const metadataKey = watchId ? `video:${watchId}` : location.path === "/playlist"
    ? `playlist:${location.query.get("list")}` : location.path;
  const metadataSession = useMemo(() => `metadata-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`,
    [location]);
  useEffect(() => setChannelTab(location.query.get("tab") === "playlists" ? "playlists" : "videos"), [metadataKey]);
  const playbackSession = useMemo(() => `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`,
    [watchId, requestedStart]);
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
  const lastControlsPointerRef = useRef(0);
  const [controlsOverlayVisible, setControlsOverlayVisible] = useState(false);
  const [playbackPosition, setPlaybackPosition] = useState(0);
  const [seekEffect, setSeekEffect] = useState<{ direction: "left" | "right"; position: number;
    duration: number; id: number; leaving: boolean } | null>(null);
  const seekEffectTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

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
      if (data.some(item => typeof item === "string" && item.includes("\"code\":\"ANTIBOT\""))) {
        setActions(ANTIBOT_ACTIONS);
      }
      const details = data.length > 0
        ? ` · ${data.slice(0, 3).map(item => item instanceof Error ? `${item.name}: ${item.message}` : String(item)).join(" · ").slice(0, 500)}`
        : "";
      setError(`Плеер: ${String(reason.message)}${details}`);
    } else if (reason instanceof Error) {
      setError(reason.message);
    } else {
      setError(String(reason));
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
  const metadataLogs = usePlaybackLogs(null, metadataSession,
    Boolean(session) && !archiveFile && metadataSupported, apiFetch, "metadata");

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

  const selectChannelTab = useCallback((tab: "videos" | "playlists") => {
    setChannelTab(tab);
    const params = new URLSearchParams(window.location.search);
    params.set("tab", tab);
    window.history.replaceState({}, "", `${window.location.pathname}?${params.toString()}`);
  }, []);

  const rememberPage = useCallback((key: string, page: number, cursor: string | null, nextCursor: string | null) => {
    const pages = { ...(pageCursors.current[key] || {}), [page]: cursor };
    if (nextCursor) pages[page + 1] = nextCursor;
    else delete pages[page + 1];
    pageCursors.current = { ...pageCursors.current, [key]: pages };
  }, []);

  const canOpenPage = useCallback((page: number) => page === 1
    || Boolean(pageCursors.current[metadataKey]?.[page]), [metadataKey]);

  const openPage = useCallback((page: number) => {
    if (!canOpenPage(page)) return;
    const params = new URLSearchParams(location.query);
    const cursor = page === 1 ? null : pageCursors.current[metadataKey]?.[page];
    if (page === 1) params.delete("page");
    else params.set("page", String(page));
    if (cursor) params.set("cursor", cursor);
    else params.delete("cursor");
    navigate(`${location.path}?${params.toString()}`);
  }, [location, metadataKey, canOpenPage, navigate]);

  useEffect(() => {
    const listener = () => setLocation(readLocation());
    window.addEventListener("popstate", listener);
    return () => window.removeEventListener("popstate", listener);
  }, []);

  const openArchivedVideo = useCallback((videoId: string) => {
    const location = archiveLocations[videoId];
    if (!location) return;
    const params = new URLSearchParams({ scope: "global", channel: location.channelId,
      playlist: location.playlistId || "none", video: videoId });
    navigate(`/library?${params.toString()}`);
  }, [archiveLocations, navigate]);

  const loadLibrary = useCallback(async () => {
    const [sort, direction] = sortMode.split(":");
    const response = await apiFetch(`/api/v1/library?sort=${sort}&direction=${direction}&scope=${encodeURIComponent(libraryScope)}`);
    setLibrary(await response.json());
  }, [apiFetch, sortMode, libraryScope]);

  const loadUsers = useCallback(async () => {
    const response = await apiFetch("/api/v1/admin/users");
    const data = await response.json() as UserQuota[];
    setUsers(data);
    setQuotaDrafts(Object.fromEntries(data.map(user => [user.username, String(Math.round(user.effectiveQuotaBytes / 1024 / 1024 / 1024))])));
  }, [apiFetch]);

  useEffect(() => {
    if (!session) {
      setSettingsLoaded(false);
      setWatchProgress({});
      setArchiveLocations({});
      progressRequestRef.current = null;
      return;
    }
    progressRequestRef.current = Promise.allSettled([
      apiFetch("/api/v1/users/me/settings"),
      apiFetch("/api/v1/library/progress"),
      apiFetch("/api/v1/library/locations?scope=global"),
    ]).then(async ([settingsResult, progressResult, locationsResult]) => {
      if (settingsResult.status === "rejected") throw settingsResult.reason;
      const loadedSettings = await settingsResult.value.json() as UserSettings;
      autoplayRef.current = loadedSettings.autoplay;
      setSettings(loadedSettings);
      if (progressResult.status === "fulfilled") {
        const entries = await progressResult.value.json() as WatchProgress[];
        setWatchProgress(Object.fromEntries(entries.map(entry => [entry.videoId, entry])));
      }
      if (locationsResult.status === "fulfilled") {
        const locations = await locationsResult.value.json() as LibraryLocation[];
        const map: Record<string, LibraryLocation> = {};
        for (const location of locations) {
          if (!map[location.videoId]) map[location.videoId] = location;
        }
        setArchiveLocations(map);
      } else {
        showError(locationsResult.reason);
      }
      setSettingsLoaded(true);
    }).catch(reason => {
      setSettingsLoaded(true);
      showError(reason);
    });
  }, [session, apiFetch, showError]);

  useEffect(() => {
    const media = window.matchMedia("(prefers-color-scheme: light)");
    const apply = () => {
      document.documentElement.dataset.theme = settings.theme === "system"
        ? media.matches ? "light" : "dark" : settings.theme;
    };
    apply();
    media.addEventListener("change", apply);
    return () => media.removeEventListener("change", apply);
  }, [settings.theme]);

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

  const avatarUrl = avatarObjectUrl || animalAvatar(session?.username || "guest");

  useEffect(() => {
    if (!session) {
      setAvatarObjectUrl(null);
      return;
    }
    let cancelled = false;
    apiFetch("/api/v1/users/me/avatar").then(async response => {
      const blob = await response.blob();
      if (cancelled) return;
      setAvatarObjectUrl(previous => {
        if (previous) URL.revokeObjectURL(previous);
        return URL.createObjectURL(blob);
      });
    }).catch(() => setAvatarObjectUrl(null));
    return () => {
      cancelled = true;
      setAvatarObjectUrl(previous => {
        if (previous) URL.revokeObjectURL(previous);
        return null;
      });
    };
  }, [session, avatarVersion, apiFetch]);

  const uploadAvatar = async (file: File) => {
    setError(null);
    try {
      if (file.size > 200 * 1024) throw new RequestError("Аватар должен быть не больше 200 КиБ");
      const bitmap = await createImageBitmap(file);
      if (bitmap.width > 256 || bitmap.height > 256) throw new RequestError("Аватар должен быть не больше 256×256");
      bitmap.close();
      await apiFetch("/api/v1/users/me/avatar", {
        method: "PUT", headers: { "Content-Type": file.type }, body: file,
      });
      setAvatarVersion(current => current + 1);
    } catch (reason) {
      showError(reason);
    }
  };

  const generateInvite = async () => {
    setError(null);
    try {
      const response = await apiFetch("/api/v1/invites", { method: "POST" });
      setInvite(await response.json());
    } catch (reason) {
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
        const youtubeRequest = apiFetch(`/api/v1/videos/${id}?logSession=${encodeURIComponent(metadataSession)}`);
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
        const cursor = currentPage === 1 ? null : query.get("cursor");
        const params = new URLSearchParams({ logSession: metadataSession });
        if (cursor) params.set("cursor", cursor);
        const response = await apiFetch(`/api/v1/playlists/${id}?${params.toString()}`);
        const data = await response.json() as Playlist;
        rememberPage(metadataKey, currentPage, cursor, data.nextCursor);
        setPlaylist(data);
      } else if (path.startsWith("/channel/") || path.startsWith("/c/") || path.startsWith("/@")) {
        const id = path.startsWith("/channel/")
          ? path.substring("/channel/".length)
          : path.startsWith("/c/") ? path.substring(3) : path.substring(1);
        const cursor = currentPage === 1 ? null : query.get("cursor");
        const params = new URLSearchParams({ logSession: metadataSession });
        if (cursor) params.set("cursor", cursor);
        const response = await apiFetch(`/api/v1/channels/${encodeURIComponent(id)}?${params.toString()}`);
        const data = await response.json() as ChannelPage;
        rememberPage(metadataKey, currentPage, cursor, data.nextCursor);
        setChannel(data);
      } else if (path === "/search") {
        const q = (query.get("q") || "").trim();
        const type = (query.get("type") || "all") as HeaderSearchType;
        const cursor = query.get("cursor");
        setSearchQuery(q);
        setSearchType(type);
        setSearchNextCursor(null);
        if (q) {
          setSearchMessage(type === "archive" ? "Поиск в вашем архиве…" : "Поиск по видео, каналам и плейлистам…");
          if (type === "archive") {
            const response = await apiFetch("/api/v1/library?scope=own");
            const archive = await response.json() as LibraryResponse;
            const normalized = q.toLocaleLowerCase();
            const items = archive.channels.flatMap(group => group.videos)
              .filter(item => item.title.toLocaleLowerCase().includes(normalized))
              .map(item => ({ kind: "video" as const, id: item.videoId, title: item.title,
                url: `/watch?v=${item.videoId}`, thumbnailUrl: item.thumbnailUrl, channelName: null,
                durationSeconds: item.durationSeconds, videoCount: null }));
            setResults(items);
            setSearchMessage(`В архиве найдено: ${items.length}`);
          } else if (type === "all") {
            const id = youtubeVideoId(q);
            const suffix = cursor ? `&cursor=${encodeURIComponent(cursor)}` : "";
            const [videosResult, channelsResult, playlistsResult, directResult] = await Promise.allSettled([
              apiFetch(`/api/v1/search?q=${encodeURIComponent(q)}&type=videos${suffix}`),
              apiFetch(`/api/v1/search?q=${encodeURIComponent(q)}&type=channels${suffix}`),
              apiFetch(`/api/v1/search?q=${encodeURIComponent(q)}&type=playlists${suffix}`),
              id ? apiFetch(`/api/v1/videos/${id}`).catch(() => null) : Promise.resolve(null),
            ]);
            const readItems = async (result: PromiseSettledResult<Response>) => {
              if (result.status !== "fulfilled") return { items: [] as SearchItem[], nextCursor: null };
              const data = await result.value.json() as SearchResults;
              return { items: data.items, nextCursor: data.nextCursor };
            };
            const [videoData, channelData, playlistData] = await Promise.all([
              readItems(videosResult), readItems(channelsResult), readItems(playlistsResult),
            ]);
            const direct = directResult.status === "fulfilled" && directResult.value
              ? videoSearchItem(await directResult.value.json() as Video) : null;
            const merged = [...direct ? [direct] : [], ...videoData.items, ...channelData.items, ...playlistData.items];
            const unique = Array.from(new Map(merged.map(item => [`${item.kind}:${item.id}`, item])).values());
            setResults(unique);
            setSearchNextCursor(videoData.nextCursor);
            setSearchMessage(`Найдено: ${unique.length}`);
          } else {
            const response = await apiFetch(`/api/v1/search?q=${encodeURIComponent(q)}&type=${type}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ""}`);
            const data = await response.json() as SearchResults;
            setResults(data.items);
            setSearchNextCursor(data.nextCursor);
            setSearchMessage(`Найдено: ${data.items.length}`);
          }
        }
      } else if (path === "/library" || path === "/") {
        await loadLibrary();
      } else if (path === "/admin") {
        await loadUsers();
      }
    };
    load().catch(showError).finally(() => setLoading(false));
  }, [location, authenticated, apiFetch, loadLibrary, loadUsers, showError, applyArchive,
    metadataSession, currentPage, metadataKey, rememberPage]);

  useEffect(() => {
    setTheater(false);
    setControlsOverlayVisible(false);
    setPlaybackPosition(0);
    setSelectedQuality(settings.preferBestQuality ? "best" : "auto");
    setQualityTracks([]);
    playerRef.current = null;
  }, [watchId]);

  useEffect(() => {
    if (!watchId || !videoElement || !archiveChecked || !settingsLoaded) return;
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
      playerRef.current = player;
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
      if (requestedStart !== null && requestedStart < videoElement.duration) {
        videoElement.currentTime = requestedStart;
      } else if (resume && resume.positionSeconds > 0 && resume.positionSeconds < videoElement.duration - 1) {
        videoElement.currentTime = resume.positionSeconds;
      }
      player.configure("abr.enabled", selectedQuality === "auto");
      const tracks = player.getVariantTracks().filter(track => (track.height || 0) > 0)
        .sort((left, right) => (right.height || 0) - (left.height || 0) || right.bandwidth - left.bandwidth);
      setQualityTracks(tracks);
      if (selectedQuality === "best" && tracks.length > 0) {
        player.selectVariantTrack(tracks[0], true);
      } else if (typeof selectedQuality === "number") {
        const selected = tracks.find(track => track.id === selectedQuality);
        if (selected) player.selectVariantTrack(selected, true);
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
      playerRef.current = null;
    };
  }, [watchId, videoElement, ensureAccessToken, showError, playbackSession, archiveChecked, playbackVersion, requestedStart, settingsLoaded]);

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
      const response = await fetch(`${API_BASE_URL}/api/v1/auth/${registration ? "register" : "login"}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(registration ? { invite: inviteCode, username, password } : { username, password }),
      });
      if (!response.ok) throw await responseError(response);
      acceptTokens((await response.json()) as TokenPair, username);
      setPassword("");
    } catch (reason) {
      showError(reason);
    }
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

  const playlistBack = () => {
    if (!playlist) return;
    const from = location.query.get("from");
    if (from?.startsWith("/")) {
      const target = new URL(from, window.location.origin);
      if (target.pathname.startsWith("/channel/") || target.pathname.startsWith("/c/") || target.pathname.startsWith("/@")) {
        target.searchParams.set("tab", "playlists");
        navigate(`${target.pathname}?${target.searchParams.toString()}`);
        return;
      }
    }
    navigate(`${playlist.channel.url}?tab=playlists`);
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

  const selectQuality = (value: string) => {
    const player = playerRef.current;
    if (!player) return;
    if (value === "auto") {
      setSelectedQuality("auto");
      player.configure("abr.enabled", true);
      return;
    }
    player.configure("abr.enabled", false);
    if (value === "best") {
      setSelectedQuality("best");
      if (qualityTracks.length > 0) player.selectVariantTrack(qualityTracks[0], true);
      return;
    }
    const id = Number(value);
    const track = qualityTracks.find(candidate => candidate.id === id);
    if (track) {
      setSelectedQuality(id);
      player.selectVariantTrack(track, true);
    }
  };

  const controlsClickTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const singleClickControls = useCallback(() => {
    if (controlsClickTimer.current) clearTimeout(controlsClickTimer.current);
    controlsClickTimer.current = setTimeout(() => {
      controlsClickTimer.current = null;
      setControlsOverlayVisible(current => !current);
    }, 300);
  }, []);

  const cancelControlsClick = useCallback(() => {
    if (controlsClickTimer.current) clearTimeout(controlsClickTimer.current);
    controlsClickTimer.current = null;
  }, []);

  const showSeekEffect = useCallback((offset: number) => {
    if (!videoElement) return;
    if (seekEffectTimer.current) clearTimeout(seekEffectTimer.current);
    setSeekEffect({ direction: offset < 0 ? "left" : "right", position: videoElement.currentTime,
      duration: videoElement.duration, id: performance.now(), leaving: false });
    seekEffectTimer.current = setTimeout(() => {
      setSeekEffect(current => current ? { ...current, leaving: true } : null);
      seekEffectTimer.current = setTimeout(() => setSeekEffect(null), 300);
    }, 700);
  }, [videoElement]);

  useEffect(() => () => {
    if (seekEffectTimer.current) clearTimeout(seekEffectTimer.current);
    if (controlsClickTimer.current) clearTimeout(controlsClickTimer.current);
  }, []);

  const seekTo = useCallback((position: number) => {
    if (!videoElement) return;
    videoElement.currentTime = position;
    setPlaybackPosition(position);
  }, [videoElement]);

  const seekFromPointer = useCallback((clientX: number) => {
    if (!videoElement || !controlsReady || !Number.isFinite(videoElement.duration)) return;
    const bounds = videoElement.getBoundingClientRect();
    const offset = clientX < bounds.left + bounds.width / 2 ? -10 : 10;
    seekTo(Math.max(0, Math.min(videoElement.duration, videoElement.currentTime + offset)));
    showSeekEffect(offset);
  }, [videoElement, controlsReady, seekTo, showSeekEffect]);

  const doubleClickSeek = useCallback((event: ReactMouseEvent<HTMLDivElement>) => {
    event.preventDefault();
    event.stopPropagation();
    cancelControlsClick();
    if (Date.now() - lastTouchSeekRef.current < 500) return;
    seekFromPointer(event.clientX);
  }, [cancelControlsClick, seekFromPointer]);

  const seekPointerUp = useCallback((event: ReactPointerEvent<HTMLDivElement>) => {
    if (!event.isPrimary || event.button > 0) return;
    lastControlsPointerRef.current = Date.now();
    if (event.pointerType !== "touch") {
      if (controlsReady) singleClickControls();
      return;
    }
    if (!controlsReady) {
      lastTouchTapRef.current = null;
      return;
    }
    const previous = lastTouchTapRef.current;
    if (previous && event.timeStamp - previous.timeStamp <= 350
      && Math.abs(event.clientX - previous.x) <= 40 && Math.abs(event.clientY - previous.y) <= 40) {
      event.preventDefault();
      cancelControlsClick();
      lastTouchSeekRef.current = Date.now();
      lastTouchTapRef.current = null;
      seekFromPointer(event.clientX);
    } else {
      lastTouchTapRef.current = { timeStamp: event.timeStamp, x: event.clientX, y: event.clientY };
      singleClickControls();
    }
  }, [controlsReady, cancelControlsClick, singleClickControls, seekFromPointer]);

  const dismissJob = useCallback(async (job: QueueJob) => {
    setError(null);
    try {
      await apiFetch(`/api/v1/archive/jobs/${job.id}`, { method: "DELETE" });
    } catch (reason) {
      showError(reason);
    }
  }, [apiFetch, showError]);

  const clearCompleted = useCallback(async () => {
    setError(null);
    try {
      await apiFetch("/api/v1/archive/jobs", { method: "DELETE" });
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
      <main onAuxClickCapture={auxiliaryNavigation}>
        <header><h1>SpankTube</h1><p>{registration ? "Регистрация по инвайту" : "Вход в личный прокси YouTube"}</p></header>
        <section className="panel auth">
          <form onSubmit={login}>
            {registration && <input value={inviteCode} onChange={event => setInviteCode(event.target.value.trim())}
              placeholder="Код приглашения" aria-label="Код приглашения" required />}
            <input value={username} onChange={event => setUsername(event.target.value)} autoComplete="username" aria-label="Логин" required />
            <input type="password" value={password} onChange={event => setPassword(event.target.value)} autoComplete={registration ? "new-password" : "current-password"} placeholder="Пароль" aria-label="Пароль" required />
            <button type="submit">{registration ? "Зарегистрироваться" : "Войти"}</button>
          </form>
          <button className="link" onClick={() => { setRegistration(current => !current); setError(null); }}>
            {registration ? "Вернуться ко входу" : "Регистрация по инвайту"}
          </button>
        </section>
        {error && <section className="error" role="alert"><strong>{error}</strong></section>}
      </main>
    );
  }

  return (
    <main onAuxClickCapture={auxiliaryNavigation}>
      <AppHeader searchQuery={searchQuery} searchType={searchType} onQueryChange={setSearchQuery}
        onTypeChange={setSearchType} onSearch={search} onNavigate={navigate}
        onQueue={anchor => togglePopup("queue", anchor)} onQueuePage={() => navigate("/jobs")}
        onUser={anchor => togglePopup("user", anchor)} avatarUrl={avatarUrl} queueButtonRef={queueButtonRef} />

      {location.path === "/" && library && <>
        <h2>Рекомендации из архива</h2>
        <LibraryView library={library} query={new URLSearchParams()} role={session.role}
          navigate={navigate} apiFetch={apiFetch} sortMode={sortMode} onSortModeChange={setSortMode}
          onDownload={item => void downloadBlob(item.downloadUrl, item.filename)} onRemove={remove}
          hideTechnicalDetails={settings.hideTechnicalDetails} progressMap={watchProgress} />
      </>}

      {error && (
        <section className="error" role="alert">
          <button type="button" className="error-close" aria-label="Закрыть сообщение об ошибке" title="Закрыть"
            onClick={() => { setError(null); setActions([]); }}>×</button>
          <strong>{error}</strong>
          {(actions.length > 0 || error.includes("\"code\":\"ANTIBOT\"")) && <ul>
            {(actions.length > 0 ? actions : ANTIBOT_ACTIONS).map(action => <li key={action}>{action}</li>)}
          </ul>}
        </section>
      )}
      {loading && !archiveFile && metadataSupported && location.path !== "/watch" && <div className="metadata-loading panel">
        <PlaybackLog lines={metadataLogs} overlay={false} />
      </div>}
      {loading && !archiveFile && !metadataSupported && <p className="muted">Загрузка…</p>}

      {location.path === "/search" && <>
        {searchMessage && <p className="muted" role="status">{searchMessage}</p>}
        <ResultGrid items={results} navigate={navigate} progressMap={watchProgress} archiveLocations={archiveLocations} onArchiveOpen={openArchivedVideo} />
        {searchNextCursor && <button className="secondary" disabled={loading} onClick={() => navigate(`/search?q=${encodeURIComponent(searchQuery)}&type=${searchType}&cursor=${encodeURIComponent(searchNextCursor)}`)}>Следующая страница</button>}
      </>}

      {watchId && (
        <article>
          <section className={theater ? "player panel theater-panel" : "player panel"}>
            <div className={theater ? "video-frame theater" : "video-frame"}
              onMouseDownCapture={event => { if (event.detail > 1) event.preventDefault(); }}>
              <video ref={setVideoElement} playsInline
                aria-label="Видеоплеер. Двойной клик слева — на 10 секунд назад, справа — вперёд"
                onLoadedMetadata={() => {
                  syncMediaReady();
                  setPlaybackPosition(videoElement?.currentTime || 0);
                }}
                onTimeUpdate={event => setPlaybackPosition(event.currentTarget.currentTime)}
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
                  setControlsOverlayVisible(true);
                  setPlaybackStatus("Пауза");
                  void reportProgress();
                }} />
              {controlsReady && <div className="seek-gestures" aria-hidden="true"
                onClick={event => {
                  if (event.detail <= 1 && Date.now() - lastTouchSeekRef.current >= 500
                    && Date.now() - lastControlsPointerRef.current >= 500) singleClickControls();
                }}
                onDoubleClickCapture={doubleClickSeek} onPointerUp={seekPointerUp} />}
              {seekEffect && <div key={seekEffect.id} className={`seek-effect ${seekEffect.direction}${seekEffect.leaving ? " leaving" : ""}`} aria-hidden="true">
                <div className="seek-effect-circle" />
                <div className="seek-effect-arrows">{[0, 1, 2].map(index => <span key={index} style={{ animationDelay: `${index * 0.15}s` }}>
                  {seekEffect.direction === "left" ? "◀" : "▶"}
                </span>)}</div>
                <div className="seek-effect-label">10 секунд</div>
                <div className="seek-effect-time">{formatDuration(Math.round(seekEffect.position))} / {formatDuration(Math.round(seekEffect.duration))}</div>
              </div>}
              {videoElement && controlsOverlayVisible && controlsReady && <PlayerControls
                video={videoElement} player={playerRef.current} position={playbackPosition}
                sponsorSegments={video?.sponsorBlock || []} autoplay={settings.autoplay}
                qualityTracks={qualityTracks} selectedQuality={selectedQuality} theater={theater}
                onTogglePlayback={togglePlayback} onSeek={seekTo} onSelectQuality={selectQuality}
                onToggleAutoplay={() => void updateSettings({ autoplay: !settings.autoplay })}
                onToggleTheater={() => setTheater(current => !current)} onError={showError} />}
              {!controlsReady && <PlaybackLog lines={loading && !archiveFile && metadataLogs.length > 0
                ? metadataLogs : playbackLogs} />}
            </div>
            <div className="playback">
              {video && <>
                {archiveFile
                  ? <button type="button" className="secondary" onClick={() => void downloadBlob(archiveFile.downloadUrl, archiveFile.filename)}>Скачать из архива</button>
                  : <button type="button" className="secondary" onClick={archive}>Архивировать</button>}
                {archiveFile && <button type="button" className="secondary" onClick={event => void openHistory(event.currentTarget)}>История <span className="split-arrow-icon">▼</span></button>}
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
              <button className="link" data-nav-url={video.channelUrl} onClick={() => navigate(video.channelUrl)}>{video.channelName}</button>
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
          <div className="playlist-channel-line">
            <button className="secondary icon-button" aria-label="Назад к плейлистам канала" title="Назад" onClick={playlistBack}>
              <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m15 18-6-6 6-6" /></svg>
            </button>
            {playlist.channel.avatarUrl && <img className="avatar" src={playlist.channel.avatarUrl} alt="" />}
            <button className="link" data-nav-url={playlist.channel.url} onClick={() => navigate(playlist.channel.url)}>{playlist.channel.title}</button>
          </div>
          <div className="playlist-heading"><h2>{playlist.title}</h2>
            <button className="secondary" onClick={() => saveCollection(`/api/v1/playlists/${playlist.playlistId}/save`)}>Архивировать</button>
          </div>
          <ResultGrid items={playlist.items} navigate={navigate} progressMap={watchProgress} archiveLocations={archiveLocations} onArchiveOpen={openArchivedVideo} />
          <Pagination page={currentPage} totalPages={playlist.totalPages} hasNext={Boolean(playlist.nextCursor)}
            loading={loading} canOpen={canOpenPage} onOpen={openPage} />
        </section>
      )}

      {(location.path.startsWith("/channel/") || location.path.startsWith("/c/") || location.path.startsWith("/@")) && channel && (
        <section className="panel metadata">
          <div className="channel-heading">
            <div className="channel-line">
              <img className="avatar large" src={channel.avatarUrl} alt="" />
              <div className="channel-title"><h2>{channel.title}</h2><p className="muted">{channel.subscriberCount == null ? "" : `${number.format(channel.subscriberCount)} подписчиков`}</p></div>
            </div>
            <button className="secondary channel-archive" onClick={() => saveCollection(`/api/v1/channels/${channel.channelId}/save`)}>Архивировать канал</button>
          </div>
          <div className="channel-tabs" role="tablist" aria-label="Содержимое канала">
            <button role="tab" className={channelTab === "videos" ? "channel-tab active" : "channel-tab"}
              aria-selected={channelTab === "videos"} onClick={() => selectChannelTab("videos")}>Видео</button>
            <button role="tab" className={channelTab === "playlists" ? "channel-tab active" : "channel-tab"}
              aria-selected={channelTab === "playlists"} onClick={() => selectChannelTab("playlists")}>Плейлисты</button>
          </div>
          {channelTab === "videos" ? <>
            <ResultGrid items={channel.videos} navigate={navigate} progressMap={watchProgress} archiveLocations={archiveLocations} onArchiveOpen={openArchivedVideo} />
            <Pagination page={currentPage} totalPages={channel.totalPages} hasNext={Boolean(channel.nextCursor)}
              loading={loading} canOpen={canOpenPage} onOpen={openPage} />
          </> : <ResultGrid items={channel.playlists} navigate={navigate} progressMap={watchProgress} archiveLocations={archiveLocations} onArchiveOpen={openArchivedVideo} />}
        </section>
      )}

      {location.path === "/library" && library && <LibraryView library={library} query={location.query}
        role={session.role} navigate={navigate} apiFetch={apiFetch} sortMode={sortMode}
        onSortModeChange={setSortMode} onDownload={item => void downloadBlob(item.downloadUrl, item.filename)}
        onRemove={remove} hideTechnicalDetails={settings.hideTechnicalDetails} progressMap={watchProgress} />}

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
        {popup === "queue" && <QueuePanel jobs={jobs} beforeYou={beforeYou} onDownload={downloadJob} onDismiss={dismissJob}
          onNavigate={url => navigate(url, true)} onClose={() => setPopup(null)} onClear={clearCompleted} compact />}
        {popup === "download" && video && <DownloadMenu formats={video.formats} onDownload={download} onClose={() => setPopup(null)} />}
        {popup === "settings" && <SettingsMenu settings={settings} onChange={updateSettings} onClose={() => setPopup(null)}
          avatarUrl={avatarUrl} onAvatarUpload={uploadAvatar} isAdmin={session.role === "ADMIN"}
          invite={invite} onGenerateInvite={generateInvite} />}
        {popup === "user" && <UserMenu username={session.username} role={session.role} avatarUrl={avatarUrl}
          settings={settings} onChange={updateSettings} onLogout={() => saveSession(null)} onClose={() => setPopup(null)}
          onNavigate={url => {
            if (url === "/settings") setPopup("settings");
            else navigate(url);
          }} />}
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
  if (!progress || progress.positionSeconds <= 0) return null;
  const percent = Math.max(0, Math.min(100, progress.watchPercent));
  return <progress className="watch-progress" max={100} value={percent} aria-label={`Прогресс просмотра: ${Math.round(percent)}%`} />;
}

function withPlaylistOrigin(item: SearchItem): string {
  if (item.kind !== "playlist") return item.url;
  const from = `${window.location.pathname}${window.location.search}`;
  return `${item.url}&from=${encodeURIComponent(from)}`;
}

function ResultGrid({ items, navigate, progressMap, archiveLocations, onArchiveOpen }: {
  items: SearchItem[];
  navigate: (url: string) => void;
  progressMap: WatchProgressMap;
  archiveLocations: Record<string, LibraryLocation>;
  onArchiveOpen: (videoId: string) => void;
}) {
  return (
    <div className="result-grid">
      {items.map(item => (
        <button className="result-card" key={`${item.kind}:${item.id}`} data-nav-url={withPlaylistOrigin(item)}
          onClick={() => navigate(withPlaylistOrigin(item))}>
          <div className="result-thumbnail">
            {item.thumbnailUrl && <img src={item.thumbnailUrl} alt="" loading="lazy" onError={event => { event.currentTarget.style.visibility = "hidden"; }} />}
            <WatchProgressBar progress={progressMap[item.id]} />
            {archiveLocations[item.id] && <span className="archive-badge" role="button" tabIndex={0}
              aria-label="Открыть видео в архиве" title="В архиве"
              onClick={event => { event.stopPropagation(); onArchiveOpen(item.id); }}
              onKeyDown={event => {
                if (event.key === "Enter" || event.key === " ") {
                  event.preventDefault();
                  event.stopPropagation();
                  onArchiveOpen(item.id);
                }
              }}>
              <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7h16v13H4zM3 3h18v4H3zm5 8h8" /></svg>
            </span>}
          </div>
          <div className="result-kind-row"><span className="result-kind">{item.kind}</span>{item.durationSeconds != null && <span className="result-duration">{formatDuration(item.durationSeconds)}</span>}</div>
          <strong>{item.title}</strong>
          <span className="muted">{item.channelName || ""}{item.videoCount != null ? ` · ${item.videoCount} видео` : ""}</span>
        </button>
      ))}
    </div>
  );
}
