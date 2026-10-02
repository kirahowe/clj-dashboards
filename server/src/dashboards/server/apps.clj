(ns dashboards.server.apps
  "Where the server gets its apps from.

  An app source is anything the server can turn into an app on demand:

  - an app value (`dashboards.app/app`),
  - a var holding one -- re-read for each new page, so re-evaluating
    the var at the REPL takes effect on reload,
  - a symbol naming such a var, `my.dashboard/app`, required on first
    use,
  - a directory containing `app.clj`, like a Shiny app directory. The
    file is loaded with `load-file`, and its last form must evaluate to
    the app (or to a var holding it). When any `.clj` file in the
    directory changes, the app is reloaded for new sessions. An
    optional `deps.edn` beside it may list extra `:deps`, which are
    added to the running server when the app loads."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [dashboards.app :as app])
  (:import (java.io File)))

(defprotocol AppSource
  (resolve-app [src] "The current app value.")
  (source-dir [src] "The app's own directory, if it has one, else nil."))

(defn- ensure-app [x where]
  (let [x (if (var? x) @x x)]
    (when-not (app/app? x)
      (throw (ex-info (str where " did not produce an app. Make sure it evaluates to "
                           "(dashboards.app/app {...}).")
                      {:got (type x)})))
    x))

(defrecord ValueSource [app]
  AppSource
  (resolve-app [_] app)
  (source-dir [_] nil))

(defrecord VarSource [v]
  AppSource
  (resolve-app [_] (ensure-app @v (str v)))
  (source-dir [_] nil))

(defrecord SymbolSource [sym]
  AppSource
  (resolve-app [_]
    (let [v (or (requiring-resolve sym)
                (throw (ex-info (str "Could not resolve " sym) {:symbol sym})))]
      (ensure-app @v (str sym))))
  (source-dir [_] nil))

;; ---------------------------------------------------------------------------
;; App directories

(defn app-dir? [^File dir]
  (and (.isDirectory dir) (.isFile (io/file dir "app.clj"))))

(defn- clj-files [^File dir]
  (->> (file-seq dir)
       (filter #(and (.isFile ^File %)
                     (re-find #"\.(clj|cljc|edn)$" (.getName ^File %))
                     (not (str/includes? (.getPath ^File %) (str File/separator "www" File/separator)))))))

(defn- fingerprint [dir]
  (into {} (map (fn [^File f] [(.getPath f) (.lastModified f)])) (clj-files dir)))

(defn- add-app-deps!
  "Add the `:deps` of an app directory's `deps.edn` to the running
  process. Needs the Clojure CLI, which `clojure.repl.deps` calls."
  [^File dir]
  (let [f (io/file dir "deps.edn")]
    (when (.isFile f)
      (when-let [deps (not-empty (:deps (edn/read-string (slurp f))))]
        (try
          (let [add-libs (requiring-resolve 'clojure.repl.deps/add-libs)]
            (binding [*repl* true]
              (add-libs deps)))
          (catch Throwable t
            (throw (ex-info (str "Could not add the dependencies in " f ": " (ex-message t)
                                 " (extra deps need the server to run under the Clojure CLI)")
                            {:deps deps} t))))))))

(defn load-app-dir
  "Load `dir/app.clj` and return the app it evaluates to."
  [^File dir]
  (add-app-deps! dir)
  (let [file (io/file dir "app.clj")
        result (binding [app/*app-dir* (.getAbsoluteFile dir)
                         *ns* *ns*]
                 (load-file (.getPath file)))]
    (ensure-app result (str file))))

(defrecord DirSource [^File dir state reload?]
  AppSource
  (resolve-app [_]
    (locking state
      (let [{:keys [app fp]} @state]
        (if (and app (or (not reload?) (= fp (fingerprint dir))))
          app
          (let [fp (fingerprint dir)
                app (load-app-dir dir)]
            (when (:app @state)
              (println "dashboards: reloaded" (str dir)))
            (reset! state {:app app :fp fp})
            app)))))
  (source-dir [_] dir))

(defn dir-source [dir & {:keys [reload?] :or {reload? true}}]
  (->DirSource (.getAbsoluteFile (io/file dir)) (atom {}) reload?))

(defn ->source
  "Turn an app, var, symbol or directory into an `AppSource`."
  [x & {:keys [reload?] :or {reload? true}}]
  (cond
    (satisfies? AppSource x) x
    (app/app? x) (->ValueSource x)
    (var? x) (->VarSource x)
    (qualified-symbol? x) (->SymbolSource x)
    (or (string? x) (instance? File x))
    (let [f (io/file x)]
      (if (app-dir? f)
        (dir-source f :reload? reload?)
        (throw (ex-info (str "No app.clj in " f) {:dir (str f)}))))
    :else (throw (ex-info (str "Don't know how to serve " (pr-str x)
                               ". Expected an app, a var, a qualified symbol or a directory.")
                          {:got (type x)}))))

(defn scan-apps-dir
  "The app directories directly inside `apps-dir`, as a map from mount
  path to directory: each subdirectory with an `app.clj` is served at
  `/<name>`, and `apps-dir` itself at `/` if it has one."
  [apps-dir]
  (let [root (io/file apps-dir)]
    (merge
     (into {}
           (for [^File d (sort (.listFiles root))
                 :when (and (app-dir? d) (not (str/starts-with? (.getName d) ".")))]
             [(str "/" (.getName d)) d]))
     (when (app-dir? root) {"/" root}))))
