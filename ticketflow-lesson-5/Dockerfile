# Build stage: JDK 25 + Maven. Dependencies are cached in their own layer.
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

# Run stage: just a JRE and the jar.
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/target/ticketflow.jar ticketflow.jar
EXPOSE 8080
ENTRYPOINT ["java", "-Xms128m", "-Xmx256m", "-jar", "ticketflow.jar"]
