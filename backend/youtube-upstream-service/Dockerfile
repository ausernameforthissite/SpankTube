FROM gradle:9.8.2-jdk25 AS build
WORKDIR /workspace
COPY . .
ARG GRADLE_EXTRA_ARGS=""
RUN --mount=type=cache,id=spanktube-gradle,target=/home/gradle/.gradle \
    gradle :backend:youtube-upstream-service:bootJar --no-daemon ${GRADLE_EXTRA_ARGS}

FROM eclipse-temurin:25-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 python3-pip \
    && pip3 install --no-cache-dir --break-system-packages yt-dlp \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/backend/youtube-upstream-service/build/libs/*.jar /app/app.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
