# See services/api-gateway/Dockerfile for the rationale (minimal, Phase-09 does the real
# multi-stage layered build). Build context is the repo root.
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml .
COPY platform platform
COPY services services
RUN mvn -q -pl services/notification-service -am -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /workspace/services/notification-service/target/*.jar app.jar
EXPOSE 8088
ENTRYPOINT ["java", "-jar", "app.jar"]
