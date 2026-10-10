FROM maven:3.9.11-eclipse-temurin-21 AS build
ARG APP
WORKDIR /src
COPY backend/ .
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -pl "$APP" -am -DskipTests package && cp "$APP/target/$APP-0.1.0.jar" /app.jar

FROM eclipse-temurin:21.0.8_9-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* && useradd --uid 10001 --create-home campus
WORKDIR /app
COPY --from=build /app.jar /app.jar
USER campus
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app.jar"]
