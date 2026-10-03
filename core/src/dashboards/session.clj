(ns dashboards.session
  "One browser tab's connection to an app.

  A session owns the tab's inputs, runs the app's server function once,
  and keeps the outputs it returns up to date. It knows nothing about
  how messages travel: the hosting layer (see the `dashboards.server`
  module) hands it a `send!` function and feeds it what the browser
  sends with `receive!`. That separation is what lets the same app run
  under the bundled server, inside a test, or behind any other
  transport. `dashboards.datastar` turns the messages into the
  Datastar SSE events the browser understands.

  **What survives a reconnect.** Inputs live in the browser, as
  Datastar signals, and every (re)connecting stream sends all of them.
  So when a browser reconnects to a server that doesn't know its
  session -- a different instance behind a load balancer, or this one
  after a restart -- a fresh session starts from those inputs and
  renders the same outputs. Anything kept only on the server for the
  session does not survive that: a `reactive/value` created inside
  the server function, or whatever an `observe-event` handler has
  accumulated (a click count, an undo history), starts over, because
  the server function runs again from scratch. Keep state that must
  outlast a server in inputs, or outside the session (a database; a
  value at the top level of the app's namespace lasts as long as the
  process).

  **From the browser** come Datastar signal snapshots: a map of every
  signal on the page, string keys, as Datastar sends them. Each
  top-level signal is an input, except `dsh`, which carries the
  session id, how to decode each input (`dsh.kinds`) and the measured
  size of each plot (`dsh.sizes`).

  **To the browser** go message maps (`connected` is written by the
  hosting layer as each stream opens; see `connected-message`):

      {:type \"connected\" :session \"<id>\"}
      {:type \"output\" :id \"plot\" :html \"...\" :status \"ok\"|\"empty\"|\"validation\"|\"error\"}
      {:type \"recalculating\" :id \"plot\"}
      {:type \"busy\"} {:type \"idle\"}
      {:type \"signals\" :signals {...}}
      {:type \"choices\" :id \"x\" :signal \"x\" :choices [...] :selected ...}
      {:type \"notification\" :message \"...\" :level \"info\" :duration 5000}"
  (:require [clojure.string :as str]
            [dashboards.html :as html]
            [dashboards.reactive :as r]
            [dashboards.render :as render])
  (:import (java.time LocalDate)
           (java.util UUID)))

(def ^:dynamic *session*
  "The session whose code is running, bound on the session's thread."
  nil)

(defn- current [session]
  (or session *session*
      (throw (ex-info "No session: call this from inside a server function, or pass a session." {}))))

;; ---------------------------------------------------------------------------
;; Inputs
;;
;; Each input is a reactive value, keyed by its Datastar signal name.

(defn- cell
  "The reactive value under `k` in the atom `cells`, created on first use."
  [cells k]
  (or (get @cells k)
      (get (swap! cells (fn [m] (if (contains? m k) m (assoc m k (r/value nil :label k))))) k)))

(defn- input-cell [session id]
  (cell (:inputs session) (if (string? id) id (html/signal-name id))))

(deftype Input [session]
  clojure.lang.IFn
  (invoke [_ id] @(input-cell session id))
  (invoke [_ id not-found] (let [v @(input-cell session id)] (if (nil? v) not-found v)))

  clojure.lang.ILookup
  (valAt [_ id] @(input-cell session id))
  (valAt [_ id not-found] (let [v @(input-cell session id)] (if (nil? v) not-found v))))

(defn input
  "The current session's inputs, as a function of an input id:
  `((input) :n)`. Server functions are given this as `:input`."
  ([] (input nil))
  ([session] (->Input (current session))))

(defn- keywordize [v]
  (cond (map? v) (into {} (map (fn [[k x]] [(keyword k) (keywordize x)])) v)
        (sequential? v) (mapv keywordize v)
        :else v))

(defn- decode-input
  "Turn a signal's JSON value into the input's Clojure value, as its
  kind (declared by the component that made it) says."
  [kind v]
  (case kind
    "edn" (if (sequential? v)
            (into [] (comp (remove #(= "" %)) (map html/decode-value)) v)
            (html/decode-value v))
    "number" (cond (number? v) v
                   (and (string? v) (not (str/blank? v)))
                   (or (parse-long (str/trim v)) (parse-double (str/trim v)))
                   :else nil)
    "date" (when (and (string? v) (not (str/blank? v)))
             (try (LocalDate/parse v) (catch Exception _ nil)))
    "action" (when (and (number? v) (pos? v)) v)
    (keywordize v)))

(defn- encode-input
  "The inverse of `decode-input`, for values the server sets."
  [kind v]
  (case kind
    "edn" (if (sequential? v) (mapv html/encode-value v) (html/encode-value v))
    ("number" "date") (if (nil? v) "" (str v))
    "action" (or v 0)
    (cond (keyword? v) (html/encode-value v)
          (instance? LocalDate v) (str v)
          :else v)))

(defn client-size
  "The measured size `[width height]` of output `id` in the browser, or
  nil before it has been measured. Reactive."
  [session id]
  @(cell (:sizes session) (html/id->str id)))

(defn- set-signals!
  "Apply a snapshot of the page's signals. Inputs missing from the
  snapshot (a brush that was cleared, say) become nil."
  [session signals]
  (let [{:strs [dsh]} signals
        kinds (get dsh "kinds" {})
        inputs (dissoc signals "dsh")]
    (reset! (:kinds session) kinds)
    (doseq [[id size] (get dsh "sizes")]
      (when (and (sequential? size) (= 2 (count size)) (every? pos? size))
        (reset! (cell (:sizes session) id) (vec size))))
    (doseq [[k v] inputs]
      (reset! (input-cell session k) (decode-input (get kinds k) v)))
    (doseq [[k c] @(:inputs session)
            :when (not (contains? inputs k))]
      (reset! c nil))))

;; ---------------------------------------------------------------------------
;; Sending

(defn send!
  "Send a message map to the session's browser."
  [session msg]
  (try (@(:transport session) msg)
       (catch Throwable t
         (binding [*out* *err*]
           (println "dashboards: failed to send to session" (:id session) "-" (ex-message t))))))

(defn notify!
  "Show a notification in the browser.
  Options: `:level` (`:info`, `:success`, `:warning`, `:error`),
  `:duration` in ms (default 5000; nil keeps it until closed)."
  ([message] (notify! nil message {}))
  ([message opts] (notify! nil message opts))
  ([session message {:keys [level duration] :or {level :info duration 5000}}]
   (send! (current session) {:type "notification" :message (str message)
                             :level (name level) :duration duration})))

(defn update-input!
  "Change an input in the browser.

  - `:value` sets the input's value.
  - `:choices` replaces the choices of a select, radio buttons or
    checkbox group (in any form `ui/select-input` accepts), with
    `:selected` saying which are chosen.

      (update-input! :y {:choices numeric-columns :selected :body-mass})"
  ([id props] (update-input! nil id props))
  ([session id {:keys [value choices selected] :as props}]
   (let [session (current session)
         signal (html/signal-name id)
         kind (get @(:kinds session) signal)]
     (if choices
       (send! session {:type "choices"
                       :id (html/id->str id)
                       :signal signal
                       :choices (mapv (fn [{:keys [value label]}]
                                        {:value (html/encode-value value) :label (str label)})
                                      (html/normalize-choices choices))
                       :selected (when (contains? props :selected)
                                   (if (sequential? selected)
                                     (mapv html/encode-value selected)
                                     (html/encode-value selected)))})
       (when (contains? props :value)
         (send! session {:type "signals" :signals {signal (encode-input kind value)}}))))))

(defn on-ended
  "Call `f` when the session ends (the tab closes or disconnects)."
  ([f] (on-ended nil f))
  ([session f] (swap! (:on-ended (current session)) conj f)))

(defn request
  "The HTTP request that opened the session (a Ring request map), for
  reading headers such as a user name set by an authenticating proxy."
  ([] (request nil))
  ([session] (:request (current session))))

(defn session-id
  ([] (session-id nil))
  ([session] (:id (current session))))

;; ---------------------------------------------------------------------------
;; Outputs

(defn- error-message [session ^Throwable t]
  (if (:sanitize-errors? session)
    "An error occurred. Check the server log for details."
    (or (ex-message t) (.getName (class t)))))

(defn- log-error [session label ^Throwable t]
  (binding [*out* *err*]
    (println (str "dashboards: error in " label " (session " (:id session) "): "
                  (or (ex-message t) (class t))))
    (when-let [c (ex-cause t)] (println "  caused by:" (ex-message c)))))

(defn- render-message [session id spec]
  (let [id-str (html/id->str id)
        msg (fn [status html] {:type "output" :id id-str :status status :html html})]
    (try
      (let [out (binding [render/*size* (client-size session id)]
                  (render/run spec))]
        (msg (if (str/blank? out) "empty" "ok") out))
      (catch Throwable t
        (cond
          (r/silent? t) (msg "empty" "")
          (r/validation? t) (msg "validation"
                                 (html/hiccup->html [:div.dsh-output-validation (ex-message t)]))
          :else (do (log-error session (str "output " id-str) t)
                    (msg "error"
                         (html/hiccup->html [:div.dsh-output-error [:strong "Error"] " "
                                             (error-message session t)]))))))))

(defn- add-output! [session id spec]
  (when-not (render/spec? spec)
    (throw (ex-info (str "The server function's map has " (pr-str id) " -> " (pr-str spec)
                         ", which is not a render spec. Wrap the value in one of"
                         " render/text, render/plot, render/table, render/ui, render/auto"
                         " or render/download.")
                    {:id id})))
  (if (= :download (::render/type spec))
    (swap! (:downloads session) assoc (html/id->str id) spec)
    (let [id-str (html/id->str id)]
      (r/observe* (fn []
                    (let [msg (render-message session id spec)]
                      (swap! (:outputs session) assoc id-str msg)
                      (send! session msg)))
                  :label (str "output " id-str)
                  :on-invalidate #(send! session {:type "recalculating" :id id-str})))))

(defn- start-server-fn! [session]
  (let [{:keys [server]} (:app session)
        outputs (when server
                  (server {:input (->Input session) :session session}))]
    (when (and outputs (not (map? outputs)))
      (throw (ex-info "A server function must return a map of output id -> render spec (or nil)."
                      {:returned (type outputs)})))
    (doseq [[id spec] outputs]
      (add-output! session id spec))))

;; ---------------------------------------------------------------------------
;; Lifecycle

(defn start!
  "Start a session for `app`.

  Options:

  - `:send!`   -- `(fn [msg])`, delivers a message map to the browser.
                  Required.
  - `:request` -- the request that opened the connection.
  - `:id`      -- a session id (default: a random UUID).
  - `:sanitize-errors?` -- show a generic message instead of exception
                  messages in outputs (for production).

  The server function runs when the first signal snapshot arrives."
  [app {transport :send! :keys [request id sanitize-errors?]}]
  (let [id (or id (str (UUID/randomUUID)))
        session-p (promise)
        send-msg! (fn [msg] (send! @session-p msg))
        domain (r/domain :label (str "session " id)
                         :bindings (fn [] {#'*session* @session-p})
                         :on-busy #(send-msg! {:type "busy"})
                         :on-idle #(send-msg! {:type "idle"})
                         :on-error (fn [obs t]
                                     (log-error @session-p (or (:label obs) "observer") t)
                                     (send-msg! {:type "notification" :level "error" :duration nil
                                                 :message (str "Error: " (error-message @session-p t))})))
        session {:id id
                 :app app
                 :transport (atom (or transport (fn [_])))
                 :request request
                 :sanitize-errors? sanitize-errors?
                 :domain domain
                 :inputs (atom {})
                 :sizes (atom {})
                 :kinds (atom {})
                 :outputs (atom {})
                 :downloads (atom {})
                 :on-ended (atom [])
                 :started (atom false)}]
    (deliver session-p session)
    session))

(defn set-transport!
  "Send through `send!` from now on (after the browser reconnects,
  say). Pass nil to drop messages while no browser is connected."
  [session send!]
  (reset! (:transport session) (or send! (fn [_]))))

(defn connected-message
  "The message that tells a browser which session it is talking to:
  `{:type \"connected\" :session id}`. The hosting layer writes it
  straight onto each stream as the stream (re)opens, before handing
  the stream to the session with `set-transport!`, so that it arrives
  at once even while the session is busy running an observer."
  [session]
  {:type "connected" :session (:id session)})

(defn resend-outputs!
  "Send every output the browser may have missed, on the session's
  thread (after whatever it is running now). Call it whenever a stream
  (re)opens, after `set-transport!`."
  [session]
  (r/submit! (:domain session)
             (fn []
               (doseq [msg (vals @(:outputs session))]
                 (send! session msg)))))

(defn receive!
  "Apply a snapshot of the browser's signals (a map with string keys,
  as parsed from Datastar's JSON). The first snapshot starts the
  server function. Processing happens on the session's thread; this
  returns immediately."
  [session signals]
  (r/submit! (:domain session)
             (fn []
               (set-signals! session signals)
               (when (compare-and-set! (:started session) false true)
                 (try (start-server-fn! session)
                      (catch Throwable t
                        (log-error session "server function" t)
                        (send! session {:type "notification" :level "error" :duration nil
                                        :message (str "The app failed to start: "
                                                      (error-message session t))})))))))

(defn download!
  "Produce the download `id` for this session: `{:filename
  :content-type :body}`, or nil if there is no such download. Blocks
  until done; runs on the session's thread."
  [session id]
  (when-let [spec (get @(:downloads session) (str id))]
    (r/submit-sync! (:domain session)
                    #(r/isolate (render/download-content spec)))))

(defn closed? [session] (r/closed? (:domain session)))

(defn close!
  "End the session: run its `on-ended` callbacks and stop its
  observers."
  [session]
  (let [domain (:domain session)]
    (when-not (r/closed? domain)
      (doseq [f @(:on-ended session)]
        (try (binding [*session* session] (f))
             (catch Throwable t (log-error session "on-ended callback" t))))
      (r/close-domain! domain))))
