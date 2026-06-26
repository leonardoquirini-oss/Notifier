# Multi-stage build for Event Control Processor Service

# Stage 1: Build
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn clean package -DskipTests

# Stage 2: Runtime
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

RUN apk add --no-cache curl

COPY --from=builder /app/target/event-control-processor-*.jar app.jar

RUN addgroup -S eventcontrolprocessor && adduser -S eventcontrolprocessor -G eventcontrolprocessor
USER eventcontrolprocessor

EXPOSE 8080
EXPOSE 5018
#ENV JAVA_TOOL_OPTIONS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5018"

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD curl -f http://localhost:8080/api/health/live || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]

CMD []
