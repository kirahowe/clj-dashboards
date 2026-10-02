# dashboards-server

The hosting runtime for dashboards apps, the counterpart of Shiny Server.
It's an http-kit server that runs each browser tab as a websocket session
and serves client assets, downloads and each app's `www/` files. It also
has a `/_health` endpoint.

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

An app source can be an app value, a var (re-read for each page load), a
qualified symbol, or a directory containing `app.clj`. Directories reload
when their files change, and a `deps.edn` beside `app.clj` can add
libraries at load time (the server must run under the Clojure CLI for
this).

```clojure
io.github.kirahowe/dashboards-server
{:git/url "https://github.com/kirahowe/clj-dashboards" :git/sha "…" :deps/root "server"}
```
