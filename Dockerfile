# syntax=docker/dockerfile:1
# One image: Angular UI (static) + Spring Boot API. Tests run in CI, not in the image build.

# ---- 1. web UI
FROM node:22-alpine AS web
WORKDIR /web
COPY web/package.json ./
RUN npm install --no-audit --no-fund
COPY web/ ./
RUN npm run build

# ---- 2. service jar (UI copied into static resources)
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY orchestrator-core orchestrator-core
COPY url-shortener-service url-shortener-service
COPY --from=web /web/dist/web/browser/ url-shortener-service/src/main/resources/static/
RUN ./gradlew :url-shortener-service:bootJar --no-daemon -x test

# ---- 3. runtime: JRE only, non-root
FROM eclipse-temurin:21-jre-jammy
RUN useradd --system --uid 10001 --no-create-home app
WORKDIR /app
COPY --from=build /src/url-shortener-service/build/libs/url-shortener.jar app.jar
USER 10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
