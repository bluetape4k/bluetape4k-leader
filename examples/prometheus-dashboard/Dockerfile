FROM eclipse-temurin:21.0.6_7-jdk AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :examples:prometheus-dashboard:bootJar --no-daemon

FROM eclipse-temurin:21.0.6_7-jre
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /workspace/examples/prometheus-dashboard/build/libs/*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
