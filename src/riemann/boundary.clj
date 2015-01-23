(ns riemann.boundary
  "Forwards events to Boundary Premium."
  (:require [clj-http.client :as client]
            [cheshire.core :as json]
            [clojure.string :as s]))

(def ^:private base-uri "Boundary API base URI."
  "https://premium-api.boundary.com")
(def ^:private version "Boundary API version to use."
  "v1")

(defn ^:private boundarify
  "As of Boundary's specs, metric ids can only contain characters
  matching \"[A-Z0-9_]\", thus all other characters will be stripped
  and the remaining ones will be upcased.

  To preserve structure, unacceptable characters will be removed
  *after* substituting spaces with underscores.

  Should an organization name be provided, it will be placed before
  the name of the service.

  Last but not least, if after all the manipulation of the string, no
  characters remain (i.e. empty string), an exception is thrown.

  Examples:

  ((boundarify) \"foo\") => \"FOO\"
  ((boundarify) \"foo bar\") => \"FOO_BAR\"
  ((boundarify) \"foo@\") => \"FOO\"
  ((boundarify) \"foo@bar\") => \"FOOBAR\"
  ((boundarify \"org\") \"foo\") => \"ORG_FOO\"
  ((boundarify) \"!#@\") => exception
  ((boundarify \"org\") \"!#@\") => exception
  "
  [& [organization]]
  (fn [service]
    (let [good-ones (s/replace (s/upper-case (s/replace service #"\s+" "_"))
                               #"[^A-Z0-9_]" "")
          res (if-not (nil? organization)
                (str (s/upper-case organization) "_" good-ones)
                good-ones)]
      (if-not (empty? good-ones)
        res
        (throw (RuntimeException.
                (str "can't accept the given service string \""
                     service "\" as metric id")))))))

(defn ^:private packer-upper
  "Returns a function that packs up the events in a form suitable for
  Boundary's API.

  If a metric-id is given, it will be used for all the events in the
  pack. Otherwise, every single event service is \"boundarified\". In
  both cases, organization is prepended if given."
  [metric-id organization]
  (let [helper
        #(vector
          (:host %)
          `~(cond (and (nil? metric-id) (nil? organization))
                  ((boundarify) (:service %))
                  (and (nil? metric-id) (not (nil? organization)))
                  ((boundarify organization) (:service %))
                  (and (not (nil? metric-id)) (nil? organization))
                  ((boundarify) metric-id)
                  :else
                  ((boundarify organization) metric-id))
          (:metric %)
          (:time %))]
    (fn [events]
      (vec (map helper events)))))

(defn boundary
  "Returns a function used to generate specific senders (like mailer)
  that takes two optional named arguments, namely :metric-id
  and :organization, that modify which metric the events are sent to.

  Specifically, if :metric-id is supplied, every single event is sent
  to that metric, otherwise the event's :service field is used to
  construct the destination Boundary's metric id. In both cases,
  organization is prepended if non nil.

  Examples:

  (def bdry (boundary eml tkn))
  (when :foo (bdry)) => builds the destination metric id with :service
  (when :foo (bdry :metric-id \"METRIC_ID\")) => sends to METRIC_ID"
  [email token]
  (fn [& {:keys [metric-id org] :or {metric-id nil org nil}}]
    (let [pack-up (packer-upper metric-id org)]
      (fn [events]
        (let [pack (pack-up events)
              req-map {:scheme :https
                       :basic-auth [email token]
                       :headers {"Content-Type" "application/json"}
                       :body (json/generate-string pack {:pretty true})}]
          (client/post (s/join "/" [base-uri version "measurements"])
                       req-map))))))
