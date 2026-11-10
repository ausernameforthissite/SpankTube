import { useCallback, useEffect, useRef, useState } from "react";
import type { ApiFetch } from "./types";
import type { components } from "./api/schema";

type LiveRecording = components["schemas"]["LiveRecording"];

export function useLiveRecording(videoId: string | null, apiFetch: ApiFetch,
  onError: (reason: unknown) => void, notify: (message: string) => void, onClear?: () => void) {
  const [recording, setRecording] = useState<LiveRecording | null>(null);
  const [busy, setBusy] = useState(false);
  const videoIdRef = useRef(videoId);
  const recordingRef = useRef<LiveRecording | null>(null);
  const completionNotified = useRef(false);
  const failureNotified = useRef<string | null>(null);
  videoIdRef.current = videoId;
  recordingRef.current = recording;

  const loaded = useCallback(async () => {
    if (!videoId) return;
    const id = videoId;
    try {
      const response = await apiFetch(`/api/v1/videos/${id}/recording`, { expectedStatuses: [404] });
      if (videoIdRef.current !== id) return;
      if (response.status === 404) {
        setRecording(null);
        return;
      }
      const next = await response.json() as LiveRecording;
      setRecording(next);
      if (next.status === "COMPLETED" && recordingRef.current?.status === "STOPPING" && !completionNotified.current) {
        completionNotified.current = true;
        notify("Запись сохранена в библиотеке");
      }
    } catch (reason) {
      onError(reason);
    }
  }, [apiFetch, videoId, notify, onError]);

  useEffect(() => {
    setRecording(null);
    setBusy(false);
    completionNotified.current = false;
    failureNotified.current = null;
    void loaded();
  }, [loaded]);

  useEffect(() => {
    if (recording?.status !== "RECORDING" && recording?.status !== "STOPPING") return;
    const timer = window.setInterval(() => void loaded(), 5000);
    return () => window.clearInterval(timer);
  }, [loaded, recording?.status]);

  useEffect(() => {
    if (recording?.status !== "FAILED" || !recording.error || failureNotified.current === recording.error) return;
    failureNotified.current = recording.error;
    onError(new Error(recording.error));
  }, [recording?.status, recording?.error, onError]);

  const toggle = useCallback(async (quality?: { height?: number | null; bandwidth?: number | null }) => {
    if (!videoId || busy || recording?.status === "STOPPING") return;
    const id = videoId;
    const action = recording?.status === "RECORDING" ? "stop" : "start";
    onClear?.();
    setBusy(true);
    try {
      const response = await apiFetch(`/api/v1/videos/${id}/recording/${action}`, {
        method: "POST", body: action === "start" ? JSON.stringify(quality || {}) : undefined,
      });
      if (videoIdRef.current !== id) return;
      const next = await response.json() as LiveRecording;
      setRecording(next);
      if (action === "start") {
        completionNotified.current = false;
        failureNotified.current = null;
        notify("Запись началась");
      } else if (next.status === "COMPLETED") {
        if (!completionNotified.current) {
          completionNotified.current = true;
          notify("Запись сохранена в библиотеке");
        }
      } else {
        notify("Остановка записи…");
      }
    } catch (reason) {
      onError(reason);
    } finally {
      if (videoIdRef.current === id) setBusy(false);
    }
  }, [apiFetch, busy, notify, onClear, onError, recording?.status, videoId]);

  const stopOnLeave = useCallback((id: string | null) => {
    if (!id) return;
    void apiFetch(`/api/v1/videos/${id}/recording/stop`, { method: "POST", keepalive: true }).catch(() => {});
  }, [apiFetch]);

  useEffect(() => {
    const id = videoId;
    return () => {
      if (recordingRef.current?.status === "RECORDING") stopOnLeave(id);
    };
  }, [stopOnLeave, videoId]);

  useEffect(() => {
    const leave = () => {
      if (recordingRef.current?.status === "RECORDING") stopOnLeave(videoIdRef.current);
    };
    window.addEventListener("pagehide", leave);
    return () => window.removeEventListener("pagehide", leave);
  }, [stopOnLeave]);

  return { recording, busy, toggle };
}
