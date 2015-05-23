(ns afterglow.effects.movement
  "Effects pipeline functions for working with direction assignments
  to fixtures and heads."
  {:author "James Elliott"}
  (:require [afterglow.channels :as channels]
            [afterglow.effects :refer :all]
            [afterglow.effects.channel :refer [apply-channel-value]]
            [afterglow.effects.params :as params]
            [afterglow.rhythm :as rhythm]
            [afterglow.show-context :refer [*show*]]
            [afterglow.transform :refer [calculate-position]]
            [clojure.math.numeric-tower :as math]
            [taoensso.timbre :refer [debug]]
            [taoensso.timbre.profiling :refer [pspy]])
  (:import [afterglow.effects Assigner Effect]
           [javax.vecmath Point3d Vector3d]))

(defn find-moving-heads
  "Returns all heads of the supplied fixtures which are capable of
  movement, in other words they have either a pan or tilt channel."
  [fixtures]
  (filter #(pos? (count (filter #{:pan :tilt} (map :type (:channels %))))) (channels/expand-heads fixtures)))

(defn direction-cue
  "Returns an effect which assigns a direction parameter to all
  moving heads of the fixtures supplied when invoked. The direction is
  a vector in the frame of reference of the show, so standing in the
  audience facing the show, x increases to the left, y away from the
  ground, and z towards the audience."
  [name direction fixtures]
  {:pre [(some? name) (some? *show*) (seq? fixtures)]}
  (params/validate-param-type direction Vector3d)
  (let [heads (find-moving-heads fixtures)
        assigners (build-head-parameter-assigners :direction heads direction *show*)]
    (Effect. name always-active (fn [show snapshot] assigners) end-immediately)))


(defn direction-assignment-resolver
  "Resolves the assignment of a direction to a fixture or a head."
  [show buffers snapshot target assignment]
  (let [direction (params/resolve-param assignment show snapshot target)  ; In case it is frame dynamic
        [pan tilt] (calculate-position target direction)]
    ;; TODO: Track and pass in current pan and tilt as targets, to smooth movement
    (debug "Resolver pan:" pan "tilt:" tilt)
    (doseq [c (filter #(= (:type %) :pan) (:channels target))]
      (apply-channel-value buffers c pan))
    (doseq [c (filter #(= (:type %) :tilt) (:channels target))]
      (apply-channel-value buffers c tilt))))
