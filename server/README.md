# dashboards-server

The hosting runtime for dashboards apps, the counterpart of Shiny Server.
It's an http-kit server that runs each browser tab's session over a
server-sent event stream (via the Datastar Clojure SDK's http-kit
adapter, with the page posting its Datastar signals back), and serves client assets, downloads and each app's `www/` files.
It also has a `/_health` endpoint, which answers 503 once the server
starts shutting down.

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
this).

```clojure
io.github.kirahowe/dashboards-server
{:git/url "https://github.com/kirahowe/clj-dashboards" :git/sha "…" :deps/root "server"}
```
