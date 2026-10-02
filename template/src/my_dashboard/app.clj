(ns my-dashboard.app
  "Your dashboard. `ui` is what the page shows; `server` runs once per
  browser session and returns what fills each output."
  (:require [clojure.java.io :as io]
            [dashboards.app :as app]
            [dashboards.reactive :as r]
            [dashboards.render :as render]
            [dashboards.ui :as ui]
            [scicloj.plotje.api :as pj]
            [tablecloth.api :as tc]))

;; Data that ships with the app lives in resources/, so it ends up
;; inside the jar too.
(def sales
  (tc/dataset (io/input-stream (io/resource "my_dashboard/sales.csv"))
              {:file-type :csv :key-fn keyword}))

(def regions (vec (sort (distinct (sales :region)))))

(def ui
  (ui/page-sidebar
   {:title "Monthly sales"}
   (ui/sidebar
    (ui/checkbox-group-input :regions "Regions" regions {:selected regions})
    (ui/switch-input :cumulative "Cumulative"))
   (ui/layout-columns
    (ui/value-box {:title "Total units" :value (ui/text-output :total) :theme :primary}))
   (ui/card {:title "Units sold"} (ui/plot-output :plot))
   (ui/card {:title "Data"} (ui/table-output :table {:max-height 320}))))

(defn server [{:keys [input]}]
  (let [filtered (r/reactive
                  (r/validate (seq (input :regions)) "Choose at least one region.")
                  (let [chosen (set (input :regions))]
                    (tc/select-rows sales #(chosen (:region %)))))
        shown (r/reactive
               (if (input :cumulative)
                 (-> @filtered
                     (tc/group-by [:region])
                     (tc/add-column :units #(reductions + (:units %)))
                     (tc/ungroup))
                 @filtered))]
    {:total (render/text (format "%,d" (long (reduce + (:units @filtered)))))
     :plot (render/plot (-> @shown
                            (pj/lay-line :month :units {:color :region})
                            (pj/lay-point {:color :region})))
     :table (render/table @shown)}))

(def app
  (app/app {:title "Monthly sales" :ui ui :server server}))
