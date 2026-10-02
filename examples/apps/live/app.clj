;; A live dashboard. A background thread feeds a reactive value that is
;; shared by every session, so every open browser updates together --
;; open the app in two windows to see it. Each viewer still has inputs
;; of their own.
(ns live.app
  (:require [dashboards.app :as app]
            [dashboards.reactive :as r]
            [dashboards.render :as render]
            [dashboards.ui :as ui]
            [scicloj.plotje.api :as pj]
            [tablecloth.api :as tc]))

(def services [:checkout :search :catalog])

(def history-length 300)

;; Shared by all sessions: the last few minutes of simulated request
;; latencies, one reading per service per second.
(defonce readings (r/value []))

(defn- next-reading [t prev service]
  (let [base ({:checkout 120 :search 60 :catalog 85} service)
        last-ms (or (:latency-ms prev) base)
        spike? (< (rand) 0.02)
        drift (* 0.85 (- last-ms base))
        noise (* base 0.12 (- (rand) 0.5) 2)]
    {:t t
     :service service
     :latency-ms (-> (+ base drift noise (if spike? (* base (+ 1 (rand 2))) 0))
                     (max 5.0) (* 10) Math/round (/ 10.0))
     :requests (+ 40 (rand-int 30) ({:checkout 0 :search 60 :catalog 25} service))}))

(defn- tick! [t]
  (swap! readings
         (fn [rs]
           (let [latest (into {} (map (juxt :service identity)) (take-last 3 rs))
                 new (map #(next-reading t (latest %) %) services)]
             (vec (take-last (* history-length (count services)) (into rs new)))))))

(defonce feeder
  (future
    (loop [t 0]
      (tick! t)
      (Thread/sleep 1000)
      (recur (inc t)))))

;; ---------------------------------------------------------------------------

(def ui
  (ui/page-sidebar
   {:title "Service latency" :subtitle "Live, shared by every viewer"}
   (ui/sidebar
    (ui/checkbox-group-input :services "Services" services {:selected services})
    (ui/slider-input :window "Window" {:min 30 :max history-length :value 120 :step 10 :suffix " s"})
    (ui/slider-input :threshold "Alert threshold" {:min 50 :max 400 :value 220 :step 10 :suffix " ms"})
    (ui/switch-input :paused "Pause my view")
    (ui/help-text "Pausing freezes only your own window; the data keeps flowing for everyone else."))
   (ui/ui-output :boxes)
   (ui/card {:title "Latency (ms)" :full-screen? true}
            (ui/plot-output :latency {:height 380}))
   (ui/layout-columns
    (ui/card {:title "Requests per second"}
             (ui/plot-output :requests {:height 260}))
    (ui/card {:title "Readings over the threshold"}
             (ui/table-output :alerts {:max-height 260})))))

(defn server [{:keys [input]}]
  (let [;; While paused, keep returning the readings as they were.
        frozen (atom nil)
        visible (r/reactive
                 (let [rs @readings]
                   (if (input :paused)
                     (or @frozen (reset! frozen rs))
                     (do (reset! frozen nil) rs))))
        window (r/reactive
                (r/validate (seq (input :services)) "Choose at least one service.")
                (let [rs @visible
                      wanted (set (input :services))
                      t-max (:t (peek rs) 0)
                      t-min (- t-max (input :window))]
                  (->> rs
                       (filter #(and (wanted (:service %)) (> (:t %) t-min)))
                       (map #(update % :service name))
                       (tc/dataset))))]
    {:boxes
     (render/ui
      (let [rs @visible
            latest (take-last (count services) rs)]
        (into [:div.dsh-columns.dsh-columns-auto]
              (for [{:keys [service latency-ms]} (sort-by :service latest)]
                (ui/value-box {:title (name service)
                               :value (format "%.0f ms" latency-ms)
                               :subtitle "latest latency"
                               :theme (if (> latency-ms (input :threshold)) :danger :success)})))))

     :latency
     (render/plot
      (-> @window
          (pj/lay-line :t :latency-ms {:color :service})
          (pj/lay-rule-h {:y-intercept (input :threshold) :stroke-dash :dashed :color "#b42318"})
          (pj/options {:x-label "time (s)" :y-label "latency (ms)"})))

     :requests
     (render/plot
      (-> @window
          (pj/lay-area :t :requests {:color :service :alpha 0.5})
          (pj/options {:x-label "time (s)" :y-label "requests/s"})))

     :alerts
     (render/table
      (let [over (tc/select-rows @window #(> (:latency-ms %) (input :threshold)))]
        (r/validate (pos? (tc/row-count over)) "Nothing over the threshold in this window.")
        (-> over
            (tc/select-columns [:t :service :latency-ms])
            (tc/order-by [:t] :desc))))}))

(app/app {:title "Service latency"
          :description "Live data shared across sessions, with per-viewer controls."
          :ui ui
          :server server})
