(ns dashboards.app
  "An app is a UI and a server function, nothing more.

      (def app
        (app/app {:title  \"Penguins\"
                  :ui     my-ui           ; hiccup, or (fn [request] hiccup)
                  :server my-server}))    ; (fn [{:keys [input session]}] {output-id render-spec})

  An app value says nothing about where or how it is served. Run it
  from a REPL with `dashboards.server/run-app!`, or host it -- alone or
  alongside other apps -- with the `dashboards.server` runtime. See the
  project README for deployment."
  (:require [clojure.java.io :as io]
            [dashboards.html :as html]
            [dashboards.ui :as ui]))

(defn app
  "Define an app.

  - `:ui`     -- the page: hiccup, usually built with `dashboards.ui`,
                 or a function of the Ring request returning hiccup.
  - `:server` -- `(fn [{:keys [input session]}] ...)`, called once for
                 each browser session. Returns a map from output id to
                 render spec (see `dashboards.render`).
  - `:title`  -- the browser tab's title.
  - `:head`   -- extra hiccup for the document head (stylesheets,
                 fonts, meta tags).
  - `:www`    -- a directory whose files are served alongside the app,
                 for images and the like (`[:img {:src \"logo.png\"}]`)."
  [{:keys [ui server] :as spec}]
  (when-not (some? ui)
    (throw (ex-info "An app needs a :ui." {:keys (keys spec)})))
  (when (and server (not (ifn? server)))
    (throw (ex-info "An app's :server must be a function." {:server server})))
  (assoc spec ::app true))

(defn app?
  [x]
  (boolean (and (map? x) (::app x))))

;; ---------------------------------------------------------------------------
;; Client assets

(def datastar-version "1.0.4")

(def asset-names
  "The browser assets the page loads, served from the classpath.
  `dashboards.js` imports Datastar itself."
  #{"dashboards.js" "dashboards.css" (str "datastar-" datastar-version ".js")})

(defn asset
  "The URL of a classpath client asset by file name, or nil."
  [file-name]
  (when (contains? asset-names file-name)
    (io/resource (str "dashboards/assets/" file-name))))

(def ^:private asset-version
  (delay
    (Integer/toHexString
     (hash (mapv #(some-> (asset %) slurp) (sort asset-names))))))

(def assets-path
  "Where the page expects the client assets and endpoints, relative to
  the app's own URL."
  "_dashboards")

;; ---------------------------------------------------------------------------
;; The page

(def ^:private stream-options
  ;; Keep the stream open in background tabs, as Shiny does, and keep
  ;; trying to reconnect: a session survives a short disconnection.
  "{openWhenHidden: true, retry: 'always', retryInterval: 500, retryMaxWait: 5000, retryMaxCount: 100000}")

(defn page-html
  "The full HTML document for `app`. All URLs in it are relative, so
  the app works wherever it is mounted, including behind a proxy that
  serves it under a path prefix -- as long as the page URL ends in a
  slash.

  The page connects to the server with Datastar: the last element of
  the body opens the session's event stream, and every change to a
  signal (an input) is posted back, debounced."
  ^String [app request]
  (let [{:keys [ui title head]} app
        body (ui/expand (if (fn? ui) (ui request) ui))
        v @asset-version]
    (str "<!DOCTYPE html>\n"
         (html/hiccup->html
          [:html {:lang "en"}
           [:head
            [:meta {:charset "utf-8"}]
            [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
            [:title (or title "Dashboard")]
            [:link {:rel "icon" :href "data:,"}]
            [:link {:rel "stylesheet" :href (str assets-path "/dashboards.css?v=" v)}]
            [:script {:type "module" :src (str assets-path "/dashboards.js?v=" v)}]
            head]
           [:body.dsh-body {:data-signals (html/js-literal {"dsh" {"session" "" "sizes" {} "kinds" {}}})}
            [:div.dsh-progress {:aria-hidden "true"}]
            body
            [:div#dsh-notifications.dsh-notifications {:aria-live "polite"}]
            ;; dashboards.js shows one message at a time, and only after
            ;; a wait long enough to matter.
            [:div.dsh-disconnected {:hidden true :role "alert"}
             [:div.dsh-disconnected-box
              [:div.dsh-connection-message {:data-dsh-connection "reconnecting"}
               [:strong "Reconnecting to the server\u2026"]]
              [:div.dsh-connection-message {:data-dsh-connection "buffering" :hidden true}
               [:strong "Updates from the server aren\u2019t arriving."]
               [:p "Something between your browser and the server \u2013 a proxy, VPN, "
                "antivirus software or a corporate firewall \u2013 may be buffering the "
                "event stream this page uses for live updates. This isn\u2019t a problem "
                "with the app. Try another network, or ask your network administrator "
                "to allow streaming (text/event-stream) responses."]]
              [:div.dsh-connection-message {:data-dsh-connection "failed" :hidden true}
               [:strong "Lost the connection to the server."]
               [:p "Reload the page to reconnect."]]
              [:button.dsh-btn.dsh-btn-secondary {:type "button" :onclick "location.reload()"}
               "Reload"]]]
            ;; Every signal change goes back to the server. Signals whose
            ;; names start with an underscore stay in the browser.
            [:div {:hidden true
                   :data-on-signal-patch__debounce.100ms
                   (str "@post('" assets-path "/signals', {retry: 'error', retryMaxCount: 3})")}]
            ;; Last, so every input has declared its signal before the
            ;; stream sends them all to start the session.
            [:div#dsh-stream {:hidden true
                              :data-init (str "@get('" assets-path "/stream', " stream-options ")")}]]]))))

;; ---------------------------------------------------------------------------
;; Files next to the app

(def ^:dynamic *app-dir*
  "While an app directory's `app.clj` loads, the directory it is in."
  nil)

(defn local-file
  "A file in the app's own directory, for data that ships with the app:

      (def penguins (tc/dataset (app/local-file \"penguins.csv\")))

  Resolved against the directory of the file being loaded, so call it
  at load time (in a `def`), not inside a server function."
  ^java.io.File [path]
  (let [dir (or *app-dir*
                (some-> *file* io/file .getAbsoluteFile .getParentFile))]
    (if dir (io/file dir path) (io/file path))))
