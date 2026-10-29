Recipes
=======

Each recipe is a policy or an option for the Ring middleware, which the
Pedestal interceptor takes too. The examples use these aliases:

```clojure
(require '[clojure.string :as str]
         '[dk.simongray.ratelimit :as ratelimit]
         '[dk.simongray.ratelimit.ring :as ratelimit.ring])
```

Behind a proxy
--------------

Give the number of proxies in front of the server:

```clojure
(ratelimit.ring/wrap-rate-limit handler {:quota 100 :window :minute}
                                {:proxies 1})
```

One reverse proxy, such as nginx, Caddy, a cloud load balancer or a
Kubernetes ingress, is 1. Count each proxy that adds to X-Forwarded-For,
e.g. 2 for a CDN in front of a load balancer. Without :proxies, every
client shares the limit of the proxy's address. Only set it when clients
can't reach the server around the proxies, since a client can write
anything in X-Forwarded-For.

A policy with a :key of its own doesn't use :proxies, so it calls
`ratelimit/client-address` with the same number, as in the recipes below.

A limit per API key
-------------------

Count requests with an API key by the key, and the others by address:

```clojure
(defn api-client
  "The client of the `request`: its API key, or else its address behind
  one proxy."
  [request]
  (if-let [api-key (get-in request [:headers "x-api-key"])]
    [:api-key api-key]
    [:address (ratelimit/client-address request {:proxies 1})]))

(def per-api-key
  {:name "api" :quota 1000 :window :hour :key api-client})
```

The vectors keep an API key and an address apart, even if they're the
same text. If clients can make up keys, check the key before the rate
limit, since each made-up key would get a quota of its own.

Login attempts
--------------

Limit the attempts for each username, and for each address, since one
address can try many usernames:

```clojure
(defn login?
  "Whether the `request` is an attempt to log in."
  [{:keys [request-method uri] :as request}]
  (and (= :post request-method) (= "/login" uri)))

(def login-policies
  [{:name   "login-username"
    :quota  5
    :window :minute
    :when   login?
    :key    #(some-> (get-in % [:params "username"]) str/lower-case)}
   {:name   "login-address"
    :quota  20
    :window :minute
    :when   login?
    :key    #(ratelimit/client-address % {:proxies 1})}])
```

The username comes from :params, so put Ring's `wrap-params` outside the
rate limit. A request without a username has no key, so only the policy
for its address counts it.

A stricter limit on one route
-----------------------------

Every policy whose :when matches a request counts it, so a request for
/search counts against both of these:

```clojure
(def policies
  [{:name "pages" :quota 600 :window :minute}
   {:name "search" :quota 30 :window :minute :when #(= "/search" (:uri %))}])
```

With a router such as reitit, you can also put the middleware on that
route alone.

A JSON 429 for an API
---------------------

Answer with [problem details](https://www.rfc-editor.org/rfc/rfc9457.html),
the standard format for HTTP errors in JSON:

```clojure
(defn problem-429
  "The 429 of the `decision` as problem details in JSON."
  [_ decision]
  (-> (ratelimit/too-many-requests decision)
      (assoc-in [:headers "Content-Type"] "application/problem+json")
      (assoc :body (str "{\"type\": \"about:blank\","
                        " \"title\": \"Too Many Requests\","
                        " \"status\": 429}"))))

(ratelimit.ring/wrap-rate-limit handler policies
                                {:too-many-requests problem-429})
```

The response keeps the Retry-After and Cache-Control of the default 429.

Logging and trying out a limit
------------------------------

The :on-limit function runs for each refused request:

```clojure
(defn log-limit!
  "Log the refusal of the `request` by the `decision`."
  [request decision]
  (println "Rate limited:" (:uri request) (:limited decision)))

(ratelimit.ring/wrap-rate-limit handler policies
                                {:on-limit log-limit!
                                 :dry-run? true})
```

With :dry-run?, the middleware logs the requests that it would refuse
but lets them through, so you can find the right quota before you
enforce it.

Several servers
---------------

The memory store counts in each process on its own. Behind a load
balancer that spreads clients over n servers, divide the quota by n, or
send each client to the same server. A store that servers share, e.g. in
Redis, would implement the protocol `dk.simongray.ratelimit.store/Store`.

Testing
-------

Pass a clock that you control, so a test doesn't have to wait:

```clojure
(deftest rate-limit
  (let [now (atom 0)
        app (ratelimit.ring/wrap-rate-limit handler {:quota 2 :window :minute}
                                            {:clock #(deref now)})]
    (is (= [200 200 429]
           (map :status (repeatedly 3 #(app {:remote-addr "a"})))))
    (swap! now + 30000)
    (is (= 200 (:status (app {:remote-addr "a"}))))))
```

With GCRA, 2 a minute gives back a request every 30 seconds.
