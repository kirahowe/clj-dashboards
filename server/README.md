# dashboards-server

The hosting runtime for dashboards apps, the counterpart of Shiny Server.
It's an http-kit server that runs each browser tab's session over a
server-sent event stream (via the Datastar Clojure SDK's http-kit
adapter, with the page posting its Datastar signals back), and serves client assets, downloads and each app's `www/` files.
It also has a `/_health` endpoint, which answers 503 while the server's
apps are still loading and once it starts shutting down.

From this repository, `bb serve` runs the CLI below with the examples
on the classpath.

```sh
clojure -M -m dashboards.server.main --help
clojure -M -m dashboards.server.main --app my.dashboard/app          # one app at /
clojure -M -m dashboards.server.main --app /a=my.a/app --app /b=apps/b
clojure -M -m dashboards.server.main --apps-dir /srv/dashboards      # a folder of apps
clojure -M -m dashboards.server.main --config server.edn
```

From Clojure:

```clojure
(require '[dashboards.server :as server])
(server/run-app! #'app)                         ; REPL development
(def s (server/start! {:port 8080 :apps [{:path "/" :app 'my.dashboard/app}]}))
(server/stop! s)
```

Stopping (or SIGTERM, for the CLI) drains first: `/_health` and new
streams get 503, the server waits `:shutdown-delay-ms` (default 0), and
then it closes the open streams, so browsers reconnect to another
instance or to this one after a restart, before ending the sessions.
For HTTP/2, proxies, timeouts and sticky
sessions across replicas, see
[Running it in production](../README.md#running-it-in-production).

An app source can be an app value, a var (re-read for each page load), a
qualified symbol, or a directory containing `app.clj`. Directories reload
when their files change, and a `deps.edn` beside `app.clj` can add
libraries at load time (the server must run under the Clojure CLI for
this). The server listens at once, then loads every app it is given, and
every one already in `--apps-dir`, in the background and one at a time,
so the first visitors don't wait for them; `/_health` reports
`"starting"` (503) until each has loaded, failed, or run out of time
(`:app-load-timeout-ms`, default 60000). A request for an app still
loading waits no longer than that. Apps load through the server's own
queue, which nothing else waits on, so sessions can require namespaces
while apps load. A hung `app.clj` fails after the timeout and stops
holding up the apps queued behind it; if it ignores being interrupted
(blocked on a socket, say), its thread is abandoned until it returns or
the server restarts. An app that fails or runs out of time is logged
and answers 503 with its error at once, without loading again, until
its files change; the rest are served as usual.

```clojure
io.github.kirahowe/dashboards-server
{:git/url "https://github.com/kirahowe/clj-dashboards" :git/sha "…" :deps/root "server"}
```
