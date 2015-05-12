(ns afterglow.effects.params
  "A general mechanism for passing dynamic parameters to effect
  functions and assigners allowing for dynamic values to be computed
  either when an effect creates its assigners, or when the assigners
  are resolving DMX values. Parameters can be calculated based on the
  show metronome snapshot, show variables (which can be bound to OSC
  and MIDI mappings), and other, not-yet-imagined things."
  {:author "James Elliott"}
  (:require [afterglow.rhythm :refer [metro-snapshot]]
            [clojure.math.numeric-tower :as math]
            [com.evocomputing.colors :as colors]
            [taoensso.timbre :refer [error]]))

(defprotocol IParam
  "A dynamic parameter which gets evaluated during the run of a light show,
  with access to the show and its metronome snapshot."
  (evaluate [this show snapshot]
    "Determine the value of this parameter at a given moment of the show.")
  (frame-dynamic? [this]
    "If true, this parameter varies at every frame of the show, and
    must be invoked by effect assigners for each frame of DMX data
    generated. If false, the value can be determined at the time an
    effect is created, and passed as a primitive to the assigners.")
  (result-type [this]
    "The type of value that will be returned when this parameter is resolved.")
  (resolve-non-frame-dynamic-elements [this show snapshot]
    "Called when an effect is created using this parameter. If it is
    not frame-dynamic, returns its final resolution; otherwise,
    returns a version of itself where any non frame-dynamic input
    parameters have been resolved."))

;; TODO add things like MIDIParam, OpenSoundParam? Or more likely those come from ShowVariableParam...

(defn check-type
  "Ensure that a parameter satisfies a predicate, or that it satisfies
  IParam and, when evaluated, returns a type that passes that
  predicate, throwing an exception otherwise. Used by the
  validate-param-type macros to do the actual type checking."
  [value type-expected name]
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
  IParam and, when evaluated, returns a type that passes that predicate,
  throwing an exception otherwise."
  ([value type-expected]
   (let [arg value]
     `(check-type ~value ~type-expected ~(str arg))))
  ([value type-expected name]
   `(check-type ~value ~type-expected ~name)))

(defmacro validate-optional-param-type
  "Ensure that a parameter satisfies a predicate, or that it satisfies
  IParam and, when evaluated, returns a type that passes that predicate,
  throwing an exception otherwise."
  ([value type-expected]
   (let [arg value]
     `(validate-optional-param-type ~value ~type-expected ~(str arg))))
  ([value type-expected name]
   `(when (some? ~value) (check-type ~value ~type-expected ~name))))

(defprotocol IHeadParam
  "An extension to IParam for parameters that are specific to a given
  head (because they depend on things like its orientation or
  location)."
  (evaluate-for-head [this show snapshot head]
    "Determine the value of this numeric parameter at a given moment
    of the show, as applied to the specific fixture head.")
    (resolve-non-frame-dynamic-elements-for-head [this show snapshot head]
    "Called when an effect is created using this parameter and there
    is head information available. If the parameter is not
    frame-dynamic, returns its final resolution; otherwise, returns a
    version of itself where any non frame-dynamic input parameters
    have been resolved."))

(defn head-param?
  "Checks whether the argument is an IParam which also satisfies IHeadParam"
  [arg]
  (and (satisfies? IParam arg) (satisfies? IHeadParam arg)))

(defn resolve-param
  "Takes an argument which may be a raw value, or may be an IParam. If
  it is the latter, evaluates it and returns the resulting number.
  Otherwise just returns the value that was passed in. If head is
  supplied, and the parameter can use it at resolution time, then pass
  it along."
  ([arg show snapshot]
   (resolve-param arg show snapshot nil))
  ([arg show snapshot head]
   (if (satisfies? IParam arg)
     (if (and (some? head) (satisfies? IHeadParam arg))
       (evaluate-for-head arg show snapshot head)
       (evaluate arg show snapshot))
     arg)))

(defn frame-dynamic-param?
  "Checks whether the argument is an IParam which is dynamic to the frame level."
  [arg]
  (and (satisfies? IParam arg) (frame-dynamic? arg)))

(defn- oscillator-resolver-internal
  "Handles the calculation of an oscillator based on dynamic parameter
  values for at least one of min and max"
  [show params-snapshot min-arg max-arg osc osc-snapshot]
  (let [min (resolve-param min-arg show params-snapshot)
        max (resolve-param max-arg show params-snapshot)
        range (- max min)]
    (if (pos? range)
      (+ min (* range (osc osc-snapshot)))
      (do
        (error "Oscillator dynamic parameters min > max, returning max.")
        max))))

(defn resolve-unless-frame-dynamic
  "If the first argument is a dynamic parameter which is not dynamic
  all the way to the frame level, return the result of resolving it
  now. If it is dynamic to the frame level, ask it to resolve any non
  frame-dynamic elements. Otherwise return it unchanged. If head is
  supplied, and the parameter can use it at resolution time, then pass
  it along."
  ([arg show snapshot]
   (resolve-unless-frame-dynamic arg show snapshot nil))
  ([arg show snapshot head]
   (if (satisfies? IParam arg)
     (if-not (frame-dynamic? arg)
       (resolve-param arg show snapshot head)
       (if (satisfies? IHeadParam arg)
         (resolve-non-frame-dynamic-elements-for-head arg show snapshot head)
         (resolve-non-frame-dynamic-elements arg show snapshot)))
     arg)))

(defn build-variable-param
  "Create a dynamic parameter whose value is determined by the value
  held in a show variable at the time evaluation is performed. Unless
  :frame-dynamic is passed a false value, this evaluation will happen
  every frame. If no type-compatible value is found in the show
  variable, a default value is returned. That will be the number zero,
  unless otherwise specified by :default. The type expected (and
  returned) by this parameter will be numeric, unless a different
  value is passed for :type, (in which case a new type-compatible
  default value must be specified :default). If the show variable
  already holds a dyamic parameter at the time this variable parameter
  is created, the binding is short-circuited to return that existing
  parameter rather than creating a new one, so the type must be
  compatible."
  [show variable & {:keys [frame-dynamic type default] :or {frame-dynamic true type Number default 0}}]
  (validate-param-type default type)
  (let [key (keyword variable)
        current (get @(:variables show) key)]
    (if (and (some? current) (satisfies? IParam current))
      ;; Found a parameter at the named variable, try to bind now.
      (do (validate-param-type current type key)
          current)  ; Binding succeeded; return underlying parameter
      ;; Did not find parameter, defer binding via variable
      (reify IParam
        (evaluate [this show snapshot] (let [candidate (get @(:variables show) key default)]
                                         (try
                                           (validate-param-type candidate type)
                                           candidate
                                           (catch Throwable t
                                             (error (str "Unable to use value of variable " key ", value " candidate
                                                         " is not of type " type ". Using default " default))
                                             default))))
        (frame-dynamic? [this] frame-dynamic)
        (result-type [this] type)
        (resolve-non-frame-dynamic-elements [this show snapshot]  ; Nothing to resolve, return self
          this)))))

(defn- bind-keyword-param-internal
  "If an input to a dynamic parameter has been passed as a keyword,
  treat that as a reference to a show variable. If that variable
  currently holds a dynamic parameter, try to bind it directly (throw
  an exception if the types do not match). Otherwise, build a new
  variable param to bind to future values of that show variable, and
  return that, logging a warning if the current value (if any) of the
  show variable is of an incompatible type for the parameter being
  bound. If the input parameter is not a keyword, simply validate its
  type."
  [param show type-expected default param-name]
  (when (some? param)
    (if (keyword? param)
      (build-variable-param show param :type type-expected :default default)
      ;; No keyword to bind to, just validate
      (validate-param-type param type-expected param-name))))

(defmacro bind-keyword-param
  "If an input to a dynamic parameter has been passed as a keyword,
  treat that as a reference to a show variable. If the input parameter
  is not a keyword, simply validate its type."
  ([value show type-expected default]
   (let [arg value]
     `(bind-keyword-param-internal ~value ~show ~type-expected ~default ~(str arg))))
  ([value show type-expected default param-name]
   `(bind-keyword-param-internal ~value ~show ~type-expected ~default ~param-name)))

;; TODO Come up with a way to read and write parameters. Some kind of DSL for these builders.
;; Was thinking could do it through defrecord, but that seems not flexible enough. Will also
;; need a DSL for oscillators, of course. And will want to add dynamic oscillators which
;; themselves support this kind of deferred parameters. Maybe a string format for oscillator
;; specifications? Actually, leaning towards a build-oscillator-param which resolves to an
;; oscillator, so make that a protocol too, and have the builder take the name and osc
;; parameters, which can all be dynamic. Make a metronome param too, so they can come out
;; of the show variables.
(defn build-oscillated-param
  "Returns a number parameter that is driven by an oscillator. By
  default will be frame-dynamic, since it oscillates, but if you make
  frame-dynamic false, the value will be fixed once it is assigned to
  an effect, acting like a random number generator with the
  oscillator's range."
  [show osc & {:keys [min max metronome frame-dynamic] :or {min 0 max 255 frame-dynamic true}}]
  (let [min (bind-keyword-param min show Number 0)
        max (bind-keyword-param max show Number 255)]
    (if-not (some (partial satisfies? IParam) [min max])
      ;; Optimize the simple case of all constant parameters
      (let [range (- max min)
            dyn (boolean frame-dynamic)
            eval-fn (if (some? metronome)
                      (fn [show _] (+ min (* range (osc (metro-snapshot metronome)))))
                      (fn [show snapshot] (+ min (* range (osc snapshot)))))]
        (when-not (pos? range)
          (throw (IllegalArgumentException. "min must be less than max")))
        (reify IParam
          (evaluate [this show snapshot] (eval-fn show snapshot))
          (frame-dynamic? [this] dyn)
          (result-type [this] Number)
          (resolve-non-frame-dynamic-elements [this show snapshot]  ; Nothing to resolve, return self
            this)))
      ;; Support the general case where we have an incoming variable parameter
      (let [dyn (boolean frame-dynamic)
            eval-fn (if (some? metronome)
                      (fn [show snapshot]
                        (oscillator-resolver-internal show snapshot min max osc (metro-snapshot metronome)))
                      (fn [show snapshot]
                        (oscillator-resolver-internal show snapshot min max osc snapshot)))]
        (reify IParam
          (evaluate [this show snapshot] (eval-fn show snapshot))
          (frame-dynamic? [this] dyn)
          (result-type [this] Number)
          (resolve-non-frame-dynamic-elements [this show snapshot]
            (build-oscillated-param osc :min (resolve-unless-frame-dynamic min show snapshot)
                                    :max (resolve-unless-frame-dynamic max show snapshot)
                                    :metronome metronome :frame-dynamic dyn)))))))

;; TODO metronome parameters, with access to the show metronome and other metronome variables


(defn interpret-color
  "Accept a color as either a jolby/colors object, an IParam which will produce a color,
  a keyword, which will be bound to a show variable by the caller, or a string which is
  passed to the jolby/colors create-color function."
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
        (throw (IllegalArgumentException. (str "Unable to interpret color parameter")))))

(def ^:private default-color (colors/create-color [0 0 0]))

;; Someday it may be desirable to add parameters hue-delta,
;; saturation-delta, lightness-delta, for adding to the base
;; color values, and hue-scale, saturation-scale, and
;; lightness-scale, for multiplying by the base values. For now,
;; leaving these out, because they can perhaps be achieved in
;; the setup of oscillators and/or controller mappings.
(defn build-color-param
  "Returns a dynamic color parameter. If supplied, color establishes
  the base color to which other arguments are applied. The default
  base color is black, in the form of all zero values for r, g, b, h,
  s, and l. To this base it will then assign values for individual
  color parameters. Not all combinations make sense, of course: you
  will probably want to stick with some of h, s, and l, or r, g, and
  b. If values from both are supplied, the r, g, and/or b assignments
  will occur first, then then any h, s, and l assignments will be
  applied to the resulting color. If you do not specify a value for
  frame-dynamic, the color parameter will be frame dynamic if it has
  any incoming parameters which are."
  [show & {:keys [color r g b h s l frame-dynamic]
      :or {color default-color frame-dynamic :default}}]
  (let [c (bind-keyword-param (interpret-color color) show :com.evocomputing.colors/color default-color "color")
        r (bind-keyword-param r show Number 0)
        g (bind-keyword-param g show Number 0)
        b (bind-keyword-param b show Number 0)
        h (bind-keyword-param h show Number 0)
        s (bind-keyword-param s show Number 0)
        l (bind-keyword-param l show Number 0)]
    (if-not (some (partial satisfies? IParam) [c r g b h s l])
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
          (let [hue (when h (colors/clamp-hue (float h)))
                saturation (when s (colors/clamp-percent-float (float s)))
                lightness (when l (colors/clamp-percent-float (float l)))]
            (swap! result-color #(colors/create-color {:h (or hue (colors/hue %))
                                                       :s (or saturation (colors/saturation %))
                                                       :l (or lightness (colors/lightness %))}))))
        @result-color)
      ;; Handle the general case of some dynamic parameters
      (let [dyn (if (= :default frame-dynamic)
                  (boolean (some frame-dynamic-param? [color r g b h s l])) ; Let incoming args control how dynamic to be
                  (boolean frame-dynamic))
            eval-fn (fn [show snapshot head]
                      (let [result-color (atom c)]
                        (if (seq (filter identity [r g b]))
                          (let [red (when r (colors/clamp-rgb-int (math/round (resolve-param r show snapshot head))))
                                green (when g (colors/clamp-rgb-int (math/round (resolve-param g show snapshot head))))
                                blue (when b ((colors/clamp-rgb-int (math/round (resolve-param b show snapshot head)))))]
                            (swap! result-color #(colors/create-color {:r (or red (colors/red %))
                                                                       :g (or green (colors/green %))
                                                                       :b (or blue (colors/blue %))
                                                                       :a (colors/alpha %)}))))
                        (if (seq (filter identity [h s l]))
                          (let [hue (when h (colors/clamp-hue (float (resolve-param h show snapshot head))))
                                saturation (when s (colors/clamp-percent-float (float (resolve-param s show snapshot head))))
                                lightness (when l (colors/clamp-percent-float (float (resolve-param l show snapshot head))))]
                            (swap! result-color #(colors/create-color {:h (or hue (colors/hue %))
                                                                       :s (or saturation (colors/saturation %))
                                                                       :l (or lightness (colors/lightness %))}))))
                        @result-color))
            resolve-fn (fn [show snapshot head]
                         (build-color-param show
                                            :color (resolve-unless-frame-dynamic c show snapshot head)
                                            :r (resolve-unless-frame-dynamic r show snapshot head)
                                            :g (resolve-unless-frame-dynamic g show snapshot head)
                                            :b (resolve-unless-frame-dynamic b show snapshot head)
                                            :h (resolve-unless-frame-dynamic h show snapshot head)
                                            :s (resolve-unless-frame-dynamic s show snapshot head)
                                            :l (resolve-unless-frame-dynamic l show snapshot head)
                                            :frame-dynamic dyn))]
        (reify
          IParam
          (evaluate [this show snapshot] (eval-fn show snapshot nil))
          (frame-dynamic? [this] dyn)
          (result-type [this] :com.evocomputing.colors/color)
          (resolve-non-frame-dynamic-elements [this show snapshot] (resolve-fn show snapshot nil))
          IHeadParam
          (evaluate-for-head [this show snapshot head] (eval-fn show snapshot head))
          (resolve-non-frame-dynamic-elements-for-head [this show snapshot head] (resolve-fn show snapshot head)))))))

;; TODO some kind of random parameter?





