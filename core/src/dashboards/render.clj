(ns dashboards.render
  "Render functions: the server side of an output.

  A server function returns a map from output id to a render spec.
  Each macro here wraps its body in a function, so the body runs
  reactively: whenever something it reads changes, it runs again and
  the browser receives the new HTML.

      (defn server [{:keys [input]}]
        {:count   (render/text (str (tc/row-count @data) \" rows\"))
         :scatter (render/plot (pj/lay-point @data :x :y))
         :rows    (render/table @data)})

  Every macro also accepts an options map as its first form, when the
  body has more forms after it: `(render/plot {:height 300} pose)`."
  (:refer-clojure :exclude [print])
  (:require [clojure.pprint :as pprint]
            [clojure.string :as str]
            [dashboards.html :as html]
            [dashboards.ui :as dui]
            [scicloj.plotje.api :as pj]))

(defn spec?
  "Is `x` a render spec?"
  [x]
  (and (map? x) (contains? x ::type)))

(defn spec
  "Make a render spec from a type, options and a function of no
  arguments. The macros in this namespace are sugar over this."
  [type opts f]
  {::type type ::opts (or opts {}) ::f f})

(defn- split-opts [forms]
  (if (and (map? (first forms)) (next forms))
    [(first forms) (rest forms)]
    [nil forms]))

(defn- spec-form [type forms]
  (let [[opts body] (split-opts forms)]
    `(spec ~type ~opts (fn [] ~@body))))

(defmacro text
  "Render the body's value as plain text. Pairs with `ui/text-output`."
  [& forms]
  (spec-form :text forms))

(defmacro print
  "Render the body's value pretty-printed in a code block, or, if the
  body prints, what it printed. Pairs with `ui/verbatim-output`."
  [& forms]
  (spec-form :print forms))

(defmacro ui
  "Render hiccup (or any value `auto` understands) as HTML. The HTML
  may contain inputs and outputs of its own. Pairs with `ui/ui-output`."
  [& forms]
  (spec-form :ui forms))

(defmacro plot
  "Render a plotje pose as SVG. Unless the pose sets `:width` or
  `:height` itself, the plot is sized to fit its output element, and
  re-rendered when that changes size. Options: `:width`, `:height`.
  Pairs with `ui/plot-output`."
  [& forms]
  (spec-form :plot forms))

(defmacro table
  "Render a dataset (or a sequence of maps) as a table. Options:
  `:max-rows` (default 500), `:sortable?` (default true). Pairs with
  `ui/table-output`."
  [& forms]
  (spec-form :table forms))

(defmacro auto
  "Render whatever the body returns in the most fitting way: plotje
  poses as plots, datasets as tables, hiccup as HTML, Kindly-annotated
  values by their kind, strings as text, anything else pretty-printed."
  [& forms]
  (spec-form :auto forms))

(defmacro download
  "A file to download. The body returns the file's content: a dataset
  or sequence of maps (sent as CSV), a string, or a byte array. It runs
  when the user clicks the matching `ui/download-button`. Options:

  - `:filename` -- a string, or a function of no arguments returning
    one, read reactively at click time (default \"download\").
  - `:content-type` -- defaults from the content.

      {:csv (render/download {:filename \"penguins.csv\"} @filtered)}"
  [& forms]
  (spec-form :download forms))

;; ---------------------------------------------------------------------------
;; Turning values into HTML

(defn- kind-of [x]
  (some-> x meta :kindly/kind))

(defn- pose? [x]
  (try (pj/pose? x) (catch Exception _ false)))

(defn- hiccup? [x]
  (and (vector? x) (keyword? (first x))))

(defn- text-html [x]
  (html/hiccup->html [:span.dsh-text (str x)]))

(defn- print-html [x]
  (html/hiccup->html
   [:pre.dsh-pre (if (string? x) x (str/trimr (with-out-str (pprint/pprint x))))]))

(defn- svg-from-plot
  "Plotje returns SVG hiccup, or, when the plot asks for tooltips or a
  brush, a Kindly fragment with the SVG wrapped in a div next to
  scripts. The dashboard client does tooltips and brushing itself, so
  only the SVG is kept."
  [rendered]
  (letfn [(find-svg [x]
            (cond
              (and (vector? x) (= :svg (first x))) x
              (coll? x) (some find-svg x)
              :else nil))]
    (or (find-svg rendered) rendered)))

(def ^:dynamic *size*
  "The size, `[width height]` in pixels, of the output being rendered,
  as measured in the browser. Nil when not known."
  nil)

(def default-plot-size [640 400])

(defn plot-html
  "Render a plotje pose (or SVG hiccup) to an SVG string, sized from
  `opts` or, failing that, the output's measured size."
  [x {:keys [width height]}]
  (cond
    (hiccup? x) (html/hiccup->html x)
    :else
    (let [pose (pj/pose x)
          own (:opts pose)
          [mw mh] (or *size* default-plot-size)
          w (or width (:width own) mw)
          h (or height (:height own) mh)
          sized (pj/options pose {:width (max 120 (long w)) :height (max 100 (long h))})]
      (html/hiccup->html (svg-from-plot (pj/plot sized))))))

(declare auto-html)

(defn- kind-html [kind x opts]
  (case kind
    (:kind/hiccup :kind/hiccup2) (if (and (vector? x) (= :svg (first x)))
                                   (html/hiccup->html x)
                                   (html/hiccup->html (svg-from-plot x)))
    :kind/html (str/join (if (sequential? x) x [x]))
    :kind/table (html/hiccup->html (html/dataset->hiccup x opts))
    (:kind/pprint :kind/edn) (print-html x)
    :kind/code (html/hiccup->html [:pre.dsh-pre [:code (str/join "\n" (if (sequential? x) x [x]))]])
    (:kind/md :kind/text) (html/hiccup->html [:div.dsh-md (str/join "\n" (if (sequential? x) x [x]))])
    :kind/fragment (str/join (map #(auto-html % opts) x))
    nil))

(defn auto-html
  "Render any value to HTML, choosing how by what it is."
  [x opts]
  (or (when-let [k (kind-of x)]
        (when-not (pose? x) (kind-html k x opts)))
      (cond
        (nil? x) ""
        (pose? x) (plot-html x opts)
        (string? x) (text-html x)
        (number? x) (text-html (html/format-cell x))
        (hiccup? x) (html/hiccup->html (dui/expand x))
        (html/dataset-like? x) (html/hiccup->html (html/dataset->hiccup x opts))
        (and (seq? x) (every? hiccup? x)) (html/hiccup->html (dui/expand x))
        :else (print-html x))))

(defn ->html
  "Render the value a spec's body returned, according to the spec's
  type."
  [type opts x]
  (case type
    :text (if (nil? x) "" (text-html x))
    :print (print-html x)
    :plot (plot-html x opts)
    :table (if (nil? x) "" (html/hiccup->html (html/dataset->hiccup x opts)))
    (:ui :auto) (auto-html x opts)))

(defn run
  "Run a render spec's body and render the result to an HTML string.
  For `print` specs, output printed by the body is captured."
  [{::keys [type opts f]}]
  (if (= type :print)
    (let [result (volatile! nil)
          printed (with-out-str (vreset! result (f)))]
      (->html :print opts (if (and (str/blank? printed) (some? @result))
                            @result
                            printed)))
    (->html type opts (f))))

;; ---------------------------------------------------------------------------
;; Downloads

(defn download-content
  "Run a download spec and return `{:filename :content-type :body}`,
  with `:body` a byte array."
  [{::keys [opts f]}]
  (let [{:keys [filename content-type]} opts
        x (f)
        filename (str (or (if (fn? filename) (filename) filename) "download"))
        [default-type body] (cond
                              (bytes? x) ["application/octet-stream" x]
                              (string? x) ["text/plain; charset=utf-8" (.getBytes ^String x "UTF-8")]
                              (html/dataset-like? x) ["text/csv; charset=utf-8"
                                                      (.getBytes (html/dataset->csv x) "UTF-8")]
                              :else ["application/edn; charset=utf-8"
                                     (.getBytes (with-out-str (pprint/pprint x)) "UTF-8")])]
    {:filename filename
     :content-type (or content-type default-type)
     :body body}))
