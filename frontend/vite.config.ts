import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  // En desarrollo /api y /config.js los atiende el backend (python) en el puerto 8080
  server: {
    port: 5173,
    proxy: {
      "/api": process.env.API_URL ?? "http://localhost:8080",
      "/config.js": process.env.API_URL ?? "http://localhost:8080",
    },
  },
});
