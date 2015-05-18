(ns afterglow.effects.color
  "Effects pipeline functions for working with color assignments to fixtures and heads."
  {:author "James Elliott"}
  (:require [afterglow.channels :as channels]
            [afterglow.effects :refer [always-active end-immediately]]
            [afterglow.effects.channel :refer [apply-channel-value]]
            [afterglow.effects.params :as params]
            [afterglow.rhythm :as rhythm]
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

(defn build-color-assigner
  "Returns an assigner which applies the specified assignment function
  to the supplied head or fixture."
  [head f]
  (Assigner. :color (keyword (str "i" (:id head))) head f))

(defn build-color-assigners
  "Returns a list of assigners which apply a fixed color assignment
  function to all the supplied heads or fixtures."
  [heads f]
  (map #(build-color-assigner % f) heads))

(defn build-color-parameter-assigner
  "Returns an assigner which applies a color parameter to the supplied
  head or fixture. If the parameter is not frame-dynamic, it gets
  resolved when creating this assigner. Otherwise, resolution is
  deferred to frame rendering time."
  [head param show snapshot]
  (let [resolved (params/resolve-unless-frame-dynamic param show snapshot head)]
    (build-color-assigner head (fn [show snapshot target previous-assignment] resolved))))

(defn build-color-parameter-assigners
  "Returns a list of assigners which apply a color parameter to all
  the supplied heads or fixtures."
  [heads param show]
  (let [snapshot (rhythm/metro-snapshot (:metronome show))]
    (map #(build-color-parameter-assigner % param show snapshot) heads)))

;; TODO support different kinds of color mixing, blending, HTP...
;; TODO someday support color wheels too, optionally, with a tolerance level
;; Then can combine with a conditional dimmer setting if a color was assigned.
(defn color-cue
  "Returns an effect which assigns a color parameter to all heads of the fixtures supplied when invoked."
  [name color show fixtures]
  (params/validate-param-type color :com.evocomputing.colors/color)
  (let [heads (filter #(= 3 (count (filter #{:red :green :blue} (map :color (:channels %))))) (channels/expand-heads fixtures))
        assigners (build-color-parameter-assigners heads color show)]
    (Effect. name always-active (fn [show snapshot] assigners) end-immediately)))

(defn find-rgb-heads
  "Returns all heads of the supplied fixtures which are capable of
  mixing RGB color, in other words they have at least a red, green,
  and blue color channel."
  [fixtures]
  (filter #(= 3 (count (filter #{:red :green :blue} (map :color (:channels %))))) (channels/expand-heads fixtures)))

;; Deprecated in favor of new composable dynamic parameter mechanism
(defn hue-oscillator
  "*Deprecated* Returns an effect function which sets the hue to all
  heads of the fixtures supplied according to a supplied oscillator
  function and the show metronome. Unless otherwise specified,
  via :min and :max, the hue ranges from 0 to 359. Saturation defaults
  to 100 and lightness to 50, but these can be set via :saturation
  and :lightness."
  [osc fixtures & {:keys [min max saturation lightness] :or {min 0 max 359 saturation 100 lightness 50}}]
  {:pre [(<= 0 saturation 100) (<= 0 lightness 100) (< min max) (seq? fixtures) (ifn? osc)]}
  (let [range (long (- max min))
        heads (find-rgb-heads fixtures)
        f (fn [show snapshot target previous-assignment]
            (pspy :hue-oscillator
                  (let [phase (osc snapshot)
                        new-hue (+ min (* range phase))]
                    (colors/create-color {:h new-hue :s saturation :l lightness}))))
        assigners (build-color-assigners heads f)]
    (Effect. "Hue Oscillator" always-active (fn [show snapshot] assigners) end-immediately)))

;; TODO handle color wheels
(defn color-assignment-resolver
  "Resolves the assignmnet of a color to a fixture or a head."
  [show buffers snapshot target assignment]
  (let [resolved (params/resolve-param assignment show snapshot target)]  ; In case it is frame dynamic
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
        (apply-channel-value buffers c (colors/red as-if-red))))))
