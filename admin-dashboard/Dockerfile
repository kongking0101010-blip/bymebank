# ── Build stage ────────────────────────────────────────────────────
FROM node:20-alpine AS build
WORKDIR /app

# VITE_API_BASE is baked into the bundle at build time (Vite compile-time
# env). Render passes it via --build-arg from the dashboard service's
# VITE_API_BASE env var.
ARG VITE_API_BASE=/
ENV VITE_API_BASE=$VITE_API_BASE

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
