FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app \
    && mkdir -p /app/models/ner /var/log/ai-search \
    && chown -R app:app /app /var/log/ai-search
COPY target/ai-search-backend-2.0.0.jar /app/app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS} -jar /app/app.jar"]
