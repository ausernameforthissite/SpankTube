export const ARCHIVE_QUALITY_HEIGHTS = [144, 240, 360, 480, 720, 1080, 1440, 2160];

export function ArchiveQualitySelect({ value, onChange, ariaLabel = "Качество архивации" }: {
  value: number | null;
  onChange: (value: number | null) => void;
  ariaLabel?: string;
}) {
  const heights = value !== null && !ARCHIVE_QUALITY_HEIGHTS.includes(value)
    ? [...ARCHIVE_QUALITY_HEIGHTS, value].sort((left, right) => left - right) : ARCHIVE_QUALITY_HEIGHTS;
  return <select aria-label={ariaLabel} value={value === null ? "best" : String(value)}
    onChange={event => onChange(event.target.value === "best" ? null : Number(event.target.value))}>
    <option value="best">Лучшее</option>
    {heights.map(height => <option key={height} value={height}>{height}p</option>)}
  </select>;
}
