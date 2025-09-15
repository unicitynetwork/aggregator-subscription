# Multi-stage build for smaller image size
FROM gradle:8.5-jdk21 AS builder

WORKDIR /app

# Copy gradle files first for better caching
COPY build.gradle settings.gradle gradle.properties ./
COPY gradle ./gradle
COPY gradlew ./

# Download dependencies
RUN gradle dependencies --no-daemon

# Copy source code and resources
COPY src ./src

# Build the application including the fat JAR
RUN gradle build fatJar --no-daemon -x test

# Runtime stage
FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache bash

WORKDIR /app

# Copy the fat JAR
COPY --from=builder /app/build/libs/*-all.jar app.jar

# Create a non-root user
RUN addgroup -g 1001 -S appuser && \
    adduser -u 1001 -S appuser -G appuser

USER appuser

# Expose the proxy port
EXPOSE 8080

# Run the fat JAR directly
ENTRYPOINT ["sh", "-c", "java -jar app.jar $PROXY_ARGS"]