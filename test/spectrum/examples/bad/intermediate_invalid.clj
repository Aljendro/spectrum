(ns spectrum.examples.bad.intermediate-invalid
  (:require [clojure.spec :as s]))

(s/fdef foo :args (s/cat :x string?) :ret string?)
(defn foo [x]
  (inc x)
  x)
