(ns dk.simongray.ratelimit.ring
  "Ring middleware that limits the requests of each client before the
  handler gets them."
  (:require [dk.simongray.ratelimit.impl.adapter :as adapter]))

(defn wrap-rate-limit
  "Ring middleware that applies the `policies` to each request before the
  `handler` gets it, with the `opts` below. The `policies` can also be a
  single policy.

  The handler finds the decision of limit! in the request, under
  :dk.simongray.ratelimit/decision. When a policy refuses the request, the
  middleware answers 429 itself.

  The `opts` are:

  - :proxies, the number of proxies of your own in front of the server.
    Policies without a :key then count each client by the address that
    the outermost proxy added to X-Forwarded-For. Without :proxies, the
    middleware warns once when a request has that field.
  - :store, where the state of each key lives, by default a new memory
    store
  - :clock, a function that returns the time in milliseconds, by default
    the system clock
  - :too-many-requests, a function of the request and the decision that
    returns the response to a refused request, e.g. a 429 of your own. By
    default it's too-many-requests.
  - :on-limit, a function of the request and the decision that runs for
    each refused request, e.g. to log it
  - :dry-run?, to run :on-limit but let refused requests through, e.g. to
    try out a new limit
  - :headers, a draft of the RateLimit fields, such as :draft-11, to add
    them to every response

  It takes a synchronous or an asynchronous handler, and throws when a
  policy or an option is wrong."
  ([handler policies]
   (wrap-rate-limit handler policies {}))
  ([handler policies opts]
   (let [decide (adapter/decider policies opts)]
     (fn
       ([request]
        (let [[request decision] (decide request)]
          (adapter/finish opts decision
                          (if (adapter/refuse? opts decision)
                            (adapter/refusal opts request decision)
                            (handler request)))))
       ([request respond raise]
        (let [[request decision] (decide request)
              respond            #(respond (adapter/finish opts decision %))]
          (if (adapter/refuse? opts decision)
            (respond (adapter/refusal opts request decision))
            (handler request respond raise))))))))
