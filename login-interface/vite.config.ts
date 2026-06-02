import { defineConfig } from 'vite'
import react, { reactCompilerPreset } from '@vitejs/plugin-react'
import babel from '@rolldown/plugin-babel'
import tailwindcss from '@tailwindcss/vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    react(),
    babel({ presets: [reactCompilerPreset()] }),
    tailwindcss(),
  ],
  // BFF: o SPA fala com o gateway (:8081) pela mesma origem (:5173) via proxy.
  // Mantém o cookie de sessão como first-party e dispensa SameSite=None/CORS em dev.
  // Só o callback OAuth2 (/login/oauth2/**) vai ao gateway; /login puro é rota do SPA.
  server: {
    proxy: {
      '/users': { target: 'http://localhost:8081', changeOrigin: true },
      '/oauth2': { target: 'http://localhost:8081', changeOrigin: true },
      '/login/oauth2': { target: 'http://localhost:8081', changeOrigin: true },
      '/logout': { target: 'http://localhost:8081', changeOrigin: true },
    },
  },
})
