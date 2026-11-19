import { MouseEvent as ReactMouseEvent, useEffect, useRef, useState } from "react";
import shaka from "shaka-player/dist/shaka-player.compiled.js";
import { PlayerControls } from "./PlayerControls";
import { SubtitleDisplayer } from "./SubtitleDisplayer";
import type { ApiFetch, UserSettings } from "./types";

type FileEntry = {
  id: string;
  name: string;
  path: string;
  kind: "DIRECTORY" | "VIDEO";
  sizeBytes: number | null;
  manifestUrl: string | null;
};

type Directory = { path: string; parentPath: string | null; entries: FileEntry[] };
type QualityTrack = ReturnType<shaka.Player["getVariantTracks"]>[number];

const MEDIA_BASE_URL = (import.meta.env.VITE_MEDIA_BASE_URL || import.meta.env.VITE_API_BASE_URL || "").replace(/\/$/, "");

function fileUrl(path: string): string {
  if (!path) return "/files";
  return `/files/${path.split("/").map(segment => encodeURIComponent(segment)).join("/")}`;
}

function parentPath(path: string): string {
  const separator = path.lastIndexOf("/");
  return separator < 0 ? "" : path.substring(0, separator);
}

function formatSize(bytes: number): string {
  if (bytes >= 1024 ** 3) return `${(bytes / 1024 ** 3).toFixed(2)} ГиБ`;
  if (bytes >= 1024 ** 2) return `${(bytes / 1024 ** 2).toFixed(1)} МиБ`;
  return `${Math.max(1, Math.round(bytes / 1024))} КиБ`;
}

export function FileBrowser({ apiFetch, ensureAccessToken, settings, settingsLoaded, routePath, onNavigate, onSettingsChange, onError }: {
  apiFetch: ApiFetch;
  ensureAccessToken: () => Promise<string>;
  settings: UserSettings;
  settingsLoaded: boolean;
  routePath: string;
  onNavigate: (url: string) => void;
  onSettingsChange: (patch: Partial<UserSettings>) => void;
  onError: (reason: unknown) => void;
}) {
  const [path, setPath] = useState(routePath);
  const [directory, setDirectory] = useState<Directory | null>(null);
  const [loading, setLoading] = useState(true);
  const [selected, setSelected] = useState<FileEntry | null>(null);
  const [videoElement, setVideoElement] = useState<HTMLVideoElement | null>(null);
  const [player, setPlayer] = useState<shaka.Player | null>(null);
  const playerRef = useRef<shaka.Player | null>(null);
  const destroyRef = useRef<Promise<void> | null>(null);
  const [ready, setReady] = useState(false);
  const [status, setStatus] = useState("Выберите видеофайл");
  const [position, setPosition] = useState(0);
  const [qualityTracks, setQualityTracks] = useState<QualityTrack[]>([]);
  const [selectedQuality, setSelectedQuality] = useState<"best" | "auto" | number>("auto");
  const [playbackRate, setPlaybackRate] = useState(1);
  const [theater, setTheater] = useState(false);
  const autoplayRef = useRef(settings.autoplay);
  autoplayRef.current = settings.autoplay;

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setDirectory(null);
    setSelected(null);
    const load = async () => {
      const entryResponse = await apiFetch(`/api/v1/files/entry?path=${encodeURIComponent(routePath)}`);
      const entry = await entryResponse.json() as FileEntry;
      const currentPath = entry.kind === "VIDEO" ? parentPath(entry.path) : entry.path;
      if (cancelled) return;
      setPath(currentPath);
      setSelected(entry.kind === "VIDEO" ? entry : null);
      const directoryResponse = await apiFetch(`/api/v1/files?path=${encodeURIComponent(currentPath)}`);
      const result = await directoryResponse.json() as Directory;
      if (!cancelled) setDirectory(result);
    };
    load()
      .catch(reason => {
        if (!cancelled) onError(reason);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => { cancelled = true; };
  }, [apiFetch, routePath, onError]);

  useEffect(() => {
    if (!selected?.manifestUrl || !videoElement || !settingsLoaded) return;
    let disposed = false;
    let instance: shaka.Player | null = null;
    setReady(false);
    setPlayer(null);
    setPosition(0);
    setTheater(false);
    setSelectedQuality("auto");
    setQualityTracks([]);
    setStatus("Подготовка видеофайла…");
    const start = async () => {
      shaka.polyfill.installAll();
      if (!shaka.Player.isBrowserSupported()) throw new Error("Этот браузер не поддерживается Shaka Player");
      if (destroyRef.current) {
        const previous = destroyRef.current;
        destroyRef.current = null;
        await previous;
      }
      instance = new shaka.Player();
      playerRef.current = instance;
      instance.setVideoContainer(videoElement.parentElement);
      instance.configure("textDisplayFactory", () => new SubtitleDisplayer(videoElement, videoElement.parentElement));
      await instance.attach(videoElement);
      instance.configure("streaming.retryParameters.timeout", 120_000);
      instance.configure("streaming.retryParameters.connectionTimeout", 60_000);
      instance.configure("streaming.retryParameters.stallTimeout", 30_000);
      instance.configure("manifest.retryParameters.timeout", 5 * 60_000);
      instance.configure("manifest.retryParameters.connectionTimeout", 60_000);
      const networking = instance.getNetworkingEngine();
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
      instance.addEventListener("error", event => onError((event as CustomEvent<unknown>).detail));
      await instance.load(`${MEDIA_BASE_URL}${selected.manifestUrl}`);
      if (disposed) {
        await instance.destroy();
        return;
      }
      const tracks = instance.getVariantTracks().filter(track => (track.height || 0) > 0)
        .sort((left, right) => (right.height || 0) - (left.height || 0) || right.bandwidth - left.bandwidth);
      setQualityTracks(tracks);
      setPlayer(instance);
      setReady(true);
      setStatus("Готово к воспроизведению");
      if (autoplayRef.current) {
        try {
          await videoElement.play();
        } catch (reason) {
          if (reason instanceof DOMException && reason.name === "NotAllowedError") {
            setStatus("Нажмите «Играть»");
          } else {
            onError(reason);
          }
        }
      }
    };
    start().catch(reason => {
      if (!disposed) {
        setStatus("Ошибка воспроизведения");
        onError(reason);
      }
    });
    return () => {
      disposed = true;
      setReady(false);
      if (instance) destroyRef.current = instance.destroy();
      playerRef.current = null;
      setPlayer(null);
    };
  }, [selected?.id, selected?.manifestUrl, videoElement, settingsLoaded, ensureAccessToken, onError]);

  useEffect(() => {
    if (!videoElement) return;
    videoElement.defaultPlaybackRate = playbackRate;
    videoElement.playbackRate = playbackRate;
  }, [videoElement, playbackRate]);

  const followLink = (event: ReactMouseEvent<HTMLAnchorElement>, path: string) => {
    if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    event.preventDefault();
    onNavigate(fileUrl(path));
  };

  const togglePlayback = async () => {
    if (!videoElement || !ready) return;
    try {
      if (videoElement.paused) await videoElement.play();
      else videoElement.pause();
    } catch (reason) {
      onError(reason);
    }
  };

  const selectQuality = (value: string) => {
    const current = playerRef.current;
    if (!current) return;
    if (value === "auto") {
      setSelectedQuality("auto");
      current.configure("abr.enabled", true);
      return;
    }
    current.configure("abr.enabled", false);
    const activeAudioId = current.getVariantTracks().find(track => track.active)?.audioId;
    if (value === "best") {
      setSelectedQuality("best");
      const preferred = qualityTracks.find(track => track.audioId === activeAudioId)
        || qualityTracks.find(track => track.audioRoles?.includes("main")) || qualityTracks[0];
      if (preferred) current.selectVariantTrack(preferred, true);
      return;
    }
    const id = Number(value);
    const track = qualityTracks.find(candidate => candidate.id === id && candidate.audioId === activeAudioId)
      || qualityTracks.find(candidate => candidate.id === id);
    if (track) {
      setSelectedQuality(id);
      current.selectVariantTrack(track, true);
    }
  };

  const breadcrumbs = path.split("/").filter(Boolean);
  const breadcrumbPath = (index: number) => breadcrumbs.slice(0, index + 1).join("/");
  const upPath = selected ? path : directory?.parentPath;

  return (
    <article className="local-files">
      <h2>Видеофайлы</h2>
      <p className="muted">NFS-каталог доступен только для чтения. Поддерживаются MKV и MP4; одноимённые SRT, ASS, SSA и VTT подключаются как субтитры.</p>
      {selected && <section className={theater ? "player panel theater-panel" : "player panel"}>
        <div className={theater ? "video-frame theater" : "video-frame"}>
          <video ref={setVideoElement} playsInline aria-label={`Видеофайл ${selected.name}`}
            onClick={() => void togglePlayback()}
            onTimeUpdate={event => setPosition(event.currentTarget.currentTime)}
            onPlaying={() => setStatus("Воспроизведение")}
            onPause={() => setStatus("Пауза")}
            onWaiting={() => setStatus("Буферизация…")} />
          {videoElement && ready && <PlayerControls video={videoElement} player={player} position={position}
            sponsorSegments={[]} autoplay={settings.autoplay} qualityTracks={qualityTracks}
            selectedQuality={selectedQuality} playbackRate={playbackRate} theater={theater}
            onTogglePlayback={() => void togglePlayback()}
            onSeek={value => { videoElement.currentTime = value; setPosition(value); }}
            onSelectQuality={selectQuality} onPlaybackRateChange={setPlaybackRate}
            onToggleAutoplay={() => onSettingsChange({ autoplay: !settings.autoplay })}
            onToggleTheater={() => setTheater(current => !current)} onInteraction={() => {}} onError={onError} />}
          {!ready && <div className="playback-log playback-log-overlay"><div className="playback-log-line">{status}</div></div>}
        </div>
        <div className="playback"><strong>{selected.name}</strong><span className="playback-status">{status}</span></div>
      </section>}
      <section className="panel metadata local-file-directory">
        <nav className="library-breadcrumb" aria-label="Каталоги">
          <a className="link" href="/files" onClick={event => followLink(event, "")}>Корень</a>
          {breadcrumbs.map((name, index) => <span key={breadcrumbPath(index)}>
            <span className="muted">/</span> <a className="link" href={fileUrl(breadcrumbPath(index))}
              onClick={event => followLink(event, breadcrumbPath(index))}>{name}</a>
          </span>)}
        </nav>
        {upPath !== null && upPath !== undefined && <a className="secondary icon-button file-up-button"
          href={fileUrl(upPath)} aria-label="Вверх" title="Вверх" onClick={event => followLink(event, upPath)}>
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 19V5m-6 6 6-6 6 6" /></svg>
        </a>}
        {loading && <p className="muted">Загрузка каталога…</p>}
        {!loading && directory?.entries.length === 0 && <p className="muted">Каталогов и видеофайлов нет</p>}
        <div className="file-list">
          {directory?.entries.map(entry => <a className="file-row" key={entry.id} href={fileUrl(entry.path)}
            onClick={event => followLink(event, entry.path)}>
            <svg viewBox="0 0 24 24" aria-hidden="true">{entry.kind === "DIRECTORY"
              ? <path d="M3 6h7l2 2h9v10H3z" />
              : <><rect x="3" y="4" width="18" height="16" rx="2" /><path d="m10 9 5 3-5 3z" /></>}</svg>
            <span><strong>{entry.name}</strong></span>
            <span className="muted">{entry.kind === "DIRECTORY" ? "Каталог" : formatSize(entry.sizeBytes || 0)}</span>
          </a>)}
        </div>
      </section>
    </article>
  );
}
