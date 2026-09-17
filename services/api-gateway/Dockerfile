# Minimal functional image for local docker-compose use. Multi-stage layered builds with
# proper caching are a Phase 09 polish item (plans/phase-09-polish.md task 09.1) — this stays
# deliberately simple: build the whole reactor (services/pom.xml's <modules> list means Maven
# needs every service directory present even to build just this one, and this service itself
# depends on platform/common-*), then run the fat jar on a slim JRE.
#
# Build context is the REPO ROOT, not this directory: docker-compose.yml sets
# `context: .` + `dockerfile: services/api-gateway/Dockerfile`.
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml .
COPY platform platform
COPY services services
# Tests already ran in the gate before this image is ever built; skipping them here avoids
# re-running the whole reactor's test suite (including Testcontainers) on every image build.
RUN mvn -q -pl services/api-gateway -am -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /workspace/services/api-gateway/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
