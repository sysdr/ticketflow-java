# Build stage: compiles and tests inside the container, so the host needs no JDK or Maven.
FROM maven:3-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q clean package

# Run stage: just a JRE and the jar.
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/target/ticketflow-0.2.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
