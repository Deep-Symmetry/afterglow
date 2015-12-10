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
  "A waveform generator for building effects that vary at frequencies
  related to a show metronome."
  (evaluate [this show snapshot head]
    "Determine the value of this oscillator at a given moment of the
    show. In addition to the metronome snapshot, the show and (if
    applicable) fixture head must be passed in case any oscillator
    configuration arguments rely
    on [variable](https://github.com/brunchboy/afterglow/blob/master/doc/parameters.adoc#variable-parameters)
    or [spatial](https://github.com/brunchboy/afterglow/blob/master/doc/parameters.adoc#spatial-parameters)
    parameters.")
  (resolve-non-frame-dynamic-elements [this show snapshot head]
    "Called when an effect is created using this oscillator. Returns a
    version of itself where any non frame-dynamic input parameters
    have been resolved."))))

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
  none of its configuration parameters are dynamic variables."
  [shape-fn interval interval-ratio phase]
  (let [base-phase-fn (build-base-phase-fn interval interval-ratio)
        adjusted-phase-fn (build-adjusted-phase-fn base-phase-fn phase)]
    (reify IOscillator
      (evaluate [this _ snapshot _]
        (shape-fn (adjusted-phase-fn snapshot)))
      (resolve-non-frame-dynamic-elements [this _ _ _]  ; Already resolved
        this))))

(defn build-oscillator
  "Returns an oscillator which generates a waveform relative to the
  phase of the current beat, bar, or phrase. The shape of the wave is
  determined by the first argument, `shape-fn`, which is a function
  that takes a single argument, the curent phase of the oscillator,
  which ranges from `0` to `1`, and returns what the value fo the
  oscillator should be at that phase in its waveform. The value
  returned by `shape-fn` must also range from `0` to `1`. All of the
  standard oscillators provided by Afterglow are built in this way.
  For example, an upwards-sloping sawtooth wave would be created by
  passing a `shape-fn` that simply returns its argument:

  ```
  (build-oscillator (fn [phase] phase))
   ```

  For examples of how to generate other kinds of waveforms, view the
  source for [[sine]], [[square]], and [[triangle]].

  With no additional arguments, the waveform is defined by calling
  `shape-fn` with an argument that ramps upward from `0` to `1` over
  the course of each beat.

  Passing the value `:bar` or `:phrase` with the optional keyword
  argument `:interval` makes the wave cycle over a bar or phrase
  instead.

  Supplying a value with `:interval-ratio` will run the oscillator at
  the specified fraction or multiple of the chosen interval (beat,
  bar, or phrase), and supplying a `:phase` will offset the oscillator
  from the underlying metronome phase by that amount. (See the
  [documentation](https://github.com/brunchboy/afterglow/blob/master/doc/oscillators.adoc#sawtooth-oscillators)
  for an expanded explanation illustrated with graphs.)"
  [shape-fn & {:keys [interval interval-ratio phase] :or {interval :beat interval-ratio 1 phase 0.0}}]
  {:pre [(fn? shape-fn)]}
  (let [interval (params/bind-keyword-param interval clojure.lang.Keyword :beat)
        interval-ratio (params/bind-keyword-param interval-ratio Number 1)
        phase (params/bind-keyword-param phase Number 0)]
    (if (not-any? params/param? [interval interval-ratio phase])  ; Can optimize case with no dynamic parameters
      (fixed-oscillator shape-fn interval interval-ratio phase)
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
            this))))))

(defn- fixed-sawtooth
  "Build an optimized version of the sawtooth oscillator which can be
  used when none of its configuration parameters are dynamic
  variables."
  [interval down? interval-ratio phase]
  (let [base-phase-fn (build-base-phase-fn interval interval-ratio)
        adjusted-phase-fn (build-adjusted-phase-fn base-phase-fn phase)]
    (if down?
      (reify IOscillator  ; Downward sawtooth wave
        (evaluate [this _ snapshot _]
          (- 1.0 (adjusted-phase-fn snapshot)))
        (resolve-non-frame-dynamic-elements [this _ _ _]  ; Already resolved
          this))
      (reify IOscillator  ; Upward sawtooth wave
        (evaluate [this _ snapshot _]
          (adjusted-phase-fn snapshot))
         (resolve-non-frame-dynamic-elements [this _ _ _]  ; Already resolved
           this)))))

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

  Other than `:down`, which establishes the basic waveform, all
  arguments can be [dynamic
  parameters](https://github.com/brunchboy/afterglow/blob/master/doc/parameters.adoc#dynamic-parameters)."
  [& {:keys [down? interval interval-ratio phase] :or {down? false interval :beat interval-ratio 1 phase 0.0}}]
  (let [shape-fn (if down?
                   (fn [phase] (- 1.0 phase))
                   (fn [phase] phase))]
    (build-oscillator shape-fn :interval interval :interval-ratio interval-ratio :phase phase)))

(defn sawtooth-beat
  "In version 0.1.6 this was replaced with the [[sawtooth]] function,
  and this stub was left for backwards compatibility, but is
  deprecated and will be removed in a future release.

  Returns an oscillator which generates a sawtooth wave relative to the
  phase of the current bar. Passing `true` with `:down?` creates an
  inverse sawtooth wave (ramps downward rather than upward), supplying
  a value with `:bar-ratio` will run the oscillator at the specified
  fraction or multiple of a bar, and supplying a `:phase` will offset
  the oscillator from the underlying metronome phase by that
  amount. (See the
  [documentation](https://github.com/brunchboy/afterglow/blob/master/doc/oscillators.adoc#sawtooth-oscillators)
  for an expanded explanation illustrated with graphs.)"
  {:deprecated "0.1.6"}
  [& {:keys [down? beat-ratio phase] :or {down? false beat-ratio 1 phase 0.0}}]
  (sawtooth :down? down? :interval-ratio beat-ratio :phase phase))

(defn sawtooth-bar
  "In version 0.1.6 this was replaced with the [[sawtooth]] function,
  and this stub was left for backwards compatibility, but is
  deprecated and will be removed in a future release.

  Returns an oscillator which generates a sawtooth wave relative to the phase
  of the current bar. Passing `true` with `:down?` creates an inverse sawtooth
  wave (ramps downward rather than upward), supplying a value with
  `:bar-ratio` will run the oscillator at the specified fraction or
  multiple of a bar, and supplying a `:phase` will offset the oscillator
  from the underlying metronome phase by that amount. (See the
  [documentation](https://github.com/brunchboy/afterglow/blob/master/doc/oscillators.adoc#sawtooth-oscillators)
  for an expanded explanation illustrated with graphs.)"
  {:deprecated "0.1.6"}
  [& {:keys [down? bar-ratio phase] :or {down? false bar-ratio 1 phase 0.0}}]
  (sawtooth :interval :bar :down? down? :interval-ratio bar-ratio :phase phase))

(defn sawtooth-phrase
  "In version 0.1.6 this was replaced with the [[sawtooth]] function,
  and this stub was left for backwards compatibility, but is
  deprecated and will be removed in a future release.

  Returns an oscillator which generates a sawtooth wave relative to the phase
  of the current phrase. Passing `true` with `:down?` creates an inverse sawtooth wave
  (ramps downward rather than upward), supplying a value with
  `:phrase-ratio` will run the oscillator at the specified fraction or
  multiple of a phrase, and supplying a `:phase` will offset the
  oscillator from the underlying metronome phase by that amount. (See the
  [documentation](https://github.com/brunchboy/afterglow/blob/master/doc/oscillators.adoc#sawtooth-oscillators)
  for an expanded explanation illustrated with graphs.)"
  {:deprecated "0.1.6"}
  [& {:keys [down? phrase-ratio phase] :or {down? false phrase-ratio 1 phase 0.0}}]
  (sawtooth :interval :phrase :down? down? :interval-ratio phrase-ratio :phase phase))

(defn triangle-beat
  "Returns an oscillator which generates a triangle wave relative to
  the phase of the current beat. Supplying a value with `:beat-ratio`
  will run the oscillator at the specified fraction or multiple of a
  beat, and supplying a `:phase` will offset the oscillator from the
  underlying metronome phase by that amount. (See the
  [documentation](https://github.com/brunchboy/afterglow/blob/master/doc/oscillators.adoc#triangle-oscillators)
  for an expanded explanation illustrated with graphs.)"
  [& {:keys [beat-ratio phase] :or {beat-ratio 1 phase 0.0}}]
  (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
    (let [reached (adjust-phase (rhythm/snapshot-beat-phase snapshot beat-ratio) phase)]
      (if (< reached 0.5)
        (* reached 2.0)
        (- 2.0 (* reached 2.0))))))

(defn triangle-bar
  "Returns an oscillator which generates a triangle wave relative to
  the phase of the current bar. Supplying a value with `:bar-ratio`
  will run the oscillator at the specified fraction or multiple of a
  bar, and supplying a `:phase` will offset the oscillator from the
  underlying metronome phase by that amount. (See the
  [documentation](https://github.com/brunchboy/afterglow/blob/master/doc/oscillators.adoc#triangle-oscillators)
  for an expanded explanation illustrated with graphs.)"
  [& {:keys [bar-ratio phase] :or {bar-ratio 1 phase 0.0}}]
  (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
    (let [reached (adjust-phase (rhythm/snapshot-bar-phase snapshot bar-ratio) phase)]
            (if (< reached 0.5)
        (* reached 2.0)
        (- 2.0 (* reached 2.0))))))

(defn triangle-phrase
  "Returns an oscillator which generates a triangle wave relative to
  the phase of the current phrase. Supplying a value with
  `:phrase-ratio` will run the oscillator at the specified fraction or
  multiple of a phrase, and supplying a `:phase` will offset the
  oscillator from the underlying metronome phase by that amount. (See the
  [documentation](https://github.com/brunchboy/afterglow/blob/master/doc/oscillators.adoc#triangle-oscillators)
  for an expanded explanation illustrated with graphs.)"
  [& {:keys [phrase-ratio phase] :or {phrase-ratio 1 phase 0.0}}]
  (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
    (let [reached (adjust-phase (rhythm/snapshot-phrase-phase snapshot phrase-ratio) phase)]
      (if (< reached 0.5)
        (* reached 2.0)
        (- 2.0 (* reached 2.0))))))

(defn square-beat
  "Returns an oscillator which generates a square wave relative to the
  phase of the current beat. Specifying a value with `:width` adjusts
  how much of the time the wave is _on_ (high); the default is `0.5`,
  lower values cause it to turn off sooner, larger values later. In
  any case the width must be greater than `0.0` and less than `1.0`.
  Supplying a value with `:beat-ratio` will run the oscillator at the
  specified fraction or multiple of a beat, and supplying a `:phase`
  will offset the oscillator from the underlying metronome phase by
  that amount. (See the
  [documentation](https://github.com/brunchboy/afterglow/blob/master/doc/oscillators.adoc#square-oscillators)
  for an expanded explanation illustrated with graphs.)"
  [& {:keys [width beat-ratio phase] :or {width 0.5 beat-ratio 1 phase 0.0}}]
  (when-not (and (> width 0.0) (< width 1.0))
    (throw (IllegalArgumentException. "width must fall between 0.0 and 1.0")))
  (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
    (let [reached (adjust-phase (rhythm/snapshot-beat-phase snapshot beat-ratio) phase)]
      (if (< reached width)
        1.0
        0.0))))

(defn square-bar
  "Returns an oscillator which generates a square wave relative to the
  phase of the current bar. Specifying a value with `:width` adjusts
  how much of the time the wave is _on_ (high); the default is `0.5`,
  lower values cause it to turn off sooner, larger values later. In
  any case the width must be greater than `0.0` and less than `1.0`.
  Supplying a value with `:bar-ratio` will run the oscillator at the
  specified fraction or multiple of a bar, and supplying a `:phase`
  will offset the oscillator from the underlying metronome phase by
  that amount. (See the
  [documentation](https://github.com/brunchboy/afterglow/blob/master/doc/oscillators.adoc#square-oscillators)
  for an expanded explanation illustrated with graphs.)"
  [& {:keys [width bar-ratio phase] :or {width 0.5 bar-ratio 1 phase 0.0}}]
  (when-not (and (> width 0.0) (< width 1.0))
    (throw (IllegalArgumentException. "width must fall between 0.0 and 1.0")))
  (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
    (let [reached (adjust-phase (rhythm/snapshot-bar-phase snapshot bar-ratio) phase)]
      (if (< reached width)
        1.0
        0.0))))

(defn square-phrase
  "Returns an oscillator which generates a square wave relative to the
  phase of the current phrase. Specifying a value with `:width`
  adjusts how much of the time the wave is _on_ (high); the default is
  `0.5`, lower values cause it to turn off sooner, larger values
  later. In any case the width must be greater than `0.0` and less
  than `1.0`. Supplying a value with `:phrase-ratio` will run the
  oscillator at the specified fraction or multiple of a phrase, and
  supplying a `:phase` will offset the oscillator from the underlying
  metronome phase by that amount. (See the
  [documentation](https://github.com/brunchboy/afterglow/blob/master/doc/oscillators.adoc#square-oscillators)
  for an expanded explanation illustrated with graphs.)"
  [& {:keys [width phrase-ratio phase] :or {width 0.5 phrase-ratio 1 phase 0.0}}]
  (when-not (and (> width 0.0) (< width 1.0))
    (throw (IllegalArgumentException. "width must fall between 0.0 and 1.0")))
  (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
    (let [reached (adjust-phase (rhythm/snapshot-phrase-phase snapshot phrase-ratio) phase)]
      (if (< reached width)
        1.0
        0.0))))

(defn sine-beat
  "Returns an oscillator which generates a sine wave relative to the
  phase of the current beat. The wave has value `0.0` at phase `0.0`,
  rising to `1.0` at phase `0.5`, and returning to `0.0`. Supplying a
  value with `:beat-ratio` will run the oscillator at the specified
  fraction or multiple of a beat, and supplying a `:phase` will offset
  the oscillator from the underlying metronome phase by that amount.
  (See the
  [documentation](https://github.com/brunchboy/afterglow/blob/master/doc/oscillators.adoc#sine-oscillators)
  for an expanded explanation illustrated with graphs.)"
  [& {:keys [beat-ratio phase] :or {beat-ratio 1 phase 0.0}}]
  (let [adjusted-phase (- phase 0.25)
        two-pi (* 2.0 Math/PI)]
    (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
      (+ 0.5 (Math/sin (* two-pi (adjust-phase (rhythm/snapshot-beat-phase snapshot beat-ratio) adjusted-phase)))))))

(defn sine-bar
  "Returns an oscillator which generates a sine wave relative to the
  phase of the current bar. The wave has value `0.0` at phase `0.0`,
  rising to `1.0` at phase `0.5`, and returning to `0.0`. Supplying a
  value with `:bar-ratio` will run the oscillator at the specified
  fraction or multiple of a bar, and supplying a `:phase` will offset
  the oscillator from the underlying metronome phase by that
  amount. (See the
  [documentation](https://github.com/brunchboy/afterglow/blob/master/doc/oscillators.adoc#sine-oscillators)
  for an expanded explanation illustrated with graphs.)"
  [& {:keys [bar-ratio phase] :or {bar-ratio 1 phase 0.0}}]
  (let [adjusted-phase (- phase 0.25)
        two-pi (* 2.0 Math/PI)]
    (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
      (+ 0.5 (* 0.5 (Math/sin (* two-pi (adjust-phase (rhythm/snapshot-bar-phase snapshot bar-ratio)
                                                      adjusted-phase))))))))

(defn sine-phrase
  "Returns an oscillator which generates a sine wave relative to the
  phase of the current phrase. The wave has value `0.0` at phase
  `0.0`, rising to `1.0` at phase `0.5`, and returning to `0.0`.
  Supplying a value with `:phrase-ratio` will run the oscillator at
  the specified fraction or multiple of a phrase, and supplying a
  `:phase` will offset the oscillator from the underlying metronome
  phase by that amount. (See the
  [documentation](https://github.com/brunchboy/afterglow/blob/master/doc/oscillators.adoc#sine-oscillators)
  for an expanded explanation illustrated with graphs.)"
  [& {:keys [phrase-ratio phase] :or {phrase-ratio 1 phase 0.0}}]
  (let [adjusted-phase (- phase 0.25)
        two-pi (* 2.0 Math/PI)]
    (fn [^afterglow.rhythm.MetronomeSnapshot snapshot]
      (+ 0.5 (* 0.5 (Math/sin (* two-pi (adjust-phase (rhythm/snapshot-phrase-phase snapshot phrase-ratio)
                                                      adjusted-phase))))))))

(defn- evaluate-oscillator
  "Handles the calculation of an oscillator based on dynamic parameter
  values for at least one of min and max."
  [show params-snapshot head min max osc osc-snapshot]
  (let [min (params/resolve-param min show params-snapshot head)
        max (params/resolve-param max show params-snapshot head)
        range (- max min)]
    (if (neg? range)
      (do
        (timbre/error "Oscillator dynamic parameters min > max, returning max.")
        max)
      (+ min (* range (evaluate osc show osc-snapshot head))))))

(defn build-oscillated-param
  "Returns a number parameter that is driven by
  an [oscillator](https://github.com/brunchboy/afterglow/blob/master/doc/oscillators.adoc#oscillators).
  By default will be frame-dynamic, since it oscillates, but if you
  pass a `false` value for `:frame-dynamic`, the value will be fixed
  once it is assigned to an effect, acting like a random number
  generator with the oscillator's range. If you don't specify a
  `:metronome` to use, the
  main [metronome](https://github.com/brunchboy/afterglow/blob/master/doc/metronomes.adoc#metronomes)
  in [[*show*]] will be used."
  [osc & {:keys [min max metronome frame-dynamic] :or {min 0 max 255 frame-dynamic true}}]
  {:pre [(some? *show*) (satisfies? IOscillator osc)]}
  (let [min (params/bind-keyword-param min Number 0)
        max (params/bind-keyword-param max Number 255)
        metronome (params/bind-keyword-param metronome Metronome (:metronome *show*))]
    (if (not-any? params/param? [min max metronome])
      ;; Optimize the simple case of all constant parameters
      (let [range (- max min)
            dyn (boolean frame-dynamic)
            eval-fn (if (some? metronome)
                      (fn [show snapshot head]
                        (let [osc-snapshot (rhythm/metro-snapshot metronome (:instant snapshot))]
                          (+ min (* range (evaluate osc show osc-snapshot head)))))
                      (fn [show snapshot head] (+ min (* range (evaluate osc show snapshot head)))))
            resolve-fn (fn [show snapshot head]
                         (with-show show
                           (build-oscillated-param (resolve-non-frame-dynamic-elements osc show snapshot head)
                                                   :min min :max max :metronome metronome
                                                   :frame-dynamic frame-dynamic)))]
        (when-not (pos? range)
          (throw (IllegalArgumentException. "min must be less than max")))
        (reify
          params/IParam
          (params/evaluate [this show snapshot]
            (eval-fn show snapshot nil))
          (params/frame-dynamic? [this]
            dyn)
          (params/result-type [this]
            Number)
          (params/resolve-non-frame-dynamic-elements [this show snapshot]
            (resolve-fn show snapshot nil))

          params/IHeadParam
          (params/evaluate-for-head [this show snapshot head]
            (eval-fn show snapshot head))
          (params/resolve-non-frame-dynamic-elements-for-head [this show snapshot head]
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
          (params/evaluate [this show snapshot]
            (eval-fn show snapshot nil))
          (params/frame-dynamic? [this]
            dyn)
          (params/result-type [this]
            Number)
          (params/resolve-non-frame-dynamic-elements [this show snapshot]
            (resolve-fn show snapshot nil))

          params/IHeadParam
          (params/evaluate-for-head [this show snapshot head]
            (eval-fn show snapshot head))
          (params/resolve-non-frame-dynamic-elements-for-head [this show snapshot head]
            (resolve-fn show snapshot head)))))))
