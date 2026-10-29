(ns dk.simongray.ratelimit.rfc-test
  "The examples of the specs: client addresses by RFC 4291, RFC 5952, RFC
  7239 and MDN, and the fields of draft-ietf-httpapi-ratelimit-headers."
  (:require [clojure.test :refer [deftest is testing]]
            [dk.simongray.ratelimit :as ratelimit]))

(defn- address
  "The client address of a request from `s`, with all of its bits."
  [s]
  (ratelimit/client-address {:remote-addr s} {:prefix 128}))

;; RFC 5952, section 4
(deftest ipv6-text
  (is (= "2001:db8::1" (address "2001:0db8::0001")) "4.1")
  (is (= "2001:db8::2:1" (address "2001:db8:0:0:0:0:2:1")) "4.2.1")
  (is (= "2001:db8:0:1:1:1:1:1" (address "2001:db8:0:1:1:1:1:1")) "4.2.2")
  (is (= "2001:0:0:1::1" (address "2001:0:0:1:0:0:0:1")) "4.2.3")
  (is (= "2001:db8::1:0:0:1" (address "2001:db8:0:0:1:0:0:1")) "4.2.3")
  (is (= "2001:db8::1" (address "2001:DB8::1")) "4.3")
  (is (= "::" (address "0:0:0:0:0:0:0:0")))
  (is (= "::1" (address "::1"))))

;; RFC 4291, sections 2.2 and 2.5.5
(deftest ipv6-forms
  (is (= "192.0.2.1" (address "::ffff:192.0.2.1")) "IPv4-mapped")
  (is (= "192.0.2.1" (address "::FFFF:c000:201")) "IPv4-mapped in hex")
  (is (= "::c000:201" (address "::192.0.2.1")) "IPv4-compatible")
  (is (= "fe80::1" (address "fe80::1%eth0")) "a zone")
  (testing "not addresses"
    (doseq [s ["1:2:3:4:5:6:7:8:9" "1::2::3" "12345::" "1:2:3:4:5:6:7" "::1.2.3.256"]]
      (is (= s (address s))))))

(deftest ipv6-prefix
  (is (= "2001:db8:cafe:17::/64"
         (ratelimit/client-address {:remote-addr "2001:db8:cafe:17:a:b:c:d"})))
  (is (= "2001:db8:ca00::/40"
         (ratelimit/client-address {:remote-addr "2001:db8:cafe:17::1"} {:prefix 40})))
  (is (= "203.0.113.7" (ratelimit/client-address {:remote-addr "203.0.113.7"}))
      "IPv4 counts by every bit"))

(defn- forwarded
  "The client address of a request through `proxies` with the Forwarded
  value `s`."
  [s proxies]
  (ratelimit/client-address {:remote-addr "10.0.0.1"
                             :headers     {"forwarded" s}}
                            {:proxies proxies :header "Forwarded"}))

;; RFC 7239, sections 4, 6 and 7.4
(deftest rfc-7239
  (is (= "_gazonk" (forwarded "for=\"_gazonk\"" 1)))
  (is (= "2001:db8:cafe::/64" (forwarded "For=\"[2001:db8:cafe::17]:4711\"" 1)))
  (is (= "192.0.2.60" (forwarded "for=192.0.2.60;proto=http;by=203.0.113.43" 1)))
  (is (= "198.51.100.17" (forwarded "for=192.0.2.43, for=198.51.100.17" 1)))
  (is (= "192.0.2.43" (forwarded "for=192.0.2.43, for=198.51.100.17" 2)))
  (is (= "192.0.2.43" (forwarded "for=192.0.2.43, for=\"[2001:db8:cafe::17]\"" 2))
      "the X-Forwarded-For example, converted")
  (testing "an element without for"
    (is (= "10.0.0.1" (forwarded "by=203.0.113.43" 1)))))

(defn- x-forwarded-for
  "The client address of a request through `proxies` with the
  X-Forwarded-For value `s`."
  [s proxies]
  (ratelimit/client-address {:remote-addr "10.0.0.1"
                             :headers     {"x-forwarded-for" s}}
                            {:proxies proxies}))

;; MDN, X-Forwarded-For: the nth address from the right behind n proxies
(deftest mdn
  (let [s "203.0.113.195,2001:db8:85a3:8d3:1319:8a2e:370:7348,198.51.100.178"]
    (is (= "198.51.100.178" (x-forwarded-for s 1)))
    (is (= "2001:db8:85a3:8d3::/64" (x-forwarded-for s 2)))
    (is (= "203.0.113.195" (x-forwarded-for s 3)))
    (is (= "203.0.113.195" (x-forwarded-for s 5)) "fewer than the proxies"))
  (is (= "203.0.113.195" (x-forwarded-for "203.0.113.195:41237" 1)) "a port")
  (is (= "10.0.0.1" (x-forwarded-for " , " 1)) "only empty elements")
  (is (= "\"\\" (x-forwarded-for "\"\\" 1)) "an unfinished quoted string")
  (is (= "10.0.0.1"
         (ratelimit/client-address {:remote-addr "10.0.0.1"
                                    :headers     {"x-forwarded-for" "203.0.113.195"}}))
      "no proxies, so the field is ignored"))

(defn- result
  "A result of the policy `name` with the `quota` and `window` and the
  `remaining` units, which go up after `refill-after` seconds."
  [name quota window remaining refill-after]
  {:name name :quota quota :window window :remaining remaining
   :refill-after refill-after})

;; draft-ietf-httpapi-ratelimit-headers-11, sections 3.2 and 4.2. The
;; draft leaves out the space after the comma, which RFC 9651 (4.1.1)
;; writes and which parses the same.
(deftest ratelimit-fields
  (is (= {"RateLimit-Policy" "\"permin\";q=50;w=60, \"perhr\";q=1000;w=3600"
          "RateLimit"        "\"permin\";r=50, \"perhr\";r=999;t=4"}
         (ratelimit/headers {:results [(result "permin" 50 60 50 nil)
                                       (result "perhr" 1000 3600 999 3.6)]}
                            :draft-11)))
  (is (= "\"default\";r=50;t=30"
         (get (ratelimit/headers {:results [(result "default" 100 60 50 30)]}
                                 :draft-11)
              "RateLimit")))
  (is (= "\"a \\\"quoted\\\" \\\\ name\";q=1;w=1"
         (get (ratelimit/headers {:results [(result "a \"quoted\" \\ name" 1 1 1 nil)]}
                                 :draft-11)
              "RateLimit-Policy"))
      "RFC 9651, section 4.1.6")
  (is (nil? (ratelimit/headers {:results []} :draft-11))))
