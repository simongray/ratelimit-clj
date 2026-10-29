(ns dk.simongray.ratelimit.generative-test
  "Properties that hold for any policy and sequence of requests, and for
  any request, tried on random ones in both Clojure and ClojureScript."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [dk.simongray.ratelimit :as ratelimit]
            [dk.simongray.ratelimit.generators :as g]))

(defn- run
  "The time, cost and result of each of the `requests` by one key of the
  `policy`, and the state after them."
  [policy requests]
  (reduce (fn [{:keys [now state] :as acc} [dt cost]]
            (let [now    (+ now dt)
                  result (ratelimit/consume policy state now cost)]
              (-> acc
                  (assoc :now now :state (:state result))
                  (update :results conj [now cost result]))))
          {:now 0 :state nil :results []}
          requests))

(defn- units
  "The units of the results that passed between the times `from` and
  `to`."
  [results from to]
  (transduce (comp (filter (fn [[now _ result]]
                             (and (<= from now to) (:allowed? result))))
                   (map second))
             + results))

(defspec gcra-allows-no-more-than-the-burst-plus-the-rate 1000
  (prop/for-all [policy   (gen/fmap #(assoc % :algorithm :gcra) g/policy)
                 requests g/requests]
    (let [{:keys [results]} (run policy requests)
          {:keys [quota window burst]} policy
          times             (map first results)]
      (every? (fn [[from to]]
                (<= (units results from to)
                    (+ (or burst quota)
                       (* quota (/ (- to from) (* 1000.0 window)))
                       1e-6)))
              (for [from times, to times :when (<= from to)] [from to])))))

(defspec a-fixed-window-allows-no-more-than-the-quota 1000
  (prop/for-all [policy   (gen/fmap #(assoc % :algorithm :fixed-window) g/policy)
                 requests g/requests]
    (let [{:keys [results]} (run policy requests)
          w                 (* 1000 (:window policy))]
      (->> (filter (comp :allowed? last) results)
           (group-by #(Math/floor (/ (first %) w)))
           vals
           (every? #(<= (reduce + (map second %)) (:quota policy)))))))

(defspec retry-after-is-exact 1000
  (prop/for-all [policy   g/policy
                 requests g/requests
                 cost     (gen/elements [1 2 5])]
    (let [{:keys [now state]} (run policy requests)
          result              (ratelimit/consume policy state now cost)
          ms                  (some-> (:retry-after result) (* 1000))]
      (or (:allowed? result)
          (nil? ms)
          (and (:allowed? (ratelimit/consume policy state (+ now ms 1e-3) cost))
               (or (< ms 1)
                   (not (:allowed? (ratelimit/consume policy state (+ now ms -1) cost)))))))))

(defspec a-cost-of-0-takes-nothing 1000
  (prop/for-all [policy   g/policy
                 requests g/requests
                 later    (gen/choose 0 5000)
                 cost     (gen/elements [1 2 5])]
    (let [{:keys [now state]} (run policy requests)
          peek                (ratelimit/consume policy state now 0)
          then                #(select-keys (ratelimit/consume policy % (+ now later) cost)
                                            [:allowed? :remaining])]
      (and (:allowed? peek)
           (= (then state) (then (:state peek)))))))

(defspec a-full-key-is-a-new-key 1000
  (prop/for-all [policy   g/policy
                 requests g/requests
                 cost     (gen/elements [0 1 2 5])]
    (let [{:keys [now state]} (run policy requests)
          later               (+ now (* 1000 (:reset-after (ratelimit/consume policy state now 0)))
                                 1e-3)
          at                  #(select-keys (ratelimit/consume policy % later cost)
                                            [:allowed? :remaining])]
      (= (at nil) (at state)))))

(defspec refill-after-is-when-units-come-back 1000
  (prop/for-all [policy   g/policy
                 requests g/requests]
    (let [{:keys [now state]}               (run policy requests)
          {:keys [remaining refill-after]} (ratelimit/consume policy state now 0)
          at                                #(:remaining (ratelimit/consume policy state % 0))]
      (or (nil? refill-after)
          (and (< remaining (at (+ now (* 1000 refill-after) 1e-3)))
               (or (< (* 1000 refill-after) 1)
                   (= remaining (at (+ now (* 1000 refill-after) -1)))))))))

(defspec no-request-throws 2000
  (prop/for-all [request g/request
                 proxies (gen/choose 0 3)
                 header  (gen/elements ["x-forwarded-for" "forwarded"])
                 prefix  (gen/choose 0 128)]
    (let [opts     {:proxies proxies :header header :prefix prefix}
          policies [{:name "a" :quota 5 :window 1 :key #(ratelimit/client-address % opts)}]
          address  (ratelimit/client-address request opts)
          decision (ratelimit/limit! policies (ratelimit/memory-store) request 0)]
      (and (or (nil? address) (string? address))
           (vector? (:results decision))))))
