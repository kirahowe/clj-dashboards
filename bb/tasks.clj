(ns tasks
  "Helpers for the tasks in bb.edn."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
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
