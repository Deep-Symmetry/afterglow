(ns afterglow.effects.fun
  "A collection of neat effects that are both useful in shows, and
  examples of how to create such things."
  {:author "James Elliott"}
  (:require [afterglow.effects :refer [build-head-assigner build-head-assigners]]
            [afterglow.effects.color :refer [htp-merge
                                             find-rgb-heads]]
            [afterglow.effects.params :as params]
            [afterglow.rhythm :as rhythm :refer [snapshot-bar-phase
                                                 snapshot-beat-phase
                                                 snapshot-down-beat?]]
            [afterglow.show-context :refer [*show*]]
            [com.evocomputing.colors :as colors]
            [taoensso.timbre.profiling :refer [pspy]])
  (:import (afterglow.effects Effect)
           (afterglow.rhythm Metronome)))

(def default-down-beat-color
  "The default color to flash on the down beats."
  (colors/lighten (colors/create-color :red) 20))

(def default-other-beat-color
  "The default color to flash on beats that are not down beats."
  (colors/darken (colors/create-color :yellow) 30))

(defn metronome-effect
  "Returns an effect which flashes the supplied fixtures to the beats
  of the show metronome, emphasizing the down beat, which is a great
  way to test and understand metronome synchronization. The color of
  the flashes can be controlled by the :down-beat-color
  and :other-beat-color arguments (defaulting to red with lightness
  70, and yellow with lightness 20, respectively)."
  [fixtures & {:keys [down-beat-color other-beat-color metronome]
               :or {down-beat-color default-down-beat-color
                    other-beat-color default-other-beat-color
                    metronome (:metronome *show*)}}]
  {:pre [(some? *show*)]}
  (let [down-beat-color (params/bind-keyword-param down-beat-color :com.evocomputing.colors/color default-down-beat-color)
        other-beat-color (params/bind-keyword-param other-beat-color :com.evocomputing.colors/color default-other-beat-color)
        metronome (params/bind-keyword-param metronome Metronome (:metronome *show*))]
    (params/validate-param-type down-beat-color :com.evocomputing.colors/color)
    (params/validate-param-type other-beat-color :com.evocomputing.colors/color)
    (params/validate-param-type metronome Metronome)
    (let [heads (find-rgb-heads fixtures)
          running (atom true)
          ;; Need to use the show metronome as a snapshot to resolve our metronome parameter first
          metronome (params/resolve-param metronome *show* (rhythm/metro-snapshot (:metronome *show*)))
          snapshot (rhythm/metro-snapshot metronome)
          down-beat-color (params/resolve-unless-frame-dynamic down-beat-color *show* snapshot)
          other-beat-color (params/resolve-unless-frame-dynamic other-beat-color *show* snapshot)
          local-snapshot (atom nil)  ; Need to set up a snapshot at start of each run for all assigners
          f (fn [show snapshot target previous-assignment]
              (pspy :metronome-effect
                    (let [raw-intensity (* 2 (- (/ 1 2) (snapshot-beat-phase @local-snapshot 1)))
                          intensity (if (neg? raw-intensity) 0 raw-intensity)
                          base-color (if (snapshot-down-beat? @local-snapshot)
                                       (params/resolve-param down-beat-color show @local-snapshot)
                                       (params/resolve-param other-beat-color show @local-snapshot))]
                      (colors/create-color {:h (colors/hue base-color)
                                            :s (colors/saturation base-color)
                                            :l (* (colors/lightness base-color) intensity)}))))
          assigners (build-head-assigners :color heads f)]
      (Effect. "Metronome"
               (fn [snow snapshot]  ;; Continue running until the end of a measure
                 ;; Also need to set up the local snapshot based on our private metronome
                 ;; for the assigners to use.
                 (reset! local-snapshot (rhythm/metro-snapshot metronome))
                 (or @running (< (snapshot-bar-phase @local-snapshot) 0.9)))
               (fn [show snapshot] assigners)
               (fn [snow snapshot]  ;; Arrange to shut down at the end of a measure
                 (reset! running false))))))

(def default-sparkle-color
  "The default color for the sparkle effect."
  (colors/create-color "white"))

(defn- remove-finished-sparkles
  "Filters out any sparkles that were created longer ago than the fade time.
  sparkles is a map from head to the timestamp at which the sparkle was created."
  [sparkles show snapshot fade-time]
  (pspy :remove-finished-sparkles
        (let [now (:instant snapshot)]
          (reduce
           (fn [result [head creation-time]]
             (let [fade-time (params/resolve-param fade-time show snapshot head)]
               (if (< (- now creation-time) fade-time)
                 (assoc result head creation-time)
                 result)))
           {}
           sparkles))))

;; TODO: add off-beat-penalty that slopes the chance downwards as the beat passes,
;; same for off-bar-penalty, so can prioritize beats and bars, perhaps pass an oscillator
;; so they can be scaled in time too. Eventually allow randomization of fade time and perhaps
;; hue and peak brightness, with control over how much they vary?
(defn sparkle
  "A random sparkling effect like a particle generator over the supplied fixture heads."
  [fixtures & {:keys [color chance fade-time] :or {color default-sparkle-color chance 0.001 fade-time 500}}]
  {:pre [(some? *show*)]}
  (let [color (params/bind-keyword-param color :com.evocomputing.colors/color default-sparkle-color)
        chance (params/bind-keyword-param chance Number 0.001)
        fade-time (params/bind-keyword-param fade-time Number 500)]
    (params/validate-param-type color :com.evocomputing.colors/color)
    (params/validate-param-type chance Number)
    (params/validate-param-type fade-time Number)
    (let [heads (find-rgb-heads fixtures)
          running (atom true)
          sparkles (atom {})  ; Currently a map from head ID to creation timestamp for active sparkles
          snapshot (rhythm/metro-snapshot (:metronome *show*))
          color (params/resolve-unless-frame-dynamic color *show* snapshot)
          chance (params/resolve-unless-frame-dynamic chance *show* snapshot)
          fade-time (params/resolve-unless-frame-dynamic fade-time *show* snapshot)]
      (Effect. "Sparkle"
              (fn [show snapshot]
                ;; Continue running until all existing sparkles fade
                (swap! sparkles remove-finished-sparkles show snapshot fade-time)
                (or @running (seq @sparkles)))
              (fn [show snapshot]
                (pspy :sparkle
                      ;; See if we create any new sparkles (unless we've been asked to end).
                      (when @running
                        (doseq [head heads]
                          (let [chance (params/resolve-param chance show snapshot head)]
                            (when (< (rand) chance)
                              (swap! sparkles assoc head (:instant snapshot))))))
                      ;; Build assigners for all active sparkles.
                      (let [fade-time (params/resolve-unless-frame-dynamic fade-time show snapshot)
                            now (:instant snapshot)]
                        (for [[head creation-time] @sparkles]
                          (let [color (params/resolve-param color show snapshot head)
                                fade-time (max 10 (params/resolve-param fade-time show snapshot head))
                                fraction (/ (- now creation-time) fade-time)
                                faded (colors/darken color (* fraction (colors/lightness color)))]
                            (build-head-assigner :color head
                                                 (fn [show snapshot target previous-assignment]
                                                   (htp-merge (params/resolve-param previous-assignment show snapshot head)
                                                              faded))))))))
              (fn [show snapshot]
                ;; Arrange to shut down once all existing sparkles fade out.
                (reset! running false))))))

(defn blast
  "An effect which creates a sphere of light which expands from a
  point, then hollows out as it continues to grow and fade."
  [fixtures & {:keys [color x y grow-time max-solid-diameter]
               :or {color default-sparkle-color x (:center-x @(:dimensions *show*))
                    y (:center-y @(:dimensions *show*)) grow-time 1000
                    max-solid-diameter 0.2}}]
  ;; Some kind of gradient slope for edges too?
  )
