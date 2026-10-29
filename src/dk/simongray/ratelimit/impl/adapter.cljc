(ns ^:no-doc dk.simongray.ratelimit.impl.adapter
  "What the Ring middleware and the Pedestal interceptor share: the
  decision for a request, the answer when it's refused, and the headers of
  a response."
  (:require [dk.simongray.ratelimit.impl.address :as address]
            [dk.simongray.ratelimit.impl.decision :as decision]
            [dk.simongray.ratelimit.impl.memory :as memory]
            [dk.simongray.ratelimit.impl.response :as response]))

#?(:clj (set! *warn-on-reflection* true))

(defn- validate-opts
  "The `opts` as they are. It throws when the :headers draft is unknown,
  or :proxies isn't a number of proxies."
  [{:keys [headers proxies] :as opts}]
  (when (and headers (not (response/drafts headers)))
    (throw (ex-info (str "Unknown draft for :headers: " headers)
                    {:headers headers :drafts response/drafts})))
  (when (and (some? proxies) (not (nat-int? proxies)))
    (throw (ex-info ":proxies is the number of proxies, e.g. 1"
                    {:proxies proxies})))
  opts)

(defn- with-proxies
  "The `policies`, where those without a :key count clients by the address
  that the outermost of the :proxies of the `opts` added, if it has any."
  [policies {:keys [proxies] :as opts}]
  (if proxies
    (let [k #(address/client-address % {:proxies proxies})]
      (mapv #(cond-> % (nil? (:key %)) (assoc :key k)) policies))
    policies))

(defn- forwarded?
  "Whether the Ring `request` has a field that proxies add to."
  [{:keys [headers] :as request}]
  (or (contains? headers "x-forwarded-for")
      (contains? headers "forwarded")))

(def proxy-warning
  "The warning for a request through a proxy without the :proxies option."
  (str "ratelimit-clj: a request has an X-Forwarded-For or Forwarded field,"
       " but :proxies isn't set, so every client behind the proxy shares one"
       " limit. Set :proxies to the number of proxies in front of the server,"
       " or to 0 if there are none."))

(defn- warn!
  "Print the warning `s` to standard error, or to the console in
  JavaScript."
  [s]
  #?(:clj  (binding [*out* *err*] (println s))
     :cljs (js/console.warn s)))

(defn decider
  "A function that decides a Ring request by the `policies` and the
  `opts`. It returns the request with the decision in it, and the
  decision.

  When a policy refuses the request, it calls the :on-limit of the opts,
  also in a dry run. The first time a request comes through a proxy while
  a policy counts clients by the default key and :proxies isn't set, it
  prints a warning."
  [policies {:keys [store clock on-limit proxies] :as opts}]
  (let [policies (-> (decision/as-policies policies)
                     decision/validate
                     (with-proxies (validate-opts opts)))
        store    (or store (memory/memory-store 60))
        clock    (or clock decision/now)
        warn?    (and (nil? proxies) (some (comp nil? :key) policies))
        warned   (atom false)]
    (fn [request]
      (when (and warn?
                 (forwarded? request)
                 (compare-and-set! warned false true))
        (warn! proxy-warning))
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
