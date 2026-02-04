# Multi-stage build per TFP Gateway

# Stage 1: Build con Maven
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /app

# Copia pom.xml e sorgenti
COPY pom.xml .
COPY src ./src

# Compila (include ActiveJDBC instrumentation)
RUN mvn clean package -DskipTests

# Stage 2: Runtime
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Utente non-root
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

# Copia JAR dal build stage
COPY --from=build /app/target/*.jar app.jar

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

EXPOSE 8080
EXPOSE 5005
ENV JAVA_TOOL_OPTIONS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5205"

ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", \
    "app.jar"]
