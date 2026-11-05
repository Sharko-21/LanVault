import test from "node:test";
import assert from "node:assert/strict";
import {
  archiveStatus,
  archiveLocation,
  displayPath,
  sampleRate,
} from "../desktop/src/ui-state.ts";

test("receive rate uses a counter across upload boundaries and a ten-second window", () => {
  let result = sampleRate([], "100", 1000);
  assert.equal(result.rate, 0);
  result = sampleRate(result.samples, "2100", 3000);
  assert.equal(result.rate, 1000);
  // Finishing an upload must not reset this process-wide byte counter.
  result = sampleRate(result.samples, "3100", 4000);
  assert.equal(result.rate, 1000);
  result = sampleRate(result.samples, "3100", 15000);
  assert.equal(result.rate, 0);
  assert.equal(result.samples.length, 1);
});
test("receive rate resets on receiver restart or clock reversal", () => {
  for (const [bytes, time] of [
    ["1", 2000],
    ["3000", 500],
  ]) {
    const result = sampleRate([{ bytes: 2000n, time: 1000 }], bytes, time);
    assert.equal(result.rate, 0);
    assert.equal(result.samples.length, 1);
  }
});
test("rate retains precision for large byte counters", () => {
  const result = sampleRate(
    [{ bytes: 9007199254740993000n, time: 1000 }],
    "9007199254740995000",
    3000,
  );
  assert.equal(result.rate, 1000);
});
test("archive status respects stopped/paused receivers even with in-flight uploads", () => {
  const runtime = {
    folder: {},
    serverListening: true,
    paused: false,
    activeUploads: [{ id: "file" }],
  };
  assert.equal(archiveStatus(runtime).label, "Копирование");
  assert.equal(archiveStatus({ ...runtime, paused: true }).label, "На паузе");
  assert.equal(archiveStatus({ ...runtime, serverListening: false }).label, "Приём выключен");
  assert.equal(archiveStatus({ ...runtime, folder: null }).label, "Нужна папка");
  assert.equal(archiveStatus({ ...runtime, activeUploads: [] }).label, "Готов к приёму");
});
test("archive display paths keep Unicode and do not alter ordinary paths", () => {
  assert.equal(displayPath("\\\\?\\D:\\Фото"), "D:\\Фото");
  assert.equal(displayPath("D:\\Фото"), "D:\\Фото");
});
test("recent file locations omit internal device and upload identifiers", () => {
  assert.equal(
    archiveLocation("device-id/Videos/2026/09/upload-id__video.mp4"),
    "Видео / 2026 / 09",
  );
  assert.equal(
    archiveLocation("device-id/Photos/2026/11/upload-id__photo.jpg"),
    "Фото / 2026 / 11",
  );
});
