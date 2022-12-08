(ns pipeline-extras.core-test
  (:require
   [clojure.core :as c]
   [clojure.core.async
    :as a
    :refer [<! <!! >! alts!! chan close! go to-chan!]]
   [clojure.test :refer [deftest is testing]]
   [pipeline-extras.core
    :refer
    [pipeline
     pipeline-async
     pipeline-async-unordered
     pipeline-blocking
     pipeline-blocking-unordered
     pipeline-unordered]]))

(defn- take-all!!
  ([c] (<!! (a/into [] c)))
  ([c timeout]
   (->> [(a/timeout timeout)
         (a/into [] c)]
        alts!!
        first)))

(defn- incr [{:keys [current max] :as counter}]
  (let [new (inc current)]
    (-> counter
        (assoc :current new)
        (assoc :max (c/max new max)))))

(defn- decr [counter]
  (update counter :current dec))

(def ^:private counter
  (atom {:current 0 :max 0}))

(defn- reset-counter! []
  (reset! counter {:current 0 :max 0}))

(deftest task-count-test
  (let [max 100
        timeout 1000
        parallelism 10
        async-task (fn [x c]
                     (go
                       (swap! counter incr)
                       (<! (a/timeout 10))
                       (>! c x)
                       (swap! counter decr)
                       (close! c)))
        compute-task (fn [x]
                       (swap! counter incr)
                       (Thread/sleep 10)
                       (swap! counter decr)
                       x)]
    (testing "Pipelines don't spawn more tasks than needed"
      (let [to (chan parallelism)]
        (reset-counter!)
        (pipeline parallelism to (map compute-task) (to-chan! (range max)))
        (is (= (set (range max))
               (set (take-all!! to timeout))))
        (is (= {:max parallelism :current 0}
               @counter)))
      (let [to (chan parallelism)]
        (reset-counter!)
        (pipeline-blocking parallelism to (map compute-task) (to-chan! (range max)))
        (is (= (set (range max))
               (set (take-all!! to timeout))))
        (is (= {:max parallelism :current 0}
               @counter)))
      (let [to (chan parallelism)]
        (reset-counter!)
        (pipeline-async parallelism to async-task (to-chan! (range max)))
        (is (= (set (range max))
               (set (take-all!! to timeout))))
        (is (= {:max parallelism :current 0}
               @counter))))
    (testing "Unordered pipelines don't spawn more tasks than needed"
      (let [to (chan parallelism)]
        (reset-counter!)
        (pipeline-unordered parallelism to (map compute-task) (to-chan! (range max)))
        (is (= (set (range max))
               (set (take-all!! to timeout))))
        (is (= {:max parallelism :current 0}
               @counter)))
      (let [to (chan parallelism)]
        (reset-counter!)
        (pipeline-blocking-unordered parallelism to (map compute-task) (to-chan! (range max)))
        (is (= (set (range max))
               (set (take-all!! to timeout))))
        (is (= {:max parallelism :current 0}
               @counter)))
      (let [to (chan parallelism)]
        (reset-counter!)
        (pipeline-async-unordered parallelism to async-task (to-chan! (range max)))
        (is (= (set (range max))
               (set (take-all!! to timeout))))
        (is (= {:max parallelism :current 0}
               @counter))))))

(deftest order-test
  (let [max 10
        parallelism max
        timeout 6000
        sleep (/ (- timeout 1000) parallelism)
        async-task (fn [v c]
                     (go
                       (<! (a/timeout (* (- max v) sleep)))
                       (>! c v)
                       (close! c)))
        compute-task (fn [v]
                       (Thread/sleep (* (- max v) sleep))
                       v)]
    (testing "Pipelines keep original order"
      (let [to (chan parallelism)]
        (pipeline parallelism to (map compute-task) (to-chan! (range max)))
        (is (= (range max)
               (take-all!! to timeout))))
      (let [to (chan parallelism)]
        (pipeline-blocking parallelism to (map compute-task) (to-chan! (range max)))
        (is (= (range max)
               (take-all!! to timeout))))
      (let [to (chan parallelism)]
        (pipeline-async parallelism to async-task (to-chan! (range max)))
        (is (= (range max)
               (take-all!! to timeout)))))
    (testing "Unordered pipelines produce items in the order of completion"
      (let [to (chan parallelism)]
        (pipeline-async-unordered parallelism to async-task (to-chan! (range max)))
        (is (= (reverse (range max))
               (take-all!! to timeout))))
      (let [to (chan parallelism)]
        (pipeline-unordered parallelism to (map compute-task) (to-chan! (range max)))
        (is (= (reverse (range max))
               (take-all!! to timeout))))
      (let [to (chan parallelism)]
        (pipeline-blocking-unordered parallelism to (map compute-task) (to-chan! (range max)))
        (is (= (reverse (range max))
               (take-all!! to timeout)))))))
