import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// En desarrollo /api se redirige al backend; en producción lo hace nginx.
export default defineConfig({
  plugins: [react()],
  server: { port: 5173, proxy: { "/api": process.env.API_URL ?? "http://localhost:8000" } },
});
