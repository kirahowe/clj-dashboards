(ns dashboards.reactive-test
  (:require [clojure.test :refer [deftest is testing]]
            [dashboards.reactive :as r]))

(defn- settle!
  "Wait until everything queued on `d` (and the flushes it causes) has run."
  [d]
  (r/submit-sync! d (fn [])))

(defn- eventually [pred]
  (loop [n 0]
    (cond (pred) true
          (> n 200) false
          :else (do (Thread/sleep 10) (recur (inc n))))))

(deftest values-behave-like-atoms
  (let [v (r/value 1)]
    (is (= 1 @v))
    (is (= 2 (swap! v inc)))
    (is (= 5 (reset! v 5)))
    (is (= [5 6] (swap-vals! v inc)))
    (is (= 6 (v)))))

(deftest reactives-cache-and-recompute
  (let [a (r/value 1)
        runs (atom 0)
        doubled (r/reactive (swap! runs inc) (* 2 @a))]
    (is (= 2 @doubled))
    (is (= 2 @doubled))
    (is (= 1 @runs) "cached while nothing changes")
    (reset! a 2)
    (is (= 4 @doubled))
    (is (= 2 @runs))
    (reset! a 2)
    (is (= 4 @doubled))
    (is (= 2 @runs) "writing an equal value does not invalidate")))

(deftest reactives-chain
  (let [a (r/value 1)
        b (r/reactive (+ @a 10))
        c (r/reactive (* @b 2))]
    (is (= 22 @c))
    (reset! a 5)
    (is (= 30 @c))))

(deftest dynamic-dependencies
  (let [use-a? (r/value true)
        a (r/value :a)
        b (r/value :b)
        runs (atom 0)
        pick (r/reactive (swap! runs inc) (if @use-a? @a @b))]
    (is (= :a @pick))
    (reset! b :b2)
    (is (= :a @pick))
    (is (= 1 @runs) "b was not read, so changing it does nothing")
    (reset! use-a? false)
    (is (= :b2 @pick))
    (reset! a :a2)
    @pick
    (is (= 2 @runs) "a is no longer read")))

(deftest reactive-errors-are-cached-and-rethrown
  (let [a (r/value 0)
        q (r/reactive (/ 10 @a))]
    (is (thrown? ArithmeticException @q))
    (is (thrown? ArithmeticException @q))
    (reset! a 2)
    (is (= 5 @q))))

(deftest observers-run-and-rerun
  (let [d (r/domain)
        a (r/value 1)
        seen (atom [])]
    (r/submit! d #(r/observe (swap! seen conj @a)))
    (settle! d)
    (is (= [1] @seen))
    (reset! a 2)
    (settle! d)
    (is (= [1 2] @seen))
    (testing "several changes before a flush coalesce into one run"
      (r/submit-sync! d #(do (reset! a 3) (reset! a 4)))
      (is (= [1 2 4] @seen)))
    (r/close-domain! d)))

(deftest changes-from-other-threads-reach-session-observers
  (let [shared (r/value 0)
        d1 (r/domain) d2 (r/domain)
        s1 (atom []) s2 (atom [])]
    (r/submit! d1 #(r/observe (swap! s1 conj @shared)))
    (r/submit! d2 #(r/observe (swap! s2 conj @shared)))
    (settle! d1) (settle! d2)
    @(future (reset! shared 42))
    (is (eventually #(and (= [0 42] @s1) (= [0 42] @s2))))
    (r/close-domain! d1)
    (reset! shared 43)
    (is (eventually #(= [0 42 43] @s2)))
    (Thread/sleep 50)
    (is (= [0 42] @s1) "closed domains stop observing")
    (r/close-domain! d2)))

(deftest isolate-and-observe-event
  (let [d (r/domain)
        clicks (r/value nil)
        text (r/value "a")
        saved (atom [])]
    (r/submit! d #(r/observe-event @clicks (swap! saved conj @text)))
    (settle! d)
    (is (= [] @saved) "nil events are ignored")
    (reset! text "b")
    (settle! d)
    (is (= [] @saved) "the handler's reads are not tracked")
    (reset! clicks 1)
    (settle! d)
    (is (= ["b"] @saved))
    (r/close-domain! d)))

(deftest event-reactive
  (let [go (r/value nil)
        n (r/value 1)
        er (r/event-reactive @go (* 10 @n))]
    (is (thrown-with-msg? Exception #"req" @er))
    (reset! go 1)
    (is (= 10 @er))
    (reset! n 2)
    (is (= 10 @er))
    (swap! go inc)
    (is (= 20 @er))))

(deftest req-and-validate
  (is (= 3 (r/req 1 2 3)))
  (doseq [x [nil false "" "  " [] {}]]
    (is (r/silent? (try (r/req x) (catch Exception e e)))))
  (let [e (try (r/validate true "ok" nil "Pick something") (catch Exception e e))]
    (is (r/validation? e))
    (is (= "Pick something" (ex-message e)))))

(deftest observer-errors-are-reported
  (let [errors (atom [])
        d (r/domain :on-error (fn [_ t] (swap! errors conj (ex-message t))))]
    (r/submit! d #(r/observe (throw (ex-info "boom" {}))))
    (r/submit! d #(r/observe (r/req nil)))
    (settle! d)
    (is (= ["boom"] @errors) "req is silent")
    (r/close-domain! d)))

(deftest invalidate-later-ticks
  (let [d (r/domain)
        ticks (atom 0)]
    (r/submit! d #(r/observe (r/invalidate-later 20) (swap! ticks inc)))
    (is (eventually #(>= @ticks 3)))
    (r/close-domain! d)
    (let [n @ticks]
      (Thread/sleep 80)
      (is (<= (- @ticks n) 1)))))

(deftest debounce-waits-for-quiet
  (let [d (r/domain)
        src (r/value "a")
        seen (atom [])]
    (r/submit! d #(let [deb (r/debounce (fn [] @src) 60)]
                    (r/observe (swap! seen conj @deb))))
    (is (eventually #(= ["a"] @seen)))
    (reset! src "ab")
    (reset! src "abc")
    (Thread/sleep 20)
    (is (= ["a"] @seen))
    (is (eventually #(= ["a" "abc"] @seen)))
    (r/close-domain! d)))
