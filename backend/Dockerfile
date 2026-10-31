FROM gradle:9.8.2-jdk25 AS build
WORKDIR /workspace
COPY . .
ARG SERVICE
ARG GRADLE_EXTRA_ARGS=""
RUN --mount=type=cache,id=spanktube-gradle,target=/home/gradle/.gradle \
    gradle ":backend:${SERVICE}:bootJar" --no-daemon ${GRADLE_EXTRA_ARGS}

FROM eclipse-temurin:25-jre
ARG SERVICE
WORKDIR /app
COPY --from=build "/workspace/backend/${SERVICE}/build/libs/*.jar" /app/app.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
