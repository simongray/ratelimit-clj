ratelimit-clj: a proposal
=========================

A Clojure(Script) library that limits how often each client can make
requests, for Ring and Pedestal. It follows conneg-clj: one cljc codebase,
plain data in and out, the sections of the specs cited in the code, and no
exception on anything a client sends. A limit is a map, the arithmetic is a
pure function of state, time and cost, and where the state lives is a
separate choice.

The examples use the namespace `dk.simongray.ratelimit` with the alias
`ratelimit`. They are sketches, but the numbers in them come from a
prototype of the arithmetic. The research was done on 29 October 2026, on
DanNet's master at abef196. The decisions that followed the proposal are
at the end.

These findings shape the design:

- **No Clojure library gets the basics right and sends the standard
  headers.** The rate limiter in encore comes closest: it's atomic,
  checks several limits together and runs in cljc. But its window starts at a
  key's first request, so twice the limit can pass around the end of a
  window, and it has no HTTP layer. Two of the Ring middlewares let extra
  requests through under concurrency, and none sends the IETF RateLimit
  fields.
- **DanNet's limiter blocks clients that stay under the limit.** Each
  allowed request moves the expiry forward, so a client that sends one
  request a second is blocked after 400 seconds. Under 32 threads it lets
  103 to 113 requests through for a limit of 100, and it keeps every
  client it has seen until that client comes back. The docstring describes
  the first behaviour, but the library report listed it as a bug.
- **The arithmetic can be a pure function.** Go's x/time/rate takes the
  time as an argument, and Go's throttled keeps its algorithm (GCRA) apart
  from a store with three operations. A function of state, time and cost
  keeps the arithmetic apart from where the state lives and from HTTP, and
  makes the tests exact. GCRA keeps one number per key, which suits
  compare-and-set, in an atom or in Redis.
- **The IETF fields aren't stable.** The draft is at version 11 (May 2026)
  and still a working group document. Its wire format changed in -07 and
  again in -08, and -11 changed what t means. An open pull request renames r
  and t, and the HTTP directorate's review of -10 said "Not Ready". So the
  default is Retry-After alone, and the RateLimit fields are an option
  named by draft version, as in express-rate-limit.
- **No spec says which proxy entry to trust or how to group IPv6
  addresses.** RFC 7239 and MDN say to trust only what your own proxies
  added, and a single host often has a whole /64. So the number of proxies
  is an explicit option, and IPv6 addresses count by /64.


Library or fix in place?
------------------------

The library report of 13 October ranked rate limiting ninth of ten
spin-outs, since the code was small and the bugs looked easier to fix in
place. The survey changes that a little. No Clojure limiter for Ring or
Pedestal passes all five test cases below, and none sends the IETF fields,
so a library fills a gap. A version with several policies, costs, proxies
and eviction is closer to 500 lines than 120.

For DanNet alone, encore's limiter is already on the classpath through
Telemere. It would fix the three bugs in a few lines, but it lets twice
the limit through at a window's end and gives no headers.


What the specs say
------------------

These are the rules the library implements, from RFC 6585, RFC 9110 and
RFC 9111, and from draft-ietf-httpapi-ratelimit-headers-11 ("the draft"):

- **429 means too many requests in a given amount of time** (RFC 6585, 4).
  The response SHOULD explain the condition and MAY carry Retry-After. It
  MUST NOT be stored by a cache. The RFC doesn't say how to identify the
  client or how to count.
- **Retry-After is an HTTP-date or a whole number of seconds** (RFC 9110,
  10.2.3). Seconds don't depend on the clocks agreeing. A date MUST be sent
  as IMF-fixdate (5.6.7).
- **503 is for an overloaded server** and MAY carry Retry-After too (RFC
  9110, 15.6.4).
- **A 429 needs no Cache-Control**, since it isn't heuristically cacheable
  (RFC 9110, 15.1; RFC 9111, 3). A no-store still guards against caches
  that don't know RFC 6585.
- **RateLimit-Policy is a List of Items, one per policy** (draft, 3). Each
  is a String name with q (the quota, required), w (the window in
  seconds), qu (the unit, "requests" by default) and pk (a partition key),
  e.g. `"permin";q=50;w=60,"perhr";q=1000;w=3600`.
- **RateLimit holds the state of each policy** (draft, 4): r (the
  available quota, required) and t (the seconds within which the client
  can use no more than r), e.g. `"default";r=50;t=30`.
- **Both fields are optional on any response**, 429 included (draft, 6). A
  server MAY lower the values it sends at any time. Neither may go in a
  trailer. They are Structured Fields (RFC 9651), so a field that doesn't
  parse is ignored as a whole.
- **Retry-After SHOULD NOT point to a time before t runs out** (draft, 6),
  and a client MUST follow Retry-After over t (7).
- **A cache can replay a 2xx with its RateLimit fields to other clients**
  (RFC 9111, 3.1). Clients SHOULD ignore the fields on a response from a
  cache (draft, 7.3).
- **Partition keys SHOULD be documented and should avoid sensitive data**
  (draft, 6.1). A client's address is sensitive.
- **The draft's problem types aren't registered** (draft, 5). IANA's
  registry has none for rate limits, and RFC 9457 (4) says a plain status
  code is usually enough for a generic problem.
- **X-Forwarded-For and Forwarded can't be trusted as a whole** (RFC 7239,
  8.1). Behind N proxies of your own, the Nth entry from the right is the
  one your outermost proxy added (MDN). All field lines form one list.
- **An IPv6 host gets a /64 or more** (RFC 7934, 8; RFC 6177, 1), and its
  temporary addresses change within it (RFC 8981). An IPv4-mapped address
  (::ffff:0:0/96) is an IPv4 address (RFC 4291, 2.5.5.2).
- **A crawler that gets a 4xx for robots.txt may fetch everything** (RFC
  9309, 2.3.1.3). Google treats a 429 like a 5xx, but /robots.txt should
  never be limited.


Other Clojure libraries
-----------------------

I ran each library on the same cases, with a simulated clock where it
could take one:

1. Edge: a limit of 10 a second, with 1 request at 0 s, 9 at 0.9 s and
   10 at 1.1 s. A sliding window allows 11, and a token bucket 13.
2. Retry-After, when the true wait is 7.7 s: 8.
3. Steady: a limit of 400 a minute, and one request every 150 ms for
   three minutes. None should be blocked.
4. Threads: 32 threads on one key, with a limit of 100. Exactly 100
   should pass.
5. Eviction: 100,000 clients make one request each. Their entries should
   go once the window has passed.

Wrong results are in bold:

| Case | encore | ring-congestion | io.staticweb | ring-ratelimit | clj-rate-limiter | Bucket4j | DanNet | GCRA prototype |
|---|---|---|---|---|---|---|---|---|
| 1. Edge | **20** | **20** | **20** | **20** | 11 | 13 | **10** | 13 |
| 2. Retry-After | 7,700 ms, no header | **a date in the JVM's locale** | **7** | the next full hour | **none** | right | 8 | - |
| 3. Steady, blocked | 0-1 | 2 | - | 0 | **453** | 0 | **400** | 0 |
| 4. Threads, passed | 100 | **101-115** | **101-122** | 100 | **111-139** | 100 | **103-113** | - |
| 5. Eviction | gradual | **290 s for the 100,000** | - | hourly | **OutOfMemoryError at about 12,000** | your own map | **never** | - |

In short:

- **encore** (3.172.1, September 2026) checks several limits atomically
  before it counts any of them, runs in cljc and returns an exact backoff
  in milliseconds. Its window starts at a key's first request, and its
  sweep of old keys runs at random, on about one call in 4,000. It has
  no clock to inject and no HTTP layer, and a cost per request is
  undocumented.
- **ring-congestion** (0.1.3, 2022) reads the count and then increments
  it, so concurrent requests slip through. Each request scans every key,
  and it formats Retry-After as a date in the JVM's locale, so a Danish
  JVM sends a Danish date. Its fork io.staticweb/rate-limit sends seconds
  but rounds down, so a client that waits that long is still blocked.
- **ring-ratelimit** (0.2.3, 2023) counts correctly, but its window is
  the clock hour. It's the only one that sends X-RateLimit headers.
- **sturdy-throttle** (0.0.7, July 2026) shares one window between all
  clients in memory and sends no headers.
- **clj-rate-limiter** (2016) is a real sliding log, but it starts a
  thread per key, which crashed the JVM. It counts refused requests, so a
  client that sends at the limit is blocked a third of the time.
- **diehard, TrueGrit, resilience4clj and throttler** limit outgoing calls
  with a single limiter and no keys.
- **clj-bucket4j** (0.1.4, 2023) wraps Bucket4j, which is exact and takes
  a clock. Keys, eviction and headers are up to you.

Pedestal 0.8.2 and reitit 0.11.0 have no rate limiter. The only other cljc
library is indole (2018), a single bucket with no keys. The harnesses are
in the scratch directory, one per library.


Other ecosystems
----------------

- **.NET's System.Threading.RateLimiting** separates the parts most
  cleanly: the algorithm, the partitioning by key, the chaining of several
  limiters, the result (a lease with metadata such as RetryAfter) and the
  HTTP middleware. But it sends no headers and answers 503 by default.
- **Bucket4j** keeps the configuration as an immutable value apart from
  the state, and the storage behind a proxy manager. One bucket can hold
  several bandwidths, and a request must fit all of them.
- **Go's x/time/rate** takes the time as an argument. Its Reservation
  separates the decision from the waiting.
- **Go's throttled** runs GCRA against a store of three operations: get,
  set if absent, and compare and swap. A Redis store implements them in
  Lua.
- **express-rate-limit** names its header format by draft version, and
  checks the setup for mistakes such as an untrusted X-Forwarded-For or a
  request counted twice. But its stores decide the algorithm, so a new
  algorithm needs a new store.
- **rate-limiter-flexible** signals a refusal by rejecting the promise, so
  a refusal and a store error arrive the same way. The library keeps them
  apart.
- **nginx, Envoy and Cloudflare** have a dry run that logs refusals
  without enforcing them.
- **Stripe** lets requests through when the store is down, and runs a
  limit on concurrent requests beside its rate limits.

None of these libraries avoids counting twice when one page load makes
two requests.


Algorithms
----------

| Algorithm | State per key | Requests in any window | Good for |
|---|---|---|---|
| Fixed window | a count | up to twice the quota, around the edge | quotas that reset at set times, e.g. daily |
| Sliding window counter | two counts | about the quota | strict limits |
| Sliding log | a time per request | exactly the quota | small quotas |
| Token bucket, GCRA | one time, for GCRA | the burst plus the quota | smooth limits |
| Concurrency | requests in flight | not a rate | costly endpoints |

GCRA allows the same requests as a token bucket, but keeps a single
number per key: the time at which the key's quota is full again. The
remaining quota, the retry-after and the reset time all follow from that
number and the policy. Cloudflare measured the sliding window counter as
wrong for 0.003% of requests.


Scope
-----

**In:**

- policies as maps, with GCRA, a fixed window and a sliding window counter
- `consume`, the pure arithmetic for one key
- a store protocol, and a memory store that removes idle keys
- `limit!`, which applies the policies to a Ring request and returns a
  decision
- client addresses behind proxies, with IPv6 grouped by prefix
- responses: the 429, Retry-After, and the RateLimit fields by draft
  version
- Ring middleware and a Pedestal interceptor in cljc, with no dependency
  on Ring or Pedestal, as in conneg-clj

ClojureScript gets the same arithmetic and memory store, for Node servers
and for limiting calls from a browser.

**Left out:**

- Other stores, e.g. Redis. The protocol leaves room for one, and DanNet
  runs a single process.
- Concurrency limits, for now (see the decisions at the end).
- Waiting instead of refusing, e.g. a function that sleeps until a call
  may run. With :retry-after, that's a short loop.
- Penalties, e.g. blocking a client for an hour after abuse as rack-attack
  does, and lists of addresses to allow or block. A :when predicate or the
  proxy covers these.
- The sliding log, which needs a time for every request.
- The pk parameter, which identifies clients and which the open pull
  request changes.
- Bodies as problem details (RFC 9457), since the draft's problem types
  aren't registered.
- Reading the RateLimit fields in a client.
- Rate strings such as "400/minute".
- A cap on the number of keys in the memory store. Under a flood of new
  addresses, the store grows until the entries expire.

**Readings where the specs leave a choice:**

- **A policy is a quota per window, and GCRA is the default, with the
  whole quota as its burst.** A client can use the quota at once, and then
  gets a unit back every window/quota seconds. So in its first window a
  client can make almost twice the quota (799 for 400 a minute), which a
  fixed window also allows at its edge. A lower :burst tightens that, e.g.
  439 with a burst of 40. A sliding window keeps every window close to the
  quota.
- **A refused request takes nothing from any policy.** Counting refusals
  would punish a client that retries too soon, but it also keeps a client
  blocked for as long as it keeps trying, as in clj-rate-limiter.
- **Retry-After is in seconds, rounded up**, never a date. A client that
  waits that long gets through.
- **t is the time until the available quota next goes up, rounded up.**
  That fits both -10's meaning ("until more quota is available") and
  -11's (the client can't use more than r before then). On a 429 it is the
  same as Retry-After, as the draft asks.
- **A cost above the burst is refused without a Retry-After**, since
  waiting never helps. The plain GCRA arithmetic would ask the client to
  wait a few milliseconds.
- **The RateLimit fields list the policies that applied to the request**,
  and no others.
- **Fixed windows align to the clock**, so a daily window resets at
  midnight UTC. That's the reason to pick one over GCRA. All clients then
  come back at once, as the draft warns (8.5).
- **IPv6 addresses count by /64**, as Cloudflare's old limiter did and as
  Better Auth chose to fix a CVE in May 2026. express-rate-limit uses /56.
- **The proxy header is named, never guessed.** If your proxy appends to
  X-Forwarded-For, a Forwarded header can only come from the client, so
  the library never falls back from one to the other.
- **The library exempts nothing by itself**, not even /robots.txt. A :when
  predicate does that.


API
---

### A policy

```clojure
(require '[dk.simongray.ratelimit :as ratelimit])

(def pages
  {:name   "pages"
   :quota  400
   :window 60})
```

A policy is a RateLimit-Policy item as a map: a name, a quota and a window
in seconds. It also takes:

- :burst, the most units at once, by default the quota
- :algorithm, which is :gcra by default, :fixed-window or :sliding-window
- :key, a function of the request that returns the client, by default
  `ratelimit/client-address`
- :when, a predicate on the requests that count, by default all of them
- :cost, a function of the request that returns its cost, by default 1

The arithmetic reads :quota, :window, :burst and :algorithm, and the
request functions read :key, :when and :cost, so one map serves both.

### The arithmetic

```clojure
(ratelimit/consume pages nil 0 1)
;; => {:allowed?     true
;;     :remaining    399
;;     :refill-after 0.15
;;     :reset-after  0.15
;;     :state        150.0}
```

The `consume` function takes a policy, the state of one key (nil for a new
key), the time in milliseconds and a cost. It returns the result with the
new state. Here is the next request after 400 at once:

```clojure
(ratelimit/consume pages 60000.0 0 1)
;; => {:allowed?     false
;;     :remaining    0
;;     :retry-after  0.15
;;     :refill-after 0.15
;;     :reset-after  60.0
;;     :state        60000.0}
```

The times in a result are in seconds:

- :retry-after, until a refused request could pass
- :refill-after, until :remaining next goes up
- :reset-after, until the whole quota is back

A cost of 0 checks without consuming.

### A store

```clojure
(def store
  (ratelimit/memory-store))

(ratelimit/consume! store pages "203.0.113.7" 1)
```

A store applies `consume` to the state of one key atomically. The memory
store is a ConcurrentHashMap on the JVM and a map in JavaScript. An entry
whose quota is full again holds nothing that a new key doesn't, so the
store drops it in a sweep every so often. The writes run the sweep, so no
thread is needed. The protocol has one function, so a Redis store could
implement it with compare-and-set in a Lua script, as throttled does.

The `consume!` function also works outside HTTP, e.g. for calls to an
external API: sleep for :retry-after and try again.

### A request

```clojure
(def policies
  [{:name "pages" :quota 400 :window 60 :when page?}
   {:name "sparql" :quota 60 :window 60 :when sparql?}])

(ratelimit/limit! policies store request)
;; => {:results [{:name "pages" :key "203.0.113.7" :allowed? true ...}
;;               {:name "sparql" :key "203.0.113.7" :allowed? false
;;                :retry-after 4.2 ...}]
;;     :limited ["sparql"]}
```

The `limit!` function consumes from every policy whose :when matches the
request. When a policy refuses, the decision lists it under :limited, and
the request takes nothing from the others. So a client that a short burst
limit blocks keeps its daily quota.

### Responses

```clojure
(ratelimit/too-many-requests decision)
;; => {:status  429
;;     :headers {"Retry-After"   "5"
;;               "Cache-Control" "no-store"
;;               "Content-Type"  "text/plain;charset=utf-8"}
;;     :body    "Too many requests. Try again in 5 seconds."}

(ratelimit/headers decision :draft-11)
;; => {"RateLimit-Policy" "\"pages\";q=400;w=60, \"sparql\";q=60;w=60"
;;     "RateLimit"        "\"pages\";r=12;t=1, \"sparql\";r=0;t=5"}
```

Retry-After is the longest :retry-after of the policies that refused,
rounded up. The `headers` function takes the draft version, since the next
draft may rename r and t.

### Ring and Pedestal

```clojure
(require '[dk.simongray.ratelimit.ring :as ratelimit.ring]
         '[dk.simongray.ratelimit.pedestal :as ratelimit.pedestal])

(def app
  (ratelimit.ring/wrap-rate-limit handler policies))

(def rate-limit-ic
  (ratelimit.pedestal/rate-limit policies))
```

Both put the decision in the request under ::ratelimit/decision. They
answer a refused request with a 429, without calling the handler. Their
options are:

- :store, by default a new memory store
- :clock, a function that returns the time in milliseconds, by default the
  system clock
- :too-many-requests, a function of the request and the decision that
  returns the response, e.g. a 429 in the negotiated media type
- :on-limit, a function of the request and the decision that runs for each
  refused request, e.g. to log it
- :dry-run?, to run :on-limit but let the request through, for trying out
  a new limit
- :headers, a draft version such as :draft-11, to add the RateLimit
  fields to every response

### Client addresses

```clojure
(ratelimit/client-address {:remote-addr "2001:db8:cafe:17:a:b:c:d"})
;; => "2001:db8:cafe:17::/64"

(ratelimit/client-address request {:proxies 1})
```

With :proxies, the address is the entry that your outermost proxy added to
X-Forwarded-For, or to Forwarded when :header is "forwarded". With fewer
entries than proxies, it's the leftmost, and without the field it's
:remote-addr. The :prefix option sets how many
bits of an IPv6 address count. A key can be any value, so a key of several
parts is a vector, e.g. from `juxt`.


Errors
------

No function throws on anything a client sends. An address that doesn't
parse is used as it is, as a string. A request whose key is nil isn't
counted, as in nginx.

A policy without a quota or a window, or two policies with the same name,
throw when the middleware is made, since that's a mistake in the code
rather than in a request.


Tests
-----

- **The cases above**, with a simulated clock: the edge, Retry-After, a
  steady client, 32 threads on one key and eviction
- **The draft's examples** (3.2, 4.2 and B.3), serialized
- **Addresses:** the examples of RFC 7239 and MDN, and the forms of IPv6
  addresses in RFC 5952
- **Generative tests:** over any span of time, GCRA never allows more than
  the burst plus the quota for that span. A request at exactly
  :retry-after passes, and one a millisecond earlier doesn't. A cost of 0
  never changes the state. A key whose quota is full again is the same as
  a new key. No request map throws.

All of them run on the JVM and in Node.


Performance
-----------

A request costs one atomic update for each policy that applies, plus the
parsing of an address. A bench like conneg-clj's would time that with many
keys and 32 threads.


DanNet
------

This is how DanNet's rate limiting could look:

```clojure
(defn fingerprint
  "The client of the `request`: its address as Caddy saw it, with its
  User-Agent and Accept-Language."
  [{:keys [headers] :as request}]
  [(ratelimit/client-address request {:proxies 1})
   (get headers "user-agent")
   (get headers "accept-language")])

(def policies
  [{:name   "pages"
    :quota  400
    :window 60
    :key    fingerprint
    :when   costly-path?}
   {:name   "sparql"
    :quota  60
    :window 60
    :key    fingerprint
    :when   sparql-path?}])

(def rate-limit-ic
  (ratelimit.pedestal/rate-limit policies
                                 {:too-many-requests res/rate-limit-response
                                  :on-limit          log-rate-limit!}))
```

- **The interceptor replaces** rate_limit.clj and the map in
  dannet-rate-limit-ic.
- **The SPARQL endpoint gets a policy of its own**, which settles the TODO
  in resources.clj. The quota here is only an example.
- **The 429 keeps its negotiated body**, through :too-many-requests.
  rate-limit-response would take the request and the decision, and name
  the quota of the policy that refused.
- **Logging moves to :on-limit.**
- **The HTML page and its Transit fetch still count twice.** A cost of 1/2
  for each would count a page load once, but would let a client that only
  fetches HTML make twice as many requests.

What changes for users:

- A client that stays under 400 a minute is never blocked. Today, one
  request a second is blocked after 400 seconds.
- Concurrent requests can't get past the limit.
- A blocked client can make a request every 150 ms, i.e. 400 a minute,
  rather than none until a minute has passed since its last allowed
  request.
- Entries go once their clients have been idle for a minute.


Size
----

About 500 lines of source. The three algorithms and the parsing of
addresses are most of it, and the adapters are short.


Decisions
---------

After the proposal, on 29 October 2026:

- **The name is ratelimit-clj**, as `dk.simongray/ratelimit-clj` with the
  namespace `dk.simongray.ratelimit`.
- **The first version has all three algorithms:** GCRA, the fixed window
  and the sliding window counter.
- **Concurrency limits wait.** They would be a second kind of policy,
  released when the response is done.
- **The RateLimit fields are off by default** until the draft settles. The
  :headers option turns them on for a given draft version.
- **The library has a repository of its own**, next to conneg-clj.

During the build:

- **A store's errors aren't caught**, since the memory store has none. The
  :on-error option waits for a store that can fail, e.g. Redis.
- **Behind fewer proxies than the option says, the leftmost address
  counts**, rather than :remote-addr. Every entry came from a proxy of
  your own, while :remote-addr is the innermost proxy, which all clients
  share.
- **Each result in a decision has its policy's :name, :quota and
  :window**, since the RateLimit fields need them.
