import type { FeedbackTicket } from "./types";

export function feedbackType(value: FeedbackTicket["type"]): string {
  return { Question: "Вопрос", Bug: "Ошибка", Feature: "Новая функция", Improvement: "Улучшение" }[value];
}

export function feedbackPriority(value: FeedbackTicket["priority"]): string {
  return { Minor: "Незначительный", Major: "Серьёзный", Critical: "Критический", Blocker: "Блокирующий" }[value];
}

export function feedbackStatus(value: FeedbackTicket["status"]): string {
  return { Accepted: "Принят", "In Progress": "В работе", Testing: "Проверка", Done: "Завершён" }[value];
}

export function feedbackResolution(value: NonNullable<FeedbackTicket["resolution"]>): string {
  return { Completed: "Завершено", Answered: "Отвечен", Rejected: "Отклонён", Duplicate: "Дубликат" }[value];
}

export function feedbackDate(value: string): string {
  return new Date(value).toLocaleString("ru-RU");
}
