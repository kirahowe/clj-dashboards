# my-dashboard

A starting point for a dashboard of your own, built with
[clj-dashboards](https://github.com/kirahowe/clj-dashboards).

1. Copy this directory. The `:git/sha` entries in `deps.edn` pin the
   clj-dashboards version; `bb upgrade` moves them to the latest commit.
2. Edit `src/my_dashboard/app.clj`. Data goes in `resources/`.
3. Develop at the REPL: `bb dev`, then `(go)`, then open
   http://localhost:8080/. Re-evaluate your code and reload the page.

You need the [Clojure CLI](https://clojure.org/guides/install_clojure)
and [Babashka](https://babashka.org) (`bb tasks` lists the tasks).

| | |
|---|---|
| `bb serve` | serve the app on port 8080 (`PORT` changes it) |
| `bb dev` | a REPL with `(go)` and `(stop)` |
| `bb uber` | build `target/my-dashboard.jar` |
| `java -jar target/my-dashboard.jar --app my-dashboard.app/app` | run the jar |
| `bb docker` | build a container image (JRE only) |
| `bb upgrade` | move to the latest clj-dashboards |

The container listens on port 8080. Point health checks at `/_health`.
The browser talks to the app over plain HTTP: one long-lived
server-sent event stream per tab, plus small POSTs. Any proxy that
streams responses works; turn off response buffering for
`_dashboards/stream` if yours buffers. Serve it to browsers over
HTTP/2, since each open tab holds a connection, and use sticky sessions
if you run more than one instance. See
[Running it in production](https://github.com/kirahowe/clj-dashboards#running-it-in-production)
for proxy, shutdown and platform details.
