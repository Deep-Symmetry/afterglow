(ns afterglow.effects.params
  "A general mechanism for passing dynamic parameters to effect
  functions and assigners allowing for dynamic values to be computed
  either when an effect creates its assigners, or when the assigners
  are resolving DMX values. Parameters can be calculated based on the
  show metronome snapshot, show variables (which can be bound to OSC
  and MIDI mappings), and other, not-yet-imagined things."
  {:author "James Elliott"}
  (:require [afterglow.channels :as chan]
            [afterglow.rhythm :refer [metro-snapshot]]
            [afterglow.show-context :refer [*show* with-show]]
            [afterglow.transform :as transform]
            [clojure.math.numeric-tower :as math]
            [com.evocomputing.colors :as colors]
            [taoensso.timbre :as timbre :refer [error]])
  (:import [afterglow.rhythm Metronome]
           [javax.media.j3d Transform3D]
           [javax.vecmath Point3d Vector3d]))

(defonce
  ^{:private true
    :doc "Protect protocols against namespace reloads"}
  _PROTOCOLS_
  (do
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
    have been resolved."))))


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
  {:doc/format :markdown}
  ([value type-expected]
   (let [arg value]
     `(check-type ~value ~type-expected ~(str arg))))
  ([value type-expected name]
   `(check-type ~value ~type-expected ~name)))

(defmacro validate-optional-param-type
  "Ensure that a parameter, if not `nil`, satisfies a predicate, or that
  it satisfies [[IParam]] and, when evaluated, returns a type that passes
  that predicate, throwing an exception otherwise."
  {:doc/format :markdown}
  ([value type-expected]
   (let [arg value]
     `(validate-optional-param-type ~value ~type-expected ~(str arg))))
  ([value type-expected name]
   `(when (some? ~value) (check-type ~value ~type-expected ~name))))

(defn head-param?
  "Checks whether the argument is an [[IParam]] which also
  satisfies [[IHeadParam]]."
  {:doc/format :markdown}
  [arg]
  (and (satisfies? IParam arg) (satisfies? IHeadParam arg)))

(defn resolve-param
  "Takes an argument which may be a raw value, or may be
  an [[IParam]]. If it is the latter, evaluates it and returns the
  resulting value. Otherwise just returns the value that was passed
  in. If `head` is supplied, and the parameter can use it at
  resolution time, then pass it along."
  {:doc/format :markdown}
  ([arg show snapshot]
   (resolve-param arg show snapshot nil))
  ([arg show snapshot head]
   (if (satisfies? IParam arg)
     (if (and (some? head) (satisfies? IHeadParam arg))
       (evaluate-for-head arg show snapshot head)
       (evaluate arg show snapshot))
     arg)))

(defn frame-dynamic-param?
  "Checks whether the argument is an [[IParam]] which is dynamic to
  the frame level."
  {:doc/format :markdown}
  [arg]
  (and (satisfies? IParam arg) (frame-dynamic? arg)))

(defn- resolve-oscillator
  "Handles the calculation of an oscillator based on dynamic parameter
  values for at least one of min and max."
  [show params-snapshot min max osc osc-snapshot]
  (let [min (resolve-param min show params-snapshot)
        max (resolve-param max show params-snapshot)
        range (- max min)]
    (if (neg? range)
      (do
        (error "Oscillator dynamic parameters min > max, returning max.")
        max)
      (+ min (* range (osc osc-snapshot))))))

(defn resolve-unless-frame-dynamic
  "If the first argument is an [[IParam]] which is not dynamic all the
  way to the frame level, return the result of resolving it now. If it
  is dynamic to the frame level, ask it to resolve any non
  frame-dynamic elements. Otherwise return it unchanged. If `head` is
  supplied, and the parameter can use it at resolution time, then pass
  it along." {:doc/format :markdown}
  ([arg show snapshot]
   (resolve-unless-frame-dynamic arg show snapshot nil))
  ([arg show snapshot head]
   {:pre [(some? show)]}
   (if (satisfies? IParam arg)
     (if-not (frame-dynamic? arg)
       (resolve-param arg show snapshot head)
       (if (satisfies? IHeadParam arg)
         (resolve-non-frame-dynamic-elements-for-head arg show snapshot head)
         (resolve-non-frame-dynamic-elements arg show snapshot)))
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

  If the named show variable already holds a dyamic parameter at the
  time this variable parameter is created, the binding is
  short-circuited to return that existing parameter rather than
  creating a new one, so the type must be compatible. If `:adjust-fn`
  is supplied, it will be called with the value of the variable and
  its return value will be used as the value of the dynamic parameter.
  It must return a compatible type or its result will be discarded."
  {:doc/format :markdown}
  [variable & {:keys [frame-dynamic type default adjust-fn] :or {frame-dynamic true type Number default 0}}]
  {:pre [(some? *show*)]}
  (validate-param-type default type)
  (let [key (keyword variable)
        current (get @(:variables *show*) key)]
    (if (and (some? current) (satisfies? IParam current))
      ;; Found a parameter at the named variable, try to bind now.
      (do (validate-param-type current type key)
          current)  ; Binding succeeded; return underlying parameter
      ;; Did not find parameter, defer binding via variable
      (let [eval-fn (fn [show] (if (nil? adjust-fn)
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
                                     (let [adjusted (adjust-fn candidate)]
                                       (try
                                         (validate-param-type adjusted type)
                                         adjusted
                                         (catch Throwable t
                                           (error (str "Unable to use adjust-fn result for variable " variable
                                                       ", value " adjusted " is not of type " type
                                                       ". Using unadjusted value " candidate))
                                           candidate)))
                                     (catch Throwable t
                                       (error (str "Unable to use value of variable " key ", value " candidate
                                                   " is not of type " type ". Using default " default))
                                       default)))))]
        (reify IParam
          (evaluate [this show snapshot] (eval-fn show))
          (frame-dynamic? [this] frame-dynamic)
          (result-type [this] type)
          (resolve-non-frame-dynamic-elements [this show snapshot]  ; Nothing to resolve, return self
            this))))))

(defn bind-keyword-param*
  "Helper function that does the work of the [[bind-keyword-param]]
  macro, which passes it the name of the parameter being bound for
  nice error messages."
  {:doc/format :markdown}
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
  {:doc/format :markdown}
  ([value type-expected default]
   (let [arg value]
     `(bind-keyword-param* ~value ~type-expected ~default ~(str arg))))
  ([value type-expected default param-name]
   `(bind-keyword-param* ~value ~type-expected ~default ~param-name)))

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
  {:doc/format :markdown}
  [osc & {:keys [min max metronome frame-dynamic] :or {min 0 max 255 frame-dynamic true}}]
  {:pre [(some? *show*) (ifn? osc)]}
  (let [min (bind-keyword-param min Number 0)
        max (bind-keyword-param max Number 255)
        metronome (bind-keyword-param metronome Metronome (:metronome *show*))]
    (if-not (some (partial satisfies? IParam) [min max metronome])
      ;; Optimize the simple case of all constant parameters
      (let [range (- max min)
            dyn (boolean frame-dynamic)
            eval-fn (if (some? metronome)
                      (fn [_ _] (+ min (* range (osc (metro-snapshot metronome)))))
                      (fn [_ snapshot] (+ min (* range (osc snapshot)))))]
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
                        (resolve-oscillator show snapshot min max osc
                                                      (metro-snapshot (resolve-param metronome show snapshot))))
                      (fn [show snapshot]
                        (resolve-oscillator show snapshot min max osc snapshot)))]
        (reify IParam
          (evaluate [this show snapshot] (eval-fn show snapshot))
          (frame-dynamic? [this] dyn)
          (result-type [this] Number)
          (resolve-non-frame-dynamic-elements [this show snapshot]
            (with-show show
              (build-oscillated-param osc :min (resolve-unless-frame-dynamic min show snapshot)
                                      :max (resolve-unless-frame-dynamic max show snapshot)
                                      :metronome metronome :frame-dynamic dyn))))))))

(defn interpret-color
  "Accept a color as either
  a [jolby/colors](https://github.com/jolby/colors) object,
  an [[IParam]] which will produce a color, a keyword, which will be
  bound to a show variable by the caller, or a string which is passed
  to the jolby/colors `create-color` function."
  {:doc/format :markdown}
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
  {:doc/format :markdown}
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
    (if-not (some (partial satisfies? IParam) [c r g b h s l adjust-hue adjust-saturation adjust-lightness])
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
        (when adjust-hue
          (swap! result-color #(colors/adjust-hue % (float adjust-hue))))
        (when adjust-saturation
          (swap! result-color #(colors/saturate % (float adjust-saturation))))
        (when adjust-lightness
          (swap! result-color #(colors/lighten % (float adjust-lightness))))
        @result-color)
      ;; Handle the general case of some dynamic parameters
      (let [dyn (if (= :default frame-dynamic)
                  ;; Default means incoming args control how dynamic we should be
                  (boolean (some frame-dynamic-param?
                                 [color r g b h s l adjust-hue adjust-saturation adjust-lightness]))
                  ;; We were given an explicit value for frame-dynamic
                  (boolean frame-dynamic))
            eval-fn (fn [show snapshot head]
                      (let [result-color (atom c)]
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
                          (let [hue (when h (colors/clamp-hue (float (resolve-param h show snapshot head))))
                                saturation (when s (colors/clamp-percent-float
                                                    (float (resolve-param s show snapshot head))))
                                lightness (when l (colors/clamp-percent-float
                                                   (float (resolve-param l show snapshot head))))]
                            (swap! result-color #(colors/create-color {:h (or hue (colors/hue %))
                                                                       :s (or saturation (colors/saturation %))
                                                                       :l (or lightness (colors/lightness %))}))))
                                (when adjust-hue
                                  (swap! result-color
                                         #(colors/adjust-hue % (float (resolve-param adjust-hue show snapshot head)))))
                                (when adjust-saturation
                                  (swap! result-color #(colors/saturate % (float (resolve-param adjust-saturation
                                                                                   show snapshot head)))))
                                (when adjust-lightness
                                  (swap! result-color
                                         #(colors/lighten % (float (resolve-param
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
        (reify
          IParam
          (evaluate [this show snapshot] (eval-fn show snapshot nil))
          (frame-dynamic? [this] dyn)
          (result-type [this] :com.evocomputing.colors/color)
          (resolve-non-frame-dynamic-elements [this show snapshot] (resolve-fn show snapshot nil))
          IHeadParam
          (evaluate-for-head [this show snapshot head] (eval-fn show snapshot head))
          (resolve-non-frame-dynamic-elements-for-head [this show snapshot head] (resolve-fn show snapshot head)))))))

(defn build-direction-param
  "Returns a dynamic direction parameter. If no arguments are
  supplied, returns a static direction facing directly out towards the
  audience. Keywords `:x`, `:y`, and `:z` can be used to specify a
  vector in the [frame of
  reference](https://github.com/brunchboy/afterglow/blob/master/doc/show_space.adoc#show-space)
  of the light show.

  All incoming parameter values may be literal or dynamic, and may be
  keywords, which will be dynamically bound to variables
  in [[*show*]].

  If you do not specify an explicit value for `:frame-dynamic`, this
  direction parameter will be frame dynamic if it has any incoming
  parameters which themselves are."
  {:doc/format :markdown}
  [& {:keys [x y z frame-dynamic] :or {x 0 y 0 z 1 frame-dynamic :default}}]
  {:pre [(some? *show*)]}
  (let [x (bind-keyword-param x Number 0)
        y (bind-keyword-param y Number 0)
        z (bind-keyword-param z Number 1)]
    (if-not (some (partial satisfies? IParam) [x y z])
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
        (reify
          IParam
          (evaluate [this show snapshot] (eval-fn show snapshot nil))
          (frame-dynamic? [this] dyn)
          (result-type [this] Vector3d)
          (resolve-non-frame-dynamic-elements [this show snapshot] (resolve-fn show snapshot nil))
          IHeadParam
          (evaluate-for-head [this show snapshot head] (eval-fn show snapshot head))
          (resolve-non-frame-dynamic-elements-for-head [this show snapshot head] (resolve-fn show snapshot head)))))))

(defn- make-radians
  "If an angle was not already radians, convert it from degrees to
  radians."
  [angle radians]
  (if radians
    angle
    (* (/ angle 180) Math/PI)))

(defn- vector-from-pan-tilt
  "Convert a pan and tilt value to an aiming vector."
  [pan tilt radians]
  (let [pan (make-radians pan radians)
        tilt (make-radians tilt radians)
        euler (Vector3d. tilt pan 0)
        rotation (Transform3D.)
        direction (Vector3d. 0.0 0.0 1.0)]
    (.setEuler rotation euler)
    (.transform rotation direction)
    direction))

(defn build-direction-param-from-pan-tilt
  "An alternate to [[build-direction-param]] for cases in which angles
  are more convenient than a vector. Returns a dynamic direction
  parameter specified in terms of pan and tilt angles away from facing
  directly out towards the audience. If no arguments are supplied,
  returns a static direction facing directly out towards the audience.
  Keywords `:pan` and `:tilt` can be used to specify angles to turn around the Y and X axes respectively
  (see [show
  space](https://github.com/brunchboy/afterglow/blob/master/doc/show_space.adoc#show-space)
  for a diagram of these axes). For human friendliness, the angles are
  assumed to be in degrees unless keyword `:radians` is supplied with
  a true value.

  The values passed for `:pan` and `:tilt` may be dynamic, or may be
  keywords, which will be dynamically bound to variables
  in [[*show*]].

  If you do not specify an explicit value for `:frame-dynamic`, the
  resulting direction parameter will be frame dynamic if it has any
  incoming parameters which themselves are."
  {:doc/format :markdown}
  [& {:keys [pan tilt radians frame-dynamic] :or {pan 0 tilt 0 frame-dynamic :default}}]
  {:pre [(some? *show*)]}
  (let [pan (bind-keyword-param pan Number 0)
        tilt (bind-keyword-param tilt Number 0)]
    (if-not (some (partial satisfies? IParam) [pan tilt])
      ;; Optimize the degenerate case of all constant parameters
      (vector-from-pan-tilt pan tilt radians)
      ;; Handle the general case of some dynamic parameters
      (let [dyn (if (= :default frame-dynamic)
                  ;; Default means incoming args control how dynamic we should be
                  (boolean (some frame-dynamic-param? [pan tilt]))
                  ;; We were given an explicit value for frame-dynamic
                  (boolean frame-dynamic))
            eval-fn (fn [show snapshot head]
                      (vector-from-pan-tilt (resolve-param pan show snapshot head)
                                            (resolve-param tilt show snapshot head)
                                            radians))
            resolve-fn (fn [show snapshot head]
                         (with-show show
                           (build-direction-param-from-pan-tilt :pan (resolve-unless-frame-dynamic pan show
                                                                                                   snapshot head)
                                                                :tilt (resolve-unless-frame-dynamic tilt show
                                                                                                    snapshot head)
                                                                :radians radians
                                                                :frame-dynamic dyn)))]
        (reify
          IParam
          (evaluate [this show snapshot] (eval-fn show snapshot nil))
          (frame-dynamic? [this] dyn)
          (result-type [this] Vector3d)
          (resolve-non-frame-dynamic-elements [this show snapshot] (resolve-fn show snapshot nil))
          IHeadParam
          (evaluate-for-head [this show snapshot head] (eval-fn show snapshot head))
          (resolve-non-frame-dynamic-elements-for-head [this show snapshot head] (resolve-fn show snapshot head)))))))

(defn build-aim-param
  "Returns a dynamic aiming parameter. If no arguments are supplied,
  returns a static direction aiming towards a spot on the floor two
  meters towards the audience from the center of the light show.
  Keywords `:x`, `:y`, and `:z` can be used to specify a target point
  in the [frame of
  reference](https://github.com/brunchboy/afterglow/blob/master/doc/show_space.adoc#show-space)
  of the light show.

  All incoming parameter values may be literal or dynamic, and may be
  keywords, which will be dynamically bound to variables
  in [[*show*]].

  If you do not specify an explicit value for `:frame-dynamic`, this
  aim parameter will be frame dynamic if it has any incoming
  parameters which themselves are."
  {:doc/format :markdown}
  [& {:keys [x y z frame-dynamic] :or {x 0 y 0 z 2 frame-dynamic :default}}]
  {:pre [(some? *show*)]}
  (let [x (bind-keyword-param x Number 0)
        y (bind-keyword-param y Number 0)
        z (bind-keyword-param z Number 2)]
    (if-not (some (partial satisfies? IParam) [x y z])
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
        (reify
          IParam
          (evaluate [this show snapshot] (eval-fn show snapshot nil))
          (frame-dynamic? [this] dyn)
          (result-type [this] Point3d)
          (resolve-non-frame-dynamic-elements [this show snapshot] (resolve-fn show snapshot nil))
          IHeadParam
          (evaluate-for-head [this show snapshot head] (eval-fn show snapshot head))
          (resolve-non-frame-dynamic-elements-for-head [this show snapshot head]
            (resolve-fn show snapshot head)))))))

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
  (if-not (some (partial satisfies? IParam) (vals results))
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
      (fn [show snapshot head] (get precalculated (:id head))))

    ;; Handle the general case of some dynamic results
    (fn [show snapshot head]
      (if scaling  ; Need to resolve all heads in order to scale the requested value appropriately
        (let [resolved (reduce (fn [altered-map [k v]]
                                 (assoc altered-map k (resolve-param v show snapshot head)))
                               {} results)
              smallest (apply min (vals resolved))
              largest (apply max (vals resolved))
              value-range (- largest smallest)]
          (scale-spatial-result (get resolved (:id head)) smallest value-range start target-range))
        ;; Not scaling, only need to resolve the parameter for the specific head requested
        (resolve-param (get results (:id head)) show snapshot head)))))

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
  range [`:start`-`:end`].

  Useful things that `f` can do include calculating the distance of
  the head from some point, either in 3D or along an axis, its angle
  from some line, and so on. These can allow the creation of lighting
  gradients across all or part of a show. Spatial parameters make
  excellent building blocks
  for [color](#var-build-color-param), [direction](#var-build-direction-param)
  and [aim](#var-build-aim-param) parameters, as shown in the [effect
  examples](https://github.com/brunchboy/afterglow/blob/master/doc/effects.adoc#spatial-effects).

  If you do not specify an explicit value for `:frame-dynamic`, this
  spatial parameter will be frame dynamic if any values returned by
  `f` are dynamic parameters which themselves are frame dynamic."
  {:doc/format :markdown}
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
    (doseq [v (vals results)] check-type v Number "spatial-param function result")
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
                           (reify
                             IParam
                             (evaluate [this show snapshot] min) ; Needs head to do anything
                             (frame-dynamic? [this] dyn)
                             (result-type [this] Number)
                             (resolve-non-frame-dynamic-elements [this show snapshot]
                               this) ; Already resolved
                             IHeadParam
                             (evaluate-for-head [this show snapshot head] (resolved-eval-fn show snapshot head))
                             (resolve-non-frame-dynamic-elements-for-head [this show snapshot head]
                               this)))))] ; Already resolved
      (reify
        IParam
        (evaluate [this show snapshot] min) ; Needs head to do anything
        (frame-dynamic? [this] dyn)
        (result-type [this] Number)
        (resolve-non-frame-dynamic-elements [this show snapshot]
          this) ; Spatial parameters can't be evaluated or resolved with no head.
        IHeadParam
        (evaluate-for-head [this show snapshot head]
          (eval-fn show snapshot head))
        (resolve-non-frame-dynamic-elements-for-head [this show snapshot head]
          (resolve-fn show snapshot head))))))

;; TODO: some kind of random parameter?
