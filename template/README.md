# my-dashboard

A starting point for a dashboard of your own, built with
[clj-dashboards](https://github.com/kirahowe/clj-dashboards).

1. Copy this directory. The `:git/sha` entries in `deps.edn` pin the
   clj-dashboards version; point them at a newer commit to upgrade.
2. Edit `src/my_dashboard/app.clj`. Data goes in `resources/`.
3. Develop at the REPL: `clj -M:dev`, then `(go)`, then open
   http://localhost:8080/. Re-evaluate your code and reload the page.

| | |
|---|---|
| `clojure -M:run` | serve the app on port 8080 (`PORT` changes it) |
| `clojure -T:build uber` | build `target/my-dashboard.jar` |
| `java -jar target/my-dashboard.jar --app my-dashboard.app/app` | run the jar |
| `docker build -t my-dashboard .` | build a container image (JRE only) |

The container listens on port 8080. Point health checks at `/_health`.
If you put it behind a proxy, make sure the proxy forwards websockets.
