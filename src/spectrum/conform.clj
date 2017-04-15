(ns spectrum.conform
  (:require [clojure.core.memoize :as memo]
            [clojure.reflect :as reflect]
            [clojure.spec :as s]
            [clojure.spec.gen :as gen]
            [clojure.set :as set]
            [clojure.string :as str]
            [spectrum.util :refer (fn-literal? print-once strip-namespace var-name queue)]
            [spectrum.data :as data :refer (*a*)]
            [spectrum.java :as j])
  (:import (clojure.lang Var Keyword)
           java.io.Writer))

(declare conform)
(declare valid?)
(declare parse-spec)
(declare value)

(declare and-spec?)
(declare class-spec?)
(declare pred-spec?)
(declare regex?)

(declare or-spec)
(declare class-spec)
(declare pred-spec)
(declare spect-generator)
(declare conform-args?)

(declare value-invoke)

(declare re-conform)
(declare re-explain)

(defprotocol Spect
  (conform* [spec x]
    "Return the conforming value if value x conforms to spec, else false")
  (explain* [spec path via in x]))

(defprotocol Compound
  (map- [spec f])
  (filter- [spec f]))

(s/fdef compound-spec :args (s/cat :x any?) :ret boolean?)
(defn compound-spec? [x]
  (satisfies? Compound x))

(defprotocol DependentSpecs
  (dependent-specs [spec]))

(defn dependent-specs? [s]
  (satisfies? DependentSpecs s))

(defn no-dependent-specs [s]
  (extend s DependentSpecs {:dependent-specs (fn [this] #{})}))

(defn map* [f spec]
  (map- spec f))

(defn filter* [f spec]
  (filter- spec f))

(defn remove* [f spec]
  (filter- spec (complement f)))

(s/fdef spect? :args (s/cat :x any?) :ret boolean?)
(defn spect? [x]
  (and (instance? clojure.lang.IRecord x) (satisfies? Spect x)))

(defprotocol WillAccept
  (will-accept [spec]
    "Return a spect that will make (derivative spec x) return accept, or nil"))

(s/fdef spec->class :args (s/cat :s ::spect) :ret (s/nilable ::j/java-type))
(defprotocol SpecToClass
  (spec->class [s]
    "If this spec checks for an instance of a class, return it, else nil"))

(s/fdef spec->class? :args (s/cat :x any?) :ret boolean?)
(defn spec->class? [x]
  (satisfies? SpecToClass x))

(defprotocol RegexConform
  (regex-conform [spec seq]
    "True if this seq conforms to the spec"))

(defprotocol Regex
  (derivative
    [spec x]
    "Given a parsed spec, return the derivative")
  (re-explain* [spec path via in x])
  (empty-regex [this]
    "The empty pattern for this regex")
  (accept-nil? [this]
    "True if it is valid for this pattern to match no data")
  (return [this]
    "Given a completed regex parse, return the conform matching value")
  (add-return [this ret k]
    "Add ret to the return data of this regex, with optional key k"))

(defprotocol FirstRest
  (first* [this])
  (rest* [this]
    "Return a spect or nil.

    Returns nil if it's legal to call rest on this, but there are no
    items. Return (invalid) if it's not legal to call rest,
    i.e. (value :foo)")
  (seq*? [this]
    "Truthy if first will return a value"))

(defprotocol Truthyness
  (truthyness [this]
    "The truthyness of this spec, if it appeared in an `if`. Returns :truthy, :falsey or :ambiguous"))

(s/fdef invoke :args (s/cat :s spect? :args spect?) :ret spect?)
(defprotocol Invoke
  (invoke [s args]
    "if code calls (s args), return the expected return type"))

(s/fdef invoke? :args (s/cat :x any?) :ret boolean?)
(defn invoke? [x]
  (satisfies? Invoke x))

(s/fdef keyword-invoke :args (s/cat :s spect? :args spect?) :ret spect?)
(defprotocol KeywordInvoke
  (keyword-invoke [s args]
    "If code calls (:foo spec), return the expected type"))

(defn keyword-invoke? [s]
  (satisfies? KeywordInvoke s))

(s/def ::spect (s/with-gen (s/and spect? map?)
                 (fn []
                   spect-generator)))

(defn fn-invoke? [x]
  (and (seq? x) (symbol? (first x))))


;; spect-like is hard. It should be 'anything that can be parsed into
;; a spect (spec, symbol, var, keyword)' + 'anything that can be
;; parsed into a value (any?)'. That's pretty wide, so this is just
;; any?. There may be a narrower type, but I'm not sure what it is.

;; a thing that parse-spec will return a valid ::spect on
(s/def ::spect-like any? ;; (s/or :spec s/spec? :spect ::spect :key keyword? :sym symbol? :var var?)
  )

(s/fdef conform* :args (s/cat :spec spect? :x any?))

(defrecord Accept [ret])

(defrecord Reject [])

(defn accept [x]
  (map->Accept {:ret x}))

(def reject (map->Reject {}))

(defn accept? [x]
  (instance? Accept x))

(defn reject? [x]
  (instance? Reject x))

(defrecord Invalid [a-loc form message]
  Spect
  (conform* [this x]
    false)
  Truthyness
  (truthyness [this]
    :ambiguous)
  WillAccept
  (will-accept [this]
    reject))

(s/def :invalid/message string?)
(s/def :invalid/form any?)

(s/fdef invalid :args (s/cat :args (s/keys :req-un [:invalid/message] :opt-un [:invalid/form] )))
(defn invalid [{:keys [form a-loc message] :as args}]
  (let [a *a*
        form (if (find args form)
               form
               (:form *a*))
        a-loc (if (find args :a-loc)
                a-loc
                (select-keys *a* [:file :line :column]))]
    (map->Invalid {:form form :a-loc a-loc :message message})))

(defn first-rest? [x]
  (satisfies? FirstRest x))

(defn maybe-first* [ps]
  (if (first-rest? ps)
    (first* ps)
    (first ps)))

(defn maybe-rest* [ps]
  (if (first-rest? ps)
    (rest* ps)
    (rest ps)))

(defn second* [ps]
  (first* (rest* ps)))

(defn nth* [ps i]
  (if (and (seq ps) (not (neg-int? i)))
    (if (= 0 i)
      (first* ps)
      (recur (rest* ps) (dec i)))
    nil))

(def first-rest-singular-impl
  {:first* (fn [this] reject)
   :rest* (fn [this] reject)
   :seq*? (fn [this] false)})

(defn first-rest-singular
  "FirstRest implementation for a type that is singular"
  [s]
  (extend s FirstRest first-rest-singular-impl))

(s/fdef regex? :args (s/cat :x any?) :ret boolean?)

(defn regex? [x]
  (and (spect? x) (satisfies? Regex x) (or (:ps x) (:ret x))))

(declare reject)

(declare map->RegexAlt)

(extend-type Accept
  Spect
  (conform* [spec x]
    (and (accept? x) (= (:ret spec) (:ret x))))
  Regex
  (derivative [spec x]
    reject)
  (empty-regex [spec]
    accept)
  (accept-nil? [this]
    true)
  (return [this]
    (:ret this))
  (add-return [this ret k]
    ret)
  WillAccept
  (will-accept [this]
    reject))

(first-rest-singular Accept)

(extend-type Reject
  Spect
  (conform* [spec x]
    false)
  Regex
  (derivative [spec x]
    reject)
  (empty-regex [spec]
    reject)
  (accept-nil? [this]
    false)
  (return [this]
    (invalid {:message "reject"}))
  (add-return [this ret k]
    nil)
  WillAccept
  (will-accept [this]
    reject)
  Truthyness
  (truthyness [this]
    :falsey)
  SpecToClass
  (spec->class [this]
    nil))

(first-rest-singular Reject)

(s/fdef invalid? :args (s/cat :x any?) :ret boolean?)
(defn invalid? [x]
  (or (instance? Invalid x)
      (= ::invalid x)
      (reject? x)))

(defrecord Unknown [form file line column])

(defn unknown? [x]
  (instance? Unknown x))

(s/fdef unknown :args (s/cat :args (s/keys :req-un [:invalid/message] :opt-un [:invalid/form])))
(defn unknown
  [{:keys [form a-loc message] :as args}]
  (let [a *a*
        form (if (find args form)
               form
               (:form *a*))
        a-loc (if (find args a-loc)
                a-loc
                (select-keys *a* [:file :line :column]))]
    (map->Unknown {:form form :a-loc a-loc :message message})))

(defn unknown-invoke [spec args]
  (unknown {:message (format "don't know how to invoke %s" spec)}))

(extend-type Unknown
  Spect
  (conform* [this x]
    false)
  Truthyness
  (truthyness [this]
    :ambiguous)
  SpecToClass
  (spec->class [s]
    Object)
  WillAccept
  (will-accept [this]
    reject)
  Invoke
  (invoke [spec args]
    (unknown-invoke spec args)))

(no-dependent-specs Unknown)

(defn known? [x]
  (not (unknown? x)))

(extend-type nil
  Regex
  (derivative [spec x]
    reject)
  (empty-regex [spec]
    reject)
  (accept-nil? [this]
    false)
  (return [this]
    ::s/nil)
  (add-return [this r k]
    r)
  Truthyness
  (truthyness [this]
    :falsey)
  FirstRest
  (first* [this]
    nil)
  (rest* [this]
    nil)
  (seq*? [this]
    false))

(extend-type Invalid
  Regex
  (derivative [spec x]
    reject)
  (empty-regex [spec]
    reject)
  (accept-nil? [this]
    false)
  (return [this]
    ::s/nil)
  (add-return [this r k]
    r))

(first-rest-singular Invalid)
(no-dependent-specs Invalid)

(s/fdef spec-dx :args (s/cat :spec ::spect :x ::spect) :ret ::spect)
(defn spec-dx [spec x]
  (if (valid? spec x)
    (map->Accept {:ret x})
    reject))

(extend-type Object
  Regex
  (return [this]
    this))

(def spect-regex-impl
  {:derivative spec-dx
   :empty-regex (fn [this]
                  reject)
   :accept-nil? (fn [this]
                  false)
   :return (fn [this]
             this)
   :add-return (fn [this ret k]
                 nil)})

(defn extend-regex
  "extends the Regex protocol to a non-regex Spect"
  [s]
  (extend s Regex spect-regex-impl))

(defn will-accept-this
  "extend the spec to WillAccept itself"
  [s]
  (extend s WillAccept {:will-accept (fn [this] this)}))

(extend-regex Unknown)

(defn maybe-alt-
  "If both regexes are valid, returns Alt r1 r2, else first non-reject one"
  [r1 r2]
  (if (and r1 r2 (not (reject? r1)) (not (reject? r2)))
    (map->RegexAlt {:ps [r1 r2]})
    (first (remove reject? [r1 r2]))))

(declare map->RegexCat)

(s/def :cat/ks (s/nilable (s/coll-of keyword?)))
(s/def :cat/ps (s/coll-of any?))
(s/def :cat/fs (s/nilable coll?))
(s/def :cat/ret coll?)

(s/fdef map->RegexCat :args (s/cat :x (s/keys :opt-un [:cat/ks :cat/ps :cat/fs] :req-un [:cat/ret])) :ret regex?)

(s/fdef new-regex-cat :args (s/cat :ps (s/nilable (s/coll-of any?)) :ks (s/nilable (s/coll-of keyword?)) :fs (s/nilable coll?) :ret coll?) :ret regex?)

(defn new-regex-cat [[p0 & pr :as ps] [k0 & kr :as ks] [f0 & fr :as forms] ret]
  (if (and ps
           (every? #(not (reject? %)) ps)
           (every? identity ps))
    (if (accept? p0)
      (let [ret (conj ret (if k0 {k0 (:ret p0)} (:ret p0)))]
        (if pr
          (map->RegexCat {:ps pr
                          :ks kr
                          :forms fr
                          :ret ret})
          (accept ret)))
      (map->RegexCat {:ps ps
                      :ks ks
                      :forms forms
                      :ret ret}))
    reject))

(s/fdef cat- :args (s/cat :ps (s/coll-of ::spect-like)) :ret spect?)
(defn cat- [ps]
  (new-regex-cat ps nil nil []))

(defrecord RegexCat [ps ks forms ret])

(extend-type RegexCat
  Spect
  (conform* [spec data]
    (re-conform spec data))
  (explain* [spec path via in x]
    (re-explain spec path via in x))
  Regex
  (derivative [{:keys [ps ks forms ret] :as this} x]
    (let [v (let [ps (map parse-spec ps)
                  [p0 & pr] ps
                  [k0 & kr] ks
                  [f0 & fr] forms]
              (maybe-alt-
               (new-regex-cat (cons (derivative p0 x) pr) ks forms ret)
               (if (accept-nil? p0)
                 (derivative (new-regex-cat pr kr fr (add-return p0 ret k0)) x)
                 reject)))]
      v))

  (re-explain* [{:keys [ps ks forms ] :as spec} path via in x]
    (let [pkfs (map vector
                    ps
                    (or (seq ks) (repeat nil))
                    (or (seq forms) (repeat nil)))
          [pred k form] (if (= 1 (count pkfs))
                          (first pkfs)
                          (first (remove (fn [[p]] (accept-nil? p)) pkfs)))
          path (if k (conj path k) path)]
      (if (and (empty? x) (not pred))
        [{:path path
          :reason "Insufficient input"
          :pred pred
          :val ()
          :via via
          :in in}]
        (explain* pred path via in x))))

  (accept-nil? [this]
    (->>
      this
     :ps
     (map parse-spec)
     (every? accept-nil?)))
  (return [{:keys [ps ks ret] :as this}]
    (return (add-return (some-> ps first parse-spec) ret (first ks))))
  (add-return [this r k]
    (let [ret (return this)]
      (if (empty? ret)
        r
        (conj r (if k {k ret} ret)))))
  WillAccept
  (will-accept [this]
    (if-let [p (some-> this :ps first parse-spec)]
      (will-accept p)
      nil))
  FirstRest
  (first* [this]
    (let [p (some-> this :ps first parse-spec)]
      (if (and (and (first-rest? p) (regex? p)))
        (first* p)
        p)))
  (rest* [this]
    (let [dx (derivative this (will-accept this))]
      (when (not (or (spect? dx) (nil? dx)))
        (println "rest* not spect:" this dx))
      (assert (or (spect? dx) (nil? dx)))
      (if (not (accept? dx))
        dx
        nil)))
  (seq*? [this]
    (pos? (count (:ps this))))
  Truthyness
  (truthyness [this]
    :truthy))

(s/fdef cat-spec? :args (s/cat :x any?) :ret boolean?)
(defn cat-spec? [x]
  (instance? RegexCat x))

(s/fdef cat-spec :args (s/cat :ks (s/* keyword?) :ps (s/* any?)) :ret cat-spec?)
(defn cat-spec [ks ps]
  (new-regex-cat ps ks nil []))

(declare map->RegexSeq)

(defn new-regex-seq [ps ret splice forms]
  (if (every? #(not (reject? %)) ps)
    (if (accept? (first ps))
      (map->RegexSeq {:ps (vec (rest ps))
                      :forms forms
                      :ret ((fnil conj []) ret (:ret (first ps)))
                      :splice splice})
      (map->RegexSeq {:ps (vec ps)
                      :forms forms
                      :ret ret
                      :splice splice}))
    reject))

(defrecord RegexSeq [ps ks forms splice ret]
  Spect
  (conform* [spec data]
    (re-conform spec data))
  (explain* [spec path via in x]
    (re-explain spec path via in x))
  Regex
  (derivative [this x]
    (let [p (first ps)
          p (parse-spec p)]
      (new-regex-seq [(derivative p x) p] ret splice forms)))
  (accept-nil? [this]
    true)
  (return [this]
    ret)
  (add-return [this r k]
    (let [ret (return this)]
      (if (empty? ret)
        r
        ((if splice into conj) r (if k {k ret} ret)))))
  FirstRest
  (first* [this]
    (some-> ps first parse-spec))
  (rest* [this]
    (let [ret (derivative this (will-accept this))]
      (assert (or (spect? ret) (nil? ret)))
      ret))
  (seq*? [this]
    true)
  WillAccept
  (will-accept [this]
    (some-> ps first parse-spec will-accept))
  Truthyness
  (truthyness [this]
    :truthy)
  SpecToClass
  (spec->class [this]
    clojure.lang.ISeq)
  DependentSpecs
  (dependent-specs [this]
    #{(pred-spec #'seq?) (pred-spec #'seqable?)}))

(defn regex-seq? [x]
  (instance? RegexSeq x))

(defn regex-seq [s & [{:keys [splice]}]]
  (map->RegexSeq {:ps [s]
                  :forms nil
                  :ret nil
                  :splice splice}))

(defn seq-of [s]
  (map->RegexSeq {:ps [s]}))

(defn filter-alt [ps ks forms f]
  (if (or ks forms)
    (let [pks (->> (map vector ps
                        (or (seq ks) (repeat nil))
                        (or (seq forms) (repeat nil)))
                   (filter #(-> % first f)))]
      [(seq (map first pks)) (when ks (seq (map second pks))) (when forms (seq (map #(nth % 2) pks)))])
    [(seq (filter f ps)) ks forms]))

(defn new-regex-alt [ps ks forms]
  (let [[[p1 & pr :as ps] [k1 :as ks] forms] (filter-alt ps ks forms #(not (reject? %)))]
    (when ps
      (let [ret (map->RegexAlt {:ps ps :ks ks :forms forms})]
        (if (nil? pr)
          (if k1
            (if (accept? p1)
              (do
                (accept [k1 (:ret p1)]))
              ret)
            p1)
          ret)))))

(defrecord RegexAlt [ps ks forms ret]
  Spect
  (conform* [spec data]
    (re-conform spec data))
  (explain* [spec path via in x]
    (re-explain spec path via in x))
  Regex
  (derivative [this x]
    (let [ps (map parse-spec ps)]
      (new-regex-alt (mapv #(derivative % x) ps) ks forms)))

  (empty-regex [this]
    (map->RegexAlt {:ps (map empty-regex ps)
                    :ks ks
                    :forms forms}))
  (accept-nil? [this]
    (let [ps (map parse-spec ps)]
      (some accept-nil? ps)))
  (return [this]
    (let [ps (map parse-spec ps)
          [[p0] [k0]] (filter-alt ps ks forms accept-nil?)
          r (if (nil? p0)
              nil
              (return p0))]
      (if k0
        [k0 r]
        r)))
  (add-return [this r k]
    (let [ret (return this)]
      (if (= ret ::s/nil)
        r
        (conj r (if {k ret} ret)))))
  (re-explain* [spec path via in x]
    (if (empty? x)
      [{:path path
        :reason "Insufficient input"
        :val ()
        :via via
        :in in}]
      (apply concat
             (map (fn [k form p]
                    (explain* p
                              (if k (conj path k) path)
                              via
                              in
                              x))
                  (or (seq ks) (repeat nil))
                  (or (seq forms) (repeat nil))
                  ps))))

  FirstRest
  (first* [this]
    (some-> ps first parse-spec))
  (rest* [this]
    (derivative this (will-accept this)))
  (seq*? [this]
    (pos? (count (:ps this))))
  WillAccept
  (will-accept [this]
    (some-> ps first parse-spec will-accept))
  Truthyness
  (truthyness [this]
    (let [b (distinct (map truthyness ps))]
      (if (= 1 (count b))
        (first b)
        :ambiguous))))

(declare new-regex-plus)

(defn get-spec [spec-name]
  (let [s (s/get-spec spec-name)
        cs (parse-spec s)]
    (if-let [t (data/get-invoke-transformer spec-name)]
      (assoc cs :transformer t)
      cs)))

(defn parse-spec*-dispatch [x]
  (cond
    (s/spec? x) :spec
    (s/regex? x) (:clojure.spec/op x)
    (spect? x) :literal
    (symbol? x) :fn-sym
    (var? x) :var
    (fn-literal? x) :fn-literal
    (keyword? x) :keyword
    (and (seq? x) (symbol? (first x))) (first x)
    (coll? x) :coll
    (class? x) :class
    :else :literal))

(defmulti parse-spec* #'parse-spec*-dispatch)

(defmethod parse-spec* :literal [x]
  (if (spect? x)
    x
    (value x)))

(defmethod parse-spec* :class [x]
  (class-spec x))

(defn maybe-resolve [x]
  (try
    (resolve x)
    (catch Exception e
      nil)))

(defn parse-spec [x]
  (try
    (cond
      (spect? x) x
      (and (symbol? x) (maybe-resolve x)) (parse-spec* (s/spec-impl x (resolve x) nil nil))
      (var? x) (parse-spec* (s/spec-impl (var-name x) x nil nil))
      (= ::s/nil x) (value nil)
      (s/spec? x) (parse-spec* (s/form x))
      (s/regex? x) (parse-spec* x)
      :else (parse-spec* x))
    (catch IllegalArgumentException e
      (println "don't know how to parse:" x)
      (throw e))))

(defmethod parse-spec* :spec [x]
  (parse-spec* (s/form x)))

(defmethod parse-spec* :keyword [x]
  (if (and (qualified-keyword? x) (s/get-spec x))
    (parse-spec (s/get-spec x))
    (value x)))

(defn pred->value
  "If the spec is a pred that checks for a single value e.g. nil? false?, return the value, else nil"
  [s]
  (when (pred-spec? s)
    (condp = (:pred s)
      #'nil? (value nil)
      #'false? (value false)
      #'true? (value true)
      #'zero? (value 0)
      nil)))

(defrecord Value [v])

(s/fdef value? :args (s/cat :x any?) :ret boolean?)
(defn value? [s]
  (instance? Value s))

(s/fdef value :args (s/cat :x any?) :ret value?)
(defn value
  "spec representing a single value"
  [v]
  (map->Value {:v v}))

(defn maybe-strip-value [s]
  (if (value? s)
    (:v s)
    s))

(s/fdef get-value :args (s/cat :v value?) :ret any?)
(defn get-value [v]
  (:v v))

(extend-type Value
  Spect
  (conform* [this x]
    (cond
      (instance? Value x) (when (= (:v this) (:v x))
                            x)
      (pred->value x) (when (= this (pred->value x))
                        x)
      :else (when (= (:v this) x)
              x)))
  SpecToClass
  (spec->class [this]
    (class (:v this)))
  Truthyness
  (truthyness [this]
    (if (:v this)
      :truthy
      :falsey))
  Invoke
  (invoke [this args]
    (value-invoke this args))
  KeywordInvoke
  (keyword-invoke [this args]
    (let [key (first* args)
          else (second* args)
          rest (rest* (rest* args))]
      (cond
        (nil? key) (invalid {:message "not enough args"})
        rest (invalid {:message "value keyword invoke: too many args"})
        (and (value? key) (value? else)) (value (get (:v this) (:v key) (:v else)))
        (and (value? key) (nil? else)) (value (get (:v this) (:v key)))
        :else (unknown {:message "don't know how to keyword-invoke"}))))
  FirstRest
  (first* [{:keys [v] :as this}]
    (if (and v (or (seq? v) (seqable? v)))
      (if (seq v)
        (value (first v))
        nil)
      (invalid {:message (format "value %s does not support first" v)
                :form `(first ~v)})))
  (rest* [{:keys [v] :as this}]
    (if (or (seq? v) (seqable? v))
      (if (seq (rest v))
        (value (rest v))
        nil)
      (invalid {:message (format "value %s does not support rest" v) :form `(rest ~v)})))
  (seq*? [this]
    (boolean (seq (:v this))))
  WillAccept
  (will-accept [this]
    this)
  Regex
  (derivative [this x]
    (if (valid? this x)
      (accept x)
      reject))
  (empty-regex [_]
    reject)
  (accept-nil? [_]
    false)
  (return [this]
    this))

(no-dependent-specs Value)

(defn raw-value?
  "A normal clojure value that isn't a spect, and isn't Value"
  [x]
  (not (spect? x)))

(s/fdef valuey? :args (s/cat :x any?) :ret boolean)
(defn valuey? [x]
  (or (value? x) (raw-value? x)))

(s/def ::valuey (s/or :v value? :rv raw-value?))
(s/fdef get-value :args (s/cat :x ::valuey))
(defn get-value [x]
  (if (value? x)
    (:v x)
    x))

(s/fdef truthy-value? :args (s/cat :x any?) :ret boolean?)
(defn truthy-value? [s]
  "true if s is a value with a truthy value"
  (and (value? s) (boolean (:v s))))

(defrecord SpecSpec [s]
  ;; 'container' spec, for when the user does e.g. (s/cat :x (s/spec (s/* integer?)))
  ;; necessary because it changes the behavior of `first`
  Spect
  (conform* [this x]
    (conform* (parse-spec s) x))
  WillAccept
  (will-accept [this]
    (parse-spec s))
  FirstRest
  (first* [this]
    (-> s parse-spec first*))
  (rest* [this]
    (-> s parse-spec rest*))
  (seq*? [this]
    (-> s parse-spec seq*?))
  SpecToClass
  (spec->class [this]
    (-> s parse-spec spec->class))
  Truthyness
  (truthyness [this]
    (-> s parse-spec spec->class)))

(extend-regex SpecSpec)

(defn spec-spec? [x]
  (instance? SpecSpec x))

(defn spec-spec [s]
  (if (not (spec-spec? s))
    (map->SpecSpec {:s s})
    s))

(s/fdef conformy? :args (s/cat :x any?) :ret boolean?)
(defn conformy?
  "True if the conform result returns anything other than ::invalid or reject"
  [x]
  ;; {:post [(do (println "conformy?" x "=> " %) true)]}
  (boolean (and x
                (not (invalid? x))
                (not (reject? x)))))

(defrecord PredSpec [pred form])

(extend-regex PredSpec)
(first-rest-singular PredSpec)

(s/def ::pred (s/or :v var? :fn fn? :nil nil?))

(s/fdef pred-spec :args (s/cat :p ::pred) :ret ::spect)
(defn pred-spec [p]
  (map->PredSpec {:pred p
                  :form (when (var? p)
                          (var-name p))}))

(s/fdef pred-spec? :args (s/cat :x any?) :ret boolean?)
(defn pred-spec? [x]
  (instance? PredSpec x))

(defn resolve-pred-spec
  "If spec is a PredSpec, find and parse its fnspec"
  [s]
  (if (pred-spec? s)
    (let [fnspec (s/get-spec (:pred s))]
      (when fnspec
        (let [fnspec (parse-spec fnspec)]
          (if (var? (:pred s))
            (assoc fnspec :var (:pred s))
            fnspec))))
    s))

(def any?-form '(clojure.core/fn [x] true))

(s/fdef any-spec? :args (s/cat :s pred-spec?) :ret boolean?)
(defn any-spec?
  "To prevent infinite recursion, recognize if this is the 'any? spec, and return true"
  [pred-spec]
  (or (-> pred-spec :pred (= #'any?))
      (-> pred-spec :pred (= any?-form))))

(s/fdef pred-invoke-truthy? :args (s/cat :spec pred-spec? :x any?) :ret boolean?)
(defn pred-invoke-truthy?
  "True if (invoke)ing the pred w/ x returns unambigously truthy"
  [spec x]
  (let [ret (invoke spec (cat- [x]))]
    (= :truthy (truthyness ret))))

(extend-type PredSpec
  Spect
  (conform* [spec x]
    (let [pred (:pred spec)]
      (cond
        (any-spec? spec) x
        (and (pred-spec? x) (= pred (:pred x))) x
        (pred-invoke-truthy? spec x) x
        (and (= #'class? pred) (class-spec? x)) x

        (class-spec? x) (when-let [pred-class (spec->class spec)]
                          (when (isa? (:cls x) pred-class)
                              x))
        (data/pred->protocol? pred) (let [proto (data/pred->protocol pred)]
                                      (satisfies? proto x))
        ;; calling the pred should always be last resort
        ;; TODO remove this, or restrict to only using w/ pure functions. Not technically 'static' analysis.
        (and (conform-args? spec (cat- [x])) (valuey? x)) (when (pred (get-value x))
                                                            x))))
  (explain* [spec path via in x]
    (when (not (valid? spec x))
      [{:path path :pred (:form spec) :val x :via via :in in}]))
  WillAccept
  (will-accept [this]
    this)
  Truthyness
  (truthyness [this]
    (condp = (:pred this)
      #'boolean? :ambiguous
      #'false? :falsey
      #'nil? :falsey
      #'any? :ambiguous
      :truthy))
  Invoke
  (invoke [this args]
    (let [arg (first* args)
          rest (rest* args)
          pred-fn-spec (resolve-pred-spec this)]
      (cond
        rest (invalid {:message (format "predspec invoke: too many args: %s %s" (print-str this) (print-str args))})
        (not arg) (invalid {:message "not enough args"})
        pred-fn-spec (invoke pred-fn-spec args)
        :else (unknown {:message (format "couldn't resolve pred-spec %s" this)}))))
  DependentSpecs
  (dependent-specs [this]
    (loop [ret #{}
           spec this]
      (let [spec-fn (resolve-pred-spec spec)
            spec-arg (-> spec-fn :args (first*))]
        (if (and spec-fn (not (any-spec? spec-arg)))
          (do
            (recur (conj ret spec-arg) spec-arg))
          (if-let [tt (data/get-type-transformer (:pred this))]
            (conj ret tt)
            ret)))))
  SpecToClass
  (spec->class [this]
    (or
     (when (var? (:pred this))
       (data/pred->class (:pred this)))
     (some (fn [c] (when (spec->class? c)
                     (spec->class c))) (dependent-specs this)))))

(defrecord FnSpec [args ret fn var])

(extend-regex FnSpec)
(will-accept-this FnSpec)

(s/fdef fn-spec? :args (s/cat :x any?) :ret boolean?)
(defn fn-spec? [x]
  (instance? FnSpec x))

(s/fdef fn-spec :args (s/cat :args (s/nilable ::spect) :ret (s/nilable ::spect) :fn (s/nilable ::spect)))
(defn fn-spec [args ret fn]
  (map->FnSpec {:args args
                :ret ret
                :fn fn}))

(s/fdef get-var-fn-spec :args (s/cat :v var?) :ret (s/nilable fn-spec?))
(defn get-var-fn-spec [v]
  (when-let [s (s/get-spec v)]
    (assoc (parse-spec s) :var v)))

(defn var-named-predicate?
  "True if the var's name looks like a predicate"
  [v]
  (boolean (re-find #"\?$" (name (.sym ^Var v)))))

(s/fdef var-predicate? :args (s/cat :v var?) :ret boolean?)
(defn var-predicate?
  [v]
  (let [s (get-var-fn-spec v)]
    (if s
      (and (-> s :args cat-spec?)
           (-> s :args :ps count (= 1))
           (-> s :ret (= (pred-spec #'boolean?)))
           (var-named-predicate? v))
      false)))

(declare invoke-fn-spec)

(s/fdef maybe-transform :args (s/cat :s spect? :args spect?) :ret (s/nilable spect?))
(defn maybe-transform [spec args]
  (when-let [v (:var spec)]
    (when-let [t (data/get-invoke-transformer v)]
      (let [spec* (t spec args)
            transformed? (not= spec spec*)]
        (if transformed?
          (if (or (valid? spec spec*) (invalid? spec*) (unknown? spec*))
            spec*
            (invalid {:message (format "transformed fn must conform to original spec. original: %s  with args %s transformed: %s" (print-str spec) (print-str args) (print-str spec*))})))))))

(s/fdef invoke-fn-spec :args (s/cat :s fn-spec? :args spect?) :ret spect?)
(defn invoke-fn-spec [spec invoke-args]
  (let [v (:var spec)]
    (if-let [spec* (maybe-transform spec invoke-args)]
      (do
        (if (not (invalid? spec*))
          (recur (assoc spec* :var nil) invoke-args)
          spec*))
      (if-let [args (parse-spec (:args spec))]
        (if (valid? args invoke-args)
          (if-let [ret (:ret spec)]
            ret
            (pred-spec #'any?))
          (invalid {:message (format "invoke %s with %s does not conform" (print-str spec) (print-str invoke-args))}))
        (unknown {:message "no :args spec"})))))

(extend-type FnSpec
  Spect
  (conform* [this x]
    (and (instance? FnSpec x)
         (or
          (and (:var this)
               (:var x)
               (= (:var this)
                  (:var x)))
          (and
           (if (:args this)
             (if (:args x)
               (if (valid? (:args this) (:args x))
                 x
                 false)
               false)
             x)
           (if (:ret this)
             (if (:ret x)
               (if (valid? (:ret this) (:ret x))
                 x
                 false)
               false)
             x)
           (if (:fn this)
             (if (:fn x)
               (if (valid? (:fn this) (:fn x))
                 x
                 false)
               false)
             x)))))
  (explain* [spec path via in x]
    (when-not (valid? spec x)
      [{:path path :pred spec :val x :via via :in in}]))
  SpecToClass
  (spec->class [this]
    clojure.lang.IFn)
  Invoke
  (invoke [this args]
    (invoke-fn-spec this args)))

(defn maybe-spec-spec [x]
  (if (regex-seq? x)
    (spec-spec x)
    x))

(s/fdef conform-fn-args? :args (s/cat :s fn-spec? :x spect?) :ret boolean?)
(defn conform-fn-args?
  "True if x conforms to the :args of the fn, i.e. it's valid to call the fn with x as args"
  [fn-spec args-spect]
  (if (:args fn-spec)
    (valid? (:args fn-spec) args-spect)
    (do (println "no :args for fn-spec" fn-spec)
        false)))

(s/fdef conform-pred-args? :args (s/cat :p pred-spec? :x spect?) :ret boolean?)
(defn conform-pred-args?
  [pred-spec args-spect]
  (if-let [fn-spec (resolve-pred-spec pred-spec)]
    (conform-fn-args? fn-spec args-spect)
    false))

(s/fdef conform-var-args? :args (s/cat :v var? :x spect?) :ret boolean?)
(defn conform-var-args? [v args-spect]
  (if-let [fn-spec (get-var-fn-spec v)]
    (conform-fn-args? fn-spec args-spect)
    false))

(s/fdef conform-args? :args (s/cat :s (s/or :f fn-spec? :p pred-spec? :v var?) :args spect?) :ret boolean?)
(defn conform-args? [spec args-spect]
  (cond
    (pred-spec? spec) (conform-pred-args? spec args-spect)
    (fn-spec? spec) (conform-fn-args? spec args-spect)
    (var? spec) (conform-var-args? spec args-spect)
    :else (throw (Exception. (format "dont' know how to conform-args %s" spec)))))

(defn maybe-transform-method
  "apply the java method transformer, if applicable"
  [meth spec args-spec]
  (if-let [t (data/get-invoke-transformer meth)]
    (let [spec* (t spec args-spec)]
      spec*)
    spec))

(defn integer-range [^Class cls]
  (assert cls)
  [(.get ^java.lang.reflect.Field (.getDeclaredField cls "MIN_VALUE") nil)
   (.get ^java.lang.reflect.Field (.getDeclaredField ^Class cls "MAX_VALUE") nil)])

(defn integer-castable?
  "True if integer value n can be cast to class c without overflow"
  [n class]
  (let [[min max] (integer-range class)]
    (and (integer? n)
         (>= n min)
         (<= n max))))

;; Spec representing a java class. Probably won't need to use this
;; directly. Used in java interop, and other places where we don't
;; have 'real' specs

(defrecord ClassSpec [cls]
  Spect
  (conform* [this v]
    (cond
      (and (spect? v) (isa? (or (when (spec->class? v) (spec->class v)) Object) cls)) v
      (and (isa? cls Number)
           (value? v)
           (contains? #{Long Integer Short Byte} (class (maybe-strip-value v)))
           (integer-castable? (maybe-strip-value v) cls)) v
      :else nil))
  WillAccept
  (will-accept [this]
    this)
  Truthyness
  (truthyness [this]
    (condp = (:cls this)
      Boolean :ambiguous
      nil :falsey
      :truthy))
  SpecToClass
  (spec->class [s]
    ;;; hack for the godawful clojure.lang.MapEquivalence
    ;;; hack. deftype checks for MapEquivalence, an interface that is
    ;;; only implemented by APersistentMap, even though the defrecord
    ;;; constructor takes IPersistentMap.
    (if (= clojure.lang.MapEquivalence (:cls s))
      clojure.lang.APersistentMap
      (:cls s)))
  Invoke
  (invoke [this args]
    (unknown-invoke this args)))

(extend-regex ClassSpec)
(first-rest-singular ClassSpec)
(no-dependent-specs ClassSpec)

(defn class-spec [c]
  (map->ClassSpec {:cls c}))

(s/fdef class-spec :args (s/cat :x any?) :ret boolean?)
(defn class-spec? [x]
  (instance? ClassSpec x))

(defn maybe-class [x]
  (cond
    (class-spec? x) (:cls x)
    (class? x) x
    (and (value? x) (class? (:v x))) (:v x)
    :else nil))

(defmethod parse-spec* :fn-sym [x]
  (let [v (resolve x)]
    (assert v (format "couldn't resolve %s" x))
    (map->PredSpec {:pred v
                    :form (symbol (str (.ns ^Var v)) (str (.sym ^Var v)))})))

(defmethod parse-spec* :fn-literal [x]
  (map->PredSpec {:pred (eval x)
                  :form x}))

(defmethod parse-spec* 'clojure.core/fn [x]
  (if (= x any?-form)
    (pred-spec #'any?)
    (do
      (map->PredSpec {:pred nil ;;(eval x)
                      :form x}))))

(defmethod parse-spec* 'quote [x]
  (parse-spec* (second x)))

(defmethod parse-spec* 'var [x]
  (parse-spec* (second x)))

(defmethod parse-spec* 'clojure.spec/spec [x]
  (spec-spec (parse-spec* (second x))))

(defmethod parse-spec* :clojure.spec/pcat [x]
  (map->RegexCat {:ks (:ks x)
                  :ps (mapv (fn [[form pred]]
                              (if (:clojure.spec/op pred)
                                pred
                                form)) (map vector (:forms x) (:ps x)))
                  :forms (:forms x)
                  :ret (:ret x)}))

(defmethod parse-spec* :clojure.spec/accept [x]
  (accept (:ret x)))

(defmethod parse-spec* 'clojure.spec/cat [x]
  (let [pairs (->> x rest (partition 2))
        ks (map first pairs)
        ps (map second pairs)]
    (map->RegexCat {:ks ks
                    :ps ps
                    :forms ps
                    :ret {}})))

(defmethod parse-spec* 'clojure.spec/fspec [x]
  (let [pairs (->> x rest (partition 2))
        pairs (map (fn [[k p]]
                     (when p
                       [k (parse-spec p)])) pairs)
        args (into {} pairs)]
    (map->FnSpec args)))

(defmethod parse-spec* :clojure.spec/rep [x]
  (let [forms (if (vector? (:forms x))
                (:forms x)
                [(:forms x)])]
    (map->RegexSeq {:ks (:ks x)
                    :ps forms
                    :forms forms
                    :ret []
                    :splice (:splice x)})))

(defmethod parse-spec* :clojure.spec/rep [x]
  (let [forms (if (vector? (:forms x))
                (:forms x)
                [(:forms x)])]
    (map->RegexSeq {:ks (:ks x)
                    :ps forms
                    :forms forms
                    :ret []
                    :splice (:splice x)})))

(defmethod parse-spec* 'clojure.spec/* [x]
  (let [forms (rest x)]
    (map->RegexSeq {:ps forms
                    :forms forms
                    :ret []
                    :splice false})))

(defmethod parse-spec* :clojure.spec/alt [x]
  ;; evaled alt
  (let [pairs (map vector (:ps x) (:forms x))
        forms (map (fn [[p f]]
                    (if (fn? p)
                      f
                      p)) pairs)]

    (map->RegexAlt {:ks (:ks x)
                    :forms (:forms x)
                    :ps forms})))

(defn parse-literal-alt [x]
  (let [pairs (partition 2 (rest x))
        ks (mapv first pairs)
        forms (mapv second pairs)]
    (map->RegexAlt {:ks ks
                    :forms forms
                    :ps forms})))

(defmethod parse-spec* 'clojure.spec/alt [x]
  ;; literal alt form
  (parse-literal-alt x))

(defmethod parse-spec* 'clojure.spec/? [x]
  (map->RegexAlt {:ps [(second x) (accept ::s/nil)]}))

(defn and-conform-literal [and-s x]
  (when (every? (fn [f]
                  ((:pred f) x)) (:ps and-s))
    x))

(defn spec->class-seq [ps]
  (let [classes (->> ps
                     (mapcat (fn [f]
                               (let [a (when (spec->class? (first ps))
                                         (spec->class (first ps)))
                                     b (when (spec->class? f)
                                         (spec->class f))]
                                 (when (and a b)
                                   [(j/shared-ancestors a b)]))))
                     (filter identity)
                     (apply set/union))
        cls (if (seq (remove j/interface? classes))
              (j/most-specific-class (remove j/interface? classes))
              (first classes))]
    cls))


(defrecord AndSpec [ps])
(extend-type AndSpec
  Spect
  (conform* [this x]
    (conform this x))
  DependentSpecs
  (dependent-specs [spec]
    (->> spec
         :ps
         (map parse-spec)
         (map dependent-specs)
         (apply set/union)))
  WillAccept
  (will-accept [this]
    this)
  Truthyness
  (truthyness [this]
    (let [b (distinct (->> this :ps (map parse-spec) (map truthyness)))]
      (if (= 1 (count b))
        (first b)
        :ambiguous)))
  SpecToClass
  (spec->class [s]
    (spec->class-seq (:ps s)))
  Invoke
  (invoke [this args]
    (unknown-invoke this args)))

(extend-regex AndSpec)

(defn and-spec? [x]
  (instance? AndSpec x))

(s/fdef and-spec :args (s/cat :forms (s/coll-of ::spect-like)) :ret ::spect-like)
(defn and-spec [ps]
  (let [ps (remove truthy-value? ps)
        ps (mapcat (fn [p] (if (and-spec? p)
                             (:ps p)
                             [p])) ps)
        ps (distinct ps)]
    (cond
      (>= (count ps) 2) (map->AndSpec {:ps ps})
      :else (first ps))))

(defmethod parse-spec* 'clojure.spec/and [x]
  (and-spec (rest x)))

(declare or-)

(defrecord OrSpec [ps ks]
  Spect
  (conform* [this x]
    (->>
     ps
     (map parse-spec)
     (mapv vector (or ks (repeat nil)))
     (some (fn [[k p]]
             (when (valid? p x)
               (if k
                 [k x]
                 x))))))
  DependentSpecs
  (dependent-specs [spec]
    (->> ps
         (map parse-spec)
         (map dependent-specs)
         (apply set/intersection)))
  SpecToClass
  (spec->class [this]
    nil)
  WillAccept
  (will-accept [this]
    (-> this :ps first parse-spec))
  Truthyness
  (truthyness [this]
    (let [b (->> this :ps (map parse-spec) (map truthyness) distinct)]
      (if (= 1 (count b))
        (first b)
        :ambiguous)))
  ;; SpecToClass
  ;; (spec->class [s]
  ;;   nil)
  Compound
  (map- [this f]
    (let [kps (->> (mapv vector (or ks (repeat nil)) ps)
                   (map (fn [[k p]]
                          [k (f (parse-spec p))])))]
      (or-spec (map first kps) (map second kps))))
  (filter- [this f]
    (let [kps (->> (mapv vector (or ks (repeat nil)) ps)
                   (filter (fn [[k p]]
                             (f (parse-spec p)))))]
      (or-spec (map first kps) (map second kps))))
  KeywordInvoke
  (keyword-invoke [this args]
    (->> ps
         (map parse-spec)
         (filter keyword-invoke?)
         (map (fn [p]
                (keyword-invoke p args)))
         (distinct)
         (or-))))

(extend-regex OrSpec)

(defn or-spec? [x]
  (instance? OrSpec x))

(defn or-some
  "clojure.core/some, called on each pred in the orspec"
  [f or-spec]
  (some f (->> or-spec :ps (map parse-spec))))

(s/fdef or- :args (s/cat :ps (s/coll-of ::spect-like)) :ret or-spec?)
(defn or- [ps]
  (cond
    (>= (count ps) 2) (map->OrSpec {:ps ps
                                    :ks (take (count ps) (repeat nil))})
    (= 1 (count ps)) (first ps)
    :else (invalid {:message "or spect requires at least one arg"})))

(defn or-spec [ks ps]
  (cond
    (>= (count ps) 2) (map->OrSpec {:ks ks
                                    :ps ps})
    (= 1 (count ps)) (first ps)
    :else (invalid {:message "or spect requires at least one arg"})))

(defn equivalent? [s1 s2]
  (and (valid? s1 s2)
       (valid? s2 s1)))

(s/fdef or-disj :args (s/cat :s or-spec? :p spect?) :ret spect?)
(defn or-disj
  "Remove pred from the set of preds"
  [s pred]
  (->> s
       (map* parse-spec)
       (map* (fn [p]
               (if (compound-spec? p)
                 (or-disj p pred)
                 p)))
       (filter* (fn [p]
                  (not (equivalent? p pred))))))

(defmethod parse-spec* 'clojure.spec/or [x]
  (let [pairs (map vec (partition 2 (rest x)))
        ks (map first pairs)
        ps (map second pairs)]
    (map->OrSpec {:ks ks
                  :ps ps})))

(defrecord TupleSpec [ps])

(defn tuple-spec [ps]
  (map->TupleSpec {:ps ps}))

(extend-type TupleSpec
  Spect
  (conform* [spec xs]
    (let [xs-orig xs]
      (loop [ps (:ps spec)
             xs xs]
        (let [p (first ps)
              x (first* xs)]
          (cond
            (and p x) (if (valid? (parse-spec p) (parse-spec x))
                        (recur (rest ps) (rest* xs))
                        false)
            (and (not p) (not x)) xs-orig
            :else  false)))))
  FirstRest
  (first* [this]
    (some-> this :ps first parse-spec))
  (rest* [this]
    (tuple-spec (-> this :ps rest)))
  (seq*? [this]
    (pos? (count (:ps this)))))

(defmethod parse-spec* 'clojure.spec/tuple [x]
  (let [preds (rest x)]
    (map->TupleSpec {:ps (vec preds)})))

(defn keyspec-get-key-
  ([s key]
   (some (fn [key-type]
           (when-let [v (get-in s [key-type key])]
             [key-type (parse-spec v)])) [:req :req-un :opt :opt-un])))

(defn keyspec-get-key
  ([s key]
   (let [k (maybe-strip-value key)
         [key-type val] (keyspec-get-key- s k)]
     (if val
       (if (contains? #{:opt :opt-un} key-type)
         (or- [val (value nil)])
         val)
       (value nil))))
  ([s key else]
   (let [k (maybe-strip-value key)
         [key-type val] (keyspec-get-key- s k)]
     (if val
       (if (contains? #{:opt :opt-un} key-type)
         (or- [val else])
         val)
       else))))

(defrecord KeysSpec [req req-un opt opt-un]
  WillAccept
  (will-accept [this]
    this)
  SpecToClass
  (spec->class [this]
    clojure.lang.PersistentHashMap)
  Truthyness
  (truthyness [this]
    :truthy)
  KeywordInvoke
  (keyword-invoke [this args]
    (let [k (first* args)
          else (second* args)
          rest (rest* (rest* args))]
      (cond
        rest (invalid {:message (format "keysspec keywordw invoke: too many args:" (print-str this) (print-str args))})
        else (keyspec-get-key this k else)
        k (keyspec-get-key this k)
        :else (invalid {:message "not enough args"}))))
  Invoke
  (invoke [this args]
    (unknown-invoke this args)))

(no-dependent-specs KeysSpec)

(s/fdef keys-spec? :args (s/cat :x any?) :ret boolean?)
(defn keys-spec? [x]
  (instance? KeysSpec x))

(s/fdef conform-keys-keys :args (s/cat :s ::keys-spec :x ::keys-spec) :ret any?)
(defn conform-keys-keys [this x]
  (when (or
         (= this x) ;; short circuit
         (and
          (keys-spec? x)
          ;; x keys conform to spec
          (every? (fn [[key spec]]
                    (valid? (parse-spec spec) (parse-spec (get-in x [:req key])))) (:req this))
          (every? (fn [[key spec]]
                    (valid? (parse-spec spec) (parse-spec (get-in x [:req-un (strip-namespace key)])))) (:req-un this))))
    x))

(defn conform-keys-value [s x]
  (let [x* (get-value x)]
    (when (and
           ;; x keys conform to spec
           (every? (fn [[key spec]]
                     (valid? (parse-spec spec) (parse-spec (get x* key)))) (:req s))
           (every? (fn [[key spec]]
                     (valid? (parse-spec spec) (parse-spec (get x* (strip-namespace key))))) (:req-un s))
           (every? (fn [[key spec]]
                     (if-let [v (parse-spec (get x* key))]
                       (valid? (parse-spec spec) v)
                       true)) (:opt s))
           (every? (fn [[key spec]]
                     (if-let [v (get x* (strip-namespace key))]
                       (valid? (parse-spec spec) (parse-spec v))
                       true)) (:req-un s)))
      x)))

(defn explain-keys [{:keys [req req-un] :as spec} path via in x]
  (if-not (conform (pred-spec #'keys-spec?) x)
    [{:path path :pred 'map? :val x :via via :in in}]
    (->>
     (concat (mapv (fn [[key spec]]
                     [key spec (get-in x [:req key])]) req)
             (mapv (fn [[key spec]]
                     [key spec (get-in x [:req (strip-namespace key)])]) req-un)
             (mapv (fn [[key spec]]
                     [key spec (get-in x [:req key])]) (:opt x))
             (mapv (fn [[key spec]]
                     [key spec (get-in x [:req (strip-namespace key)])]) (:opt-un x)))
     (mapv (fn [[key spec val]]
             (when-not (valid? spec val)
               (explain* spec (conj path key) via in val)))))))

(s/def ::keys-spec keys-spec?)

(s/fdef keys-spec :args (s/cat :req (s/nilable (s/map-of qualified-keyword? ::spect-like))
                               :req-un (s/nilable (s/map-of keyword? ::spect-like))
                               :opt (s/nilable (s/map-of qualified-keyword? ::spect-like))
                               :opt-un (s/nilable (s/map-of keyword? ::spect-like)))
        :ret keys-spec?)

(defn keys-spec [req req-un opt opt-un]
  (map->KeysSpec {:req req
                  :req-un (into {} (map (fn [[k s]]
                                          [(strip-namespace k) s]) req-un))
                  :opt opt
                  :opt-un (into {} (map (fn [[k s]]
                                          [(strip-namespace k) s]) opt-un))}))

(extend-type KeysSpec
  Spect
  (conform* [this x]
    (cond
      (keys-spec? x) (conform-keys-keys this x)
      (value? x) (conform-keys-value this x)))
  (explain* [spec path via in x]
    (explain-keys spec path via in x)))

(extend-regex KeysSpec)

(defn keys-contains?
  "clojure.core/contains? for keys-spec"
  [ks key]
  (some (fn [key-type]
          (contains? (get ks key-type) key)) [:req :req-un :opt :opt-un]))

(s/fdef keys-get :args (s/cat :ks keys-spec? :key keyword?) :ret (s/nilable any?))
(defn keys-get
  "clojure.core/get, for key-spec"
  [ks key]
  (some->> [:req :req-un :opt :opt-un]
           (some (fn [key-type]
                   (get-in ks [key-type key])))
           (parse-spec)))

(defrecord CollOfSpec [s kind])

(extend-regex CollOfSpec)
(will-accept-this CollOfSpec)

(s/fdef coll-of :args (s/cat :s ::spect-like :kind (s/? (s/nilable coll?))) :ret ::spect)
(defn coll-of
  ([s]
   (coll-of s nil))
  ([s kind]
   (map->CollOfSpec {:s s
                     :kind kind})))

(s/fdef coll-of? :args (s/cat :x any?) :ret boolean?)
(defn coll-of? [x]
  (instance? CollOfSpec x))

(s/fdef infinite? :args (s/cat :r first-rest?) :ret boolean?)
(defn infinite?
  "True if this regex accepts infinite input"
  [r]
  (or (regex-seq? r)
      (coll-of? r)
      (if-let [n (some-> r :ps first)]
        (recur n)
        false)))

(s/fdef every-distinct :args (s/cat :r first-rest?) :ret set?)
(defn every-distinct [s]
  "Every distinct value this spect can accept"
  (loop [ret #{}
         s s]
    (if (seq*? s)
      (let [ret (conj ret (first* s))]
        (if (and (not (infinite? s)) (rest* s))
          (recur ret (rest* s))
          ret))
      ret)))

(s/fdef conform-collof-value :args (s/cat :collof ::spect :x (s/nilable value?)))
(defn conform-collof-value [collof x]
  (let [s (parse-spec (:s collof))]
    (when (and (or (nil? (:kind collof))
                   (= (empty (:kind collof))
                      (empty x)))
               (every? (fn [v]
                         (valid? s v)) (every-distinct x)))
      x)))

(s/fdef coll-of-key :args (s/cat :s spect?) :ret #{:map :set :vector :unknown})
(defn coll-of-kind
  "Given a coll-of spect, return its kind as a keyword"
  [s]
  (condp = (::s/kind-form s)
    'clojure.core/map? :map
    'clojure.core/set? :set
    'clojure.core/vector? :vector
    :unknown))

(defn coll-of-invoke-dispatch [s args]
  (coll-of-kind s))

(defmulti coll-of-invoke #'coll-of-invoke-dispatch)

(defmethod coll-of-invoke :default [s args]
  (unknown {:message (format "don't know how to invoke %s" s)}))

(defmethod coll-of-invoke :map [s args]
  (let [key (first* args)
        else (or (second* args) (value nil))
        rest (rest* (rest* args))
        map-key-spec (-> s :s parse-spec first*)
        map-val-spec (-> s :s parse-spec second*)]
    (cond
      rest (invalid {:message (format "too many args to invoke, got %s" (print-str args))})
      (not key) (invalid {:message "not enough args to invoke"})
      (valid? map-key-spec key) (or- [map-val-spec else])
      (or-spec? key) (if (or-some #(valid? map-key-spec %) key)
                       (or- [map-val-spec else])
                       (value nil))
      :else (if else
              else
              (value nil)))))

(extend-type CollOfSpec
  Spect
  (conform* [this x]
    (cond
      (instance? CollOfSpec x) (when (valid? (parse-spec (:s this)) (parse-spec (:s x)))
                                 x)
      (value? x) (conform-collof-value this x)
      :else false))
  SpecToClass
  (spec->class [s]
    (or (class s) clojure.lang.PersistentList))
  FirstRest
  (first* [this]
    (parse-spec (:s this)))
  (rest* [this]
    this)
  (seq*? [this]
    true)
  Truthyness
  (truthyness [this]
    :truthy)
  Invoke
  (invoke [this args]
    (coll-of-invoke this args)))

(defn parse-coll-of [x]
  (let [args (rest x)
        s (first args)
        opts (apply hash-map (rest args))]
    (map->CollOfSpec (merge {:s s} opts))))

(defmethod parse-spec* 'clojure.spec/every [x]
  (parse-coll-of x))

(defmethod parse-spec* 'clojure.spec/coll-of [x]
  (parse-coll-of x))

(defmethod parse-spec* 'clojure.spec/nilable [x]
  (let [s (second x)]
    (or- [s (parse-spec #'nil?)])))

(defmethod parse-spec* 'clojure.spec/or [x]
  (let [pairs (partition 2 (rest x))
        keys (mapv first pairs)
        forms (mapv second pairs)]
    (map->OrSpec {:ks keys
                  :ps forms})))

(defmethod parse-spec* 'clojure.spec/keys [x]
  (let [args (->> (rest x)
                  (partition 2)
                  (map (fn [[key-type specs]]
                         [key-type (into {} (map (fn [spec-name]
                                                   (if-let [s (s/get-spec spec-name)]
                                                     [spec-name (s/form s)]
                                                     (throw (Exception. (format "Could not resolve spec: %s" spec-name))))) specs))]))
                  (into {} ))]
    (keys-spec (:req args)
               (:req-un args)
               (:opt args)
               (:opt-un args))))

(defn parse-spec-seq [x]
  (let [v (mapv parse-spec* x)]
    (if (list? x)
      (value (list* v))
      (value (into (or (empty x) []) v)))))

(defn parse-spec-map [x]
  (let [state (reduce (fn [state [k v]]
                        (cond
                          (qualified-keyword? k) (assoc-in state [:req k] v)
                          (simple-keyword? k) (assoc-in state [:req-un k] v))) {:req {}
                                                                                :req-un {}} x)]
    (apply keys-spec [(:req state) (:req-un state) {} {}])))

(defmethod parse-spec* :coll [x]
  (cond
    (sequential? x) (parse-spec-seq x)
    (map? x) (parse-spec-map x)))

(s/fdef merge-keys :args (s/cat :ks (s/coll-of keys-spec?)) :ret keys-spec?)
(defn merge-keys [ks]
  (->> ks
       (mapv parse-spec)
       (apply merge-with merge)
       (map->KeysSpec)))

(declare merge-specs)
(s/fdef merge-or-keys :args (s/cat :or or-spec? :key keys-spec?) :ret or-spec?)
(defn merge-or-keys [o k]
  (or- (mapv (fn [p]
               (merge-specs p k)) (:ps o))))

(defn merge-specs [& specs]
  (reduce (fn [s1 s2]
            (let [s1 (parse-spec s1)
                  s2 (parse-spec s2)]
              (cond
                (and (keys-spec? s1) (keys-spec? s2)) (merge-keys [s1 s2])
                (and (or-spec? s1) (keys-spec? s2)) (merge-or-keys s1 s2)
                (and (keys-spec? s1) (or-spec? s2)) (merge-or-keys s2 s1)
                :else (throw (ex-info (str "don't know how to merge:" s1 s2) {:specs [s1 s2]}))))) specs))

(s/fdef conform-map-of :args (s/cat :m ::spect :v value?) :ret any?)
(defn conform-map-of [map-of v]
  (when (and (every? (fn [k]
                       (valid? (:ks map-of) k)) (keys (:v v)))
             (every? (fn [v]
                       (valid? (:vs map-of) v)) (vals (:v v))))
    v))

(defrecord MapOf [ks vs]
  Spect
  (conform* [this x]
    (cond
      (instance? MapOf x) (when (and (valid? ks (:ks x))
                                     (valid? vs (:vs x)))
                                 x)
      (value? x) (conform-map-of this x)
      :else false))
  SpecToClass
  (spec->class [s]
    clojure.lang.PersistentHashMap)
  ;; FirstRest
  ;; TODO need tuples/MapEntry
  ;; (first* [this]
  ;;   )
  ;; (rest* [this]
  ;;   this)
  Truthyness
  (truthyness [this]
    :truthy)
  Invoke
  (invoke [this args]
    (unknown-invoke this args)))

(extend-regex MapOf)
(will-accept-this MapOf)

(defn map-of [key-pred val-pred]
  (map->MapOf {:ks key-pred
               :vs val-pred}))

(defmethod parse-spec* 'clojure.spec/map-of [x]
  (let [k (nth x 1)
        v (nth x 2)]
    (map-of k v)))

(defmethod parse-spec* 'clojure.spec/merge [x]
  (apply merge-specs (rest x)))

(defmethod parse-spec* 'clojure.spec/merge-spec-impl [x]
  (let [[forms preds & _] (rest x)
        forms (second forms)]
    (merge-keys forms)))

(defmethod parse-spec* 'clojure.spec/conformer [x]
  (value true))

(defmethod parse-spec* 'clojure.spec/nonconforming [x]
  (parse-spec* (second x)))

(s/fdef valid-invoke? :args (s/cat :s fn-spec? :args ::spect) :ret boolean?)
(defn valid-invoke?
  "check that fnspec can be invoked w/ args"
  [spec args]
  (assert (fn-spec? spec))
  (valid? (:args spec) args))

(defn multispec-dispatch-values
  "Returns the seq of allowed dispatch values in the multimethod"
  [^clojure.lang.MultiFn ms]
  (->> (.getMethodTable ms)
       (keys)))

(defn maybe-resolve-keyword-spec [x]
  (if (and (value? x) (keyword? (:v x)) (#'s/maybe-spec (:v x)))
    (parse-spec (s/spec (:v x)))
    x))

(declare multispec?)

(s/fdef multispec-dispatch-ret-value :args (s/cat :ms multispec? :val any?) :ret spect?)

(defn multispec-assoc-retag
  "Given a concrete instance of a multispec, update it to include the dispatch value"
  [ms spec dispatch-value]
  (let [{:keys [retag]} ms
        key-type (if (simple-keyword? retag)
                   :req-un
                   :req)
        existing-key-spec (get-in spec [key-type retag])]
    (when-not existing-key-spec
      (println "Multispec assoc retag:" ms spec key-type retag))
    (assert existing-key-spec)
    (assert (valid? (parse-spec existing-key-spec) (value dispatch-value)))
    (assoc-in spec [key-type retag] (value dispatch-value))))

(defn multispec-dispatch-ret-value
  "Given a dispatch value, return the spec"
  [ms dispatch-value]
  (let [v (:multimethod ms)
        retag (:retag ms)
        s (v {retag dispatch-value})
        s (parse-spec s)
        s (multispec-assoc-retag ms s dispatch-value)]
    (if s
      s
      (unknown {:form [v dispatch-value]}))))

(defn multispec-dispatch-invoke [ms v]
  (assert (fn? (:retag ms)))
  (assert (var? (:retag ms)))
  (if-let [s (get-var-fn-spec (:retag ms))]
    (if-let [t (data/get-invoke-transformer v)]
      (let [fn-spec* (t s v)]
        (:ret fn-spec*))
      (:ret s))
    (do
      (println "no spec for" (:retag ms))
      ::no-dispatch)))

(defn multispec-dispatch
  [ms x]
  (cond
    (keyword? (:retag ms)) (or (when (keys-contains? x (:retag ms))
                                 (let [d (keys-get x (:retag ms))]
                                   (if (value? d)
                                     d
                                     ::no-dispatch)))
                               ::no-dispatch)
    (fn? (:retag ms)) (multispec-dispatch-invoke ms x)))

(defn multispec-default-spec
  "When we can't determine the correct multispec dispatch value, get the lowest common denominator spec, Or'ing all of the possible values together"
  [ms]
  (or- (map (fn [v]
              (multispec-dispatch-ret-value ms v)) (multispec-dispatch-values @(:multimethod ms)))))

(defn multispec-resolve-spec
  "Given a multispec and a dispatch value, attempt to return the spec for that defmethod call. Returns specific spec, or multispec-default-spec"
  [ms v]
  (let [d (multispec-dispatch ms v)]
    (if (not= d ::no-dispatch)
      (multispec-dispatch-ret-value ms d)
      (multispec-default-spec ms))))

(defrecord MultiSpec [multimethod retag]
  Spect
  (conform* [this x]
    (let [dispatch-type (cond
                          (keyword? (:retag this)) :keyword
                          (fn? (:retag this)) :fn
                          :else (assert false "unknown dispatch type"))]
      (condp = dispatch-type
        :keyword (when (valid? (pred-spec #'map?) x)
                   (let [s (multispec-resolve-spec this x)
                         x (if (multispec? x)
                             (multispec-resolve-spec ))]
                     (conform s x)))
        :fn (when (valid-invoke? (:retag this) x)
              (conform (multispec-resolve-spec this x) x)))))
  KeywordInvoke
  (keyword-invoke [this k]
    (keyword-invoke (multispec-default-spec this) k)))

(extend-regex MultiSpec)

(defn multispec [method retag]
  (map->MultiSpec {:multimethod method
                   :retag retag}))

(defn multispec? [x]
  (instance? MultiSpec x))

(defmethod parse-spec* 'clojure.spec/multi-spec [x]
  (let [retag (nth x 2)
        retag (cond
                (keyword? retag) retag
                (symbol? retag) (resolve retag))
        method (resolve (nth x 1))]
    (assert retag)
    (multispec-default-spec (multispec method retag))))

(extend-protocol Spect
  clojure.spec.Spec
  (conform* [spec x]
    (conform* (parse-spec spec) (parse-spec x))))

(extend-type clojure.spec.Spec
  Spect
  (conform* [spec x]
    (conform* (parse-spec* spec) x)))

(s/fdef re-conform :args (s/cat :s ::spect :d ::spect) :ret ::spect)
(defn re-conform [spec data]
  (let [spec* spec
        data* data]
    (loop [spec spec
           data data
           iter 0]
      (if (> iter 100)
        (do (println "infinite re-conform:" spec* data*)
            (invalid {:message "infinite"}))
        (let [x (first* data)]
          (if (nil? x)
            (if (accept-nil? spec)
              (return spec)
              (invalid {:message (format "%s does not conform to %s" (print-str data) (print-str spec))}))
            (if-let [dp (derivative spec x)]
              (if (conformy? dp)
                (if (infinite? (rest* data))
                  (return dp)
                  (recur dp (rest* data) (inc iter)))
                (invalid {:message (format "%s does not conform to %s" (print-str data) (print-str spec))}))
              (invalid {:message (format "%s does not conform to %s" (print-str data) (print-str spec))}))))))))

(defn re-explain [spec path via in data]
  (loop [spec spec
         [x & xr :as data] data
         i 0]
    (if (empty? data)
      (if (accept-nil? spec)
        nil
        [{:path path :via via :in in :reason (format "insufficient input. Empty input but re expects: %s" spec) :i i}])
      (if-let [dp (derivative spec x)]
        (recur dp xr (inc i))
        (re-explain* spec path via (conj in i) data)))))

(s/fdef value-invoke-dispatch :args (s/cat :f spect? :args spect?) :ret keyword?)
(defn value-invoke-dispatch [f args]
  (assert (first-rest? args))
  (let [val f
        obj (first* args)
        else (second* args)
        rest (rest* (rest* args))]
    ;; (println "invoke type:" (class (:v val)))
    (assert (value? val))
    (cond
      (var? (:v f)) :var
      (fn? (:v f)) :fn
      (not obj) :invalid
      rest :invalid
      (and (value? obj) (nil? else)) :value-value
      (and (value? obj) (value? else)) :value-value-else
      (and (coll-of? obj) (nil? else)) :value-coll-of
      (and (coll-of? obj) else) :value-coll-of-else
      :else :unknown)))

(defmulti value-invoke "" #'value-invoke-dispatch)

(defmethod value-invoke :value-value [spec args]
  (let [f (:v spec)
        arg (first* args)
        arg (:v arg)]
    (if (ifn? f)
      (value (get f arg))
      (invalid {:message (format "can't invoke %s" f)}))))

(defmethod value-invoke :value-value-else [spec args]
  (let [key (:v spec)
        obj (first* args)
        obj (:v obj)
        [key obj] (cond
                    (and (keyword? key) (coll? obj)) [key obj]
                    (and (coll? key) (keyword? obj)) [obj key]
                    :else [key obj])
        else (second* args)]
    (if-let [ret (get obj key)]
      (value ret)
      else)))

(defmethod value-invoke :value-coll-of [spec args]
  (unknown {:message "not yet implemented"}))

(defmethod value-invoke :value-coll-of-else [spec args]
  (unknown {:message "not yet implemented"}))

(defmethod value-invoke :var [spec args]
  (let [v (:v spec)]
    (if-let [s (get-var-fn-spec v)]
      (invoke s args)
      (unknown {:message (format "no spec for %s" v)}))))

(defmethod value-invoke :fn [spec args]
  (unknown {:message (format "no spec for fn invoke %s" (print-str spec))}))

(defmethod value-invoke :invalid [f args]
  (let [val f
        obj (first* args)
        else (second* args)
        rest (rest* (rest* args))]
    (cond
      (not obj) (invalid {:message "not enough args to invoke"})
      rest (invalid {:message (format "too many args to invoke %s, got %s" (print-str val) (print-str args))})
      :else (assert false))))

(defmethod value-invoke :unknown [spec args]
  (unknown {:message (format "don't know how to invoke %s %s" spec args)}))

(s/fdef resolve-dependent-specs :args (s/cat :s spect?) :ret (s/nilable (s/coll-of spect?)))
(defn resolve-dependent-specs
  "If s supports dependent specs, resolve them and return a seq of specs"
  [s]
  (let [specs #{}
        specs (if (and (dependent-specs? s) (seq (dependent-specs s)))
                (do
                  (assert (set? (dependent-specs s)))
                  (set/union specs (dependent-specs s)))
                specs)
        specs (if (and (spec->class? s) (spec->class s))
                (set/union specs #{(class-spec (spec->class s))})
                specs)
        specs (set (remove unknown? specs))]
    (when (seq specs)
      (set specs))))

(def spect-generator (gen/elements [(pred-spec #'int?) (class-spec Long) (value true) (value false) (unknown nil)]))

(defn conform-strategy [spec args]
  (let [spec-or (or-spec? spec)
        spec-and (and-spec? spec)
        args-or (or-spec? args)
        args-and (and-spec? args)]
    (cond
      (and spec-and args-and) :and-and
      (and spec-or args-or) :or-or
      ;; (and spec-and args-or) :and-or
      (and spec-or args-and) :or-and
      spec-and :spec-and
      args-and :args-and
      args-or :args-or
      :else :simple)))

(defmulti conform-compound #'conform-strategy)

(defmethod conform-compound :and-and [spec args]
  (when (every? (fn [p]
                  (valid? p args)) (map parse-spec (:ps spec)))
    args))

(defmethod conform-compound :or-or [spec args]
  (when (every? (fn [arg]
                  (valid? spec arg)) (map parse-spec (:ps args)))
    args))

(defmethod conform-compound :or-and [spec args]
  (when (some (fn [arg]
                (some (fn [s]
                        (valid? s arg)) (map parse-spec (:ps spec)))) (map parse-spec (:ps args)))
    args))

(defmethod conform-compound :spec-and [spec args]
  (when (every? (fn [p]
                  (valid? p args)) (map parse-spec (:ps spec)))
    args))

(defmethod conform-compound :args-and [spec args]
  (when (some (fn [arg]
                (valid? spec arg)) (map parse-spec (:ps args)))
    args))

(defmethod conform-compound :args-or [spec args]
  (when (every? (fn [arg]
                  (valid? spec arg)) (map parse-spec (:ps args)))
    args))

(defmethod conform-compound :simple [spec args]
  (conform* spec args))

(defn conform-bfs [spec args]
  (let [args-orig args]
    (loop [args args
           q (queue)
           seen #{}]
      (let [val (conform-compound spec args)]
        (if (= ::s/nil val)
          (value nil)
          (if (conformy? val)
            val
            (let [ds (if args
                       (resolve-dependent-specs args)
                       #{})
                  _ (assert (every? spect? ds))
                  _ (assert (or (nil? ds) (set? ds)))
                  _ (assert (set? seen))
                  ds (set/difference ds seen)
                  q (concat q ds)]
              (if (seq q)
                (recur (first q) (rest q) (set/union seen ds))
                (invalid {:message (format "%s does not conform to %s" (print-str args) (print-str spec))})))))))))

(s/fdef conform- :args (s/cat :s ::spect :args (s/nilable (s/and spect? map?))) :ret ::spect)
(defn conform-
  "Given a spect and args, return the conforming parse. Behaves similar
  to s/conform, but args must be spectrum spects, rather than clojure.specs"
  [spec args]
  (try
    (conform-bfs spec args)
    (catch Throwable e
      (println "conform: kaboom:" spec args (.getMessage e))
      (throw e))))

(def conform (memo/lru conform- :lru/threshold 10000))

(s/fdef valid? :args (s/cat :s ::spect :args (s/nilable spect?)) :ret boolean?)
(defn valid? [spec x]
  (conformy? (conform spec x)))

(s/fdef valid-return? :args (s/cat :s ::spect :args ::spect) :ret boolean?)
(defn valid-return?
  "True if spec conforms, as a return value. Conform must return truthy c/value"
  [spec args]
  (valid? spec args))

(defn explain-data [spec x]
  (explain* spec [] [] [] x))

(defn explain-out [data])

(defn explain [spec args]
  (explain-data spec args))

(defmethod print-method Unknown [spec ^Writer w]
  (let [{:keys [file line column]} spec]
    (.write w (str "#Unknown[" (print-str (:form spec)) (when file
                                                          (str file line column)) "]"))))

(defn regex-print-method [re-name spec ^Writer writer]
  (.write writer (str "#" re-name "[" (str/join ", " (map print-str (:ps spec))) "]")))

(defmethod print-method RegexCat [v ^Writer w]
  (regex-print-method "Cat" v w))

(defmethod print-method RegexSeq [v ^Writer w]
  (regex-print-method "Seq" v w))

(defmethod print-method RegexAlt [v ^Writer w]
  (regex-print-method "Alt" v w))

(defmethod print-method Value [v ^Writer w]
  (.write w (format "#Value[%s]" (print-str (:v v)))))

(defmethod print-method PredSpec [v ^Writer w]
  (.write w (format "#Pred[%s]" (print-str (:form v)))))

(defmethod print-method ClassSpec [v ^Writer w]
  (.write w (format "#Class[%s]" (print-str (:cls v)))))

(defmethod print-method AndSpec [v ^Writer w]
  (.write w (format "#And[%s]" (str/join ", " (map print-str (:ps v))))))

(defmethod print-method OrSpec [v ^Writer w]
  (.write w (format "#Or[%s]" (str/join ", " (map print-str (:ps v))))))

(defmethod print-method KeysSpec [spec ^Writer w]
  (.write w (format "#Keys{%s}" (->> [:req :req-un :opt :opt-un]
                                          (map (fn [k]
                                                 [k (get spec k)]))
                                          (filter (fn [[k v]]
                                                    v))
                                          (map (fn [[key-type key-preds]]
                                                 (format "%s{%s}" key-type (->> key-preds
                                                                                (map (fn [[k v]]
                                                                                       (format "%s %s" k (print-str v))))
                                                                                (str/join " " )))))
                                          (str/join " ")))))

(defmethod print-method CollOfSpec [spec ^Writer w]
  (.write w (let [[open close] (condp = (:kind spec)
                                      map? ["{" "}"]
                                      vector? ["[" "]"]
                                      set? ["#{" "}"]
                                      ["(" ")"])]
              (str "#CollOf "open  (print-str (:s spec))  close))))
