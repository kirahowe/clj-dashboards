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

(deftest session-lifecycle
  (let [{:keys [session sent] :as t} (start test-app)]
    (is (= {:type "hello" :session (:id session)} (first @sent)))

    (testing "outputs render once the browser sends its inputs"
      (session/receive! session {:type "init" :inputs {"n" 2 "choice" ":b"} :kinds {"choice" "edn"}})
      (settle! t)
      (is (str/includes? (:html (last-output sent "doubled")) ">4<"))
      (is (str/includes? (:html (last-output sent "choice")) ":b")
          "EDN-encoded choices come back as the values they were"))

    (testing "changing an input re-renders what depends on it, and only that"
      (let [plot-renders (count (outputs sent "plot"))]
        (session/receive! session {:type "input" :id "n" :value 5})
        (settle! t)
        (is (str/includes? (:html (last-output sent "doubled")) ">10<"))
        (is (some #(= {:type "recalculating" :id "doubled"} %) @sent))
        (is (= plot-renders (count (outputs sent "plot"))))))

    (testing "req, validate and errors"
      (is (= "empty" (:status (last-output sent "needs"))))
      (is (= {:status "validation" :message "Not OK yet"}
             (select-keys (last-output sent "checked") [:status :message])))
      (session/receive! session {:type "input" :id "ok" :value true})
      (settle! t)
      (is (= "ok" (:status (last-output sent "checked"))))
      (is (= {:status "error" :message "kaboom"}
             (select-keys (last-output sent "broken") [:status :message]))))

    (testing "plots and tables"
      (is (str/starts-with? (:html (last-output sent "plot")) "<svg"))
      (is (str/includes? (:html (last-output sent "table")) "<table")))

    (testing "plots are sized to their element"
      (session/receive! session {:type "inputs" :inputs {"clientdata/output-plot-width" 500
                                                         "clientdata/output-plot-height" 300}})
      (settle! t)
      (is (str/includes? (:html (last-output sent "plot")) "width=\"500\"")))

    (testing "bind resends cached outputs"
      (let [n (count @sent)]
        (session/receive! session {:type "bind" :outputs ["doubled"]})
        (settle! t)
        (is (= (last-output sent "doubled") (last @sent)))
        (is (= (inc n) (count @sent)))))

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
          (session/receive! session {:type "input" :id "n" :value 7})
          (Thread/sleep 50)
          (is (= n (count @sent))))))))

(deftest server-functions-must-return-render-specs
  (let [{:keys [session sent] :as t}
        (start (app/app {:ui [:div] :server (fn [_] {:oops 42})}))]
    (session/receive! session {:type "init" :inputs {}})
    (settle! t)
    (is (some #(and (= "notification" (:type %))
                    (str/includes? (:message %) "not a render spec"))
              @sent))))

(deftest update-input-encodes-choices
  (let [{:keys [session sent] :as t}
        (start (app/app {:ui [:div]
                         :server (fn [_]
                                   (session/update-input! :col {:choices [:a :b] :selected :b})
                                   nil)}))]
    (session/receive! session {:type "init" :inputs {}})
    (settle! t)
    (is (= {:type "update-input" :id "col"
            :props {:choices [{:value ":a" :label "a"} {:value ":b" :label "b"}]
                    :selected ":b"}}
           (last @sent)))))
