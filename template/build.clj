(ns build
  "clojure -T:build uber  ->  target/my-dashboard.jar

  Run it with: java -jar target/my-dashboard.jar --app my-dashboard.app/app"
  (:require [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def jar-file "target/my-dashboard.jar")

(defn clean [_] (b/delete {:path "target"}))

(defn uber [{:keys [aliases]}]
  (clean nil)
  (let [basis (b/create-basis {:project "deps.edn" :aliases aliases})]
    (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
    ;; Only the launcher is compiled ahead of time; the app loads from
    ;; source at startup like any namespace.
    (b/compile-clj {:basis basis
                    :ns-compile '[dashboards.server.main]
                    :class-dir class-dir})
    (b/uber {:class-dir class-dir
             :uber-file jar-file
             :basis basis
             :main 'dashboards.server.main})
    (println "Built" jar-file)))
