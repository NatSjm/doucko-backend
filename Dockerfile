# Backend image for compose.yaml. Builds inside Docker from the pinned contract/ submodule, so the
# host needs no JDK. Generated code is produced here and never committed (NFR-12).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY .mvn .mvn
COPY contract/api contract/api
COPY src/main src/main
RUN mvn -B -q -DskipTests -Dspotless.check.skip=true -Djacoco.skip=true package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S doucko && adduser -S doucko -G doucko
USER doucko
WORKDIR /app
COPY --from=build /build/target/backend-*-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
