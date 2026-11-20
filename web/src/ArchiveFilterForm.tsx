import { useState } from "react";
import { ArchiveQualitySelect } from "./ArchiveQualitySelect";
import type { ArchiveDateFilter, ArchiveFilter, ArchiveNumericFilter } from "./types";

type Condition<T> = {
  exact: T | null;
  minimum: T | null;
  maximum: T | null;
  minimumInclusive: boolean;
  maximumInclusive: boolean;
};
type Mode = "none" | "range" | "exact";

function emptyCondition<T>(): Condition<T> {
  return { exact: null, minimum: null, maximum: null, minimumInclusive: true, maximumInclusive: true };
}

function localDateTime(value: string | null): string {
  if (!value) return "";
  const date = new Date(value);
  return new Date(date.getTime() - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 19);
}

function ConditionEditor<T extends number | string>({ label, type, condition, defaultExact, onChange }: {
  label: string;
  type: "number" | "datetime-local";
  condition: Condition<T> | null;
  defaultExact: T;
  onChange: (condition: Condition<T> | null) => void;
}) {
  const [mode, setMode] = useState<Mode>(condition ? condition.exact !== null ? "exact" : "range" : "none");
  const current = condition || emptyCondition<T>();
  const update = (patch: Partial<Condition<T>>) => onChange({ ...current, ...patch });
  const selectMode = (next: Mode) => {
    setMode(next);
    if (next === "none") {
      onChange(null);
      return;
    }
    onChange({ ...current, exact: next === "exact" ? current.exact ?? defaultExact : null });
  };
  const parse = (value: string): T | null => {
    if (!value) return null;
    return (type === "number" ? Number(value) : new Date(value).toISOString()) as T;
  };
  const format = (value: T | null): string | number => value === null ? ""
    : type === "number" ? value : localDateTime(value as string);
  return <fieldset className="archive-filter-condition">
    <legend>{label}</legend>
    <select aria-label={`Фильтр: ${label}`} value={mode} onChange={event => selectMode(event.target.value as Mode)}>
      <option value="none">Не используется</option>
      <option value="range">Диапазон</option>
      <option value="exact">Точное значение</option>
    </select>
    {mode === "exact" && <input type={type} step={1} min={type === "number" ? 0 : undefined}
      aria-label={`${label}: точное значение`} required value={format(current.exact)}
      onChange={event => update({ exact: parse(event.target.value) })} />}
    {mode === "range" && <div className="archive-filter-range">
      <input type={type} step={1} min={type === "number" ? 0 : undefined} aria-label={`${label}: от`}
        placeholder="−∞" value={format(current.minimum)} onChange={event => update({ minimum: parse(event.target.value) })} />
      <input type={type} step={1} min={type === "number" ? 0 : undefined} aria-label={`${label}: до`}
        placeholder="+∞" value={format(current.maximum)} onChange={event => update({ maximum: parse(event.target.value) })} />
      <label><input type="checkbox" checked={current.minimumInclusive} aria-label={`${label}: нижняя граница включена`}
        onChange={event => update({ minimumInclusive: event.target.checked })} /> Нижняя включена</label>
      <label><input type="checkbox" checked={current.maximumInclusive} aria-label={`${label}: верхняя граница включена`}
        onChange={event => update({ maximumInclusive: event.target.checked })} /> Верхняя включена</label>
      <small className="muted">Пустое поле — без ограничения.</small>
    </div>}
  </fieldset>;
}

export function ArchiveFilterForm({ initialTargetHeight, onStart }: {
  initialTargetHeight: number | null;
  onStart: (filter: ArchiveFilter, targetHeight: number | null) => void;
}) {
  const [filter, setFilter] = useState<ArchiveFilter>({ mode: "AND", likes: null, views: null, publishedAt: null });
  const [targetHeight, setTargetHeight] = useState<number | null>(initialTargetHeight);
  const update = (key: "likes" | "views" | "publishedAt", value: ArchiveNumericFilter | ArchiveDateFilter | null) =>
    setFilter(previous => ({ ...previous, [key]: value }));
  return <form className="archive-filter-form" onSubmit={event => {
    event.preventDefault();
    onStart(filter, targetHeight);
  }}>
    <p>Обрабатывать только видео, соответствующие условиям. Пустые условия не ограничивают архивацию.</p>
    <label className="archive-quality-option">Качество видео
      <ArchiveQualitySelect value={targetHeight} onChange={setTargetHeight} ariaLabel="Качество видео для архивации" />
    </label>
    <select aria-label="Способ объединения условий" value={filter.mode}
      onChange={event => setFilter(previous => ({ ...previous, mode: event.target.value as "AND" | "OR" }))}>
      <option value="AND">Все активные условия (AND)</option>
      <option value="OR">Хотя бы одно активное условие (OR)</option>
    </select>
    <ConditionEditor label="Лайки" type="number" condition={filter.likes} defaultExact={0}
      onChange={value => update("likes", value)} />
    <ConditionEditor label="Просмотры" type="number" condition={filter.views} defaultExact={0}
      onChange={value => update("views", value)} />
    <ConditionEditor label="Дата и время публикации" type="datetime-local" condition={filter.publishedAt}
      defaultExact={new Date().toISOString()} onChange={value => update("publishedAt", value)} />
    <button type="submit">Начать оценку</button>
  </form>;
}
