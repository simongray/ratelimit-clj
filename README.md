ratelimit-clj
=============

This library limits how often each client can make requests, in Clojure
and ClojureScript. Use it as Ring middleware or a Pedestal interceptor, or
call it from your own code, e.g. to limit calls to an external API.

- **Limits as data.** A policy is a map of a quota and a window in
  seconds, as in the RateLimit-Policy field of the IETF draft. Several
  policies can apply to one request, e.g. a burst limit and a daily quota,
  and a request that one of them refuses takes nothing from the others.
- **Three algorithms.** GCRA by default, which gives each client a steady
  rate with room for bursts, or a fixed or a sliding window. Each is a
  pure function of a key's state, the time and the cost.
- **Exact under load.** The memory store updates each key atomically, so
  concurrent requests never get past a limit. It drops a key's entry once
  the key is idle.
- **Client addresses behind proxies.** It takes the address that your own
  proxy added to X-Forwarded-For or Forwarded, and counts an IPv6 address
  by its first 64 bits, since a host often has a whole /64.
- **Standard responses.** A 429 with a Retry-After that a client can
  trust, and the RateLimit fields of the IETF draft if you want them.
- **No request breaks it.** No function throws on anything a client
  sends.
- **The same results everywhere.** One codebase runs on the JVM, in Node
  and in browsers, with no dependencies.

> This library was developed with assistance from frontier LLMs.

Getting started
---------------

The library needs Clojure 1.11+ and Java 11+. It isn't released yet, so
add a checkout of this repository to the `:deps` in your `deps.edn`:

```clojure
dk.simongray/ratelimit-clj {:local/root "../ratelimit-clj"}
```

To limit each client to 400 requests a minute, put the Ring middleware
around your handler:

```clojure
(require '[dk.simongray.ratelimit.ring :as ratelimit.ring])

(def app
  (ratelimit.ring/wrap-rate-limit handler
                                  [{:name "pages" :quota 400 :window 60}]))
```

A client that goes over the limit gets this response, and your handler
isn't called:

```clojure
{:status  429
 :headers {"Retry-After"   "1"
           "Cache-Control" "no-store"
           "Content-Type"  "text/plain;charset=utf-8"}
 :body    "Too many requests. Try again in 1 second."}
```

By default, the client is the request's :remote-addr. Behind a proxy of
your own, see [Client addresses](#client-addresses).

Policies
--------

A policy has a :name, a :quota of units and a :window in seconds. It can
also have:

- :burst, the most units at once with GCRA, by default the quota
- :algorithm, which is :gcra by default, :fixed-window or
  :sliding-window
- :key, a function of the request that returns the client, by default
  `ratelimit/client-address`. A request whose key is nil isn't counted.
- :when, a predicate on the requests that the policy counts, by default
  all of them
- :cost, a function of the request that returns its units, by default 1

Each policy counts the requests that its :when matches:

```clojure
(def policies
  [{:name "burst" :quota 10 :window 1}
   {:name "daily" :quota 5000 :window 86400 :algorithm :fixed-window}
   {:name   "search"
    :quota  60
    :window 60
    :when   #(= "/search" (:uri %))
    :cost   #(if (= :post (:request-method %)) 5 1)}])
```

When one of them refuses a request, the request takes nothing from the
others, so a client that the burst limit blocks keeps its daily quota.

The algorithms count in different ways:

- **GCRA** lets a client use its quota at once, and then gives it a unit
  back every window/quota seconds. So in its first window, a client can
  make almost twice the quota. A lower :burst tightens that.
- **A fixed window** counts the units in windows aligned to the clock, so
  a daily window resets at midnight UTC. Around the end of a window, a
  client can make twice the quota.
- **A sliding window** counts the current window in full, and the window
  before it by the part that the sliding window still covers. That keeps
  the count over any span of one window close to the quota.

Ring and Pedestal
-----------------

The middleware puts its decision in the request, under
:dk.simongray.ratelimit/decision:

```clojure
{:results [{:name         "pages"
            :quota        400
            :window       60
            :key          "203.0.113.7"
            :allowed?     true
            :remaining    399
            :reset-after  0.15
            :refill-after 0.15}]}
```

When a policy refuses the request, the decision lists the policy's name
under :limited. The durations are in seconds:

- :retry-after, until a refused request could pass
- :refill-after, until :remaining goes up
- :reset-after, until the key has all of its units again

The middleware takes these options:

- :store, where the state of each key lives, by default a new memory
  store
- :clock, a function that returns the time in milliseconds, by default
  the system clock
- :too-many-requests, a function of the request and the decision that
  returns the response to a refused request, e.g. a 429 in the media type
  that the client asked for
- :on-limit, a function of the request and the decision that runs for
  each refused request, e.g. to log it
- :dry-run?, to run :on-limit but let refused requests through, e.g. to
  try out a new limit
- :headers, a draft of the RateLimit fields, such as :draft-11, to add
  them to every response

The middleware works with both synchronous and asynchronous handlers,
and throws when a policy or an option is wrong.

For Pedestal, put the interceptor in a route, or pass it to
`io.pedestal.interceptor/interceptor` to use it among the interceptors of
a service. It takes the same options:

```clojure
(require '[dk.simongray.ratelimit.pedestal :as ratelimit.pedestal])

(def rate-limit
  (ratelimit.pedestal/rate-limit policies))

(def routes
  #{["/word/:id" :get [rate-limit word-handler] :route-name ::word]})
```

On enter, the interceptor puts the decision in the request, or sets a 429
that ends the chain. On leave, it adds the RateLimit fields when :headers
asks for them.

Client addresses
----------------

The default key is the client's address:

```clojure
(require '[dk.simongray.ratelimit :as ratelimit])

(ratelimit/client-address {:remote-addr "2001:db8:cafe:17:a:b:c:d"})
;; => "2001:db8:cafe:17::/64"
```

Behind proxies of your own, the :remote-addr is your proxy's. Pass the
number of proxies, and the address is the one that the outermost proxy
added to X-Forwarded-For:

```clojure
(ratelimit/client-address
 {:remote-addr "10.0.0.2"
  :headers     {"x-forwarded-for" "198.51.100.9, 203.0.113.7"}}
 {:proxies 1})
;; => "203.0.113.7"
```

The entries to the left of it came from the client, which can write
anything there. Only use :proxies when clients can't reach your server
around the proxies. For a proxy that adds to the Forwarded field of RFC
7239 instead, give :header "forwarded". The :prefix option sets how many
bits of an IPv6 address count.

A key can be any value, e.g. a vector of the client's address and its
User-Agent:

```clojure
(defn client
  "The client of the `request` behind one proxy, by its address and its
  User-Agent."
  [request]
  [(ratelimit/client-address request {:proxies 1})
   (get-in request [:headers "user-agent"])])
```

Responses and headers
---------------------

To answer a refused request yourself, build on `ratelimit/retry-after`,
which returns the Retry-After value of a decision, or on
`ratelimit/too-many-requests`, which returns the whole 429.

The RateLimit and RateLimit-Policy fields come from an IETF draft that
is still changing, so you choose the version:

```clojure
(ratelimit/headers decision :draft-11)
;; => {"RateLimit-Policy" "\"pages\";q=400;w=60"
;;     "RateLimit"        "\"pages\";r=399;t=1"}
```

In RateLimit, t is the time until the available quota next goes up. A
shared cache can replay a successful response with these fields to other
clients, so only turn them on for responses that such caches don't
store.

Without HTTP
------------

You can also use the library without the middleware:

- `ratelimit/limit!` applies policies to a Ring request and returns the
  decision.
- `ratelimit/consume!` takes units from one key in a store.
- `ratelimit/consume` is the arithmetic itself: it takes the state of a
  key, the time and a cost, and returns the result with the new state.

For example, to wait for a slot before each call to an external API:

```clojure
(def store
  (ratelimit/memory-store))

(defn call-api!
  "Call `f` once the API's limit of 10 calls a second allows it."
  [f]
  (let [{:keys [allowed? retry-after]}
        (ratelimit/consume! store {:quota 10 :window 1} :api 1)]
    (if allowed?
      (f)
      (do (Thread/sleep (long (Math/ceil (* 1000 retry-after))))
          (recur f)))))
```

To keep the state elsewhere, e.g. in Redis, implement the protocol
`dk.simongray.ratelimit.store/Store`. Its one function applies an update
to the state of one key atomically.

Choices that the standard leaves open
-------------------------------------

- **A refused request takes nothing.** Counting refusals would punish a
  client that retries too soon, but it also keeps a client blocked for as
  long as it keeps trying.
- **Retry-After is in seconds, rounded up.** A client that waits that
  long gets through. Seconds also don't depend on the clocks agreeing.
- **A request that costs more than a policy ever allows** gets a 429
  without a Retry-After, since waiting won't help.
- **When the field has fewer entries than :proxies**, the leftmost one
  counts, since your proxies added all of them.
- **The proxy field is the one you name.** If your proxy adds to
  X-Forwarded-For, a Forwarded field can only come from the client, so
  the library never falls back from one to the other.
- **Nothing is exempt by default**, not even /robots.txt. A crawler that
  gets a 4xx for it may fetch anything, so leave it out with :when.

Other Clojure libraries
-----------------------

Here is how each library fares on five cases, with a simulated clock where
it can take one (encore 3.172.1, ring-congestion 0.1.3,
io.staticweb/rate-limit 1.1.0, ring-ratelimit 0.2.3, clj-rate-limiter
0.1.6-RC1 and clj-bucket4j 0.1.4). Wrong results are in bold:

1. Edge: a limit of 10 a second, with 1 request at 0 s, 9 at 0.9 s and
   10 at 1.1 s. A sliding window allows 11, and a token bucket 13.
2. Retry-After, when the true wait is 7.7 s.
3. Steady: a limit of 400 a minute, and one request every 150 ms for
   three minutes. None should be blocked.
4. Threads: 32 threads on one key with a limit of 100.
5. Eviction: what happens to the entries of 100,000 clients once their
   window has passed.

| Case | ratelimit-clj | encore | ring-congestion | io.staticweb | ring-ratelimit | clj-rate-limiter | clj-bucket4j |
|---|---|---|---|---|---|---|---|
| 1. Edge | 13 | **20** | **20** | **20** | **20** | 11 | 13 |
| 2. Retry-After | 8 | no header | **a date in the JVM's locale** | **7** | the next full hour | **none** | no header |
| 3. Steady, blocked | 0 | 0-1 | 2 | - | 0 | **453** | 0 |
| 4. Threads, passed | 100 | 100 | **101-115** | **101-122** | 100 | **111-139** | 100 |
| 5. Eviction | dropped in a sweep | dropped at random | **dropped, but each request scans every key** | - | dropped hourly | **runs out of threads at about 12,000** | up to you |

The Ring middlewares send no RateLimit fields, and encore and
clj-bucket4j have no HTTP layer. None of them runs in ClojureScript.

Not yet
-------

- Stores other than the memory store, e.g. Redis
- Limits on concurrent requests, which the draft's concurrent-requests
  unit describes
- Waiting for a request to pass rather than refusing it
- Penalties, e.g. blocking a client for an hour after abuse
- The draft's partition keys and problem types

Principles
----------

- **Standards first.** The code follows RFC 6585, RFC 9110 and the IETF
  draft, and cites their sections. The tests include the examples of RFC
  5952, RFC 7239 and the draft.
- **Plain data.** Policies, results and decisions are maps, and a key can
  be any value.
- **Pure arithmetic.** The algorithms take the time as an argument, so
  the tests run on a simulated clock.
- **One codebase.** The library is written in `.cljc` and has no
  dependencies.

Development
-----------

```bash
clojure -X:test                                       # the tests on the JVM
clojure -X:test:pedestal                              # also in Pedestal's chain
npm install                                           # once, for the Node tests
clojure -M:cljs compile test                          # the tests in Node
clojure -M:bench -m dk.simongray.ratelimit.bench      # benchmarks on the JVM
clojure -M:cljs release bench && node target/bench.js # benchmarks in Node
```

The :pedestal alias also runs the interceptor in Pedestal 0.8's chain,
which needs Java 17. The design and the research behind it are in
[doc/proposal.md](doc/proposal.md).

The public namespaces are the root, `store`, `ring` and `pedestal`. The
internal ones are under `impl`:

- `impl.algorithm`, the arithmetic of GCRA and the two windows
- `impl.memory`, the memory store
- `impl.address`, client addresses and the fields of proxies
- `impl.decision`, the decision for a request
- `impl.response`, Retry-After, the RateLimit fields and the 429
- `impl.adapter`, what the middleware and the interceptor share

License
-------

The ratelimit-clj project is licensed under the [MIT licence](LICENSE).
