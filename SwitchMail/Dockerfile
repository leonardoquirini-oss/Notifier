# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Stage 1: build
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /app

# Le dipendenze in un layer a parte: cambiare una riga di codice non deve far riscaricare Maven.
# I Dockerfile fratelli lo dichiarano in un commento ma poi copiano subito anche i sorgenti.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src

# I test girano nella build per default. Nei fratelli -DskipTests e' hardcodato, quindi l'unica
# build che qualcuno lancia non esegue mai un test e la suite marcisce nel giro di un mese.
# Per l'emergenza: docker compose build --build-arg SKIP_TESTS=true  (oppure task build-sm-fast).
ARG SKIP_TESTS=false
RUN mvn -B clean package -DskipTests=${SKIP_TESTS}

# ---------------------------------------------------------------------------
# Stage 2: runtime
# ---------------------------------------------------------------------------
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

RUN apk add --no-cache curl tzdata

COPY --from=builder /app/target/switch-mail-*.jar app.jar

# Utente non privilegiato + directory scrivibile per il DB SQLite
RUN addgroup -S switchmail && adduser -S switchmail -G switchmail \
    && mkdir -p /app/data && chown -R switchmail:switchmail /app/data
ENV SWITCHMAIL_DB_PATH=/app/data/switchmail.db
USER switchmail

EXPOSE 8080

# Parametri del contratto di piattaforma (BERLink/prompt/HEALTH_CONTRACT.md): liveness pubblica,
# nessun check di dipendenze, cosi' il container non risulta unhealthy perche' BERLink e' giu'.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -fsS http://localhost:8080/api/health/live || exit 1

# exec form: senza, il SIGTERM non arriva al JVM e lo shutdown hook che finisce la mail
# in volo non gira mai, lasciando una riga IN_PROGRESS da recuperare al riavvio.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
