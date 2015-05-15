(ns afterglow.effects.fun
  "A collection of neat effects that are both useful in shows, and
  examples of how to create such things."
  {:author "James Elliott"}
  (:require [afterglow.effects.color :refer [build-color-assigners
                                             find-rgb-heads]]
            [afterglow.effects.util]
            [afterglow.rhythm :refer [snapshot-bar-phase
                                      snapshot-beat-phase
                                      snapshot-down-beat?]]
            [com.evocomputing.colors :as colors]
            [taoensso.timbre.profiling :refer [pspy]])
  (:import (afterglow.effects.util Effect)))

(def default-down-beat-color
  "The default color to flash on the down beats."
  (colors/lighten (colors/create-color :red) 20))

(def default-other-beat-color
  "The default color to flash on beats that are not down beats."
  (colors/darken (colors/create-color :yellow) 30))

;; TODO take a metronome parameter and support dynamic parameters!
(defn metronome-cue
  "Returns an effect function which flashes the supplied fixtures to
  the beats of the show metronome, emphasizing the down beat, which is
  a great way to test and understand metronome synchronization. The
  color of the flashes can be controlled by the :down-beat-color
  and :other-beat-color arguments (defaulting to red with lightness
  70, and yellow with lightness 20, respectively)."
  [show fixtures & {:keys [down-beat-color other-beat-color]
                    :or {down-beat-color default-down-beat-color
                         other-beat-color default-other-beat-color}}]
  (let [heads (find-rgb-heads fixtures)
        running (atom true)
        f (fn [show snapshot target previous-assignment]
            (pspy :metronome-cue
                  (let [raw-intensity (* 2 (- (/ 1 2) (snapshot-beat-phase snapshot 1)))
                        intensity (if (neg? raw-intensity) 0 raw-intensity)
                        base-color (if (snapshot-down-beat? snapshot) down-beat-color other-beat-color)]
                    (colors/create-color {:h (colors/hue base-color)
                                          :s (colors/saturation base-color)
                                          :l (* (colors/lightness base-color) intensity)}))))
        assigners (build-color-assigners heads f)]
    (Effect. "Metronome"
             (fn [snow snapshot]  ;; Continue running until the end of a measure
               (or @running (< (snapshot-bar-phase snapshot) 0.9)))
             (fn [show snapshot] assigners)
             (fn [snow snapshot]  ;; Arrange to shut down at the end of a measure
               (reset! running false)))))

;; TODO add off-beat-penalty that slopes the chance downwards as the beat passes,
;; same for off-bar-penalty, so can prioritize beats and bars, perhaps pass an oscillator
;; so they can be scaled in time too. Eventually allow randomization of dwell and perhaps
;; hue and peak brightness, with control over how much they vary?
(defn sparkle
  "A random sparkling effect like a particle generator over the supplied fixture heads."
  [fixtures & {:keys [color chance dwell]}]
  )

