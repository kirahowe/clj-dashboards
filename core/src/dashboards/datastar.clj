(ns dashboards.datastar
  "Session messages as Datastar server-sent events.

  The browser runs Datastar (https://data-star.dev): one long-lived
  SSE stream per tab carries everything the server sends, and the
  page posts its signals back whenever they change. Outputs arrive as
  standard `datastar-patch-elements` events, which morph the new HTML
  into the output's element; input values the server sets arrive as
  `datastar-patch-signals`. What Datastar has no event for -- busy and
  recalculating states, notifications, replacing an input's choices --
  travels as a `datastar-dashboards` event, handled by the small
  companion script `dashboards.js`.

  Any transport that can write text to a response can serve a
  session: write `(event msg)` for each message the session sends."
  (:require [charred.api :as json]
            [clojure.string :as str]
            [dashboards.html :as html]))

(defn- data-lines
  "SSE data lines for `key value`, one line of `value` per data line:
  Datastar joins lines with the same key back together."
  [k v]
  (let [lines (str/split-lines (str v))]
    (if (empty? lines)
      [(str "data: " k " ")]
      (map #(str "data: " k " " %) lines))))

(defn sse
  "Format one server-sent event. `fields` is a sequence of
  `[key value]` pairs, written as Datastar's `data: key value` lines."
  [event-name fields]
  (str "event: " event-name "\n"
       (str/join "\n" (mapcat (fn [[k v]] (data-lines k v)) fields))
       "\n\n"))

(defn patch-elements
  "A `datastar-patch-elements` event. Options: `:selector`, `:mode`
  (`\"outer\"` by default in Datastar; `\"inner\"`, `\"append\"`, ...)."
  [html {:keys [selector mode]}]
  (sse "datastar-patch-elements"
       (cond-> []
         selector (conj ["selector" selector])
         mode (conj ["mode" mode])
         true (conj ["elements" html]))))

(defn patch-signals
  "A `datastar-patch-signals` event merging `signals` (a map) into the
  page's signals."
  [signals]
  (sse "datastar-patch-signals" [["signals" (json/write-json-str signals)]]))

(defn- dashboards-event [type & fields]
  (sse "datastar-dashboards" (cons ["type" type] (partition 2 fields))))

(defn event
  "The SSE text for a session message (see `dashboards.session`)."
  ^String [{:keys [type] :as msg}]
  (case type
    "connected"
    (str (patch-signals {"dsh" {"session" (:session msg)}})
         (dashboards-event "connected"))

    "output"
    (str (patch-elements (:html msg) {:selector (html/id-selector (:id msg)) :mode "inner"})
         (dashboards-event "rendered" "id" (:id msg) "status" (:status msg)))

    "recalculating" (dashboards-event "recalculating" "id" (:id msg))
    "busy" (dashboards-event "busy")
    "idle" (dashboards-event "idle")
    "signals" (patch-signals (:signals msg))

    "choices"
    (dashboards-event "choices" "id" (:id msg) "signal" (:signal msg)
                      "choices" (json/write-json-str (:choices msg))
                      "selected" (json/write-json-str (:selected msg)))

    "notification"
    (dashboards-event "notify" "level" (:level msg) "duration" (or (:duration msg) 0)
                      "message" (:message msg))))

(def keep-alive
  "An SSE comment, sent periodically so proxies don't close an idle stream."
  ": keep-alive\n\n")

(defn read-signals
  "Parse the signals Datastar sends: the `datastar` query parameter of
  a GET, or the JSON body of a POST. String keys are kept."
  [s]
  (if (str/blank? s) {} (json/read-json s)))
