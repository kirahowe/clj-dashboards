(ns dashboards.html
  "Turning Clojure values into HTML: hiccup, Kindly-annotated values,
  datasets as tables, and the EDN encoding used for input choices."
  (:require [charred.api :as charred]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [hiccup2.core :as h]
            [hiccup.util :as hu]
            [tablecloth.api :as tc]))

(defn hiccup->html
  "Render hiccup (or a seq of hiccup forms) to an HTML string."
  ^String [hiccup]
  (str (h/html {:mode :html} hiccup)))

(defn raw
  "Mark a string as HTML that should be inserted without escaping."
  [s]
  (h/raw s))

(defn escape ^String [s] (str (hu/escape-html (str s))))

;; ---------------------------------------------------------------------------
;; Ids and choices

(defn id->str
  "An output or input id as it appears in the DOM. Keeps a keyword's
  namespace: `:a/b` -> \"a/b\"."
  ^String [id]
  (cond (keyword? id) (subs (str id) 1)
        (symbol? id) (str id)
        :else (str id)))

(defn str->id
  "The inverse of `id->str`."
  [s]
  (keyword s))

(defn signal-name
  "The Datastar signal that holds input `id`. Datastar camel-cases
  signal names, so `:scatter-brush` is the signal `scatterBrush`, the
  same signal `data-bind:scatter-brush` creates."
  ^String [id]
  (let [[head & more] (str/split (name id) #"-")]
    (apply str head (map str/capitalize more))))

(defn js-literal
  "A Clojure value as a JavaScript literal (JSON), for Datastar
  attribute expressions."
  ^String [v]
  (charred/write-json-str v))

(defn id-selector
  "A CSS selector for the element whose id is `id`."
  ^String [id]
  (str "#" (str/replace (id->str id) #"[^a-zA-Z0-9_-]" #(str "\\" %))))

(defn encode-value
  "Encode a choice value for the browser. Values travel as EDN so a
  choice can be a keyword, number or string and comes back as the
  same thing."
  ^String [v]
  (pr-str v))

(defn decode-value
  "Decode a value encoded with `encode-value`. Strings that aren't a
  single EDN value (or that read as a bare symbol, as hand-written
  option values like \"red\" do) come back as they are."
  [s]
  (if (string? s)
    (try
      (let [r (java.io.PushbackReader. (java.io.StringReader. s))
            v (edn/read {:eof ::eof} r)
            rest (edn/read {:eof ::eof} r)]
        (if (or (= v ::eof) (not= rest ::eof) (symbol? v)) s v))
      (catch Exception _ s))
    s))

(defn choice-label [v]
  (cond (keyword? v) (name v)
        (nil? v) ""
        :else (str v)))

(defn normalize-choices
  "Normalise choices for selects, radio buttons and checkbox groups to
  a vector of `{:value v :label \"...\"}`. Accepts:

  - a sequence of values: `[:adelie :gentoo]`, `[\"a\" \"b\"]`, `[1 2]`
  - a sequence of maps with `:value` and `:label`
  - a map of value -> label: `{:adelie \"Adélie\" :gentoo \"Gentoo\"}`"
  [choices]
  (cond
    (map? choices)
    (mapv (fn [[v l]] {:value v :label (str l)}) choices)

    :else
    (mapv (fn [c]
            (if (and (map? c) (contains? c :value))
              (update c :label #(or % (choice-label (:value c))))
              {:value c :label (choice-label c)}))
          choices)))

;; ---------------------------------------------------------------------------
;; Tables

(defn dataset-like?
  "Is `x` a dataset, or a sequence of maps that can become one?"
  [x]
  (or (tc/dataset? x)
      (and (sequential? x) (seq x) (every? map? (take 10 x)))))

(defn ->dataset [x]
  (if (tc/dataset? x) x (tc/dataset x)))

(defn format-cell
  "How a single cell value is shown in a table."
  [v]
  (cond
    (nil? v) ""
    (float? v) (let [d (double v)]
                 (cond (Double/isNaN d) ""
                       (== d (Math/rint d)) (if (< (Math/abs d) 1e15) (str (long d)) (str d))
                       (< (Math/abs d) 1e-3) (format "%.3g" d)
                       :else (-> (format "%.4f" d)
                                 (str/replace #"0+$" "")
                                 (str/replace #"\.$" ""))))
    (keyword? v) (name v)
    :else (str v)))

(defn- numeric-column? [ds col]
  (let [c (ds col)]
    (boolean (some-> c meta :datatype #{:float64 :float32 :int64 :int32 :int16 :int8
                                       :uint8 :uint16 :uint32 :uint64}))))

(defn dataset->hiccup
  "A dataset (or a sequence of maps) as an HTML table. Shows at most
  `:max-rows` rows (default 500) and says how many were left out."
  ([data] (dataset->hiccup data {}))
  ([data {:keys [max-rows sortable? class] :or {max-rows 500 sortable? true}}]
   (let [ds (->dataset data)
         n (tc/row-count ds)
         cols (vec (tc/column-names ds))
         numeric (set (filter #(numeric-column? ds %) cols))
         shown (if (> n max-rows) (tc/head ds max-rows) ds)
         rows (tc/rows shown :as-seqs)]
     [:div.dsh-table-wrap
      [:table {:class (str "dsh-table" (when class (str " " class)))
               :data-sortable (when sortable? "true")}
       [:thead
        [:tr (for [c cols]
               [:th {:class (when (numeric c) "dsh-num")} (choice-label c)])]]
       [:tbody
        (for [row rows]
          [:tr (map (fn [c v] [:td {:class (when (numeric c) "dsh-num")} (format-cell v)])
                    cols row)])]]
      (when (> n max-rows)
        [:div.dsh-table-note (format "Showing %,d of %,d rows" max-rows n)])])))

(defn dataset->csv
  "A dataset (or a sequence of maps) as a CSV string."
  ^String [data]
  (let [ds (->dataset data)
        w (java.io.StringWriter.)
        cell #(cond (nil? %) "" (keyword? %) (name %) :else (str %))]
    (charred/write-csv w
                       (cons (map choice-label (tc/column-names ds))
                             (map #(map cell %) (tc/rows ds :as-seqs)))
                       :close-writer? true)
    (str w)))
