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

(defn cue
  "Creates a cue for managing in a cue grid. `show-key` will be used
  as the effect keyword when the cue is triggered to add it to a show,
  ending any existing effect that was using that key. `effect` is a
  function that will be called to obtain the effect to be started and
  monitored when this cue is triggered.

  If supplied, `:short-name` identifies a compact, user-oriented name
  to be displayed in the web interface or controller display (if it
  has one) to help identify the cue, which can be helpful if the name
  of the underlying [[Effect]] is ambiguous or overly long.

 `:color` requests that the web interface and control surfaces draw
  the cue using the specified color rather than the default white to
  help the user identify it. This is only a request, as not all
  control surfaces support all (or any) colors. The color is passed
  to [[interpret-color]], so it can be specified in a variety of ways.

  `:end-keys` introduces a sequence of keywords identifying other
  effects which should be ended whenever this one is started. This
  allows a set of grid cues to be set up as mutually exclusive, even
  if they use different keywords within the show for other reasons."
  {:doc/format :markdown}
  [show-key effect & {:keys [short-name color end-keys priority held]
                      :or {short-name (:name (effect)) color :white priority 0}}]
  {:pre [(some? show-key) (ifn? effect) (= (class (effect)) Effect)]}
  {:name (name short-name)
   :key (keyword show-key)
   :effect effect
   :priority priority
   :held held
   :color (params/interpret-color (if (keyword? color) (name color) color))
   :end-keys (vec end-keys)
   })
