# The dashboards server as a container image: the Clojure counterpart of
# rocker/shiny. It serves every app directory mounted under
# /srv/dashboards, each at /<directory-name>/.
#
#   docker build -f deploy/Dockerfile -t clj-dashboards .
#   docker run -p 8080:8080 -v "$PWD/examples/apps:/srv/dashboards" clj-dashboards
#
# The image includes the scicloj stack (tablecloth, plotje, fastmath,
# kindly). An app needing more can list it in a deps.edn next to its
# app.clj; it is fetched when the app first loads.
FROM clojure:temurin-21-tools-deps-bookworm-slim

RUN useradd --create-home --uid 1000 dashboards \
 && mkdir -p /opt/dashboards /srv/dashboards \
 && chown dashboards /opt/dashboards /srv/dashboards

USER dashboards
WORKDIR /opt/dashboards

COPY --chown=dashboards core/deps.edn core/deps.edn
COPY --chown=dashboards server/deps.edn server/deps.edn
COPY --chown=dashboards deploy/deps.edn deps.edn
# Fetch dependencies in their own layer, so code changes rebuild fast.
RUN clojure -P -M -m dashboards.server.main

COPY --chown=dashboards core core
COPY --chown=dashboards server server
COPY --chown=dashboards deploy/server.edn /opt/dashboards/server.edn

ENV PORT=8080 \
    HOST=0.0.0.0 \
    DASHBOARDS_CONFIG=/opt/dashboards/server.edn
EXPOSE 8080

ENTRYPOINT ["clojure", "-J-XX:MaxRAMPercentage=75", "-M", "-m", "dashboards.server.main"]
