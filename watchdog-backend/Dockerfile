# Multi-stage build for watchdog-backend.
#
# IMPORTANT: build context must be the REPO ROOT (not watchdog-backend/) so
# the multi-module parent pom is available to Maven. The root
# docker-compose.yml sets:
#   build:
#     context: .
#     dockerfile: watchdog-backend/Dockerfile
#
# If you run `docker build` directly, do it from the repo root:
#   docker build -f watchdog-backend/Dockerfile -t watchdog-backend .

# ─── builder ──────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /build

RUN apk add --no-cache maven

# 1. Copy the parent pom and the child pom first so Maven can pre-fetch
#    dependencies into a cached layer (rebuilt only when poms change).
COPY pom.xml ./pom.xml
COPY watchdog-backend/pom.xml ./watchdog-backend/pom.xml
RUN mvn -B -f watchdog-backend/pom.xml dependency:go-offline

# 2. Copy the actual source and build the fat jar.
COPY watchdog-backend/src ./watchdog-backend/src
RUN mvn -B -f watchdog-backend/pom.xml package -DskipTests

# ─── runtime ──────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

RUN addgroup -S watchdog && adduser -S watchdog -G watchdog
USER watchdog

COPY --from=builder /build/watchdog-backend/target/watchdog-backend-*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "app.jar"]
