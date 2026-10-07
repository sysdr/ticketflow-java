# Build stage — Maven + JDK 25
# Confirm the exact maven:3.9-eclipse-temurin-25 tag on Docker Hub before first use.
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
# Cache dependencies layer separately — rebuild only when pom.xml changes
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

# Runtime stage — JRE only, smaller image
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/target/ticketflow-*.jar app.jar

# Non-root user for security
RUN useradd -r -u 1001 ticketflow
USER ticketflow

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
