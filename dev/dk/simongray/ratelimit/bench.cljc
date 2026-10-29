(ns dk.simongray.ratelimit.bench
  "Benchmarks of the arithmetic, the store and whole requests, on the JVM
  by criterium and in Node by the clock, and flame graphs of the JVM by
  clj-async-profiler.

      clojure -M:bench -m dk.simongray.ratelimit.bench
      clojure -M:cljs release bench && node target/bench.js

  In a REPL with the :bench alias, (run) gives the mean of each case in
  microseconds, and (profile :limit! 10) the file of a flame graph."
  (:require #?@(:clj [[clj-async-profiler.core :as prof]
                      [criterium.core :as criterium]])
            [dk.simongray.ratelimit :as ratelimit]
            [dk.simongray.ratelimit.ring :as ratelimit.ring]))

(def roomy
  "A quota that the benchmarks never use up."
  1000000000)

(defn fingerprint
  "The client of the `request` as DanNet counts it: its address behind
  one proxy, with its User-Agent and Accept-Language."
  [{:keys [headers] :as request}]
  [(ratelimit/client-address request {:proxies 1})
   (get headers "user-agent")
   (get headers "accept-language")])

(def policies
  "Two policies like DanNet's, which never run out."
  [{:name   "pages"
    :quota  roomy
    :window 60
    :key    fingerprint
    :when   #(re-find #"^/dannet/(data|external|sparql)" (:uri %))}
   {:name   "sparql"
    :quota  roomy
    :window 60
    :key    fingerprint
    :when   #(re-find #"^/dannet/sparql" (:uri %))}])

(def request
  "A Ring request from a browser behind one proxy."
  {:uri         "/dannet/data/synset-1"
   :remote-addr "172.18.0.3"
   :headers     {"x-forwarded-for" "203.0.113.7"
                 "user-agent"      "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"
                 "accept-language" "da-DK,da;q=0.9"}})

(def store
  "The store of the benchmarks."
  (ratelimit/memory-store))

(def refused
  "A store in which the key of the request has used its quota."
  (doto (ratelimit/memory-store)
    (ratelimit/consume! {:name "tight" :quota 1 :window 3600} :k 1 0)))

(def decision
  "A decision with two policies, one of which refused."
  {:results [{:name "burst" :quota 3 :window 1 :allowed? false
              :remaining 0 :retry-after 0.2 :refill-after 0.2}
             {:name "daily" :quota 10 :window 86400 :allowed? true
              :remaining 7 :refill-after 4000.5}]
   :limited ["burst"]})

(def app
  "Ring middleware with the policies, around a handler that does nothing."
  (ratelimit.ring/wrap-rate-limit (constantly {:status 200 :headers {}})
                                  policies
                                  {:clock   (constantly 0)
                                   :headers :draft-11}))

(def cases
  "Each case by name, as a function to time."
  {:consume-gcra     #(ratelimit/consume {:quota 400 :window 60} 30000.0 0 1)
   :consume-fixed    #(ratelimit/consume {:quota 400 :window 60 :algorithm :fixed-window}
                                         [0 100] 0 1)
   :consume-sliding  #(ratelimit/consume {:quota 400 :window 60 :algorithm :sliding-window}
                                         [0 100 50] 30000 1)
   :consume!         #(ratelimit/consume! store {:quota roomy :window 60} :k 1 0)
   :consume!-refused #(ratelimit/consume! refused {:name "tight" :quota 1 :window 3600} :k 1 0)
   :limit!           #(ratelimit/limit! policies store request 0)
   :address-ipv4     #(ratelimit/client-address {:remote-addr "203.0.113.7"})
   :address-ipv6     #(ratelimit/client-address {:remote-addr "2001:db8:cafe:17:a:b:c:d"})
   :address-proxy    #(ratelimit/client-address request {:proxies 1})
   :headers          #(ratelimit/headers decision :draft-11)
   :429              #(ratelimit/too-many-requests decision)
   :middleware       #(app request)})

#?(:cljs
   (defn- mean
     "The mean time of a call of `f` in seconds, over a second of calls
     after a second of warming up."
     [f]
     (let [now   #(js/performance.now)
           calls (fn [ms]
                   (let [end (+ (now) ms)]
                     (loop [n 0]
                       (if (< (now) end)
                         (do (dotimes [_ 100] (f)) (recur (+ n 100)))
                         n))))]
       (calls 1000)
       (let [start (now)
             n     (calls 1000)]
         (/ (- (now) start) n 1000)))))

(defn run
  "The mean time of each case, or of those named `ks`, in microseconds."
  ([]
   (run (keys cases)))
  ([ks]
   (into (sorted-map)
         (for [k    ks
               :let [mean #?(:clj  (first (:mean (criterium/quick-benchmark* (cases k) {})))
                             :cljs (mean (cases k)))]]
           [k (/ (Math/round (* mean 1e9)) 1000.0)]))))

#?(:clj
   (defn profile
     "The file of a flame graph of the case `k`, run for `seconds` by
     clj-async-profiler."
     [k seconds]
     (let [f   (cases k)
           end (+ (System/currentTimeMillis) (* 1000 seconds))]
       (prof/profile (while (< (System/currentTimeMillis) end) (f))))))

(defn -main
  "Print the mean time of each case, or of those named in `args`."
  [& args]
  (doseq [[k us] (if (seq args) (run (map keyword args)) (run))]
    (println (str (name k) (apply str (repeat (- 18 (count (name k))) " ")) us " µs")))
  #?(:clj (shutdown-agents)))

(comment
  (run)
  (run [:limit! :middleware])
  (profile :limit! 10)
  #_.)
