(ns dashboards.ui-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dashboards.app :as app]
            [dashboards.html :as html]
            [dashboards.ui :as ui]))

(defn- render [h] (html/hiccup->html h))

(deftest inputs-carry-their-id-and-type
  (let [s (render (ui/slider-input :n "N" {:min 1 :max 9 :value 3}))]
    (is (str/includes? s "data-input-id=\"n\""))
    (is (str/includes? s "data-input-type=\"slider\""))
    (is (str/includes? s "value=\"3\"")))
  (is (str/includes? (render (ui/numeric-input :my/num "N")) "data-input-id=\"my/num\"")))

(deftest choices-travel-as-edn
  (let [s (render (ui/select-input :col "Column" {:bill "Bill" :mass "Mass"} {:selected :mass}))]
    (is (str/includes? s "value=\":bill\""))
    (is (re-find #"selected[^>]*>Mass" s)))
  (is (= [{:value 1 :label "1"} {:value :a :label "a"} {:value "x" :label "X"}]
         (html/normalize-choices [1 :a {:value "x" :label "X"}])))
  (is (= :a (html/decode-value (html/encode-value :a))))
  (is (= "not edn {" (html/decode-value "not edn {")))
  (is (= "red" (html/decode-value "red")))
  (is (= [1 "a"] (html/decode-value "[1 \"a\"]"))))

(deftest outputs
  (let [s (render (ui/plot-output :scatter {:height 300 :brush true}))]
    (is (str/includes? s "data-output-id=\"scatter\""))
    (is (str/includes? s "data-brush=\"scatter-brush\""))
    (is (str/includes? s "height:300px"))))

(deftest pages
  (let [s (render (ui/page-navbar {:title "T" :id :tab}
                                  (ui/nav-panel "One" [:p "1"])
                                  (ui/nav-panel "Two" [:p "2"])))]
    (is (str/includes? s "data-input-type=\"tabs\""))
    (is (= 2 (count (re-seq #"data-dsh-pane" s)))))
  (let [s (render (ui/page-sidebar {:title "T"} (ui/sidebar [:p "side"]) [:p "main"]))]
    (is (str/includes? s "dsh-sidebar"))
    (is (str/includes? s "main"))))

(deftest page-html
  (let [a (app/app {:title "Hi" :ui (ui/page [:p "hello"])})
        s (app/page-html a {})]
    (is (str/starts-with? s "<!DOCTYPE html>"))
    (is (str/includes? s "<title>Hi</title>"))
    (is (str/includes? s "_dashboards/dashboards.js?v="))
    (is (some? (app/asset "dashboards.js")))
    (is (nil? (app/asset "../secrets")))))

(deftest tables
  (let [s (render (html/dataset->hiccup [{:a 1.5 :b "x"} {:a 2.0 :b "y"} {:a nil :b "z"}]
                                        {:max-rows 2}))]
    (is (str/includes? s "<td class=\"dsh-num\">1.5</td>"))
    (is (str/includes? s "<td class=\"dsh-num\">2</td>"))
    (is (str/includes? s "Showing 2 of 3 rows"))))
