(ns ^:no-doc dk.simongray.ratelimit.impl.decision
  "The decision for a request: the result of each policy that applies to
  it, and the policies that refused it."
  (:require [dk.simongray.ratelimit.impl.address :as address]
            [dk.simongray.ratelimit.impl.algorithm :as algorithm]
            [dk.simongray.ratelimit.store :as store]))

#?(:clj (set! *warn-on-reflection* true))

(defn now
  "The time on the system clock in milliseconds."
  []
  #?(:clj  (System/currentTimeMillis)
     :cljs (js/Date.now)))

(defn policy-name
  "The name of the `policy`, which is \"default\" when it has none."
  [policy]
  (:name policy "default"))

(defn- expires
  "The time at which the state of the `result` at `now` is the same as a
  new key's."
  [now result]
  (+ now (* 1000.0 (:reset-after result))))

(defn- update!
  "The result of `f`, a function of the state of the key `k` of the
  `policy` in the `store`, at `now`."
  [store policy k now f]
  (store/-update! store [(policy-name policy) k] now
                  (fn [state]
                    (let [result (f state)]
                      [result (expires now result)]))))

(defn consume!
  "The result of taking `cost` units at `now` from the key `k` of the
  `policy` in the `store`."
  [store policy k cost now]
  (update! store policy k now #(algorithm/consume policy % now cost)))

(defn- refund!
  "The result of giving back the `cost` units that the key `k` of the
  `policy` took at `now` in the `store`."
  [store policy k cost now]
  (update! store policy k now #(algorithm/refund policy % now cost)))

(defn- one
  "The cost of a request when the policy has no :cost."
  [_]
  1)

(defn- applied
  "The `policy` with the key and the cost of the Ring `request`, or nil
  when the policy doesn't apply to it."
  [{applies? :when key-fn :key cost-fn :cost
    :or      {applies? any? key-fn address/client-address cost-fn one}
    :as      policy}
   request]
  (when (applies? request)
    (when-some [k (key-fn request)]
      {:policy policy :key k :cost (cost-fn request)})))

(defn- take!
  "The policies that apply to the Ring `request`, under :taken, and the
  names of those that refused it, under :limited.

  In turn, each policy takes the request's cost in the `store` at `now`.
  After a refusal, the rest take nothing and only report their state. Each
  policy under :taken has the cost that it took and its result."
  [policies store request now]
  (transduce (keep #(applied % request))
             (completing
              (fn [{:keys [limited] :as acc} {:keys [policy key cost] :as a}]
                (let [cost   (if (seq limited) 0 cost)
                      result (consume! store policy key cost now)]
                  (cond-> (update acc :taken conj
                                  (assoc a :cost cost :result result))
                    (not (:allowed? result))
                    (update :limited conj (policy-name policy))))))
             {:taken [] :limited []}
             policies))

(defn- report
  "The `result` of the `policy` for the decision, with its name, quota,
  window and the key `k`."
  [{:keys [quota window] :as policy} k result]
  (-> (dissoc result :state)
      (assoc :name   (policy-name policy)
             :quota  quota
             :window (algorithm/window-seconds window)
             :key    k)))

;; a refused request takes nothing from any policy
(defn- settle!
  "The result for the decision of the policy in the `entry` of take!,
  after a refund in the `store` at `now` when the request was `refused?`."
  [store now refused? {:keys [policy key cost result] :as entry}]
  (report policy key
          (if (and refused? (:allowed? result) (pos? cost))
            (refund! store policy key cost now)
            result)))

(defn as-policies
  "The `policies` as a coll, also when it's a single policy map."
  [policies]
  (if (map? policies) [policies] policies))

(defn limit!
  "The decision for the Ring `request` by the `policies`, which take units
  in the `store` at `now`."
  [policies store request now]
  (let [{:keys [taken limited]} (take! policies store request now)
        refused?                (seq limited)]
    (cond-> {:results (mapv #(settle! store now refused? %) taken)}
      refused? (assoc :limited limited))))

(defn- printable?
  "Whether `s` is text that a Structured Field String can hold."
  [s]
  (and (string? s) (re-matches #"[\x20-\x7E]*" s)))

(defn- problem
  "What is wrong with the `policy`, or nil."
  [{:keys [name quota window burst algorithm key when cost]
    :as   policy}]
  (cond
    (not (map? policy))                          "a policy is a map"
    (not (pos-int? quota))                       ":quota is a positive integer"
    (not (or (contains? algorithm/windows window)
             (and (number? window) (pos? window))))
    ":window is a positive number, or :second, :minute, :hour or :day"
    (and (some? burst) (not (pos-int? burst)))   ":burst is a positive integer"
    (not (contains? #{nil :gcra :fixed-window :sliding-window} algorithm))
    ":algorithm is :gcra, :fixed-window or :sliding-window"
    (and (some? name) (not (printable? name)))   ":name is printable ASCII text"
    (some #(and (some? %) (not (ifn? %))) [key when cost])
    ":key, :when and :cost are functions"))

(defn validate
  "The `policies` as they are. It throws when one of them is wrong, or
  when two of them have the same name."
  [policies]
  (doseq [policy policies]
    (when-let [problem (problem policy)]
      (throw (ex-info (str "Not a valid policy: " problem)
                      {:policy policy}))))
  (let [names (map policy-name policies)]
    (when (and (seq names) (not (apply distinct? names)))
      (throw (ex-info "Each policy needs a :name of its own"
                      {:names names}))))
  policies)
