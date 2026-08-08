FROM node:20 AS builder
WORKDIR /app
COPY package*.json ./
RUN npm install
COPY . .
ARG VITE_API_URL
ENV VITE_API_URL=$VITE_API_URL
RUN npm run build
FROM nginx:stable-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /app/dist /usr/share/nginx/html
EXPOSE 80
# worker_processes NÃO é ajustado aqui: quem faz isso é a env
# NGINX_ENTRYPOINT_WORKER_PROCESSES_AUTOTUNE no docker-compose.yml, que aciona o script
# /docker-entrypoint.d/30-tune-worker-processes.sh da própria imagem — ele lê a quota de CPU do
# cgroup e reescreve a diretiva. Não tente passar `worker_processes` via `-g`: o nginx.conf base
# já a declara na linha 3 e o nginx aborta com "directive is duplicate".
CMD ["nginx", "-g", "daemon off;"]