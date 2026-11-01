FROM gradle:9.8.2-jdk25 AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,id=spanktube-gradle,target=/home/gradle/.gradle \
    gradle :backend:catalog-service:bootJar --no-daemon ${GRADLE_EXTRA_ARGS}

FROM eclipse-temurin:25-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/backend/catalog-service/build/libs/*.jar /app/app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
