(ns spectrum.core-specs
  (:require [clojure.core :as core]
            [clojure.spec :as s])
  (:import (java.lang Iterable)
           (java.util Map)
           (clojure.lang ISeq
                         Seqable)))


;;; specs for clojure.core fns, used as hacks/testing. Delete as appropriate

(s/fdef clojure.core/int? :args (s/cat :x any?) :ret boolean?)
(s/fdef clojure.core/integer? :args (s/cat :x any?) :ret boolean?)

(s/fdef clojure.core/even? :args (s/cat :n integer?) :ret boolean?)

(s/fdef clojure.core/seq? :args (s/cat :x any?) :ret boolean?)

(defn namespace? [x] (instance? clojure.lang.Namespace))

(defn array? [x] (.isArray ^Class (class x)))

(s/fdef clojure.core/in-ns :args (s/cat :ns symbol?) :ret namespace?)

(s/fdef clojure.core/seq :args (s/cat :coll seqable?) :ret seq?)

(s/fdef clojure.core/map? :args (s/cat :x any?) :ret boolean?)

(s/fdef clojure.core/not :args (s/cat :x any?) :ret boolean?)
