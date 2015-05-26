(ns afterglow.effects.color
  "Effects pipeline functions for working with color assignments to
  fixtures and heads."
  {:author "James Elliott"}
  (:require [afterglow.channels :as channels]
            [afterglow.effects :refer :all]
            [afterglow.effects.channel :refer [apply-channel-value
                                               function-percentage-to-dmx]]
            [afterglow.effects.params :as params]
            [afterglow.rhythm :as rhythm]
            [afterglow.show-context :refer [*show*]]
            [afterglow.util :as util]
            [clojure.math.numeric-tower :as math]
            [com.evocomputing.colors :as colors]
            [taoensso.timbre.profiling :refer [pspy]])
  (:import (afterglow.effects Assigner Effect)))

(defn htp-merge
  "Helper function for assigners that want to use
  highest-takes-priority blending for RGB colors. Returns a color
  that contains the highest red component from the two input colors,
  the highest green component, and the highest blue component."
  [previous current]
  (if (some? previous)
    (let [red (max (colors/red previous) (colors/red current))
          green (max (colors/green previous) (colors/green current))
          blue (max (colors/blue previous) (colors/blue current))]
      (colors/create-color :r red :g green :b blue))
    current))

(defn find-rgb-heads
  "Returns all heads of the supplied fixtures which are capable of
  mixing RGB color, in other words they have at least a red, green,
  and blue color channel."
  ([fixtures]
   (find-rgb-heads fixtures false))
  ([fixtures include-color-wheels]
   (filter #(or (= 3 (count (filter #{:red :green :blue} (map :color (:channels %)))))
                (and include-color-wheels (seq (:color-wheel-hue-map %))))
           (channels/expand-heads fixtures))
   ))

;; TODO: Support different kinds of color mixing, blending, HTP...
;; TODO: Someday support color wheels too, optionally, with a tolerance level
;;       Then can combine with a conditional dimmer setting if a color was assigned.
(defn color-cue
  "Returns an effect which assigns a color parameter to all heads of
  the fixtures supplied when invoked."
  [name color fixtures & {:keys [include-color-wheels]}]
  {:pre [(some? *show*) (some? name) (sequential? fixtures)]}
  (params/validate-param-type color :com.evocomputing.colors/color)
  (let [heads (find-rgb-heads fixtures include-color-wheels)
        assigners (build-head-parameter-assigners :color heads color *show*)]
    (Effect. name always-active (fn [show snapshot] assigners) end-immediately)))

;; Deprecated in favor of new composable dynamic parameter mechanism
(defn hue-oscillator
  "*Deprecated* Returns an effect function which sets the hue to all
  heads of the fixtures supplied according to a supplied oscillator
  function and the show metronome. Unless otherwise specified,
  via :min and :max, the hue ranges from 0 to 359. Saturation defaults
  to 100 and lightness to 50, but these can be set via :saturation
  and :lightness."
  {:deprecated true}
  [osc fixtures & {:keys [min max saturation lightness] :or {min 0 max 359 saturation 100 lightness 50}}]
  {:pre [(<= 0 saturation 100) (<= 0 lightness 100) (< min max) (sequential? fixtures) (ifn? osc)]}
  (let [range (long (- max min))
        heads (find-rgb-heads fixtures)
        f (fn [show snapshot target previous-assignment]
            (pspy :hue-oscillator
                  (let [phase (osc snapshot)
                        new-hue (+ min (* range phase))]
                    (colors/create-color {:h new-hue :s saturation :l lightness}))))
        assigners (build-head-assigners :color heads f)]
    (Effect. "Hue Oscillator" always-active (fn [show snapshot] assigners) end-immediately)))

(defn color-assignment-resolver
  "Resolves the assignment of a color to a fixture or a head."
  [show buffers snapshot target assignment _]
  (let [resolved (params/resolve-param assignment show snapshot target)]  ; In case it is frame dynamic
    ;; Start with RGB mixing
    (doseq [c (filter #(= (:color %) :red) (:channels target))]
      (apply-channel-value buffers c (colors/red resolved)))
    (doseq [c (filter #(= (:color %) :green) (:channels target))]
      (apply-channel-value buffers c (colors/green resolved)))
    (doseq [c (filter #(= (:color %) :blue) (:channels target))]
      (apply-channel-value buffers c (colors/blue resolved)))
    ;; Expermental: Does this work well in bringing in the white channel?
    (when-let [whites (filter #(= (:color %) :white) (:channels target))]
      (let [l (/ (colors/lightness resolved) 100)
            s (/ (colors/saturation resolved) 100)
            s-scale (* 2 (- 0.5 (math/abs (- 0.5 l))))
            level (* 255 l (- 1 (* s s-scale)))]
        (doseq [c whites]
          (apply-channel-value buffers c level))))
    ;; Even more experimental: Support other arbitrary color channels
    (doseq [c (filter :hue (:channels target))]
      (let [as-if-red (colors/adjust-hue resolved (- (:hue c)))]
        (apply-channel-value buffers c (colors/red as-if-red))))
    ;; Finally, see if there is a color wheel color close enough to select
    (when (seq (:color-wheel-hue-map target))
      (let [found (util/find-closest-key (:color-wheel-hue-map target) (colors/hue resolved))
            [channel function-spec] (get (:color-wheel-hue-map target) found)]
        ;; TODO: make color tolerance configurable, figure out null pointer exceptions
        (when (< (math/abs (- (colors/hue resolved) found)) 30)
          (apply-channel-value buffers channel (function-percentage-to-dmx 50 function-spec)))))))
