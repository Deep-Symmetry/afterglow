(ns afterglow.effects
  "Support functions for building the effects pipeline."
  {:author "James Elliott"}
  (:require [afterglow.channels :as channels]
            [afterglow.effects.params :as params]
            [afterglow.rhythm :as rhythm]
            [afterglow.show-context :refer [*show*]]
            [taoensso.timbre :as timbre :refer [error warn info debug]]
            [taoensso.timbre.profiling :as profiling :refer [pspy profile]])
  (:import [afterglow.rhythm MetronomeSnapshot]))

(defonce
  ^{:private true
    :doc "Protect protocols against namespace reloads"}
  _PROTOCOLS_
  (do
    (defprotocol IAssigner
  "Assign some attribute (color, attitude, channel value) to an element of a light show at a given
  point in time. Any previous assignment to this element will be supplied as an argument, and may
  be tweaked or ignored as needs dictate. The target will be a subtree of the show's fixtures,
  currently either a head or channel."
  (assign [this show ^MetronomeSnapshot snapshot target previous-assignment]
    "Calculate the value the show element should have at this moment in time. Return a value
appropriate for the kind of assignment, e.g. color object, channel value."))    

;; At each DMX frame generation, we will run through all the effects and ask them if they are still
;; active. If not, they will be removed from the list of active effects. For the remaining ones,
;; we obtain a list of assignments they want to make, and handle them as described above.
(defprotocol IEffect
    "Generates a list of assignments that should be in effect at a given moment in the show.
  Can eventually end on its own, or be asked to end. When asked, may end immediately, or after
  some final activity, such as a fade."
    (still-active? [this show snapshot]
      "Check whether this effect is finished, and can be cleaned up.")
    (generate [this show snapshot]
      "List the asignments needed to implement the desired effect at this moment in time.")
    (end [this show snapshot]
      "Arrange to finish as soon as possible; return true if can end immediately."))))


;; So...
;;
;; We are going to have types of assigners: :channel, :head-color, :head-rotation. Each will be
;; associated with some kind of target. Channel: :u<universe-id>-c<channel-number> Head-related
;; ones: :h<head-id>. We will run through the list of effects in priority order; each will
;; spit out some number of assigners, which are a tuple:
(defrecord Assigner [^clojure.lang.Keyword kind ^clojure.lang.Keyword target-id target ^clojure.lang.IFn f]
  IAssigner
  (assign [this show snapshot target previous-assignment]
    (f show snapshot target previous-assignment)))

;; We will gather these into a map, whose keys are the assigner kind, and whose values, in turn,
;; are maps of assigners of that kind. Each key in the inner map is a target for which values are
;; to be assigned, and the values are the priority-ordered list of assigners to run on that target.
;; On each DMX frame we will run through these lists in parallel, and determine the final assignment
;; value which results for each target. Finally, once that is done, the resulting assignments will be
;; resolved to DMX values by calling these:

#_(defprotocol IAssignmentResolver
  "Translates an attribute assignment (color, attitude, channel value) for an element of a light show
  to the actual DMX values that will implement it."
  (resolve-assignment [this show buffers target snapshot assignment]
    "Translate the assignment to appropriate setting for target DMX channels."))

;; The show will maintain a list of resolvers for the different kinds of assignments that can
;; exist, and will run through that list in order, handling low-level channel assignments first,
;; then more sophisticated conceptual assignments, since they should take priority over conflicting
;; low-level assignments. Each list of assignments of a particular kind can be processed in parallel,
;; however, since there will only be one resulting assignment for each target.
;; The assignment resolvers will be functions:
#_(defn AssignmentResolver [^clojure.lang.Keyword kind target snapshot assignment ^clojure.lang.IFn f]
    IAssignmentResolver
    (resolve-assignment [this show buffers target snapshot assignment]
                        (f show buffers target snapshot assignment)))

(defrecord Effect [^String name ^clojure.lang.IFn active-fn
                   ^clojure.lang.IFn gen-fn ^clojure.lang.IFn end-fn]
  IEffect
  (still-active? [this show snapshot]
    (active-fn show snapshot))
  (generate [this show snapshot]
    (gen-fn show snapshot))
  (end [this show snapshot]
    (end-fn show snapshot)))

(defn always-active
  "An effect still-active? predicate which simply always returns true."
  [show snapshot]
  true)

(defn end-immediately
  "An effect end function which simply says the effect is now finished."
  [show snapshot]
  true)

(defn build-head-assigner
  "Returns an assigner of the specified type which applies the
  specified assignment function to the provided head or fixture."
  [kind head f]
  (Assigner. kind (keyword (str "i" (:id head))) head f))

(defn build-head-assigners
  "Returns a list of assigners of the specified type which apply an
  assignment function to all the supplied heads or fixtures."
  [kind heads f]
  (map #(build-head-assigner kind % f) heads))

(defn build-head-parameter-assigner
  "Returns an assigner of the specified kind which applies a parameter
  to the supplied head or fixture. If the parameter is not
  frame-dynamic, it gets resolved when creating this assigner.
  Otherwise, resolution is deferred to frame rendering time."
  [kind head param show snapshot]
  (let [resolved (params/resolve-unless-frame-dynamic param show snapshot head)]
    (build-head-assigner kind head (fn [show snapshot target previous-assignment] resolved))))

(defn build-head-parameter-assigners
  "Returns a list of assigners of the specified kind which apply a
  parameter to all the supplied heads or fixtures."
  [kind heads param show]
  (let [snapshot (rhythm/metro-snapshot (:metronome show))]
    (map #(build-head-parameter-assigner kind % param show snapshot) heads)))
