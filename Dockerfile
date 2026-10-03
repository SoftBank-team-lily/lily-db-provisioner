# ---------- build ----------
FROM gradle:8.12-jdk21 AS build
WORKDIR /workspace

COPY settings.gradle build.gradle ./
RUN gradle dependencies --no-daemon || true

COPY src ./src
ARG APP_VERSION=1.0.0
ENV APP_VERSION=${APP_VERSION}
RUN gradle bootJar --no-daemon -x test

# ---------- pgroll (무중단 스키마 변경 CLI, 프로젝트 DB 에 init 할 때 쓴다) ----------
FROM alpine:3.20 AS pgroll
ARG TARGETARCH=amd64
ARG PGROLL_VERSION=0.16.3
ARG PGROLL_SHA256_AMD64=e86ccd704f7d99a0794a75a6c1d1095d30c8bc8c21a22b793914db8340bad182
ARG PGROLL_SHA256_ARM64=c37f41f29b8e5784a47f91069975bd397448cf1014d47cd426680f18a84804a7
RUN set -eu; \
    if [ "$TARGETARCH" = "arm64" ]; then sum=$PGROLL_SHA256_ARM64; else sum=$PGROLL_SHA256_AMD64; fi; \
    wget -qO /pgroll "https://github.com/xataio/pgroll/releases/download/v${PGROLL_VERSION}/pgroll.linux.${TARGETARCH}"; \
    echo "$sum  /pgroll" | sha256sum -c -; \
    chmod +x /pgroll

# ---------- runtime ----------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S app && adduser -S app -G app
COPY --from=pgroll /pgroll /usr/local/bin/pgroll
COPY --from=build /workspace/build/libs/app.jar app.jar
USER app

ARG APP_VERSION=1.0.0
ENV APP_VERSION=${APP_VERSION} \
    SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"

EXPOSE 8080

# 플랫폼의 헬스체크 대상
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health/readiness | grep -q UP || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
