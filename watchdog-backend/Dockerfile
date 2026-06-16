# Build context = watchdog-backend/ (single-module flat layout)
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app

RUN apk add --no-cache maven

# Cache deps first
COPY pom.xml .
RUN mvn -B dependency:go-offline

# Compile + package
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

RUN addgroup -S watchdog && adduser -S watchdog -G watchdog
USER watchdog

COPY --from=builder /app/target/watchdog-backend-*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "app.jar"]
