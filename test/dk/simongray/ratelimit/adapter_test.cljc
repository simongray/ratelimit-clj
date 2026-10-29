(ns dk.simongray.ratelimit.adapter-test
  "The Ring middleware and the Pedestal interceptor, called as Ring and
  Pedestal call them."
  (:require [clojure.test :refer [deftest is testing]]
            [dk.simongray.ratelimit :as ratelimit]
            [dk.simongray.ratelimit.pedestal :as ratelimit.pedestal]
            [dk.simongray.ratelimit.ring :as ratelimit.ring]))

(def policies
  "Two requests a second."
  [{:name "pages" :quota 2 :window 1}])

(defn handler
  "A Ring handler that answers with the decision in the `request`, and
  with a RateLimit header of its own on /own."
  ([request]
   {:status  200
    :headers (if (= "/own" (:uri request)) {"ratelimit" "own"} {})
    :body    (::ratelimit/decision request)})
  ([request respond _]
   (respond (handler request))))

(defn- run-async
  "The response of the asynchronous Ring `handler` to the `request`."
  [handler request]
  (let [result (atom nil)]
    (handler request #(reset! result %) #(reset! result {:raised %}))
    @result))

(def request
  {:uri "/" :remote-addr "203.0.113.7"})

(deftest ring
  (doseq [call [#(%1 %2) run-async]]
    (let [app (ratelimit.ring/wrap-rate-limit handler policies
                                              {:clock (constantly 0)})]
      (is (= [200 200 429] (map :status (repeatedly 3 #(call app request)))))
      (is (= {:results [{:name         "pages"
                         :quota        2
                         :window       1
                         :key          "198.51.100.1"
                         :allowed?     true
                         :remaining    1
                         :reset-after  0.5
                         :refill-after 0.5}]}
             (:body (call app (assoc request :remote-addr "198.51.100.1")))))
      (is (= {:status  429
              :headers {"Retry-After"   "1"
                        "Cache-Control" "no-store"
                        "Content-Type"  "text/plain;charset=utf-8"}
              :body    "Too many requests. Try again in 1 second."}
             (call app request))))))

(deftest ring-options
  (testing ":headers on every response, except those the handler set"
    (let [app (ratelimit.ring/wrap-rate-limit handler policies
                                              {:clock   (constantly 0)
                                               :headers :draft-11})]
      (is (= {"RateLimit-Policy" "\"pages\";q=2;w=1"
              "RateLimit"        "\"pages\";r=1;t=1"}
             (:headers (app request))))
      (is (= {"ratelimit"        "own"
              "RateLimit-Policy" "\"pages\";q=2;w=1"}
             (:headers (app (assoc request :uri "/own")))))
      (is (= "\"pages\";r=0;t=1" (get-in (app request) [:headers "RateLimit"])))))
  (testing ":dry-run? and :on-limit"
    (let [limited (atom [])
          app     (ratelimit.ring/wrap-rate-limit handler policies
                                                  {:clock    (constantly 0)
                                                   :dry-run? true
                                                   :on-limit #(swap! limited conj
                                                                     (:limited %2))})]
      (is (= [200 200 200] (map :status (repeatedly 3 #(app request)))))
      (is (= [["pages"]] @limited))))
  (testing ":too-many-requests"
    (let [app (ratelimit.ring/wrap-rate-limit handler policies
                                              {:clock             (constantly 0)
                                               :too-many-requests (fn [_ decision]
                                                                    {:status 503
                                                                     :body   (:limited decision)})})]
      (is (= {:status 503 :body ["pages"]} (last (repeatedly 3 #(app request)))))))
  (testing ":store"
    (let [store (ratelimit/memory-store)
          app   #(ratelimit.ring/wrap-rate-limit handler policies
                                                 {:clock (constantly 0) :store store})]
      ((app) request)
      ((app) request)
      (is (= 429 (:status ((app) request))) "two middlewares share it")))
  (testing "bad policies and options throw when the middleware is made"
    (doseq [[policies opts] [[[{:quota 0 :window 1}] {}]
                             [[{:quota 1}] {}]
                             [[{:quota 1 :window 1 :algorithm :leaky}] {}]
                             [[{:quota 1 :window 1 :burst 1.5}] {}]
                             [[{:quota 1 :window 1 :name "\n"}] {}]
                             [[{:quota 1 :window 1} {:quota 2 :window 1}] {}]
                             [[{:quota 1 :window :fortnight}] {}]
                             [[{:quota 1 :window 1}] {:headers :draft-99}]
                             [[{:quota 1 :window 1}] {:proxies "1"}]]]
      (is (thrown? #?(:clj Exception :cljs js/Error)
                   (ratelimit.ring/wrap-rate-limit handler policies opts))))))

(defn- through-proxy
  "The statuses of requests to the `app` from clients at the `addresses`,
  through one proxy."
  [app addresses]
  (mapv #(:status (app {:remote-addr "10.0.0.2"
                        :headers     {"x-forwarded-for" %}}))
        addresses))

(deftest proxies
  (let [clock {:clock (constantly 0)}]
    (testing "without :proxies, the clients share the proxy's limit"
      (is (= [200 200 429]
             (through-proxy (ratelimit.ring/wrap-rate-limit handler policies
                                                            (assoc clock :proxies 0))
                            ["203.0.113.1" "203.0.113.2" "198.51.100.3"]))))
    (testing "with :proxies, each client has its own"
      (is (= [200 200 200 200 429]
             (through-proxy (ratelimit.ring/wrap-rate-limit handler policies
                                                            (assoc clock :proxies 1))
                            ["203.0.113.1" "203.0.113.2" "198.51.100.3"
                             "203.0.113.1" "203.0.113.1"]))))
    (testing "a policy's own :key stays"
      (is (= [200 200 429]
             (through-proxy (ratelimit.ring/wrap-rate-limit
                             handler
                             [{:quota 2 :window 1 :key (constantly :everyone)}]
                             (assoc clock :proxies 1))
                            ["203.0.113.1" "203.0.113.2" "198.51.100.3"]))))))

#?(:clj
   (deftest proxy-warning
     (let [warnings (fn [policies opts]
                      (let [err (java.io.StringWriter.)]
                        (binding [*err* err]
                          (through-proxy (ratelimit.ring/wrap-rate-limit
                                          handler policies
                                          (assoc opts :clock (constantly 0)))
                                         ["203.0.113.1" "203.0.113.2"]))
                        (count (re-seq #"ratelimit-clj:" (str err)))))]
       (is (= 1 (warnings policies {})) "once, not for every request")
       (is (= 0 (warnings policies {:proxies 0})))
       (is (= 0 (warnings policies {:proxies 1})))
       (is (= 0 (warnings [{:quota 2 :window 1 :key :uri}] {}))
           "no policy counts by the default key"))))

(deftest one-policy
  (let [app (ratelimit.ring/wrap-rate-limit handler {:quota 1 :window :minute}
                                            {:clock (constantly 0)})]
    (is (= [200 429] (map :status (repeatedly 2 #(app request)))))
    (is (= "60" (get-in (app request) [:headers "Retry-After"])))))

(deftest pedestal
  (let [{:keys [enter leave]} (ratelimit.pedestal/rate-limit policies
                                                             {:clock   (constantly 0)
                                                              :headers :draft-11})
        run                   (fn []
                                (let [ctx (enter {:request request})]
                                  (leave (cond-> ctx
                                           (not (:response ctx))
                                           (assoc :response {:status 200 :headers {}})))))]
    (is (= [200 200 429] (map (comp :status :response) (repeatedly 3 run))))
    (is (= "\"pages\";r=0;t=1" (get-in (run) [:response :headers "RateLimit"])))
    (is (= ["pages"] (get-in (enter {:request request})
                             [:request ::ratelimit/decision :limited])))))
