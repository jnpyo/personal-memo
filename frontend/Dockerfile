FROM node:24.6-alpine AS build

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci --ignore-scripts --no-audit --no-fund

COPY . .

ARG VITE_OWNER_REMOTE_APP_HOSTNAME=
ENV CI=true \
    VITE_OWNER_REMOTE_APP_HOSTNAME=${VITE_OWNER_REMOTE_APP_HOSTNAME}
RUN npm run lint
RUN npm run test
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.29-alpine AS runtime

ENV API_PROXY_TARGET=http://backend:8080 \
    NGINX_ENVSUBST_FILTER=^API_PROXY_TARGET$

USER root
RUN mkdir -p /etc/nginx/personal-listeners /run/personal-memo/tls \
    && chown -R 101:101 /etc/nginx/personal-listeners /run/personal-memo

COPY --from=build --chown=101:101 /app/dist /usr/share/nginx/html
COPY --chown=101:101 nginx/default.conf /etc/nginx/templates/default.conf.template

EXPOSE 5173 8443

USER 101:101

STOPSIGNAL SIGQUIT

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:5173/ || exit 1
