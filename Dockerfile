FROM maven:3.9-eclipse-temurin-8 AS build
WORKDIR /build

COPY pom.xml .
COPY ai-search-fccapi/pom.xml ai-search-fccapi/
COPY ai-search-feign/pom.xml ai-search-feign/
COPY ai-search-server/pom.xml ai-search-server/
COPY ai-search-rest/pom.xml ai-search-rest/
COPY ai-search-scheduled/pom.xml ai-search-scheduled/
RUN mvn -q -B dependency:go-offline

COPY . .
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:8-jre
WORKDIR /app

RUN groupadd --system app \
    && useradd --system --gid app --home-dir /app --shell /usr/sbin/nologin app \
    && mkdir -p /app/models/ner /var/log/ai-search \
    && chown -R app:app /app /var/log/ai-search

COPY --from=build --chown=app:app /build/ai-search-rest/target/*.jar app.jar

VOLUME ["/app/models/ner"]
ENV MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED=true
USER app
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS:-} -jar /app/app.jar"]
