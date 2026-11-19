import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// The UI is a thin explorer over the service API; /api is proxied to the Ktor app.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target: "http://localhost:8080",
        rewrite: (path) => path.replace(/^\/api/, ""),
      },
    },
  },
});
