import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import path from "node:path";

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: { "@": path.resolve(__dirname, "./src") },
  },
  server: {
    port: 5173,
    proxy: {
      "/api":  "http://localhost:8080",
      "/auth": "http://localhost:8080",
    },
  },
  build: {
    // No source maps in prod — reading the bundle is harder when there's
    // no map to humanise variable names. View-source still works (it's
    // the browser's call), it'll just look like minified gibberish.
    sourcemap: false,
    // Bigger chunks → fewer separately-fetchable JS files in DevTools.
    chunkSizeWarningLimit: 1500,
    // Drop console.* and debugger statements at build time so leftover
    // diagnostics don't leak in production.
    minify: "terser",
    terserOptions: {
      compress: {
        drop_console: true,
        drop_debugger: true,
        passes: 2,
      },
      mangle: {
        // Aggressively rename top-level + property names where possible
        // so the bundle is harder to read by eye.
        toplevel: true,
      },
      format: {
        comments: false,
      },
    },
  },
});
