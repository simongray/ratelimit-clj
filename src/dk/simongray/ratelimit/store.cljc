(ns dk.simongray.ratelimit.store
  "Where the state of each key lives.

  A store is anything that implements Store. The library comes with one,
  from memory-store in the root namespace. A store for Redis could
  implement Store with a compare-and-set in a Lua script.")

(defprotocol Store
  "The state of each key, updated atomically."
  (-update! [store k now f]
    "Apply `f` to the state of the key `k` atomically at `now`, a time in
    milliseconds, and return the result.

    The `f` takes the state, which is nil for a new key. It returns a
    vector of a result and the time from which the result's :state is the
    same as a new key's. Keep that :state for the key until then. When `f`
    runs more than once, e.g. because another thread updated the key in
    the meantime, return the result of the run whose state you kept."))
