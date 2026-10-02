(ns dashboards.session-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dashboards.app :as app]
            [dashboards.reactive :as r]
            [dashboards.render :as render]
            [dashboards.session :as session]
            [dashboards.ui :as ui]
            [scicloj.plotje.api :as pj]
            [tablecloth.api :as tc]))

(defn- start [app]
  (let [sent (atom [])
        s (session/start! app {:send! #(swap! sent conj %)})]
    {:session s :sent sent}))

(defn- settle! [{:keys [session]}]
  (r/submit-sync! (:domain session) (fn [])))

(defn- outputs [sent id]
  (filter #(and (= "output" (:type %)) (= id (:id %))) @sent))

(defn- last-output [sent id] (last (outputs sent id)))

(def ds (tc/dataset {:x [1 2 3 4] :y [2 4 3 5] :g ["a" "a" "b" "b"]}))

(def test-app
  (app/app
   {:ui (ui/page (ui/slider-input :n "N" {:value 2}) (ui/text-output :doubled))
    :server (fn [{:keys [input]}]
              (let [doubled (r/reactive (* 2 (input :n 0)))]
                {:doubled (render/text @doubled)
                 :choice (render/text (pr-str (input :choice)))
                 :needs (render/text (r/req (input :missing)))
                 :checked (render/text (r/validate (input :ok) "Not OK yet") "fine")
                 :broken (render/text (throw (ex-info "kaboom" {})))
                 :plot (render/plot (pj/lay-point ds :x :y))
                 :table (render/table ds)
                 :file (render/download {:filename "data.csv"} ds)}))}))

(defn- signals [m] (merge {"dsh" {"session" "" "sizes" {} "kinds" {}}} m))

(deftest session-lifecycle
  (let [{:keys [session sent] :as t} (start test-app)]
    (session/connected! session)
    (settle! t)
    (is (= {:type "connected" :session (:id session)} (first @sent)))

    (testing "outputs render once the browser sends its signals"
      (session/receive! session (signals {"n" 2 "choice" ":b" "dsh" {"kinds" {"choice" "edn"}}}))
      (settle! t)
      (is (str/includes? (:html (last-output sent "doubled")) ">4<"))
      (is (str/includes? (:html (last-output sent "choice")) ":b")
          "EDN-encoded choices come back as the values they were"))

    (testing "changing an input re-renders what depends on it, and only that"
      (let [plot-renders (count (outputs sent "plot"))]
        (session/receive! session (signals {"n" 5 "choice" ":b" "dsh" {"kinds" {"choice" "edn"}}}))
        (settle! t)
        (is (str/includes? (:html (last-output sent "doubled")) ">10<"))
        (is (some #(= {:type "recalculating" :id "doubled"} %) @sent))
        (is (= plot-renders (count (outputs sent "plot"))))))

    (testing "req, validate and errors"
      (is (= "empty" (:status (last-output sent "needs"))))
      (is (= "validation" (:status (last-output sent "checked"))))
      (is (str/includes? (:html (last-output sent "checked")) "Not OK yet"))
      (session/receive! session (signals {"n" 5 "ok" true}))
      (settle! t)
      (is (= "ok" (:status (last-output sent "checked"))))
      (is (= "error" (:status (last-output sent "broken"))))
      (is (str/includes? (:html (last-output sent "broken")) "kaboom")))

    (testing "inputs missing from a snapshot become nil"
      (is (str/includes? (:html (last-output sent "choice")) "nil")))

    (testing "plots and tables"
      (is (str/starts-with? (:html (last-output sent "plot")) "<svg"))
      (is (str/includes? (:html (last-output sent "table")) "<table")))

    (testing "plots are sized to their element"
      (session/receive! session (signals {"n" 5 "ok" true "dsh" {"sizes" {"plot" [500 300]}}}))
      (settle! t)
      (is (str/includes? (:html (last-output sent "plot")) "width=\"500\"")))

    (testing "connected! resends every output, for a browser that reconnected"
      (reset! sent [])
      (session/connected! session)
      (settle! t)
      (is (= "connected" (:type (first @sent))))
      (is (= #{"doubled" "choice" "needs" "checked" "broken" "plot" "table"}
             (set (keep :id (rest @sent))))))

    (testing "downloads"
      (let [{:keys [filename content-type body]} (session/download! session "file")]
        (is (= "data.csv" filename))
        (is (str/starts-with? content-type "text/csv"))
        (is (str/starts-with? (String. ^bytes body "UTF-8") "x,y,g\n1,2,a"))))

    (testing "closing stops the session"
      (let [ended (atom false)]
        (session/on-ended session #(reset! ended true))
        (session/close! session)
        (is @ended)
        (let [n (count @sent)]
          (session/receive! session (signals {"n" 7}))
          (Thread/sleep 50)
          (is (= n (count @sent))))))))

(deftest decoding-inputs-by-kind
  (let [seen (atom nil)
        a (app/app {:ui [:div]
                    :server (fn [{:keys [input]}]
                              (r/observe (reset! seen (mapv input [:species :n :empty :day :go :raw :scatter-brush])))
                              nil)})
        {:keys [session] :as t} (start a)]
    (session/receive! session (signals {"species" [":adelie" "" ":gentoo"] "n" "12" "empty" ""
                                        "day" "2024-03-01" "go" 0 "raw" {"a" [1 {"b" 2}]}
                                        "scatterBrush" [3 4]
                                        "dsh" {"kinds" {"species" "edn" "n" "number" "empty" "number"
                                                        "day" "date" "go" "action"}}}))
    (settle! t)
    (is (= [[:adelie :gentoo] 12 nil (java.time.LocalDate/of 2024 3 1) nil {:a [1 {:b 2}]} [3 4]]
           @seen)
        "checkbox slots drop unchecked entries; numbers parse; actions start at nil; kebab ids read camelCase signals")))

(deftest server-functions-must-return-render-specs
  (let [{:keys [session sent] :as t}
        (start (app/app {:ui [:div] :server (fn [_] {:oops 42})}))]
    (session/receive! session (signals {}))
    (settle! t)
    (is (some #(and (= "notification" (:type %))
                    (str/includes? (:message %) "not a render spec"))
              @sent))))

(deftest update-input-encodes-values
  (let [{:keys [session sent] :as t}
        (start (app/app {:ui [:div]
                         :server (fn [_]
                                   (session/update-input! :col {:choices [:a :b] :selected :b})
                                   (session/update-input! :x-col {:value :c})
                                   (session/update-input! :n {:value 3})
                                   nil)}))]
    (session/receive! session (signals {"dsh" {"kinds" {"xCol" "edn" "n" "number"}}}))
    (settle! t)
    (is (= [{:type "choices" :id "col" :signal "col"
             :choices [{:value ":a" :label "a"} {:value ":b" :label "b"}]
             :selected ":b"}
            {:type "signals" :signals {"xCol" ":c"}}
            {:type "signals" :signals {"n" "3"}}]
           (take-last 3 (remove #(#{"busy" "idle"} (:type %)) @sent))))))
