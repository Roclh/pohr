# syntax=docker/dockerfile:1.7

FROM ghcr.io/graalvm/native-image-community:25 AS builder
WORKDIR /build

COPY gradle gradle
COPY gradlew gradlew
COPY settings.gradle build.gradle ./

# Генерируем свой gradle.properties — без Windows-путей и без auto-detect
RUN printf 'org.gradle.jvmargs=-Xmx4g\norg.gradle.parallel=false\norg.gradle.daemon=false\n' \
        > gradle.properties

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew dependencies --no-daemon

COPY src src

RUN --mount=type=cache,target=/root/.gradle \
    --mount=type=cache,target=/root/.cache \
    ./gradlew nativeCompile --no-daemon

# --- nginx ---
FROM nginx:1.27-bookworm AS nginx-src

# --- caddy ---
FROM caddy:2.8 AS caddy-src

# --- runtime ---
FROM debian:bookworm-slim AS runtime

RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates \
        libpcre2-8-0 \
        zlib1g \
        libssl3 \
        libcrypt1 \
        openssl \
        tini \
    && rm -rf /var/lib/apt/lists/*

# Пользователь и директории для nginx
RUN groupadd -r nginx && useradd -r -g nginx -s /sbin/nologin -d /var/lib/nginx nginx \
 && mkdir -p /var/cache/nginx /var/log/nginx /run /var/lib/nginx \
 && chown -R nginx:nginx /var/cache/nginx /var/log/nginx /var/lib/nginx

COPY --from=builder /build/build/native/nativeCompile/pohr /app/pohr
COPY --from=nginx-src /usr/sbin/nginx /app/xray/edge/bin/nginx
COPY --from=caddy-src /usr/bin/caddy  /app/xray/edge/bin/caddy

WORKDIR /app
EXPOSE 8080 8443 443 80
ENTRYPOINT ["/usr/bin/tini", "--", "/app/pohr"]