import { useEffect, useRef, useState, type SyntheticEvent } from "react";

export function LazyThumbnail({ src, alt = "", className, priority = false, onError }: {
  src: string;
  alt?: string;
  className?: string;
  priority?: boolean;
  onError?: (event: SyntheticEvent<HTMLImageElement>) => void;
}) {
  const imageRef = useRef<HTMLImageElement>(null);
  const [nearViewport, setNearViewport] = useState(priority);

  useEffect(() => {
    setNearViewport(priority);
    if (priority) return;
    const image = imageRef.current;
    if (!image || typeof IntersectionObserver === "undefined") {
      setNearViewport(true);
      return;
    }
    const observer = new IntersectionObserver(entries => {
      if (entries.some(entry => entry.isIntersecting)) {
        setNearViewport(true);
        observer.disconnect();
      }
    }, { rootMargin: "200px 0px" });
    observer.observe(image);
    return () => observer.disconnect();
  }, [src, priority]);

  return <img ref={imageRef} className={className} src={nearViewport ? src : undefined} alt={alt}
    loading={priority ? "eager" : "lazy"} decoding="async" fetchPriority={priority ? "high" : "low"}
    onError={onError} />;
}
