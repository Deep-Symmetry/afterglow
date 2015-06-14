(ns afterglow.controllers
  "Provides shared services for all controller implementations."
  {:author "James Elliott"
   :doc/format :markdown}
  (:require [overtone.at-at :as at-at]
            [afterglow.effects :as fx]
            [afterglow.effects.params :as params])
  (:import [afterglow.effects Effect]))

(defonce
  ^{:doc "Provides thread scheduling for all controller user interface updates."}
  pool
  (at-at/mk-pool))

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
   :cues (ref {})})

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

(defn activate-cue!
  "Records the fact that the cue at the specified grid coordinates was
  activated in a show, and assigned the specified id, which can be
  used later to determine whether the same cue is still running. If id
  is nil, the cue is deactivated rather than activated."
  [grid x y id]
  (dosync
   (when-let [cue (cue-at grid x y)]
     (set-cue! grid x y (if (some? id)
                          (assoc cue :active-id id)
                          (dissoc cue :active-id))))))

;; TODO: Support variable resolution, type specification, shorter name, centered gauge
(defn cue
  "Creates a cue for managing in a cue grid. `show-key` will be used
  as the effect keyword when the cue is triggered to add it to a show,
  ending any existing effect that was using that key. `effect` is a
  function that will be called to obtain the effect to be started and
  monitored when this cue is triggered. It will be passed a map
  allowing lookup of any temporary variables introduced by the
  cue (see the `:variables` parameter below).

  If supplied, `:short-name` identifies a compact, user-oriented name
  to be displayed in the web interface or controller display (if it
  has one) to help identify the cue, which can be helpful if the name
  of the underlying effect is ambiguous or overly long.

 `:color` requests that the web interface and control surfaces draw
  the cue using the specified color rather than the default white to
  help the user identify it. This is only a request, as not all
  control surfaces support all (or any) colors. The color is passed
  to [[interpret-color]], so it can be specified in a variety of ways.

  `:end-keys` introduces a sequence of keywords identifying other
  effects which should be ended whenever this one is started. This
  allows a set of grid cues to be set up as mutually exclusive, even
  if they use different keywords within the show for other reasons.

  `:variables` introduces a list of variable bindings for the cue,
  each of which is a map with the following keys:

  * `:key` identifies the variable that is being bound by the cue (for easy
    adjustment in the user interface while the cue is running). If it is
    a string rather than a keyword, it identifies a temporary variable
    which should exist only for the duration of the cue. The actual name
    will be assigned when the cue is activated. In order for the effect
    function to be able to access the correct variable, it is passed a
    map whose keys are keywords made from the string `:key` values
    supplied in the `:variables` list, and whose values are the actual
    keyword of the corresponding temporary show variable created for the
    cue.

  * `:name`, if present, gives the name by which the variable should be
    displayed in the user interface for adjusting it. If not specified,
    the name of `:key` is used.

  * `:short-name`, if present, is a shorter version of the name which
    can be used in interfaces with limited space.

  * `:min` specifies the minimum value to which the variable can be set.
    If not supplied, zero is assumed.

  * `:max` specifies the maximum value to which the variable can be set.
    If not supplied, 100 is assumed.

  * `:start` specifies the value to assign to the variable at the start
    of the cue, if any.

  * `:type` identifies the type of the variable, to help formatting
    its display. Supported values are `:integer`, `:float`, possibly
    others in the future. If omitted or unrecognized, `:float` is
    assumed.

  * `:pan` is supplied with a true value requests that the gauge
    displayed when adjusting this variable's value be like a pan
    gauge, showing deviation from a central value.

  * `:resolution` specifies the smallest amount by which the variable
    will be incremented or decremented when the user adjusts it for
    controllers with continuous encoders. If not specified, 1/200
    of the range from `:min` to `:max` is used.

  * `:aftertouch` accompanied by a true value enables the variable to
  be adjusted by aftertouch values on pressure-sensitive controllers.

  * `:aftertouch-min` and `:aftertouch-max` specify the range into
    which MIDI aftertouch values will be mapped, if they are present.
    Otherwise the standard `:min` and `:max` values will be used."
  {:doc/format :markdown}
  [show-key effect & {:keys [short-name color end-keys priority held variables]
                      :or {short-name (:name (effect {})) color :white priority 0}}]
  {:pre [(some? show-key) (ifn? effect) (= (class (effect {})) Effect)]}
  {:name (name short-name)
   :key (keyword show-key)
   :effect effect
   :priority priority
   :held held
   :color (params/interpret-color (if (keyword? color) (name color) color))
   :end-keys (vec end-keys)
   :variables (vec variables)
   })

