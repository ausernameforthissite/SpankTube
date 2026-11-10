import { memo, useMemo, type MouseEvent, type ReactNode } from "react";

type LinkTarget = { href: string; external: boolean };
type Navigate = (url: string) => void;

function watchRoute(videoId: string, time: string | null): string | null {
  if (!/^[A-Za-z0-9_-]{11}$/.test(videoId)) return null;
  const query = new URLSearchParams({ v: videoId });
  if (time && /^\d+$/.test(time)) query.set("t", `${time}s`);
  else if (time && /^(?:(?:\d+)h)?(?:(?:\d+)m)?(?:\d+)s$/.test(time)) query.set("t", time);
  return `/watch?${query.toString()}`;
}

function linkTarget(value: string): LinkTarget | null {
  try {
    const url = new URL(value, "https://www.youtube.com");
    if (url.protocol !== "https:" && url.protocol !== "http:") return null;
    const host = url.hostname.toLowerCase();
    if (host === "youtu.be") {
      const route = watchRoute(url.pathname.slice(1).split("/")[0], url.searchParams.get("t"));
      return route ? { href: route, external: false } : null;
    }
    if (host !== "youtube.com" && !host.endsWith(".youtube.com")) {
      return { href: url.href, external: true };
    }
    if (url.pathname === "/redirect") {
      const target = url.searchParams.get("q") || url.searchParams.get("url");
      return target ? linkTarget(target) : null;
    }
    if (url.pathname === "/watch") return watchRoute(url.searchParams.get("v") || "", url.searchParams.get("t"))
      ? { href: watchRoute(url.searchParams.get("v") || "", url.searchParams.get("t"))!, external: false } : null;
    const video = /^\/(?:shorts|live|embed)\/([A-Za-z0-9_-]{11})\/?$/.exec(url.pathname);
    if (video) {
      const route = watchRoute(video[1], url.searchParams.get("t"));
      return route ? { href: route, external: false } : null;
    }
    if (url.pathname === "/playlist") {
      const id = url.searchParams.get("list");
      return id && /^[A-Za-z0-9_-]+$/.test(id)
        ? { href: `/playlist?list=${encodeURIComponent(id)}`, external: false } : null;
    }
    if (/^\/(?:channel\/[A-Za-z0-9_-]+|c\/[A-Za-z0-9_.-]+|@[A-Za-z0-9_.-]+)\/?$/.test(url.pathname)) {
      return { href: url.pathname, external: false };
    }
    return null;
  } catch {
    return null;
  }
}

function renderNode(node: Node, key: number, navigate: Navigate): ReactNode {
  if (node.nodeType === Node.TEXT_NODE) return node.textContent;
  if (!(node instanceof Element) || node.tagName === "SCRIPT" || node.tagName === "STYLE") return null;
  if (node.tagName === "BR") return <br key={key} />;
  const children = Array.from(node.childNodes).map((child, index) => renderNode(child, index, navigate));
  if (node.tagName === "A") {
    const target = linkTarget(node.getAttribute("href") || "");
    if (target) {
      return <a key={key} href={target.href} target={target.external ? "_blank" : undefined}
        rel={target.external ? "noopener noreferrer" : undefined}
        onClick={target.external ? undefined : (event: MouseEvent<HTMLAnchorElement>) => {
          if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
          event.preventDefault();
          navigate(target.href);
        }}>{children}</a>;
    }
  }
  return children;
}

export const VideoDescription = memo(function VideoDescription({ description, navigate }: {
  description: string;
  navigate: Navigate;
}) {
  const content = useMemo(() => {
    const document = new DOMParser().parseFromString(description, "text/html");
    return Array.from(document.body.childNodes).map((node, index) => renderNode(node, index, navigate));
  }, [description, navigate]);
  return <p className="description">{content}</p>;
});
