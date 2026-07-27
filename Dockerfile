FROM maven:3.9-eclipse-temurin-8 AS build
WORKDIR /build

# 可选的 Maven 镜像源。默认走 Maven Central；国内网络实测 Central 约 150 KB/s，
# 阿里云约 11 MB/s，构建时间相差数十倍。用法：
#   docker build --build-arg MAVEN_MIRROR_URL=https://maven.aliyun.com/repository/public .
# 不设则行为与原来完全一致，CI 在境外跑无需改动。
ARG MAVEN_MIRROR_URL=""
RUN if [ -n "$MAVEN_MIRROR_URL" ]; then \
      mkdir -p /root/.m2 && \
      printf '%s\n' \
        '<settings>' \
        '  <mirrors><mirror>' \
        '    <id>build-mirror</id><name>build mirror</name>' \
        "    <url>${MAVEN_MIRROR_URL}</url><mirrorOf>central</mirrorOf>" \
        '  </mirror></mirrors>' \
        '</settings>' > /root/.m2/settings.xml; \
    fi

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
