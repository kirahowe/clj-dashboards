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
                         $DASHBOARDS_SHUTDOWN_DELAY_MS); behind a load
                         balancer, its health-check interval times its
                         failure threshold, under the kill grace period
      --app-load-timeout-ms N
                         the longest anything waits for an app to load
                         (default 60000, or $DASHBOARDS_APP_LOAD_TIMEOUT_MS)

  Command-line options override the config file, which overrides the
  environment variables.

  The server listens at once and loads its apps in the background, one
  at a time: /_health answers 503 (\"starting\") until each has loaded,
  failed or run out of time. A hung app.clj delays the apps loaded
  after it until the timeout interrupts it.

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

(defn- whole-number
  "`s` as a non-negative whole number; `what` (a flag or environment
  variable) names it in the error when it isn't one."
  [what s]
  (let [n (some-> s str/trim parse-long)]
    (when-not (and n (not (neg? n)))
      (throw (ex-info (str what " expects a whole number, not " (pr-str s)) {:option what :value s})))
    n))

(defn- positive-number
  "`s` as a positive whole number, as for `whole-number`."
  [what s]
  (let [n (some-> s str/trim parse-long)]
    (when-not (and n (pos? n))
      (throw (ex-info (str what " expects a positive whole number, not " (pr-str s)) {:option what :value s})))
    n))

(defn parse-args [args]
  (loop [[a & more :as args] args, opts {}]
    (if (empty? args)
      opts
      (let [[v & rest] more
            value (fn [] (if (some? v) v (throw (ex-info (str a " needs a value") {:option a}))))
            number (fn [] (whole-number a (value)))]
        (case a
          "--config" (recur rest (assoc opts :config-file (value)))
          "--apps-dir" (recur rest (assoc opts :apps-dir (value)))
          "--app" (recur rest (update opts :apps (fnil conj []) (parse-app (value))))
          "--port" (recur rest (assoc opts :port (number)))
          "--host" (recur rest (assoc opts :host (value)))
          "--base-path" (recur rest (assoc opts :base-path (value)))
          "--title" (recur rest (assoc opts :title (value)))
          "--max-sessions" (recur rest (assoc opts :max-sessions (number)))
          "--shutdown-delay-ms" (recur rest (assoc opts :shutdown-delay-ms (number)))
          "--app-load-timeout-ms" (recur rest (assoc opts :app-load-timeout-ms (positive-number a (value))))
          "--no-reload" (recur more (assoc opts :reload? false))
          "--sanitize-errors" (recur more (assoc opts :sanitize-errors? true))
          ("-h" "--help") (recur more (assoc opts :help? true))
          (throw (ex-info (str "Unknown option: " a) {:arg a})))))))

(def ^:private env-numbers
  "Config keys read from the environment as numbers, their variables,
  and how to parse them."
  {:port ["PORT" whole-number]
   :shutdown-delay-ms ["DASHBOARDS_SHUTDOWN_DELAY_MS" whole-number]
   :app-load-timeout-ms ["DASHBOARDS_APP_LOAD_TIMEOUT_MS" positive-number]})

(defn- env-config
  "Config from the environment (`getenv`, a function of a variable
  name), numbers still as strings: only those that are used are parsed
  (see `config-from`), so that a flag can stand in for a bad one."
  [getenv]
  (let [env #(not-empty (getenv %))]
    (cond-> {}
      (env "PORT") (assoc :port (env "PORT"))
      (env "HOST") (assoc :host (env "HOST"))
      (env "DASHBOARDS_BASE_PATH") (assoc :base-path (env "DASHBOARDS_BASE_PATH"))
      (env "DASHBOARDS_APPS_DIR") (assoc :apps-dir (env "DASHBOARDS_APPS_DIR"))
      (env "DASHBOARDS_SHUTDOWN_DELAY_MS") (assoc :shutdown-delay-ms (env "DASHBOARDS_SHUTDOWN_DELAY_MS"))
      (env "DASHBOARDS_APP_LOAD_TIMEOUT_MS") (assoc :app-load-timeout-ms (env "DASHBOARDS_APP_LOAD_TIMEOUT_MS"))
      (env "DASHBOARDS_CONFIG") (assoc :config-file (env "DASHBOARDS_CONFIG")))))

(defn- read-config-file [path]
  (let [cfg (edn/read-string (slurp path))]
    ;; Apps in a config file name vars as symbols: {:app my.ns/app}.
    cfg))

(defn config-from
  "The server config from command-line `args`, the config file and the
  environment (`getenv`, default `System/getenv`)."
  ([args] (config-from args #(System/getenv ^String %)))
  ([args getenv]
   (let [cli (parse-args args)
         env (env-config getenv)
         file (or (:config-file cli) (:config-file env))
         from-file (when file (read-config-file file))
         merged (merge env from-file (dissoc cli :config-file))
         ;; Numbers from the environment that nothing overrides.
         merged (reduce-kv (fn [m k [var parse]]
                             (if (and (contains? env k) (identical? (get env k) (get m k)))
                               (assoc m k (parse (str "$" var) (get env k)))
                               m))
                           merged env-numbers)
         timeout (:app-load-timeout-ms merged)]
     ;; From the config file, so possibly anything.
     (when-not (or (nil? timeout) (and (number? timeout) (pos? timeout)))
       (throw (ex-info (str ":app-load-timeout-ms in " file " should be a positive number of milliseconds, not "
                            (pr-str timeout))
                       {:app-load-timeout-ms timeout})))
     (cond-> (dissoc merged :config-file :help?)
       (and (:apps from-file) (:apps cli)) (assoc :apps (into (vec (:apps from-file)) (:apps cli)))
       (:help? cli) (assoc :help? true)))))

(defn -main [& args]
  (let [config (try (config-from args)
                    (catch clojure.lang.ExceptionInfo e
                      (binding [*out* *err*]
                        (println (str (ex-message e) ". See --help.")))
                      (System/exit 2)))]
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
