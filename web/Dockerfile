# Multi-stage: build the static Vite bundle, then serve it from a minimal nginx image. The
# layering equivalent of the backend services' jarmode split (services/api-gateway/Dockerfile)
# is the COPY order below: package.json/package-lock.json are copied and `npm ci` run before
# the rest of the source is copied in, so the node_modules layer only invalidates when a
# dependency changes, not on every source edit.
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
