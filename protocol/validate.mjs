import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import { readFile } from "node:fs/promises";

const schema = JSON.parse(
  await readFile(new URL("./schemas/upload-descriptor.schema.json", import.meta.url), "utf8"),
);
const ajv = new Ajv2020({ allErrors: true, strict: true });
addFormats(ajv);
ajv.addFormat("positive-int64", {
  type: "string",
  validate(value) {
    return (
      /^[1-9][0-9]{0,18}$/.test(value) &&
      BigInt(value) <= 9223372036854775807n &&
      BigInt(value).toString() === value
    );
  },
});

export const validateUploadDescriptor = ajv.compile(schema);
