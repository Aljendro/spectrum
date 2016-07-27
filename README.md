# spectrum

A library for doing static analysis of Clojure code, catching clojure.spec conform errors at compile time.

**Wait what?**

It's like core.typed, but it relies on clojure.spec annotations.

**So it's an optional static type system?**

Kind-of. It finds errors at compile time, and predicates kind of look like types. So sure.

![Proving contracts ahead of time](https://pbs.twimg.com/media/CjcVSAVUYAIBY3Z.jpg)


## Usage

```clojure
(require '[spectrum.check :as st])

(st/check 'your.namespace)
```

Returns a seq of Error defrecords.

## Goals

- usable
- pragmatic
- readable code
- fast
- configurable strictness levels
- incremental

## Non-goals

- perfection
- correctness

## Anti-goals

- requiring 100% spec coverage

In particular, spectrum aims to be fast and usable, and catching bugs. A tool that catches 80% of bugs that you use every day is better than a 100% tool that you don't use. Spectrum will trend towards 100%, but it will never guarantee 100% correctness.

## Limitations

- It's still very early. It doesn't understand all clojure forms yet, nor all spec annotations. Contributions welcome.

- It also proceeds from the assumption that your specs are correct (i.e. no spec errors at runtime). If you're not running clojure.test.spec or not running your unit tests with `instrument`, you're gonna have a bad time.


## How It Works

This section is for the curious, and potential contributors. Shouldn't be necessary to understand this to use it.

### spectrum.conform

This contains a spec parser and a re-implementation of clojure.spec, except they work on literals and specs rather than normal clojure values.

```clojure
(c/conform (s/cat :x integer?) [3])
=> {:x 3}
```

```clojure
(require '[spectrum.conform :as c])
(c/parse-spec (s/+ integer?))
```
Returns a defrecord, containing the parsed spec. This is basically a reimplementation of clojure.spec, except more data-driven. If you're not using spectrum, but want to do other analysis-y stuff with specs, you may find this useful.

```
(c/conform (s/+ integer?) [1 2 3])

(c/conform (s/+ integer?) '[integer? integer?])
```

`c/conform` behaves the same as `s/conform`, except it works on literals and specs (i.e. the things we have access to at compile time)

### spectrum.flow

Flow is an intermediate pass. It takes the output of tools.analyzer, analyzes function parameters and let bindings, and updates the analyzer output with the appropriate specs, to make checking simpler. The main thing it's responsible for is adding `::flow/args-spec` and `::flow/ret-spec` to every expression.

### spectrum.check

Where the magic happens. It takes a flow, and performs checks. Returns a seq of ParseError records.

## Transformers

clojure.spec doesn't have logic variables, which means some specs
aren't as tight as they could be. Consider `map`, which takes a fn of
one argument of type `X`, and returns a type `Y`, and a collection of
`X`s, and returns a seq of `Y`s. That's currently impossible to
express in clojure.spec, the best we can do is specify the return type
of map as `seq?`, with no reference to `seq of Y`, which is based on
the return type of the mapping function.

Spectrum introduces spec transformers. They are hooks into the
checking process. For example,

```clojure
(ann #'map (fn [fnspec argspec]...))
```
ann takes a var, and fn of two arguments, the original fnspec, and the arguments to a particular invocation of the function. The transformer should return an updated fnspec, presumably with the more specific type. In this example, it would `clojure.core/update` the `:ret` spec from `seq?` to `(coll-of y?)`.

### value

In some cases, we can identify the type of an expression at compile time. For example,

(if (int? 3)
  :foo
  "bar")

If we didn't know the value of the test, the return spec of the `if` expression would be `(or keyword? string?)`. Using a spec transformer, we can make int? more specific

```
(ann #'int? (instance-or [Long Integer Short Byte]))
```

In this case, `instance-or` is a higher-order function returning a
transformer that checks for the argument being an instance of any of
the classes. If we know the type of the argument, and it conforms, the
spec transformer returns `(value true)`. Some expressions, such as
`if`, recognize value true and will replace the type of the `if`
expression from `(or keyword? string?)` to just `keyword?`. Similarly,
returning `(value false)` or `(value nil)` indicates non-conformance,
and will cause conform to fail.

## Unknown

Spectrum doesn't insist that every var be spec'd immediately. There is
a specific spec, `unknown` used in places where we don't know type,
for example as the return value of an un-specced function. Passing
unknown to a specced function is treated as a different error from a
'real' type error, for example passing an int to a function expecting
keyword?. Use configuration, described in the next section, to remove
unknowns if desired.


## Todo

- pre/post post predicates

## License

Copyright © 2016 Allen Rohner

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
