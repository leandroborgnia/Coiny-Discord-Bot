# syntax=docker/dockerfile:1
# Single multi-stage image: build stage runs Maven to produce the jar; runtime stage is a slim JRE.
# The SAME image serves dev-container parity runs and production — environments differ only by
# Spring profile + env vars, never by forking this Dockerfile.

# --- Build stage ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q -DskipTests dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -q -DskipTests package

# --- Runtime stage ---
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
RUN useradd --system --no-create-home coiny
COPY --from=build /workspace/target/*.jar app.jar
USER coiny
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
