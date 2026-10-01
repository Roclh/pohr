# syntax=docker/dockerfile:1

# === Этап 1: Сборка нативного бинарника ===
FROM ghcr.io/graalvm/graalvm-community:25 AS builder
WORKDIR /app

COPY gradlew .
COPY gradle/ gradle/
COPY settings.gradle build.gradle ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true

COPY src/ src/
RUN ./gradlew nativeCompile --no-daemon

# === Этап 2: Runtime (без Xray — он ставится в volume) ===
FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates curl unzip \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --system --create-home --shell /bin/false pohr

WORKDIR /app
COPY --from=builder /app/build/native/nativeCompile/pohr /app/pohr
RUN chmod +x /app/pohr

RUN mkdir -p /app/data /app/xray/bin /app/xray/config \
    && chown -R pohr:pohr /app

USER pohr

EXPOSE 8080
ENTRYPOINT ["/app/pohr"]