(ns afterglow.effects.params
  "A general mechanism for passing dynamic parameters to effect
  functions and assigners allowing for dynamic values to be computed
  either when an effect creates its assigners, or when the assigners
  are resolving DMX values. Parameters can be calculated based on the
  show metronome snapshot, show variables (which can be bound to OSC
  and MIDI mappings), and other, not-yet-imagined things."
  {:author "James Elliott"}
  (:require [afterglow.rhythm :refer [metro-snapshot]]
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
    "The type of value that will be returned when this parameter is resolved."))

;; TODO add things like MIDIParam, OpenSoundParam? Or more likely those come from ShowVariableParam...

(defmacro validate-param-type
  "Ensure that a parameter satisfies a predicate, or that it satisfies
  IParam and, when evaluated, returns a type that passes that predicate,
  throwing an exception otherwise."
  ([value type-expected]
   (let [arg value]
     `(validate-param-type ~value ~type-expected ~(str arg))))
  ([value type-expected name]
   `(when-not (or (instance? ~type-expected ~value)
                  (and (satisfies? IParam ~value)  (.isAssignableFrom ~type-expected (result-type ~value))))
      (throw (IllegalArgumentException. (str ~name " must be of type " (quote ~type-expected)))))))

(defn resolve-param
  "Takes an argument which may be a raw value, or may be an IParam. If it is
  the latter, evaluates it and returns the resulting number. Otherwise just
  returns the value that was passed in."
  [arg show snapshot]
  (if (satisfies? IParam arg)
    (evaluate arg show snapshot)
    arg))

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

;; TODO Come up with a way to read and write parameters. Some kind of DSL for these builders.
;; Was thinking could do it through defrecord, but that seems not flexible enough. Will also
;; need a DSL for oscillators, of course. And will want to add dynamic oscillators which
;; themselves support this kind of deferred parameters.
(defn build-oscillator-param
  "Returns a number parameter that is driven by an oscillator."
  [osc & {:keys [min max metronome frame-dynamic] :or {min 0 max 255 frame-dynamic :default}}]
  (validate-param-type min Number)
  (validate-param-type max Number)
  (if-not (some (partial satisfies? IParam) [min max])
    ;; Optimize the simple case of all constant parameters
    (let [range (- max min)
          dyn (boolean frame-dynamic)  ; Make it dynamic unless explicitly set false
          eval-fn (if (some? metronome)
                    (fn [show _] (+ min (* range (osc (metro-snapshot metronome)))))
                    (fn [show snapshot] (+ min (* range (osc snapshot)))))]
      (when-not (pos? range)
        (throw (IllegalArgumentException. "min must be less than max")))
      (reify IParam
        (evaluate [this show snapshot] (eval-fn show snapshot))
        (frame-dynamic? [this] dyn)
        (result-type [this] Number)))
    ;; Support the general case where we have an incoming variable parameter
    (let [dyn (if (= :default frame-dynamic)
                (some frame-dynamic-param? [min max])  ; Let the incoming parameter determine how dynamic to be
                (boolean frame-dynamic))
          eval-fn (if (some? metronome)
                    (fn [show snapshot]
                      (oscillator-resolver-internal show snapshot min max osc (metro-snapshot metronome)))
                    (fn [show snapshot]
                      (oscillator-resolver-internal show snapshot min max osc snapshot)))]
      (reify IParam
        (evaluate [this show snapshot] (eval-fn show snapshot))
        (frame-dynamic? [this] dyn)
        (result-type [this] Number)))))

;; TODO implement
(defn build-color-param
  "Returns a dynamic color parameter."
  [])

;; TODO some kind of random parameter?

#_(defprotocol IHeadParam
  "An extension to IParam for parameters that are specific to a given
  head (because they depend on things like its orientation or
  location). Upon reflection, this is probably unnecessary, because
  that is what head assigners are for. Leaving commented out for now."
  (evaluate-for-head [this show snapshot head]
    "Determine the value of this numeric parameter at a given moment
    of the show, as applied to the specific fixture head."))



