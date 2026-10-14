FROM gradle:8.14.3-jdk21 AS build

WORKDIR /workspace
COPY settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY app/build.gradle.kts app/build.gradle.kts
COPY identity/build.gradle.kts identity/build.gradle.kts
COPY shared-ai/build.gradle.kts shared-ai/build.gradle.kts
COPY shared-audit/build.gradle.kts shared-audit/build.gradle.kts
COPY app/src app/src
COPY identity/src identity/src
COPY shared-ai/src shared-ai/src
COPY shared-audit/src shared-audit/src
RUN gradle --no-daemon :app:bootJar

FROM eclipse-temurin:21-jre

RUN apt-get update \
    && apt-get install --no-install-recommends --yes curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /workspace/app/build/libs/*.jar app.jar

EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
