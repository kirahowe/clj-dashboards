(ns dashboards.server
  "Hosting for dashboards apps: HTTP pages, the server-sent event
  stream that carries each session, client assets, downloads and
  static files.

  Each browser tab opens one long-lived SSE stream (`GET
  _dashboards/stream`), which starts its session, and posts its
  Datastar signals back whenever they change (`POST
  _dashboards/signals`). Both are plain HTTP, so any proxy that can
  stream a response can sit in front. If the stream drops, the browser
  reconnects and picks up the same session. While the page is open it
  sends a small heartbeat; a session the server has not heard from in
  `:session-timeout-ms` ends, as does one whose page sends its close
  beacon on the way out.

  The server listens as soon as it starts, and loads its apps in the
  background, one at a time through its own queue (Clojure's `require`
  isn't thread-safe): `/_health` answers 503 (`starting`) until every
  app has loaded, failed, or taken longer than `:app-load-timeout-ms`.
  Nothing waits longer than that for an app. One whose `app.clj` hangs
  fails then, is interrupted, and stops holding up the apps queued
  behind it; if it ignores the interrupt, its thread is abandoned
  (restart the server to reclaim it). An app that failed to load
  answers 503 at once, without loading again, until its files change.

  Stopping the server (`stop!`, which the standalone server runs on
  SIGTERM) drains it first: `/_health` answers 503 and new streams are
  refused, then every open stream is closed so browsers reconnect at
  once -- to another instance behind a load balancer, or to this one
  once it is back. A reconnecting browser sends all of its inputs, so
  a server that doesn't know its session starts a fresh one from
  them; state kept only on the server is lost (see
  `dashboards.session`).

  Apps don't depend on this namespace; it is the infrastructure that
  runs them, like Shiny Server is for Shiny apps. Use it three ways:

  - At the REPL: `(run-app! #'app)` serves one app at
    http://localhost:8080/ and picks up re-evaluated definitions on
    page reload.
  - In your own program: `(start! {:port 8080 :apps [...]})`.
  - As a standalone server: `clojure -M -m dashboards.server.main`,
    configured by an EDN file and command-line flags, serving apps
    from your classpath or from a directory of `app.clj` folders.
    See `dashboards.server.main`."
  (:require [charred.api :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [dashboards.app :as app]
            [dashboards.datastar :as datastar]
            [dashboards.html :as html]
            [dashboards.server.apps :as apps]
            [dashboards.session :as session]
            [org.httpkit.server :as http]
            [starfederation.datastar.clojure.adapter.http-kit :as sse]
            [starfederation.datastar.clojure.api :as d*])
  (:import (java.io File)
           (java.net URLDecoder URLEncoder)
           (java.util UUID)
           (java.util.concurrent ExecutorService Executors Future RejectedExecutionException
                                 ScheduledExecutorService ScheduledThreadPoolExecutor Semaphore ThreadFactory
                                 TimeUnit)
           (java.util.concurrent.atomic AtomicBoolean)
           (java.time LocalDateTime)
           (java.time.format DateTimeFormatter)))

(defn- log [& xs]
  (println (str (.format (LocalDateTime/now) DateTimeFormatter/ISO_LOCAL_DATE_TIME)
                " " (str/join " " xs))))

;; ---------------------------------------------------------------------------
;; Responses

(def ^:private content-types
  {"html" "text/html; charset=utf-8" "htm" "text/html; charset=utf-8"
   "css" "text/css; charset=utf-8" "js" "text/javascript; charset=utf-8"
   "json" "application/json" "svg" "image/svg+xml" "png" "image/png"
   "jpg" "image/jpeg" "jpeg" "image/jpeg" "gif" "image/gif" "webp" "image/webp"
   "ico" "image/x-icon" "txt" "text/plain; charset=utf-8" "csv" "text/csv; charset=utf-8"
   "woff" "font/woff" "woff2" "font/woff2" "pdf" "application/pdf"})

(defn- content-type [path]
  (get content-types (some-> (re-find #"\.([^./]+)$" (str path)) second str/lower-case)
       "application/octet-stream"))

(defn- text [status body]
  {:status status :headers {"Content-Type" "text/plain; charset=utf-8"} :body body})

(defn- html-response [body]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8" "Cache-Control" "no-cache"}
   :body body})

(defn- redirect [location]
  {:status 302 :headers {"Location" location} :body ""})

(defn- url-decode [s] (URLDecoder/decode (str s) "UTF-8"))

(defn- serve-file
  "Serve a file from `root`, refusing paths that escape it."
  [^File root rel-path]
  (when (and root (not (str/blank? rel-path)))
    (let [f (io/file root (url-decode rel-path))
          root-path (.getCanonicalPath root)
          path (.getCanonicalPath f)]
      (when (and (str/starts-with? path (str root-path File/separator)) (.isFile f))
        {:status 200
         :headers {"Content-Type" (content-type path) "Cache-Control" "max-age=300"}
         :body f}))))

;; ---------------------------------------------------------------------------
;; Sessions over SSE

(defn- session-count [server-state] (count @(:sessions server-state)))

;; A session whose stream has been acknowledged but whose app is still
;; loading is "pending": `(:pending server-state)` maps its id to the
;; latest signals the page has posted for it, applied once it starts.

;; A server's `:phase` moves from :running through :draining (failing
;; health checks, refusing new streams) and :closing (closing streams
;; and sessions) to :stopped.

(defn- running? [server-state] (= :running @(:phase server-state)))

(defn- closing? [server-state] (contains? #{:closing :stopped} @(:phase server-state)))

(defn- close-stream! [gen]
  (try (d*/close-sse! gen) (catch Throwable _ nil)))

;; Each session's stream is a Datastar SDK SSE generator (`gen`).

(defn- now [] (System/currentTimeMillis))

(defn- end-session!
  "Stop session `sid`, forget it and close its stream."
  [server-state sid]
  (when-let [s (:session (get @(:sessions server-state) sid))]
    (try
      ;; Closing marks the session closed before it runs the on-ended
      ;; callbacks, and before it is forgotten here, so a reconnecting
      ;; stream can't take it over meanwhile (see `attach!`).
      (session/close! s)
      (finally
        (let [[old _] (swap-vals! (:sessions server-state)
                                  #(if (identical? s (get-in % [sid :session])) (dissoc % sid) %))]
          (when-let [{:keys [session gen]} (get old sid)]
            (when (identical? s session)
              (swap! (:streams server-state) dissoc gen)
              ;; Should the browser still be there, it reconnects and
              ;; starts a new session.
              (close-stream! gen))))))))

(defn- touch!
  "Note that the browser behind session `sid` is still there."
  [server-state sid]
  (swap! (:sessions server-state)
         (fn [m] (if (contains? m sid) (assoc-in m [sid :seen] (now)) m))))

(defn- attach!
  "Point session `s` at the stream `gen`, closing any earlier one: the
  browser has moved on from it, though the server may not have noticed
  it go. Returns false, attaching nothing, if the session has ended or
  is ending."
  [server-state s gen]
  (let [sid (:id s)
        send (fn [msg] (datastar/send! gen msg))
        entry {:session s :gen gen :send send :seen (now)}
        ;; Checked inside the swap: `end-session!` marks the session
        ;; closed before it forgets it, so either this sees the mark,
        ;; or `end-session!` forgets this entry and closes `gen`.
        [old new] (swap-vals! (:sessions server-state)
                              #(if (session/closed? s) % (assoc % sid entry)))]
    (if-not (identical? entry (get new sid))
      false
      (let [old-gen (get-in old [sid :gen])]
        (swap! (:streams server-state) assoc gen sid)
        (session/set-transport! s send)
        (when (and old-gen (not= old-gen gen))
          (swap! (:streams server-state) dissoc old-gen)
          (close-stream! old-gen))
        true))))

(defn- detach!
  "Stream `gen` has closed: stop its session sending to it -- unless a
  newer stream has taken the session over already."
  [server-state gen]
  (let [[old _] (swap-vals! (:streams server-state) dissoc gen)
        sid (get old gen)]
    (when (string? sid)
      (let [{:keys [session send] g :gen} (get @(:sessions server-state) sid)]
        ;; Clears only the transport this stream installed, atomically:
        ;; one a new stream has just attached is left alone.
        (when (and session (= gen g))
          (session/clear-transport! session send))))))

(defn- housekeeping!
  "Run periodically: comment on every stream so proxies keep idle ones
  open, and end sessions whose browser has not been heard from (by
  heartbeat, signals or a reconnecting stream) within the timeout."
  [server-state]
  (doseq [gen (keys @(:streams server-state))]
    (try (datastar/keep-alive! gen) (catch Exception _ nil)))
  (let [cutoff (- (now) (:session-timeout-ms (:config server-state)))]
    (doseq [[sid {:keys [seen]}] @(:sessions server-state)
            :when (< seen cutoff)]
      (end-session! server-state sid))))

;; ---------------------------------------------------------------------------
;; Loading apps

;; Apps are resolved on the server's `:executor`, never on the thread
;; that needs one, which waits no longer than `:app-load-timeout-ms`:
;; an `app.clj` that never returns must not hold anything else up.
;; `(:loads server-state)` maps a mount's path to its load in progress
;; (see `load!`), which every request for the app meanwhile shares -- so
;; a hung app ties up one thread, not one per request.
;;
;; As Clojure's `require` isn't thread-safe, apps load one at a time,
;; each waiting its turn for the server's one load permit,
;; `:load-permit` (see `serializer`). Nothing but loads takes it:
;; requests for apps already loaded, and code requiring a namespace (in
;; a session, or in a future an `app.clj` waits for), never wait for
;; it. A load is timed from when it gets the permit. One still running
;; `:app-load-timeout-ms` later fails, has its permit released for it
;; -- so the next load starts, whatever this one does -- and is
;; interrupted. If it ignores the interrupt (blocked reading a socket
;; that never answers, say), its thread carries on, abandoned, until it
;; returns or the server restarts; should it return an app after all,
;; that app is served. Meanwhile it may still be loading code as later
;; loads run, and the two could then race as Clojure's `require`
;; isn't thread-safe: a risk taken so that one stuck `app.clj` can't
;; keep every app after it from loading.
;;
;; An app that failed to load, or ran out of time, stays failed (see
;; `current-failure`): requests for it get its failure at once, and the
;; index page reports it, without loading it again -- until its files
;; change (`apps/source-fingerprint`). Then the next request loads it
;; once more.

(defn- submit! [server-state f]
  (.execute ^ExecutorService (:executor server-state) ^Runnable f))

(defn- schedule!
  "Run `f` on the load timer in `ms`; nil once the server has stopped."
  [server-state ms f]
  (try (.schedule ^ScheduledExecutorService (:load-timer server-state) ^Runnable f (long ms) TimeUnit/MILLISECONDS)
       (catch RejectedExecutionException _ nil)))

(defn- progress!
  "Note that a load has just started or ended (see `load-apps!`)."
  [server-state]
  (reset! (:load-progress server-state) (now)))

(defn- finish!
  "Give load `l` its result `r`, unless it has one, and forget it."
  [server-state path l r]
  (deliver (:result l) r)
  (swap! (:loads server-state) #(if (identical? l (get % path)) (dissoc % path) %)))

(defn- failure-reason
  "Why an app failed to load, `t` being what it threw."
  [^Throwable t]
  (let [compiler? (instance? clojure.lang.Compiler$CompilerException t)
        ;; Such an exception's own message only says where.
        ^Throwable c (or (when compiler? (ex-cause t)) t)
        {:clojure.error/keys [source line]} (when compiler? (ex-data t))]
    (str (or (ex-message c) (.getName (class c)))
         (when (and source (not= c t))
           (str " (" (.getName (io/file (str source))) (when line (str ":" line)) ")")))))

(defn- fail!
  "Load `l` of `mount`'s app has failed, because of `cause`: make that
  its result, and remember it, so that the app reports it at once,
  without loading again, until its files change (see
  `current-failure`). Returns the error the app reports."
  [server-state {:keys [path source]} l ^Throwable cause]
  (let [e (ex-info (str "The app at " path " failed to load: " (failure-reason cause) "; "
                        (apps/retry-hint source) ".")
                   {::not-loaded path ::failed path} cause)]
    (when-not (closing? server-state)
      (swap! (:failures server-state) assoc path
             {:fp (or @(:fp l) (apps/source-fingerprint source)) :error e :load l}))
    (finish! server-state path l {:error e})
    e))

(defn- current-failure
  "How `mount`'s app failed to load, if it did and its files haven't
  changed since: `{:error e ...}`."
  [server-state {:keys [path source]}]
  (when-let [f (get @(:failures server-state) path)]
    (when (= (:fp f) (apps/source-fingerprint source))
      f)))

(defn- release-permit!
  "Release the load permit load `l` holds, if it holds it still. Exactly
  once: the load releases it as it finishes, unless `time-out!` has
  released it for the load first."
  [server-state l]
  (when (.compareAndSet ^AtomicBoolean (:permit l) true false)
    (.release ^Semaphore (:load-permit server-state))
    (progress! server-state)))

(defn- time-out!
  "Load `l` has run out of time, unless its clock has been stopped or
  reset since `clock` was set: fail it, release its permit so that the
  next load can start, and interrupt it."
  [server-state {:keys [path source] :as mount} l clock]
  (let [timeout (:app-load-timeout-ms (:config server-state))
        ^Thread thread @(:thread l)
        ;; Under `l`'s lock, so as not to interrupt its thread once it
        ;; has moved on to other work (see `run-load!`).
        timed-out? (locking l
                     (when (and (identical? clock @(:clock l)) (not @(:done l)))
                       (when-not (closing? server-state)
                         (log "the app at" path "took longer than" (str timeout "ms") "to load; interrupting it."
                              "It reports this when opened;" (apps/retry-hint source)))
                       ;; Before interrupting it, so that this, not the
                       ;; error the interrupt causes, is its result.
                       (fail! server-state mount l
                              (ex-info (str "it took longer than " timeout "ms, so its load was stopped")
                                       {::timed-out true}))
                       (release-permit! server-state l)
                       (.interrupt thread)
                       true))]
    (when timed-out?
      (progress! server-state)
      (when-not (closing? server-state)
        (schedule! server-state (min 1000 timeout)
                   #(when-not (or @(:done l) (closing? server-state))
                      (log "the app at" path "ignored its interruption and is still loading, on thread"
                           (str (.getName thread) ".") "It is abandoned: it no longer holds up other loads,"
                           "and if it finishes, its app is served. Restart the server to reclaim the thread")))))))

(defn- stop-clock!
  "Stop timing load `l`."
  [l]
  (some-> ^Future @(:timer l) (.cancel false)))

(defn- start-clock!
  "Time load `l` from now."
  [server-state mount l]
  (stop-clock! l)
  (let [clock {:at (now)}]
    (reset! (:clock l) clock)
    (reset! (:timer l) (schedule! server-state (:app-load-timeout-ms (:config server-state))
                                  #(time-out! server-state mount l clock)))))

(defn- serializer
  "Load `l`'s `apps/*serializer*`: it runs a load once it has the
  server's load permit, and times it from then (see `time-out!`).

  The permit is the server's own (each server has one): nothing but its
  app loads waits for it, unlike Clojure's require lock. A load that
  runs out of time has it released on its behalf, exactly once (see
  `release-permit!`), whether or not its thread ever returns. Residual
  risk: a thread abandoned that way while it is still requiring
  namespaces can race the next load, as Clojure's `require` isn't
  thread-safe; that is preferred to one stuck `app.clj` blocking every
  load after it for good."
  [server-state {:keys [source] :as mount} l]
  (fn [f]
    ;; Not timed while it waits its turn.
    (locking l (reset! (:clock l) nil))
    (stop-clock! l)
    (.acquire ^Semaphore (:load-permit server-state))
    (.set ^AtomicBoolean (:permit l) true)
    (try
      ;; As the files are now, before it reads them: should they
      ;; change while it loads, it is loaded again.
      (reset! (:fp l) (apps/source-fingerprint source))
      (progress! server-state)
      (start-clock! server-state mount l)
      (f)
      (finally
        ;; Stopped, under its lock, before the permit goes, so that it
        ;; can't run out of time once done with it.
        (locking l (swap! (:clock l) #(some-> % (assoc :stopped (now)))))
        (stop-clock! l)
        (release-permit! server-state l)))))

(defn- run-load!
  "Load `mount`'s app, as load `l` (see `load!`), on this thread."
  [server-state {:keys [path source] :as mount} l]
  (reset! (:thread l) (Thread/currentThread))
  ;; An app source that doesn't wait its turn is timed from now.
  (start-clock! server-state mount l)
  (let [t0 (now)
        r (try
            (binding [apps/*serializer* (serializer server-state mount l)]
              {:app (apps/resolve-app source)})
            (catch Throwable t {:error t}))
        ;; Whether it ran out of time, and has failed already.
        late? (locking l
                (reset! (:done l) true)
                (realized? (:result l)))]
    (stop-clock! l)
    ;; Clear an interrupt meant for this load, now done.
    (Thread/interrupted)
    (progress! server-state)
    (cond
      (:app r)
      (do
        ;; Served from now on, even if it ran out of time first -- unless
        ;; another load has failed since.
        (swap! (:failures server-state)
               (fn [m] (if (or (not late?) (identical? l (:load (get m path)))) (dissoc m path) m)))
        (finish! server-state path l r))

      late? nil

      :else
      (do
        (fail! server-state mount l (:error r))
        (when-not (closing? server-state)
          (log "the app at" path "failed to load after" (str (- (now) t0) "ms -") (failure-reason (:error r))
               (str "(it reports this when opened; " (apps/retry-hint source) ")")))))
    (when (and late? (not (closing? server-state)))
      (let [ms (some->> @(:clock l) :at (- (now)))]
        (log "the app at" path
             (if (:app r)
               (str "finished loading after " ms "ms, past its timeout; serving it")
               (str "stopped loading after " ms "ms - " (failure-reason (:error r)))))))))

(defn- load!
  "The load of `mount`'s app in progress, starting one if there is none:
  `{:result <promise of {:app a} or {:error e}> :requested <ms>
  :clock <atom of {:at <ms>} while it is timed, else nil> ...}`. An app
  that failed to load, its files unchanged since, isn't loaded again:
  its load fails at once."
  [server-state {:keys [path] :as mount}]
  (let [loads (:loads server-state)
        mine {:result (promise) :requested (now)
              :clock (atom nil) :timer (atom nil) :thread (atom nil) :done (atom false)
              ;; Whether it holds the load permit (see `release-permit!`).
              :permit (AtomicBoolean. false)
              ;; Its source's fingerprint as it began loading.
              :fp (atom nil)}
        current (get (swap! loads #(if (contains? % path) % (assoc % path mine))) path)]
    (when (identical? current mine)
      ;; Checked once this is the app's only load, as a failure is
      ;; remembered before its load is forgotten: so a change to its
      ;; files has it loaded again just once.
      (if-let [{:keys [error]} (current-failure server-state mount)]
        (finish! server-state path mine {:error error})
        (try
          (submit! server-state #(run-load! server-state mount mine))
          (catch Throwable t (finish! server-state path mine {:error t})))))
    current))

(defn- await-load
  "The app that load `l` (see `load!`) produced, waiting until `deadline`
  at the latest. Throws its error if it failed, and an exception with
  `::not-loaded` in its data if it hasn't finished."
  [path {:keys [result requested clock]} deadline]
  (let [r (deref result (max 0 (- deadline (now))) nil)]
    (cond
      (nil? r) (throw (ex-info (str "The app at " path " has not finished loading after "
                                    (- (now) requested) "ms"
                                    (when-not @clock " (it is waiting for an earlier load to finish)")
                                    "; see the server log.")
                               {::not-loaded path}))
      (:error r) (throw (:error r))
      :else (:app r))))

(defn- resolve-app
  "The app mounted at `mount`, loading it if need be, but waiting no
  longer than `:app-load-timeout-ms`."
  [server-state mount]
  (await-load (:path mount) (load! server-state mount)
              (+ (now) (:app-load-timeout-ms (:config server-state)))))

;; ---------------------------------------------------------------------------
;; Starting sessions

(defn- stream-failed!
  "No session could start on stream `gen` (its app failed to load,
  say): tell the page why."
  [server-state mount gen ^Throwable t]
  (log "failed to start session for" (:path mount) "-" (ex-message t))
  ;; Stops the page waiting for `connected`, so no connection notice
  ;; covers the error.
  (datastar/send! gen {:type "failed"})
  (datastar/send! gen {:type "notification" :level "error" :duration nil
                       :message (str "The app could not start: "
                                     (if (:sanitize-errors? (:config server-state))
                                       "see the server log."
                                       (ex-message t)))})
  ;; Left open, as closing it would have the browser retry (and fail)
  ;; over and over; but tracked, so it gets keep-alives and `stop!`
  ;; closes it.
  (swap! (:streams server-state) update gen #(if (string? %) % ::no-session))
  (when (closing? server-state) (close-stream! gen)))

(defn- session-opened!
  "Session `s` is running on a stream that has just opened."
  [server-state s]
  (when (closing? server-state)
    ;; `stop!` began closing streams and sessions while this one was
    ;; opening, and may have missed it.
    (end-session! server-state (:id s))))

(defn- apply-pending!
  "Hand the new session `s` the signals held for it while its app
  loaded, then stop holding them. A post that arrives meanwhile is held
  too, and applied after them (see `receive-signals`); one after that
  goes straight to the session, behind them. So signals reach the
  session in the order they came."
  [server-state s]
  (let [pending (:pending server-state)
        sid (:id s)]
    (loop [signals (get @pending sid)]
      (session/receive! s signals)
      (let [[_ held] (swap-vals! pending #(if (identical? signals (get % sid)) (dissoc % sid) %))]
        (when (contains? held sid)
          (recur (get held sid)))))))

(defn- start-session!
  "Start a new session on stream `gen`, acknowledging the stream before
  the app is resolved: loading an app (a namespace to require, an
  `app.clj`, its deps) can take a while, and the page takes a long wait
  for the acknowledgement as a sign that a proxy is buffering the
  stream. The app is resolved, and the session started, on the
  server's executor rather than on one of http-kit's few request
  threads, which a slow load would otherwise tie up."
  [server-state mount req gen signals]
  (let [sid (str (UUID/randomUUID))
        pending (:pending server-state)
        streams (:streams server-state)]
    ;; Signals posted while the app loads replace these (see
    ;; `receive-signals`).
    (swap! pending assoc sid signals)
    (try
      ;; Tracked while the app loads, so it gets keep-alives, `stop!`
      ;; closes it, and its closing is noticed.
      (swap! streams update gen #(or % ::loading))
      (datastar/send! gen (session/connected-message sid))
      (submit! server-state
               (fn []
                 (try
                   (let [s (session/start! (resolve-app server-state mount)
                                           {:id sid
                                            :request (dissoc req :async-channel :body)
                                            :sanitize-errors? (:sanitize-errors? (:config server-state))})]
                     (if (and (contains? @streams gen) (attach! server-state s gen))
                       (do (session/resend-outputs! s)
                           ;; After attaching, so that later posts find
                           ;; the session.
                           (apply-pending! server-state s)
                           (session-opened! server-state s))
                       ;; The stream closed while the app loaded: the
                       ;; browser has gone, or reconnected and started
                       ;; another session.
                       (session/close! s)))
                   (catch Throwable t
                     (if (contains? @streams gen)
                       (stream-failed! server-state mount gen t)
                       (log "failed to start session for" (:path mount) "-" (ex-message t))))
                   (finally (swap! pending dissoc sid)))))
      (catch Throwable t
        (swap! pending dissoc sid)
        (throw t)))))

(defn- stream
  "The session's event stream. Opening it starts a session -- or
  resumes the one named in the signals, after a reconnect."
  [server-state mount req]
  (let [{:keys [max-sessions]} (:config server-state)
        signals (try (datastar/read-signals req) (catch Exception _ {}))
        sid (get-in signals ["dsh" "session"])
        existing (some-> (get @(:sessions server-state) sid) :session)]
    (cond
      (not (running? server-state))
      {:status 503 :headers {"Content-Type" "text/plain" "Retry-After" "1"} :body "Shutting down"}

      (and (nil? existing) max-sessions
           (>= (+ (session-count server-state) (count @(:pending server-state))) max-sessions))
      {:status 503 :headers {"Content-Type" "text/plain"} :body "At capacity"}

      :else
      (sse/->sse-response
       req
       {:headers {"Cache-Control" "no-cache, no-transform" "X-Accel-Buffering" "no"}
        sse/on-open
        (fn [gen]
          (if (closing? server-state)
            ;; Opened just as the server began closing streams.
            (close-stream! gen)
            (try
              ;; Acknowledge the stream first, straight onto it: the
              ;; page counts the wait for this, and a session busy
              ;; running an observer would hold up anything sent
              ;; through it.
              (if (and existing (not (session/closed? existing)))
                (do (datastar/send! gen (session/connected-message existing))
                    (if (attach! server-state existing gen)
                      (do (session/resend-outputs! existing)
                          (session/receive! existing signals)
                          (session-opened! server-state existing))
                      ;; It ended just as the browser reconnected to
                      ;; it: start over from the page's signals, under
                      ;; a new id.
                      (start-session! server-state mount req gen signals)))
                (start-session! server-state mount req gen signals))
              (catch Throwable t
                (stream-failed! server-state mount gen t)))))
        ;; Not every server reports a client going away from a
        ;; streaming response, so the session isn't ended here: the
        ;; browser's heartbeat and close beacon decide (see
        ;; `housekeeping!`).
        sse/on-close
        (fn [gen & _] (detach! server-state gen))}))))

(defn- read-body [req]
  (try (datastar/read-signals req) (catch Exception _ nil)))

(def ^:private no-content {:status 204 :body ""})

(defn- receive-signals
  "A POST of the page's signals, after one of them changed."
  [server-state req]
  (let [signals (read-body req)
        sid (get-in signals ["dsh" "session"])
        ;; A session whose app is still loading takes them once it
        ;; starts, in the order they came (see `apply-pending!`).
        [pending _] (swap-vals! (:pending server-state)
                                #(if (contains? % sid) (assoc % sid signals) %))]
    (when-not (contains? pending sid)
      (when-let [s (some-> (get @(:sessions server-state) sid) :session)]
        (touch! server-state sid)
        (session/receive! s signals)))
    no-content))

(defn- download [server-state sid id]
  (if-let [s (some-> (get @(:sessions server-state) sid) :session)]
    (if-let [{:keys [filename content-type body]} (session/download! s id)]
      {:status 200
       :headers {"Content-Type" content-type
                 "Content-Disposition" (str "attachment; filename=\""
                                            (str/replace filename #"[\"\\\r\n]" "_")
                                            "\"; filename*=UTF-8''"
                                            (str/replace (URLEncoder/encode ^String filename "UTF-8") "+" "%20"))
                 "Cache-Control" "no-store"}
       :body (java.io.ByteArrayInputStream. ^bytes body)}
      (text 404 "No such download."))
    (text 404 "Session not found; reload the page.")))

;; ---------------------------------------------------------------------------
;; Routing

(defn- app-routes
  "Handle a request inside a mounted app. `sub` is the path below the
  mount point, without a leading slash."
  [server-state mount req sub]
  (cond
    (= sub "")
    (let [the-app (resolve-app server-state mount)]
      (html-response (app/page-html the-app req)))

    (= sub "_dashboards/stream")
    (stream server-state mount req)

    (contains? #{"_dashboards/signals" "_dashboards/alive" "_dashboards/close"} sub)
    (if (not= :post (:request-method req))
      (text 405 "POST here.")
      (case sub
        "_dashboards/signals" (receive-signals server-state req)
        ;; The browser's heartbeat, while the page is open...
        "_dashboards/alive" (do (touch! server-state (get (read-body req) "session")) no-content)
        ;; ...and its goodbye, sent as the page goes away.
        "_dashboards/close" (do (end-session! server-state (get (read-body req) "session")) no-content)))

    (str/starts-with? sub "_dashboards/download/")
    (let [[sid id] (str/split (subs sub (count "_dashboards/download/")) #"/" 2)]
      (download server-state (url-decode sid) (url-decode id)))

    (str/starts-with? sub "_dashboards/")
    (if-let [res (app/asset (subs sub (count "_dashboards/")))]
      {:status 200
       :headers {"Content-Type" (content-type sub) "Cache-Control" "max-age=86400"}
       :body (slurp res)}
      (text 404 "Not found"))

    :else
    (let [the-app (resolve-app server-state mount)
          www (or (some-> (:www the-app) io/file)
                  (some-> (apps/source-dir (:source mount)) (io/file "www")))]
      (or (serve-file www sub)
          (text 404 "Not found")))))

(defn- index-page [server-state mounts]
  (let [{:keys [title]} (:config server-state)
        title (or title "Dashboards")
        ;; Every app's load is started (they run one at a time), but
        ;; for those that failed and haven't changed since (see
        ;; `load!`); then the page waits a moment at most, rather than
        ;; for an app still loading.
        loads (mapv (fn [m] [m (load! server-state m)]) (sort-by :path mounts))
        deadline (+ (now) 1000)]
    (html-response
     (str "<!DOCTYPE html>\n"
          (html/hiccup->html
           [:html {:lang "en"}
            [:head
             [:meta {:charset "utf-8"}]
             [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
             [:title title]
             [:link {:rel "stylesheet" :href (str "_dashboards/dashboards.css")}]]
            [:body.dsh-body
             [:div.dsh-page
              [:header.dsh-header [:div.dsh-brand [:h1.dsh-title title]]]
              [:main.dsh-main
               (if (empty? mounts)
                 [:p.dsh-help "No apps are configured."]
                 [:div.dsh-columns.dsh-columns-auto
                  (for [[{:keys [path]} {:keys [result] :as l}] loads
                        :let [a (try (await-load path l deadline)
                                     (catch Throwable _ nil))]]
                    [:a.dsh-card.dsh-app-link {:href (str (subs path 1) "/")
                                               :style "text-decoration:none;color:inherit"}
                     [:div.dsh-card-body
                      [:strong {:style "font-size:15px"} (or (:title a) (subs path 1))]
                      [:span.dsh-help (cond (and (nil? a) (realized? result)) "Failed to load; see the server log."
                                            (nil? a) "Not loaded yet; see the server log."
                                            (:description a) (:description a)
                                            :else path)]]])])]]]])))))

(defn- current-mounts
  "Mounts from the config's `:apps`, plus, when `:apps-dir` is set,
  one for each app directory found in it now -- so apps can be added
  without a restart."
  [server-state]
  (let [{:keys [apps-dir reload?]} (:config server-state)
        static (:static-mounts server-state)]
    (if apps-dir
      (let [found (apps/scan-apps-dir apps-dir)
            cache (:dir-sources server-state)]
        (into static
              (for [[path dir] found
                    :when (not (some #(= path (:path %)) static))]
                {:path path
                 :source (or (get @cache path)
                             (get (swap! cache assoc path (apps/dir-source dir :reload? reload?)) path))})))
      static)))

(defn- match-mount [mounts uri]
  (->> mounts
       (sort-by #(- (count (:path %))))
       (some (fn [{:keys [path] :as m}]
               (cond
                 (= path "/") [m (subs uri 1)]
                 (= uri path) [m nil] ; needs a trailing slash
                 (str/starts-with? uri (str path "/")) [m (subs uri (inc (count path)))])))))

(defn handler
  "The Ring handler for a server state (see `start!`)."
  [server-state]
  (fn [req]
    (try
      (let [base (:base-path (:config server-state) "")
            uri (:uri req)
            uri (if (and (seq base) (str/starts-with? uri base)) (subs uri (count base)) uri)
            uri (if (str/blank? uri) "/" uri)
            mounts (current-mounts server-state)]
        (cond
          (= uri "/_health")
          (let [loading @(:starting server-state)
                status (cond (not (running? server-state)) "draining"
                             (seq loading) "starting"
                             :else "ok")]
            {:status (if (= "ok" status) 200 503)
             :headers {"Content-Type" "application/json" "Cache-Control" "no-store"}
             :body (json/write-json-str (cond-> {:status status
                                                 :sessions (session-count server-state)}
                                          (= "starting" status) (assoc :loading (sort loading))))})

          :else
          (if-let [[mount sub] (match-mount mounts uri)]
            (if (nil? sub)
              (redirect (str base uri "/" (when-let [q (:query-string req)] (str "?" q))))
              (app-routes server-state mount req sub))
            (cond
              (= uri "/") (index-page server-state mounts)
              (str/starts-with? uri "/_dashboards/")
              (if-let [res (app/asset (subs uri (count "/_dashboards/")))]
                {:status 200 :headers {"Content-Type" (content-type uri)} :body (slurp res)}
                (text 404 "Not found"))
              :else (text 404 "Not found")))))
      (catch Throwable t
        (let [{::keys [not-loaded failed]} (ex-data t)
              sanitize? (:sanitize-errors? (:config server-state))]
          ;; An app's failure to load was logged as it failed.
          (when-not failed
            (log "error handling" (:uri req) "-" (ex-message t)))
          (cond
            (and failed sanitize?) (text 503 (str "The app at " failed " failed to load; see the server log."))
            not-loaded (text 503 (ex-message t))
            :else (text 500 (if sanitize?
                              "Internal server error"
                              (str "Error: " (ex-message t))))))))))

;; ---------------------------------------------------------------------------
;; Starting and stopping

(defn- normalize-path [p]
  (let [p (str "/" (str/replace (str p) #"^/+|/+$" ""))]
    p))

(defn- load-apps!
  "Start loading every app mounted now, so that the first visitors don't
  wait for them; they load one at a time (see `load!`). `/_health`
  reports `starting` until each has loaded, failed or run out of time
  -- or, should the queue somehow stall, has waited a second more than
  `:app-load-timeout-ms` for its turn without any load starting or
  ending meanwhile. One that failed or ran out is logged, and reports
  it when opened, as a broken app mustn't take the others down."
  [server-state]
  (let [mounts (sort-by :path (current-mounts server-state))
        starting (:starting server-state)
        timeout (:app-load-timeout-ms (:config server-state))
        ;; Each load ahead of it ends within `timeout`: the time-out
        ;; that ends one is given a moment more, to fire.
        stalled? (fn [l] (and (nil? @(:clock l))
                              (> (- (now) @(:load-progress server-state)) (+ timeout 1000))))
        t0 (now)]
    (when (seq mounts)
      (reset! starting (set (map :path mounts)))
      (progress! server-state)
      (log "loading" (count mounts) "app(s), one at a time:" (str/join " " (map :path mounts))
           (str "(giving each up to " timeout "ms)"))
      (doseq [{:keys [path] :as mount} mounts]
        (submit! server-state
                 (fn []
                   (try
                     (let [l (load! server-state mount)
                           r (try
                               (loop []
                                 (if-some [r (deref (:result l) 100 nil)]
                                   r
                                   (when-not (or (closing? server-state) (stalled? l))
                                     (recur))))
                               ;; `stop!` stopping the executor.
                               (catch InterruptedException _ nil))
                           ms (str (- (now) t0) "ms")]
                       (when-not (closing? server-state)
                         (cond
                           (nil? r)
                           (log "the app at" path "has not loaded after" (str ms ";")
                                "it is waiting for an earlier load to finish. Serving the others without it")
                           (:app r) (log "loaded the app at" path "in" ms)
                           ;; A failure is logged as it happens (see
                           ;; `run-load!` and `time-out!`).
                           :else nil)))
                     (finally
                       (let [[before after] (swap-vals! starting disj path)]
                         (when (and (contains? before path) (empty? after) (running? server-state))
                           (log "apps loaded in" (str (- (now) t0) "ms;") "/_health reports ok")))))))))))

(defn start!
  "Start a server. Returns a server map; stop it with `stop!`.

  Config:

  - `:port` (8080), `:host` (\"0.0.0.0\")
  - `:apps` -- a sequence of `{:path \"/sales\" :app <source>}`, where
    the source is an app, a var, a qualified symbol or an app
    directory (see `dashboards.server.apps`). A single app at `/` is
    fine.
  - `:apps-dir` -- a directory of app directories, each served at
    `/<name>/`, discovered as they appear.
  - `:reload?` -- reload app directories when their files change
    (default true).
  - `:title` -- the heading of the index page listing the apps.
  - `:base-path` -- a path prefix the server sees in front of every
    URL, when a proxy forwards `/dashboards/...` without stripping it.
  - `:max-sessions` -- refuse new sessions beyond this many.
  - `:session-timeout-ms` -- end a session after this long without
    word from its browser (default 120000). Pages send a heartbeat
    every 20s, but browsers slow timers in background tabs to once a
    minute, so keep it well above that.
  - `:keep-alive-ms` -- how often idle streams get a comment, so that
    proxies keep them open, and timed-out sessions are ended (default
    20000).
  - `:sanitize-errors?` -- don't show exception messages to users.
  - `:shutdown-delay-ms` -- how long `stop!` reports not-ready on
    `/_health` before it closes the open streams (default 0). See
    `stop!`.
  - `:app-load-timeout-ms` -- the longest anything waits for an app to
    load (default 60000). See below.

  The server listens at once. Every app in `:apps`, and every one
  already in `:apps-dir`, then loads in the background, so the first
  visitors don't wait for them. Apps load one at a time, through the
  server's own queue, as Clojure's `require` isn't thread-safe; nothing
  else waits on it, so sessions requiring namespaces carry on while
  apps load. A page or stream for an app still loading waits for it,
  but no longer than `:app-load-timeout-ms`; after that the app reports
  that it hasn't loaded (503). An app still loading
  `:app-load-timeout-ms` after its turn came fails, and is interrupted;
  the next app in the queue starts loading then, so a hung `app.clj`
  holds the others up for no longer than that. If it ignores the
  interrupt (blocked on a socket, say), its thread is abandoned, and
  carries on until it returns (its app is then served after all) or
  the server restarts. An app that fails or runs out of time is logged,
  and answers 503 with its error at once, without loading again, until
  its files change (a directory app's; other sources until a restart):
  the next request then loads it again. The others are served as
  usual.

  `GET /_health` answers 200 with `{\"status\": \"ok\", \"sessions\": n}`
  while the server is running; 503 with `{\"status\": \"starting\",
  \"loading\": [paths]}` until each app it started with has loaded,
  failed or run out of time; and 503 with `{\"status\": \"draining\"}`
  once it is stopping (whether or not it had finished starting). Point
  load balancer health checks or a Kubernetes readiness probe at it."
  [config]
  (let [config (merge {:port 8080 :host "0.0.0.0" :reload? true
                       :session-timeout-ms 120000 :keep-alive-ms 20000
                       :shutdown-delay-ms 0 :app-load-timeout-ms 60000}
                      (into {} (remove (comp nil? val)) config))
        _ (let [t (:app-load-timeout-ms config)]
            ;; From a config file, so possibly anything.
            (when-not (and (number? t) (pos? t))
              (throw (ex-info (str ":app-load-timeout-ms should be a positive number of milliseconds, not "
                                   (pr-str t))
                              {:app-load-timeout-ms t}))))
        static-mounts (vec (for [{:keys [path app]} (:apps config)]
                             {:path (normalize-path (or path "/"))
                              :source (apps/->source app :reload? (:reload? config))}))
        state {:config (update config :base-path #(when (seq %) (normalize-path %)))
               :static-mounts static-mounts
               :dir-sources (atom {})
               :sessions (atom {})
               :pending (atom {})
               :streams (atom {})
               :loads (atom {})
               ;; Apps load one at a time, each holding this (see
               ;; `serializer`); first come, first served.
               :load-permit (Semaphore. 1 true)
               ;; Apps that failed to load (see `current-failure`).
               :failures (atom {})
               :load-progress (atom (now))
               :starting (atom #{})
               ;; Loads apps and starts sessions (see `load!`). Daemon
               ;; threads, as an app that never loads must not keep the
               ;; process alive.
               :executor (Executors/newCachedThreadPool
                          (let [n (atom 0)]
                            (reify ThreadFactory
                              (newThread [_ r]
                                (doto (Thread. ^Runnable r (str "dashboards-loader-" (swap! n inc)))
                                  (.setDaemon true))))))
               ;; Times app loads (see `time-out!`).
               :load-timer (doto (ScheduledThreadPoolExecutor.
                                  1 (reify ThreadFactory
                                      (newThread [_ r]
                                        (doto (Thread. ^Runnable r "dashboards-load-timer")
                                          (.setDaemon true)))))
                             (.setRemoveOnCancelPolicy true))
               :phase (atom :running)
               :stopped (promise)
               :scheduler (Executors/newSingleThreadScheduledExecutor)}
        _ (.scheduleAtFixedRate ^ScheduledExecutorService (:scheduler state)
                                ^Runnable #(try (housekeeping! state)
                                               (catch Throwable t (log "housekeeping failed:" (ex-message t))))
                                (long (:keep-alive-ms config)) (long (:keep-alive-ms config))
                                TimeUnit/MILLISECONDS)
        stop-fn (http/run-server (handler state)
                                 {:port (:port config)
                                  :ip (:host config)
                                  :legacy-return-value? false
                                  :max-body (* 1024 1024)})]
    ;; After listening, so a slow or hung app can't keep the server
    ;; (and its health check) from answering.
    (load-apps! state)
    (assoc state
           :http stop-fn
           :port (http/server-port stop-fn))))

(defn stop!
  "Stop a server started with `start!`, gracefully:

  1. `/_health` starts answering 503 (`{\"status\": \"draining\"}`)
     and new streams are refused with 503, so load balancers stop
     sending browsers here. Everything else, including open streams
     and their sessions, carries on.
  2. After `:shutdown-delay-ms` (from `opts`, else the server's
     config; default 0), every open stream is closed. Browsers
     reconnect within a second -- to another instance, or to this one
     after a restart -- and, as that server doesn't know them, start a
     new session from the inputs they send.
  3. Sessions end (running their `on-ended` callbacks) and the HTTP
     server stops.

  With no delay this takes moments, as at the REPL and in tests. In a
  container, set the delay to how long your load balancer takes to
  notice a failing health check (its interval times its failure
  threshold, plus a little) -- 5000 to 10000 is typical behind
  Kubernetes readiness probes -- and keep it well under the grace
  period before the process is killed (30s in Kubernetes, 10s for
  `docker stop`).

  Safe to call more than once, and from several threads: later calls
  wait for the first to finish. Each step carries on past a failure
  in the one before (logging it), and if the thread is interrupted
  while draining, the remaining steps run at once (and the thread's
  interrupt status is restored afterwards), so the server always ends
  up stopped. Returns nil."
  ([server] (stop! server nil))
  ([server opts]
   (if (compare-and-set! (:phase server) :running :draining)
     (let [interrupted? (volatile! false)
           step (fn [what f]
                  (try (f)
                       (catch InterruptedException _ (vreset! interrupted? true))
                       (catch Throwable t (log "stopping:" what "failed -" (ex-message t)))))
           end-sessions! #(doseq [sid (keys @(:sessions server))]
                            (step (str "ending session " sid) (fn [] (end-session! server sid))))
           stop-housekeeping! #(step "stopping housekeeping"
                                     (fn [] (.shutdownNow ^ScheduledExecutorService (:scheduler server))))]
       (try
         (step "draining"
               (fn []
                 (let [delay-ms (or (:shutdown-delay-ms opts) (:shutdown-delay-ms (:config server)) 0)]
                   ;; From a config file, so possibly anything.
                   (when-not (and (number? delay-ms) (not (neg? delay-ms)))
                     (throw (ex-info (str ":shutdown-delay-ms should be a number of milliseconds, not "
                                          (pr-str delay-ms))
                                     {:shutdown-delay-ms delay-ms})))
                   (when (pos? delay-ms)
                     (log (str "draining: /_health reports 503; closing " (count @(:streams server))
                               " stream(s) in " delay-ms "ms"))
                     (Thread/sleep (long delay-ms))))))
         (reset! (:phase server) :closing)
         (stop-housekeeping!)
         (doseq [gen (keys @(:streams server))]
           (close-stream! gen))
         (end-sessions!)
         (finally
           ;; Whatever happened above, the server ends up stopped.
           (reset! (:phase server) :closing)
           (stop-housekeeping!)
           (step "stopping the HTTP server"
                 #(some-> (http/server-stop! (:http server) {:timeout 1000}) (deref 5000 nil)))
           ;; Interrupts any app still loading.
           (step "stopping app loading"
                 #(do (.shutdownNow ^ExecutorService (:executor server))
                      (.shutdownNow ^ScheduledExecutorService (:load-timer server))))
           ;; Any session a stream raced to start while we were closing.
           (end-sessions!)
           (reset! (:phase server) :stopped)
           (deliver (:stopped server) true)
           (when @interrupted? (.interrupt (Thread/currentThread))))))
     @(:stopped server))
   nil))

(defonce ^:private dev-server (atom nil))

(defn run-app!
  "Serve one app, for development. `app` is an app, a var holding one
  (recommended: `#'app`, so re-evaluating it takes effect when you
  reload the page), a qualified symbol, or an app directory. Stops
  the server a previous `run-app!` started. Options as for `start!`;
  `:port` defaults to 8080 (0 picks a free port).

      (run-app! #'app)
      (run-app! #'app {:port 3000})"
  ([app] (run-app! app {}))
  ([app opts]
   (when-let [old @dev-server] (stop! old))
   (let [server (start! (merge {:host "127.0.0.1"} opts {:apps [{:path "/" :app app}]}))]
     (reset! dev-server server)
     (log (str "serving at http://" (if (= "0.0.0.0" (:host opts)) "localhost" (or (:host opts) "localhost"))
               ":" (:port server) "/"))
     server)))

(defn stop-app!
  "Stop the server started by `run-app!`."
  []
  (when-let [old @dev-server]
    (stop! old)
    (reset! dev-server nil)))
