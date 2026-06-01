# ── Build stage ────────────────────────────────────────────────────
FROM node:20-alpine AS build
WORKDIR /app

# API base is ALWAYS same-origin ("/") — nginx reverse-proxies /api/* and
# /auth/* to the Spring backend (see nginx.conf). We hardcode it here and the
# client also hardcodes "/", so a stray VITE_API_BASE env var on the host can
# never bake a bad absolute URL into the bundle again.
ENV VITE_API_BASE=/

COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

# ── Runtime stage — nginx serves the static SPA ────────────────────
FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
# Render injects PORT for web services; nginx default listens on 80
# which Render maps for free tier. No CMD override needed.
EXPOSE 80
