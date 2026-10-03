(ns dashboards.server
  "Hosting for dashboards apps: HTTP pages, the server-sent event
  stream that carries each session, client assets, downloads and
  static files.

  Each browser tab opens one long-lived SSE stream (`GET
  _dashboards/stream`), which starts its session, and posts its
  Datastar signals back whenever they change (`POST
  _dashboards/signals`). Both are plain HTTP, so any proxy that can
  stream a response can sit in front. If the stream drops, the browser
  reconnects and picks up the same session. While the page is open it
  sends a small heartbeat; a session the server has not heard from in
  `:session-timeout-ms` ends, as does one whose page sends its close
  beacon on the way out.

  Stopping the server (`stop!`, which the standalone server runs on
  SIGTERM) drains it first: `/_health` answers 503 and new streams are
  refused, then every open stream is closed so browsers reconnect at
  once -- to another instance behind a load balancer, or to this one
  once it is back. A reconnecting browser sends all of its inputs, so
  a server that doesn't know its session starts a fresh one from
  them; state kept only on the server is lost (see
  `dashboards.session`).

  Apps don't depend on this namespace; it is the infrastructure that
  runs them, like Shiny Server is for Shiny apps. Use it three ways:

  - At the REPL: `(run-app! #'app)` serves one app at
    http://localhost:8080/ and picks up re-evaluated definitions on
    page reload.
  - In your own program: `(start! {:port 8080 :apps [...]})`.
  - As a standalone server: `clojure -M -m dashboards.server.main`,
    configured by an EDN file and command-line flags, serving apps
    from your classpath or from a directory of `app.clj` folders.
    See `dashboards.server.main`."
  (:require [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [dashboards.app :as app]
            [dashboards.datastar :as datastar]
            [dashboards.html :as html]
            [dashboards.server.apps :as apps]
            [dashboards.session :as session]
            [org.httpkit.server :as http]
            [starfederation.datastar.clojure.adapter.http-kit :as sse]
            [starfederation.datastar.clojure.api :as d*])
  (:import (java.io File)
           (java.net URLDecoder URLEncoder)
           (java.util.concurrent Executors ScheduledExecutorService TimeUnit)
           (java.time LocalDateTime)
           (java.time.format DateTimeFormatter)))

(defn- log [& xs]
  (println (str (.format (LocalDateTime/now) DateTimeFormatter/ISO_LOCAL_DATE_TIME)
                " " (str/join " " xs))))

;; ---------------------------------------------------------------------------
;; Responses

(def ^:private content-types
  {"html" "text/html; charset=utf-8" "htm" "text/html; charset=utf-8"
   "css" "text/css; charset=utf-8" "js" "text/javascript; charset=utf-8"
   "json" "application/json" "svg" "image/svg+xml" "png" "image/png"
   "jpg" "image/jpeg" "jpeg" "image/jpeg" "gif" "image/gif" "webp" "image/webp"
   "ico" "image/x-icon" "txt" "text/plain; charset=utf-8" "csv" "text/csv; charset=utf-8"
   "woff" "font/woff" "woff2" "font/woff2" "pdf" "application/pdf"})

(defn- content-type [path]
  (get content-types (some-> (re-find #"\.([^./]+)$" (str path)) second str/lower-case)
       "application/octet-stream"))

(defn- text [status body]
  {:status status :headers {"Content-Type" "text/plain; charset=utf-8"} :body body})

(defn- html-response [body]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8" "Cache-Control" "no-cache"}
   :body body})

(defn- redirect [location]
  {:status 302 :headers {"Location" location} :body ""})

(defn- url-decode [s] (URLDecoder/decode (str s) "UTF-8"))

(defn- serve-file
  "Serve a file from `root`, refusing paths that escape it."
  [^File root rel-path]
  (when (and root (not (str/blank? rel-path)))
    (let [f (io/file root (url-decode rel-path))
          root-path (.getCanonicalPath root)
          path (.getCanonicalPath f)]
      (when (and (str/starts-with? path (str root-path File/separator)) (.isFile f))
        {:status 200
         :headers {"Content-Type" (content-type path) "Cache-Control" "max-age=300"}
         :body f}))))

;; ---------------------------------------------------------------------------
;; Sessions over SSE

(defn- session-count [server-state] (count @(:sessions server-state)))

;; A server's `:phase` moves from :running through :draining (failing
;; health checks, refusing new streams) and :closing (closing streams
;; and sessions) to :stopped.

(defn- running? [server-state] (= :running @(:phase server-state)))

(defn- closing? [server-state] (contains? #{:closing :stopped} @(:phase server-state)))

(defn- close-stream! [gen]
  (try (d*/close-sse! gen) (catch Throwable _ nil)))

;; Each session's stream is a Datastar SDK SSE generator (`gen`).

(defn- now [] (System/currentTimeMillis))

(defn- end-session!
  "Forget session `sid` and stop it."
  [server-state sid]
  (let [[old _] (swap-vals! (:sessions server-state) dissoc sid)]
    (when-let [{:keys [session gen]} (get old sid)]
      (swap! (:streams server-state) dissoc gen)
      (session/close! session))))

(defn- touch!
  "Note that the browser behind session `sid` is still there."
  [server-state sid]
  (swap! (:sessions server-state)
         (fn [m] (if (contains? m sid) (assoc-in m [sid :seen] (now)) m))))

(defn- attach!
  "Point session `s` at the stream `gen` (replacing any earlier one)."
  [server-state s gen]
  (let [sid (:id s)
        [old _] (swap-vals! (:sessions server-state) assoc sid {:session s :gen gen :seen (now)})]
    (when-let [old-gen (get-in old [sid :gen])]
      (swap! (:streams server-state) dissoc old-gen))
    (swap! (:streams server-state) assoc gen sid)
    (session/set-transport! s (fn [msg] (datastar/send! gen msg)))))

(defn- housekeeping!
  "Run periodically: comment on every stream so proxies keep idle ones
  open, and end sessions whose browser has not been heard from (by
  heartbeat, signals or a reconnecting stream) within the timeout."
  [server-state]
  (doseq [gen (keys @(:streams server-state))]
    (try (datastar/keep-alive! gen) (catch Exception _ nil)))
  (let [cutoff (- (now) (:session-timeout-ms (:config server-state)))]
    (doseq [[sid {:keys [seen]}] @(:sessions server-state)
            :when (< seen cutoff)]
      (end-session! server-state sid))))

(defn- stream
  "The session's event stream. Opening it starts a session -- or
  resumes the one named in the signals, after a reconnect."
  [server-state mount req]
  (let [{:keys [max-sessions sanitize-errors?]} (:config server-state)
        signals (try (datastar/read-signals req) (catch Exception _ {}))
        sid (get-in signals ["dsh" "session"])
        existing (some-> (get @(:sessions server-state) sid) :session)]
    (cond
      (not (running? server-state))
      {:status 503 :headers {"Content-Type" "text/plain" "Retry-After" "1"} :body "Shutting down"}

      (and (nil? existing) max-sessions (>= (session-count server-state) max-sessions))
      {:status 503 :headers {"Content-Type" "text/plain"} :body "At capacity"}

      :else
      (sse/->sse-response
       req
       {:headers {"Cache-Control" "no-cache, no-transform" "X-Accel-Buffering" "no"}
        sse/on-open
        (fn [gen]
          (if (closing? server-state)
            ;; Opened just as the server began closing streams.
            (close-stream! gen)
            (try
              (let [s (or (when (and existing (not (session/closed? existing))) existing)
                          (session/start! (apps/resolve-app (:source mount))
                                          {:request (dissoc req :async-channel :body)
                                           :sanitize-errors? sanitize-errors?}))]
                (attach! server-state s gen)
                (session/connected! s)
                (session/receive! s signals))
              (catch Throwable t
                (log "failed to start session for" (:path mount) "-" (ex-message t))
                (datastar/send! gen {:type "notification" :level "error" :duration nil
                                     :message (str "The app could not start: "
                                                   (if sanitize-errors? "see the server log." (ex-message t)))})))))
        ;; Not every server reports a client going away from a
        ;; streaming response, so the session isn't ended here: the
        ;; browser's heartbeat and close beacon decide (see
        ;; `housekeeping!`).
        sse/on-close
        (fn [gen & _]
          (when-let [sid (get @(:streams server-state) gen)]
            (swap! (:streams server-state) dissoc gen)
            (when (= gen (get-in @(:sessions server-state) [sid :gen]))
              (some-> (get-in @(:sessions server-state) [sid :session])
                      (session/set-transport! nil)))))}))))

(defn- read-body [req]
  (try (datastar/read-signals req) (catch Exception _ nil)))

(def ^:private no-content {:status 204 :body ""})

(defn- receive-signals
  "A POST of the page's signals, after one of them changed."
  [server-state req]
  (let [signals (read-body req)
        sid (get-in signals ["dsh" "session"])]
    (when-let [s (some-> (get @(:sessions server-state) sid) :session)]
      (touch! server-state sid)
      (session/receive! s signals))
    no-content))

(defn- download [server-state sid id]
  (if-let [s (some-> (get @(:sessions server-state) sid) :session)]
    (if-let [{:keys [filename content-type body]} (session/download! s id)]
      {:status 200
       :headers {"Content-Type" content-type
                 "Content-Disposition" (str "attachment; filename=\""
                                            (str/replace filename #"[\"\\\r\n]" "_")
                                            "\"; filename*=UTF-8''"
                                            (str/replace (URLEncoder/encode ^String filename "UTF-8") "+" "%20"))
                 "Cache-Control" "no-store"}
       :body (java.io.ByteArrayInputStream. ^bytes body)}
      (text 404 "No such download."))
    (text 404 "Session not found; reload the page.")))

;; ---------------------------------------------------------------------------
;; Routing

(defn- app-routes
  "Handle a request inside a mounted app. `sub` is the path below the
  mount point, without a leading slash."
  [server-state mount req sub]
  (cond
    (= sub "")
    (let [the-app (apps/resolve-app (:source mount))]
      (html-response (app/page-html the-app req)))

    (= sub "_dashboards/stream")
    (stream server-state mount req)

    (contains? #{"_dashboards/signals" "_dashboards/alive" "_dashboards/close"} sub)
    (if (not= :post (:request-method req))
      (text 405 "POST here.")
      (case sub
        "_dashboards/signals" (receive-signals server-state req)
        ;; The browser's heartbeat, while the page is open...
        "_dashboards/alive" (do (touch! server-state (get (read-body req) "session")) no-content)
        ;; ...and its goodbye, sent as the page goes away.
        "_dashboards/close" (do (end-session! server-state (get (read-body req) "session")) no-content)))

    (str/starts-with? sub "_dashboards/download/")
    (let [[sid id] (str/split (subs sub (count "_dashboards/download/")) #"/" 2)]
      (download server-state (url-decode sid) (url-decode id)))

    (str/starts-with? sub "_dashboards/")
    (if-let [res (app/asset (subs sub (count "_dashboards/")))]
      {:status 200
       :headers {"Content-Type" (content-type sub) "Cache-Control" "max-age=86400"}
       :body (slurp res)}
      (text 404 "Not found"))

    :else
    (let [the-app (apps/resolve-app (:source mount))
          www (or (some-> (:www the-app) io/file)
                  (some-> (apps/source-dir (:source mount)) (io/file "www")))]
      (or (serve-file www sub)
          (text 404 "Not found")))))

(defn- index-page [server-state mounts]
  (let [{:keys [title]} (:config server-state)
        title (or title "Dashboards")]
    (html-response
     (str "<!DOCTYPE html>\n"
          (html/hiccup->html
           [:html {:lang "en"}
            [:head
             [:meta {:charset "utf-8"}]
             [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
             [:title title]
             [:link {:rel "stylesheet" :href (str "_dashboards/dashboards.css")}]]
            [:body.dsh-body
             [:div.dsh-page
              [:header.dsh-header [:div.dsh-brand [:h1.dsh-title title]]]
              [:main.dsh-main
               (if (empty? mounts)
                 [:p.dsh-help "No apps are configured."]
                 [:div.dsh-columns.dsh-columns-auto
                  (for [{:keys [path source]} (sort-by :path mounts)
                        :let [a (try (apps/resolve-app source) (catch Throwable _ nil))]]
                    [:a.dsh-card.dsh-app-link {:href (str (subs path 1) "/")
                                               :style "text-decoration:none;color:inherit"}
                     [:div.dsh-card-body
                      [:strong {:style "font-size:15px"} (or (:title a) (subs path 1))]
                      [:span.dsh-help (cond (nil? a) "Failed to load; see the server log."
                                            (:description a) (:description a)
                                            :else path)]]])])]]]])))))

(defn- current-mounts
  "Mounts from the config's `:apps`, plus, when `:apps-dir` is set,
  one for each app directory found in it now -- so apps can be added
  without a restart."
  [server-state]
  (let [{:keys [apps-dir reload?]} (:config server-state)
        static (:static-mounts server-state)]
    (if apps-dir
      (let [found (apps/scan-apps-dir apps-dir)
            cache (:dir-sources server-state)]
        (into static
              (for [[path dir] found
                    :when (not (some #(= path (:path %)) static))]
                {:path path
                 :source (or (get @cache path)
                             (get (swap! cache assoc path (apps/dir-source dir :reload? reload?)) path))})))
      static)))

(defn- match-mount [mounts uri]
  (->> mounts
       (sort-by #(- (count (:path %))))
       (some (fn [{:keys [path] :as m}]
               (cond
                 (= path "/") [m (subs uri 1)]
                 (= uri path) [m nil] ; needs a trailing slash
                 (str/starts-with? uri (str path "/")) [m (subs uri (inc (count path)))])))))

(defn handler
  "The Ring handler for a server state (see `start!`)."
  [server-state]
  (fn [req]
    (try
      (let [base (:base-path (:config server-state) "")
            uri (:uri req)
            uri (if (and (seq base) (str/starts-with? uri base)) (subs uri (count base)) uri)
            uri (if (str/blank? uri) "/" uri)
            mounts (current-mounts server-state)]
        (cond
          (= uri "/_health")
          (let [ok? (running? server-state)]
            {:status (if ok? 200 503)
             :headers {"Content-Type" "application/json" "Cache-Control" "no-store"}
             :body (json/write-json-str {:status (if ok? "ok" "draining")
                                         :sessions (session-count server-state)})})

          :else
          (if-let [[mount sub] (match-mount mounts uri)]
            (if (nil? sub)
              (redirect (str base uri "/" (when-let [q (:query-string req)] (str "?" q))))
              (app-routes server-state mount req sub))
            (cond
              (= uri "/") (index-page server-state mounts)
              (str/starts-with? uri "/_dashboards/")
              (if-let [res (app/asset (subs uri (count "/_dashboards/")))]
                {:status 200 :headers {"Content-Type" (content-type uri)} :body (slurp res)}
                (text 404 "Not found"))
              :else (text 404 "Not found")))))
      (catch Throwable t
        (log "error handling" (:uri req) "-" (ex-message t))
        (text 500 (if (:sanitize-errors? (:config server-state))
                    "Internal server error"
                    (str "Error: " (ex-message t))))))))

;; ---------------------------------------------------------------------------
;; Starting and stopping

(defn- normalize-path [p]
  (let [p (str "/" (str/replace (str p) #"^/+|/+$" ""))]
    p))

(defn start!
  "Start a server. Returns a server map; stop it with `stop!`.

  Config:

  - `:port` (8080), `:host` (\"0.0.0.0\")
  - `:apps` -- a sequence of `{:path \"/sales\" :app <source>}`, where
    the source is an app, a var, a qualified symbol or an app
    directory (see `dashboards.server.apps`). A single app at `/` is
    fine.
  - `:apps-dir` -- a directory of app directories, each served at
    `/<name>/`, discovered as they appear.
  - `:reload?` -- reload app directories when their files change
    (default true).
  - `:title` -- the heading of the index page listing the apps.
  - `:base-path` -- a path prefix the server sees in front of every
    URL, when a proxy forwards `/dashboards/...` without stripping it.
  - `:max-sessions` -- refuse new sessions beyond this many.
  - `:session-timeout-ms` -- end a session after this long without
    word from its browser (default 120000). Pages send a heartbeat
    every 20s, but browsers slow timers in background tabs to once a
    minute, so keep it well above that.
  - `:keep-alive-ms` -- how often idle streams get a comment, so that
    proxies keep them open, and timed-out sessions are ended (default
    20000).
  - `:sanitize-errors?` -- don't show exception messages to users.
  - `:shutdown-delay-ms` -- how long `stop!` reports not-ready on
    `/_health` before it closes the open streams (default 0). See
    `stop!`.

  `GET /_health` answers 200 with `{\"status\": \"ok\", \"sessions\": n}`
  while the server is running, and 503 with `{\"status\": \"draining\"}`
  once it is stopping; point load balancer health checks or a
  Kubernetes readiness probe at it."
  [config]
  (let [config (merge {:port 8080 :host "0.0.0.0" :reload? true
                       :session-timeout-ms 120000 :keep-alive-ms 20000
                       :shutdown-delay-ms 0}
                      (into {} (remove (comp nil? val)) config))
        static-mounts (vec (for [{:keys [path app]} (:apps config)]
                             {:path (normalize-path (or path "/"))
                              :source (apps/->source app :reload? (:reload? config))}))
        state {:config (update config :base-path #(when (seq %) (normalize-path %)))
               :static-mounts static-mounts
               :dir-sources (atom {})
               :sessions (atom {})
               :streams (atom {})
               :phase (atom :running)
               :stopped (promise)
               :scheduler (Executors/newSingleThreadScheduledExecutor)}
        _ (.scheduleAtFixedRate ^ScheduledExecutorService (:scheduler state)
                                ^Runnable #(try (housekeeping! state)
                                               (catch Throwable t (log "housekeeping failed:" (ex-message t))))
                                (long (:keep-alive-ms config)) (long (:keep-alive-ms config))
                                TimeUnit/MILLISECONDS)
        stop-fn (http/run-server (handler state)
                                 {:port (:port config)
                                  :ip (:host config)
                                  :legacy-return-value? false
                                  :max-body (* 1024 1024)})]
    (assoc state
           :http stop-fn
           :port (http/server-port stop-fn))))

(defn stop!
  "Stop a server started with `start!`, gracefully:

  1. `/_health` starts answering 503 (`{\"status\": \"draining\"}`)
     and new streams are refused with 503, so load balancers stop
     sending browsers here. Everything else, including open streams
     and their sessions, carries on.
  2. After `:shutdown-delay-ms` (from `opts`, else the server's
     config; default 0), every open stream is closed. Browsers
     reconnect within a second -- to another instance, or to this one
     after a restart -- and, as that server doesn't know them, start a
     new session from the inputs they send.
  3. Sessions end (running their `on-ended` callbacks) and the HTTP
     server stops.

  With no delay this takes moments, as at the REPL and in tests. In a
  container, set the delay to how long your load balancer takes to
  notice a failing health check (its interval times its failure
  threshold, plus a little) -- 5000 to 10000 is typical behind
  Kubernetes readiness probes -- and keep it well under the grace
  period before the process is killed (30s in Kubernetes, 10s for
  `docker stop`).

  Safe to call more than once, and from several threads: later calls
  wait for the first to finish. Returns nil."
  ([server] (stop! server nil))
  ([server opts]
   (if (compare-and-set! (:phase server) :running :draining)
     (try
       (let [delay-ms (or (:shutdown-delay-ms opts) (:shutdown-delay-ms (:config server)) 0)]
         (when (pos? delay-ms)
           (log (str "draining: /_health reports 503; closing " (count @(:streams server))
                     " stream(s) in " delay-ms "ms"))
           (Thread/sleep (long delay-ms)))
         (reset! (:phase server) :closing)
         (.shutdownNow ^ScheduledExecutorService (:scheduler server))
         (doseq [gen (keys @(:streams server))]
           (close-stream! gen))
         (doseq [sid (keys @(:sessions server))]
           (end-session! server sid))
         (some-> (http/server-stop! (:http server) {:timeout 1000}) deref)
         ;; Any session a stream raced to start while we were closing.
         (doseq [sid (keys @(:sessions server))]
           (end-session! server sid)))
       (finally
         (reset! (:phase server) :stopped)
         (deliver (:stopped server) true)))
     @(:stopped server))
   nil))

(defonce ^:private dev-server (atom nil))

(defn run-app!
  "Serve one app, for development. `app` is an app, a var holding one
  (recommended: `#'app`, so re-evaluating it takes effect when you
  reload the page), a qualified symbol, or an app directory. Stops
  the server a previous `run-app!` started. Options as for `start!`;
  `:port` defaults to 8080 (0 picks a free port).

      (run-app! #'app)
      (run-app! #'app {:port 3000})"
  ([app] (run-app! app {}))
  ([app opts]
   (when-let [old @dev-server] (stop! old))
   (let [server (start! (merge {:host "127.0.0.1"} opts {:apps [{:path "/" :app app}]}))]
     (reset! dev-server server)
     (log (str "serving at http://" (if (= "0.0.0.0" (:host opts)) "localhost" (or (:host opts) "localhost"))
               ":" (:port server) "/"))
     server)))

(defn stop-app!
  "Stop the server started by `run-app!`."
  []
  (when-let [old @dev-server]
    (stop! old)
    (reset! dev-server nil)))
