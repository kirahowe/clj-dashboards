(ns tasks
  (:require [babashka.process :as p]
            [clojure.string :as str]))

(def repo "https://github.com/kirahowe/clj-dashboards")

(defn upgrade
  "Set every clj-dashboards :git/sha in deps.edn to `sha`, or to the
  repository's latest commit."
  [sha]
  (let [sha (or sha (first (str/split (:out (p/sh "git" "ls-remote" repo "HEAD")) #"\s")))
        deps (slurp "deps.edn")]
    (when (str/blank? sha) (throw (ex-info "Could not find the latest commit" {})))
    (spit "deps.edn" (str/replace deps #":git/sha \"[0-9a-f]{40}\"" (str ":git/sha \"" sha "\"")))
    (println "clj-dashboards pinned at" sha)))
