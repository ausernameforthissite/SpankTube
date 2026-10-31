import { FormEvent, useCallback, useEffect, useRef, useState } from "react";
import shaka from "shaka-player/dist/shaka-player.compiled.js";
import type { components } from "./api/schema";

type TokenPair = components["schemas"]["TokenPair"];
type Video = components["schemas"]["Video"];
type ApiError = components["schemas"]["ApiError"];

type Session = TokenPair & {
  username: string;
  accessExpiresAt: number;
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
  let body: ApiError;
  try {
    body = (await response.json()) as ApiError;
  } catch {
    return new RequestError(`HTTP ${response.status}`);
  }
  return new RequestError(body.message || `HTTP ${response.status}`, body.actions || []);
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

export default function App() {
  const [session, setSession] = useState<Session | null>(loadSession);
  const sessionRef = useRef(session);
  const refreshRef = useRef<Promise<string> | null>(null);
  const [username, setUsername] = useState("viewer");
  const [password, setPassword] = useState("");
  const [videoId, setVideoId] = useState("");
  const [video, setVideo] = useState<Video | null>(null);
  const [videoElement, setVideoElement] = useState<HTMLVideoElement | null>(null);
  const [playbackStatus, setPlaybackStatus] = useState("Плеер не запущен");
  const [playerReady, setPlayerReady] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [actions, setActions] = useState<string[]>([]);

  const saveSession = useCallback((next: Session | null) => {
    sessionRef.current = next;
    setSession(next);
    if (next) sessionStorage.setItem(STORAGE_KEY, JSON.stringify(next));
    else sessionStorage.removeItem(STORAGE_KEY);
  }, []);

  const acceptTokens = useCallback(
    (tokens: TokenPair, name: string) => {
      saveSession({ ...tokens, username: name, accessExpiresAt: Date.now() + tokens.expiresIn * 1000 });
    },
    [saveSession],
  );

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
      const data = "data" in reason && reason.data != null
        ? Array.from(reason.data as ArrayLike<unknown>)
        : [];
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

  const login = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    setActions([]);
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

  const loadVideo = async (event: FormEvent) => {
    event.preventDefault();
    setLoading(true);
    setError(null);
    setActions([]);
    setVideo(null);
    setPlaybackStatus("Загрузка плеера");
    try {
      const token = await ensureAccessToken();
      const response = await fetch(`${API_BASE_URL}/api/v1/videos/${videoId}`, {
        headers: { Authorization: `Bearer ${token}` },
      });
      if (!response.ok) throw await responseError(response);
      setVideo((await response.json()) as Video);
    } catch (reason) {
      showError(reason);
    } finally {
      setLoading(false);
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
      player.addEventListener("error", (event) => {
        showError((event as CustomEvent<unknown>).detail);
      });
      await player.load(`${MEDIA_BASE_URL}${video.manifestUrl}`);
      if (disposed) {
        await player.destroy();
      } else {
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

  const number = new Intl.NumberFormat("ru-RU");

  return (
    <main>
      <header>
        <h1>SpankTube</h1>
        <p>Этап 1 · просмотр через собственный DASH-прокси</p>
      </header>

      <section className="panel auth">
        {session ? (
          <>
            <span>
              Вход: <strong>{session.username}</strong> ({session.role})
            </span>
            <button
              className="secondary"
              onClick={() => {
                saveSession(null);
                setVideo(null);
              }}
            >
              Выйти
            </button>
          </>
        ) : (
          <form onSubmit={login}>
            <input value={username} onChange={(event) => setUsername(event.target.value)} autoComplete="username" aria-label="Логин" required />
            <input type="password" value={password} onChange={(event) => setPassword(event.target.value)} autoComplete="current-password" placeholder="Пароль" aria-label="Пароль" required />
            <button type="submit">Войти</button>
          </form>
        )}
      </section>

      <section className="panel">
        <form className="video-search" onSubmit={loadVideo}>
          <input value={videoId} onChange={(event) => setVideoId(event.target.value.trim())} placeholder="YouTube videoId (11 символов)" pattern="[A-Za-z0-9_-]{11}" aria-label="YouTube videoId" required />
          <button type="submit" disabled={loading || !session}>{loading ? "Загрузка…" : "Смотреть"}</button>
        </form>
      </section>

      {error && (
        <section className="error" role="alert">
          <strong>{error}</strong>
          {actions.length > 0 && <ul>{actions.map((action) => <li key={action}>{action}</li>)}</ul>}
        </section>
      )}

      {video && (
        <article>
          <section className="player panel">
            <video
              ref={setVideoElement}
              controls={playerReady}
              playsInline
              onCanPlay={() => setPlaybackStatus("Готово к воспроизведению")}
              onPlaying={() => setPlaybackStatus("Воспроизведение")}
              onWaiting={() => setPlaybackStatus("Буферизация")}
              onPause={() => setPlaybackStatus("Пауза")}
            />
            <div className="playback">
              <button type="button" className="secondary" onClick={togglePlayback} disabled={!videoElement || !playerReady}>
                {videoElement?.paused === false ? "Пауза" : "Играть"}
              </button>
              <span>{playbackStatus}</span>
            </div>
          </section>
          <section className="panel metadata">
            <h2>{video.title || video.videoId}</h2>
            <p className="channel">{video.channelName || "Канал не указан"}</p>
            <p className="stats">
              {formatDuration(video.durationSeconds)} · {video.viewCount == null ? "—" : `${number.format(video.viewCount)} просмотров`} · {video.likeCount == null ? "—" : `${number.format(video.likeCount)} лайков`}
            </p>
            <p className="description">{video.description || "Описание отсутствует"}</p>
            <details>
              <summary>Форматы ({video.formats.length}) и субтитры ({video.subtitles.length})</summary>
              <div className="formats">
                {video.formats.map((format) => (
                  <div key={format.formatId}>
                    <code>{format.formatId}</code> {format.height ? `${format.height}p` : "audio"} · {format.mimeType} · {format.codecs}
                  </div>
                ))}
              </div>
            </details>
          </section>
        </article>
      )}
    </main>
  );
}
