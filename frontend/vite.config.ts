import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import path from "node:path";

// The shared JSON Schemas live outside this package; "@schemas/x.schema.json" points at them.
const schemasDir = path.resolve(import.meta.dirname, "../contracts/schemas");

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: { "@schemas": schemasDir },
  },
  server: {
    port: 5173,
    // Frontend calls relative "/api/..." URLs; Vite forwards them to the Ktor backend.
    proxy: {
      "/api": "http://localhost:8080",
    },
    fs: { allow: [path.resolve(import.meta.dirname, "..")] },
  },
});
