(ns dashboards.ui
  "Building blocks for a dashboard's user interface.

  Everything here returns plain hiccup, so it mixes freely with your
  own: `[:p \"Some text \" [:strong \"in bold\"]]` is as much a part of
  the UI as a `card` or a `slider-input`.

  - **Pages** lay out the whole window: `page`, `page-sidebar`,
    `page-navbar`.
  - **Layout** arranges things within it: `layout-sidebar`, `sidebar`,
    `layout-columns`, `card`, `value-box`, `navset-tabs`, `nav-panel`.
  - **Inputs** send values to the server: `text-input`, `slider-input`,
    `select-input`, `action-button`, and so on. Read them in the
    server function with `(input :id)`.
  - **Outputs** are placeholders the server fills: `plot-output`,
    `table-output`, `text-output`, `ui-output`, ... Each one is filled
    by the render spec with the same id in the server function's map."
  (:require [dashboards.html :as html]))

(defn- opts+children
  "Split optional leading options map from children. Hiccup vectors and
  other non-map values are children."
  [args]
  (if (and (map? (first args)) (not (:dashboards/nav-panel (first args))))
    [(first args) (rest args)]
    [{} args]))

(defn- classes [& cs]
  (let [s (->> cs (remove nil?) (map name) (interpose " ") (apply str))]
    (when (seq s) s)))

(defn- style [m]
  (when (seq m)
    (->> m
         (keep (fn [[k v]] (when (some? v) (str (name k) ":" (if (number? v) (str v "px") v)))))
         (interpose ";")
         (apply str))))

(defn- css-size [v]
  (cond (nil? v) nil (number? v) (str v "px") :else (str v)))

(def ^:private id->str html/id->str)

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
                                   :data-dsh-toggle-sidebar "true"}
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

(defn- nav-items [panels selected]
  (let [panels (remove nil? (flatten (vector panels)))
        selected (or selected (:value (first panels)))]
    {:selected selected
     :panels panels
     :tabs (for [{:keys [title value]} panels]
             [:button {:type "button"
                       :role "tab"
                       :class (classes "dsh-tab" (when (= value selected) "dsh-active"))
                       :aria-selected (str (= value selected))
                       :data-dsh-tab (html/encode-value value)}
              title])
     :panes (for [{:keys [value children]} panels]
              [:div {:class (classes "dsh-tab-pane" (when (= value selected) "dsh-active"))
                     :role "tabpanel"
                     :data-dsh-pane (html/encode-value value)}
               children])}))

(defn navset-tabs
  "Tabs, each holding a `nav-panel`. With an `:id` option, the selected
  panel's value is an input. Options: `:id`, `:selected`.

      (navset-tabs {:id :tab}
        (nav-panel \"Plot\" (plot-output :plot))
        (nav-panel \"Data\" (table-output :data)))"
  [& args]
  (let [[{:keys [id selected class]} panels] (opts+children args)
        {:keys [tabs panes]} (nav-items panels selected)]
    [:div {:class (classes "dsh-navset" class)
           :data-input-id (some-> id id->str)
           :data-input-type (when id "tabs")}
     [:div.dsh-tabs {:role "tablist"} tabs]
     [:div.dsh-tab-content panes]]))

(defn page-navbar
  "A page with a navigation bar across the top; each `nav-panel` is a
  page of its own. With an `:id` option, the selected panel's value is
  an input.

  Options: `:title`, `:id`, `:selected`, `:class`."
  [& args]
  (let [[{:keys [title id selected class]} panels] (opts+children args)
        {:keys [tabs panes]} (nav-items panels selected)]
    [:div {:class (classes "dsh-page" "dsh-page-navbar" "dsh-navset" class)
           :data-input-id (some-> id id->str)
           :data-input-type (when id "tabs")}
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
                                 :data-dsh-fullscreen "true"}
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

(defn- input-wrapper [type id label & body]
  [:div {:class (classes "dsh-input" (str "dsh-input-" type))}
   (when label [:label.dsh-label {:for (id->str id)} label])
   body])

(defn text-input
  "A single-line text input. Its value is a string.
  Options: `:value`, `:placeholder`."
  ([id label] (text-input id label {}))
  ([id label {:keys [value placeholder]}]
   (input-wrapper "text" id label
                  [:input.dsh-control {:type "text" :id (id->str id)
                                       :data-input-id (id->str id) :data-input-type "text"
                                       :value (or value "") :placeholder placeholder}])))

(defn textarea-input
  "A multi-line text input. Its value is a string.
  Options: `:value`, `:placeholder`, `:rows`."
  ([id label] (textarea-input id label {}))
  ([id label {:keys [value placeholder rows] :or {rows 4}}]
   (input-wrapper "textarea" id label
                  [:textarea.dsh-control {:id (id->str id)
                                          :data-input-id (id->str id) :data-input-type "text"
                                          :placeholder placeholder :rows rows}
                   (or value "")])))

(defn numeric-input
  "A number input. Its value is a number, or nil when empty.
  Options: `:value`, `:min`, `:max`, `:step`."
  ([id label] (numeric-input id label {}))
  ([id label {:keys [value min max step]}]
   (input-wrapper "number" id label
                  [:input.dsh-control {:type "number" :id (id->str id)
                                       :data-input-id (id->str id) :data-input-type "number"
                                       :value value :min min :max max :step (or step "any")}])))

(defn slider-input
  "A slider. Its value is a number.
  Options: `:min` (0), `:max` (100), `:value` (`:min`), `:step` (1),
  `:prefix` and `:suffix` for the displayed value."
  [id label {:keys [min max value step prefix suffix] :or {min 0 max 100 step 1}}]
  (let [value (or value min)]
    [:div.dsh-input.dsh-input-slider
     [:label.dsh-label {:for (id->str id)}
      [:span label]
      [:output.dsh-slider-value {:data-prefix prefix :data-suffix suffix}
       (str prefix value suffix)]]
     [:input.dsh-range {:type "range" :id (id->str id)
                        :data-input-id (id->str id) :data-input-type "slider"
                        :min min :max max :step step :value value}]
     [:div.dsh-range-limits [:span (str prefix min suffix)] [:span (str prefix max suffix)]]]))

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
         selected (cond multiple? (set (or selected []))
                        (some? selected) #{selected}
                        :else #{(:value (first choices))})]
     (input-wrapper (if multiple? "select-multiple" "select") id label
                    [:select.dsh-control
                     {:id (id->str id)
                      :data-input-id (id->str id) :data-input-type "select"
                      :multiple multiple?
                      :size (when multiple? (or size (min 6 (count choices))))}
                     (for [{:keys [value label]} choices]
                       [:option {:value (html/encode-value value)
                                 :selected (contains? selected value)}
                        label])]))))

(defn checkbox-input
  "A single checkbox. Its value is true or false. Options: `:value`."
  ([id label] (checkbox-input id label {}))
  ([id label {:keys [value]}]
   [:div.dsh-input.dsh-input-checkbox
    [:label.dsh-check
     [:input {:type "checkbox" :id (id->str id)
              :data-input-id (id->str id) :data-input-type "checkbox"
              :checked (boolean value)}]
     [:span label]]]))

(defn switch-input
  "An on/off switch. Its value is true or false. Options: `:value`."
  ([id label] (switch-input id label {}))
  ([id label {:keys [value]}]
   [:div.dsh-input.dsh-input-switch
    [:label.dsh-check.dsh-switch
     [:input {:type "checkbox" :role "switch" :id (id->str id)
              :data-input-id (id->str id) :data-input-type "checkbox"
              :checked (boolean value)}]
     [:span.dsh-switch-track]
     [:span label]]]))

(defn- choice-group [type input-type id label choices selected inline?]
  (let [choices (html/normalize-choices choices)]
    [:fieldset {:class (classes "dsh-input" (str "dsh-input-" type) (when inline? "dsh-inline"))
                :id (id->str id)
                :data-input-id (id->str id)
                :data-input-type type}
     (when label [:legend.dsh-label label])
     (for [{:keys [value label]} choices]
       [:label.dsh-check
        [:input {:type input-type
                 :name (id->str id)
                 :value (html/encode-value value)
                 :checked (contains? selected value)}]
        [:span label]])]))

(defn checkbox-group-input
  "A group of checkboxes. Its value is a vector of the checked choices.
  See `select-input` for `choices`. Options: `:selected` (a collection),
  `:inline?`."
  ([id label choices] (checkbox-group-input id label choices {}))
  ([id label choices {:keys [selected inline?]}]
   (choice-group "checkbox-group" "checkbox" id label choices (set selected) inline?)))

(defn radio-buttons
  "A group of radio buttons. Its value is the chosen choice. See
  `select-input` for `choices`. Options: `:selected` (defaults to the
  first choice), `:inline?`."
  ([id label choices] (radio-buttons id label choices {}))
  ([id label choices {:keys [selected inline?]}]
   (let [selected (if (some? selected)
                    selected
                    (:value (first (html/normalize-choices choices))))]
     (choice-group "radio" "radio" id label choices #{selected} inline?))))

(defn date-input
  "A date picker. Its value is a `java.time.LocalDate`, or nil.
  Options: `:value` (a LocalDate or \"yyyy-mm-dd\"), `:min`, `:max`."
  ([id label] (date-input id label {}))
  ([id label {:keys [value min max]}]
   (input-wrapper "date" id label
                  [:input.dsh-control {:type "date" :id (id->str id)
                                       :data-input-id (id->str id) :data-input-type "date"
                                       :value (some-> value str) :min (some-> min str)
                                       :max (some-> max str)}])))

(defn action-button
  "A button. Its value counts the clicks: nil before the first, then 1,
  2, ... Use it with `observe-event` or `event-reactive`.
  Options: `:class`, `:theme` (`:primary` default, `:secondary`)."
  ([id label] (action-button id label {}))
  ([id label {:keys [class theme] :or {theme :primary}}]
   [:button {:type "button"
             :class (classes "dsh-btn" (str "dsh-btn-" (name theme)) class)
             :id (id->str id)
             :data-input-id (id->str id) :data-input-type "action"}
    label]))

;; ---------------------------------------------------------------------------
;; Outputs

(defn- output [tag type id attrs]
  [tag (merge {:class (classes "dsh-output" (str "dsh-output-" type) (:class attrs))
               :id (id->str id)
               :data-output-id (id->str id)
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
    indices into the plotted data (or nil when cleared). Pass `true`
    to use `<output-id>-brush`.
  - `:click` -- an input id that receives the row index of a clicked
    point. Pass `true` to use `<output-id>-click`."
  ([id] (plot-output id {}))
  ([id {:keys [height width brush click class]}]
   (let [default-id (fn [suffix v]
                      (cond (true? v) (str (id->str id) "-" suffix)
                            v (id->str v)))]
     (output :div "plot" id
             {:class class
              :style (style {:height (css-size (or height 400)) :width (css-size width)})
              :data-brush (default-id "brush" brush)
              :data-click (default-id "click" click)}))))

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
   [:a {:class (classes "dsh-btn" (str "dsh-btn-" (name theme)) "dsh-download" "dsh-disabled" class)
        :id (id->str id)
        :data-download-id (id->str id)
        :href "#"
        :download ""}
    [:span.dsh-download-icon] label]))
