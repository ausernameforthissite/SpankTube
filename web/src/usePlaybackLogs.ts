import { useEffect, useState } from "react";
import type { ApiFetch } from "./types";

export type PlaybackLogLine = { id: number; text: string };

export function usePlaybackLogs(videoId: string | null, sessionId: string, active: boolean,
  apiFetch: ApiFetch): PlaybackLogLine[] {
  const [lines, setLines] = useState<PlaybackLogLine[]>([]);

  useEffect(() => {
    setLines([]);
  }, [videoId, sessionId]);

  useEffect(() => {
    if (!videoId || !active) return;
    let stopped = false;
    let retry: ReturnType<typeof setTimeout> | undefined;
    let abort: AbortController | null = null;
    let nextId = 0;

    const listen = async () => {
      try {
        abort = new AbortController();
        const response = await apiFetch(`/media/v/${videoId}/logs?session=${sessionId}`, { signal: abort.signal });
        if (!response.body) throw new Error("SSE stream is unavailable");
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = "";
        while (!stopped) {
          const { done, value } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });
          const blocks = buffer.split(/\r?\n\r?\n/);
          buffer = blocks.pop() || "";
          for (const block of blocks) {
            const event = block.split(/\r?\n/).find(line => line.startsWith("event:"))?.slice(6).trim();
            if (event !== "playback-log") continue;
            const text = block.split(/\r?\n/).filter(line => line.startsWith("data:"))
              .map(line => line.slice(5).trimStart()).join("\n");
            if (!text || stopped) continue;
            const id = ++nextId;
            setLines(current => [...current, { id, text }].slice(-10));
          }
        }
      } catch (error) {
        if (stopped || (error instanceof DOMException && error.name === "AbortError")) return;
      }
      if (!stopped) retry = setTimeout(listen, 2000);
    };

    void listen();
    return () => {
      stopped = true;
      if (retry) clearTimeout(retry);
      abort?.abort();
    };
  }, [videoId, sessionId, active, apiFetch]);

  return lines;
}
