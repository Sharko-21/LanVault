import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import Ajv from "ajv";
import addFormats from "ajv-formats";

test("every frontend command is declared and narrowly permitted in the main window", async () => {
  const load = (path) => readFile(new URL(path, import.meta.url), "utf8");
  const [bridge, build, capability] = await Promise.all([
    load("../desktop/src/platform.ts"),
    load("../desktop/src-tauri/build.rs"),
    load("../desktop/src-tauri/capabilities/main.json"),
  ]);
  const cap = JSON.parse(capability);
  assert.deepEqual(cap.windows, ["main"]);
  const commands = [...bridge.matchAll(/invoke(?:<[^;]*?>)?\(\s*"([a-z_]+)"/g)].map((m) => m[1]);
  assert.ok(commands.length >= 12, "bridge parser must find the actual commands");
  for (const command of commands) {
    assert.ok(build.includes(`"${command}"`), `missing app manifest command: ${command}`);
    assert.ok(
      cap.permissions.includes(`allow-${command.replaceAll("_", "-")}`),
      `missing capability: ${command}`,
    );
  }
  assert.ok(
    !cap.permissions.some((p) => /shell:|fs:/.test(p)),
    "archive controls must not need broad shell/fs access",
  );
});

test("Tauri configuration conforms to the installed CLI schema", async () => {
  const schema = JSON.parse(
    await readFile(
      new URL("../desktop/node_modules/@tauri-apps/cli/config.schema.json", import.meta.url),
      "utf8",
    ),
  );
  const config = JSON.parse(
    await readFile(new URL("../desktop/src-tauri/tauri.conf.json", import.meta.url), "utf8"),
  );
  // Tauri's Rust-generated patterns use identity escapes that are invalid with JS /u.
  // The wire contract uses a separate strict Ajv2020 instance and is unaffected.
  const ajv = new Ajv({ strict: false, allErrors: true, unicodeRegExp: false });
  addFormats(ajv);
  for (const [name, maximum] of [
    ["uint8", 255],
    ["uint32", 4294967295],
  ]) {
    ajv.addFormat(name, {
      type: "number",
      validate: (value) => Number.isInteger(value) && value >= 0 && value <= maximum,
    });
  }
  const validate = ajv.compile(schema);
  assert.equal(validate(config), true, JSON.stringify(validate.errors));
});
