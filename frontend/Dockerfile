# syntax=docker/dockerfile:1

# ---- build stage ----
# Angular CLI 22.x requires Node >= 22.22.3; Node 20 LTS is too old for the
# resolved Angular 22.1.x deps, so build on the Node 22 LTS image.
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build -- --configuration production

# ---- serve stage ----
FROM nginx:1.27-alpine
# nginx config that serves the Angular SPA and proxies /api/* to the backend
# services by their docker-compose service names.
COPY <<'NGINX' /etc/nginx/conf.d/default.conf
server {
    listen 80;
    server_name _;
    root /usr/share/nginx/html;
    index index.html;

    # SPA fallback: serve index.html for client-side routes.
    location / {
        try_files $uri $uri/ /index.html;
    }

    # API proxying to the backend services (by docker-compose service name).
    location /api/v1/cases/      { proxy_pass http://case-service:8084; }
    location /api/v1/cases       { proxy_pass http://case-service:8084; }
    location /api/v1/custodians/ { proxy_pass http://case-service:8084; }
    location /api/v1/custodians  { proxy_pass http://case-service:8084; }
    location /api/v1/search/     { proxy_pass http://search-service:8083; }
    location /api/v1/search      { proxy_pass http://search-service:8083; }
    location /api/v1/saved-searches/ { proxy_pass http://search-service:8083; }
    location /api/v1/saved-searches  { proxy_pass http://search-service:8083; }
    location /api/v1/holds/      { proxy_pass http://hold-retention-service:8085; }
    location /api/v1/holds       { proxy_pass http://hold-retention-service:8085; }
    # A disposition run deletes synchronously, so its request stays open for
    # as long as the deletion takes. nginx's 60s default read timeout drops the
    # connection mid-run and returns 504 while the deletion carries on
    # server-side, which reads on screen as a failure that isn't one.
    location /api/v1/retention/  {
        proxy_pass http://hold-retention-service:8085;
        proxy_read_timeout 1800s;
        proxy_send_timeout 1800s;
    }
    location /api/v1/retention   {
        proxy_pass http://hold-retention-service:8085;
        proxy_read_timeout 1800s;
        proxy_send_timeout 1800s;
    }
    location /api/v1/exports/    { proxy_pass http://export-service:8086; }
    location /api/v1/exports     { proxy_pass http://export-service:8086; }
    location /api/v1/audit/      { proxy_pass http://audit-service:8087; }
    location /api/v1/audit       { proxy_pass http://audit-service:8087; }
    location /api/v1/messages/   { proxy_pass http://archive-service:8082; }
    location /api/v1/messages    { proxy_pass http://archive-service:8082; }
    # ingestion-service serves /api/v1/messages too, so prefix routing alone
    # cannot tell it apart from the archive. The frontend addresses it as
    # /api/v1/ingestion and the prefix is rewritten here (a proxy_pass with a
    # URI replaces the matched location), mirroring proxy.conf.mjs in dev.
    location /api/v1/ingestion   { proxy_pass http://ingestion-service:8081/api/v1/messages; }
}
NGINX

COPY --from=build /app/dist/frontend/browser /usr/share/nginx/html
EXPOSE 80
