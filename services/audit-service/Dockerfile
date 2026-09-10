# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY services/shared-contracts ./shared-contracts
COPY services/service-commons ./service-commons
COPY services/audit-service ./audit-service
RUN cd shared-contracts && mvn -q -DskipTests install \
 && cd ../service-commons && mvn -q -DskipTests install \
 && cd ../audit-service && mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/audit-service/target/audit-service-1.0.0-SNAPSHOT.jar app.jar
EXPOSE 8087
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
