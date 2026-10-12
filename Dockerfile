# ---- build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package && mv target/hookshot-*.jar app.jar

# ---- runtime stage ----
FROM eclipse-temurin:21-jre
RUN useradd --system --create-home hookshot
USER hookshot
WORKDIR /app
COPY --from=build /build/app.jar app.jar
EXPOSE 8080
# Liveness/readiness: GET /actuator/health
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
