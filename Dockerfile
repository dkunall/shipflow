# build stage: resolve deps first so code changes don't re-download the world
FROM maven:3.9.9-eclipse-temurin-21-alpine AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline -DexcludeArtifactIds=shipflow
COPY src ./src
RUN mvn -B -q package -DskipTests \
 && java -Djarmode=tools -jar target/shipflow-*.jar extract --layers --launcher --destination /layers

# runtime stage: JRE only, non-root
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S -G app -u 10001 app \
 && apk add --no-cache wget
WORKDIR /app
COPY --from=build --chown=app:app /layers/dependencies/ ./
COPY --from=build --chown=app:app /layers/spring-boot-loader/ ./
COPY --from=build --chown=app:app /layers/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /layers/application/ ./
USER 10001
ENV PORT=8080 \
    SPRING_PROFILES_ACTIVE=json \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
  CMD wget -qO- http://127.0.0.1:${PORT}/actuator/health/readiness | grep -q UP || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
