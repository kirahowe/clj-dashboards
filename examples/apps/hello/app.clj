;; The "hello world" of reactive dashboards: draw a random sample and
;; look at its histogram. Run it with
;;
;;   bb serve --app examples/apps/hello
;;
;; or serve every example at once (see the README).
(ns hello.app
  (:require [dashboards.app :as app]
            [dashboards.reactive :as r]
            [dashboards.render :as render]
            [fastmath.random :as random]
            [fastmath.stats :as stats]
            [scicloj.plotje.api :as pj]
            [tablecloth.api :as tc]))

(def distributions
  {:normal "Normal (μ = 0, σ = 1)"
   :log-normal "Log-normal"
   :exponential "Exponential (mean 1)"
   :uniform "Uniform (0, 1)"})

(defn draw [dist n]
  (let [d (case dist
            :normal (random/distribution :normal {:mu 0 :sd 1})
            :log-normal (random/distribution :log-normal {:scale 0 :shape 0.6})
            :exponential (random/distribution :exponential {:mean 1})
            :uniform (random/distribution :uniform-real {:lower 0 :upper 1}))]
    (vec (random/->seq d n))))

(def ui
  ;; The whole UI is data: component tags (:ui/...) mixed with plain
  ;; hiccup. The colour picker is plain HTML bound to a Datastar
  ;; signal; the server reads it as (input :bar-color), and the swatch
  ;; label updates in the browser without a round trip.
  [:ui/page-sidebar {:title "Hello, dashboards" :subtitle "A random sample and its histogram"}
   [:ui/sidebar
    [:ui/select-input {:id :dist :label "Distribution" :choices distributions}]
    [:ui/slider-input {:id :n :label "Sample size" :min 10 :max 5000 :value 500 :step 10}]
    [:ui/slider-input {:id :bins :label "Bins" :min 5 :max 80 :value 30}]
    [:div.dsh-input {:data-signals:bar-color "'#2563c9'"}
     [:label.dsh-label {:for "bar-color"} "Bar colour " [:code {:data-text "$barColor"}]]
     [:input#bar-color {:type "color" :data-bind "barColor"}]]
    [:ui/action-button {:id :resample} "Draw a new sample"]
    [:ui/help-text "The sample is redrawn when the distribution or size changes, "
     "or when you press the button. Changing the bins or colour only "
     "redraws the plot."]]
   [:ui/layout-columns
    [:ui/value-box {:title "Mean" :value [:ui/text-output {:id :mean}] :theme :primary}]
    [:ui/value-box {:title "Standard deviation" :value [:ui/text-output {:id :sd}]}]
    [:ui/value-box {:title "Median" :value [:ui/text-output {:id :median}]}]]
   [:ui/card {:title "Histogram" :full-screen? true}
    [:ui/plot-output {:id :histogram :height 420}]]
   [:ui/card {:title "Summary"}
    [:ui/table-output {:id :summary}]]])

(defn server [{:keys [input]}]
  (let [sample (r/reactive
                ;; Reading the button makes it a dependency, so each
                ;; click draws again.
                (input :resample)
                (draw (input :dist) (input :n)))
        fmt #(format "%.3f" (double %))]
    {:mean (render/text (fmt (stats/mean @sample)))
     :sd (render/text (fmt (stats/stddev @sample)))
     :median (render/text (fmt (stats/median @sample)))
     :histogram (render/plot
                 (-> (tc/dataset {:value @sample})
                     (pj/lay-histogram :value {:bins (input :bins)
                                               :color (input :bar-color "#2563c9")})
                     (pj/options {:x-label "value" :y-label "count"})))
     :summary (render/table
               (let [m (stats/stats-map @sample)]
                 (for [k [:Size :Min :Q1 :Median :Q3 :Max :Mean :SD :Skewness :Kurtosis]]
                   {:statistic (name k) :value (m k)})))}))

(app/app {:title "Hello, dashboards"
          :description "A random sample and its histogram."
          :ui ui
          :server server})
