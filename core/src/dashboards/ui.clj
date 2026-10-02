(ns dashboards.ui
  "Building blocks for a dashboard's user interface.

  A UI is hiccup, in three interchangeable styles:

  1. **Functions** that return hiccup: `(ui/slider-input :n \"N\" {:max 10})`.
  2. **Component tags**, the same components written as plain data:
     `[:ui/slider-input {:id :n :label \"N\" :max 10}]`. A UI made only
     of tags is a value: it can be stored, generated or sent anywhere.
     Register your own tags with `defcomponent`.
  3. **Plain hiccup with Datastar attributes.** Inputs are Datastar
     signals: `[:input {:type \"range\" :data-bind \"n\"}]` is an input
     the server reads as `(input :n)`. Anything Datastar can do in the
     browser -- `data-show`, `data-text`, `data-class`, ... -- works
     here, without a round trip to the server.
     See https://data-star.dev/reference/attributes.

  Outputs are elements with an id; the server fills the element whose
  id matches each key of the server function's map.

  - **Pages:** `page`, `page-sidebar`, `page-navbar`
  - **Layout:** `layout-sidebar`, `sidebar`, `layout-columns`, `card`,
    `value-box`, `navset-tabs`, `nav-panel`
  - **Inputs:** `text-input`, `textarea-input`, `numeric-input`,
    `slider-input`, `select-input`, `checkbox-input`, `switch-input`,
    `checkbox-group-input`, `radio-buttons`, `date-input`,
    `action-button`
  - **Outputs:** `plot-output`, `table-output`, `text-output`,
    `verbatim-output`, `ui-output`, `download-button`"
  (:require [dashboards.html :as html]))

(defn- opts+children
  "Split an optional leading options map from children. Sidebar and
  nav-panel maps are children, not options."
  [args]
  (let [a (first args)]
    (if (and (map? a) (not (:dashboards/nav-panel a)) (not (:dashboards/sidebar a)))
      [a (rest args)]
      [{} args])))

(defn- classes [& cs]
  (let [s (->> cs (remove nil?) (map name) (interpose " ") (apply str))]
    (when (seq s) s)))

(defn- style [m]
  (let [s (->> m
               (keep (fn [[k v]] (when (some? v) (str (name k) ":" (if (number? v) (str v "px") v)))))
               (interpose ";")
               (apply str))]
    (when (seq s) s)))

(defn- css-size [v]
  (cond (nil? v) nil (number? v) (str v "px") :else (str v)))

(def ^:private id->str html/id->str)
(def ^:private sig html/signal-name)
(def ^:private js html/js-literal)

(defn- signals
  "A `data-signals__ifmissing` attribute defining input `id` with
  `value`, and recording how the server should decode it. `ifmissing`
  keeps a value the user already chose when the element is re-rendered."
  [id value kind]
  {:data-signals__ifmissing
   (js (cond-> {(sig id) value}
         kind (assoc "dsh" {"kinds" {(sig id) kind}})))})

;; ---------------------------------------------------------------------------
;; Pages

(defn page
  "A plain full-width page. Options: `:title` (shown as a heading),
  `:class`."
  [& args]
  (let [[{:keys [title class]} children] (opts+children args)]
    [:div {:class (classes "dsh-page" "dsh-page-fluid" class)}
     (when title [:h1.dsh-page-title title])
     children]))

(defn sidebar
  "A sidebar of inputs, for `page-sidebar` or `layout-sidebar`.
  Options: `:title`, `:width` (default 280), `:position` (`:left` or
  `:right`), `:open?` (default true)."
  [& args]
  (let [[opts children] (opts+children args)]
    {:dashboards/sidebar true :opts opts :children children}))

(defn layout-sidebar
  "A sidebar next to a main area, for use anywhere: inside a page, a
  card, or a tab.

      (layout-sidebar (sidebar (slider-input :n \"N\" {:value 10}))
                      (plot-output :plot))"
  [sidebar & main]
  (let [{:keys [opts children]} sidebar
        {:keys [title width position open?] :or {width 280 position :left open? true}} opts]
    [:div {:class (classes "dsh-layout-sidebar"
                           (when (= position :right) "dsh-sidebar-right")
                           (when-not open? "dsh-sidebar-collapsed"))
           :style (str "--dsh-sidebar-width:" (css-size width))}
     [:aside.dsh-sidebar
      [:button.dsh-sidebar-toggle {:type "button" :aria-label "Toggle sidebar"
                                   :data-dsh-toggle-sidebar true}
       [:span.dsh-chevron]]
      [:div.dsh-sidebar-content
       (when title [:div.dsh-sidebar-title title])
       children]]
     [:div.dsh-main main]]))

(defn page-sidebar
  "A page with a title bar, a sidebar of inputs, and a main area. The
  classic dashboard.

      (page-sidebar {:title \"Penguins\"}
        (sidebar (select-input :species \"Species\" [:adelie :gentoo]))
        (card (plot-output :plot)))

  Options: `:title`, `:subtitle`, `:class`."
  [opts sidebar & main]
  (let [{:keys [title subtitle class]} opts]
    [:div {:class (classes "dsh-page" "dsh-page-sidebar" class)}
     [:header.dsh-header
      [:div.dsh-brand
       (when title [:h1.dsh-title title])
       (when subtitle [:span.dsh-subtitle subtitle])]]
     (apply layout-sidebar sidebar main)]))

(defn nav-panel
  "One panel of a `navset-tabs` or `page-navbar`. `title` labels its
  tab. Options: `:value`, the value reported as the tab input when
  this panel is selected (defaults to the title)."
  [title & args]
  (let [[opts children] (opts+children args)]
    {:dashboards/nav-panel true
     :title title
     :value (or (:value opts) title)
     :children children}))

(defn- nav-items
  "Tabs and panes switched by a signal: the input `id` when given,
  otherwise a browser-only signal (Datastar never sends signals whose
  names start with an underscore)."
  [id panels selected]
  (let [panels (remove nil? (flatten (vector panels)))
        selected (or selected (:value (first panels)))
        signal (if id (sig id) (str "_tab" (Math/abs (hash (mapv :title panels)))))
        is (fn [v] (str "$" signal " == " (js (html/encode-value v))))]
    {:signals (if id
                (signals id (html/encode-value selected) "edn")
                {:data-signals__ifmissing (js {signal (html/encode-value selected)})})
     :tabs (for [{:keys [title value]} panels]
             [:button {:type "button"
                       :role "tab"
                       :class (classes "dsh-tab" (when (= value selected) "dsh-active"))
                       :data-on:click (str "$" signal " = " (js (html/encode-value value)))
                       :data-class:dsh-active (is value)}
              title])
     :panes (for [{:keys [value children]} panels]
              [:div {:class (classes "dsh-tab-pane" (when (= value selected) "dsh-active"))
                     :role "tabpanel"
                     :data-class:dsh-active (is value)}
               children])}))

(defn navset-tabs
  "Tabs, each holding a `nav-panel`. With an `:id` option, the selected
  panel's value is an input. Options: `:id`, `:selected`.

      (navset-tabs {:id :tab}
        (nav-panel \"Plot\" (plot-output :plot))
        (nav-panel \"Data\" (table-output :data)))"
  [& args]
  (let [[{:keys [id selected class]} panels] (opts+children args)
        {:keys [signals tabs panes]} (nav-items id panels selected)]
    [:div (merge {:class (classes "dsh-navset" class)} signals)
     [:div.dsh-tabs {:role "tablist"} tabs]
     [:div.dsh-tab-content panes]]))

(defn page-navbar
  "A page with a navigation bar across the top; each `nav-panel` is a
  page of its own. With an `:id` option, the selected panel's value is
  an input.

  Options: `:title`, `:id`, `:selected`, `:class`."
  [& args]
  (let [[{:keys [title id selected class]} panels] (opts+children args)
        {:keys [signals tabs panes]} (nav-items id panels selected)]
    [:div (merge {:class (classes "dsh-page" "dsh-page-navbar" "dsh-navset" class)} signals)
     [:header.dsh-header
      [:div.dsh-brand (when title [:h1.dsh-title title])]
      [:nav.dsh-tabs.dsh-navbar-tabs {:role "tablist"} tabs]]
     [:div.dsh-tab-content.dsh-navbar-content panes]]))

;; ---------------------------------------------------------------------------
;; Layout

(defn layout-columns
  "Lay children out in columns that wrap on narrow screens.

  Options:

  - `:widths` -- column widths on a 12-column grid, one per child,
    e.g. `[4 8]`. Without it, columns share the space equally.
  - `:min-width` -- with no `:widths`, the narrowest a column may get
    before wrapping (default 280px).
  - `:gap` -- space between columns (default 16px)."
  [& args]
  (let [[{:keys [widths min-width gap class]} children] (opts+children args)
        children (remove nil? children)]
    (if widths
      [:div {:class (classes "dsh-columns" "dsh-columns-12" class)
             :style (style {:gap (css-size gap)})}
       (map (fn [w child] [:div {:style (str "--dsh-span:" w)} child])
            (cycle widths) children)]
      [:div {:class (classes "dsh-columns" "dsh-columns-auto" class)
             :style (style {:gap (css-size gap)
                            :--dsh-min-col (css-size (or min-width 280))})}
       (for [child children] [:div child])])))

(defn card
  "A card: a bordered box with an optional header and footer.

  Options: `:title`, `:footer`, `:full-screen?` (adds a button to
  expand the card to the whole window), `:height`, `:class`."
  [& args]
  (let [[{:keys [title footer full-screen? height class]} children] (opts+children args)]
    [:section {:class (classes "dsh-card" class)
               :style (style {:height (css-size height)})}
     (when (or title full-screen?)
       [:header.dsh-card-header
        [:span.dsh-card-title title]
        (when full-screen?
          [:button.dsh-icon-btn {:type "button" :title "Expand"
                                 :aria-label "Expand card"
                                 :data-dsh-fullscreen true}
           [:span.dsh-expand-icon]])])
     [:div.dsh-card-body children]
     (when footer [:footer.dsh-card-footer footer])]))

(defn value-box
  "A box that shows one number prominently, for a dashboard's headline
  figures. `:value` is usually a `text-output`.

      (value-box {:title \"Penguins\" :value (text-output :n)
                  :subtitle \"in the current selection\" :theme :primary})

  Options: `:title`, `:value`, `:subtitle`, `:icon` (any hiccup or
  string), `:theme` (`:primary`, `:success`, `:warning`, `:danger`,
  `:neutral`)."
  [{:keys [title value subtitle icon theme class]}]
  [:section {:class (classes "dsh-value-box" (str "dsh-theme-" (name (or theme :neutral))) class)}
   (when icon [:div.dsh-value-box-icon icon])
   [:div.dsh-value-box-body
    [:div.dsh-value-box-title title]
    [:div.dsh-value-box-value value]
    (when subtitle [:div.dsh-value-box-subtitle subtitle])]])

(defn help-text
  "Small, muted explanatory text."
  [& children]
  [:p.dsh-help children])

(defn markdown-ish
  "A block of prose with comfortable typography for hiccup content."
  [& children]
  [:div.dsh-prose children])

;; ---------------------------------------------------------------------------
;; Inputs
;;
;; Every input binds a Datastar signal named after its id (see
;; `dashboards.html/signal-name`) and declares the signal's starting
;; value -- typed, so a slider's value is a number -- on its wrapper.
;; Values that don't survive JSON as themselves (keywords, dates, ...)
;; are tagged with a "kind" the server uses to decode them.

(defn- input-wrapper [type id label signal-attrs & body]
  [:div (merge {:class (classes "dsh-input" (str "dsh-input-" type))} signal-attrs)
   (when label [:label.dsh-label {:for (id->str id)} label])
   body])

(defn text-input
  "A single-line text input. Its value is a string.
  Options: `:value`, `:placeholder`."
  ([id label] (text-input id label {}))
  ([id label {:keys [value placeholder]}]
   (input-wrapper "text" id label (signals id (str value) nil)
                  [:input.dsh-control {:type "text" :id (id->str id) :data-bind (sig id)
                                       :value (str value) :placeholder placeholder}])))

(defn textarea-input
  "A multi-line text input. Its value is a string.
  Options: `:value`, `:placeholder`, `:rows`."
  ([id label] (textarea-input id label {}))
  ([id label {:keys [value placeholder rows] :or {rows 4}}]
   (input-wrapper "textarea" id label (signals id (str value) nil)
                  [:textarea.dsh-control {:id (id->str id) :data-bind (sig id)
                                          :placeholder placeholder :rows rows}
                   (str value)])))

(defn numeric-input
  "A number input. Its value is a number, or nil when empty.
  Options: `:value`, `:min`, `:max`, `:step`."
  ([id label] (numeric-input id label {}))
  ([id label {:keys [value min max step]}]
   ;; Kept as a string in the browser so that an empty box is "" (nil)
   ;; rather than 0.
   (input-wrapper "number" id label (signals id (if (some? value) (str value) "") "number")
                  [:input.dsh-control {:type "number" :id (id->str id) :data-bind (sig id)
                                       :value value :min min :max max
                                       :step (or step "any")}])))

(defn slider-input
  "A slider. Its value is a number.
  Options: `:min` (0), `:max` (100), `:value` (`:min`), `:step` (1),
  `:prefix` and `:suffix` for the displayed value."
  [id label {:keys [min max value step prefix suffix] :or {min 0 max 100 step 1}}]
  (let [value (or value min)
        s (str "$" (sig id))
        fill (str "'--dsh-fill:' + ((" s " - " min ") / " (- max min) " * 100) + '%'")]
    [:div (merge {:class "dsh-input dsh-input-slider"} (signals id value nil))
     [:label.dsh-label {:for (id->str id)}
      [:span label]
      [:output.dsh-slider-value {:data-text (str (js (str prefix)) " + " s " + " (js (str suffix)))}
       (str prefix value suffix)]]
     [:input.dsh-range {:type "range" :id (id->str id) :data-bind (sig id)
                        :min min :max max :step step :value value
                        :style (str "--dsh-fill:" (if (= max min) 0 (* 100.0 (/ (- value min) (- max min)))) "%")
                        :data-attr:style fill}]
     [:div.dsh-range-limits [:span (str prefix min suffix)] [:span (str prefix max suffix)]]]))

(defn- option-tags [choices selected?]
  (for [{:keys [value label]} choices]
    [:option {:value (html/encode-value value) :selected (boolean (selected? value))} label]))

(defn select-input
  "A drop-down list. Its value is the selected choice -- a keyword,
  number or string, just as given -- or, with `:multiple? true`, a
  vector of them.

  `choices` is a sequence of values (`[:bill-length :bill-depth]`), a
  sequence of `{:value v :label \"...\"}` maps, or a map from value
  to label. Options: `:selected`, `:multiple?`, `:size`."
  ([id label choices] (select-input id label choices {}))
  ([id label choices {:keys [selected multiple? size]}]
   (let [choices (html/normalize-choices choices)
         selected (cond multiple? (vec (or selected []))
                        (some? selected) selected
                        :else (:value (first choices)))
         chosen? (if multiple? (set selected) #{selected})
         initial (if multiple?
                   (mapv html/encode-value selected)
                   (html/encode-value selected))]
     (input-wrapper (if multiple? "select-multiple" "select") id label (signals id initial "edn")
                    [:select.dsh-control
                     {:id (id->str id) :data-bind (sig id)
                      :multiple multiple?
                      :size (when multiple? (or size (min 6 (count choices))))}
                     (option-tags choices chosen?)]))))

(defn checkbox-input
  "A single checkbox. Its value is true or false. Options: `:value`."
  ([id label] (checkbox-input id label {}))
  ([id label {:keys [value]}]
   [:div (merge {:class "dsh-input dsh-input-checkbox"} (signals id (boolean value) nil))
    [:label.dsh-check
     [:input {:type "checkbox" :id (id->str id) :data-bind (sig id) :checked (boolean value)}]
     [:span label]]]))

(defn switch-input
  "An on/off switch. Its value is true or false. Options: `:value`."
  ([id label] (switch-input id label {}))
  ([id label {:keys [value]}]
   [:div (merge {:class "dsh-input dsh-input-switch"} (signals id (boolean value) nil))
    [:label.dsh-check.dsh-switch
     [:input {:type "checkbox" :role "switch" :id (id->str id) :data-bind (sig id)
              :checked (boolean value)}]
     [:span.dsh-switch-track]
     [:span label]]]))

(defn- choice-items [input-type id choices chosen?]
  [:div.dsh-choices {:id (str (id->str id) "-choices")}
   (for [{:keys [value label]} choices]
     [:label.dsh-check
      [:input {:type input-type
               :name (id->str id)
               :data-bind (sig id)
               :value (html/encode-value value)
               :checked (boolean (chosen? value))}]
      [:span label]])])

(defn checkbox-group-input
  "A group of checkboxes. Its value is a vector of the checked choices.
  See `select-input` for `choices`. Options: `:selected` (a collection),
  `:inline?`."
  ([id label choices] (checkbox-group-input id label choices {}))
  ([id label choices {:keys [selected inline?]}]
   (let [choices (html/normalize-choices choices)
         chosen? (set selected)
         ;; Datastar keeps one slot per checkbox: its value when
         ;; checked, "" when not.
         slots (mapv #(if (chosen? (:value %)) (html/encode-value (:value %)) "") choices)]
     [:fieldset (merge {:class (classes "dsh-input" "dsh-input-checkbox-group" (when inline? "dsh-inline"))
                        :id (id->str id)}
                       (signals id slots "edn"))
      (when label [:legend.dsh-label label])
      (choice-items "checkbox" id choices chosen?)])))

(defn radio-buttons
  "A group of radio buttons. Its value is the chosen choice. See
  `select-input` for `choices`. Options: `:selected` (defaults to the
  first choice), `:inline?`."
  ([id label choices] (radio-buttons id label choices {}))
  ([id label choices {:keys [selected inline?]}]
   (let [choices (html/normalize-choices choices)
         selected (if (some? selected) selected (:value (first choices)))]
     [:fieldset (merge {:class (classes "dsh-input" "dsh-input-radio" (when inline? "dsh-inline"))
                        :id (id->str id)}
                       (signals id (html/encode-value selected) "edn"))
      (when label [:legend.dsh-label label])
      (choice-items "radio" id choices #{selected})])))

(defn date-input
  "A date picker. Its value is a `java.time.LocalDate`, or nil.
  Options: `:value` (a LocalDate or \"yyyy-mm-dd\"), `:min`, `:max`."
  ([id label] (date-input id label {}))
  ([id label {:keys [value min max]}]
   (input-wrapper "date" id label (signals id (str value) "date")
                  [:input.dsh-control {:type "date" :id (id->str id) :data-bind (sig id)
                                       :value (some-> value str) :min (some-> min str)
                                       :max (some-> max str)}])))

(defn action-button
  "A button. Its value counts the clicks: nil before the first, then 1,
  2, ... Use it with `observe-event` or `event-reactive`.
  Options: `:class`, `:theme` (`:primary` default, `:secondary`)."
  ([id label] (action-button id label {}))
  ([id label {:keys [class theme] :or {theme :primary}}]
   (let [s (str "$" (sig id))]
     [:button (merge {:type "button"
                      :class (classes "dsh-btn" (str "dsh-btn-" (name theme)) class)
                      :id (id->str id)
                      :data-on:click (str s " = " s " + 1")}
                     (signals id 0 "action"))
      label])))

;; ---------------------------------------------------------------------------
;; Outputs

(defn- output [tag type id attrs]
  [tag (merge {:class (classes "dsh-output" (str "dsh-output-" type) (:class attrs))
               :id (id->str id)
               :data-output true
               :aria-live "polite"}
              (dissoc attrs :class))])

(defn text-output
  "Inline text, filled by `render/text`."
  [id & [{:keys [class]}]]
  (output :span "text" id {:class class}))

(defn verbatim-output
  "Preformatted text, filled by `render/print`."
  [id & [{:keys [class]}]]
  (output :div "verbatim" id {:class class}))

(defn plot-output
  "A plot, filled by `render/plot`. The plot is drawn to fit the
  element's size.

  Options:

  - `:height` -- CSS height (default 400px); `:width` (default 100%).
  - `:brush` -- an input id. Dragging a rectangle over the plot selects
    the points under it; the input's value is a vector of their row
    indices into the plotted data (empty once cleared, nil before any
    brushing). Pass `true`
    to use `<output-id>-brush`.
  - `:click` -- an input id that receives the row index of a clicked
    point. Pass `true` to use `<output-id>-click`."
  ([id] (plot-output id {}))
  ([id {:keys [height width brush click class]}]
   (let [signal-for (fn [suffix v]
                      (cond (true? v) (sig (str (name id) "-" suffix))
                            v (sig v)))]
     (output :div "plot" id
             {:class class
              :style (style {:height (css-size (or height 400)) :width (css-size width)})
              :data-brush (signal-for "brush" brush)
              :data-click (signal-for "click" click)}))))

(defn table-output
  "A table, filled by `render/table`. Options: `:max-height` (the
  table scrolls beyond it)."
  ([id] (table-output id {}))
  ([id {:keys [max-height class]}]
   (output :div "table" id {:class class :style (style {:max-height (css-size max-height)})})))

(defn ui-output
  "Arbitrary HTML, filled by `render/ui` or `render/auto`. What the
  server sends may itself contain inputs and outputs."
  ([id] (ui-output id {}))
  ([id {:keys [class]}]
   (output :div "ui" id {:class class})))

(defn download-button
  "A button that downloads the file produced by `render/download`."
  ([id label] (download-button id label {}))
  ([id label {:keys [class theme] :or {theme :secondary}}]
   [:a {:class (classes "dsh-btn" (str "dsh-btn-" (name theme)) "dsh-download" class)
        :id (id->str id)
        :href "#"
        :download ""
        :data-attr:href (str "'_dashboards/download/' + $dsh.session + '/' + "
                             (js (id->str id)))
        :data-class:dsh-disabled "!$dsh.session"}
    [:span.dsh-download-icon] label]))

;; ---------------------------------------------------------------------------
;; Component tags: the components above, written as data

(defmulti component
  "Expand a component tag into hiccup. Dispatches on the tag; receives
  the attribute map (possibly empty) and the children, already
  expanded. Add your own with `defcomponent`."
  (fn [tag _attrs _children] tag))

(defmacro defcomponent
  "Define a hiccup component tag.

      (defcomponent :my/kpi [{:keys [label id]} children]
        [:div.kpi [:span label] (text-output id)])

      [:my/kpi {:label \"Revenue\" :id :revenue}]"
  [tag [attrs-binding children-binding] & body]
  `(defmethod component ~tag [_# ~attrs-binding ~children-binding] ~@body))

(defn component-tag?
  "Is `x` a keyword with a registered component?"
  [x]
  (and (keyword? x) (contains? (methods component) x)))

(defn expand
  "Expand every component tag in a hiccup tree, innermost first, so
  that what a component receives as children is already plain hiccup.
  Hiccup inside attribute values (such as a value box's `:value`) is
  expanded too. Everything else is left as it is."
  [hiccup]
  (letfn [(exp [x]
            (cond
              (and (vector? x) (component-tag? (first x)))
              (let [[tag & more] x
                    [attrs children] (if (map? (first more))
                                       [(first more) (rest more)]
                                       [{} more])
                    attrs (update-vals attrs exp)
                    children (doall (map exp children))]
                (exp (component tag attrs children)))

              (vector? x) (into [] (map exp) x)
              (seq? x) (doall (map exp x))
              :else x))]
    (exp hiccup)))

(defn- input-component [f & arg-keys]
  (fn [attrs children]
    (let [args (map #(get attrs %) arg-keys)
          opts (apply dissoc attrs arg-keys)
          label-from-children (when (and (contains? (set arg-keys) :label)
                                         (nil? (:label attrs))
                                         (seq children))
                                children)
          args (if label-from-children
                 (map #(if (= %1 :label) label-from-children %2) arg-keys args)
                 args)]
      (apply f (concat args [opts])))))

(doseq [[tag f] {:ui/text-input (input-component text-input :id :label)
                 :ui/textarea-input (input-component textarea-input :id :label)
                 :ui/numeric-input (input-component numeric-input :id :label)
                 :ui/slider-input (input-component slider-input :id :label)
                 :ui/select-input (input-component select-input :id :label :choices)
                 :ui/checkbox-input (input-component checkbox-input :id :label)
                 :ui/switch-input (input-component switch-input :id :label)
                 :ui/checkbox-group-input (input-component checkbox-group-input :id :label :choices)
                 :ui/radio-buttons (input-component radio-buttons :id :label :choices)
                 :ui/date-input (input-component date-input :id :label)
                 :ui/action-button (input-component action-button :id :label)
                 :ui/download-button (input-component download-button :id :label)
                 :ui/text-output (fn [{:keys [id] :as a} _] (text-output id (dissoc a :id)))
                 :ui/verbatim-output (fn [{:keys [id] :as a} _] (verbatim-output id (dissoc a :id)))
                 :ui/plot-output (fn [{:keys [id] :as a} _] (plot-output id (dissoc a :id)))
                 :ui/table-output (fn [{:keys [id] :as a} _] (table-output id (dissoc a :id)))
                 :ui/ui-output (fn [{:keys [id] :as a} _] (ui-output id (dissoc a :id)))
                 :ui/page (fn [a c] (apply page a c))
                 :ui/page-sidebar (fn [a [sb & main]] (apply page-sidebar a sb main))
                 :ui/page-navbar (fn [a c] (apply page-navbar a c))
                 :ui/sidebar (fn [a c] (apply sidebar a c))
                 :ui/layout-sidebar (fn [_ [sb & main]] (apply layout-sidebar sb main))
                 :ui/nav-panel (fn [{:keys [title] :as a} c] (apply nav-panel title (dissoc a :title) c))
                 :ui/navset-tabs (fn [a c] (apply navset-tabs a c))
                 :ui/layout-columns (fn [a c] (apply layout-columns a c))
                 :ui/card (fn [a c] (apply card a c))
                 :ui/value-box (fn [a _] (value-box a))
                 :ui/help-text (fn [_ c] (apply help-text c))}]
  (defmethod component tag [_ attrs children] (f attrs children)))

(comment
  ;; Component tags and functions produce the same hiccup:
  (= (expand [:ui/slider-input {:id :n :label "N" :max 10}])
     (slider-input :n "N" {:max 10})))
