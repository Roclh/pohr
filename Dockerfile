FROM ghcr.io/graalvm/native-image-community:25 AS builder
ARG APP_HOME=/app
WORKDIR ${APP_HOME}

COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle gradle.properties ./
RUN chmod +x gradlew

RUN --mount=type=cache,target=/root/.gradle \
    --mount=type=cache,target=/root/.gradle-wrapper \
    ./gradlew --no-daemon dependencies

COPY src src
RUN --mount=type=cache,target=/root/.gradle \
    --mount=type=cache,target=/root/.gradle-wrapper \
    ./gradlew --no-daemon nativeCompile

FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates tzdata \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -m -u 1000 -s /bin/bash pohr

WORKDIR /app
COPY --from=builder /app/build/native/nativeCompile/pohr /app/pohr
RUN chmod +x /app/pohr

RUN mkdir -p /app/data /app/scripts /app/xray/bin /app/xray/config \
    && chown -R pohr:pohr /app

USER pohr

ENV SCRIPTS_HOME=/app/scripts \
    SPRING_PROFILES_ACTIVE=prod \
    XRAY_AUTO_INSTALL=true \
    EU_XRAY_VERSION=latest

EXPOSE 8080 8443

ENTRYPOINT ["/app/pohr"]