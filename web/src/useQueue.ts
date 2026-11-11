import { useEffect, useRef, useState } from "react";
import type { ApiFetch, QueueJob, QueueSnapshot } from "./types";
import { useDocumentVisible } from "./useDocumentVisible";

type QueueMessage = { type: "request" } | { type: "snapshot"; snapshot: QueueSnapshot };

export function useQueue(enabled: boolean, apiFetch: ApiFetch, onJob?: (job: QueueJob) => void): QueueSnapshot {
  const [snapshot, setSnapshot] = useState<QueueSnapshot>({ jobs: [], beforeYou: 0 });
  const listener = useRef(onJob);
  listener.current = onJob;
  const visible = useDocumentVisible();

  useEffect(() => {
    if (!enabled || !visible) {
      setSnapshot({ jobs: [], beforeYou: 0 });
      return;
    }
    let stopped = false;
    let abort: AbortController | null = null;
    let retry: ReturnType<typeof setTimeout> | undefined;
    let latest: QueueSnapshot | null = null;
    let initialized = false;
    const seen = new Set<string>();
    const channel = new BroadcastChannel("spanktube.queue");

    const apply = (next: QueueSnapshot) => {
      latest = next;
      if (initialized) {
        for (const job of next.jobs) {
          if (!seen.has(job.id)) listener.current?.(job);
        }
      }
      seen.clear();
      for (const job of next.jobs) seen.add(job.id);
      initialized = true;
      if (!stopped) setSnapshot(next);
    };

    channel.onmessage = event => {
      const message = event.data as QueueMessage;
      if (message.type === "snapshot") apply(message.snapshot);
      else if (latest) channel.postMessage({ type: "snapshot", snapshot: latest } satisfies QueueMessage);
    };

    const listen = async () => {
      if (stopped) return;
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
          apply(next);
          if (!stopped) channel.postMessage({ type: "snapshot", snapshot: next } satisfies QueueMessage);
        }
      }
    };

    const connect = () => {
      if (stopped) return;
      const run = async () => {
        if (navigator.locks?.request) {
          await navigator.locks.request("spanktube.queue", { ifAvailable: true }, lock => {
            if (lock) return listen();
            return false;
          });
        } else {
          await listen();
        }
      };
      void run().catch(() => {}).finally(() => {
        if (!stopped) retry = setTimeout(connect, 2000);
      });
    };

    channel.postMessage({ type: "request" } satisfies QueueMessage);
    connect();
    return () => {
      stopped = true;
      channel.close();
      if (retry) clearTimeout(retry);
      abort?.abort();
    };
  }, [enabled, visible, apiFetch]);

  return snapshot;
}
