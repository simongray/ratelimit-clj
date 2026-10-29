(ns ^:no-doc dk.simongray.ratelimit.impl.memory
  "The memory store: a ConcurrentHashMap on the JVM and a map in a volatile
  in JavaScript, which has a single thread.

  An entry whose state is the same as a new key's holds nothing, so the
  store drops it: at once when an update leads to it, or else in a sweep.
  The updates run the sweep themselves, so the store needs no thread."
  (:require [dk.simongray.ratelimit.store :as store])
  #?(:clj (:import [java.util Map$Entry]
                   [java.util.concurrent ConcurrentHashMap]
                   [java.util.concurrent.atomic AtomicLong]
                   [java.util.function BiFunction])))

#?(:clj (set! *warn-on-reflection* true))

(deftype Entry [state ^double expires])

(defn- expired?
  "Whether the `entry` holds nothing at `now`."
  [^Entry entry now]
  (<= (.-expires entry) now))

(defn- entry
  "The entry of the `state` that expires at `expires`, or nil when it
  holds nothing at `now`."
  [state expires now]
  (when (< now expires)
    (Entry. state expires)))

#?(:clj
   (defn- sweep!
     "Remove the entries that hold nothing at `now` from the `entries`."
     [^ConcurrentHashMap entries now]
     (doseq [^Map$Entry e (.entrySet entries)]
       (when (expired? (.getValue e) now)
         (.remove entries (.getKey e) (.getValue e)))))

   :cljs
   (defn- sweep
     "The `entries` without those that hold nothing at `now`."
     [entries now]
     (persistent! (reduce-kv (fn [m k e]
                               (cond-> m (expired? e now) (dissoc! k)))
                             (transient entries)
                             entries))))

#?(:clj
   (deftype MemoryStore [^ConcurrentHashMap entries
                         ^AtomicLong next-sweep
                         ^long sweep-ms]
     store/Store
     (-update! [_ k now f]
       (let [due (.get next-sweep)]
         (when (and (<= due (long now))
                    (.compareAndSet next-sweep due (+ (long now) sweep-ms)))
           (sweep! entries now)))
       (let [result (volatile! nil)]
         (.compute entries k
                   (reify BiFunction
                     (apply [_ _ old]
                       (let [[r expires] (f (some-> ^Entry old .-state))]
                         (vreset! result r)
                         (entry (:state r) expires now)))))
         @result)))

   :cljs
   (deftype MemoryStore [entries next-sweep sweep-ms]
     store/Store
     (-update! [_ k now f]
       (when (<= @next-sweep now)
         (vreset! next-sweep (+ now sweep-ms))
         (vswap! entries sweep now))
       (let [old         (get @entries k)
             [r expires] (f (some-> ^Entry old .-state))]
         (if-let [e (entry (:state r) expires now)]
           (vswap! entries assoc k e)
           (vswap! entries dissoc k))
         r))))

(defn memory-store
  "A new memory store that sweeps out old entries every `sweep-every`
  seconds."
  [sweep-every]
  (let [sweep-ms (long (* 1000 sweep-every))]
    #?(:clj  (MemoryStore. (ConcurrentHashMap.) (AtomicLong. 0) sweep-ms)
       :cljs (MemoryStore. (volatile! {}) (volatile! 0) sweep-ms))))

(defn size
  "The number of entries in the memory `store`."
  [^MemoryStore store]
  #?(:clj  (.size ^ConcurrentHashMap (.-entries store))
     :cljs (count @(.-entries store))))
