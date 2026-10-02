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
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers WebSocket WebSocket$Listener)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(def counter-app
  (app/app {:title "Counter"
            :ui (ui/page (ui/numeric-input :n "N" {:value 1}) (ui/text-output :sq))
            :server (fn [{:keys [input]}]
                      {:sq (render/text (let [n (input :n 0)] (* n n)))
                       :csv (render/download {:filename "n.csv"} [{:n (input :n)}])})}))

(def ^:dynamic *server* nil)

(use-fixtures :each
  (fn [t]
    (let [s (server/start! {:port 0 :host "127.0.0.1"
                            :apps [{:path "/" :app counter-app}
                                   {:path "/other" :app counter-app}]})]
      (try (binding [*server* s] (t))
           (finally (server/stop! s))))))

(defn- url [path] (str "http://127.0.0.1:" (:port *server*) path))

(defn- http-get [path]
  (let [client (HttpClient/newHttpClient)
        resp (.send client (.build (HttpRequest/newBuilder (URI. (url path))))
                    (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode resp)
     :body (.body resp)
     :headers (into {} (map (fn [[k v]] [k (first v)])) (.map (.headers resp)))}))

(defn- connect [path]
  (let [q (LinkedBlockingQueue.)
        buf (StringBuilder.)
        listener (reify WebSocket$Listener
                   (onText [_ ws data last?]
                     (.append buf data)
                     (when last?
                       (.put q (json/read-json (str buf) :key-fn keyword))
                       (.setLength buf 0))
                     (.request ws 1)
                     nil))
        ws (.join (.buildAsync (.newWebSocketBuilder (HttpClient/newHttpClient))
                               (URI. (str/replace (url path) "http:" "ws:"))
                               listener))]
    {:ws ws :q q}))

(defn- send-json [{:keys [^WebSocket ws]} m]
  (.join (.sendText ws (json/write-json-str m) true)))

(defn- await-msg [{:keys [^LinkedBlockingQueue q]} pred]
  (loop []
    (let [m (.poll q 10 TimeUnit/SECONDS)]
      (cond (nil? m) (throw (ex-info "timed out waiting for a message" {}))
            (pred m) m
            :else (recur)))))

(deftest pages-and-assets
  (let [{:keys [status body]} (http-get "/")]
    (is (= 200 status))
    (is (str/includes? body "<title>Counter</title>")))
  (is (= 302 (:status (http-get "/other"))))
  (is (= 200 (:status (http-get "/other/"))))
  (is (str/includes? (:headers (http-get "/_dashboards/dashboards.js")) "javascript")
      "assets are served")
  (is (= 404 (:status (http-get "/_dashboards/nope.js"))))
  (is (= "{\"status\":\"ok\",\"sessions\":0}" (:body (http-get "/_health")))))

(deftest a-session-over-websocket
  (let [c (connect "/_dashboards/ws")
        hello (await-msg c #(= "hello" (:type %)))]
    (is (string? (:session hello)))
    (send-json c {:type "init" :inputs {:n 3}})
    (is (str/includes? (:html (await-msg c #(= "output" (:type %)))) ">9<"))
    (send-json c {:type "input" :id "n" :value 12})
    (is (str/includes? (:html (await-msg c #(= "output" (:type %)))) ">144<"))
    (is (str/includes? (:body (http-get "/_health")) "\"sessions\":1"))

    (testing "downloads go through the session"
      (let [{:keys [status body headers]} (http-get (str "/_dashboards/download/" (:session hello) "/csv"))]
        (is (= 200 status))
        (is (= "n\n12\n" body))
        (is (str/includes? (get headers "content-disposition") "n.csv"))))
    (is (= 404 (:status (http-get "/_dashboards/download/nope/csv"))))

    (.join (.sendClose ^WebSocket (:ws c) WebSocket/NORMAL_CLOSURE "bye"))
    (Thread/sleep 200)
    (is (str/includes? (:body (http-get "/_health")) "\"sessions\":0"))))

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

(deftest command-line
  (is (= {:apps [{:path "/" :app 'my.ns/app} {:path "/b" :app "./apps/b"}]
          :port 9000 :reload? false}
         (main/config-from ["--app" "my.ns/app" "--app" "/b=./apps/b" "--port" "9000" "--no-reload"]))))
