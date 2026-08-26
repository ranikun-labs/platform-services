FROM gradle:8.13-jdk17 AS build

WORKDIR /workspace
COPY settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY app/build.gradle.kts app/build.gradle.kts
COPY identity/build.gradle.kts identity/build.gradle.kts
COPY app/src app/src
COPY identity/src identity/src
RUN gradle --no-daemon :app:bootJar

FROM eclipse-temurin:17-jre

RUN apt-get update \
    && apt-get install --no-install-recommends --yes curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /workspace/app/build/libs/*.jar app.jar

EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
