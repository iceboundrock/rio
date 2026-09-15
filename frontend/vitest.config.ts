import { defineConfig, mergeConfig } from "vitest/config";
import viteConfig from "./vite.config.ts";

export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      environment: "node",
      include: ["src/**/*.test.{ts,tsx}"],
      // Node throws on eval / new Function in every test worker, so a schema validator that quietly
      // went back to runtime compilation fails here instead of in a browser with a strict CSP (see
      // frontend/AGENTS.md). The flag lives here rather than in NODE_OPTIONS because it must not reach
      // the Vite process: jsdom test files go through Vite's client transform, whose import lexer
      // decodes specifiers with eval and silently mis-parses every import when that is disabled.
      execArgv: ["--disallow-code-generation-from-strings"],
      // Vitest normally unwraps `exports.default` of a CommonJS dependency for a default import.
      // Browsers (through Vite's pre-bundle and production bundle) and plain Node do not: the
      // default import is `module.exports` itself. Load dependencies the way the browser does, so a
      // generated validator that only works under vitest's interop fails here, not in the browser.
      deps: { interopDefault: false },
    },
  }),
);
