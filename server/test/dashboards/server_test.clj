(ns dashboards.server-test
  (:require [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [dashboards.app :as app]
            [dashboards.render :as render]
            [dashboards.server :as server]
            [dashboards.server.apps :as apps]
            [dashboards.server.main :as main]
            [dashboards.ui :as ui])
  (:import (java.net URI URLEncoder)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.util.stream Stream)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(def counter-app
  (app/app {:title "Counter"
            :ui (ui/page (ui/numeric-input :n "N" {:value 1}) (ui/text-output :sq))
            :server (fn [{:keys [input]}]
                      {:sq (render/text (let [n (input :n 0)] (* n n)))
                       :csv (render/download {:filename "n.csv"} [{:n (input :n)}])})}))

(def slow-app
  "An app whose output takes a while to render when :n is 1, keeping
  its session busy."
  (app/app {:ui (ui/page (ui/numeric-input :n "N" {:value 0}) (ui/text-output :out))
            :server (fn [{:keys [input]}]
                      {:out (render/text (let [n (input :n 0)]
                                           (when (= 1 n) (Thread/sleep 4000))
                                           n))})}))

(def broken-app
  "An app source that fails to load."
  (reify apps/AppSource
    (resolve-app [_] (throw (ex-info "the app is broken" {})))
    (source-dir [_] nil)))

(def ^:dynamic *server* nil)

(defn- start-server [config]
  (server/start! (merge {:port 0 :host "127.0.0.1"
                         :apps [{:path "/" :app counter-app}
                                {:path "/other" :app counter-app}
                                {:path "/slow" :app slow-app}
                                {:path "/broken" :app broken-app}]}
                        config)))

(use-fixtures :each
  (fn [t]
    (let [s (start-server {})]
      (try (binding [*server* s] (t))
           (finally (server/stop! s))))))

(defn- url [path] (str "http://127.0.0.1:" (:port *server*) path))

(def ^:private client (HttpClient/newHttpClient))

(defn- http-get [path]
  (let [resp (.send ^HttpClient client (.build (HttpRequest/newBuilder (URI. (url path))))
                    (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode resp)
     :body (.body resp)
     :headers (into {} (map (fn [[k v]] [k (first v)])) (.map (.headers resp)))}))

(defn- post-signals [signals & [mount]]
  (let [req (-> (HttpRequest/newBuilder (URI. (url (str mount "/_dashboards/signals"))))
                (.header "Content-Type" "application/json")
                (.POST (HttpRequest$BodyPublishers/ofString (json/write-json-str signals)))
                (.build))]
    (.statusCode (.send ^HttpClient client req (HttpResponse$BodyHandlers/discarding)))))

(defn- open-stream
  "Open a session's event stream with `signals`, as Datastar would.
  Returns {:events queue :close fn}; each event is a map with :event
  and :data (a map of Datastar data-line keys to values). `mount` is
  the app's path, for an app not at /."
  [signals & [mount]]
  (let [q (LinkedBlockingQueue.)
        req (.build (HttpRequest/newBuilder
                     (URI. (str (url (str mount "/_dashboards/stream")) "?datastar="
                                (URLEncoder/encode (json/write-json-str signals) "UTF-8")))))
        ^Stream lines (.body (.send ^HttpClient client req (HttpResponse$BodyHandlers/ofLines)))
        reader (future
                 (try
                   (loop [it (.iterator lines) event nil data {}]
                     (when (.hasNext it)
                       (let [^String line (.next it)]
                         (cond
                           (str/blank? line)
                           (do (when event (.put q {:event event :data data}))
                               (recur it nil {}))
                           (str/starts-with? line "event: ") (recur it (subs line 7) data)
                           (str/starts-with? line "data: ")
                           (let [[k v] (str/split (subs line 6) #" " 2)]
                             (recur it event (update data k #(if % (str % "\n" v) (str v)))))
                           :else (recur it event data)))))
                   (catch Exception _ nil)))]
    {:events q :reader reader :close #(do (.close lines) (future-cancel reader))}))

(defn- await-event
  ([c pred] (await-event c pred 10000))
  ([{:keys [^LinkedBlockingQueue events]} pred ms]
   (let [deadline (+ (System/currentTimeMillis) ms)]
     (loop []
       (let [e (.poll events (max 0 (- deadline (System/currentTimeMillis))) TimeUnit/MILLISECONDS)]
         (cond (nil? e) (throw (ex-info "timed out waiting for an event" {}))
               (pred e) e
               :else (recur)))))))

(defn- dashboards-event [type]
  (fn [e] (and (= "datastar-dashboards" (:event e)) (= type (get-in e [:data "type"])))))

(defn- session-of [e]
  (get-in (json/read-json (get-in e [:data "signals"])) ["dsh" "session"]))

(defn- connected? [e]
  (and (= "datastar-patch-signals" (:event e)) (session-of e)))

(defn- output-for [id]
  (fn [e] (and (= "datastar-patch-elements" (:event e))
               (= (str "#" id) (get-in e [:data "selector"])))))

(defn- sessions [] (get (json/read-json (:body (http-get "/_health"))) "sessions"))

(deftest pages-and-assets
  (let [{:keys [status body]} (http-get "/")]
    (is (= 200 status))
    (is (str/includes? body "<title>Counter</title>")))
  (is (= 302 (:status (http-get "/other"))))
  (is (= 200 (:status (http-get "/other/"))))
  (is (str/includes? (get (:headers (http-get "/_dashboards/dashboards.js")) "content-type") "javascript")
      "assets are served")
  (is (= 200 (:status (http-get "/_dashboards/datastar-1.0.4.js"))))
  (is (= 404 (:status (http-get "/_dashboards/nope.js"))))
  (is (= 0 (sessions))))

(deftest a-session-over-sse
  (let [c (open-stream {"n" "3" "dsh" {"session" "" "kinds" {"n" "number"}}})
        sid (session-of (await-event c connected?))]
    (is (string? sid))
    (is (str/includes? (get-in (await-event c (output-for "sq")) [:data "elements"]) ">9<"))

    (testing "signals posted back update the session"
      (is (= 204 (post-signals {"n" "12" "dsh" {"session" sid "kinds" {"n" "number"}}})))
      (is (str/includes? (get-in (await-event c (output-for "sq")) [:data "elements"]) ">144<"))
      (is (= 1 (sessions))))

    (testing "downloads go through the session"
      (let [{:keys [status body headers]} (http-get (str "/_dashboards/download/" sid "/csv"))]
        (is (= 200 status))
        (is (= "n\n12\n" body))
        (is (str/includes? (get headers "content-disposition") "n.csv"))))
    (is (= 404 (:status (http-get "/_dashboards/download/nope/csv"))))

    (testing "a reconnecting browser resumes its session"
      ((:close c))
      (Thread/sleep 200)
      (let [c2 (open-stream {"n" "12" "dsh" {"session" sid "kinds" {"n" "number"}}})]
        (is (= sid (session-of (await-event c2 connected?))))
        (is (str/includes? (get-in (await-event c2 (output-for "sq")) [:data "elements"]) ">144<")
            "outputs are resent")
        (is (= 1 (sessions)))
        ((:close c2))))))

(defn- post [path body]
  (let [req (-> (HttpRequest/newBuilder (URI. (url path)))
                (.POST (HttpRequest$BodyPublishers/ofString (json/write-json-str body)))
                (.build))]
    (.statusCode (.send ^HttpClient client req (HttpResponse$BodyHandlers/discarding)))))

(deftest sessions-live-while-the-page-says-so
  (let [s (start-server {:session-timeout-ms 400 :keep-alive-ms 50})]
    (try
      (binding [*server* s]
        (testing "a heartbeat keeps a session alive; silence ends it"
          (let [c (open-stream {"dsh" {"session" ""}})
                sid (session-of (await-event c connected?))]
            (dotimes [_ 4]
              (Thread/sleep 200)
              (is (= 204 (post "/_dashboards/alive" {"session" sid}))))
            (is (= 1 (sessions)))
            (Thread/sleep 800)
            (is (= 0 (sessions)))
            ((:close c))))
        (testing "the close beacon ends a session at once"
          (let [c (open-stream {"dsh" {"session" ""}})
                sid (session-of (await-event c connected?))]
            (is (= 1 (sessions)))
            (is (= 204 (post "/_dashboards/close" {"session" sid})))
            (is (= 0 (sessions)))
            ((:close c)))))
      (finally (server/stop! s)))))

(deftest app-directories
  (let [root (.toFile (Files/createTempDirectory "dashboards" (make-array FileAttribute 0)))
        dir (io/file root "demo")
        www (io/file dir "www")]
    (.mkdirs www)
    (spit (io/file www "hello.txt") "hi there")
    (spit (io/file dir "data.txt") "42")
    (spit (io/file dir "app.clj")
          (str "(ns demo.app (:require [dashboards.app :as app] [dashboards.ui :as ui]))\n"
               "(def answer (slurp (app/local-file \"data.txt\")))\n"
               "(app/app {:title (str \"Demo \" answer) :ui (ui/page [:p \"v1\"])})\n"))
    (testing "loading"
      (let [src (apps/->source (str dir))]
        (is (= "Demo 42" (:title (apps/resolve-app src))))
        (testing "reloads when the file changes"
          (Thread/sleep 1100) ; file mtimes can be second-granular
          (spit (io/file dir "app.clj")
                (str/replace (slurp (io/file dir "app.clj")) "v1" "v2"))
          (is (str/includes? (app/page-html (apps/resolve-app src) {}) "v2")))))
    (testing "serving a directory of apps"
      (let [s (server/start! {:port 0 :host "127.0.0.1" :apps-dir (str root) :title "My apps"})]
        (try
          (binding [*server* s]
            (is (str/includes? (:body (http-get "/")) "Demo 42") "the index lists apps")
            (is (str/includes? (:body (http-get "/demo/")) "v2"))
            (is (= "hi there" (:body (http-get "/demo/hello.txt"))) "www/ files are served")
            (is (= 404 (:status (http-get "/demo/..%2Fapp.clj"))) "no escaping www/"))
          (finally (server/stop! s)))))))

;; ---------------------------------------------------------------------------
;; Streams, and stopping the server

(defn- ended-within?
  "Whether the stream `c` reaches end-of-stream within `ms`."
  [c ms]
  (not= ::open (deref (:reader c) ms ::open)))

(defn- status-and-body [path]
  (let [{:keys [status body]} (http-get path)] [status body]))

(deftest the-connection-is-acknowledged-at-once
  (let [c (open-stream {"n" "0" "dsh" {"session" "" "kinds" {"n" "number"}}} "/slow")
        sid (session-of (await-event c connected?))]
    (await-event c (output-for "out"))
    ;; Keep the session's thread busy rendering for a few seconds...
    (is (= 204 (post-signals {"n" "1" "dsh" {"session" sid "kinds" {"n" "number"}}} "/slow")))
    (await-event c (dashboards-event "recalculating"))
    (Thread/sleep 200)
    ;; ...while the browser reconnects.
    ((:close c))
    (let [c2 (open-stream {"n" "1" "dsh" {"session" sid "kinds" {"n" "number"}}} "/slow")
          t0 (System/nanoTime)]
      (try
        (is (= sid (session-of (await-event c2 connected? 1000)))
            "the reconnected stream says so before the session is free")
        (is (= "connected" (get-in (await-event c2 #(= "datastar-dashboards" (:event %)) 1000) [:data "type"]))
            "the page hears `connected` straight away")
        (is (< (/ (- (System/nanoTime) t0) 1e6) 1000))
        (is (str/includes? (get-in (await-event c2 (output-for "out")) [:data "elements"]) ">1<")
            "outputs follow once the session is free")
        (finally ((:close c2)))))))

(deftest an-app-that-fails-to-start
  (let [s (start-server {})
        c (binding [*server* s] (open-stream {"dsh" {"session" ""}} "/broken"))]
    (try
      (await-event c (dashboards-event "failed") 2000)
      (is (str/includes? (get-in (await-event c (dashboards-event "notify") 2000) [:data "message"])
                         "the app is broken"))
      (testing "its stream is closed with the others when the server stops"
        (let [t0 (System/nanoTime)
              stopping (future (server/stop! s))]
          (is (ended-within? c 2000))
          (let [ms (/ (- (System/nanoTime) t0) 1e6)]
            ;; Rather than when http-kit gives up waiting for it (1s).
            (is (< ms 800) (str "the stream ended " ms "ms after stop!")))
          (is (nil? (deref stopping 5000 ::timeout)))))
      (finally ((:close c)) (server/stop! s)))))

(deftest streams-close-with-their-session
  (testing "the close beacon closes the session's stream"
    (let [c (open-stream {"dsh" {"session" ""}})
          sid (session-of (await-event c connected?))]
      (is (= 204 (post "/_dashboards/close" {"session" sid})))
      (is (ended-within? c 1000))
      ((:close c))))
  (testing "a stream that takes over a session closes the one before"
    (let [c (open-stream {"dsh" {"session" ""}})
          sid (session-of (await-event c connected?))
          c2 (open-stream {"dsh" {"session" sid}})]
      (is (= sid (session-of (await-event c2 connected?))))
      (is (ended-within? c 1000) "the old stream is closed")
      (is (not (ended-within? c2 300)) "the new one carries on")
      (is (= 1 (sessions)))
      ((:close c)) ((:close c2))))
  (testing "a stream that opens just as the server starts closing streams is closed too"
    (let [s (start-server {})
          attach! @#'server/attach!]
      (try
        (with-redefs-fn {#'server/attach! (fn [state session gen]
                                            ;; stop! gets past the stream's
                                            ;; own check before it attaches.
                                            (reset! (:phase state) :closing)
                                            (attach! state session gen))}
          (fn []
            (let [c (binding [*server* s] (open-stream {"dsh" {"session" ""}}))]
              (is (ended-within? c 1000))
              (is (= 0 (count @(:sessions s))) "and its session ended")
              ((:close c)))))
        (finally
          (reset! (:phase s) :running)
          (server/stop! s))))))

(deftest stopping-closes-streams-promptly
  (let [s (start-server {})
        c (binding [*server* s] (open-stream {"dsh" {"session" ""}}))]
    (try
      (session-of (await-event c connected?))
      (let [t0 (System/nanoTime)
            stopping (future (server/stop! s))]
        (is (ended-within? c 2000) "the browser sees its stream end, and reconnects")
        (let [ms (/ (- (System/nanoTime) t0) 1e6)]
          ;; Well before http-kit's own stop timeout (1s).
          (is (< ms 1000) (str "the stream ended " ms "ms after stop!")))
        (is (nil? (deref stopping 5000 ::timeout))))
      (testing "stop! is safe to call again"
        (is (nil? (server/stop! s)))
        (is (nil? (server/stop! s))))
      (finally ((:close c)) (server/stop! s)))))

(defn- listening? [s]
  (try (binding [*server* s] (http-get "/_health")) true
       (catch java.io.IOException _ false)))

(deftest stopping-survives-an-interrupt
  (let [s (start-server {:shutdown-delay-ms 5000})
        c (binding [*server* s] (open-stream {"dsh" {"session" ""}}))
        interrupted-after (promise)
        t (Thread. ^Runnable (fn []
                               (server/stop! s)
                               (deliver interrupted-after (.isInterrupted (Thread/currentThread)))))]
    (try
      (session-of (await-event c connected?))
      (.start t)
      (Thread/sleep 300)
      (is (= 503 (:status (binding [*server* s] (http-get "/_health")))) "draining")
      (.interrupt t)
      (is (ended-within? c 2000) "an interrupted drain still closes the streams...")
      (is (true? (deref interrupted-after 5000 ::timeout)) "...returns, with the interrupt restored...")
      (is (= 0 (count @(:sessions s))) "...ends the sessions...")
      (is (not (listening? s)) "...and stops the HTTP server")
      (is (nil? (deref (future (server/stop! s)) 2000 ::timeout)) "a later stop! returns at once")
      (finally ((:close c)) (.interrupt t) (server/stop! s)))))

(deftest stopping-survives-a-failing-step
  (let [s (start-server {})
        c (binding [*server* s] (open-stream {"dsh" {"session" ""}}))]
    (try
      (session-of (await-event c connected?))
      (with-redefs-fn {#'server/end-session! (fn [& _] (throw (ex-info "cannot end session" {})))}
        (fn []
          (is (nil? (deref (future (server/stop! s)) 5000 ::timeout)) "stop! carries on and returns")))
      (is (ended-within? c 2000) "streams are closed")
      (is (not (listening? s)) "the HTTP server is stopped")
      (is (nil? (deref (future (server/stop! s)) 2000 ::timeout)) "a later stop! returns at once")
      (finally ((:close c)) (server/stop! s)))))

(deftest draining-before-stopping
  (let [s (start-server {:shutdown-delay-ms 5000})]
    (binding [*server* s]
      (let [c (open-stream {"n" "3" "dsh" {"session" "" "kinds" {"n" "number"}}})
            sid (session-of (await-event c connected?))]
        (await-event c (output-for "sq"))
        (is (= [200 "ok"] (let [[st b] (status-and-body "/_health")] [st (get (json/read-json b) "status")])))
        (let [stopping (future (server/stop! s))
              second-stop (do (Thread/sleep 200) (future (server/stop! s)))]
          (try
            (testing "while draining"
              (let [[status body] (status-and-body "/_health")]
                (is (= 503 status) "the health check fails, so load balancers stop routing here")
                (is (= "draining" (get (json/read-json body) "status"))))
              (is (= 503 (:status (http-get (str "/_dashboards/stream?datastar="
                                                  (URLEncoder/encode (json/write-json-str {"dsh" {"session" ""}}) "UTF-8")))))
                  "new streams are refused")
              (is (not (ended-within? c 300)) "open streams carry on for now")
              (is (= 204 (post-signals {"n" "5" "dsh" {"session" sid "kinds" {"n" "number"}}})))
              (is (str/includes? (get-in (await-event c (output-for "sq")) [:data "elements"]) ">25<")
                  "existing sessions keep working"))
            (testing "after the delay the stream closes and the server stops"
              (is (ended-within? c 8000))
              (is (nil? (deref stopping 5000 ::timeout)))
              (is (nil? (deref second-stop 5000 ::timeout)) "a concurrent stop! waits and returns"))
            (finally ((:close c)) (server/stop! s))))))))

(deftest command-line
  (is (= {:apps [{:path "/" :app 'my.ns/app} {:path "/b" :app "./apps/b"}]
          :port 9000 :reload? false}
         (main/config-from ["--app" "my.ns/app" "--app" "/b=./apps/b" "--port" "9000" "--no-reload"])))
  (is (= 10000 (:shutdown-delay-ms (main/config-from ["--app" "my.ns/app" "--shutdown-delay-ms" "10000"]))))
  (testing "numbers that aren't are refused, not ignored"
    (doseq [args [["--shutdown-delay-ms" "5s"] ["--shutdown-delay-ms" "-1"] ["--port" "http"]
                  ["--max-sessions" ""] ["--port"]]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"^--\S+ (expects a whole number|needs a value)"
                            (main/config-from (into ["--app" "my.ns/app"] args)))
          (pr-str args)))))
