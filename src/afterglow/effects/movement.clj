(ns afterglow.effects.movement
  "Effects pipeline functions for working with direction assignments
  to fixtures and heads."
  {:author "James Elliott"}
  (:require [afterglow.channels :as channels]
            [afterglow.effects.channel :as chan-fx]
            [afterglow.effects.params :as params]
            [afterglow.effects :as fx]
            [afterglow.rhythm :as rhythm]
            [afterglow.show-context :refer [*show*]]
            [afterglow.transform :refer [calculate-position calculate-aim]]
            [clojure.math.numeric-tower :as math]
            [taoensso.timbre.profiling :refer [pspy]]
            [taoensso.timbre :as timbre :refer [debug]])
  (:import [afterglow.effects Assigner Effect]
           [javax.vecmath Point3d Vector3d]))

(defn find-moving-heads
  "Returns all heads of the supplied fixtures which are capable of
  movement, in other words they have either a pan or tilt channel."
  [fixtures]
  (filter #(pos? (count (filter #{:pan :tilt} (map :type (:channels %))))) (channels/expand-heads fixtures)))

(defn direction-effect
  "Returns an effect which assigns a direction parameter to all
  moving heads of the fixtures supplied when invoked. The direction is
  a vector in the frame of reference of the show, so standing in the
  audience facing the show, x increases to the left, y away from the
  ground, and z towards the audience."
  [name direction fixtures]
  {:pre [(some? name) (some? *show*) (sequential? fixtures)]}
  (params/validate-param-type direction Vector3d)
  (let [heads (find-moving-heads fixtures)
        assigners (fx/build-head-parameter-assigners :direction heads direction *show*)]
    (Effect. name fx/always-active (fn [show snapshot] assigners) fx/end-immediately)))

(defn direction-assignment-resolver
  "Resolves the assignment of a direction to a fixture or a head."
  [show buffers snapshot target assignment _]
  (let [direction (params/resolve-param assignment show snapshot target)  ; In case it is frame dynamic
        direction-key (keyword (str "pan-tilt-" (:id target)))
        former-values (direction-key (:previous @(:movement *show*)))
        [pan tilt] (calculate-position target direction former-values)]
    (debug "Direction resolver pan:" pan "tilt:" tilt)
    (doseq [c (filter #(= (:type %) :pan) (:channels target))]
      (chan-fx/apply-channel-value buffers c pan))
    (doseq [c (filter #(= (:type %) :tilt) (:channels target))]
      (chan-fx/apply-channel-value buffers c tilt))
    (swap! (:movement *show*) #(assoc-in % [:current direction-key] [pan tilt]))))

(defn aim-effect
  "Returns an effect which assigns an aim parameter to all moving
  heads of the fixtures supplied when invoked. The direction is a
  point in the frame of reference of the show, so standing in the
  audience facing the show, x increases to the left, y away from the
  ground, and z towards the audience, and the origin is the center of
  the show."
  [name target-point fixtures]
  {:pre [(some? name) (some? *show*) (sequential? fixtures)]}
  (params/validate-param-type target-point Point3d)
  (let [heads (find-moving-heads fixtures)
        assigners (fx/build-head-parameter-assigners :aim heads target-point *show*)]
    (Effect. name fx/always-active (fn [show snapshot] assigners) fx/end-immediately)))

(defn aim-assignment-resolver
  "Resolves the assignment of an aiming point to a fixture or a
  head."
  [show buffers snapshot target assignment _]
  (let [target-point (params/resolve-param assignment show snapshot target)  ; In case it is frame dynamic
        direction-key (keyword (str "pan-tilt-" (:id target)))
        former-values (direction-key (:previous @(:movement *show*)))
        [pan tilt] (calculate-aim target target-point former-values)]
    (debug "Aim resolver pan:" pan "tilt:" tilt)
    (doseq [c (filter #(= (:type %) :pan) (:channels target))]
      (chan-fx/apply-channel-value buffers c pan))
    (doseq [c (filter #(= (:type %) :tilt) (:channels target))]
      (chan-fx/apply-channel-value buffers c tilt))
    (swap! (:movement *show*) #(assoc-in % [:current direction-key] [pan tilt]))))
