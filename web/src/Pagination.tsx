type PaginationProps = {
  page: number;
  totalPages: number | null;
  hasNext: boolean;
  loading: boolean;
  canOpen: (page: number) => boolean;
  onOpen: (page: number) => void;
};

export function Pagination({ page, totalPages, hasNext, loading, canOpen, onOpen }: PaginationProps) {
  const last = totalPages ?? page + (hasNext ? 1 : 0);
  const start = Math.max(1, Math.min(page - 4, last - 9));
  const end = Math.min(last, start + 9);
  const pages = Array.from({ length: Math.max(0, end - start + 1) }, (_, index) => start + index)
    .filter(canOpen);
  if (last <= 1) return null;

  return (
    <nav className="pagination" aria-label="Страницы">
      <button className="secondary pagination-arrow" aria-label="Предыдущая страница" title="Предыдущая страница"
        disabled={loading || page <= 1} onClick={() => onOpen(page - 1)}>
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m15 18-6-6 6-6" /></svg>
      </button>
      {pages.map(number => <button key={number} className={number === page ? "pagination-page current" : "secondary pagination-page"}
        aria-label={`Страница ${number}`} aria-current={number === page ? "page" : undefined}
        disabled={loading || number === page} onClick={() => onOpen(number)}>{number}</button>)}
      <span className="pagination-status">{totalPages ? `${page} / ${totalPages}` : page}</span>
      <button className="secondary pagination-arrow" aria-label="Следующая страница" title="Следующая страница"
        disabled={loading || !hasNext} onClick={() => onOpen(page + 1)}>
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 18 6-6-6-6" /></svg>
      </button>
    </nav>
  );
}
