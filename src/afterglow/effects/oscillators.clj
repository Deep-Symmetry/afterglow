(ns afterglow.effects.oscillators
  "Provide a variety of waveforms at frequencies related to the show metronome to
  facilitate building visually and musically pleasing effects."
  (:require [afterglow.effects.params :as params]
            [afterglow.rhythm :as rhythm]
            [afterglow.show-context :refer [*show* with-show]]
            [afterglow.util :as util]
            [taoensso.timbre :as timbre])
    (:import [afterglow.rhythm Metronome]))

(defonce
  ^{:private true
    :doc "Protect protocols against namespace reloads"}
  _PROTOCOLS_
  (do
(defprotocol IOscillator
  "A waveform generator for building effects that vary at
  frequencies related to a show metronome."
  (evaluate [this show snapshot head]
  "Determine the value of this oscillator at a given moment of the
  show. In addition to the metronome snapshot, the show and (if
  applicable) fixture head must be passed in case any oscillator
  configuration arguments rely
  on [dynamic](https://github.com/brunchboy/afterglow/blob/master/doc/parameters.adoc#dynamic-parameters)
  or [spatial](https://github.com/brunchboy/afterglow/blob/master/doc/parameters.adoc#spatial-parameters)
  parameters.")
  (resolve-non-frame-dynamic-elements [this show snapshot head]
  "Called when an effect is created using this oscillator. Returns a
  version of itself where any non frame-dynamic input parameters have
  been resolved."))
(defprotocol IVariableShape
  "Shape functions which can change over time (depending on the value
  of dynamic parameters) use this protocol rather than being a simple
  function, so they can get the context needed for evaluating their
  dynamic parameters."
  (value-for-phase [this phase show snapshot head]
  "Calculate the value of the oscillator's waveform at the specified
  phase, with support for resolving dynamic parameters that it may
  depend on. [phase] ranges from `0` to `1`, and so must the return
  value from this function.")
  (simplify-unless-frame-dynamic [this show snapshot head]
  "If none of the dynamic parameters used by the shape function are
  dynamic to the level of individual frames, return a simple shape
  function based on their current values which can replace this
  variable shape function but run faster. Otherwise returns
  itself."))))

(defn- adjust-phase
  "Helper function to offset a phase by a given amount. Phases range from [0.0-1.0)."
  [^Double phase ^Double offset]
  (let [sum (+ phase offset)]
    (- sum (long sum))))

(defn- build-base-phase-fn
  "Constructs the phase-generating function for an oscillator which
  has fixed parameters, arranging for it to do as little work as
  possible."
  [interval interval-ratio]
  (if (util/float= interval-ratio 1)
    ;; Most efficient case, we can use the phase information directly in the snapshot
    (case interval
      :beat (fn [^afterglow.rhythm.MetronomeSnapshot snapshot] (.beat-phase snapshot))
      :bar (fn [^afterglow.rhythm.MetronomeSnapshot snapshot] (.bar-phase snapshot))
      :phrase (fn [^afterglow.rhythm.MetronomeSnapshot snapshot] (.phrase-phase snapshot)))
    ;; Must calculate enhanced phase based on the interval ratio
    (case interval
      :beat (fn [^afterglow.rhythm.MetronomeSnapshot snapshot] (rhythm/snapshot-beat-phase snapshot interval-ratio))
      :bar (fn [^afterglow.rhythm.MetronomeSnapshot snapshot] (rhythm/snapshot-bar-phase snapshot interval-ratio))
      :phrase (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
                (rhythm/snapshot-phrase-phase snapshot interval-ratio)))))

(defn- build-adjusted-phase-fn
  "Constructs the function which uses the oscillator phase function to
  calculate a base phase, then offsets that with a fixed phase
  parameter. If the phase offset is zero, simply returns the function
  that calculates the unadjusted phase."
  [base-fn phase]
  (if (util/float= phase 0.0)
    base-fn
    (fn [^afterglow.rhythm.MetronomeSnapshot snapshot] (adjust-phase (base-fn snapshot) phase))))

(defn- fixed-oscillator
  "Build an optimized version of an oscillator which can be used when
  none of its configuration parameters are dynamic parameters.
  See [[build-oscillator]] for details about the parameters."
  [shape-fn interval interval-ratio phase]
  (let [base-phase-fn (build-base-phase-fn interval interval-ratio)
        adjusted-phase-fn (build-adjusted-phase-fn base-phase-fn phase)]
    (reify IOscillator
      (evaluate [this _ snapshot _]
        (shape-fn (adjusted-phase-fn snapshot)))
      (resolve-non-frame-dynamic-elements [this _ _ _]  ; Already resolved
        this))))

(defn- simple-oscillator
  "Build an oscillator which has at least one dynamic parameter, but
  whose shape function does not use any. See [[build-oscillator]] for
  details about the parameters."
  [shape-fn interval interval-ratio phase]
  (reify IOscillator
    (evaluate [this show snapshot head]
      (let [interval (params/resolve-param interval show snapshot head)
            interval-ratio (params/resolve-param interval-ratio show snapshot head)
            phase (params/resolve-param phase show snapshot head)
            base-phase-fn (build-base-phase-fn interval interval-ratio)
            adjusted-phase-fn (build-adjusted-phase-fn base-phase-fn phase)]
        (shape-fn (adjusted-phase-fn snapshot))))
    (resolve-non-frame-dynamic-elements [this show snapshot head]
      (if (not-any? params/frame-dynamic-param? [interval interval-ratio phase])
        ;; Can now resolve and optimize
        (let [interval (params/resolve-param interval show snapshot head)
              interval-ratio (params/resolve-param interval-ratio show snapshot head)
              phase (params/resolve-param phase show snapshot head)]
          (fixed-oscillator shape-fn interval interval-ratio phase))
        ;; Can't optimize, there is at least one frame-dynamic parameter, so return self
        this))))

(defn- variable-oscillator
  "Build an oscillator whose shape function relies on dynamic
  parameters. See [[build-oscillator]] for details about the
  parameters."
  [shape-fn interval interval-ratio phase]
  (reify IOscillator
    (evaluate [this show snapshot head]
      (let [interval (params/resolve-param interval show snapshot head)
            interval-ratio (params/resolve-param interval-ratio show snapshot head)
            phase (params/resolve-param phase show snapshot head)
            base-phase-fn (build-base-phase-fn interval interval-ratio)
            adjusted-phase-fn (build-adjusted-phase-fn base-phase-fn phase)]
        (value-for-phase shape-fn (adjusted-phase-fn snapshot) show snapshot head)))
    (resolve-non-frame-dynamic-elements [this show snapshot head]
      (let [shape-fn (simplify-unless-frame-dynamic shape-fn show snapshot head)]
        (if (ifn? shape-fn)  ; The function simplified and no longer depends on dynamic parameters; we can optimize
          (if (not-any? params/frame-dynamic-param? [interval interval-ratio phase])
            ;; No parameters are frame-dynamic, can resolve and optimize all the way to a fixed oscillator
            (let [interval (params/resolve-param interval show snapshot head)
                  interval-ratio (params/resolve-param interval-ratio show snapshot head)
                  phase (params/resolve-param phase show snapshot head)]
              (fixed-oscillator shape-fn interval interval-ratio phase))
            ;; Can't fully optimize, there is at least one frame-dynamic parameter, so return a simple oscillator
            (simple-oscillator shape-fn interval interval-ratio phase))
          ;; Can't optimize at all, shape-fn is frame-dynamic, so return self
          this)))))

(defn build-oscillator
  "Returns an oscillator which generates a waveform relative to the
  phase of the current beat, bar, or phrase. The shape of the wave is
  determined by the first argument, `shape-fn`.

  In the simplest case, `shape-fn` is a function that takes a single
  argument, the curent phase of the oscillator, which ranges from `0`
  to `1`, and returns what the value fo the oscillator should be at
  that phase in its waveform. The value returned by `shape-fn` must
  also range from `0` to `1`.

  If the shape of the oscillator needs to be able to change over time
  depending on the value of a [dynamic
  parameter](https://github.com/brunchboy/afterglow/blob/master/doc/parameters.adoc#dynamic-parameters),
  then `shape-fn` will instead implement [[IVariableShape]] in order
  to be able to resolve those parameters.

  All of the standard oscillators provided by Afterglow are built in
  this way. For example, an upwards-sloping sawtooth wave would be
  created by passing a `shape-fn` that simply returns its argument:

  ```
  (build-oscillator (fn [phase] phase))
   ```

  For examples of how to generate other kinds of waveforms, view the
  source for [[sine]], [[square]], and [[triangle]].

  With no additional arguments, the waveform is defined by calling
  `shape-fn` with a phase argument that ramps upward from `0` to `1`
  over the course of each beat.

  Passing the value `:bar` or `:phrase` with the optional keyword
  argument `:interval` makes the wave cycle over a bar or phrase
  instead.

  Supplying a value with `:interval-ratio` will run the oscillator at
  the specified fraction or multiple of the chosen interval (beat,
  bar, or phrase), and supplying a `:phase` will offset the oscillator
  from the underlying metronome phase by that amount. (See the
  [documentation](https://github.com/brunchboy/afterglow/blob/master/doc/oscillators.adoc#sawtooth-oscillators)
  for an expanded explanation illustrated with graphs.)

  The arguments after `shape-fn` can be [dynamic
  parameters](https://github.com/brunchboy/afterglow/blob/master/doc/parameters.adoc#dynamic-parameters)."
  [shape-fn & {:keys [interval interval-ratio phase] :or {interval :beat interval-ratio 1 phase 0.0}}]
  {:pre [(or (ifn? shape-fn) (satisfies? IVariableShape shape-fn))]}
  (params/validate-param-type interval clojure.lang.Keyword)
  (let [interval-ratio (params/bind-keyword-param interval-ratio Number 1)
        phase (params/bind-keyword-param phase Number 0)]
    (if (and (not-any? params/param? [interval interval-ratio phase]) (ifn? shape-fn))
      (fixed-oscillator shape-fn interval interval-ratio phase)  ; Optimized case with no dynamic inputs
      ;; We have a variable parameter or variable shape function; need to do a bit more work
      (if (ifn? shape-fn)
        (simple-oscillator shape-fn interval interval-ratio phase)
        (variable-oscillator shape-fn interval interval-ratio phase)))))

(defn- build-fixed-sawtooth-shape-fn
  "Returns the shape function for a sawtooth wave in a fixed direction."
  [down?]
  (if down?
    (fn [phase] (- 1.0 phase))
    (fn [phase] phase)))

(defn sawtooth
  "Returns an oscillator which generates a sawtooth wave relative to
  the phase of the current beat, bar, or phrase. With no arguments, it
  creates a sawtooth wave that ramps upward from `0` to `1` over the
  course of each beat.

  Passing `true` with `:down?` creates an inverse sawtooth wave (one
  that ramps downward from `1` to `0` over the course of the interval).

  Passing the value `:bar` or `:phrase` with the optional keyword
  argument `:interval` makes the wave cycle over a bar or phrase
  instead.

  Supplying a value with `:interval-ratio` will run the oscillator at
  the specified fraction or multiple of the chosen interval (beat,
  bar, or phrase), and supplying a `:phase` will offset the oscillator
  from the underlying metronome phase by that amount. (See the
  [documentation](https://github.com/brunchboy/afterglow/blob/master/doc/oscillators.adoc#sawtooth-oscillators)
  for an expanded explanation illustrated with graphs.)

  The arguments can be [dynamic
  parameters](https://github.com/brunchboy/afterglow/blob/master/doc/parameters.adoc#dynamic-parameters)."
  [& {:keys [down? interval interval-ratio phase] :or {down? false interval :beat interval-ratio 1 phase 0.0}}]
  (let [down? (params/bind-keyword-param down? Boolean false)
        shape-fn (if (params/param? down?)
                   (reify IVariableShape  ; The shape function changes based on the dynamic value of down?
                     (value-for-phase [this phase show snapshot head]
                       (let [down? (params/resolve-param down? show snapshot head)]
                         (if down? (- 1.0 phase) phase)))
                     (simplify-unless-frame-dynamic [this show snapshot head]
                       (if (params/frame-dynamic-param? down?)
                         this  ; Can't simplify, we depend on a frame-dynamic parameter
                         (let [down? (params/resolve-param [down? show snapshot head])]  ; Can simplify
                           (build-fixed-sawtooth-shape-fn down?)))))
                   ;; The shape function depends on the fixed value of down?, so can be finalized now
                   (build-fixed-sawtooth-shape-fn down?))]
    (build-oscillator shape-fn :interval interval :interval-ratio interval-ratio :phase phase)))

(defn triangle
  "Returns an oscillator which generates a triangle wave relative to
  the phase of the current beat, bar, or phrase. With no arguments, it
  creates a triangle wave that ramps upward from `0` to `1` over the
  first half of each beat, then back down to `0` through the end of
  the beat.

  Passing the value `:bar` or `:phrase` with the optional keyword
  argument `:interval` makes the wave cycle over a bar or phrase
  instead.

  Supplying a value with `:interval-ratio` will run the oscillator at
  the specified fraction or multiple of the chosen interval (beat,
  bar, or phrase), and supplying a `:phase` will offset the oscillator
  from the underlying metronome phase by that amount. (See the
  [documentation](https://github.com/brunchboy/afterglow/blob/master/doc/oscillators.adoc#triangle-oscillators)
  for an expanded explanation illustrated with graphs.)
  
  All the arguments can be [dynamic
  parameters](https://github.com/brunchboy/afterglow/blob/master/doc/parameters.adoc#dynamic-parameters)."
  [& {:keys [interval interval-ratio phase] :or {interval :beat interval-ratio 1 phase 0.0}}]
  (build-oscillator (fn [phase]
                      (if (< phase 0.5)
                        (* phase 2.0)
                        (- 2.0 (* phase 2.0)))) :interval interval :interval-ratio interval-ratio :phase phase))

(defn- build-fixed-square-shape-fn
  "Returns the shape function for a square wave with a fixed width."
  [width]
  (when-not  (<= 0.0 width 1.0)
    (throw (IllegalArgumentException. "width must fall between 0.0 and 1.0")))
  (fn [phase]
    (if (< phase width) 1.0 0.0)))

(defn square
  "Returns an oscillator which generates a square wave relative to the
  phase of the current beat, bar, or phrase. With no arguments, it
  creates a square wave that starts at `1` at the start of each beat,
  and drops to `0` at the midpoint.

  Specifying a value with `:width` adjusts how much of the time the
  wave is _on_ (high); the default is `0.5`, lower values cause it to
  turn off sooner, larger values later. In any case the width must be
  within the range `0.0` to `1.0`. A value of zero means the
  oscillator is always off, and a value of one means it is always on.

  Passing the value `:bar` or `:phrase` with the optional keyword
  argument `:interval` makes the wave cycle over a bar or phrase
  instead.

  Supplying a value with `:interval-ratio` will run the oscillator at
  the specified fraction or multiple of a beat, and supplying a
  `:phase` will offset the oscillator from the underlying metronome
  phase by that amount. (See the
  [documentation](https://github.com/brunchboy/afterglow/blob/master/doc/oscillators.adoc#square-oscillators)
  for an expanded explanation illustrated with graphs.)

  The arguments can be [dynamic
  parameters](https://github.com/brunchboy/afterglow/blob/master/doc/parameters.adoc#dynamic-parameters)."
  [& {:keys [width interval interval-ratio phase] :or {width 0.5 interval :beat interval-ratio 1 phase 0.0}}]
  (let [width (params/bind-keyword-param width Number 0.5)
        shape-fn (if (params/param? width)
                   (reify IVariableShape  ; The shape function changes based on the dynamic value of width
                     (value-for-phase [this phase show snapshot head]
                       (let [width (params/resolve-param width show snapshot head)]
                         (when-not (<= 0.0 width 1.0)
                           (throw (IllegalArgumentException. "width must fall between 0.0 and 1.0")))
                         (if (< phase width) 1.0 0.0)))
                     (simplify-unless-frame-dynamic [this show snapshot head]
                       (if (params/frame-dynamic-param? width)
                         this  ; Can't simplify, we depend on a frame-dynamic parameter
                         (let [width (params/resolve-param [width show snapshot head])]  ; Can simplify
                           (build-fixed-square-shape-fn width)))))
                   ;; The shape function depends on the fixed value of width, so can be finalized now
                   (build-fixed-square-shape-fn width))]
    (build-oscillator shape-fn :interval interval :interval-ratio interval-ratio :phase phase)))

(defn sine
  "Returns an oscillator which generates a sine wave relative to the
  phase of the current beat, bar, or phrase. With no arguments, it
  creates a sine wave that curves upward from `0` to `1` over the
  first half of each beat, then back down to `0` through the end of
  the beat.

  Passing the value `:bar` or `:phrase` with the optional keyword
  argument `:interval` makes the wave cycle over a bar or phrase
  instead.

  Supplying a value with `:interval-ratio` will run the oscillator at
  the specified fraction or multiple of th chosen interval (beat, bar,
  or phrase), and supplying a `:phase` will offset the oscillator from
  the underlying metronome phase by that amount.
  (See the
  [documentation](https://github.com/brunchboy/afterglow/blob/master/doc/oscillators.adoc#sine-oscillators)
  for an expanded explanation illustrated with graphs.)

  All the arguments can be [dynamic
  parameters](https://github.com/brunchboy/afterglow/blob/master/doc/parameters.adoc#dynamic-parameters)."
  [& {:keys [interval interval-ratio phase] :or {interval :beat interval-ratio 1 phase 0.0}}]
  (let [two-pi (* 2.0 Math/PI)]
    (build-oscillator (fn [phase]
                        (let [adjusted-phase (- phase 0.25)]
                          (+ 0.5 (* 0.5 (Math/sin (* two-pi adjusted-phase))))))
                      :interval interval :interval-ratio interval-ratio :phase phase)))

(defn- evaluate-oscillator
  "Handles the calculation of an oscillator based on dynamic parameter
  values for at least one of min and max."
  [show params-snapshot head min max osc osc-snapshot]
  (let [min (params/resolve-param min show params-snapshot head)
        max (clojure.core/max min (params/resolve-param max show params-snapshot head))  ; Avoid impossible case
        range (- max min)]
    (+ min (* range (evaluate osc show osc-snapshot head)))))

(defn build-oscillated-param
  "Returns a number parameter that is driven by
  an [oscillator](https://github.com/brunchboy/afterglow/blob/master/doc/oscillators.adoc#oscillators).
  By default will be frame-dynamic, since it oscillates, but if you
  pass a `false` value for `:frame-dynamic`, the value will be fixed
  once it is assigned to an effect, acting like a random number
  generator with the oscillator's range. If you don't specify a
  `:metronome` to use, the
  main [metronome](https://github.com/brunchboy/afterglow/blob/master/doc/metronomes.adoc#metronomes)
  in [[*show*]] will be used.

  The values returned by the oscillator will be mapped onto the range
  from 0 to 255. If you would like to use a different range, you can
  pass in alternate numbers with the optional keyword arguments `:min`
  and `:max`. If the values you supply result in a maximum that is
  less than or equal to the minimum, the oscillated parameter will be
  stuck at the value you gave with `:min`."
  [osc & {:keys [min max metronome frame-dynamic] :or {min 0 max 255 frame-dynamic true}}]
  {:pre [(some? *show*) (satisfies? IOscillator osc)]}
  (let [min (params/bind-keyword-param min Number 0)
        max (params/bind-keyword-param max Number 255)
        metronome (params/bind-keyword-param metronome Metronome (:metronome *show*))]
    (if (not-any? params/param? [min max metronome])
      ;; Optimize the simple case of all constant parameters
      (let [max (clojure.core/max min max)  ; Handle case where min > max
            range (- max min)
            dyn (boolean frame-dynamic)
            eval-fn (if (some? metronome)
                      (fn [show snapshot head]
                        (let [osc-snapshot (rhythm/metro-snapshot metronome (:instant snapshot))]
                          (+ min (* range (evaluate osc show osc-snapshot head)))))
                      (fn [show snapshot head] (+ min (* range (evaluate osc show snapshot head)))))
            resolve-fn (fn [show snapshot head]
                         (with-show show
                           (build-oscillated-param (resolve-non-frame-dynamic-elements osc show snapshot head)
                                                   :min (params/resolve-unless-frame-dynamic min show snapshot head)
                                                   :max (params/resolve-unless-frame-dynamic max show snapshot head)
                                                   :metronome (params/resolve-unless-frame-dynamic metronome show
                                                                                                   snapshot head)
                                                   :frame-dynamic frame-dynamic)))]
        (reify
          params/IParam
          (params/evaluate [this show snapshot head]
            (eval-fn show snapshot head))
          (params/frame-dynamic? [this]
            dyn)
          (params/result-type [this]
            Number)
          (params/resolve-non-frame-dynamic-elements [this show snapshot head]
            (resolve-fn show snapshot head))))
      ;; Support the general case where we have an incoming variable parameter
      (let [dyn (boolean frame-dynamic)
            eval-fn (if (some? metronome)
                      (fn [show snapshot head]
                        (let [local-metronome (params/resolve-param metronome show snapshot head)
                              osc-snapshot (rhythm/metro-snapshot local-metronome (:instant snapshot))]
                          (evaluate-oscillator show snapshot head min max osc osc-snapshot)))
                      (fn [show snapshot head]
                        (evaluate-oscillator show snapshot head min max osc snapshot)))
            resolve-fn (fn [show snapshot head]
                         (with-show show
                           (build-oscillated-param (resolve-non-frame-dynamic-elements osc show snapshot head)
                                                   :min (params/resolve-unless-frame-dynamic min show snapshot head)
                                                   :max (params/resolve-unless-frame-dynamic max show snapshot head)
                                                   :metronome (params/resolve-unless-frame-dynamic
                                                               metronome show snapshot head)
                                                   :frame-dynamic dyn)))]
        (reify
          params/IParam
          (params/evaluate [this show snapshot head]
            (eval-fn show snapshot head))
          (params/frame-dynamic? [this]
            dyn)
          (params/result-type [this]
            Number)
          (params/resolve-non-frame-dynamic-elements [this show snapshot head]
            (resolve-fn show snapshot head)))))))
