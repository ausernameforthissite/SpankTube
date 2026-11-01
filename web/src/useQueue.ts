import { useEffect, useRef, useState } from "react";
import type { ApiFetch, QueueJob, QueueSnapshot } from "./types";

export function useQueue(enabled: boolean, apiFetch: ApiFetch, onJob: (job: QueueJob) => void): QueueSnapshot {
  const [snapshot, setSnapshot] = useState<QueueSnapshot>({ jobs: [], beforeYou: 0 });
  const listener = useRef(onJob);
  listener.current = onJob;

  useEffect(() => {
    if (!enabled) {
      setSnapshot({ jobs: [], beforeYou: 0 });
      return;
    }
    let stopped = false;
    let abort: AbortController | null = null;
    let retry: ReturnType<typeof setTimeout> | undefined;

    const listen = async () => {
      const seen = new Set<string>();
      let initialized = false;
      try {
        abort = new AbortController();
        const response = await apiFetch("/api/v1/archive/events", { signal: abort.signal });
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
            if (event !== "queue") continue;
            const text = block.split(/\r?\n/).filter(line => line.startsWith("data:"))
              .map(line => line.slice(5).trimStart()).join("\n");
            const next = JSON.parse(text) as QueueSnapshot;
            if (initialized) {
              for (const job of next.jobs) {
                if (!seen.has(job.id)) listener.current(job);
              }
            }
            seen.clear();
            for (const job of next.jobs) seen.add(job.id);
            initialized = true;
            if (!stopped) setSnapshot(next);
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
  }, [enabled, apiFetch]);

  return snapshot;
}
