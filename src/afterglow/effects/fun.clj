(ns afterglow.effects.fun
  "A collection of neat effects that are both useful in shows, and
  examples of how to create such things."
  {:author "James Elliott"}
  (:require [afterglow.effects :as fx :refer [build-head-assigner build-head-assigners always-active end-immediately]]
            [afterglow.effects.channel :refer [function-effect]]
            [afterglow.effects.color :refer [htp-merge find-rgb-heads color-effect]]
            [afterglow.effects.dimmer :refer [dimmer-effect]]
            [afterglow.effects.params :as params]
            [afterglow.rhythm :as rhythm]
            [afterglow.show :as show]
            [afterglow.show-context :refer [*show*]]
            [clojure.math.numeric-tower :as math]
            [com.evocomputing.colors :as colors]
            [taoensso.timbre :refer [info spy]]
            [taoensso.timbre.profiling :refer [pspy]])
  (:import (afterglow.effects Effect)
           (afterglow.rhythm Metronome)
           (javax.vecmath Point3d)))

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
  (let [down-beat-color (params/bind-keyword-param
                         down-beat-color :com.evocomputing.colors/color default-down-beat-color)
        other-beat-color (params/bind-keyword-param
                          other-beat-color :com.evocomputing.colors/color default-other-beat-color)
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
                    (let [raw-intensity (* 2 (- (/ 1 2) (rhythm/snapshot-beat-phase @local-snapshot 1)))
                          intensity (if (neg? raw-intensity) 0 raw-intensity)
                          base-color (if (rhythm/snapshot-down-beat? @local-snapshot)
                                       (params/resolve-param down-beat-color show @local-snapshot)
                                       (params/resolve-param other-beat-color show @local-snapshot))]
                      (colors/create-color {:h (colors/hue base-color)
                                            :s (colors/saturation base-color)
                                            :l (* (colors/lightness base-color) intensity)}))))
          assigners (build-head-assigners :color heads f)]
      (Effect. "Metronome"
               (fn [show snapshot]  ;; Continue running until the end of a measure
                 ;; Also need to set up the local snapshot based on our private metronome
                 ;; for the assigners to use.
                 (reset! local-snapshot (rhythm/metro-snapshot metronome))
                 (or @running (< (rhythm/snapshot-bar-phase @local-snapshot) 0.9)))
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

(defn strobe
  "A compound effect which sets dimmers to the level determined by the
  show variable :strobe-dimmers (defaulting to 255), assigns a color
  based on the show variables `:strobe-hue` (with a default of 277,
  purple) `:strobe-saturation` (with a default of 100), and
  `:strobe-lightness` (with a default of 100, which whites out the hue
  until it is lowered), and then sets the fixtures' strobe channel to
  the specified level, which may be a dynamic parameter.

  This is designed to be run as a high priority queue, ideally while
  held and with aftertouch adjusting a cue-introduced variable for
  `level` (which is used to control the strobe function of the
  affected fixtures, setting the strobe speed, and defaults to a
  middle value). The global strobe color can be adjusted via the show
  variables, either by aftertouch or by another effect with no assigners,
  like [[adjust-strobe]]."
  {:doc/format :markdown}
  [name fixtures level]
  {:pre [(some? *show*)]}
  (let [hue-param (params/bind-keyword-param :strobe-hue Number 277)
        saturation-param (params/bind-keyword-param :strobe-saturation Number 100)
        lightness-param (params/bind-keyword-param :strobe-lightness Number 100)
        dimmer-param (params/bind-keyword-param :strobe-dimmers Number 255)
        level-param (params/bind-keyword-param level Number 50)
        dimmers (dimmer-effect dimmer-param fixtures)
        color (color-effect "strobe color"
                            (params/build-color-param :h hue-param :s saturation-param :l lightness-param)
                            fixtures :include-color-wheels :true)
        function (function-effect "strobe level" :strobe level-param fixtures)]
    (Effect. name always-active
             (fn [show snapshot] (concat
                                  (fx/generate dimmers show snapshot)
                                  (fx/generate color show snapshot)
                                  (fx/generate function show snapshot)))
             (fn [show snapshot]
               (fx/end dimmers show snapshot)
               (fx/end color show snapshot)
               (fx/end function show snapshot)))))

;; TODO: Consider getting rid of this and moving these variables to the strobe
;;       effect itself once the web and Push interfaces allow access to more
;;       than the first two cue variables?
(defn adjust-strobe
  "An auxiliary effect which creates no assigners to directly affect
  lights, but adjusts show variables used by the [[strobe]] effect. It
  is designed to be run as a parallel cue to offer the user controls
  for adjusting the hue and saturation of the strobe."
  {:doc/format :markdown}
  []
  {:pre [(some? *show*)]}
  (let [saved-hue (show/get-variable :strobe-hue)
        saved-saturation (show/get-variable :strobe-saturation)]
    (when-not saved-saturation (show/set-variable! :strobe-saturation 100))
    (when-not saved-hue (show/set-variable! :strobe-hue 277))
    
    (Effect. "Strobe Adjust" always-active
            (fn [show snapshot] nil)
            (fn [show snapshot]
              (show/set-variable! :strobe-hue saved-hue)
              (show/set-variable! :strobe-saturation saved-saturation)
              true))))

(def default-color-cycle
  "The default list of colors to cycle through for the various color
  cycle chases."
  [(colors/create-color :red)
   (colors/create-color :orange)
   (colors/create-color :yellow)
   (colors/create-color :green)
   (colors/create-color :cyan)
   (colors/create-color :blue)
   (colors/create-color :purple)
   (colors/create-color :white)])

(defn max-distance
  "Calculate the distance from the center of the show to the furthest
  fixture."
  [show]
  (let [dimensions @(:dimensions show)
        corner-1 (Point3d. (:min-x dimensions) (:min-y dimensions) (:min-z dimensions))
        corner-2 (Point3d. (:max-x dimensions) (:max-y dimensions) (:max-z dimensions))]
    (/ (.distance corner-1 corner-2) 2)))

(defn max-xy-distance
  "Calculate the distance from the center of the show in the x-y plane
  to the furthest fixture projected onto that plane."
  [show]
  (let [dimensions @(:dimensions show)
        corner-1 (Point3d. (:min-x dimensions) (:min-y dimensions) 0.0)
        corner-2 (Point3d. (:max-x dimensions) (:max-y dimensions) 0.0)]
    (/ (.distance corner-1 corner-2) 2)))

(defn target-xy-distance-fraction
  "Calculate how far the target is from the center of the x-y plane,
  as a fraction of the largest distance within that plane."
  [target center max-distance]
  (let [location (Point3d. (:x target) (:y target) 0.0)]
    (/ (.distance center location) max-distance)))

(defn radial-wipe-cycle-chase
  "Returns an effect which changes color on each bar of a phrase,
  expanding the color from the center of the x-y plane outwards during
  the down beat."
  [fixtures & {:keys [color-cycle] :or {color-cycle default-color-cycle}}]
  {:pre [(some? *show*)]}
  (let [dimensions @(:dimensions *show*)
        center (Point3d. (:center-x dimensions) (:center-y dimensions) 0.0)
        max-distance (max-xy-distance *show*)
        previous-color (atom nil)
        color-cycle (map (fn [arg default]
                           (params/bind-keyword-param arg :com.evocomputing.colors/color default))
                         color-cycle default-color-cycle)]
    (doseq [arg color-cycle]
      (params/validate-param-type arg :com.evocomputing.colors/color))
    (let [heads (find-rgb-heads fixtures)
          ending (atom nil)
          color-cycle (map #(params/resolve-unless-frame-dynamic % *show* (rhythm/metro-snapshot (:metronome *show*)))
                           color-cycle)
          f (fn [show snapshot target previous-assignment]
              (pspy :radial-wipe-cycle-chase-effect
                    (let [base-color (params/resolve-param (nth (cycle color-cycle)
                                                                (dec (rhythm/snapshot-bar-within-phrase snapshot)))
                                                           show snapshot)]
                      (when (> (rhythm/snapshot-beat-within-bar snapshot) 1) ; Be ready for the next wipe
                        (reset! previous-color base-color))
                      (if (or (> (rhythm/snapshot-beat-within-bar snapshot) 1)
                              (> (+ (rhythm/snapshot-beat-phase snapshot) 0.1)
                                 (target-xy-distance-fraction target center max-distance)))
                        base-color
                        @previous-color))))
          assigners (build-head-assigners :color heads f)]
      (Effect. "Radial Wipe"
               (fn [show snapshot]  ;; Continue running until the end of a phrase.
                 (or (nil? @ending) (= (:phrase snapshot) @ending)))
               (fn [show snapshot] assigners)
               (fn [snow snapshot]  ;; Arrange to shut down at the end of a phrase
                 (reset! ending (:phrase snapshot))
                 nil)))))

(defn blast
  "An effect which creates a sphere of light which expands from a
  point, then hollows out as it continues to grow and fade."
  [fixtures & {:keys [color x y grow-time max-solid-diameter]
               :or {color default-sparkle-color x (:center-x @(:dimensions *show*))
                    y (:center-y @(:dimensions *show*)) grow-time 1000
                    max-solid-diameter 0.2}}]
  ;; Some kind of gradient slope for edges too?
  )
