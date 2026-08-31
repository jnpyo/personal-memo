FROM nginxinc/nginx-unprivileged:1.29-alpine@sha256:0c79d56aee561a1d81c63f00eee5fb5fe29279560cdc55e91425133104c7fbe6

COPY --chown=101:101 nginx.conf /etc/nginx/nginx.conf

EXPOSE 8080

STOPSIGNAL SIGQUIT

HEALTHCHECK --interval=15s --timeout=3s --start-period=3s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/_internal/health || exit 1
