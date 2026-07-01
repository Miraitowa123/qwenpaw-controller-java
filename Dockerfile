FROM gradle:8.10-jdk17 AS builder

WORKDIR /workspace

COPY settings.gradle build.gradle ./
COPY src ./src

RUN gradle bootJar --no-daemon

FROM eclipse-temurin:17-jre

WORKDIR /app

RUN groupadd -r app && useradd -r -u 10001 -g app appuser

COPY --from=builder /workspace/build/libs/qwenpaw-controller-java-*.jar /app/app.jar

RUN chown -R appuser:app /app
USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
