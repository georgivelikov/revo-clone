# Multi-stage, layered build. See this file for the rationale; every other service's
# Dockerfile follows the same three stages and just points back here.
#
# Build context is the REPO ROOT, not this directory: docker-compose.yml sets
# `context: .` + `dockerfile: services/api-gateway/Dockerfile`.
#
# Stage 1: build the whole reactor (services/pom.xml's <modules> list means Maven needs every
# service directory present even to build just this one, and this service itself depends on
# platform/common-*) and produce this service's executable fat jar.
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml .
COPY platform platform
COPY services services
# Tests already ran in the gate before this image is ever built; skipping them here avoids
# re-running the whole reactor's test suite (including Testcontainers) on every image build.
RUN mvn -q -pl services/api-gateway -am -DskipTests package

# Stage 2: split the fat jar into layers with Boot's `tools` jarmode (the jarmode was called
# `layertools` pre-3.3; 4.1.1 uses `tools` - see docs/spring-boot-4-notes.md) so dependencies,
# the loader and application code land in separate directories that become separate Docker
# layers below. Dependencies change far less often than application code, so a code-only
# rebuild only re-pulls the thin `application` layer, not the whole dependency set.
FROM eclipse-temurin:25-jre AS extract
WORKDIR /builder
COPY --from=build /workspace/services/api-gateway/target/*.jar application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# Stage 3: the runtime image, assembled layer by layer rather than from one fat jar COPY.
FROM eclipse-temurin:25-jre
WORKDIR /application
COPY --from=extract /builder/extracted/dependencies/ ./
COPY --from=extract /builder/extracted/spring-boot-loader/ ./
COPY --from=extract /builder/extracted/snapshot-dependencies/ ./
COPY --from=extract /builder/extracted/application/ ./
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "application.jar"]
