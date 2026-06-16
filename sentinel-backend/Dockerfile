# Single-module backend build.
#
# Build context = ./sentinel-backend (the directory containing this Dockerfile).
# Set in docker-compose.yml as:
#   build:
#     context: ./sentinel-backend
#     dockerfile: Dockerfile
#
# To build directly: docker build -t sentinel-backend ./sentinel-backend

# ─── builder ──────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /build

RUN apk add --no-cache maven

# Cache deps first — pom only, so this layer rebuilds only when pom changes.
COPY pom.xml ./pom.xml
RUN mvn -B dependency:go-offline

# Compile + package
COPY src ./src
RUN mvn -B -DskipTests package

# ─── runtime ──────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

RUN addgroup -S sentinel && adduser -S sentinel -G sentinel
USER sentinel

COPY --from=builder /build/target/sentinel-backend-*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "app.jar"]
