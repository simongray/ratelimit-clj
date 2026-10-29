(ns ^:no-doc dk.simongray.ratelimit.impl.adapter
  "What the Ring middleware and the Pedestal interceptor share: the
  decision for a request, the answer when it's refused, and the headers of
  a response."
  (:require [dk.simongray.ratelimit.impl.decision :as decision]
            [dk.simongray.ratelimit.impl.memory :as memory]
            [dk.simongray.ratelimit.impl.response :as response]))

#?(:clj (set! *warn-on-reflection* true))

(defn- validate-opts
  "The `opts` as they are. It throws when the :headers draft is unknown."
  [{:keys [headers] :as opts}]
  (when (and headers (not (response/drafts headers)))
    (throw (ex-info (str "Unknown draft for :headers: " headers)
                    {:headers headers :drafts response/drafts})))
  opts)

(defn decider
  "A function that decides a Ring request by the `policies` and the
  `opts`. It returns the request with the decision in it, and the
  decision.

  When a policy refuses the request, it calls the :on-limit of the opts,
  also in a dry run."
  [policies {:keys [store clock on-limit] :as opts}]
  (decision/validate policies)
  (validate-opts opts)
  (let [store (or store (memory/memory-store 60))
        clock (or clock decision/now)]
    (fn [request]
      (let [d       (decision/limit! policies store request (clock))
            request (assoc request :dk.simongray.ratelimit/decision d)]
        (when (and on-limit (:limited d))
          (on-limit request d))
        [request d]))))

(defn refuse?
  "Whether to refuse a request with the `decision`, by the `opts`: when a
  policy refused it, unless it's a dry run."
  [{:keys [dry-run?] :as opts} decision]
  (and (:limited decision) (not dry-run?)))

(defn refusal
  "The response to the refused Ring `request` with the `decision`: by the
  :too-many-requests of the `opts`, or else a 429."
  [{:keys [too-many-requests] :as opts} request decision]
  (if too-many-requests
    (too-many-requests request decision)
    (response/too-many-requests decision)))

(defn finish
  "The Ring `response` to a request with the `decision`, with the RateLimit
  fields of the :headers draft of the `opts`, when it has one."
  [{:keys [headers] :as opts} decision response]
  (cond-> response
    headers (response/with-headers (response/headers decision headers))))
