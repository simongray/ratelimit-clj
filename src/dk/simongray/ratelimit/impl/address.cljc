(ns ^:no-doc dk.simongray.ratelimit.impl.address
  "The address of a client: from the request or from the fields that
  trusted proxies add, with an IPv6 address cut to its prefix."
  (:require [clojure.string :as str]))

#?(:clj (set! *warn-on-reflection* true))

;; RFC 9110, section 5.6.4: a quoted-string, whose commas don't separate
;; the elements of a list
(defn split-list
  "The elements of the field value `s`, split at the commas outside
  quoted strings and trimmed, without the empty ones."
  [^String s]
  (let [n (count s)]
    (loop [i 0, start 0, quoted? false, out []]
      (if (<= n i)
        (let [element (str/trim (subs s start))]
          (cond-> out (seq element) (conj element)))
        (let [c (.charAt s i)]
          (cond
            (and quoted? (= c \\))  (recur (+ i 2) start true out)
            (= c \")                (recur (inc i) start (not quoted?) out)
            (and (not quoted?)
                 (= c \,))          (let [element (str/trim (subs s start i))]
                                      (recur (inc i) (inc i) false
                                             (cond-> out
                                               (seq element) (conj element))))
            :else                   (recur (inc i) start quoted? out)))))))

(defn- unquote-value
  "The token or quoted string `s` as text."
  [s]
  (if (and (< 1 (count s)) (str/starts-with? s "\"") (str/ends-with? s "\""))
    (str/replace (subs s 1 (dec (count s))) #"\\(.)" "$1")
    s))

;; RFC 7239, sections 4 and 5.2: forwarded-pairs separated by semicolons,
;; with names that ignore case
(defn forwarded-for
  "The for parameter of the Forwarded element `s`, or nil."
  [s]
  (some (fn [pair]
          (when-let [eq (str/index-of pair "=")]
            (when (= "for" (str/lower-case (str/trim (subs pair 0 eq))))
              (unquote-value (str/trim (subs pair (inc eq)))))))
        (str/split s #";")))

(defn forwarded-addresses
  "The addresses that the proxies added to the field `header` of the
  Ring `request`, in order, with nil for an element without one."
  [{:keys [headers] :as request} header]
  (when-let [s (get headers header)]
    (cond->> (split-list s)
      (= "forwarded" header) (mapv forwarded-for))))

;; MDN on X-Forwarded-For: behind n trusted proxies, the nth address from
;; the right is the one that the outermost proxy added. With fewer, every
;; address came from a trusted proxy, and the leftmost is the earliest.
(defn forwarded
  "The address that the outermost of `n` proxies added to the field
  `header` of the Ring `request`, or nil when it has none."
  [request header n]
  (when-let [addresses (not-empty (forwarded-addresses request header))]
    (nth addresses (max 0 (- (count addresses) n)))))

(defn- hex-digit
  "The value of the ASCII hex digit `c`, or nil."
  [c]
  (case c
    \0 0, \1 1, \2 2, \3 3, \4 4, \5 5, \6 6, \7 7, \8 8, \9 9
    (\a \A) 10, (\b \B) 11, (\c \C) 12, (\d \D) 13, (\e \E) 14, (\f \F) 15
    nil))

(defn- hextet
  "The value of the group `s`, which has up to 4 hex digits, or nil."
  [^String s]
  (let [n (count s)]
    (when (<= 1 n 4)
      (loop [i 0, value 0]
        (if (= i n)
          value
          (when-let [d (hex-digit (.charAt s i))]
            (recur (inc i) (+ (* 16 value) (long d)))))))))

(defn- ipv4-hextets
  "The two groups of the dotted IPv4 address `s`, or nil."
  [s]
  (when-let [[_ & octets]
             (re-matches #"(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})" s)]
    (let [[a b c d] (map parse-long octets)]
      (when (every? #(<= % 255) [a b c d])
        [(+ (* a 256) b) (+ (* c 256) d)]))))

(defn- format-ipv4
  "The two `groups` of an IPv4 address as dotted text."
  [[a b :as groups]]
  (str (quot a 256) "." (rem a 256) "." (quot b 256) "." (rem b 256)))

(defn- groups
  "The groups of `s`, the part of an IPv6 address on one side of the ::,
  or nil when one doesn't parse. When `last?`, it may end in an IPv4
  address."
  [s last?]
  (if (= "" s)
    []
    (let [parts   (str/split s #":" -1)
          tail    (peek parts)
          v4      (when (and last? (str/includes? tail "."))
                    (ipv4-hextets tail))
          hextets (mapv hextet (cond-> parts v4 pop))]
      (when (and (not-any? nil? hextets)
                 (or v4 (not (str/includes? tail "."))))
        (into hextets v4)))))

;; RFC 4291, section 2.2
(defn parse-ipv6
  "The 8 groups of the IPv6 address `s`, or nil when it isn't one. A zone,
  e.g. %eth0, is left out."
  [s]
  (let [s     (first (str/split s #"%" 2))
        parts (str/split s #"::" -1)]
    (case (count parts)
      1 (let [gs (groups s true)]
          (when (= 8 (count gs)) gs))
      2 (let [head (groups (first parts) false)
              tail (groups (second parts) true)]
          (when (and head tail (< (+ (count head) (count tail)) 8))
            (-> head
                (into (repeat (- 8 (count head) (count tail)) 0))
                (into tail))))
      nil)))

(defn- keep-prefix
  "The 8 `groups` with all but the first `prefix` bits set to 0."
  [groups prefix]
  (let [prefix (long prefix)]
    (into []
          (map-indexed (fn [^long i ^long g]
                         (let [bits (min 16 (max 0 (- prefix (* 16 i))))]
                           (bit-and g 0xFFFF
                                    (bit-shift-left 0xFFFF (- 16 bits))))))
          groups)))

(defn- longest-zeros
  "The start and length of the longest run of 0 in the `groups`, the
  first of them on a tie, or nil when no run has two or more."
  [groups]
  (loop [i 0, best-start 0, best-length 0, length 0]
    (if (< i 8)
      (let [length (if (zero? (long (nth groups i))) (inc length) 0)]
        (if (> length best-length)
          (recur (inc i) (inc (- i length)) length length)
          (recur (inc i) best-start best-length length)))
      (when (<= 2 best-length)
        [best-start best-length]))))

(defn- hex
  "The group `g` in lower-case hex, without leading zeros."
  [g]
  #?(:clj  (Integer/toHexString g)
     :cljs (.toString g 16)))

;; RFC 5952, section 4
(defn format-ipv6
  "The 8 `groups` of an IPv6 address as text: in lower case, without
  leading zeros, and with the longest run of two or more 0 as ::."
  [groups]
  (if-let [[start length] (longest-zeros groups)]
    (str (str/join ":" (map hex (subvec groups 0 start)))
         "::"
         (str/join ":" (map hex (subvec groups (+ start length)))))
    (str/join ":" (map hex groups))))

(defn- strip-port
  "The address `s` without the brackets of an IPv6 address or a port."
  [s]
  (let [colon (str/index-of s ":")]
    (cond
      (str/starts-with? s "[")
      (let [end (str/index-of s "]")]
        (if end (subs s 1 end) s))

      ;; one colon is an IPv4 address with a port
      (and colon (= colon (str/last-index-of s ":")))
      (subs s 0 colon)

      :else
      s)))

;; RFC 4291, section 2.5.5.2: an IPv4-mapped address is an IPv4 address
(defn address-key
  "The address `s` as a key: an IPv4 address as it is, and an IPv6 address
  cut to its first `prefix` bits, e.g. 2001:db8::/64. An address that
  doesn't parse is the key as it is, trimmed."
  [s prefix]
  (when s
    (let [s (strip-port (str/trim s))]
      (if-let [groups (when (str/includes? s ":") (parse-ipv6 s))]
        (if (= [0 0 0 0 0 0xFFFF] (subvec groups 0 6))
          (format-ipv4 (subvec groups 6))
          (cond-> (format-ipv6 (keep-prefix groups prefix))
            (< prefix 128) (str "/" prefix)))
        s))))

(defn client-address
  "The address of the client of the Ring `request`, with the `opts` of
  client-address in the root namespace."
  ([request]
   (client-address request {}))
  ([{:keys [remote-addr] :as request}
    {:keys [proxies header prefix]
     :or   {proxies 0
            header  "x-forwarded-for"
            prefix  64}
     :as   opts}]
   (address-key (or (when (pos? proxies)
                      (forwarded request (str/lower-case header) proxies))
                    remote-addr)
                prefix)))
