(ns dk.simongray.ratelimit.generators
  "Random policies, sequences of requests and client addresses for the
  generative tests.

  Most parts come from small sets, so that requests often run out of
  units and addresses often parse."
  (:require [clojure.string :as str]
            [clojure.test.check.generators :as gen]))

(def policy
  "A policy of any algorithm, with a small quota."
  (gen/let [quota     (gen/choose 1 20)
            window    (gen/elements [0.5 1 2 10 60])
            algorithm (gen/elements [:gcra :fixed-window :sliding-window])
            burst     (gen/one-of [(gen/return nil) (gen/choose 1 quota)])]
    (cond-> {:name "p" :quota quota :window window :algorithm algorithm}
      (and burst (= :gcra algorithm)) (assoc :burst burst))))

(def requests
  "A sequence of requests, each a pair of the milliseconds since the one
  before and its cost."
  (gen/vector (gen/tuple (gen/frequency [[3 (gen/return 0)]
                                         [5 (gen/choose 0 300)]
                                         [1 (gen/choose 0 20000)]])
                         (gen/elements [0 1 1 1 1 2 5]))
              0 40))

(def hextet
  "A group of an IPv6 address, or something like one."
  (gen/one-of [(gen/elements ["0" "1" "db8" "2001" "ffff" "FFFF" "0000" "12345" "g"])
               (gen/fmap #(str/lower-case (str %)) gen/nat)]))

(def address
  "Text like an IPv4 or IPv6 address, which may not be one."
  (gen/one-of [(gen/fmap #(str/join "." %) (gen/vector (gen/choose 0 300) 1 5))
               (gen/fmap #(str/join ":" %) (gen/vector hextet 1 10))
               (gen/fmap (fn [[a b]] (str (str/join ":" a) "::" (str/join ":" b)))
                         (gen/tuple (gen/vector hextet 0 4) (gen/vector hextet 0 4)))
               (gen/fmap (fn [[a port]] (str "[" a "]:" port))
                         (gen/tuple (gen/elements ["::1" "2001:db8::1" "x"])
                                    (gen/choose 0 65535)))
               (gen/elements ["unknown" "_hidden" "" " " "::ffff:1.2.3.4" "fe80::1%eth0"])
               gen/string-ascii]))

(def field
  "A field value of addresses, as X-Forwarded-For or Forwarded holds them,
  or random text."
  (gen/one-of [(gen/fmap #(str/join ", " %) (gen/vector address 0 4))
               (gen/fmap #(str/join ", " (map (fn [a] (str "for=\"" a "\";proto=https")) %))
                         (gen/vector address 0 4))
               (gen/fmap #(apply str %)
                         (gen/vector (gen/elements ["for=" "\"" "\\" "," ";" "=" "[" "]" ":" "1"])
                                     0 20))
               gen/string-ascii]))

(def request
  "A Ring request with random addresses, or none."
  (gen/let [remote-addr (gen/one-of [(gen/return nil) address])
            xff         (gen/one-of [(gen/return nil) field])
            forwarded   (gen/one-of [(gen/return nil) field])]
    {:remote-addr remote-addr
     :headers     (cond-> {}
                    xff       (assoc "x-forwarded-for" xff)
                    forwarded (assoc "forwarded" forwarded))}))
