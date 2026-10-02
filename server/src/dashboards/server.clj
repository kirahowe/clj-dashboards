(ns dashboards.server
  "Hosting for dashboards apps: HTTP pages, the websocket that carries
  each session, client assets, downloads and static files.

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
            [dashboards.html :as html]
            [dashboards.server.apps :as apps]
            [dashboards.session :as session]
            [org.httpkit.server :as http])
  (:import (java.io File)
           (java.net URLDecoder URLEncoder)
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
;; Sessions

(defn- session-count [server-state] (count @(:sessions server-state)))

(defn- websocket [server-state mount req]
  (if-not (:websocket? req)
    (text 400 "Expected a websocket request.")
    (let [{:keys [max-sessions sanitize-errors?]} (:config server-state)]
      (http/as-channel
       req
       {:on-open
        (fn [ch]
          (if (and max-sessions (>= (session-count server-state) max-sessions))
            (do (http/send! ch (json/write-json-str
                                {:type "notification" :level "error" :duration nil
                                 :message "This server is at capacity. Please try again later."}))
                (http/close ch))
            (try
              (let [the-app (apps/resolve-app (:source mount))
                    s (session/start! the-app
                                      {:send! (fn [msg] (http/send! ch (json/write-json-str msg)))
                                       :request (dissoc req :async-channel :body)
                                       :sanitize-errors? sanitize-errors?})]
                (swap! (:sessions server-state) assoc (:id s) s)
                (swap! (:channels server-state) assoc ch (:id s)))
              (catch Throwable t
                (log "failed to start session for" (:path mount) "-" (ex-message t))
                (http/send! ch (json/write-json-str
                                {:type "notification" :level "error" :duration nil
                                 :message (str "The app could not start: "
                                               (if sanitize-errors? "see the server log." (ex-message t)))}))
                (http/close ch)))))
        :on-receive
        (fn [ch data]
          (when-let [sid (get @(:channels server-state) ch)]
            (when-let [s (get @(:sessions server-state) sid)]
              (try (session/receive! s (json/read-json data :key-fn keyword))
                   (catch Exception e
                     (log "bad message from session" sid "-" (ex-message e)))))))
        :on-close
        (fn [ch _status]
          (when-let [sid (get @(:channels server-state) ch)]
            (swap! (:channels server-state) dissoc ch)
            (when-let [s (get @(:sessions server-state) sid)]
              (swap! (:sessions server-state) dissoc sid)
              (session/close! s))))}))))

(defn- download [server-state sid id]
  (if-let [s (get @(:sessions server-state) sid)]
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

    (= sub "_dashboards/ws")
    (websocket server-state mount req)

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
          {:status 200 :headers {"Content-Type" "application/json"}
           :body (json/write-json-str {:status "ok" :sessions (session-count server-state)})}

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
  - `:sanitize-errors?` -- don't show exception messages to users."
  [config]
  (let [config (merge {:port 8080 :host "0.0.0.0" :reload? true} config)
        static-mounts (vec (for [{:keys [path app]} (:apps config)]
                             {:path (normalize-path (or path "/"))
                              :source (apps/->source app :reload? (:reload? config))}))
        state {:config (update config :base-path #(when (seq %) (normalize-path %)))
               :static-mounts static-mounts
               :dir-sources (atom {})
               :sessions (atom {})
               :channels (atom {})}
        stop-fn (http/run-server (handler state)
                                 {:port (:port config)
                                  :ip (:host config)
                                  :legacy-return-value? false
                                  :max-body (* 1024 1024)})]
    (assoc state
           :http stop-fn
           :port (http/server-port stop-fn))))

(defn stop!
  "Stop a server started with `start!`, ending its sessions."
  [server]
  (doseq [s (vals @(:sessions server))]
    (session/close! s))
  (reset! (:sessions server) {})
  @(http/server-stop! (:http server) {:timeout 1000})
  nil)

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
