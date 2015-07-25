(ns afterglow.controllers
  "Provides shared services for all controller implementations."
  {:author "James Elliott", :doc/format :markdown}
  (:require [afterglow.effects.params :as params]
            [afterglow.effects]
            [overtone.at-at :as at-at]
            [overtone.midi :as midi]
            [taoensso.timbre :as timbre])
  (:import (afterglow.effects Effect)))

(defonce
  ^{:doc "Provides thread scheduling for all controller user interface updates."}
  pool
  (at-at/mk-pool))

(def minimum-bpm
  "The lowest BPM value which controllers are allowed to set."
  20)

(def maximum-bpm
  "The highest BPM value which controllers are allowed to set."
  200)

(defonce
  ^{:private true
    :doc "Protect protocols against namespace reloads"}
  _PROTOCOLS_
  (do
    (defprotocol IGridController
  "A controller which provides an interface for a section of the cue
  grid, and which can be linked to the web interface so they scroll in
  unison."
  (display-name [this]
    "Returns the name by which the controller can be identified in
    user interfaces.")
  (physical-height [this]
    "Returns the height of the cue grid on the controller.")
  (physical-width [this]
    "Returns the width of the cue grid on the controller.")
  (current-bottom [this] [this y]
    "Returns the cue grid row currently displayed at the bottom of the
    controller, or sets it.")
  (current-left [this] [this x]
    "Returns the cue grid column currently displayed at the left of
    the controller, or sets it.")
  (add-move-listener [this f]
    "Registers a function that will be called whenever the
    controller's viewport on the overall cue grid is moved, or the
    controller is deactivated. The function will be called with two
    arguments, the controller and a keyword which will be either :move
    or :deactivated. In the latter case, the function will never be
    called again.")
  (remove-move-listener [this f]
    "Unregisters a move listener function so it will no longer be
    called when the controller's origin is moved."))))

(defn cue-grid
  "Return a two dimensional arrangement for launching and monitoring
  cues, suitable for both a web interface and control surfaces with a
  pad grid. Cues are laid out with [0, 0] being the bottom left
  corner. The width of the grid is the highest x coordinate of a cue,
  and the height is the highest y coordinate."
  []
  {:dimensions (ref [0 0])
   ;; For now using a sparse grid in the form of a map whose keys are
   ;; the cue coordinates, and whose values are the cues. If performance
   ;; dictates, can change to nested vectors or something else later.
   :cues (ref {})
   ;; Also track any non-grid controllers which have bound controls or
   ;; notes to cues and want feedback as they activate and deactivate.
   :midi-feedback (ref {})
   ;; Also allow arbitrary functions to be called when cues change state.
   :fn-feedback (ref {})
   })

(defn cue-at
  "Find the cue, if any, at the specified coordinates."
  [grid x y]
  (get @(:cues grid) [x y]))

(defn grid-dimensions
  "Return the current dimensions of the cue grid."
  [grid]
  @(:dimensions grid))

(defn grid-width
  "Return the current width of the cue grid."
  [grid]
  (get (grid-dimensions grid) 0))

(defn grid-height
  "Return the current height of the cue grid."
  [grid]
  (get (grid-dimensions grid) 1))

(defn clear-cue!
  "Removes any cue which existed at the specified coordinates in the
  cue grid. If one was there, updates the grid dimensions if needed."
  [grid x y]
  {:pre [(integer? x) (integer? y) (not (neg? x)) (not (neg? y)) (some? (:cues grid))]}
  (dosync
   (when (some? (get @(:cues grid) [x y]))
     (alter (:cues grid) dissoc [x y])
     (when (or (= x (grid-width grid)) (= y (grid-height grid)))
       (ref-set (:dimensions grid) (reduce (fn [[width height] [x y]]
                                             [(max width x) (max height y)])
                                           [0 0] (keys @(:cues grid))))))))

(defn set-cue!
  "Puts the supplied cue at the specified coordinates in the cue grid.
  Replaces any cue which formerly existed at that location. If cue is
  nil, delegates to clear-cue!"
  [grid x y cue]
  {:pre [(integer? x) (integer? y) (not (neg? x)) (not (neg? y)) (some? (:cues grid))]}
  (dosync
   (if (nil? cue)
     (clear-cue! grid x y)
     (do
       (alter (:cues grid) assoc [x y] cue)
       (ref-set (:dimensions grid) [(max x (grid-width grid)) (max y (grid-height grid))])))))

(defn add-cue-feedback!
  "Arranges for the specified non-grid MIDI controller to receive
  feedback events when the cue grid location activates or
  deactivates."
  [grid x y device channel kind note & {:keys [on off] :or {on 127 off 0}}]
  {:pre [(integer? x) (integer? y) (not (neg? x)) (not (neg? y)) (some? (:midi-feedback grid))
         (#{:control :note} kind) (integer? on) (integer? off) (<= 0 on 127) (<= 0 off 127)]}
  (dosync
   (alter (:midi-feedback grid) assoc-in [[x y] [device channel note kind]] [on off]))
  nil)

(defn clear-cue-feedback!
  "Ceases sending the specified non-grid MIDI controller feedback
  events when the cue grid location activates or deactivates,
  returning the feedback values that had been in place if there were
  any."
  [grid x y device channel kind note]
  {:pre [(integer? x) (integer? y) (not (neg? x)) (not (neg? y)) (some? (:midi-feedback grid))
         (#{:control :note} kind)]}
  (dosync
   (let [entry (get (ensure (:midi-feedback grid)) [x y])
         former (get entry [device channel note kind])]
     (alter (:midi-feedback grid) assoc [x y] (dissoc entry [device channel note kind]))
     former)))

(defn add-cue-fn!
  "Arranges for the supplied function to be called when the cue grid
  location activates or deactivates. It will be called with three
  arguments: the first, a keyword identifying the state to which the
  cue has transitioned, either `:started`, `:ending`, or `:ended`, the
  keyword with which the cue created an effect in the show, and the
  unique numeric ID assigned to the effect when it was started. The
  last two argumetnts can be used with [[end-effect!]] and its
  `:when-id` argument to avoid accidentally ending a different cue."
  {:doc/format :markdown}
  [grid x y f]
  {:pre [(integer? x) (integer? y) (not (neg? x)) (not (neg? y)) (fn? f) (some? (:fn-feedback grid))]}
  (dosync
   ;; TODO: Consider putting the actual cue as the value, and logging a warning and ending the feedback
   ;;       if a different cue gets stored there.
   (alter (:fn-feedback grid) assoc-in [[x y] f] true))
  nil)

(defn clear-cue-fn!
  "Ceases calling the supplied function when the cue grid location
  activates or deactivates."
  [grid x y f]
  {:pre [(integer? x) (integer? y) (not (neg? x)) (not (neg? y)) (fn? f) (some? (:fn-feedback grid))]}
  (dosync
   (let [entry (get (ensure (:fn-feedback grid)) [x y])]
     (alter (:fn-feedback grid) assoc [x y] (dissoc entry f))))
  nil)

(defn activate-cue!
  "Records the fact that the cue at the specified grid coordinates was
  activated in a show, and assigned the specified `id`, which can be
  used later to determine whether the same cue is still running. If
  `id` is `nil`, the cue is deactivated rather than activated.

  Sends appropriate MIDI feedback events to any non-grid controllers
  which have requested them for that cue, so they can update their
  interfaces appropriately, then calls any registered functions that
  want updates about the cue state letting them know it has started,
  its effect keyword, and the `id` of the effect that was created
  or ended."
  {:doc/format :markdown}
  [grid x y id]
  (dosync
   (when-let [cue (cue-at grid x y)]
     (let [former-id (:active-id cue)]
       (set-cue! grid x y (if (some? id)
                            (assoc cue :active-id id)
                            (dissoc cue :active-id)))
       (doseq [[[device channel note kind] feedback] (get @(:midi-feedback grid) [x y])]
         (let [velocity (if (some? id) (first feedback) (second feedback))]
           (if (= :control kind)
             (midi/midi-control device note velocity channel)
             (if (some? id)
               (midi/midi-note-on device note velocity channel)
               (midi/midi-note-off device note channel)))))
       (doseq [[f _] (get @(:fn-feedback grid) [x y])]
         (if (some? id)
           (f :started (:key cue) id)
           (when (some? former-id)
             (f :ended (:key cue) former-id))))))))

(defn report-cue-ending
  "Calls any registered functions that want updates about the cue
  state to inform them it has begun to gracefully end, its effect
  keyword, and the `id` of the effect that is ending."
  {:doc/format :markdown}
  [grid x y id]
  (when-let [cue (cue-at grid x y)]
    (doseq [[f _] (get @(:fn-feedback grid) [x y])]
      (f :ending (:key cue) id))))
