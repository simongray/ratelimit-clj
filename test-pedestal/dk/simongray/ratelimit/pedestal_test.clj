(ns dk.simongray.ratelimit.pedestal-test
  "The interceptor in the interceptor chain of Pedestal 0.8, run with the
  alias :pedestal."
  (:require [clojure.test :refer [deftest is testing]]
            [dk.simongray.ratelimit :as ratelimit]
            [dk.simongray.ratelimit.pedestal :as ratelimit.pedestal]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.interceptor.chain :as chain]))

;; as io.pedestal.http.response/terminate-when-response, which Pedestal's
;; servlet and http-kit adapters add to the chain
(defn- response?
  "Whether the context `ctx` has a response."
  [{:keys [response] :as ctx}]
  (and (map? response) (pos-int? (:status response))))

(def handler
  "An interceptor that answers with the decision in the request."
  {:name  ::handler
   :enter (fn [{:keys [request] :as ctx}]
            (assoc ctx :response {:status  200
                                  :headers {}
                                  :body    (::ratelimit/decision request)}))})

(defn- run
  "The response of the chain of `interceptors` to the `request`."
  [request & interceptors]
  (-> {:request request}
      (chain/terminate-when response?)
      (chain/enqueue (map interceptor/interceptor interceptors))
      chain/execute
      :response))

(deftest chain
  (let [rate-limit (ratelimit.pedestal/rate-limit [{:name "pages" :quota 1 :window 60}]
                                                  {:clock   (constantly 0)
                                                   :headers :draft-11})
        request    {:remote-addr "203.0.113.7"}
        response   (run request rate-limit handler)]
    (is (= 200 (:status response)))
    (is (= ["pages"] (map :name (:results (:body response)))))
    (is (= "\"pages\";r=0;t=60" (get-in response [:headers "RateLimit"])))
    (testing "a 429 ends the chain before the handler"
      (let [{:keys [status headers body]} (run request rate-limit handler)]
        (is (= 429 status))
        (is (= "60" (get headers "Retry-After")))
        (is (= "\"pages\";r=0;t=60" (get headers "RateLimit")))
        (is (string? body))))))
