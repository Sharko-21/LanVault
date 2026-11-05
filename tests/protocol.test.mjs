import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { validateUploadDescriptor } from "../protocol/validate.mjs";

const fixtures = JSON.parse(
  await readFile(
    new URL("../protocol/compatibility-fixtures/uploads.json", import.meta.url),
    "utf8",
  ),
);

for (const fixture of fixtures.cases) {
  test(`wire contract: ${fixture.name}`, () => {
    const payload = { ...fixtures.base, ...fixture.patch };
    assert.equal(
      validateUploadDescriptor(payload),
      fixture.valid,
      JSON.stringify(validateUploadDescriptor.errors),
    );
  });
}

test("byte count remains exact after JSON round trip", () => {
  const restored = JSON.parse(JSON.stringify(fixtures.base));
  assert.equal(BigInt(restored.size), 9007199254740993n);
});
