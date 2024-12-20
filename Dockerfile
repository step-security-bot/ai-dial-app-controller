FROM gradle:8.10-jdk21-alpine@sha256:8721f7ba7260103b5157925e2666c6fd611e7741905fb032dfa8dbd8d12f4b2e AS cache

WORKDIR /home/gradle/src
ENV GRADLE_USER_HOME="/cache"
COPY build.gradle settings.gradle ./
# just pull dependencies for cache
RUN gradle --no-daemon build --stacktrace

FROM gradle:8.10-jdk21-alpine@sha256:8721f7ba7260103b5157925e2666c6fd611e7741905fb032dfa8dbd8d12f4b2e AS builder

COPY --from=cache /cache /home/gradle/.gradle
COPY --chown=gradle:gradle . /home/gradle/src

WORKDIR /home/gradle/src
RUN gradle --no-daemon build --stacktrace -PdisableCompression=true -x test

RUN mkdir /build && tar -xf /home/gradle/src/build/distributions/ai-dial-app-controller*.tar --strip-components=1 -C /build

FROM eclipse-temurin:21-jdk-alpine@sha256:4909fb9ab52e3ce1488cc6e6063da71a0f9f9833420cc254fe03bbe25daec9e0

ENV OTEL_TRACES_EXPORTER="none"
ENV OTEL_METRICS_EXPORTER="none"
ENV OTEL_LOGS_EXPORTER="none"

WORKDIR /app

RUN adduser -u 1001 --disabled-password --gecos "" appuser

COPY --from=builder --chown=appuser:appuser /build/ .
RUN chown -R appuser:appuser /app

USER appuser

HEALTHCHECK --start-period=30s --interval=1m --timeout=3s \
  CMD wget --no-verbose --spider --tries=1 http://localhost:8080/health || exit 1

EXPOSE 8080 9464

ENTRYPOINT ["/app/bin/ai-dial-app-controller"]
