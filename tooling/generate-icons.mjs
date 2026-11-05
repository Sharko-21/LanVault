// Deterministic native icons. No network or image-generation dependency.
import { mkdir, writeFile } from "node:fs/promises";
import { deflateSync } from "node:zlib";

function crc32(buffer) {
  let crc = 0xffffffff;
  for (const byte of buffer) {
    crc ^= byte;
    for (let i = 0; i < 8; i++) crc = (crc >>> 1) ^ (crc & 1 ? 0xedb88320 : 0);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const name = Buffer.from(type);
  const result = Buffer.alloc(data.length + 12);
  result.writeUInt32BE(data.length, 0);
  name.copy(result, 4);
  data.copy(result, 8);
  result.writeUInt32BE(crc32(Buffer.concat([name, data])), data.length + 8);
  return result;
}

function png(size) {
  const header = Buffer.alloc(13);
  header.writeUInt32BE(size, 0);
  header.writeUInt32BE(size, 4);
  header[8] = 8;
  header[9] = 6;
  const rows = Buffer.alloc(size * (size * 4 + 1));
  for (let y = 0; y < size; y++)
    for (let x = 0; x < size; x++) {
      const px = x / size;
      const py = y / size;
      const shield = px > 0.13 && px < 0.87 && py > 0.06 && py < 0.94 - Math.abs(px - 0.5) * 0.55;
      const letter =
        (px > 0.36 && px < 0.45 && py > 0.27 && py < 0.69) ||
        (px > 0.36 && px < 0.67 && py > 0.6 && py < 0.69);
      const offset = y * (size * 4 + 1) + 1 + x * 4;
      rows.set(
        shield ? (letter ? [255, 255, 255, 255] : [11, 117, 108, 255]) : [0, 0, 0, 0],
        offset,
      );
    }
  return Buffer.concat([
    Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    chunk("IHDR", header),
    chunk("IDAT", deflateSync(rows)),
    chunk("IEND", Buffer.alloc(0)),
  ]);
}

const destination = new URL("../desktop/src-tauri/icons/", import.meta.url);
await mkdir(destination, { recursive: true });
const sizes = [32, 128, 256];
const images = sizes.map(png);
const directory = Buffer.alloc(6 + sizes.length * 16);
directory.writeUInt16LE(1, 2);
directory.writeUInt16LE(sizes.length, 4);
let offset = directory.length;
for (let i = 0; i < sizes.length; i++) {
  const entry = 6 + i * 16;
  directory[entry] = sizes[i] === 256 ? 0 : sizes[i];
  directory[entry + 1] = directory[entry];
  directory.writeUInt16LE(1, entry + 4);
  directory.writeUInt16LE(32, entry + 6);
  directory.writeUInt32LE(images[i].length, entry + 8);
  directory.writeUInt32LE(offset, entry + 12);
  offset += images[i].length;
}
await writeFile(new URL("32x32.png", destination), images[0]);
await writeFile(new URL("128x128.png", destination), images[1]);
await writeFile(new URL("icon.ico", destination), Buffer.concat([directory, ...images]));
console.log("Generated deterministic PNG/ICO icons.");
