(ns tasks
  "Helpers for the tasks in bb.edn."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as p]
            [clojure.string :as str]))

(def assets "core/resources/dashboards/assets")

(defn datastar-update
  "Vendor Datastar `version` into core: the bundle, its license, and
  every reference to the version."
  [version]
  (when-not version
    (throw (ex-info "Usage: bb datastar:update <version>, e.g. 1.0.5" {})))
  (let [cdn (str "https://cdn.jsdelivr.net/gh/starfederation/datastar@" version)
        fetch (fn [path] (:body (http/get (str cdn path))))
        js (str/replace (fetch "/bundles/datastar.js") #"//# sourceMappingURL=\S+" "")]
    (run! fs/delete (fs/glob assets "datastar-*.js"))
    (spit (str assets "/datastar-" version ".js") js)
    (spit (str assets "/DATASTAR-LICENSE.md") (fetch "/LICENSE.md"))
    (doseq [[file pattern replacement]
            [["core/src/dashboards/app.clj"
              #"\(def datastar-version \"[^\"]+\"\)"
              (str "(def datastar-version \"" version "\")")]
             [(str assets "/dashboards.js")
              #"\./datastar-[^']+\.js"
              (str "./datastar-" version ".js")]
             ["server/test/dashboards/server_test.clj"
              #"datastar-[0-9][^\"]*\.js"
              (str "datastar-" version ".js")]]]
      (spit file (str/replace (slurp file) pattern replacement)))
    (println "Vendored Datastar" version "- now run: bb test")))

(defn clean []
  (doseq [d [".cpcache" "core/.cpcache" "server/.cpcache" "template/.cpcache" "template/target"]]
    (fs/delete-tree d)))

(defn run-server
  "Run a server command (`process` arguments) in the foreground.
  Stopping it with Ctrl-C or SIGTERM is the normal way out, so that
  ends the task quietly; any other failure exits with the server's exit
  code.

  A signal that reaches only this process (SIGTERM from a supervisor,
  say) is passed on to the server, and the task waits for it to finish
  shutting down -- draining its sessions -- before exiting, so the
  server is never left running on its own."
  [& args]
  (let [server (apply p/process {:inherit true} args)
        hook (Thread. ^Runnable
                      (fn []
                        ;; Ctrl-C reaches the server directly, as it is
                        ;; in the terminal's process group; SIGTERM to
                        ;; this process alone doesn't.
                        (when (p/alive? server)
                          (p/destroy server))
                        @server))]
    (.addShutdownHook (Runtime/getRuntime) hook)
    (let [{:keys [exit]} @server]
      (try (.removeShutdownHook (Runtime/getRuntime) hook)
           (catch IllegalStateException _ nil)) ; already shutting down
      ;; A JVM ends with 128 + the signal's number: 130 for SIGINT,
      ;; 143 for SIGTERM.
      (when-not (contains? #{0 130 143} exit)
        (System/exit exit)))))
