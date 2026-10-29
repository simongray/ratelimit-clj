(ns ^:no-doc dk.simongray.ratelimit.impl.algorithm
  "The arithmetic of the algorithms: how many units a key has, how long it
  waits for more, and its state after taking units or giving them back.

  Each algorithm answers these four questions about a key's state at a
  given time, and consume builds a result from the answers. Times here are
  in milliseconds on the clock, while the durations in a result are in
  seconds.

  Most functions here take the numbers of the policy as `l`, the Limits
  that limits returns for it.")

#?(:clj (set! *warn-on-reflection* true))

(def ^:const epsilon
  "The slack in comparisons of units, against the rounding of doubles."
  1e-9)

(def windows
  "The windows that a policy can name, in seconds."
  {:second 1 :minute 60 :hour 3600 :day 86400})

(defn window-seconds
  "The `window` of a policy in seconds: a number of them, or a name in
  windows."
  [window]
  (get windows window window))

;; The numbers of a policy as primitive fields, read from its map once for
;; each call rather than once for each question
(deftype Limits [algorithm
                 ^double quota
                 ^double capacity
                 ^double window
                 ^double interval])

(defn limits
  "The algorithm of the `policy` and its numbers: the quota, the most units
  at once, the window in milliseconds and, for GCRA, the milliseconds in
  which a key gets a unit back."
  ^Limits [{:keys [algorithm quota window burst] :as policy}]
  (let [algorithm (or algorithm :gcra)
        quota     (double quota)
        window    (* 1000.0 (double (window-seconds window)))]
    (Limits. algorithm
             quota
             (if (and burst (= :gcra algorithm)) (double burst) quota)
             window
             (/ window quota))))

;; GCRA keeps the time at which the key's quota is full again, its
;; theoretical arrival time (TAT), or nil for a new key. Each unit moves
;; it on by the interval, and a request passes when the TAT that it leads
;; to is no more than the burst's worth of intervals ahead.

(defn- gcra-available
  "The units that a GCRA key with the `tat` has at `now`."
  ^double [^Limits l tat ^double now]
  (if tat
    (- (.-capacity l) (/ (max 0.0 (- (double tat) now)) (.-interval l)))
    (.-capacity l)))

(defn- gcra-wait
  "The milliseconds from `now` until a GCRA key with the `tat` has `n`
  units."
  ^double [^Limits l tat ^double now ^double n]
  (let [tat (if tat (double tat) now)]
    (max 0.0 (- tat now (* (- (.-capacity l) n) (.-interval l))))))

(defn- gcra-take
  "The TAT after a key with the `tat` takes `n` units at `now`."
  ^double [^Limits l tat ^double now ^double n]
  (+ (if tat (max (double tat) now) now) (* n (.-interval l))))

(defn- gcra-untake
  "The TAT after a key with the `tat` gives back `n` units."
  [^Limits l tat ^double n]
  (when tat
    (- (double tat) (* n (.-interval l)))))

;; A fixed window keeps the index of the window, counted from the start of
;; the clock, and the units taken in it.

(defn- window-index
  "The index of the window that holds `now`."
  ^long [^Limits l ^double now]
  (long (Math/floor (/ now (.-window l)))))

(defn- window-start
  "The time at which the window with index `k` starts."
  ^double [^Limits l ^long k]
  (* (double k) (.-window l)))

(defn- fixed-count
  "The units taken in the window that holds `now`, by a fixed-window key
  with the `state`."
  ^double [^Limits l [k n :as state] ^double now]
  (if (and state (== (long k) (window-index l now)))
    (double n)
    0.0))

(defn- fixed-wait
  "The milliseconds from `now` until a fixed-window key with the `state`
  has `n` units."
  ^double [^Limits l state ^double now ^double n]
  (if (<= (+ (fixed-count l state now) n) (+ (.-quota l) epsilon))
    0.0
    (- (window-start l (inc (window-index l now))) now)))

(defn- fixed-take
  "The state after a fixed-window key with the `state` takes `n` units at
  `now`."
  [^Limits l state ^double now ^double n]
  [(window-index l now) (+ (fixed-count l state now) n)])

(defn- fixed-untake
  "The state after a fixed-window key with the `state` gives back `n`
  units that it took at `now`."
  [^Limits l [k m :as state] ^double now ^double n]
  (if (and state (== (long k) (window-index l now)))
    [k (max 0.0 (- (double m) n))]
    state))

;; A sliding window counter keeps the index of the current window, the
;; units taken in the window before it and the units taken in it. It
;; counts the units of the window before by the part of it that the
;; sliding window still covers.

(defn- sliding-counts
  "The units that a sliding-window key with the `state` took in the window
  before the one that holds `now`, and in that one."
  [^Limits l [k prev curr :as state] ^double now]
  (if (nil? state)
    [0.0 0.0]
    (let [i (window-index l now)
          k (long k)]
      (cond
        (== k i)       [(double prev) (double curr)]
        (== k (dec i)) [(double curr) 0.0]
        :else          [0.0 0.0]))))

(defn- sliding-elapsed
  "The part of the window that holds `now` that has passed by then."
  ^double [^Limits l ^double now]
  (/ (- now (window-start l (window-index l now))) (.-window l)))

(defn- sliding-estimate
  "The units that a sliding-window key with the `state` has taken in the
  sliding window that ends at `now`."
  ^double [^Limits l state ^double now]
  (let [[prev curr] (sliding-counts l state now)]
    (+ (* (double prev) (- 1.0 (sliding-elapsed l now))) (double curr))))

(defn- sliding-wait
  "The milliseconds from `now` until a sliding-window key with the `state`
  has `n` units."
  ^double [^Limits l state ^double now ^double n]
  (let [[prev curr] (sliding-counts l state now)
        prev        (double prev)
        curr        (double curr)
        quota       (.-quota l)
        i           (window-index l now)]
    (cond
      (<= (+ (sliding-estimate l state now) n) (+ quota epsilon))
      0.0

      ;; the units of the window before fade out in time
      (and (<= (+ curr n) quota) (pos? prev))
      (let [elapsed (- 1.0 (/ (- quota curr n) prev))]
        (max 0.0 (- (+ (window-start l i) (* elapsed (.-window l))) now)))

      ;; the units of this window fade out in the next one
      :else
      (let [elapsed (- 1.0 (/ (- quota n) curr))]
        (- (+ (window-start l (inc i)) (* elapsed (.-window l))) now)))))

(defn- sliding-take
  "The state after a sliding-window key with the `state` takes `n` units
  at `now`."
  [^Limits l state ^double now ^double n]
  (let [[prev curr] (sliding-counts l state now)]
    [(window-index l now) prev (+ (double curr) n)]))

(defn- sliding-untake
  "The state after a sliding-window key with the `state` gives back `n`
  units that it took at `now`."
  [^Limits l [k prev curr :as state] ^double now ^double n]
  (if (nil? state)
    state
    (let [i (window-index l now)
          k (long k)]
      (cond
        (== k i)       [k prev (max 0.0 (- (double curr) n))]
        (== k (inc i)) [k (max 0.0 (- (double prev) n)) curr]
        :else          state))))

(defn available
  "The units that a key with the `state` has at `now`."
  ^double [^Limits l state ^double now]
  (case (.-algorithm l)
    :gcra           (gcra-available l state now)
    :fixed-window   (- (.-quota l) (fixed-count l state now))
    :sliding-window (- (.-quota l) (sliding-estimate l state now))))

(defn wait
  "The milliseconds from `now` until a key with the `state` has `n` units,
  or nil when it never will."
  [^Limits l state ^double now ^double n]
  (when (<= n (+ (.-capacity l) epsilon))
    (case (.-algorithm l)
      :gcra           (gcra-wait l state now n)
      :fixed-window   (fixed-wait l state now n)
      :sliding-window (sliding-wait l state now n))))

(defn take-units
  "The state after a key with the `state` takes `n` units at `now`."
  [^Limits l state ^double now ^double n]
  (case (.-algorithm l)
    :gcra           (gcra-take l state now n)
    :fixed-window   (fixed-take l state now n)
    :sliding-window (sliding-take l state now n)))

(defn untake-units
  "The state after a key with the `state` gives back `n` units that it
  took at `now`."
  [^Limits l state ^double now ^double n]
  (case (.-algorithm l)
    :gcra           (gcra-untake l state n)
    :fixed-window   (fixed-untake l state now n)
    :sliding-window (sliding-untake l state now n)))

(defn- seconds
  "The milliseconds `ms` in seconds."
  ^double [^double ms]
  (/ ms 1000.0))

(defn- result
  "The result of a request that was `allowed?`, for a key that has the
  `state` at `now`. A refused request could pass after `retry-ms`, if
  ever."
  [^Limits l state now allowed? retry-ms]
  (let [now       (double now)
        capacity  (.-capacity l)
        remaining (-> (available l state now)
                      (+ epsilon)
                      Math/floor
                      long
                      (max 0))
        refill-ms (when (< remaining capacity)
                    (wait l state now (double (inc remaining))))]
    (cond-> {:allowed?    allowed?
             :remaining   remaining
             :reset-after (seconds (wait l state now capacity))
             :state       state}
      retry-ms  (assoc :retry-after (seconds retry-ms))
      refill-ms (assoc :refill-after (seconds refill-ms)))))

(defn- consume-limits
  "The result of taking `cost` units at `now` from a key with the
  `state`."
  [^Limits l state ^double now ^double cost]
  (if (<= cost (+ (available l state now) epsilon))
    (result l (take-units l state now cost) now true nil)
    (result l state now false (wait l state now cost))))

(defn consume
  "The result of taking `cost` units at `now` from a key of the `policy`
  with the `state`."
  [policy state now cost]
  (consume-limits (limits policy) state (double now) (double cost)))

(defn refund
  "The result at `now` for a key of the `policy` with the `state`, after
  it gives back the `cost` units that it took at `now`."
  [policy state now cost]
  (let [l   (limits policy)
        now (double now)]
    (consume-limits l (untake-units l state now (double cost)) now 0.0)))
