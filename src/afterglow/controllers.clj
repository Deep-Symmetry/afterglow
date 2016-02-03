(ns afterglow.controllers
  "Provides shared services for all controller implementations."
  {:author "James Elliott"}
  (:require [overtone.at-at :as at-at]
            [overtone.midi :as midi]
            [afterglow.midi :as amidi]
            [afterglow.rhythm :as rhythm]
            [taoensso.timbre :as timbre]))

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
  "A controller which provides an interface for a section of the cue)
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
  "Registers a function that will be called whenever the controller's
  viewport on the overall cue grid is moved, or the controller is
  deactivated. The function will be called with two arguments, the
  controller and a keyword which will be either :move or :deactivated.
  In the latter case, the function will never be called again.")
  (remove-move-listener [this f]
  "Unregisters a move listener function so it will no longer be
  called when the controller's origin is moved."))

(defprotocol IOverlay
  "An activity which takes over part of the user interface
  while it is active."
  (captured-controls [this]
  "Returns the MIDI control-change events that will be consumed by
  this overlay while it is active, a set of integers.")
  (captured-notes [this]
  "Returns the MIDI note events that will be consumed by this overlay
  while it is active, a set of integers.")
  (adjust-interface [this snapshot]
  "Set values for the next frame of the controller interface, however
  that may be done; return a falsey value if the overlay is finished
  and should be removed. The `snapshot` is an [[IMetroSnapshot]] that
  specifies the instant in musical time at which the interface is
  being rendered, so this overlay can be drawn in sync with the rest
  of the interface.")
  (handle-control-change [this message]
  "Called when a MIDI control-change event matching the
  captured-controls lists has been received. Return a truthy value if
  the overlay has consumed the event, so it should not be processed
  further. If the special value `:done` is returned, it further
  indicates the overlay is finished and should be removed.")
  (handle-note-on [this message]
  "Called when a MIDI note-on event matching the captured-notes lists
  has been received. Return a truthy value if the overlay has consumed
  the event, so it should not be processed further. If the special
  value `:done` is returned, it further indicates the overlay is
  finished and should be removed.")
  (handle-note-off [this message]
  "Called when a MIDI note-off event matching the captured-notes lists
  has been received. Return a truthy value if the overlay has consumed
  the event, so it should not be processed further. If the special
  value `:done` is returned, it further indicates the overlay is
  finished and should be removed.")
  (handle-aftertouch [this message]
  "Called when a MIDI aftertouch event matching the captured-notes
  lists has been received. Return a truthy value if the overlay has
  consumed the event, so it should not be processed further. If the
  special value `:done` is returned, it further indicates the overlay
  is finished and should be removed."))))

(defn cue-grid
  "Return a two dimensional arrangement for launching and monitoring
  cues, suitable for both a web interface and control surfaces with a
  pad grid. Cues are laid out with [0, 0] being the bottom left
  corner. The width of the grid is the highest x coordinate of a cue,
  and the height is the highest y coordinate."
  []
  (with-meta
    {:dimensions (ref [0 0])
     ;; For now using a sparse grid in the form of a map whose keys are
     ;; the cue coordinates, and whose values are the cues. If performance
     ;; dictates, can change to nested vectors or something else later.
     :cues (ref {})
     ;; Also track any non-grid controllers which have bound controls or
     ;; notes to cues and want feedback as they activate and deactivate.
     ;; A nested set of maps: The first key is a tuple of the grid coordinates.
     ;; The next map is keyed by the tuple [MidiDevice channel note kind]
     ;; identifying where the feedback should be sent, and the values
     ;; are a tuple of [feedback-on feedback-off device disconnect-handler],
     ;; the MIDI values to send as feedback when the cue turns on or off,
     ;; the overtone.midi :midi-device map corresponding to the MidiDevice
     ;; object (for convenience in sending messages), and the handler function
     ;; that was registered to clear the feedback in case the device ever got
     ;; disconnected. It is included so that it can be unregistered if the user
     ;; explicitly cancels the feedback.
     :midi-feedback (ref {})
     ;; Also allow arbitrary functions to be called when cues change state.
     :fn-feedback (ref {})
     }
    {:type :cue-grid}))

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
  {:pre [(integer? x) (integer? y) (not (neg? x)) (not (neg? y)) (= (type grid) :cue-grid)]}
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
  {:pre [(integer? x) (integer? y) (not (neg? x)) (not (neg? y)) (= (type grid) :cue-grid)]}
  (dosync
   (if (nil? cue)
     (clear-cue! grid x y)
     (do
       (alter (:cues grid) assoc [x y] cue)
       (ref-set (:dimensions grid) [(max x (grid-width grid)) (max y (grid-height grid))])))))

(defn clear-cue-feedback!
  "Ceases sending the specified non-grid MIDI controller feedback
  events when the cue grid location activates or deactivates,
  returning the feedback values that had been in place if there were
  any."
  [grid x y device channel kind note]
  {:pre [(integer? x) (integer? y) (not (neg? x)) (not (neg? y))
         (= (type device) :midi-device) (= (type grid) :cue-grid)
         (#{:control :note} kind)]}
  (dosync
   (let [entry (get (ensure (:midi-feedback grid)) [x y])
         former (get entry [(:device device) channel note kind])
         [_ _ _ disconnect-handler] former]
     (alter (:midi-feedback grid) assoc [x y] (dissoc entry [(:device device) channel note kind]))
     (when (some? disconnect-handler)
       (amidi/remove-disconnected-device-handler! device disconnect-handler))
     former)))

(defn add-cue-feedback!
  "Arranges for the specified non-grid MIDI controller to receive
  feedback events when the cue grid location activates or
  deactivates."
  [grid x y device channel kind note & {:keys [on off] :or {on 127 off 0}}]
  {:pre [(integer? x) (integer? y) (not (neg? x)) (not (neg? y))
         (= (type device) :midi-device) (= (type grid) :cue-grid)
         (#{:control :note} kind) (integer? on) (integer? off) (<= 0 on 127) (<= 0 off 127)]}
  (letfn [(disconnect-handler []
            (clear-cue-feedback! grid x y device channel kind note))]
    (amidi/add-disconnected-device-handler! device disconnect-handler)
    (dosync
     (alter (:midi-feedback grid) assoc-in [[x y] [(:device device) channel note kind]]
            [on off device disconnect-handler])))
  nil)

(defn add-cue-fn!
  "Arranges for the supplied function to be called when the cue grid
  location activates or deactivates. It will be called with three
  arguments: the first, a keyword identifying the state to which the
  cue has transitioned, either `:started`, `:ending`, or `:ended`, the
  keyword with which the cue created an effect in the show, and the
  unique numeric ID assigned to the effect when it was started. The
  last two argumetnts can be used with [[end-effect!]] and its
  `:when-id` argument to avoid accidentally ending a different cue."
  [grid x y f]
  {:pre [(integer? x) (integer? y) (not (neg? x)) (not (neg? y)) (fn? f) (= (type grid) :cue-grid)]}
  (dosync
   ;; Consider putting the actual cue as the value, and logging a warning and ending the feedback
   ;; if a different cue gets stored there? Probabyl not.
   (alter (:fn-feedback grid) assoc-in [[x y] f] true))
  nil)

(defn clear-cue-fn!
  "Ceases calling the supplied function when the cue grid location
  activates or deactivates."
  [grid x y f]
  {:pre [(integer? x) (integer? y) (not (neg? x)) (not (neg? y)) (fn? f) (= (type grid) :cue-grid)]}
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
  [grid x y id]
  {:pre [(integer? x) (integer? y) (not (neg? x)) (not (neg? y)) (= (type grid) :cue-grid)]}
  (dosync
   (when-let [cue (cue-at grid x y)]
     (let [former-id (:active-id cue)]
       (set-cue! grid x y (if (some? id)
                            (assoc cue :active-id id)
                            (dissoc cue :active-id)))
       (doseq [[[_ channel note kind] feedback] (get @(:midi-feedback grid) [x y])]
         (let [[on-feedback off-feedback device] feedback
               velocity (if (some? id) on-feedback off-feedback)]
           (if (= :control kind)
             (midi/midi-control device note velocity channel)
             (if (some? id)
               (midi/midi-note-on device note velocity channel)
               (midi/midi-note-off device note channel)))))
       (doseq [[f _] (get @(:fn-feedback grid) [x y])]
         (when (some? former-id)
             (f :ended (:key cue) former-id))
         (when (some? id)
           ;; Delay :started notifications so watchers can recognize the ID that
           ;; show/add-effect-from-cue-grid! is about to return to them.
           (at-at/after 1 #(f :started (:key cue) id) pool)))))))

(defn report-cue-ending
  "Calls any registered functions that want updates about the cue
  state to inform them it has begun to gracefully end, its effect
  keyword, and the `id` of the effect that is ending."
  [grid x y id]
  {:pre [(integer? x) (integer? y) (not (neg? x)) (not (neg? y)) (= (type grid) :cue-grid)]}
  (when-let [cue (cue-at grid x y)]
    (doseq [[f _] (get @(:fn-feedback grid) [x y])]
      (f :ending (:key cue) id))))

(defn value-for-velocity
  "Given a cue variable which has been configured to respond to MIDI
  velocity, and the velocity of a MIDI message affecting
  it (presumably either Note On or Aftertouch / Poly Pressure),
  calculate the value which should be assigned to that variable."
  [v velocity]
  (let [low (or (:velocity-min v) (:min v) 0)
        high (or (:velocity-max v) (:max v) 100)
        range (- high low)]
    (+ low (* (/ velocity 127) range))))

(defn starting-vars-for-velocity
  "Given a cue and the velocity of the MIDI message which is causing
  it to start, gather all cue variables which have been configured to
  respond to MIDI velocity, and assign their initial values based on
  the velocity of the MIDI message. Returns a map suitable for use
  with the `:var-overrides` argument to
  [[show/add-effect-from-cue-grid!]]."
  [cue velocity]
  (reduce (fn [result v] (if (:velocity v)
                           (assoc result (keyword (:key v)) (value-for-velocity v velocity))
                           result))
          {} (:variables cue)))

(defn create-overlay-state
  "Return the state information needed to manage user-interface
  overlays implementing the [[IOverlay]] protocol. Controllers
  implementing this protocol will need to pass this object to the
  functions that manipulate and invoke overlays."
  []
  (atom {:next-id 0
         :overlays (sorted-map-by >)}))

(defn add-overlay
  "Add a temporary overlay to the interface. The `state` argument must
  be a value created by [[create-overlay-state]], and `overlay` an
  implementation of the [[IOverlay]] protocol to be added as the most
  recent overlay to that state."
  [state overlay]
  (swap! state (fn [old-state]
                 (let [id (:next-id old-state)
                       overlays (:overlays old-state)]
                   (assoc old-state :next-id (inc id) :overlays (assoc overlays id overlay))))))

(defn add-control-held-feedback-overlay
  "Builds a simple overlay which just adds something to the user
  interface until a particular control (identified by `control-num`)
  is released, and adds it to the controller. The overlay will end
  when a control-change message with value 0 is sent to the specified
  control number. Other than that, all it does is call the supplied
  function every time the interface is being updated. As
  with [[add-overlay]], `state` must be a value created
  by [[create-overlay-state]] and tracked by the controller."
  [state control-num f]
  (add-overlay state
               (reify IOverlay
                 (captured-controls [this] #{control-num})
                 (captured-notes [this] #{})
                 (adjust-interface [this snapshot] (f snapshot))
                 (handle-control-change [this message]
                   (when (zero? (:velocity message))
                     :done))
                 (handle-note-off [this message])
                 (handle-note-on [this message])
                 (handle-aftertouch [this message]))))

(defn run-overlays
  "Add any contributions from interface overlays, removing them if
  they report being finished. Most recent overlays run last, having
  the opportunity to override older ones. `state` must be a value
  created by [[create-overlay-state]] and tracked by the controller.
  The `snapshot` is an [[IMetroSnapshot]] that captures the instant in
  time at which the interface is being rendered, and is passed in to
  the overlay so it can be rendered in sync with all other interface
  elements."
  [state snapshot]
  (doseq [[k overlay] (reverse (:overlays @state))]
      (when-not (adjust-interface overlay snapshot)
        (swap! state update-in [:overlays] dissoc k))))

(defn overlay-handled?
  "See if there is an interface overlay active which wants to consume
  this message; if so, send it, and see if the overlay consumes it.
  Returns truthy if an overlay consumed the message, and it should not
  be given to anyone else. `state` must be a value created
  by [[create-overlay-state]] and tracked by the controller."
  [state message]
  (case (:command message)
    (:note-on :note-off :poly-pressure)
    (some (fn [[k overlay]]
            (when (contains? (captured-notes overlay) (:note message))
              (let [result (case (:command message)
                             :note-on (handle-note-on overlay message)
                             :note-off (handle-note-off overlay message)
                             :poly-pressure (handle-aftertouch overlay message))]
                (when (= result :done)
                  (swap! state update-in [:overlays] dissoc k))
                result)))
          (seq (:overlays @state)))

    :control-change
    (some (fn [[k overlay]]
            (when (contains? (captured-controls overlay) (:note message))
              (let [result (handle-control-change overlay message)]
                (when (= result :done)
                  (swap! state update-in [:overlays] dissoc k))
                result)))
          (seq (:overlays @state)))

    ;; Nothing we process
    false))

(defonce ^{:private true
           :doc "Keeps track of all controller elements that should
  receive beat feedback. A map whose keys are a tuple of [midi-device
  channel note kind], and whose values are [metronome on-value
  off-value device-map disconnect-handler]."} beat-feedback
  (atom {}))

(defonce ^{:private true
           :doc "When any devices are requesting beat feedback, contains
  a task which sends their MIDI messages."}
  beat-task
  (atom nil))

(def beat-refresh-interval
  "How often, in milliseconds, are the controllers requesting beat
  feedback refreshed."
  (/ 1000 30))

(defonce ^{:private true
           :doc "Keeps track of the most recent marker seen for each
  beat mapping, so we can tell if this is a new beat."}
  last-marker
  (atom {}))

(defn- new-beat?
  "Returns true if the metronome is reporting a different marker
  position than the last time this function was called."
  [entry marker]
  (when (not= marker (get @last-marker entry))
    (swap! last-marker assoc entry marker)))

(defn- give-beat-feedback
  "Called periodically to send feedback to non-grid controllers
  requesting flashes driven by metronome beats."
  []
  (doseq [[entry [metronome on off device]] @beat-feedback]
    (let [[_ channel note kind] entry
          marker (rhythm/metro-marker metronome)
          bright (or (new-beat? entry marker) (< (rhythm/metro-beat-phase metronome) 0.15))]
      (if (= :control kind)
        (midi/midi-control device note (if bright on off) channel)
        (if bright
          (midi/midi-note-on device note on channel)
          (midi/midi-note-off device note channel))))))

(defn- update-beat-task
  "Checks whether the beat refresh task is currently needed (if there
  are any controllers registered to receive beat feedback), whether it
  is currently running, and starts or ends it accordingly."
  []
  (swap! beat-task (fn [current]
                     (if (empty? @beat-feedback)
                       (when current (at-at/kill current) nil)  ; Should not be running; kill if it was.
                       (or current  ; Should be running, return if it is, or start it.
                           (at-at/every beat-refresh-interval give-beat-feedback pool
                                        :desc "Metronome beat feedback update"))))))

(defn clear-beat-feedback!
  "Ceases flashing the specified non-grid MIDI controller element
  on beats of the specified metronome."
  [metronome device channel kind note]
  {:pre [(satisfies? rhythm/IMetronome metronome) (= (type device) :midi-device) (#{:control :note} kind)]}
  (let [entry [(:device device) channel note kind]
        [_ _ off _ disconnect-handler] (get @beat-feedback entry)]
    (swap! beat-feedback dissoc entry)
    (update-beat-task)
    (when (some? disconnect-handler)
      (amidi/remove-disconnected-device-handler! device disconnect-handler))
    (when off ; We were giving feedback, make sure we leave the LED in an off state
      (if (= :control kind)
                (midi/midi-control device note off channel)
                (midi/midi-note-off device note channel))))
  nil)

(defn add-beat-feedback!
  "Arranges for the specified non-grid MIDI controller to receive
  feedback events to make a particular LED flash on each beat of
  the specified metronome."
  [metronome device channel kind note & {:keys [on off] :or {on 127 off 0}}]
  {:pre [(satisfies? rhythm/IMetronome metronome) (= (type device) :midi-device)
         (#{:control :note} kind) (integer? on) (integer? off) (<= 0 on 127) (<= 0 off 127)]}
  (clear-beat-feedback! metronome device channel kind note)  ; In case there was an already in effect
  (letfn [(disconnect-handler []
            (clear-beat-feedback! metronome device channel kind note))]
    (amidi/add-disconnected-device-handler! device disconnect-handler)
    (swap! beat-feedback assoc [(:device device) channel note kind]
           [metronome on off device disconnect-handler]))
  (update-beat-task)
  nil)

