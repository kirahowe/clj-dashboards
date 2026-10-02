;; Palmer penguins explorer: filters, linked brushing between a scatter
;; plot and a table, a download, and several pages.
;;
;; Data: palmerpenguins (Horst, Hill & Gorman, 2020), CC0.
(ns penguins.app
  (:require [clojure.string :as str]
            [dashboards.app :as app]
            [dashboards.reactive :as r]
            [dashboards.render :as render]
            [dashboards.session :as session]
            [dashboards.ui :as ui]
            [scicloj.plotje.api :as pj]
            [tablecloth.api :as tc]))

(def penguins
  (-> (tc/dataset (str (app/local-file "penguins.csv")) {:key-fn keyword})
      (tc/drop-missing)
      (tc/update-columns [:sex] (partial map #(some-> % str/lower-case)))))

(def measurements
  {:bill_length_mm "Bill length (mm)"
   :bill_depth_mm "Bill depth (mm)"
   :flipper_length_mm "Flipper length (mm)"
   :body_mass_g "Body mass (g)"})

(def species (vec (sort (distinct (penguins :species)))))
(def islands (vec (sort (distinct (penguins :island)))))

;; ---------------------------------------------------------------------------
;; UI

(def explore
  (ui/layout-sidebar
   (ui/sidebar
    {:title "Filters"}
    (ui/checkbox-group-input :species "Species" species {:selected species})
    (ui/checkbox-group-input :islands "Island" islands {:selected islands})
    (ui/radio-buttons :sex "Sex" {:all "All" "female" "Female" "male" "Male"} {:inline? true})
    [:hr]
    (ui/select-input :x "Horizontal axis" measurements {:selected :flipper_length_mm})
    (ui/select-input :y "Vertical axis" measurements {:selected :body_mass_g})
    (ui/select-input :color "Colour by" {:species "Species" :island "Island" :sex "Sex"})
    (ui/switch-input :trend "Show trend lines" {:value false})
    (ui/download-button :download "Download CSV"))
   (ui/layout-columns
    (ui/value-box {:title "Penguins" :value (ui/text-output :count)
                   :subtitle (ui/text-output :count-note) :theme :primary :icon "🐧"})
    (ui/value-box {:title "Mean body mass" :value (ui/text-output :mass)
                   :subtitle "grams" :icon "⚖️"})
    (ui/value-box {:title "Mean flipper length" :value (ui/text-output :flipper)
                   :subtitle "millimetres" :icon "📏"}))
   (ui/layout-columns
    {:widths [7 5]}
    (ui/card {:title "Measurements" :full-screen? true
              :footer "Drag across the plot to select penguins; click without dragging to clear."}
             (ui/plot-output :scatter {:height 440 :brush :selection}))
    (ui/card {:title (ui/text-output :hist-title) :full-screen? true}
             (ui/plot-output :histogram {:height 440})))
   (ui/card {:title (ui/text-output :table-title)}
            (ui/table-output :table {:max-height 360}))))

(def about
  (ui/markdown-ish
   [:h2 "About this app"]
   [:p "Measurements of 333 penguins of three species, recorded at Palmer Station, "
    "Antarctica, by Dr. Kristen Gorman. Data from the "
    [:a {:href "https://allisonhorst.github.io/palmerpenguins/"} "palmerpenguins"]
    " package (CC0)."]
   [:p "Built with clj-dashboards: the UI is hiccup from " [:code "dashboards.ui"]
    ", the plots are " [:code "plotje"] " poses, and the data is a "
    [:code "tablecloth"] " dataset."]))

(def ui
  (ui/page-navbar
   {:title "Palmer penguins" :id :page}
   (ui/nav-panel "Explore" explore)
   (ui/nav-panel "Data" (ui/card {:title "All penguins"} (ui/table-output :all {:max-height 640})))
   (ui/nav-panel "About" about)))

;; ---------------------------------------------------------------------------
;; Server

(defn- mean [xs] (when (seq xs) (/ (reduce + xs) (double (count xs)))))

(defn server [{:keys [input]}]
  (let [filtered (r/reactive
                  (r/validate (seq (input :species)) "Choose at least one species."
                              (seq (input :islands)) "Choose at least one island.")
                  (let [sp (set (input :species))
                        is (set (input :islands))
                        sex (input :sex)]
                    (tc/select-rows penguins
                                    (fn [row]
                                      (and (sp (:species row))
                                           (is (:island row))
                                           (or (= sex :all) (= sex (:sex row))))))))
        ;; The brush reports row indices into the plotted (filtered) data.
        brushed (r/reactive
                 (let [rows (input :selection)]
                   (if (seq rows)
                     (tc/select-rows @filtered rows)
                     @filtered)))
        label #(measurements % (name %))]
    {:count (render/text (tc/row-count @brushed))
     :count-note (render/text (if (seq (input :selection))
                                (str "selected of " (tc/row-count @filtered) " shown")
                                (str "of " (tc/row-count penguins) " in the data")))
     :mass (render/text (some->> (mean (@brushed :body_mass_g)) (format "%,.0f")))
     :flipper (render/text (some->> (mean (@brushed :flipper_length_mm)) (format "%.1f")))

     :scatter
     (render/plot
      (let [x (input :x) y (input :y) color (input :color)
            pose (-> @filtered
                     (pj/pose x y {:color color})
                     (pj/lay-point {:alpha 0.8}))]
        (cond-> pose
          (input :trend) (pj/lay-smooth {:stat :linear-model})
          true (pj/options {:x-label (label x) :y-label (label y)}))))

     :hist-title (render/text (str (label (input :x)) ", by " (name (input :color))))
     :histogram
     (render/plot
      (let [ds @brushed]
        (r/validate (> (tc/row-count ds) 1) "Select more penguins to see a distribution.")
        (-> ds
            (pj/lay-histogram (input :x) {:color (input :color) :bins 20 :alpha 0.75})
            (pj/options {:x-label (label (input :x)) :y-label "count"}))))

     :table-title (render/text (if (seq (input :selection)) "Selected penguins" "Penguins shown"))
     :table (render/table {:max-rows 200} @brushed)
     :all (render/table {:max-rows 1000} penguins)

     :download (render/download
                {:filename #(str "penguins-" (count (input :species)) "-species.csv")}
                @brushed)}))

(app/app {:title "Palmer penguins"
          :description "Filter, brush and download penguin measurements."
          :ui ui
          :server server})
