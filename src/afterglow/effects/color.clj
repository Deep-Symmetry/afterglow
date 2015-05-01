(ns afterglow.effects.color
  "Effects pipeline functions for working with color assignments to fixtures and heads."
  {:author "James Elliott"}
  (:require [afterglow.channels :as channels]
            [afterglow.effects.channel :as chan-fx :refer [apply-channel-value]]
            [afterglow.effects.util :refer :all]
            [clojure.math.numeric-tower :as math]
            [taoensso.timbre.profiling :refer [pspy]])
  (:import (afterglow.effects.util Assigner Effect)))

(defn build-color-assigner
  "Returns an assigner which applies the specified assignment function to the supplied head or fixture."
  [head f]
  (Assigner. :color (keyword (str "i" (:id head))) head f))

(defn build-color-assigners
  "Returns a list of assigners which apply a color assignment function to all the supplied heads or fixtures."
  [heads f]
  (map #(build-color-assigner % f) heads))

;; TODO support different kinds of color mixing, blending, HTP...
;; TODO someday support color wheels too, optionally, with a tolerance level
;; Then can combine with a conditional dimmer setting if a color was assigned.
(defn color-cue
  "Returns an effect which simply assigns a fixed color to all heads of the fixtures supplied when invoked."
  [name c fixtures]
  (let [heads (filter #(= 3 (count (filter #{:red :green :blue} (map :color (:channels %))))) (channels/expand-heads fixtures))
        assigners (build-color-assigners heads (fn [show snapshot target previous-assignment] c))]
    (Effect. name always-active (fn [show snapshot] assigners) end-immediately)))

(defn hue-oscillator
  "Returns an effect function which sets the hue to all heads of the fixtures supplied according to
  a supplied oscillator function and the show metronome. Unless otherwise specified, via :min and :max,
  the hue ranges from 0 to 359. Saturation defaults to 100 and lightness to 50, but these can be set
  via :saturation and :lightness."
  [osc fixtures & {:keys [min max saturation lightness] :or {min 0 max 359 saturation 100 lightness 50}}]
  (validate-value saturation 0 100 "saturation")
  (validate-value lightness 0 100 "lightness")
  (when-not (< min max)
    (throw (IllegalArgumentException. "min must be less than max")))
  (let [range (long (- max min))
        heads (filter #(= 3 (count (filter #{:red :green :blue} (map :color (:channels %))))) (channels/expand-heads fixtures))
        f (fn [show snapshot target previous-assignment]
            (pspy :hue-oscillator
                  (let [phase (osc snapshot)
                        new-hue (+ min (* range phase))]
                    (com.evocomputing.colors/create-color {:h new-hue :s saturation :l lightness}))))
        assigners (build-color-assigners heads f)]
    (Effect. "Hue Oscillator" always-active (fn [show snapshot] assigners) end-immediately)))

;; TODO handle color wheels and/or other color channels
(defn color-assignment-resolver
  "Resolves the assignmnet of a color to a fixture or a head."
  [show buffers target assignment]
  (doseq [c (filter #(= (:color %) :red) (:channels target))]
    (apply-channel-value buffers c (com.evocomputing.colors/red assignment)))
  (doseq [c (filter #(= (:color %) :green) (:channels target))]
    (apply-channel-value buffers c (com.evocomputing.colors/green assignment)))
  (doseq [c (filter #(= (:color %) :blue) (:channels target))]
    (apply-channel-value buffers c (com.evocomputing.colors/blue assignment)))
  ;; Expermental: Does this work well in bringing in the white channel?
  (when-let [whites (filter #(= (:color %) :white) (:channels target))]
    (let [l (/ (com.evocomputing.colors/lightness assignment) 100)
          s (/ (com.evocomputing.colors/saturation assignment) 100)
          s-scale (* 2 (- 0.5 (math/abs (- 0.5 l))))
          level (* 255 l (- 1 (* s s-scale)))]
      (doseq [c whites]
        (apply-channel-value buffers c level))))
  ;; Even more experimental: Support other arbitrary color channels
  (doseq [c (filter :hue (:channels target))]
    (let [as-if-red (com.evocomputing.colors/adjust-hue assignment (- (:hue c)))]
      (apply-channel-value buffers c (com.evocomputing.colors/red as-if-red)))))
