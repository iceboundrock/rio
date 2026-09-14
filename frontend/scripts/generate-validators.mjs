// Precompiles the shared JSON Schemas (contracts/schemas) into standalone validators.
//
// Ajv normally compiles schemas at runtime with `new Function`, which a strict
// Content-Security-Policy (no 'unsafe-eval') forbids. This script runs the same Ajv at build time
// and writes plain code to src/api/validators.generated.{js,d.ts}; at runtime the browser only
// executes that code plus the small helpers under ajv/dist/runtime/*.
//
// The output is a build artifact derived from contracts/schemas/*.schema.json (the source of truth).
// Never edit it by hand: run `npm run generate:validators` (also run by `pretest` and `prebuild`),
// and commit the result. ./verify.sh fails when the checked-in files are stale.

import Ajv2020 from "ajv/dist/2020.js";
import standaloneCode from "ajv/dist/standalone/index.js";
import { readdirSync, readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const frontendDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const schemasDir = path.resolve(frontendDir, "../contracts/schemas");
const outputBase = path.resolve(frontendDir, "src/api/validators.generated");

const HEADER = `// GENERATED FILE - DO NOT EDIT.
// Built from contracts/schemas/*.schema.json by scripts/generate-validators.mjs (npm run generate:validators).
`;

// Same options as the former runtime instance, plus code.source/esm so Ajv keeps the generated source.
const ajv = new Ajv2020({ allErrors: true, strict: true, code: { source: true, esm: true, lines: true } });

const schemas = new Map(); // $id -> { file, schema }
for (const file of readdirSync(schemasDir).filter((f) => f.endsWith(".schema.json")).sort()) {
  const schema = JSON.parse(readFileSync(path.join(schemasDir, file), "utf8"));
  if (typeof schema.$id !== "string") throw new Error(`${file}: schema has no $id`);
  ajv.addSchema(schema);
  schemas.set(schema.$id, { file, schema });
}

// Export order matters: Ajv's standalone code emits a validator twice when a dependent schema
// reaches it through a root `$ref` before it is exported itself (the first emission is never marked
// complete). Exporting every schema after the schemas it references avoids the duplicate.
const exportsById = {};
for (const id of dependencyOrder(schemas)) {
  const name = exportNameFor(schemas.get(id).file);
  if (name in exportsById) throw new Error(`${schemas.get(id).file}: export name ${name} collides with another schema file`);
  exportsById[name] = id;
}

const js = toEsm(standaloneCode(ajv, exportsById));
const dts = `${HEADER}import type { ValidateFunction } from "ajv";
${Object.keys(exportsById)
  .map((name) => `export const ${name}: ValidateFunction;`)
  .join("\n")}
`;

writeFileSync(`${outputBase}.js`, js);
writeFileSync(`${outputBase}.d.ts`, dts);
console.log(`generated ${Object.keys(exportsById).length} validators -> ${path.relative(frontendDir, outputBase)}.{js,d.ts}`);

/** $ids sorted so that every schema comes after the schemas its `$ref`s point to (ties by $id). */
function dependencyOrder(schemas) {
  const order = [];
  const state = new Map(); // $id -> "visiting" | "done"
  const visit = (id) => {
    if (state.get(id) === "done") return;
    if (state.get(id) === "visiting") throw new Error(`circular $ref between schemas involving ${id}`);
    state.set(id, "visiting");
    for (const dep of [...referencedIds(schemas.get(id).schema, id)].sort()) visit(dep);
    state.set(id, "done");
    order.push(id);
  };
  for (const id of [...schemas.keys()].sort()) visit(id);
  return order;
}

/** $ids of other schema files that `node` references (directly or in nested keywords). */
function referencedIds(node, baseId, found = new Set()) {
  if (Array.isArray(node)) {
    for (const item of node) referencedIds(item, baseId, found);
  } else if (node && typeof node === "object") {
    for (const [key, value] of Object.entries(node)) {
      if (key === "$ref" && typeof value === "string") {
        const target = new URL(value, baseId);
        target.hash = "";
        if (target.href !== baseId && schemas.has(target.href)) found.add(target.href);
      } else {
        referencedIds(value, baseId, found);
      }
    }
  }
  return found;
}

/** "create-card-transactions-request.schema.json" -> "validateCreateCardTransactionsRequest" */
function exportNameFor(file) {
  const stem = file.slice(0, -".schema.json".length);
  return "validate" + stem.replace(/(^|-)([a-z0-9])/g, (_, __, c) => c.toUpperCase());
}

/**
 * Ajv's ESM standalone output still loads its runtime helpers with `require(...)`, which does not
 * exist in the browser. Rewrite those to imports and refuse anything else that needs a CommonJS
 * loader or dynamic code generation, so the artifact cannot silently regress the CSP goal.
 */
function toEsm(code) {
  const imports = [];
  let body = code.replace(
    /const (\w+) = require\("(ajv\/dist\/runtime\/\w+)"\)\.default;/g,
    (_, name, mod) => {
      imports.push(`import ${name} from "${mod}.js";`);
      return "";
    },
  );
  body = body.replace(/^"use strict";/, "");
  const out = `${HEADER}${imports.join("\n")}\n${body}`;
  for (const forbidden of [/\brequire\(/, /\bnew Function\b/, /\beval\(/]) {
    const line = out.split("\n").find((l) => forbidden.test(l));
    if (line !== undefined) throw new Error(`generated code still contains ${forbidden}: ${line.trim().slice(0, 120)}`);
  }
  return out;
}
