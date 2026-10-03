(ns dashboards.ui-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dashboards.app :as app]
            [dashboards.datastar :as datastar]
            [dashboards.html :as html]
            [dashboards.ui :as ui]
            [starfederation.datastar.clojure.adapter.test :as sse-test]))

(defn- render [h] (html/hiccup->html h))

(deftest inputs-bind-datastar-signals
  (let [s (render (ui/slider-input :max-rows "N" {:min 1 :max 9 :value 3}))]
    (is (str/includes? s "data-bind=\"maxRows\"") "kebab-case ids become camelCase signals")
    (is (str/includes? s "data-signals__ifmissing=\"{&quot;maxRows&quot;:3}\"")
        "the starting value is declared, typed")
    (is (str/includes? s "data-text=")))
  (let [s (render (ui/action-button :go "Go"))]
    (is (str/includes? s "data-on:click=\"$go = $go + 1\""))))

(deftest choices-travel-as-edn
  (let [s (render (ui/select-input :col "Column" {:bill "Bill" :mass "Mass"} {:selected :mass}))]
    (is (str/includes? s "value=\":bill\""))
    (is (re-find #"selected[^>]*>Mass" s))
    (is (str/includes? s "&quot;kinds&quot;:{&quot;col&quot;:&quot;edn&quot;}")))
  (is (= [{:value 1 :label "1"} {:value :a :label "a"} {:value "x" :label "X"}]
         (html/normalize-choices [1 :a {:value "x" :label "X"}])))
  (is (= :a (html/decode-value (html/encode-value :a))))
  (is (= "not edn {" (html/decode-value "not edn {")))
  (is (= "red" (html/decode-value "red")))
  (is (= [1 "a"] (html/decode-value "[1 \"a\"]"))))

(deftest outputs
  (let [s (render (ui/plot-output :scatter {:height 300 :brush true}))]
    (is (str/includes? s "id=\"scatter\""))
    (is (str/includes? s "data-brush=\"scatterBrush\""))
    (is (str/includes? s "height:300px"))))

(deftest component-tags
  (testing "tags expand to the same hiccup as the functions"
    (is (= (ui/slider-input :n "N" {:max 10})
           (ui/expand [:ui/slider-input {:id :n :label "N" :max 10}])))
    (is (= (ui/card {:title "T"} (ui/plot-output :p))
           (ui/expand [:ui/card {:title "T"} [:ui/plot-output {:id :p}]]))))
  (testing "nested pages, sidebars and panels"
    (let [s (render (ui/expand
                     [:ui/page-sidebar {:title "T"}
                      [:ui/sidebar [:ui/select-input {:id :x :label "X" :choices [:a :b]}]]
                      [:ui/value-box {:title "N" :value [:ui/text-output {:id :n}]}]]))]
      (is (str/includes? s "dsh-sidebar"))
      (is (str/includes? s "data-bind=\"x\""))
      (is (str/includes? s "id=\"n\"")))
    (is (str/includes? (render (ui/expand [:ui/page-navbar {:id :tab}
                                           [:ui/nav-panel {:title "One"} [:p "1"]]
                                           [:ui/nav-panel {:title "Two"} [:p "2"]]]))
                       "$tab = &quot;\\&quot;Two\\&quot;&quot;")))
  (testing "plain hiccup is untouched"
    (is (= [:div [:input {:data-bind "n"}]] (ui/expand [:div [:input {:data-bind "n"}]]))))
  (testing "your own components"
    (ui/defcomponent :test/kpi [{:keys [id label]} _]
      [:div.kpi label (ui/text-output id)])
    (is (= [:div.kpi "Revenue" (ui/text-output :rev)]
           (ui/expand [:test/kpi {:id :rev :label "Revenue"}])))))

(deftest page-html
  (let [a (app/app {:title "Hi" :ui [:ui/page [:p "hello"]]})
        s (app/page-html a {})]
    (is (str/starts-with? s "<!DOCTYPE html>"))
    (is (str/includes? s "<title>Hi</title>"))
    (is (str/includes? s "dsh-page") "component tags in :ui are expanded")
    (is (str/includes? s "type=\"module\""))
    (is (str/includes? s "@get(&apos;_dashboards/stream&apos;"))
    (is (some? (app/asset "dashboards.js")))
    (is (some? (app/asset (str "datastar-" app/datastar-version ".js"))))
    (is (nil? (app/asset "../secrets")))))

(deftest sse-events
  (let [gen (sse-test/->sse-recorder)
        sent (fn [] @(:!rec gen))]
    (datastar/send! gen {:type "output" :id "plot" :html "<div>\n</div>" :status "ok"})
    (is (= [(str "event: datastar-patch-elements\n"
                 "data: selector #plot\n"
                 "data: mode inner\n"
                 "data: elements <div>\n"
                 "data: elements </div>\n\n")
            (str "event: datastar-dashboards\n"
                 "data: type rendered\n"
                 "data: id plot\n"
                 "data: status ok\n\n")]
           (sent)))
    (datastar/send! gen {:type "output" :id "x" :html "" :status "empty"})
    (is (str/includes? (nth (sent) 2) "data: elements <!---->")
        "an empty output still sends something to patch in")
    (datastar/send! gen {:type "signals" :signals {"x" 1}})
    (is (= "event: datastar-patch-signals\ndata: signals {\"x\":1}\n\n" (last (sent))))
    (datastar/send! gen {:type "notification" :message "two\nlines" :level "info" :duration 0})
    (is (str/includes? (last (sent)) "data: message two\ndata: message lines"))))

(deftest reading-signals
  (is (= {"n" 1 "dsh" {"session" "s"}}
         (datastar/read-signals {:request-method :get
                                 :query-string (str "datastar="
                                                    (java.net.URLEncoder/encode "{\"n\":1,\"dsh\":{\"session\":\"s\"}}" "UTF-8"))})))
  (is (= {"n" 2}
         (datastar/read-signals {:request-method :post
                                 :body (java.io.ByteArrayInputStream. (.getBytes "{\"n\":2}" "UTF-8"))})))
  (is (= {} (datastar/read-signals {:request-method :get}))))

(deftest tables
  (let [s (render (html/dataset->hiccup [{:a 1.5 :b "x"} {:a 2.0 :b "y"} {:a nil :b "z"}]
                                        {:max-rows 2}))]
    (is (str/includes? s "<td class=\"dsh-num\">1.5</td>"))
    (is (str/includes? s "<td class=\"dsh-num\">2</td>"))
    (is (str/includes? s "Showing 2 of 3 rows"))))
