(ns dashboards.session
  "One browser tab's connection to an app.

  A session owns the tab's inputs, runs the app's server function once,
  and keeps the outputs it returns up to date. It speaks a small
  message protocol but knows nothing about how messages travel: the
  hosting layer (see the `dashboards.server` module) hands it a
  `send!` function and feeds it what the browser sends with
  `receive!`. That separation is what lets the same app run under the
  bundled server, inside a test, or behind any other transport.

  Messages are maps. From the browser:

      {:type \"init\"   :inputs {id value ...}}
      {:type \"input\"  :id \"n\" :value 10 :kind \"edn\"|\"date\"|nil}
      {:type \"inputs\" :inputs {id value ...} :kinds {id kind}}
      {:type \"bind\"   :outputs [\"plot\" ...]}   ; resend these outputs
      {:type \"ping\"}

  To the browser:

      {:type \"hello\" :session \"<id>\"}
      {:type \"output\" :id \"plot\" :html \"...\" :status \"ok\"|\"empty\"|\"validation\"|\"error\" :message \"...\"}
      {:type \"recalculating\" :id \"plot\"}
      {:type \"busy\"} {:type \"idle\"}
      {:type \"update-input\" :id \"x\" :props {...}}
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

(defn- name-or-str [id] (if (keyword? id) (html/id->str id) (str id)))

(defn- input-cell
  "The reactive value holding input `id`, created on first use."
  [session id]
  (let [inputs (:inputs session)]
    (or (get @inputs id)
        (get (swap! inputs (fn [m] (if (contains? m id) m (assoc m id (r/value nil :label id)))))
             id))))

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

(defn- decode-input [kind v]
  (case kind
    "edn" (if (sequential? v) (mapv html/decode-value v) (html/decode-value v))
    "date" (when (and (string? v) (not (str/blank? v)))
             (try (LocalDate/parse v) (catch Exception _ nil)))
    (if (sequential? v) (vec v) v)))

(defn- set-input! [session id kind v]
  (reset! (input-cell session (html/str->id (name-or-str id))) (decode-input kind v)))


(defn client-size
  "The measured size `[width height]` of output `id` in the browser, or
  nil before it has been measured. Reactive."
  [session id]
  (let [in (->Input session)
        w (in (keyword "clientdata" (str "output-" (html/id->str id) "-width")))
        h (in (keyword "clientdata" (str "output-" (html/id->str id) "-height")))]
    (when (and (number? w) (number? h) (pos? w) (pos? h))
      [w h])))

;; ---------------------------------------------------------------------------
;; Sending

(defn send!
  "Send a message map to the session's browser."
  [session msg]
  (try ((:send! session) msg)
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
  "Change an input in the browser. `props` may hold `:value`, `:label`,
  `:choices` (for selects, radio buttons and checkbox groups, in any
  form `ui/select-input` accepts), `:selected`, `:min`, `:max`, `:step`.
  The input's new value comes back to the server as usual.

      (update-input! :y {:choices numeric-columns :selected :body-mass})"
  ([id props] (update-input! nil id props))
  ([session id props]
   (let [encode (fn [v] (if (coll? v) (mapv html/encode-value v) (html/encode-value v)))
         props (cond-> props
                 (:choices props) (update :choices #(mapv (fn [{:keys [value label]}]
                                                            {:value (html/encode-value value) :label label})
                                                          (html/normalize-choices %)))
                 (contains? props :selected) (update :selected encode)
                 (some? (:value props)) (update :value #(if (instance? LocalDate %) (str %) %)))]
     (send! (current session) {:type "update-input" :id (name-or-str id) :props props}))))

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
  (let [id-str (html/id->str id)]
    (try
      (let [html (binding [render/*size* (client-size session id)]
                   (render/run spec))]
        {:type "output" :id id-str :html html :status (if (str/blank? html) "empty" "ok")})
      (catch Throwable t
        (cond
          (r/silent? t) {:type "output" :id id-str :html "" :status "empty"}
          (r/validation? t) {:type "output" :id id-str :html "" :status "validation"
                             :message (ex-message t)}
          :else (do (log-error session (str "output " id-str) t)
                    {:type "output" :id id-str :html "" :status "error"
                     :message (error-message session t)}))))))

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

  The server function runs once the browser's `init` message arrives."
  [app {:keys [send! request id sanitize-errors?]}]
  (let [id (or id (str (UUID/randomUUID)))
        session-p (promise)
        domain (r/domain :label (str "session " id)
                         :bindings (fn [] {#'*session* @session-p})
                         :on-busy #(send! {:type "busy"})
                         :on-idle #(send! {:type "idle"})
                         :on-error (fn [obs t]
                                     (log-error @session-p (or (:label obs) "observer") t)
                                     (send! {:type "notification" :level "error" :duration nil
                                             :message (str "Error: " (error-message @session-p t))})))
        session {:id id
                 :app app
                 :send! send!
                 :request request
                 :sanitize-errors? sanitize-errors?
                 :domain domain
                 :inputs (atom {})
                 :outputs (atom {})
                 :downloads (atom {})
                 :on-ended (atom [])
                 :started (atom false)}]
    (deliver session-p session)
    (send! {:type "hello" :session id})
    session))

(defn- handle! [session {:keys [type] :as msg}]
  (case type
    "init"
    (do (doseq [[id v] (:inputs msg)]
          (set-input! session id (get-in msg [:kinds id]) v))
        (when (compare-and-set! (:started session) false true)
          (try (start-server-fn! session)
               (catch Throwable t
                 (log-error session "server function" t)
                 (send! session {:type "notification" :level "error" :duration nil
                                 :message (str "The app failed to start: " (error-message session t))})))))

    "input"
    (set-input! session (:id msg) (:kind msg) (:value msg))

    "inputs"
    (doseq [[id v] (:inputs msg)]
      (set-input! session id (get-in msg [:kinds id]) v))

    "bind"
    (doseq [id (:outputs msg)]
      (when-let [m (get @(:outputs session) (str id))]
        (send! session m)))

    "ping" nil

    (binding [*out* *err*]
      (println "dashboards: unknown message type" (pr-str type)))))

(defn- keywordize-map-keys
  "Input ids arrive from JSON as keys; keep them as strings."
  [m]
  (into {} (map (fn [[k v]] [(name-or-str k) v])) m))

(defn receive!
  "Handle a message from the browser. `msg` is a map with keyword keys
  at the top level (as parsed from JSON). Processing happens on the
  session's thread; this returns immediately."
  [session msg]
  (let [msg (cond-> msg
              (:inputs msg) (update :inputs keywordize-map-keys)
              (:kinds msg) (update :kinds keywordize-map-keys))]
    (r/submit! (:domain session) #(handle! session msg))))

(defn download!
  "Produce the download `id` for this session: `{:filename
  :content-type :body}`, or nil if there is no such download. Blocks
  until done; runs on the session's thread."
  [session id]
  (when-let [spec (get @(:downloads session) (str id))]
    (r/submit-sync! (:domain session)
                    #(r/isolate (render/download-content spec)))))

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
