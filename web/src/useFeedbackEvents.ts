import { useEffect, useRef, useState } from "react";
import type { ApiFetch } from "./types";
import { useDocumentVisible } from "./useDocumentVisible";

export function useFeedbackEvents(enabled: boolean, apiFetch: ApiFetch, onTicket: (ticketId: string) => void): number {
  const visible = useDocumentVisible();
  const [revision, setRevision] = useState(0);
  const callback = useRef(onTicket);
  callback.current = onTicket;

  useEffect(() => {
    if (!enabled || !visible) return;
    let stopped = false;
    let abort: AbortController | null = null;
    let retry: number | undefined;

    const listen = async () => {
      abort = new AbortController();
      try {
        const response = await apiFetch("/api/v1/feedback/events", { signal: abort.signal });
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
            if (event !== "ticket") continue;
            const data = block.split(/\r?\n/).filter(line => line.startsWith("data:"))
              .map(line => line.slice(5).trimStart()).join("\n");
            if (!data) continue;
            const update = JSON.parse(data) as { ticketId?: string };
            if (!update.ticketId || stopped) continue;
            callback.current(update.ticketId);
            setRevision(current => current + 1);
          }
        }
      } catch {
      }
      if (!stopped) retry = window.setTimeout(listen, 2000);
    };

    void listen();
    return () => {
      stopped = true;
      if (retry !== undefined) window.clearTimeout(retry);
      abort?.abort();
    };
  }, [enabled, visible, apiFetch]);

  return revision;
}
