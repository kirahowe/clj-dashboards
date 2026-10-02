# Build:  docker build -t my-dashboard .
# Run:    docker run -p 8080:8080 my-dashboard
FROM clojure:temurin-21-tools-deps-bookworm-slim AS build
WORKDIR /build
COPY deps.edn build.clj ./
RUN clojure -P -T:build && clojure -P
COPY src src
COPY resources resources
RUN clojure -T:build uber

FROM eclipse-temurin:21-jre
RUN useradd --create-home --uid 1000 app
USER app
WORKDIR /app
COPY --from=build /build/target/my-dashboard.jar app.jar
ENV PORT=8080 HOST=0.0.0.0
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar", \
            "--app", "my-dashboard.app/app", "--sanitize-errors", "--no-reload"]
