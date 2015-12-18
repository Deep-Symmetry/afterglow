(ns afterglow.effects.color
  "Effects pipeline functions for working with color assignments to
  fixtures and heads."
  {:author "James Elliott"}
  (:require [afterglow.channels :as channels]
            [afterglow.effects :as fx]
            [afterglow.effects.channel :as chan-fx]
            [afterglow.effects.oscillators :as osc]
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
  and blue color channel. If the second argument is present and
  `true`, also returns heads with color wheels."
  ([fixtures]
   (find-rgb-heads fixtures false))
  ([fixtures include-color-wheels?]
   (filter #(or (= 3 (count (filter #{:red :green :blue} (map :color (:channels %)))))
                (and include-color-wheels? (seq (:color-wheel-hue-map %))))
           (channels/expand-heads fixtures))))

(defn has-rgb-heads?
  "Given a fixture, returns a truthy value if it has any heads capable
  of mixing RGB color. If the second argument is present and `true`,
  having a head with a color wheel is good enough."
  ([fixture]
   (has-rgb-heads? fixture false))
  ([fixture include-color-wheels?]
   (seq (find-rgb-heads [fixture] include-color-wheels?))))

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

(defn color-effect
  "Returns an effect which assigns a color parameter to all heads of
  the fixtures supplied when invoked. If `:include-color-wheels?` is
  passed with a `true` value, then fixtures which use color wheels are
  included, otherwise only color-mixing fixtures are included. If
  `:htp?` is passed with a `true` value, highest-takes-precedence
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

;;; Multimethod implementations to support color effects

;; Resolves the assignment of a color to a fixture or a head,
;; performing color mixing with any color component channels found in
;; the target head. If color wheel heads were included in the cue, will
;; find the closest matching hue on the wheel, as long as it is within
;; tolerance. The default tolerance is 60 degrees around the hue wheel,
;; which is very lenient. If you want to tighten that up, you can set a
;; lower value in the show variable :color-wheel-hue-tolerance
(defmethod fx/resolve-assignment :color [assignment show snapshot buffers]
  ;; Resolve in case assignment is still frame dynamic
  (let [target (:target assignment)
        resolved (params/resolve-param (:value assignment) show snapshot target)
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
            s-scale (if (< l 0.5) 1.0 (- 1.0 (* 2.0 (- l 0.5))))
            level (* 255.0 l (- 1.0 (* s s-scale)))]
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

(def ^:private default-color
  "The color to mix with when fading from a non-assignment."
  (colors/create-color :black))

(defn- blackened-color
  "Determine the color to fade to when one side of a fade is nil;
  return the fully darkened version of the other color in the fade, if
  there is one, or a default black if both were nil."
  [color]
  (if color (colors/darken color 100.0) default-color))

(defn fade-colors
  "Calculate a weighted HSL blend between two colors, which may be
  dynamic parameters, and where nil is considered to be a fully
  darkened version of the other side of the fade."
  [from to fraction show snapshot target]
  ;; Resolve any remaining dynamic parameters now, and make sure fraction really
  ;; does only range between 0 and 1, then convert it to the percentage wanted by
  ;; the colors library.
  (let [from (params/resolve-param from show snapshot target)
        to (params/resolve-param to show snapshot target)
        weight (* 100 (colors/clamp-unit-float fraction))]
    ;; Weight goes in the opposite direction you might expect, so the following order works:
    (colors/mix-hsl (or to (blackened-color from)) (or from (blackened-color to)) weight)))

;; Fades between two color assignments to a fixture or head.
(defmethod fx/fade-between-assignments :color [from-assignment to-assignment fraction show snapshot]
  (cond (<= fraction 0) from-assignment
        (>= fraction 1) to-assignment
        :else (merge from-assignment {:value (fade-colors (:value from-assignment) (:value to-assignment) fraction
                                                          show snapshot (:target from-assignment))})))

;;; Effects which transform other color effects

(defn build-saturation-transformation
  "Creates a color transformation for use with [[transform-colors]]
  which changes the saturation based on a variable parameter. If no
  parameter is supplied, the default is to use an oscillated parameter
  based on [[sawtooth-beat]] with `:down?` set to `true` so the color
  is fully saturated at the start of the beat, and fully desaturated
  by the end. A different pattern can be created by supplying a
  different parameter with the `:param` optional keyword argument."
  [& {:keys [param] :or {param (osc/build-oscillated-param (osc/sawtooth-beat :down? true) :max 100)}}]
  (fn [color show snapshot head]
    (let [saturation (colors/clamp-percent-float (params/resolve-param param show snapshot head))]
      (colors/create-color {:h (colors/hue color) :s saturation :l (colors/lightness color)}))))

(defn transform-colors
  "Creates an effect which modifies any effect that is currently
  assigning a color to the supplied fixtures. Needs to be assigned a
  higher priority than any effects it should transform, so that it
  will run after them. The actual transformation is implemented by a
  function which takes a color, show, snapshot, and head, and returns
  a transformed color. This function is specified with the
  `:transform-fn` optional keyword argument; if none is specified,
  [[build-saturation-transformation]] is called with no arguments to
  create one which causes the saturation of the color to range from
  full at the start of each beat to none at the end.

  If the optional keyword argument `:beyond-server` is passed with a
  Beyond server (as returned by [[beyond-server]]), any color being
  sent to that integrated laser show using [[laser-color-effect]] will
  also be transformed."
  [fixtures & {:keys [transform-fn beyond-server] :or {transform-fn (build-saturation-transformation)
                                                       beyond-server nil}}]
  (let [heads (find-rgb-heads fixtures)
        f (fn [show snapshot target previous-assignment]  ;; Assigners for regular light colors; have heads
            (pspy :transform-colors
                  (when-let [resolved (params/resolve-param previous-assignment show snapshot target)]
                    (transform-fn resolved show snapshot target))))
        lf (when beyond-server
             (fn [show snapshot target previous-assignment]  ;; Assigner for laser show colors; no head
               (pspy :transform-colors
                     (when-let [resolved (params/resolve-param previous-assignment show snapshot)]
                       (transform-fn resolved show snapshot nil)))))
        assigners (concat (fx/build-head-assigners :color heads f)
                          (when beyond-server
                            [(Assigner. :beyond-color (keyword (str "s" (:id beyond-server))) beyond-server lf)]))]
    (Effect. "Transform Colors" fx/always-active (fn [show snapshot] assigners) fx/end-immediately)))

