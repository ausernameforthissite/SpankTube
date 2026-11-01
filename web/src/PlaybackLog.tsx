import { useLayoutEffect, useRef } from "react";
import type { PlaybackLogLine } from "./usePlaybackLogs";

export function PlaybackLog({ lines, overlay = true }: { lines: PlaybackLogLine[]; overlay?: boolean }) {
  const elements = useRef(new Map<number, HTMLDivElement>());
  const positions = useRef(new Map<number, number>());

  useLayoutEffect(() => {
    const next = new Map<number, number>();
    for (const [id, element] of elements.current) {
      const top = element.getBoundingClientRect().top;
      const previous = positions.current.get(id);
      if (previous !== undefined) {
        const offset = previous - top;
        if (offset !== 0) {
          element.animate([{ transform: `translateY(${offset}px)` }, { transform: "translateY(0)" }],
            { duration: 180, easing: "ease-out" });
        }
      }
      next.set(id, top);
    }
    positions.current = next;
  }, [lines]);

  return (
    <div className={overlay ? "playback-log playback-log-overlay" : "playback-log playback-log-popup"} aria-live="polite">
      {lines.map((line, index) => (
        <div className="playback-log-line" key={line.id}
          ref={element => {
            if (element) elements.current.set(line.id, element);
            else elements.current.delete(line.id);
          }}
          style={{ opacity: Math.max(0.1, 1 - (lines.length - index - 1) * 0.1) }}>
          {line.text}
        </div>
      ))}
    </div>
  );
}
