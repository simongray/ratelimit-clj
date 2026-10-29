(ns dk.simongray.ratelimit
  "Limits on how often each client can make requests.

  A policy is a map of a :name, a :quota of units and a :window, like an
  item of the RateLimit-Policy field. The window is a number of seconds,
  or :second, :minute, :hour or :day. A policy also takes:

  - :burst, the most units at once with GCRA, by default the quota
  - :algorithm, which is :gcra by default, :fixed-window or
    :sliding-window
  - :key, a function of the request that returns the client, by default
    client-address. A request whose key is nil isn't counted.
  - :when, a predicate on the requests that the policy counts, by default
    all of them
  - :cost, a function of the request that returns its units, by default 1

  Durations in results and decisions are in seconds. The functions take
  times in milliseconds, as your clock returns them."
  (:require [dk.simongray.ratelimit.impl.address :as address]
            [dk.simongray.ratelimit.impl.algorithm :as algorithm]
            [dk.simongray.ratelimit.impl.decision :as decision]
            [dk.simongray.ratelimit.impl.memory :as memory]
            [dk.simongray.ratelimit.impl.response :as response]))

(defn consume
  "The result of taking `cost` units at `now` from a key of the `policy`
  with the `state`, which is nil for a new key.

      (consume {:quota 400 :window 60} nil 0 1)
      ;; => {:allowed?     true
      ;;     :remaining    399
      ;;     :reset-after  0.15
      ;;     :refill-after 0.15
      ;;     :state        150.0}

  The result has the number of units :remaining, the new :state and these
  durations:

  - :retry-after, until a refused request could pass. A request that
    costs more than the policy ever allows has none.
  - :refill-after, until :remaining next goes up, unless the key has all
    of its units
  - :reset-after, until the key has all of its units again

  A cost of 0 checks the key without taking anything."
  [policy state now cost]
  (algorithm/consume policy state now cost))

(defn memory-store
  "A new store that keeps the state of each key in memory, with the `opts`
  below.

  It drops a key's entry once the key is back to the state of a new key:
  at once when an update leaves it there, or else in a sweep. Updates run
  the sweep every so often, so the store needs no thread. It counts in
  this process only, so with several servers, a client can use the quota
  on each of them. The `opts` are:

  - :sweep-every, the seconds between sweeps, by default 60"
  ([]
   (memory-store {}))
  ([{:keys [sweep-every] :or {sweep-every 60} :as opts}]
   (memory/memory-store sweep-every)))

(defn consume!
  "The result of taking `cost` units from the key `k` of the `policy` in
  the `store`, at `now` or else at the time on the system clock.

      (consume! (memory-store) {:quota 400 :window 60} \"203.0.113.7\" 1)
      ;; => {:allowed? true :remaining 399 ...}

  It returns the same result as consume. Policies with different names can
  share a store without mixing their keys."
  ([store policy k cost]
   (consume! store policy k cost (decision/now)))
  ([store policy k cost now]
   (decision/consume! store policy k cost now)))

(defn limit!
  "The decision for the Ring `request` by the `policies`, which take units
  in the `store` at `now` or else at the time on the system clock. The
  `policies` can also be a single policy.

      (limit! {:name \"pages\" :quota 400 :window :minute}
              (memory-store)
              {:remote-addr \"203.0.113.7\"})
      ;; => {:results [{:name        \"pages\"
      ;;                :quota       400
      ;;                :window      60
      ;;                :key         \"203.0.113.7\"
      ;;                :allowed?    true
      ;;                :remaining   399
      ;;                ...}]}

  Each policy whose :when matches the request takes the request's :cost
  from its :key. The decision has a result for each of these policies,
  with the policy's name, quota and window and the key. When a policy
  refuses the request, the decision lists its name under :limited, and the
  request takes nothing from any policy.

  Unlike the middleware and the interceptor, it doesn't check that the
  policies are valid."
  ([policies store request]
   (limit! policies store request (decision/now)))
  ([policies store request now]
   (decision/limit! (decision/as-policies policies) store request now)))

(defn client-address
  "The address of the client of the Ring `request`, with the `opts` below.

      (client-address {:remote-addr \"2001:db8:cafe:17:a:b:c:d\"})
      ;; => \"2001:db8:cafe:17::/64\"

      (client-address {:remote-addr \"10.0.0.2\"
                       :headers     {\"x-forwarded-for\" \"203.0.113.7\"}}
                      {:proxies 1})
      ;; => \"203.0.113.7\"

  An IPv6 address counts by its first 64 bits, since a host often has a
  whole /64. An IPv4-mapped address counts as IPv4, and one that doesn't
  parse is used as it is. The `opts` are:

  - :proxies, the number of proxies of your own in front of the server,
    by default 0. The address is then the one that the outermost proxy
    added to the field, or the leftmost one when the field has fewer
    entries than that. Without the field, it's the :remote-addr. Use it
    only when clients can't reach the server around the proxies.
  - :header, the field that the proxies add to: x-forwarded-for by
    default, or forwarded (RFC 7239)
  - :prefix, the bits of an IPv6 address that count, by default 64"
  ([request]
   (address/client-address request))
  ([request opts]
   (address/client-address request opts)))

(defn retry-after
  "The Retry-After value of the `decision` in whole seconds, rounded up,
  or nil when waiting won't help.

      (retry-after {:results [{:allowed? false :retry-after 4.2}]})
      ;; => \"5\"

  It's the longest wait of the policies that refused the request."
  [decision]
  (response/retry-after decision))

(defn headers
  "The RateLimit and RateLimit-Policy fields of the `decision` by `draft`,
  a version of the IETF draft such as :draft-11, or nil when no policy
  applied.

      (headers {:results [{:name \"pages\" :quota 400 :window 60
                           :remaining 12 :refill-after 0.15}]}
               :draft-11)
      ;; => {\"RateLimit-Policy\" \"\\\"pages\\\";q=400;w=60\"
      ;;     \"RateLimit\"        \"\\\"pages\\\";r=12;t=1\"}

  The t of a policy is the time until its available quota next goes up,
  in whole seconds, rounded up. A window that isn't a whole number of
  seconds has no w."
  [decision draft]
  (response/headers decision draft))

(defn too-many-requests
  "The 429 Too Many Requests response to the request of the `decision`.

      (too-many-requests {:results [{:allowed? false :retry-after 4.2}]
                          :limited ...})
      ;; => {:status  429
      ;;     :headers {\"Cache-Control\" \"no-store\"
      ;;               \"Content-Type\"  \"text/plain;charset=utf-8\"
      ;;               \"Retry-After\"   \"5\"}
      ;;     :body    \"Too many requests. Try again in 5 seconds.\"}

  It has a Retry-After field unless waiting won't help. Its Cache-Control
  is no-store, for caches that don't know they must not store a 429."
  [decision]
  (response/too-many-requests decision))

(comment
  (consume {:quota 400 :window 60} nil 0 1)
  (consume {:quota 400 :window 60} 60000.0 0 1)
  (limit! [{:name "pages" :quota 400 :window 60}]
          (memory-store)
          {:remote-addr "203.0.113.7"})
  (client-address {:remote-addr "::ffff:192.0.2.1"})
  #_.)
