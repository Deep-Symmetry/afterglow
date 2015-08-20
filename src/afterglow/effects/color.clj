(ns afterglow.effects.color
  "Effects pipeline functions for working with color assignments to
  fixtures and heads."
  {:author "James Elliott"}
  (:require [afterglow.channels :as channels]
            [afterglow.effects :as fx]
            [afterglow.effects.channel :as chan-fx]
            [afterglow.effects.params :as params]
            [afterglow.rhythm :as rhythm]
            [afterglow.show-context :refer [*show*]]
            [afterglow.util :as util]
            [clojure.math.numeric-tower :as math]
            [com.evocomputing.colors :as colors]
            [taoensso.timbre :as timbre]
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
  ([fixtures include-color-wheels?]
   (filter #(or (= 3 (count (filter #{:red :green :blue} (map :color (:channels %)))))
                (and include-color-wheels? (seq (:color-wheel-hue-map %))))
           (channels/expand-heads fixtures))
   ))

(defn build-htp-color-assigner
  "Returns an assigner that applies highest-takes-precedence color
  mixing of a dynamic color parameter to the supplied head or fixture.
  If the parameter is not frame-dynamic, it gets resolved when
  creating this assigner. Otherwise, resolution is deferred to frame
  rendering time. At that time, both the previous assignment and the
  current parameter are resolved, and the red, green, and blue values
  of the color are set to whichever of the previous and current
  assignment held the highest."
  [head param show snapshot]
  (let [resolved (params/resolve-unless-frame-dynamic param show snapshot head)]
    (fx/build-head-assigner :color head
                            (fn [show snapshot target previous-assignment]
                              (if (some? previous-assignment)
                                (let [current (params/resolve-param resolved show snapshot head)
                                      previous (params/resolve-param previous-assignment show snapshot head)]
                                  (htp-merge previous current))
                                resolved)))))

(defn build-htp-color-assigners
  "Returns a list of assigners which apply highest-takes-precedence
  color mixing to all the supplied heads or fixtures."
  [heads param show]
  (let [snapshot (rhythm/metro-snapshot (:metronome show))]
    (map #(build-htp-color-assigner % param show snapshot) heads)))

;; TODO: Support other kinds of color mixing, blending...
(defn color-effect
  "Returns an effect which assigns a color parameter to all heads of
  the fixtures supplied when invoked. If :include-color-wheels? is
  passed with a true value, then fixtures which use color wheels are
  included, otherwise only color-mixing fixtures are included.
  If :htp? is passed with a true value, highest-takes-precedence
  assignment is used with the red, green, and blue color values to
  blend this color with any previous color that might have been
  assigned to the affected fixtures."
  [name color fixtures & {:keys [include-color-wheels? htp?]}]
  {:pre [(some? *show*) (some? name) (sequential? fixtures)]}
  (params/validate-param-type color :com.evocomputing.colors/color)
  (let [heads (find-rgb-heads fixtures include-color-wheels?)
        assigners (if htp?
                    (build-htp-color-assigners heads color *show*)
                    (fx/build-head-parameter-assigners :color heads color *show*))]
    (Effect. name fx/always-active (fn [show snapshot] assigners) fx/end-immediately)))

;; Deprecated in favor of new composable dynamic parameter mechanism
(defn hue-oscillator
  "*Deprecated* Returns an effect which sets the hue to all heads of
  the fixtures supplied according to a supplied oscillator function
  and the show metronome. Unless otherwise specified, via :min
  and :max, the hue ranges from 0 to 359. Saturation defaults to 100
  and lightness to 50, but these can be set via :saturation
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
        assigners (fx/build-head-assigners :color heads f)]
    (Effect. "Hue Oscillator" fx/always-active (fn [show snapshot] assigners) fx/end-immediately)))

(defn color-assignment-resolver
  "Resolves the assignment of a color to a fixture or a head,
  performing color mixing with any color component channels found in
  the target head. If color wheel heads were included in the cue, will
  find the closest matching hue on the wheel, as long as it is within
  tolerance. The default tolerance is 60 degrees around the hue wheel,
  which is very lenient. If you want to tighten that up, you can set a
  lower value in the show variable :color-wheel-hue-tolerance"
  [show buffers snapshot target assignment _]
  (let [resolved (params/resolve-param assignment show snapshot target) ; In case it is frame dynamic
        color-key (keyword (str "color-" (:id target)))]
    ;; Start with RGB mixing
    (doseq [c (filter #(= (:color %) :red) (:channels target))]
      (chan-fx/apply-channel-value buffers c (colors/red resolved)))
    (doseq [c (filter #(= (:color %) :green) (:channels target))]
      (chan-fx/apply-channel-value buffers c (colors/green resolved)))
    (doseq [c (filter #(= (:color %) :blue) (:channels target))]
      (chan-fx/apply-channel-value buffers c (colors/blue resolved)))
    (swap! (:movement *show*) #(assoc-in % [:current color-key] resolved))
    ;; Expermental: Does this work well in bringing in the white channel?
    (when-let [whites (filter #(= (:color %) :white) (:channels target))]
      (let [l (/ (colors/lightness resolved) 100)
            s (/ (colors/saturation resolved) 100)
            s-scale (* 2 (- 0.5 (math/abs (- 0.5 l))))
            level (* 255 l (- 1 (* s s-scale)))]
        (doseq [c whites]
          (chan-fx/apply-channel-value buffers c level))))
    ;; Even more experimental: Support other arbitrary color channels
    (doseq [c (filter :hue (:channels target))]
      (let [as-if-red (colors/adjust-hue resolved (- (:hue c)))]
        (chan-fx/apply-channel-value buffers c (colors/red as-if-red))))
    ;; Finally, see if there is a color wheel color close enough to select
    (when (seq (:color-wheel-hue-map target))
      (let [found (util/find-closest-key (:color-wheel-hue-map target) (colors/hue resolved))
            [channel function-spec] (get (:color-wheel-hue-map target) found)]
        (when (< (math/abs (- (colors/hue resolved) found)) (:color-wheel-hue-tolerance @(:variables show) 60))
          (chan-fx/apply-channel-value buffers channel (chan-fx/function-percentage-to-dmx 50 function-spec)))))))
