(ns dashboards.reactive
  "A small reactive engine in the style of Shiny's.

  Three kinds of node make up a reactive graph:

  - **values** (`value`) hold state. They work like atoms: `deref`,
    `reset!` and `swap!` all work, and writing a new value invalidates
    everything that read the old one.
  - **reactives** (`reactive`) are cached, lazily computed expressions.
    They remember which values and reactives they read, and recompute
    only after one of those changes.
  - **observers** (`observe`) are eager side effects. They re-run
    whenever something they read changes. Outputs are observers that
    push freshly rendered HTML to the browser.

  Dependencies are discovered at run time: whatever a reactive or
  observer reads while it runs is what it depends on, so they can
  change from one run to the next.

  Each run of a reactive or observer happens inside a *context*.
  Reading a value from inside a context links the two; invalidating
  the context (because the value changed) marks the reactive stale or
  schedules the observer to run again.

  Observers belong to a *domain*: a serial executor that runs them one
  at a time, in order. Each browser session is a domain, so all of a
  session's code runs on one logical thread and never races with
  itself. Values and reactives created outside any session (at the
  top level of an app namespace, say) are shared by every session; a
  background thread writing to a shared value re-renders every open
  session that reads it."
  (:import (java.util.concurrent ConcurrentLinkedQueue ExecutorService Executors
                                 ScheduledExecutorService ThreadFactory TimeUnit)
           (java.util.concurrent.atomic AtomicBoolean AtomicLong)))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Executors

(defn- thread-factory ^ThreadFactory [prefix daemon?]
  (let [n (AtomicLong.)]
    (reify ThreadFactory
      (newThread [_ r]
        (doto (Thread. ^Runnable r (str prefix "-" (.incrementAndGet n)))
          (.setDaemon daemon?))))))

(defonce ^:private ^ExecutorService worker-pool
  (Executors/newCachedThreadPool (thread-factory "dashboards-worker" true)))

(defonce ^:private ^ScheduledExecutorService timer-pool
  (Executors/newScheduledThreadPool 1 (thread-factory "dashboards-timer" true)))

(defn- serial-executor
  "An executor that runs submitted tasks one at a time, in submission
  order, borrowing threads from a shared pool. Cheaper than a thread
  per session, and just as strict about ordering."
  []
  (let [queue (ConcurrentLinkedQueue.)
        running (AtomicBoolean. false)]
    (letfn [(drain []
              (loop []
                (when-let [^Runnable task (.poll queue)]
                  (try (.run task)
                       (catch Throwable t
                         (binding [*out* *err*]
                           (println "dashboards: uncaught error in task:" (ex-message t)))))
                  (recur)))
              (.set running false)
              ;; A task may have been queued after the last poll but
              ;; before the flag was cleared.
              (when (and (not (.isEmpty queue)) (.compareAndSet running false true))
                (.execute worker-pool ^Runnable drain)))]
      (fn submit [^Runnable task]
        (.add queue task)
        (when (.compareAndSet running false true)
          (.execute worker-pool ^Runnable drain))))))

;; ---------------------------------------------------------------------------
;; Contexts

(def ^:dynamic *context*
  "The context of the reactive or observer that is currently running,
  or nil. Reads made while it is bound become dependencies."
  nil)

(def ^:dynamic *domain*
  "The domain whose code is currently running, or nil outside one."
  nil)

(defonce ^:private id-counter (AtomicLong.))
(defn- next-id [] (.incrementAndGet ^AtomicLong id-counter))

(defn- new-context []
  {:id (next-id)
   :state (atom {:invalidated? false :callbacks []})})

(defn- run-callback [f]
  (try (f)
       (catch Throwable t
         (binding [*out* *err*]
           (println "dashboards: error in invalidation callback:" (ex-message t))))))

(defn invalidated?
  "Has `ctx` been invalidated?"
  [ctx]
  (:invalidated? @(:state ctx)))

(defn on-invalidate
  "Call `f` when `ctx` is invalidated, or right away if it already is.
  With one argument, uses the current context."
  ([f] (when *context* (on-invalidate *context* f)))
  ([ctx f]
   (let [[old _] (swap-vals! (:state ctx)
                             (fn [s] (if (:invalidated? s) s (update s :callbacks conj f))))]
     (when (:invalidated? old) (run-callback f)))))

(defn invalidate!
  "Invalidate `ctx`, running its callbacks once. With no argument,
  invalidates the current context."
  ([] (when *context* (invalidate! *context*)))
  ([ctx]
   (let [[old _] (swap-vals! (:state ctx) assoc :invalidated? true :callbacks [])]
     (when-not (:invalidated? old)
       (run! run-callback (:callbacks old))))))

;; Dependents are kept as a map of context id -> context. A context is
;; removed once it is invalidated, so each run re-registers what it
;; actually reads.

(defn- depend! [dependents]
  (when-let [ctx *context*]
    (let [id (:id ctx)]
      (when-not (contains? @dependents id)
        (swap! dependents assoc id ctx)
        (on-invalidate ctx #(swap! dependents dissoc id))))))

(defn- invalidate-dependents! [dependents]
  (let [[old _] (reset-vals! dependents {})]
    (run! invalidate! (vals old))))

(defmacro isolate
  "Evaluate `body` without taking dependencies on anything it reads."
  [& body]
  `(binding [*context* nil] ~@body))

;; ---------------------------------------------------------------------------
;; Domains

(defn- report-error [domain observer ^Throwable t]
  (if-let [handler (:on-error domain)]
    (handler observer t)
    (binding [*out* *err*]
      (println "dashboards: error in observer" (pr-str (:label observer)) "-" (ex-message t)))))

(declare run-observer!)

(defn- flush! [domain]
  (let [pending (:pending domain)]
    (when (seq @pending)
      (when-let [f (:on-busy domain)] (f))
      (loop []
        (let [[batch _] (reset-vals! pending #{})]
          (when (seq batch)
            ;; Higher priority first; ties run in creation order.
            (doseq [obs (sort-by (juxt (comp - :priority) :id) batch)]
              (run-observer! obs))
            (recur))))
      (when-let [f (:on-idle domain)] (f)))))

(defn domain
  "Create a domain: a serial executor for observers.

  Options:

  - `:label`    -- a name, for error messages.
  - `:on-error` -- `(fn [observer throwable])`, called when an observer
                   throws. Defaults to printing to stderr.
  - `:on-busy`, `:on-idle` -- called around each flush that runs
                   anything, on the domain's thread.
  - `:bindings` -- a map of vars to values bound on the domain thread,
                   or a function of no arguments returning one."
  [& {:as opts}]
  (merge opts
         {:id (next-id)
          :submit (serial-executor)
          :pending (atom #{})
          :observers (atom #{})
          :closed (AtomicBoolean. false)}))

(defn closed? [domain] (.get ^AtomicBoolean (:closed domain)))

(defn- submit* [domain f after]
  (when-not (closed? domain)
    ((:submit domain)
     (fn []
       (if (closed? domain)
         (when after (after nil))
         (with-bindings (let [b (:bindings domain)]
                          (assoc (if (fn? b) (b) b) #'*domain* domain))
           (let [result (try {:value (f)} (catch Throwable t {:error t}))]
             (try (flush! domain)
                  (finally (when after (after result))))
             (when (and (:error result) (not after))
               (throw (:error result))))))))))

(defn submit!
  "Run `f` on the domain's thread with `*domain*` bound, then flush any
  observers it invalidated. Returns nil."
  [domain f]
  (submit* domain f nil)
  nil)

(defn submit-sync!
  "Like `submit!` but waits until `f` and the flush after it are done,
  and returns `f`'s result (or rethrows its exception). Waits up to
  `timeout-ms` (default 60s)."
  ([domain f] (submit-sync! domain f 60000))
  ([domain f timeout-ms]
   (let [p (promise)]
     (if (closed? domain)
       (deliver p nil)
       (submit* domain f #(deliver p %)))
     (let [r (deref p timeout-ms ::timeout)]
       (cond
         (= r ::timeout) (throw (ex-info "Timed out waiting for the session" {}))
         (:error r) (throw (:error r))
         :else (:value r))))))

(defn- schedule! [domain observer]
  (swap! (:pending domain) conj observer)
  (submit! domain (fn [])))

(declare destroy!)

(defn close-domain!
  "Stop the domain: destroy its observers and run no more tasks on it."
  [domain]
  (.set ^AtomicBoolean (:closed domain) true)
  (run! destroy! @(:observers domain))
  (reset! (:pending domain) #{}))

(defonce ^{:doc "The domain for observers created outside any session."}
  global-domain
  (domain :label "global"))

(defn current-domain [] (or *domain* global-domain))


;; ---------------------------------------------------------------------------
;; Values

(defn- swap-state! [state dependents f]
  (let [[old new :as r] (swap-vals! state f)]
    (when (not= old new) (invalidate-dependents! dependents))
    r))

(deftype Value [label dependents state]
  clojure.lang.IDeref
  (deref [_]
    (depend! dependents)
    @state)

  clojure.lang.IFn
  (invoke [this] (.deref this))

  clojure.lang.IAtom2
  (reset [_ v] (second (swap-state! state dependents (constantly v))))
  (swap [_ f] (second (swap-state! state dependents f)))
  (swap [_ f a] (second (swap-state! state dependents #(f % a))))
  (swap [_ f a b] (second (swap-state! state dependents #(f % a b))))
  (swap [_ f a b more] (second (swap-state! state dependents #(apply f % a b more))))
  (compareAndSet [_ old new]
    (let [ok (compare-and-set! state old new)]
      (when (and ok (not= old new)) (invalidate-dependents! dependents))
      ok))
  (resetVals [_ v] (swap-state! state dependents (constantly v)))
  (swapVals [_ f] (swap-state! state dependents f))
  (swapVals [_ f a] (swap-state! state dependents #(f % a)))
  (swapVals [_ f a b] (swap-state! state dependents #(f % a b)))
  (swapVals [_ f a b more] (swap-state! state dependents #(apply f % a b more))))

(defn value
  "A reactive value: an atom whose readers are re-run when it changes.

      (def threshold (value 10))
      @threshold          ; read (and depend on) it
      (reset! threshold 20)
      (swap! threshold inc)

  Calling it with no arguments reads it too: `(threshold)`. Writing
  an equal value is a no-op."
  ([] (value nil))
  ([init & {:keys [label]}]
   (->Value label (atom {}) (atom init))))

(defn value? [x] (instance? Value x))

(defmethod print-method Value [^Value v ^java.io.Writer w]
  (.write w (str "#reactive/value[" (pr-str (isolate @v)) "]")))

;; ---------------------------------------------------------------------------
;; Reactives

(defn- reactive-deref [lock f dependents state]
  (depend! dependents)
  (let [{:keys [value error]}
        (locking lock
          (when-not (:valid? @state)
            (let [ctx (new-context)]
              (swap! state assoc :ctx ctx :valid? true)
              (on-invalidate ctx (fn []
                                    (when (locking lock
                                            (when (identical? ctx (:ctx @state))
                                              (swap! state assoc :valid? false)
                                              true))
                                      (invalidate-dependents! dependents))))
              (let [result (try {:value (binding [*context* ctx] (f)) :error nil}
                                (catch Throwable t {:value nil :error t}))]
                (swap! state merge result))))
          @state)]
    (if error (throw error) value)))

(deftype Reactive [label f dependents state]
  clojure.lang.IDeref
  (deref [this] (reactive-deref this f dependents state))

  clojure.lang.IFn
  (invoke [this] (.deref this)))

(defn reactive*
  "A cached computation of `f` (a function of no arguments) that is
  recomputed only when something it read has changed. Read it with
  `deref` or by calling it."
  [f & {:keys [label]}]
  (->Reactive label f (atom {}) (atom {:valid? false})))

(defmacro reactive
  "A cached reactive expression. `body` runs lazily, the first time
  the reactive is read, and again only after something it read
  changes.

      (def filtered
        (reactive (tc/select-rows data #(= (:species %) (input :species)))))
      @filtered"
  [& body]
  `(reactive* (fn [] ~@body)))

(defn reactive? [x] (instance? Reactive x))

(defmethod print-method Reactive [^Reactive r ^java.io.Writer w]
  (.write w (str "#reactive/reactive[" (or (.-label r) "") "]")))

;; ---------------------------------------------------------------------------
;; Errors that are part of the flow

(defn- blank-ish? [x]
  (or (nil? x) (false? x)
      (and (string? x) (zero? (count (.trim ^String x))))
      (and (coll? x) (empty? x))))

(defn req
  "Stop the current reactive, observer or output quietly unless every
  `x` is present (not nil, false, blank or empty). An output stopped
  this way renders as empty rather than as an error. Returns the last
  `x`.

      (req (input :file))"
  [& xs]
  (doseq [x xs]
    (when (blank-ish? x)
      (throw (ex-info "dashboards/req: required value missing" {::silent true}))))
  (last xs))

(defn silent?
  "Was `t` thrown by `req`?"
  [t]
  (boolean (::silent (ex-data t))))

(defn validate
  "Check conditions an output needs before it can render. Takes pairs
  of test and message; the first failing test (nil, false, blank or
  empty) stops the output and shows its message in place of the
  output, styled as a gentle note rather than an error.

      (validate (seq (input :species)) \"Pick at least one species.\")"
  [& tests-and-messages]
  (doseq [[test msg] (partition 2 tests-and-messages)]
    (when (blank-ish? test)
      (throw (ex-info (str msg) {::validation true}))))
  nil)

(defn validation?
  "Was `t` thrown by `validate`?"
  [t]
  (boolean (::validation (ex-data t))))

;; ---------------------------------------------------------------------------
;; Observers

(defrecord Observer [id label f priority domain on-invalidate state])

(defn- run-observer! [obs]
  (let [state (:state obs)]
    (when-not (:destroyed? @state)
      (let [ctx (new-context)]
        (swap! state assoc :ctx ctx)
        (on-invalidate ctx (fn []
                             (when-not (:destroyed? @state)
                               (when-let [cb (:on-invalidate obs)] (run-callback cb))
                               (schedule! (:domain obs) obs))))
        (try
          (binding [*context* ctx] ((:f obs)))
          (catch Throwable t
            (when-not (silent? t)
              (report-error (:domain obs) obs t))))))))

(defn observe*
  "Run `f` (a function of no arguments) now-ish, on the current
  domain, and again whenever anything it read changes. Returns the
  observer, which `destroy!` stops.

  Options:

  - `:label`         -- a name, for error messages.
  - `:priority`      -- observers with higher priority run first in a
                        flush (default 0).
  - `:domain`        -- the domain to run on (default: current).
  - `:on-invalidate` -- called whenever the observer goes stale."
  [f & {:keys [label priority domain on-invalidate]}]
  (let [d (or domain (current-domain))
        obs (->Observer (next-id) label f (or priority 0) d on-invalidate
                        (atom {:destroyed? false}))]
    (when-let [registry (:observers d)]
      (swap! registry conj obs))
    (schedule! d obs)
    obs))

(defmacro observe
  "Run `body` for its side effects, and again whenever anything it
  read changes."
  [& body]
  `(observe* (fn [] ~@body)))

(defn destroy!
  "Stop an observer for good."
  [obs]
  (let [state (:state obs)]
    (swap! state assoc :destroyed? true)
    (swap! (:pending (:domain obs)) disj obs)
    (when-let [registry (:observers (:domain obs))]
      (swap! registry disj obs))
    (when-let [ctx (:ctx @state)]
      (invalidate! ctx))))

(defn- event-fired? [v ignore-nil?]
  (not (and ignore-nil? (or (nil? v) (false? v)))))

(defn observe-event*
  "Run `(handler v)` each time `(event)` produces a new value `v`.
  Only `event` is tracked; nothing `handler` reads makes it re-run.

  Options: `:ignore-init?` (default false) skips the first run;
  `:ignore-nil?` (default true) skips nil and false events, which is
  what an action button reports before its first click. Also takes
  the options of `observe*`."
  [event handler & {:keys [ignore-init? ignore-nil?] :or {ignore-nil? true} :as opts}]
  (let [first-run (atom true)]
    (apply observe*
           (fn []
             (let [v (event)
                   first? @first-run]
               (reset! first-run false)
               (when (and (event-fired? v ignore-nil?)
                          (not (and first? ignore-init?)))
                 (isolate (handler v)))))
           (mapcat identity (dissoc opts :ignore-init? :ignore-nil?)))))

(defmacro observe-event
  "Run `body` each time `event-expr` changes, without tracking what
  `body` reads. The classic use is an action button:

      (observe-event (input :save)
        (save! @draft))"
  [event-expr & body]
  `(observe-event* (fn [] ~event-expr) (fn [_#] ~@body)))

(defn event-reactive*
  "A reactive that only updates when `(event)` changes, computing its
  value with `f` without tracking what `f` reads. Until the first
  event it stops readers quietly, as `req` does."
  [event f & {:keys [ignore-nil?] :or {ignore-nil? true}}]
  (reactive* (fn []
               (let [v (event)]
                 (when-not (event-fired? v ignore-nil?) (req nil))
                 (isolate (f))))))

(defmacro event-reactive
  "A reactive whose value is `body`, recomputed only when `event-expr`
  changes.

      (def sample (event-reactive (input :resample)
                    (draw-sample (input :n))))"
  [event-expr & body]
  `(event-reactive* (fn [] ~event-expr) (fn [] ~@body)))

;; ---------------------------------------------------------------------------
;; Time

(defn invalidate-later
  "Invalidate the current reactive or observer after `ms`
  milliseconds, so it runs again. Call it inside an observer or
  reactive to make it tick:

      (observe (invalidate-later 1000) (println (java.time.Instant/now)))"
  [ms]
  (when-let [ctx *context*]
    (let [fut (.schedule timer-pool ^Runnable (fn [] (invalidate! ctx))
                         (long ms) TimeUnit/MILLISECONDS)]
      (on-invalidate ctx #(.cancel fut false))))
  nil)

(defn reactive-poll
  "A reactive that re-reads a changing source. Every `interval-ms` it
  calls the cheap `(check)`; when that returns something new it
  recomputes the possibly expensive `(read-value)`.

      (reactive-poll 2000
        #(.lastModified (io/file \"data.csv\"))
        #(tc/dataset \"data.csv\"))"
  [interval-ms check read-value]
  (let [token (value ::unset)]
    (observe* (fn []
                (invalidate-later interval-ms)
                (reset! token (check)))
              :label "reactive-poll"
              :priority 100)
    (reactive* (fn []
                 (req (not= ::unset @token))
                 (isolate (read-value))))))

(defn debounce
  "A reactive that follows `(source)` but only after it has stopped
  changing for `ms` milliseconds. Useful for search boxes and other
  inputs that change quickly.

      (def query (debounce #(input :search) 400))"
  [source ms]
  (let [out (value ::unset)
        d (current-domain)
        pending (atom nil)]
    (observe* (fn []
                (let [v (source)]
                  (some-> ^java.util.concurrent.Future @pending (.cancel false))
                  (if (= ::unset (isolate @out))
                    (reset! out v)
                    (reset! pending
                            (.schedule timer-pool
                                       ^Runnable (fn [] (submit! d #(reset! out v)))
                                       (long ms) TimeUnit/MILLISECONDS)))))
              :label "debounce"
              :priority 100
              :domain d)
    (reactive* (fn []
                 (let [v @out]
                   (req (not= ::unset v))
                   v)))))
