# syntax=docker/dockerfile:1
# Build context is the repo root (see docker-compose.yml) so we can COPY the
# sibling services/shared-contracts module that this service depends on.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Copy the shared contracts module first and install it, then build this service.
COPY services/shared-contracts ./shared-contracts
COPY services/service-commons ./service-commons
COPY services/ingestion-service ./ingestion-service
RUN cd shared-contracts && mvn -q -DskipTests install \
 && cd ../service-commons && mvn -q -DskipTests install \
 && cd ../ingestion-service && mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/ingestion-service/target/ingestion-service-1.0.0-SNAPSHOT.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
