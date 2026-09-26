# Multi-stage: build the static Vite bundle, then serve it from a minimal nginx image. Real
# caching/layering polish is a Phase 09 item (plans/phase-09-polish.md task 09.1), same as
# every backend Dockerfile in this repo - see services/api-gateway/Dockerfile for the rationale.
#
# Vite inlines VITE_* variables into the bundle at build time, not at container start, so they
# arrive here as build args rather than as `environment:` in docker-compose.yml the way every
# backend service's config does. Changing one means rebuilding this image.
#
# Build context is the REPO ROOT: docker-compose.yml sets `context: .` + `dockerfile: web/Dockerfile`.
FROM node:22-alpine AS build
WORKDIR /workspace
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ .
ARG VITE_OIDC_AUTHORITY
ARG VITE_OIDC_CLIENT_ID
ARG VITE_API_BASE_URL
ARG VITE_NOTIFICATION_BASE_URL
ENV VITE_OIDC_AUTHORITY=${VITE_OIDC_AUTHORITY} \
    VITE_OIDC_CLIENT_ID=${VITE_OIDC_CLIENT_ID} \
    VITE_API_BASE_URL=${VITE_API_BASE_URL} \
    VITE_NOTIFICATION_BASE_URL=${VITE_NOTIFICATION_BASE_URL}
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /workspace/dist /usr/share/nginx/html
COPY web/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
