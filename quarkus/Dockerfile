# DBA Manager (Quarkus). Una sola imagen: API e interfaz web en el mismo puerto.
# La configuración llega por variables de entorno (ver .env.example).

FROM maven:3.9.16-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21.0.13_9-jre-alpine-3.23
RUN apk upgrade --no-cache && addgroup -S -g 1001 app && adduser -S -u 1001 -G app -h /app app
WORKDIR /app
# Grupo 0 con los mismos permisos: funciona también con el UID arbitrario de OpenShift
COPY --from=build --chown=1001:0 --chmod=g=u /src/target/quarkus-app/ /app/
USER 1001
ENV PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s CMD wget -qO- http://127.0.0.1:${PORT}/q/health/live >/dev/null || exit 1
CMD ["sh", "-c", "exec java $JAVA_OPTS -jar /app/quarkus-run.jar"]
