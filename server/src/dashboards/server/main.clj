(ns dashboards.server.main
  "Command-line entry point for the dashboards server.

      clojure -M -m dashboards.server.main [options]

  Options:

      --config FILE      EDN config file (see `dashboards.server/start!`)
      --apps-dir DIR     serve each DIR/<name>/app.clj at /<name>/
      --app [PATH=]APP   serve APP (a namespace-qualified var, like
                         my.dashboard/app, or an app directory) at PATH
                         (default /). Repeatable.
      --port N           port to listen on (default 8080, or $PORT)
      --host HOST        address to bind (default 0.0.0.0, or $HOST)
      --base-path PATH   URL prefix to strip (or $DASHBOARDS_BASE_PATH)
      --title TEXT       heading of the app index page
      --no-reload        don't reload app directories when they change
      --sanitize-errors  hide error details from users
      --max-sessions N   refuse sessions beyond N
      --shutdown-delay-ms N
                         on SIGTERM, fail /_health for N ms before
                         closing streams (default 0, or
                         $DASHBOARDS_SHUTDOWN_DELAY_MS); 5000-10000
                         suits a load balancer or Kubernetes

  Command-line options override the config file, which overrides the
  environment variables.

  On SIGTERM the server stops gracefully (see
  `dashboards.server/stop!`): /_health answers 503 while it drains,
  then every open stream is closed so browsers reconnect elsewhere."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [dashboards.server :as server])
  (:gen-class))

(defn- parse-app [s]
  (let [[path app] (if (str/includes? s "=") (str/split s #"=" 2) ["/" s])
        app (if (and (re-matches #"[\w.\-]+/[\w\-*?!<>=+]+" app)
                     (not (.exists (java.io.File. ^String app))))
              (symbol app)
              app)]
    {:path path :app app}))

(defn parse-args [args]
  (loop [[a & more :as args] args, opts {}]
    (if (empty? args)
      opts
      (let [[v & rest] more]
        (case a
          "--config" (recur rest (assoc opts :config-file v))
          "--apps-dir" (recur rest (assoc opts :apps-dir v))
          "--app" (recur rest (update opts :apps (fnil conj []) (parse-app v)))
          "--port" (recur rest (assoc opts :port (parse-long v)))
          "--host" (recur rest (assoc opts :host v))
          "--base-path" (recur rest (assoc opts :base-path v))
          "--title" (recur rest (assoc opts :title v))
          "--max-sessions" (recur rest (assoc opts :max-sessions (parse-long v)))
          "--shutdown-delay-ms" (recur rest (assoc opts :shutdown-delay-ms (parse-long v)))
          "--no-reload" (recur more (assoc opts :reload? false))
          "--sanitize-errors" (recur more (assoc opts :sanitize-errors? true))
          ("-h" "--help") (recur more (assoc opts :help? true))
          (throw (ex-info (str "Unknown option: " a) {:arg a})))))))

(defn- env-config []
  (let [env #(not-empty (System/getenv %))]
    (cond-> {}
      (env "PORT") (assoc :port (parse-long (env "PORT")))
      (env "HOST") (assoc :host (env "HOST"))
      (env "DASHBOARDS_BASE_PATH") (assoc :base-path (env "DASHBOARDS_BASE_PATH"))
      (env "DASHBOARDS_APPS_DIR") (assoc :apps-dir (env "DASHBOARDS_APPS_DIR"))
      (env "DASHBOARDS_SHUTDOWN_DELAY_MS") (assoc :shutdown-delay-ms
                                                  (parse-long (env "DASHBOARDS_SHUTDOWN_DELAY_MS")))
      (env "DASHBOARDS_CONFIG") (assoc :config-file (env "DASHBOARDS_CONFIG")))))

(defn- read-config-file [path]
  (let [cfg (edn/read-string (slurp path))]
    ;; Apps in a config file name vars as symbols: {:app my.ns/app}.
    cfg))

(defn config-from [args]
  (let [cli (parse-args args)
        env (env-config)
        file (or (:config-file cli) (:config-file env))
        from-file (when file (read-config-file file))
        merged (merge env from-file (dissoc cli :config-file))]
    (cond-> (dissoc merged :config-file :help?)
      (and (:apps from-file) (:apps cli)) (assoc :apps (into (vec (:apps from-file)) (:apps cli)))
      (:help? cli) (assoc :help? true))))

(defn -main [& args]
  (let [config (config-from args)]
    (when (:help? config)
      (println (:doc (meta (find-ns 'dashboards.server.main))))
      (System/exit 0))
    (when (and (empty? (:apps config)) (not (:apps-dir config)))
      (binding [*out* *err*]
        (println "No apps to serve. Pass --app my.ns/app, --apps-dir DIR, or --config FILE. See --help."))
      (System/exit 1))
    (let [srv (server/start! config)]
      (println (str "dashboards server listening on http://" (:host config "0.0.0.0") ":" (:port srv)
                    (or (:base-path config) "") "/"))
      (doseq [{:keys [path app]} (:apps config)]
        (println "  app" path "->" app))
      (when-let [d (:apps-dir config)]
        (println "  apps directory" d))
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. ^Runnable (fn []
                                             (println "dashboards server stopping")
                                             (server/stop! srv))))
      @(promise))))
