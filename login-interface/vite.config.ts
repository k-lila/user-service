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
  // Só o callback OAuth2 (/login/oauth2/**) vai ao gateway. /login NÃO está aqui — e desde o
  // ADR-019 também não é rota do SPA (o path pertence ao IdP; <Login/> mora em /). Em dev isso
  // não quebra o fluxo: OAUTH_AUTHORIZATION_URI manda o browser DIRETO ao localhost:8082, então
  // o formulário do IdP é servido na origem dele e nunca passa por este proxy. /login é a ÚNICA
  // divergência deliberada em relação ao nginx (Docker/deploy), que precisa proxiar o path porque
  // lá a topologia é de hostname único. Efeito colateral conhecido: abrir :5173/login em dev não
  // casa nenhuma rota do React Router e renderiza página em branco.
  // /swagger-ui e /v3/api-docs andam em par e espelham o nginx: o link do ProfileBox aponta para o
  // primeiro, e o swagger-initializer busca a spec no segundo — proxiar só um deixa a UI vazia.
  server: {
    proxy: {
      '/v1/users': { target: 'http://localhost:8081', changeOrigin: true },
      '/oauth2': { target: 'http://localhost:8081', changeOrigin: true },
      '/login/oauth2': { target: 'http://localhost:8081', changeOrigin: true },
      '/logout': { target: 'http://localhost:8081', changeOrigin: true },
      '/swagger-ui': { target: 'http://localhost:8081', changeOrigin: true },
      '/v3/api-docs': { target: 'http://localhost:8081', changeOrigin: true },
    },
  },
})
