import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In local dev (`npm run dev`) API calls are proxied to the Spring Boot backend.
// In Docker, nginx does the same proxying (see nginx.conf).
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': process.env.VITE_API_PROXY || 'http://localhost:8080',
    },
  },
  build: {
    chunkSizeWarningLimit: 1500,
  },
});
