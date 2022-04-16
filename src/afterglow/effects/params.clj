(ns afterglow.effects.params
  "A general mechanism for passing dynamic parameters to effect
  functions and assigners allowing for dynamic values to be computed
  either when an effect creates its assigners, or when the assigners
  are resolving DMX values. Parameters can be calculated based on the
  show metronome snapshot, show variables (which can be bound to OSC
  and MIDI mappings), and other, not-yet-imagined things."
  {:author "James Elliott"}
  (:require [afterglow.channels :as chan]
            [afterglow.rhythm :as rhythm :refer [metro-snapshot]]
            [afterglow.show-context :refer [*show* with-show]]
            [afterglow.transform :as transform]
            [afterglow.util :as util]
            [clojure.math.numeric-tower :as math]
            [com.evocomputing.colors :as colors]
            [taoensso.timbre :as timbre :refer [error]])
  (:import [afterglow.rhythm MetronomeSnapshot]
           [javax.media.j3d Transform3D]
           [javax.vecmath Point3d Vector3d Vector2d]))

(defonce
  ^{:private true
    :doc "Protect protocols against namespace reloads"}
  _PROTOCOLS_
  (do
(defprotocol IParam
  "A dynamic parameter which gets evaluated during the run of a light
  show, with access to the show and its metronome snapshot."
  (evaluate [this show snapshot head]
  "Determine the value of this parameter at a given moment (identified
  by `snapshot`) of the `show`. If the parameter is being evaluated
  for a specific fixture head, that will be passed in `head`. If no
  fixure context is available, `head` will be `nil`.")
  (frame-dynamic? [this]
  "If `true`, this parameter varies at every frame of the show, and
  must be invoked by effect assigners for each frame of DMX data
  generated. If `false`, the value can be determined at the time an
  effect is created, and passed as a constant to the assigners.")
  (result-type [this]
  "The type of value that will be returned when this parameter is
  resolved.")
  (resolve-non-frame-dynamic-elements [this show snapshot head]
  "Called when an effect is created using this parameter. If the
  parameter is not frame-dynamic, this function should return the
  parameter's final resolution to a constant of the type returned by
  `result-type`; otherwise, it should return a version of the
  parameter where any of its own non frame-dynamic input parameters
  have been resolved.

  If the parameter is being evaluated for a specific fixture head,
  that will be passed in `head`. If no fixure context is available,
  `head` will be `nil`."))))


(defn check-type
  "Ensure that a parameter is of a particular type, or that it
  satisfies IParam and, when evaluated, returns that type, throwing an
  exception otherwise. Used by the validate-param-type macros to do
  the actual type checking."
  [value type-expected name]
  {:pre [(some? value) (some? type-expected) (some? name)]}
  (cond (class? type-expected)
        (when-not (or (instance? type-expected value)
                      (and (satisfies? IParam value)  (.isAssignableFrom type-expected (result-type value))))
          (throw (IllegalArgumentException. (str "Variable " name " must be of type " type-expected))))

        (keyword? type-expected)
        (when-not (or (= type-expected (type value))
                      (and (satisfies? IParam value) (= type-expected (result-type value))))
          (throw (IllegalArgumentException. (str "Variable " name " must be of type " type-expected))))

        :else
        (throw (IllegalArgumentException. (str "Do not know how to check for type " type-expected))))
  value)  ;; Return the value if it validated

(defmacro validate-param-type
  "Ensure that a parameter satisfies a predicate, or that it satisfies
  [[IParam]] and, when evaluated, returns a type that passes that predicate,
  throwing an exception otherwise."
  ([value type-expected]
   (let [arg value]
     `(check-type ~value ~type-expected ~(str arg))))
  ([value type-expected name]
   `(check-type ~value ~type-expected ~name)))

(defmacro validate-optional-param-type
  "Ensure that a parameter, if not `nil`, satisfies a predicate, or that
  it satisfies [[IParam]] and, when evaluated, returns a type that passes
  that predicate, throwing an exception otherwise."
  ([value type-expected]
   (let [arg value]
     `(validate-optional-param-type ~value ~type-expected ~(str arg))))
  ([value type-expected name]
   `(when (some? ~value) (check-type ~value ~type-expected ~name))))

(defn param?
  "Checks whether the argument is an [[IParam]]."
  [arg]
  (satisfies? IParam arg))

(defn resolve-param
  "Takes an argument which may be a raw value, or may be
  an [[IParam]]. If it is the latter, evaluates it and returns the
  resulting value. Otherwise just returns the value that was passed
  in."
  ([arg show snapshot]
   (resolve-param arg show snapshot nil))
  ([arg show snapshot head]
   (if (satisfies? IParam arg)
     (evaluate arg show snapshot head)
     arg)))

(defn frame-dynamic-param?
  "Checks whether the argument is an [[IParam]] which is dynamic to
  the frame level."
  [arg]
  (and (satisfies? IParam arg) (frame-dynamic? arg)))

(defn resolve-unless-frame-dynamic
  "If the first argument is an [[IParam]] which is not dynamic all the
  way to the frame level, return the result of resolving it now. If it
  is dynamic to the frame level, ask it to resolve any non
  frame-dynamic elements. Otherwise return it unchanged. If `head` is
  supplied, and the parameter can use it at resolution time, then pass
  it along."
  ([arg show snapshot]
   (resolve-unless-frame-dynamic arg show snapshot nil))
  ([arg show snapshot head]
   {:pre [(some? show)]}
   (if (satisfies? IParam arg)
     (if-not (frame-dynamic? arg)
       (resolve-param arg show snapshot head)
       (resolve-non-frame-dynamic-elements arg show snapshot head))
     arg)))

(defn build-variable-param
  "Create a dynamic parameter whose value is determined by the value
  held in a variable of [[*show*]] at the time evaluation is
  performed. Unless `:frame-dynamic` is passed a false value, this
  evaluation will happen every frame.

  If no type-compatible value is found in the show variable, a default
  value is returned. That will be the number zero, unless otherwise
  specified by `:default`. The type expected (and returned) by this
  parameter will be numeric, unless a different value is passed for
  `:type`, (in which case a new type-compatible `:default` value must
  be specified).

  If the named show variable already holds a dynamic parameter at the
  time this variable parameter is created, the binding is
  short-circuited to return that existing parameter rather than
  creating a new one, so the type must be compatible. If `:transform-fn`
  is supplied, it will be called with the value of the variable and
  its return value will be used as the value of the dynamic parameter.
  It must return a compatible type or its result will be discarded."
  [variable & {:keys [frame-dynamic type default transform-fn] :or {frame-dynamic true type Number default 0}}]
  {:pre [(some? *show*)]}
  (validate-param-type default type)
  (let [key (keyword variable)
        current (get @(:variables *show*) key)]
    (if (and (some? current) (param? current))
      ;; Found a parameter at the named variable, try to bind now.
      (do (validate-param-type current type key)
          current)  ; Binding succeeded; return underlying parameter
      ;; Did not find parameter, defer binding via variable
      (let [eval-fn (fn [show] (if (nil? transform-fn)
                                 (let [candidate (get @(:variables show) key default)]
                                   (try
                                     (validate-param-type candidate type)
                                     candidate
                                     (catch Throwable t
                                       (error (str "Unable to use value of variable " key ", value " candidate
                                                   " is not of type " type ". Using default " default))
                                       default)))
                                 (let [candidate (get @(:variables show) key default)]
                                   (try
                                     (validate-param-type candidate type)
                                     (let [transformed (transform-fn candidate)]
                                       (try
                                         (validate-param-type transformed type)
                                         transformed
                                         (catch Throwable t
                                           (error (str "Unable to use transform-fn result for variable " variable
                                                       ", value " transformed " is not of type " type
                                                       ". Using untransformed value " candidate))
                                           candidate)))
                                     (catch Throwable t
                                       (error (str "Unable to use value of variable " key ", value " candidate
                                                   " is not of type " type ". Using default " default))
                                       default)))))]
        (reify IParam
          (evaluate [this show _ _]
            (eval-fn show))
          (frame-dynamic? [this]
            frame-dynamic)
          (result-type [this]
            type)
          (resolve-non-frame-dynamic-elements [this _ _ _]  ; Nothing to resolve, return self
            this))))))

(defn bind-keyword-param*
  "Helper function that does the work of the [[bind-keyword-param]]
  macro, which passes it the name of the parameter being bound for
  nice error messages."
  [param type-expected default param-name]
  (when (some? param)
    (if (keyword? param)
      (build-variable-param param :type type-expected :default default)
      ;; No keyword to bind to, just validate
      (validate-param-type param type-expected param-name))))

(defmacro bind-keyword-param
  "If an input to a dynamic parameter has been passed as a keyword,
  treat that as a reference to a variable in [[*show*]]. If that variable
  currently holds a dynamic parameter, try to bind it directly (throw
  an exception if the types do not match). Otherwise, build a new
  variable param to bind to future values of that show variable, and
  return that, logging a warning if the current value (if any) of the
  show variable is of an incompatible type for the parameter being
  bound. If the input parameter is not a keyword, simply validate its
  type."
  ([value type-expected default]
   (let [arg value]
     `(bind-keyword-param* ~value ~type-expected ~default ~(str arg))))
  ([value type-expected default param-name]
   `(bind-keyword-param* ~value ~type-expected ~default ~param-name)))


(defn- ensure-valid-interval
  "Make sure the desired interval is one of the valid keywords,
  `:beat`, `:bar`, or `:phrase`. If not, log a warning and return
  `:beat` as the value to use instead."
  [interval]
  (or (#{:beat :bar :phrase} interval)
      (do (timbre/warn "Unrecognized interval keyword" interval "using :beat instead") :beat)))

(defn- ensure-valid-fade-curve
  "Make sure the desired fade curve is one of the valid keywords,
  `:linear` or `:sine`. If not, log a warning and return `:linear` as
  the value to use instead."
  [curve]
  (or (#{:linear :sine} curve)
      (do (timbre/warn "Unrecognized fade curve keyword" curve "using :linear instead") :linear)))

(defn- fixed-step-param
  "Build an optimized version of a step parameter which can be used
  when none of its configuration parameters are dynamic parameters.
  See [[build-step-param]] for details about the parameters."
  [interval interval-ratio fade-fraction fade-curve starting]
  (let [interval (ensure-valid-interval interval)
        fade-fraction (colors/clamp-unit-float fade-fraction)
        fade-curve (ensure-valid-fade-curve fade-curve)
        phase-key (keyword (str (name interval) "-phase"))
        origin (dec (+ (interval starting) (math/round (phase-key starting))))
        step-fn (if (= interval-ratio 1) ; Calculate base step level to be faded
                    (fn [_ marker] marker) ; Simple, no beat ratio
                    (fn [snapshot marker]
                      (let [phase (phase-key snapshot)
                            phased-marker (if (util/float= phase 0.0) marker (+ marker phase))]
                        (inc (Math/floor (/ (dec phased-marker) interval-ratio))))))
        phase-fn (if (= interval-ratio 1) ; Calculate phase of current step being faded
                   (fn [snapshot _] (phase-key snapshot)) ; Simple, no beat ratio
                   (fn [snapshot marker] (rhythm/enhanced-phase marker (phase-key snapshot) interval-ratio)))
        fade-in (/ fade-fraction 2) ; Phase at which we are done fading in
        fade-out (- 1 (/ fade-fraction 2)) ; Phase at which we start fading out
        eval-fn (cond
                  (util/float= fade-fraction 0)
                  step-fn ; No fading required, use the basic step function

                  (and (util/float= fade-fraction 1) (= fade-curve :linear))
                  (fn [snapshot marker] ; Fade linearly through entire step
                    (let [step (step-fn snapshot marker)]
                      (+ (- step 0.5) (phase-fn snapshot marker))))

                  :else
                  (case fade-curve
                    :linear (fn [snapshot marker]
                              (let [step (step-fn snapshot marker)
                                    phase (phase-fn snapshot marker)]
                                (cond
                                  (< phase fade-in) (- step (* 0.5 (/ (- fade-in phase) fade-in)))
                                  (> phase fade-out) (+ step (* 0.5 (/ (- phase fade-out) fade-in)))
                                  :else step)))
                    :sine (fn [snapshot marker]
                            (let [step (step-fn snapshot marker)
                                  phase (phase-fn snapshot marker)]
                              (cond
                                (< phase fade-in)
                                (let [fade-phase (/ phase fade-in)]
                                  (+ step (/ (dec (Math/cos (* Math/PI (+ (/ fade-phase 2) 1.5)))) 2)))

                                (> phase fade-out)
                                (let [fade-phase (/ (- phase fade-out) fade-in)]
                                  (+ step (/ (inc (Math/cos (* Math/PI (inc (/ fade-phase 2))))) 2)))

                                :else
                                step)))))]
    (reify IParam
      (evaluate [this _ snapshot _]
        (let [marker (- (interval snapshot) origin)]
          (eval-fn snapshot marker)))
      (frame-dynamic? [this]
        true)
      (result-type [this]
        Number)
      (resolve-non-frame-dynamic-elements [this _ _ _]  ; Nothing to resolve, always frame-dynamic
        this))))

(defn build-step-param
  "Returns a number parameter that increases over time, ideal for
  convenient control
  of [chases]({{guide-url}}effects.html#chases).

  With no arguments, the parameter will evaluate to one at the beat
  closest when it was created, and will increase by one for each beat
  that passes, with no fades (fractional states).

  The optional keyword argument `:interval` can be supplied with the
  value `:bar` or `:phrase` to change the timing so that it instead
  relates to bars or phrases. Supplying a value with `:interval-ratio`
  will run the step parameter at the specified fraction or multiple of
  the chosen interval (beat, bar, or phrase), as illustrated in
  the [Ratios]({{guide-url}}oscillators.html#ratios)
  section of the Oscillator documentation.

  If fading between steps is desired, the optional keyword argument
  `:fade-fraction` can be supplied with a non-zero value (up to but no
  greater than `1`). This specifies what fraction of each interval is
  involved in fading to or from the next value.

  > See the graphs in the Step Parameters
  > [documentation]({{guide-url}}parameters.html#step-parameters)
  > for a visual illustration of how these parameters work.

  A fade fraction of `0` provides the default behavior of an instant
  jump between values with no fading. A fade fraction of `1` means
  that each value continually fades into the next, and is never
  steady. A fade fraction of `0.5` would mean that the value is stable
  half the time, and fading for the other half: during the middle of
  the interval, the value is steady at its assigned value; once the
  final quarter of the interval begins, the value starts fading up,
  reaching the halfway point as the interval ends, and the fade
  continues through the first quarter of the next interval, finally
  stabilizing for the next middle section. Smaller fade fractions mean
  shorter periods of stability and slower fades, while larger
  fractions yield longer periods of steady values, and quicker fades.

  A smoother look can be obtained by using a sine curve to smooth the
  start and end of each fade, by passing the optional keyword argument
  `:fade-curve` with the value `:sine`. (Again, see
  the [graphs]({{guide-url}}parameters.html#step-parameters)
  to get a visual feel for what this does.)

  If the timing should start at an instant other than when the step
  parameter was created, a metronome snapshot containing the desired
  start point can be passed with the optional keyword argument
  `:starting`.

  All incoming parameters may
  be [dynamic]({{guide-url}}parameters.html).
  Step parameters are always frame-dynamic."
  [& {:keys [interval interval-ratio fade-fraction fade-curve starting]
      :or {interval :beat interval-ratio 1 fade-fraction 0 fade-curve :linear
           starting (when *show* (metro-snapshot (:metronome *show*)))}}]
  {:pre [(some? *show*)]}
  (validate-param-type interval clojure.lang.Keyword)
  (validate-param-type fade-curve clojure.lang.Keyword)
  (let [interval-ratio (bind-keyword-param interval-ratio Number 1)
        fade-fraction (bind-keyword-param fade-fraction Number 0)
        starting (bind-keyword-param starting MetronomeSnapshot (when *show* (metro-snapshot (:metronome *show*))))]
    (if (not-any? param? [interval interval-ratio fade-fraction fade-curve starting])
      (fixed-step-param interval interval-ratio fade-fraction fade-curve starting)  ; No dynamic params, can optimize
      (reify IParam  ; Must build dynamic version
        (evaluate [this show snapshot head]
          ;; Build and delegate to a one-time-use fixed step parameter based on the current values of our parameters
          (let [interval (resolve-param interval show snapshot head)
                interval-ratio (resolve-param interval-ratio show snapshot head)
                fade-fraction (resolve-param fade-fraction show snapshot head)
                fade-curve (resolve-param fade-curve show snapshot head)
                starting (resolve-param starting show snapshot head)
                current-version (fixed-step-param interval interval-ratio fade-fraction fade-curve starting)]
            (evaluate current-version show snapshot head)))
        (frame-dynamic? [this]
          true)
        (result-type [this]
          Number)
        (resolve-non-frame-dynamic-elements [this show snapshot head]
          (if (not-any? frame-dynamic? [interval interval-ratio fade-fraction fade-curve starting])
            ;; None of our arguments are frame-dynamic, so we can now optimize to a fixed step parameter
            (let [interval (resolve-param interval show snapshot head)
                  interval-ratio (resolve-param interval-ratio show snapshot head)
                  fade-fraction (resolve-param fade-fraction show snapshot head)
                  fade-curve (resolve-param fade-curve show snapshot head)
                  starting (resolve-param starting show snapshot head)]
              (fixed-step-param interval interval-ratio fade-fraction fade-curve starting))
            this))))))  ; We have frame dynamic inputs, so we need to stay fully dynamic

(defn interpret-color
  "Accept a color as either
  a [jolby/colors](https://github.com/jolby/colors) object,
  an [[IParam]] which will produce a color, a keyword, which will be
  bound to a show variable by the caller, or a string which is passed
  to the jolby/colors `create-color` function."
  [color]
  (cond (string? color)
        (colors/create-color color)

        (keyword? color)
        color

        (= (type color) :com.evocomputing.colors/color)
        color

        (satisfies? IParam color)
        color

        :else
        (throw (IllegalArgumentException. (str "Unable to interpret color parameter:" color)))))

  (def ^:private default-color "The default color for build-color-param."
    (colors/create-color [0 0 0]))

(defn build-color-param
  "Returns a dynamic color parameter. If supplied, `:color` is passed
  to [[interpret-color]] to establish the base color to which other
  arguments are applied. The default base color is black, in the form
  of all zero values for `r`, `g`, `b`, `h`, `s`, and `l`. To this
  base it will then assign values passed in for individual color
  parameters.

  All incoming parameter values may be literal or dynamic, and may be
  keywords, which will be dynamically bound to variables
  in [[*show*]].

  Not all parameter combinations make sense, of course: you will
  probably want to stick with either some of `:h`, `:s`, and `:l`, or
  some of `:r`, `:g`, and `:b`. If values from both are supplied, the
  `:r`, `:g`, and/or `:b` assignments will occur first, then then any
  `:h`, `:s`, and `:l` assignments will be applied to the resulting
  color.

  Finally, if any adjustment values have been supplied for hue,
  saturation or lightness, they will be added to the corresponding
  values (rotating around the hue circle, clamped to the legal range
  for the others).

  If you do not specify an explicit value for `:frame-dynamic`, this
  color parameter will be frame dynamic if it has any incoming
  parameters which themselves are."
  [& {:keys [color r g b h s l adjust-hue adjust-saturation adjust-lightness frame-dynamic]
      :or {color default-color frame-dynamic :default}}]
  {:pre [(some? *show*)]}
  (let [c (bind-keyword-param (interpret-color color) :com.evocomputing.colors/color default-color "color")
        r (bind-keyword-param r Number 0)
        g (bind-keyword-param g Number 0)
        b (bind-keyword-param b Number 0)
        h (bind-keyword-param h Number 0)
        s (bind-keyword-param s Number 0)
        l (bind-keyword-param l Number 0)
        adjust-hue (bind-keyword-param adjust-hue Number 0)
        adjust-saturation (bind-keyword-param adjust-saturation Number 0)
        adjust-lightness (bind-keyword-param adjust-lightness Number 0)]
    (if (not-any? param? [c r g b h s l adjust-hue adjust-saturation adjust-lightness])
      ;; Optimize the degenerate case of all constant parameters
      (let [result-color (atom c)]
        (if (seq (filter identity [r g b]))
          (let [red (when r (colors/clamp-rgb-int (math/round r)))
                green (when g (colors/clamp-rgb-int (math/round g)))
                blue (when b (colors/clamp-rgb-int (math/round b)))]
            (swap! result-color #(colors/create-color {:r (or red (colors/red %))
                                                       :g (or green (colors/green %))
                                                       :b (or blue (colors/blue %))
                                                       :a (colors/alpha %)}))))
        (if (seq (filter identity [h s l]))
          (let [hue (when h (colors/clamp-hue (double h)))
                saturation (when s (colors/clamp-percent-float (double s)))
                lightness (when l (colors/clamp-percent-float (double l)))]
            (swap! result-color #(colors/create-color {:h (or hue (colors/hue %))
                                                       :s (or saturation (colors/saturation %))
                                                       :l (or lightness (colors/lightness %))}))))
        (when adjust-hue
          (swap! result-color #(colors/adjust-hue % (double adjust-hue))))
        (when adjust-saturation
          (swap! result-color #(colors/saturate % (double adjust-saturation))))
        (when adjust-lightness
          (swap! result-color #(colors/lighten % (double adjust-lightness))))
        @result-color)
      ;; Handle the general case of some dynamic parameters
      (let [dyn (if (= :default frame-dynamic)
                  ;; Default means incoming args control how dynamic we should be
                  (boolean (some frame-dynamic-param?
                                 [color r g b h s l adjust-hue adjust-saturation adjust-lightness]))
                  ;; We were given an explicit value for frame-dynamic
                  (boolean frame-dynamic))
            eval-fn (fn [show snapshot head]
                      (let [result-color (atom (resolve-param c show snapshot head))]
                        (if (seq (filter identity [r g b]))
                          (let [red (when r (colors/clamp-rgb-int
                                             (math/round (resolve-param r show snapshot head))))
                                green (when g (colors/clamp-rgb-int
                                               (math/round (resolve-param g show snapshot head))))
                                blue (when b ((colors/clamp-rgb-int
                                               (math/round (resolve-param b show snapshot head)))))]
                            (swap! result-color #(colors/create-color {:r (or red (colors/red %))
                                                                       :g (or green (colors/green %))
                                                                       :b (or blue (colors/blue %))
                                                                       :a (colors/alpha %)}))))
                        (if (seq (filter identity [h s l]))
                          (let [hue (when h (colors/clamp-hue (double (resolve-param h show snapshot head))))
                                saturation (when s (colors/clamp-percent-float
                                                    (double (resolve-param s show snapshot head))))
                                lightness (when l (colors/clamp-percent-float
                                                   (double (resolve-param l show snapshot head))))]
                            (swap! result-color #(colors/create-color {:h (or hue (colors/hue %))
                                                                       :s (or saturation (colors/saturation %))
                                                                       :l (or lightness (colors/lightness %))}))))
                                (when adjust-hue
                                  (swap! result-color
                                         #(colors/adjust-hue % (double (resolve-param adjust-hue show snapshot head)))))
                                (when adjust-saturation
                                  (swap! result-color #(colors/saturate % (double (resolve-param adjust-saturation
                                                                                                 show snapshot head)))))
                                (when adjust-lightness
                                  (swap! result-color
                                         #(colors/lighten % (double (resolve-param
                                                                     adjust-lightness show snapshot head)))))
                                @result-color))
            resolve-fn (fn [show snapshot head]
                         (with-show show
                           (build-color-param :color (resolve-unless-frame-dynamic c show snapshot head)
                                              :r (resolve-unless-frame-dynamic r show snapshot head)
                                              :g (resolve-unless-frame-dynamic g show snapshot head)
                                              :b (resolve-unless-frame-dynamic b show snapshot head)
                                              :h (resolve-unless-frame-dynamic h show snapshot head)
                                              :s (resolve-unless-frame-dynamic s show snapshot head)
                                              :l (resolve-unless-frame-dynamic l show snapshot head)
                                              :adjust-hue (resolve-unless-frame-dynamic adjust-hue show snapshot head)
                                              :adjust-saturation (resolve-unless-frame-dynamic
                                                                  adjust-saturation show snapshot head)
                                              :adjust-lightness (resolve-unless-frame-dynamic
                                                                 adjust-lightness show snapshot head)
                                              :frame-dynamic dyn)))]
        (reify IParam
          (evaluate [this show snapshot head]
            (eval-fn show snapshot head))
          (frame-dynamic? [this]
            dyn)
          (result-type [this]
            :com.evocomputing.colors/color)
          (resolve-non-frame-dynamic-elements [this show snapshot head]
            (resolve-fn show snapshot head)))))))

(defn build-direction-param
  "Returns a dynamic direction parameter for use
  with [[direction-effect]]. If no arguments are supplied, returns a
  static direction facing directly out towards the audience. Keywords
  `:x`, `:y`, and `:z` can be used to specify a vector in the [frame
  of reference]({{guide-url}}show_space.html) of the light show.

  All incoming parameter values may be literal or dynamic, and may be
  keywords, which will be dynamically bound to variables
  in [[*show*]].

  If you do not specify an explicit value for `:frame-dynamic`, this
  direction parameter will be frame dynamic if it has any incoming
  parameters which themselves are."
  [& {:keys [x y z frame-dynamic] :or {x 0 y 0 z 1 frame-dynamic :default}}]
  {:pre [(some? *show*)]}
  (let [x (bind-keyword-param x Number 0)
        y (bind-keyword-param y Number 0)
        z (bind-keyword-param z Number 1)]
    (if (not-any? param? [x y z])
      ;; Optimize the degenerate case of all constant parameters
      (Vector3d. x y z)
      ;; Handle the general case of some dynamic parameters
      (let [dyn (if (= :default frame-dynamic)
                  ;; Default means incoming args control how dynamic we should be
                  (boolean (some frame-dynamic-param? [x y z]))
                  ;; We were given an explicit value for frame-dynamic
                  (boolean frame-dynamic))
            eval-fn (fn [show snapshot head]
                      (Vector3d. (resolve-param x show snapshot head)
                                 (resolve-param y show snapshot head)
                                 (resolve-param z show snapshot head)))
            resolve-fn (fn [show snapshot head]
                         (with-show show
                           (build-direction-param :x (resolve-unless-frame-dynamic x show snapshot head)
                                                  :y (resolve-unless-frame-dynamic y show snapshot head)
                                                  :z (resolve-unless-frame-dynamic z show snapshot head)
                                                  :frame-dynamic dyn)))]
        (reify IParam
          (evaluate [this show snapshot head]
            (eval-fn show snapshot head))
          (frame-dynamic? [this]
            dyn)
          (result-type [this]
            Vector3d)
          (resolve-non-frame-dynamic-elements [this show snapshot head]
            (resolve-fn show snapshot head)))))))

(defn build-direction-transformer
  "Returns a dynamic direction parameter for use
  with [[direction-effect]] which evaluates an incoming direction
  parameter and transforms it according to a `Transform3D`
  parameter.

  All incoming parameter values may be literal or dynamic, and may be
  keywords, which will be dynamically bound to variables
  in [[*show*]].

  If you do not specify an explicit value for `:frame-dynamic`, this
  direction parameter will be frame dynamic if it has any incoming
  parameters which themselves are."
  [direction transform & {:keys [frame-dynamic] :or {frame-dynamic :default}}]
  {:pre [(some? *show*)]}
  (let [direction (bind-keyword-param direction Vector3d (Vector3d. 0 0 1))
        transform (bind-keyword-param transform Transform3D (Transform3D.))]
    (let [dyn (if (= :default frame-dynamic)
                ;; Default means incoming args control how dynamic we should be
                (boolean (some frame-dynamic-param? [direction transform]))
                ;; We were given an explicit value for frame-dynamic
                (boolean frame-dynamic))
          eval-fn (fn [show snapshot head]
                    (let [result (Vector3d. (resolve-param direction show snapshot head))]
                      (.transform (resolve-param transform show snapshot head) result)
                      result))
          resolve-fn (fn [show snapshot head]
                       (with-show show
                         (build-direction-transformer (resolve-unless-frame-dynamic direction show snapshot head)
                                                      (resolve-unless-frame-dynamic transform show snapshot head)
                                                      :frame-dynamic dyn)))]
      (reify IParam
        (evaluate [this show snapshot head]
          (eval-fn show snapshot head))
        (frame-dynamic? [this]
          dyn)
        (result-type [this]
          Vector3d)
        (resolve-non-frame-dynamic-elements [this show snapshot head]
          (resolve-fn show snapshot head))))))

(defn- make-radians
  "If an angle was not already radians, convert it from degrees to
  radians."
  [angle radians]
  (if radians
    angle
    (* (/ angle 180) Math/PI)))

(defn vector-from-pan-tilt
  "Convert a pan and tilt value (angles in radians away from facing
  directly out towards the audience) to the corresponding aiming
  vector."
  [pan tilt]
  (let [euler (Vector3d. tilt pan 0)
        rotation (Transform3D.)
        direction (Vector3d. 0.0 0.0 1.0)]
    (.setEuler rotation euler)
    (.transform rotation direction)
    direction))

(defn build-direction-param-from-pan-tilt
  "An alternative to [[build-direction-param]] for cases in which
  angles are more convenient than a vector, but when you still want to
  use a [[direction-effect]], probably because you want to be able to
  fade to or from another direction-effect. (In cases where you don't
  need to do that, it is simpler to use a [[pan-tilt-effect]]
  with [[build-pan-tilt-param]] and actually have the effect work with
  pan and tilt angles, the way most lighting software does.)

  Returns a dynamic direction parameter specified in terms of pan and
  tilt angles away from facing directly out towards the audience. If
  no arguments are supplied, returns a static direction facing
  directly out towards the audience. Keywords `:pan` and `:tilt` can
  be used to specify angles to turn around the Y and X axes
  respectively
  (see [show space]({{guide-url}}show_space.html) for a diagram of
  these axes). For human friendliness, the angles are assumed to be in
  degrees unless keyword `:radians` is supplied with a true value.

  The values passed for `:pan` and `:tilt` may be dynamic, or may be
  keywords, which will be dynamically bound to variables
  in [[*show*]].

  If you do not specify an explicit value for `:frame-dynamic`, the
  resulting direction parameter will be frame dynamic if it has any
  incoming parameters which themselves are."
  [& {:keys [pan tilt radians frame-dynamic] :or {pan 0 tilt 0 frame-dynamic :default}}]
  {:pre [(some? *show*)]}
  (let [pan (bind-keyword-param pan Number 0)
        tilt (bind-keyword-param tilt Number 0)]
    (if (not-any? param? [pan tilt])
      ;; Optimize the degenerate case of all constant parameters
      (vector-from-pan-tilt (make-radians pan radians) (make-radians tilt radians))
      ;; Handle the general case of some dynamic parameters
      (let [dyn (if (= :default frame-dynamic)
                  ;; Default means incoming args control how dynamic we should be
                  (boolean (some frame-dynamic-param? [pan tilt]))
                  ;; We were given an explicit value for frame-dynamic
                  (boolean frame-dynamic))
            eval-fn (fn [show snapshot head]
                      (vector-from-pan-tilt (make-radians (resolve-param pan show snapshot head) radians)
                                            (make-radians (resolve-param tilt show snapshot head) radians)))
            resolve-fn (fn [show snapshot head]
                         (with-show show
                           (build-direction-param-from-pan-tilt
                            :pan (resolve-unless-frame-dynamic pan show snapshot head)
                            :tilt (resolve-unless-frame-dynamic tilt show snapshot head)
                            :radians radians
                            :frame-dynamic dyn)))]
        (reify IParam
          (evaluate [this show snapshot head]
            (eval-fn show snapshot head))
          (frame-dynamic? [this]
            dyn)
          (result-type [this]
            Vector3d)
          (resolve-non-frame-dynamic-elements [this show snapshot head]
            (resolve-fn show snapshot head)))))))

(defn build-pan-tilt-param
  "Returns a dynamic pan/tilt parameter for use with [[pan-tilt-effect]],
  specified in terms of pan and tilt angles away from facing directly
  out towards the audience. If no arguments are supplied, returns a
  static orientation facing directly out towards the audience.
  Keywords `:pan` and `:tilt` can be used to specify angles to turn
  around the Y and X axes respectively
  (see [show space]({{guide-url}}show_space.html) for a diagram of
  these axes). For human friendliness, the angles are assumed to be in
  degrees unless keyword `:radians` is supplied with a true value.

  The values passed for `:pan` and `:tilt` may be dynamic, or may be
  keywords, which will be dynamically bound to variables
  in [[*show*]].

  If you do not specify an explicit value for `:frame-dynamic`, the
  resulting pan/tilt parameter will be frame dynamic if it has any
  incoming parameters which themselves are.

  Note that if you want to be able to fade the effect you are creating
  to or from a [[direction-effect]], you need to create a
  direction-effect rather than a pan-tilt-effect, and you can instead
  use [[build-direction-param-from-pan-tilt]] to set its direction."
  [& {:keys [pan tilt radians frame-dynamic] :or {pan 0 tilt 0 frame-dynamic :default}}]
  {:pre [(some? *show*)]}
  (let [pan (bind-keyword-param pan Number 0)
        tilt (bind-keyword-param tilt Number 0)]
    (if (not-any? param? [pan tilt])
      ;; Optimize the degenerate case of all constant parameters
      (Vector2d. (make-radians pan radians) (make-radians tilt radians))
      ;; Handle the general case of some dynamic parameters
      (let [dyn (if (= :default frame-dynamic)
                  ;; Default means incoming args control how dynamic we should be
                  (boolean (some frame-dynamic-param? [pan tilt]))
                  ;; We were given an explicit value for frame-dynamic
                  (boolean frame-dynamic))
            eval-fn (fn [show snapshot head]
                      (Vector2d. (make-radians (resolve-param pan show snapshot head) radians)
                                 (make-radians (resolve-param tilt show snapshot head) radians)))
            resolve-fn (fn [show snapshot head]
                         (with-show show
                           (build-pan-tilt-param :pan (resolve-unless-frame-dynamic pan show snapshot head)
                                                 :tilt (resolve-unless-frame-dynamic tilt show snapshot head)
                                                 :radians radians
                                                 :frame-dynamic dyn)))]
        (reify IParam
          (evaluate [this show snapshot head]
            (eval-fn show snapshot head))
          (frame-dynamic? [this]
            dyn)
          (result-type [this]
            Vector2d)
          (resolve-non-frame-dynamic-elements [this show snapshot head]
            (resolve-fn show snapshot head)))))))

(defn build-aim-param
  "Returns a dynamic aiming parameter for use with [[aim-effect]].
  If no arguments are supplied, returns a static direction aiming
  towards a spot on the floor two meters towards the audience from the
  center of the light show. Keywords `:x`, `:y`, and `:z` can be used
  to specify a target point in the [frame of
  reference]({{guide-url}}show_space.html)
  of the light show.

  All incoming parameter values may be literal or dynamic, and may be
  keywords, which will be dynamically bound to variables
  in [[*show*]].

  If you do not specify an explicit value for `:frame-dynamic`, this
  aim parameter will be frame dynamic if it has any incoming
  parameters which themselves are."
  [& {:keys [x y z frame-dynamic] :or {x 0 y 0 z 2 frame-dynamic :default}}]
  {:pre [(some? *show*)]}
  (let [x (bind-keyword-param x Number 0)
        y (bind-keyword-param y Number 0)
        z (bind-keyword-param z Number 2)]
    (if (not-any? param? [x y z])
      ;; Optimize the degenerate case of all constant parameters
      (Point3d. x y z)
      ;; Handle the general case of some dynamic parameters
      (let [dyn (if (= :default frame-dynamic)
                  ;; Default means incoming args control how dynamic we should be
                  (boolean (some frame-dynamic-param? [x y z]))
                  ;; We were given an explicit value for frame-dynamic
                  (boolean frame-dynamic))
            eval-fn (fn [show snapshot head]
                      (Point3d. (resolve-param x show snapshot head)
                                (resolve-param y show snapshot head)
                                (resolve-param z show snapshot head)))
            resolve-fn (fn [show snapshot head]
                         (with-show show
                           (build-aim-param :x (resolve-unless-frame-dynamic x show snapshot head)
                                            :y (resolve-unless-frame-dynamic y show snapshot head)
                                            :z (resolve-unless-frame-dynamic z show snapshot head)
                                            :frame-dynamic dyn)))]
        (reify IParam
          (evaluate [this show snapshot head]
            (eval-fn show snapshot head))
          (frame-dynamic? [this]
            dyn)
          (result-type [this]
            Point3d)
          (resolve-non-frame-dynamic-elements [this show snapshot head]
            (resolve-fn show snapshot head)))))))

(defn build-aim-transformer
  "Returns a dynamic aiming parameter for use with [[aim-effect]]
  which evaluates an incoming aiming parameter and transforms it
  according to a `Transform3D` parameter.

  All incoming parameter values may be literal or dynamic, and may be
  keywords, which will be dynamically bound to variables
  in [[*show*]].

  If you do not specify an explicit value for `:frame-dynamic`, this
  aim parameter will be frame dynamic if it has any incoming
  parameters which themselves are."
  [aim transform & {:keys [frame-dynamic] :or {frame-dynamic :default}}]
  {:pre [(some? *show*)]}
  (let [aim (bind-keyword-param aim Point3d (Point3d. 0 0 2))
        transform (bind-keyword-param transform Transform3D (Transform3D.))]
    (let [dyn (if (= :default frame-dynamic)
                ;; Default means incoming args control how dynamic we should be
                (boolean (some frame-dynamic-param? [aim transform]))
                ;; We were given an explicit value for frame-dynamic
                (boolean frame-dynamic))
          eval-fn (fn [show snapshot head]
                    (let [result (Point3d. (resolve-param aim show snapshot head))]
                      (.transform (resolve-param transform show snapshot head) result)
                      result))
          resolve-fn (fn [show snapshot head]
                       (with-show show
                         (build-aim-transformer (resolve-unless-frame-dynamic aim show snapshot head)
                                                (resolve-unless-frame-dynamic transform show snapshot head)
                                                :frame-dynamic dyn)))]
      (reify IParam
        (evaluate [this show snapshot head]
          (eval-fn show snapshot head))
        (frame-dynamic? [this]
          dyn)
        (result-type [this]
          Point3d)
        (resolve-non-frame-dynamic-elements [this show snapshot head]
          (resolve-fn show snapshot head))))))

(defn- scale-spatial-result
  "Map an individual spatial parameter function result into the range
  desired for all results, given the smallest result value, the size
  of the range in which all result values fell, the start of the
  desired output range, and the size of the desired output range."
  [value smallest value-range start target-range]
  (if (zero? value-range)
    (+ start (/ target-range 2))  ; All values are the same, map to middle of range
    (+ start (* target-range (/ (- value smallest) value-range)))))

(defn- build-spatial-eval-fn
  "Create the function which evaluates a dynamic spatial parameter for
  a given point in show time. If any of the parameters are dynamic,
  they must be evaluated each time. Otherwise we can precompute them
  now, for fast lookup as each DMX frame is rendered. If scaling is
  requested, scale the results so they fall in the specified range."
  [results scaling start target-range]
  (if (not-any? param? (vals results))
    ;; Optimize the case of all constant results
    (let [precalculated (if scaling
                          (let [smallest (apply min (vals results))
                                largest (apply max (vals results))
                                value-range (- largest smallest)]
                            (reduce (fn [altered-map [k v]]
                                      (assoc altered-map k (scale-spatial-result
                                                            v smallest value-range start target-range)))
                                    {} results))
                          results)]
      (fn [show snapshot head] (get precalculated (:id head) start))) ; Return min value if no head match

    ;; Handle the general case of some dynamic results
    (fn [show snapshot head]
      (if scaling  ; Need to resolve all heads in order to scale the requested value appropriately
        (let [resolved (reduce (fn [altered-map [k v]]
                                 (assoc altered-map k (resolve-param v show snapshot head)))
                               {} results)
              smallest (apply min (vals resolved))
              largest (apply max (vals resolved))
              value-range (- largest smallest)]
          (scale-spatial-result (get resolved (:id head) smallest) smallest value-range start target-range))
        ;; Not scaling, only need to resolve the parameter for the specific head requested
        (resolve-param (get results (:id head) start) show snapshot head)))))

(defn build-spatial-param
  "Returns a dynamic number parameter related to the physical
  arrangement of the supplied fixtures or heads. First the heads of
  any fixtures passed in `fixtures-or-heads` are included. Then
  function `f` is called for all fixtures or heads, passing in the
  fixture or head. It must return a literal number or dynamic number
  parameter.

  If you pass a value with either `:max` or `:min` as optional keyword
  parameters, this activates result scaling. (If you pass only a
  `:max` value, `:min` defaults to zero; if you pass only `:min`, then
  `:max` defaults to 255. If you pass neither, scaling is not
  performed, and the results from `f` are returned unchanged.)
  With scaling active, when it comes time to evaluate this spatial
  parameter, any dynamic number parameters are evaluated, and the
  resulting numbers are scaled as a group (after evaluating `f` for
  every participating head or fixture) so they fall within the
  range from `:start` to `:end`, inclusive.

  Useful things that `f` can do include calculating the distance of
  the head from some point, either in 3D or along an axis, its angle
  from some line, and so on. These can allow the creation of lighting
  gradients across all or part of a show. Spatial parameters make
  excellent building blocks
  for [color](#var-build-color-param), [direction](#var-build-direction-param)
  and [aim](#var-build-aim-param) parameters, as shown in the [effect
  examples]({{guide-url}}effects.html#spatial-effects).

  If you do not specify an explicit value for `:frame-dynamic`, this
  spatial parameter will be frame dynamic if any values returned by
  `f` are dynamic parameters which themselves are frame dynamic."
  [fixtures-or-heads f & {:keys [min max frame-dynamic] :or {frame-dynamic :default}}]
  {:pre [(some? *show*) (sequential? fixtures-or-heads) (ifn? f)
         (or (nil? min) (number? min)) (or (nil? max) (number? max)) (< (or min 0) (or max 255))]}
  (let [heads (chan/expand-heads fixtures-or-heads)
        results (zipmap (map :id heads)
                        (map #(bind-keyword-param (f %) Number nil "spatial-param function result") heads))
        scaling (or min max)
        min (or min 0)
        max (or max 255)
        target-range (math/abs (- min max))]
    (doseq [v (vals results)] (check-type v Number "spatial-param function result"))
    (let [dyn (if (= :default frame-dynamic)
                ;; Default means results of head function control how dynamic to be
                (boolean (some frame-dynamic-param? (vals results)))
                ;; We were given an explicit value for frame-dynamic-param
                (boolean frame-dynamic))
          eval-fn (build-spatial-eval-fn results scaling min target-range)
          resolve-fn (fn [show snapshot head]
                       (with-show show
                         (let [resolved (reduce (fn [altered-map [k v]]
                                                  (assoc altered-map k (resolve-unless-frame-dynamic
                                                                        v show snapshot head)))
                                                {} results)
                               resolved-eval-fn (build-spatial-eval-fn resolved scaling min target-range)]
                           (reify IParam
                             (evaluate [this show snapshot head]
                               (resolved-eval-fn show snapshot head))
                             (frame-dynamic? [this]
                               dyn)
                             (result-type [this]
                               Number)
                             (resolve-non-frame-dynamic-elements [this _ _ _]
                               this)))))] ; Already resolved
      (reify IParam
        (evaluate [this show snapshot head]
          (eval-fn show snapshot head))
        (frame-dynamic? [this]
          dyn)
        (result-type [this]
          Number)
        (resolve-non-frame-dynamic-elements [this show snapshot head]
          (resolve-fn show snapshot head))))))

(defn build-param-formula
  "A helper function to create a dynamic parameter that involves some
  sort of calculation based on the values of another group of dynamic
  parameters. The result type reported by the resulting parameter will
  be `param-type`.

  Whenever the parameter's value is needed, it will evaluate all of
  the parameters passed as `input-params`, and call `calc-fn` with
  their current values, returning its result, which must have the type
  specified by `param-type`.

  The compound dynamic parameter will be frame dynamic if any of its
  input parameters are."
  [param-type calc-fn & input-params]
  (reify IParam
    (evaluate [this show snapshot head]
      (apply calc-fn (map #(resolve-param % show snapshot head) input-params)))
    (frame-dynamic? [this] (some frame-dynamic-param? input-params))
    (result-type [this]
      param-type)
    (resolve-non-frame-dynamic-elements [this show snapshot head]
      (apply build-param-formula param-type calc-fn
             (map #(resolve-unless-frame-dynamic % show snapshot head) input-params)))))

;; TODO: some kind of random parameter?
