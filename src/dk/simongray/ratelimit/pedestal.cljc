(ns dk.simongray.ratelimit.pedestal
  "A Pedestal interceptor that limits the requests of each client.

  The interceptor is a map, so the library doesn't depend on Pedestal. A
  route takes it as it is. Elsewhere, e.g. among the interceptors of a
  service, pass it to io.pedestal.interceptor/interceptor first."
  (:require [dk.simongray.ratelimit.impl.adapter :as adapter]))

(defn rate-limit
  "A Pedestal interceptor that applies the `policies` to the request, with
  the `opts` below.

  On enter, it puts the decision of limit! in the request, under
  :dk.simongray.ratelimit/decision. When a policy refuses the request, it
  sets the response to a 429, which ends the chain. On leave, it adds the
  RateLimit fields when the :headers option asks for them.

  The `opts` are those of the Ring middleware, wrap-rate-limit: :store,
  :clock, :too-many-requests, :on-limit, :dry-run? and :headers. It throws
  when a policy or an option is wrong."
  ([policies]
   (rate-limit policies {}))
  ([policies opts]
   (let [decide (adapter/decider policies opts)]
     {:name  ::rate-limit
      :enter (fn [{:keys [request] :as ctx}]
               (let [[request decision] (decide request)]
                 (cond-> (assoc ctx :request request)
                   (adapter/refuse? opts decision)
                   (assoc :response (adapter/refusal opts request decision)))))
      :leave (fn [{:keys [request response] :as ctx}]
               (cond-> ctx
                 response
                 (assoc :response
                        (adapter/finish opts
                                        (:dk.simongray.ratelimit/decision request)
                                        response))))})))
