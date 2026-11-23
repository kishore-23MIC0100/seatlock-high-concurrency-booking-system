# syntax=docker/dockerfile:1
FROM maven:3.9.12-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-noble
WORKDIR /app
COPY --from=build /build/target/seatlock-api-0.1.0.jar /app/seatlock.jar
COPY --chmod=0555 healthcheck.sh /app/healthcheck
USER 10001:10001
EXPOSE 8181
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/seatlock.jar"]
