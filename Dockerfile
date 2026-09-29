# ---------- build ----------
FROM gradle:8.12-jdk21 AS build
WORKDIR /workspace

COPY settings.gradle build.gradle ./
RUN gradle dependencies --no-daemon || true

COPY src ./src
ARG APP_VERSION=1.0.0
ENV APP_VERSION=${APP_VERSION}
RUN gradle bootJar --no-daemon -x test

# ---------- runtime ----------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S app && adduser -S app -G app
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
