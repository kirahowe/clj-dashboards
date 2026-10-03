(ns dashboards.datastar
  "Session messages as Datastar server-sent events, written with the
  Datastar Clojure SDK (https://github.com/starfederation/datastar-clojure).

  The browser runs Datastar (https://data-star.dev): one long-lived SSE
  stream per tab carries everything the server sends, and the page
  posts its signals back whenever they change. Outputs arrive as
  standard `datastar-patch-elements` events, which morph the new HTML
  into the output's element; input values the server sets arrive as
  `datastar-patch-signals`. What Datastar has no event for -- busy and
  recalculating states, notifications, replacing an input's choices --
  travels as a `datastar-dashboards` event, handled by the small
  companion script `dashboards.js`.

  Any server the SDK has an adapter for (http-kit, Ring, ...) can carry
  a session: open an SSE response with the adapter, and send each of
  the session's messages with `(send! sse-gen msg)`."
  (:require [charred.api :as json]
            [clojure.string :as str]
            [dashboards.html :as html]
            [starfederation.datastar.clojure.api :as d*]
            [starfederation.datastar.clojure.protocols :as p]))

(def event-type
  "The SSE event type for what Datastar has no event of its own for."
  "datastar-dashboards")

(defn- data-lines
  "Datastar's `key value` data lines for `fields`, one per line of each
  value: Datastar joins lines with the same key back together."
  [fields]
  (vec (for [[k v] fields
             line (let [lines (str/split-lines (str v))] (if (seq lines) lines [""]))]
         (str k " " line))))

(defn- dashboards-event! [sse-gen type & fields]
  (p/send-event! sse-gen event-type (data-lines (cons ["type" type] (partition 2 fields))) {}))

(def ^:private empty-html
  ;; Datastar needs some element content to patch in; an empty comment
  ;; clears an output without showing anything.
  "<!---->")

(defn send!
  "Send a session message (see `dashboards.session`) on `sse-gen`.
  Returns false once the connection is closed."
  [sse-gen {:keys [type] :as msg}]
  (case type
    "connected"
    (and (d*/patch-signals! sse-gen (json/write-json-str {"dsh" {"session" (:session msg)}}))
         (dashboards-event! sse-gen "connected"))

    ;; The stream is up but no session could start on it (the app
    ;; failed to load): the page stops waiting for `connected`, so the
    ;; error notification sent with it stays in view.
    "failed" (dashboards-event! sse-gen "failed")

    "output"
    (and (d*/patch-elements! sse-gen
                             (if (str/blank? (:html msg)) empty-html (:html msg))
                             {d*/selector (html/id-selector (:id msg))
                              d*/patch-mode d*/pm-inner})
         (dashboards-event! sse-gen "rendered" "id" (:id msg) "status" (:status msg)))

    "recalculating" (dashboards-event! sse-gen "recalculating" "id" (:id msg))
    "busy" (dashboards-event! sse-gen "busy")
    "idle" (dashboards-event! sse-gen "idle")
    "signals" (d*/patch-signals! sse-gen (json/write-json-str (:signals msg)))

    "choices"
    (dashboards-event! sse-gen "choices" "id" (:id msg) "signal" (:signal msg)
                       "choices" (json/write-json-str (:choices msg))
                       "selected" (json/write-json-str (:selected msg)))

    "notification"
    (dashboards-event! sse-gen "notify" "level" (:level msg) "duration" (or (:duration msg) 0)
                       "message" (:message msg))))

(defn keep-alive!
  "Send an event the browser ignores, so proxies keep an idle stream
  open. Returns false once the connection is closed."
  [sse-gen]
  (dashboards-event! sse-gen "ping"))

(defn read-signals
  "The signals Datastar sent with a Ring request -- the `datastar`
  query parameter of a GET, or the JSON body of a POST -- as a map with
  string keys. Works whether or not the request has `:query-params`."
  [request]
  (let [request (if (and (#{:get :delete} (:request-method request))
                         (not (:query-params request)))
                  (assoc request :query-params
                         {"datastar" (some (fn [pair]
                                             (let [[k v] (str/split pair #"=" 2)]
                                               (when (= "datastar" k)
                                                 (java.net.URLDecoder/decode (str v) "UTF-8"))))
                                           (some-> (:query-string request) (str/split #"&")))})
                  request)
        raw (d*/get-signals request)
        s (if (string? raw) raw (some-> raw slurp))]
    (if (str/blank? s) {} (json/read-json s))))
