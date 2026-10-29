(ns dk.simongray.ratelimit-test
  "The algorithms, the store and the decisions, on the cases that the
  other Clojure libraries were tried on, with a simulated clock."
  (:require [clojure.test :refer [deftest is testing]]
            [dk.simongray.ratelimit :as ratelimit]
            [dk.simongray.ratelimit.impl.memory :as memory]))

(def algorithms
  [:gcra :fixed-window :sliding-window])

(defn- run
  "The results of requests of `cost` at the `times` by one key of the
  `policy`."
  ([policy times]
   (run policy times 1))
  ([policy times cost]
   (:results (reduce (fn [{:keys [state] :as acc} now]
                       (let [result (ratelimit/consume policy state now cost)]
                         (-> acc
                             (assoc :state (:state result))
                             (update :results conj result))))
                     {:results []}
                     times))))

(defn- allowed
  "The number of allowed `results`."
  [results]
  (count (filter :allowed? results)))

(deftest consume
  (let [pages {:quota 400 :window 60}]
    (is (= {:allowed?     true
            :remaining    399
            :reset-after  0.15
            :refill-after 0.15
            :state        150.0}
           (ratelimit/consume pages nil 0 1)))
    (testing "the request after 400 at once"
      (is (= {:allowed?     false
              :remaining    0
              :retry-after  0.15
              :refill-after 0.15
              :reset-after  60.0
              :state        60000.0}
             (ratelimit/consume pages 60000.0 0 1))))
    (testing "a cost of 0 takes nothing"
      (let [result (ratelimit/consume pages 30000.0 0 0)]
        (is (:allowed? result))
        (is (= 200 (:remaining result)))
        (is (= 30000.0 (:state result)))))
    (testing "a cost above the burst never passes"
      (let [result (ratelimit/consume pages nil 0 401)]
        (is (false? (:allowed? result)))
        (is (nil? (:retry-after result)))))
    (testing "a lower burst"
      (is (= 39 (:remaining (ratelimit/consume (assoc pages :burst 40) nil 0 1)))))))

;; 10 a second: 1 request at 0 s, 9 at 0.9 s and 10 at 1.1 s
(deftest window-edge
  (let [times (concat [0] (repeat 9 900) (repeat 10 1100))
        pass  #(allowed (run {:quota 10 :window 1 :algorithm %} times))]
    (is (= 13 (pass :gcra)) "a token bucket gets 2 units back by 1.1 s")
    (is (= 20 (pass :fixed-window)) "the windows are aligned to the clock")
    (is (= 11 (pass :sliding-window)) "the first request has slid out")))

(deftest steady-client
  (testing "400 a minute, one request every 150 ms for 3 minutes"
    (let [blocked #(- 1200 (allowed (run {:quota 400 :window 60 :algorithm %}
                                         (range 0 180000 150))))]
      (is (= 0 (blocked :gcra)))
      (is (= 0 (blocked :fixed-window)))
      (is (= 2 (blocked :sliding-window))
          "at exactly the limit, the estimate is full at each window's start")))
  (testing "one request a second, which DanNet's old limiter blocked after 400"
    (doseq [algorithm algorithms]
      (is (= 1000 (allowed (run {:quota 400 :window 60 :algorithm algorithm}
                                (range 0 1000000 1000))))))))

(defn- retry-after-exact?
  "Whether a request of `cost` after the `times` passes at its
  :retry-after but not a millisecond earlier."
  [policy times cost]
  (let [now    (last times)
        state  (:state (last (run policy times)))
        result (ratelimit/consume policy state now cost)
        ms     (* 1000 (:retry-after result))]
    (and (not (:allowed? result))
         (:allowed? (ratelimit/consume policy state (+ now ms 1e-6) cost))
         (not (:allowed? (ratelimit/consume policy state (+ now ms -1) cost))))))

(deftest retry-after
  (doseq [algorithm  algorithms
          [times cost] [[(repeat 10 0) 1]
                        [(range 0 1000 50) 3]
                        [(repeat 10 1500) 10]]]
    (is (retry-after-exact? {:quota 10 :window 1 :algorithm algorithm} times cost)
        (str algorithm " after " (count times) " requests, cost " cost)))
  (testing "blocked at 2.3 s, with 7.7 s left of a fixed window"
    (let [policy {:name "w" :quota 10 :window 10 :algorithm :fixed-window}
          store  (ratelimit/memory-store)
          _      (dotimes [_ 10] (ratelimit/limit! [policy] store {:remote-addr "a"} 0))
          d      (ratelimit/limit! [policy] store {:remote-addr "a"} 2300)]
      (is (= "8" (ratelimit/retry-after d)))
      (is (empty? (:limited (ratelimit/limit! [policy] store {:remote-addr "a"} 10000)))))))

(deftest eviction
  (let [store  (ratelimit/memory-store)
        policy {:quota 10 :window 60}]
    (dotimes [i 100000]
      (ratelimit/consume! store policy i 1 0))
    (is (= 100000 (memory/size store)))
    (ratelimit/consume! store policy :late 1 120000)
    (is (= 1 (memory/size store)) "the sweep at 120 s drops the idle keys")
    (testing "a cost of 0 on a new key keeps nothing"
      (ratelimit/consume! store policy :peek 0 120000)
      (is (= 1 (memory/size store))))))

#?(:clj
   (deftest threads
     (doseq [algorithm algorithms]
       (let [store  (ratelimit/memory-store)
             policy {:quota 100 :window 3600 :algorithm algorithm}
             passed (->> (repeatedly 32 #(future
                                           (allowed (repeatedly 50 (fn []
                                                                     (ratelimit/consume! store policy :k 1 0))))))
                         doall
                         (map deref)
                         (reduce +))]
         (is (= 100 passed) (str algorithm))))))

(deftest limit!
  (let [burst   {:name "burst" :quota 3 :window 1}
        daily   {:name "daily" :quota 10 :window 86400 :algorithm :fixed-window}
        request {:remote-addr "203.0.113.7"}]
    (doseq [policies [[burst daily] [daily burst]]]
      (let [store     (ratelimit/memory-store)
            decisions (mapv #(ratelimit/limit! policies store request %)
                            [0 0 0 0 1000])
            remaining (fn [d name]
                        (some #(when (= name (:name %)) (:remaining %)) (:results d)))]
        (is (= [nil nil nil ["burst"] nil] (map :limited decisions)))
        (testing "a refused request takes nothing from any policy"
          (is (= [9 8 7 7 6] (map #(remaining % "daily") decisions))))))
    (testing "the results"
      (is (= {:results [{:name         "burst"
                         :quota        3
                         :window       1
                         :key          "203.0.113.7"
                         :allowed?     true
                         :remaining    2
                         :reset-after  (/ 1 3.0)
                         :refill-after (/ 1 3.0)}]}
             (ratelimit/limit! [burst] (ratelimit/memory-store) request 0))))
    (testing ":when, :key and :cost"
      (let [policy {:name "api"
                    :quota 10
                    :window 60
                    :when  #(= "/api" (:uri %))
                    :key   #(get-in % [:headers "x-api-key"])
                    :cost  #(if (= :post (:request-method %)) 5 1)}
            store  (ratelimit/memory-store)
            decide #(ratelimit/limit! [policy] store % 0)]
        (is (= [] (:results (decide {:uri "/page" :headers {"x-api-key" "a"}}))))
        (is (= [] (:results (decide {:uri "/api"}))) "no key, so not counted")
        (is (= 5 (-> (decide {:uri "/api" :request-method :post :headers {"x-api-key" "a"}})
                     :results first :remaining)))
        (is (= 4 (-> (decide {:uri "/api" :request-method :get :headers {"x-api-key" "a"}})
                     :results first :remaining)))))))

(deftest responses
  (let [decision {:results [{:name "burst" :quota 3 :window 1 :allowed? false
                             :remaining 0 :retry-after 0.2 :refill-after 0.2}
                            {:name "daily" :quota 10 :window 86400 :allowed? true
                             :remaining 7 :refill-after 4000.5}]
                  :limited ["burst"]}]
    (is (= "1" (ratelimit/retry-after decision)))
    (is (= {:status  429
            :headers {"Retry-After"   "1"
                      "Cache-Control" "no-store"
                      "Content-Type"  "text/plain;charset=utf-8"}
            :body    "Too many requests. Try again in 1 second."}
           (ratelimit/too-many-requests decision)))
    (is (= {"RateLimit-Policy" "\"burst\";q=3;w=1, \"daily\";q=10;w=86400"
            "RateLimit"        "\"burst\";r=0;t=1, \"daily\";r=7;t=4001"}
           (ratelimit/headers decision :draft-11)))
    (testing "waiting won't help"
      (is (= {:status  429
              :headers {"Cache-Control" "no-store"
                        "Content-Type"  "text/plain;charset=utf-8"}
              :body    "Too many requests."}
             (ratelimit/too-many-requests {:results [{:allowed? false}]
                                           :limited ["x"]}))))
    (testing "a window that isn't whole seconds has no w"
      (is (= "\"x\";q=5" (get (ratelimit/headers {:results [{:name "x" :quota 5 :window 0.5
                                                               :remaining 5}]}
                                                  :draft-11)
                              "RateLimit-Policy"))))))
