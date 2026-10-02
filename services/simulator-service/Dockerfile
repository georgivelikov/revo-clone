# Multi-stage, layered build - see services/api-gateway/Dockerfile for the full rationale.
# Build context is the repo root.
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml .
COPY platform platform
COPY services services
RUN mvn -q -pl services/simulator-service -am -DskipTests package

FROM eclipse-temurin:25-jre AS extract
WORKDIR /builder
COPY --from=build /workspace/services/simulator-service/target/*.jar application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:25-jre
WORKDIR /application
COPY --from=extract /builder/extracted/dependencies/ ./
COPY --from=extract /builder/extracted/spring-boot-loader/ ./
COPY --from=extract /builder/extracted/snapshot-dependencies/ ./
COPY --from=extract /builder/extracted/application/ ./
EXPOSE 8089
ENTRYPOINT ["java", "-jar", "application.jar"]
