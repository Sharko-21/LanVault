import type { RuntimeInfo } from "./platform";

export type Sample = { time: number; bytes: bigint };
// A monotonic counter survives individual uploads finishing or being retried.
export function sampleRate(samples: Sample[], bytes: string, time: number) {
  const current = { time, bytes: BigInt(bytes) };
  const last = samples.at(-1);
  const window =
    last && (current.bytes < last.bytes || time <= last.time)
      ? []
      : samples.filter((s) => time - s.time <= 10000);
  window.push(current);
  const first = window[0]!;
  const elapsed = time - first.time;
  return {
    samples: window,
    rate: elapsed > 0 ? (Number(current.bytes - first.bytes) * 1000) / elapsed : 0,
  };
}
export function archiveStatus(runtime: RuntimeInfo | null) {
  if (!runtime) return { label: "Подготовка", tone: "muted", hint: "Открываем архив…" };
  if (!runtime.folder)
    return {
      label: "Нужна папка",
      tone: "warning",
      hint: "Выберите, где хранить копии с телефона.",
    };
  if (!runtime.serverListening)
    return {
      label: "Приём выключен",
      tone: "muted",
      hint: "Включите приём, чтобы телефон мог отправлять файлы.",
    };
  if (runtime.paused)
    return { label: "На паузе", tone: "warning", hint: "Новые файлы пока не принимаются." };
  if (runtime.activeUploads.length)
    return { label: "Копирование", tone: "active", hint: "Принимаем оригиналы с телефона." };
  return {
    label: "Готов к приёму",
    tone: "ready",
    hint: "Телефон сможет отправить новые фото и видео по Wi-Fi.",
  };
}
export function displayPath(path: string) {
  return path.replace(/^\\\\\?\\/, "");
}
export function archiveLocation(path: string) {
  const parts = path.split(/[\\/]/);
  const category = parts.findIndex((part) => part === "Photos" || part === "Videos");
  if (category < 0) return parts.slice(0, -1).join(" / ") || "Папка архива";
  return [parts[category] === "Photos" ? "Фото" : "Видео", ...parts.slice(category + 1, -1)].join(
    " / ",
  );
}
export function dateLabel(value: string | null) {
  if (!value) return "Ещё не подключался";
  const date = new Date(
    value.endsWith("Z") || /[+-]\d\d:\d\d$/.test(value) ? value : value.replace(" ", "T") + "Z",
  );
  return Number.isNaN(date.getTime())
    ? "Дата неизвестна"
    : date.toLocaleString("ru-RU", {
        day: "numeric",
        month: "short",
        hour: "2-digit",
        minute: "2-digit",
      });
}
