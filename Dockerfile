FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn --batch-mode package

FROM eclipse-temurin:17-jre

WORKDIR /app
COPY --from=build /workspace/target/*.jar /app/app.jar
RUN mkdir -p /app/config /app/data \
    && chown -R 10001:10001 /app

USER 10001:10001
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar", "--spring.config.additional-location=optional:file:/app/config/"]
