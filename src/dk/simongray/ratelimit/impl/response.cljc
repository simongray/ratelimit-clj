(ns ^:no-doc dk.simongray.ratelimit.impl.response
  "The headers and responses that follow from a decision: Retry-After,
  the RateLimit fields and 429 Too Many Requests."
  (:require [clojure.string :as str]))

#?(:clj (set! *warn-on-reflection* true))

(def drafts
  "The versions of draft-ietf-httpapi-ratelimit-headers whose fields the
  library writes."
  #{:draft-11})

(defn- whole-seconds
  "The `seconds` rounded up to a whole number."
  [seconds]
  (long (Math/ceil seconds)))

;; RFC 9110, section 10.2.3, as delay-seconds, which don't depend on the
;; clocks agreeing. Rounded up, a client that waits that long gets through.
(defn retry-after
  "The Retry-After value of the `decision`, or nil when waiting won't
  help: the longest wait of the policies that refused, in whole seconds."
  [{:keys [results] :as decision}]
  (some->> (keep :retry-after results)
           not-empty
           (apply max)
           whole-seconds
           (max 1)
           str))

;; RFC 9651, section 4.1.6
(defn sf-string
  "The text `s` as a Structured Field String."
  [s]
  (if (or (str/includes? s "\\") (str/includes? s "\""))
    (str "\"" (str/escape s {\\ "\\\\" \" "\\\""}) "\"")
    (str "\"" s "\"")))

(defn- whole-window
  "The `window` of a policy in whole seconds, or nil when it isn't a
  whole number of them, since w is an Integer."
  [window]
  (when (and (<= 1 window) (== window (Math/floor window)))
    (long window)))

;; draft-ietf-httpapi-ratelimit-headers-11, section 3
(defn- policy-item
  "The RateLimit-Policy item that describes the policy of the `result`."
  [{:keys [name quota window] :as result}]
  (let [w (whole-window window)]
    (str (sf-string name) ";q=" quota (when w ";w=") w)))

;; draft-ietf-httpapi-ratelimit-headers-11, section 4. The t is the time
;; until the available quota next goes up, so the client can't use more
;; than r before then. On a refusal, it's no later than Retry-After.
(defn- state-item
  "The RateLimit item of the `result`."
  [{:keys [name remaining refill-after] :as result}]
  (str (sf-string name) ";r=" remaining
       (when refill-after ";t=") (some-> refill-after whole-seconds)))

;; RFC 9651, section 4.1.1: the members of a List joined by a comma and a
;; space
(defn headers
  "The RateLimit fields of the `decision` as the `draft` defines them, or
  nil when no policy applied."
  [{:keys [results] :as decision} draft]
  (when (seq results)
    (case draft
      :draft-11 {"RateLimit-Policy" (str/join ", " (map policy-item results))
                 "RateLimit"        (str/join ", " (map state-item results))})))

;; RFC 6585, section 4: the response explains the condition, may say how
;; long to wait, and must not be stored by a cache
(defn too-many-requests
  "The 429 Too Many Requests response to the request of the `decision`."
  [decision]
  (let [seconds (retry-after decision)]
    {:status  429
     :headers (cond-> {"Cache-Control" "no-store"
                       "Content-Type"  "text/plain;charset=utf-8"}
                seconds (assoc "Retry-After" seconds))
     :body    (cond
                (= "1" seconds) "Too many requests. Try again in 1 second."
                seconds         (str "Too many requests. Try again in "
                                     seconds " seconds.")
                :else           "Too many requests.")}))

(defn with-headers
  "The Ring `response` with the `headers` added, except the ones that it
  has already, in any letter case, or nil when it's nil."
  [response headers]
  (when response
    (let [present (into #{} (map str/lower-case) (keys (:headers response)))]
      (reduce-kv (fn [response name value]
                   (cond-> response
                     (not (present (str/lower-case name)))
                     (assoc-in [:headers name] value)))
                 response
                 headers))))
