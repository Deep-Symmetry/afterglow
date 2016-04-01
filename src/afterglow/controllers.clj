(ns afterglow.controllers
  "Provides shared services for all controller implementations."
  {:author "James Elliott"}
  (:require [overtone.at-at :as at-at]
            [overtone.midi :as midi]
            [afterglow.midi :as amidi]
            [afterglow.rhythm :as rhythm]
            [taoensso.timbre :as timbre]
            [taoensso.truss :as truss :refer [have have! have?]])
  (:import [java.util.concurrent LinkedBlockingDeque]))

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

(defonce ^:private ^{:doc "The queue used to serialize auto-binding
  attempts, so that only one happens at a time, without backing up the
  main MIDI event processing queue. Although we allocate a capacity of
  100 elements, it is expected that the queue will be empty most of
  the time, and only collect an input port or two when a new device is
  connected, and all input ports briefly when [[auto-bind]] is
  invoked."}
  bind-queue
  (LinkedBlockingDeque. 100))

(declare bind-to-show)

(defonce ^:private ^{:doc "The thread used to run [[bind-bottleneck]]
  and serialize the auto-binding of potential controllers that have
  been found."}
  auto-bind-thread
  (atom nil))

(defn- bind-bottleneck
  "Takes potential auto-bind devices from the incoming queue one by
  one and tries binding to them. This is used to make sure that we are
  only trying one device at a time, taking as long as it needs,
  without backing up the main MIDI event handler thread.

  Entries on the cue are a tuple containing the device which has been
  identified as a potential new controller port, the show to which it
  should be bound if it turns out to be suitable, and any optional
  arguments which should be passed along to [[bind-to-show]].

  If the special flag value `:done` is passed as the third element in
  the tuple, it signals the loop to end, because auto-binding has been
  canceled."
  []
  (loop [[device show args] (.take bind-queue)]
    (when device
      (timbre/info "Attempting auto-binding to potential controller" device args)
      (try
        (apply bind-to-show (concat [show (fn [port]
                                            (and (= (:name port) (:name device))
                                                 (= (:description port) (:description device))))]
                                    (flatten (seq args))))
        (catch Exception e
          (timbre/error e "Problem binding to controller"))))

    ;; Wait for the next candidate device, unless we have been told to end
    (when-not (= args :done)
      (recur (.take bind-queue))))
  (swap! auto-bind-thread (constantly nil)))

(defn- start-auto-bind-thread
  "Creates the thread used to serialize auto-bind attempts."
  [old-thread]
  (or old-thread
      (doto (Thread. bind-bottleneck "auto-bind bottleneck")
        (.setDaemon true)
        (.start))))

(defonce ^{:private true
           :doc "Lists all the built-in controller implementations
           that need to be loaded, so they can register their
           recognizers. This needs to happen after the namespace
           itself loads, to avoid circular dependency issues. Once
           that is done, this atom will be set to `nil`. Functions
           like [[bind-to-show]] and [[auto-bind]] which rely on
           controller implementations being registered must call
           [[load-built-in-controllers]] to ensure this has
           happened."}
  built-in-controllers
  (atom '[afterglow.controllers.ableton-push afterglow.controllers.ableton-push-2
          afterglow.controllers.launchpad-pro afterglow.controllers.launchpad-mk2
          afterglow.controllers.launchpad-mini]))

(defn- load-built-in-controllers
  "Makes sure that the namespaces defining the grid controllers that
  Afterglow supports natively have been loaded. Because of circular
  dependency issues, this has to wait until after the namespace itself
  has been loaded. Functions like [[bind-to-show]] and [[auto-bind]]
  which rely on controller implementations need to call this function
  to ensure loading has occurred. After the first call, the list of
  namespaces will be replaced by `nil`, so it will no longer do
  anything."
  []
  (swap! built-in-controllers
         (fn [namespaces]
           (doseq [n namespaces]
             (require n)))))

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
  is finished and should be removed.")
  (handle-pitch-bend [this message]
  "Called when a MIDI pitch-bend event has been received. Return a
  truthy value if the overlay has consumed the event, so it should not
  be processed further. If the special value `:done` is returned, it
  further indicates the overlay is finished and should be
  removed."))))

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

     ;; Track saved cue variables to be reapplied the next time the cue is run.
     ;; A map indexed in the same way as :cues, whose values are the map of
     ;; variable keys and values.
     :saved-vars (ref {})
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
  cue grid. If one was there, updates the grid dimensions if needed.
  Also clears any variables that were saved for that cue."
  [grid x y]
  {:pre [(have? integer? x) (have? integer? y) (have? #(not (neg? %)) x) (have? #(not (neg? %)) y)
         (have? #(= (type %) :cue-grid) grid)]}
  (dosync
   (when (some? (get @(:cues grid) [x y]))
     (alter (:cues grid) dissoc [x y])
     (when (or (= x (grid-width grid)) (= y (grid-height grid)))
       (ref-set (:dimensions grid) (reduce (fn [[width height] [x y]]
                                             [(max width x) (max height y)])
                                           [0 0] (keys @(:cues grid)))))
     (alter (:saved-vars grid) dissoc [x y]))))

(defn set-cue!
  "Puts the supplied cue at the specified coordinates in the cue grid.
  Replaces any cue which formerly existed at that location, and clears
  any variables that might have been saved for it. If `cue` is nil,
  delegates to clear-cue!"
  [grid x y cue]
  {:pre [(have? integer? x) (have? integer? y) (have? #(not (neg? %)) x) (have? #(not (neg? %)) y)
         (have? #(= (type %) :cue-grid) grid)]}
  (if (nil? cue)
    (clear-cue! grid x y)
    (dosync
     (alter (:cues grid) assoc [x y] cue)
     (alter (:saved-vars grid) dissoc [x y])
     (ref-set (:dimensions grid) [(max x (grid-width grid)) (max y (grid-height grid))]))))

(defn save-cue-vars!
  "Save a set of variable starting values to be applied when launching
  a cue. If there is no cue at the specified grid location, nothing
  will be saved."
  [grid x y vars]
  (dosync
   (when (some? (get (ensure (:cues grid)) [x y]))
         (alter (:saved-vars grid) assoc [x y] vars))))

(defn clear-saved-cue-vars!
  "Remove any saved starting values assigned to the cue at the
  specified grid location."
  [grid x y]
  (dosync
   (alter (:saved-vars grid) dissoc [x y])))

(defn cue-vars-saved-at
  "Return the saved starting values, if any, assigned to the cue at
  the specified grid location."
  [grid x y]
  (get @(:saved-vars grid) [x y]))

(defn clear-cue-feedback!
  "Ceases sending the specified non-grid MIDI controller feedback
  events when the cue grid location activates or deactivates,
  returning the feedback values that had been in place if there were
  any."
  [grid x y device channel kind note]
  {:pre [(have? integer? x) (have? integer? y) (have? #(not (neg? %)) x) (have? #(not (neg? %)) y)
         (have? #(= (type %) :midi-device) device) (have? #(= (type %) :cue-grid) grid)
         (have? #{:control :note} kind)]}
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
  {:pre [(have? integer? x) (have? integer? y) (have? #(not (neg? %)) x) (have? #(not (neg? %)) y)
         (have? #(= (type %) :midi-device) device) (have? #(= (type %) :cue-grid))
         (have? #{:control :note} kind) (have? integer? on) (have? integer? off)
         (have? #(<= 0 % 127) on) (have? #(<= 0 % 127) off)]}
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
  {:pre [(have? integer? x) (have? integer? y) (have? #(not (neg? %)) x) (have? #(not (neg? %)) y)
         (have? ifn? f) (have? #(= (type %) :cue-grid) grid)]}
  (dosync
   ;; Consider putting the actual cue as the value, and logging a warning and ending the feedback
   ;; if a different cue gets stored there? Probabyl not.
   (alter (:fn-feedback grid) assoc-in [[x y] f] true))
  nil)

(defn clear-cue-fn!
  "Ceases calling the supplied function when the cue grid location
  activates or deactivates."
  [grid x y f]
  {:pre [(have? integer? x) (have? integer? y) (have? #(not (neg? %)) x) (have? #(not (neg? %)) y)
         (have? #(ifn? %) f) (have? #(= (type %) :cue-grid) grid)]}
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
  {:pre [(have? integer? x) (have? integer? y) (have? #(not (neg? %)) x) (have? #(not (neg? %)) y)
         (have? #(= (type %) :cue-grid) grid)]}
  (dosync
   (when-let [cue (cue-at grid x y)]
     (let [former-id (:active-id cue)]
       (dosync  ;; Update the active-id value in the cue
        (alter (:cues grid) assoc [x y] (if (some? id)
                                          (assoc cue :active-id id)
                                          (dissoc cue :active-id))))
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
  {:pre [(have? integer? x) (have? integer? y) (have? #(not (neg? %)) x) (have? #(not (neg? %)) y)
         (have? #(= (type %) :cue-grid) grid)]}
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
         :overlays (sorted-map)}))

(defn add-overlay
  "Add a temporary overlay to the interface. The `state` argument must
  be a value created by [[create-overlay-state]], and `overlay` an
  implementation of the [[IOverlay]] protocol to be added as the most
  recent overlay to that state. The optional keyword argument
  `:priority` can be used to modify the sorting order of overlays,
  which is that more recent ones run later, so they get the last word
  when it comes to rendering. The default priority is `0`."
  [state overlay & {:keys [priority] :or {priority 0}}]
  (swap! state (fn [old-state]
                 (let [id (:next-id old-state)
                       overlays (:overlays old-state)]
                   (assoc old-state :next-id (inc id) :overlays (assoc overlays [priority id] overlay))))))

(defn add-control-held-feedback-overlay
  "Builds a simple overlay which just adds something to the user
  interface until a particular control (identified by `control-num`)
  is released, and adds it to the controller. The overlay will end
  when a control-change message with value 0 is sent to the specified
  control number. Other than that, all it does is call the supplied
  function every time the interface is being updated, passing it the
  metronome snapshot which represents the moment at which the
  interface is being drawn. If the function returns a falsey value,
  the overlay will be ended.

  As with [[add-overlay]], `state` must be a value created
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
  they report being finished. Most recent and higher priority overlays
  run last, having the opportunity to override older ones. `state`
  must be a value created by [[create-overlay-state]] and tracked by
  the controller. The `snapshot` is an [[IMetroSnapshot]] that
  captures the instant in time at which the interface is being
  rendered, and is passed in to the overlay so it can be rendered in
  sync with all other interface elements."
  [state snapshot]
  (doseq [[k overlay] (:overlays @state)]
      (when-not (adjust-interface overlay snapshot)
        (swap! state update-in [:overlays] dissoc k))))

(defn overlay-handled?
  "See if there is an interface overlay active which wants to consume
  this message; if so, send it, and see if the overlay consumes it.
  Returns truthy if an overlay consumed the message, and it should not
  be given to anyone else. `state` must be a value created
  by [[create-overlay-state]] and tracked by the controller.

  More recent (and higher priority) overlays get the first chance to
  decide if they want to consume the message, so the overlay list is
  traversed in reverse order."
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
          (rseq (:overlays @state)))

    :control-change
    (some (fn [[k overlay]]
            (when (contains? (captured-controls overlay) (:note message))
              (let [result (handle-control-change overlay message)]
                (when (= result :done)
                  (swap! state update-in [:overlays] dissoc k))
                result)))
          (seq (:overlays @state)))

    :pitch-bend
    (some (fn [[k overlay]]
            (let [result (handle-pitch-bend overlay message)]
              (when (= result :done)
                (swap! state update-in [:overlays] dissoc k))
              result))
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
  {:pre [(have? #(satisfies? rhythm/IMetronome %) metronome)
         (have? #(= (type %) :midi-device) device) (have? #{:control :note} kind)]}
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
  {:pre [(have? #(satisfies? rhythm/IMetronome %) metronome)
         (have? #(= (type %) :midi-device) device)
         (have? #{:control :note} kind) (have? integer? on) (have? integer? off)
         (have? #(<= 0 % 127) on) (have? #(<= 0 % 127) off)]}
  (clear-beat-feedback! metronome device channel kind note)  ; In case there was an already in effect
  (letfn [(disconnect-handler []
            (clear-beat-feedback! metronome device channel kind note))]
    (amidi/add-disconnected-device-handler! device disconnect-handler)
    (swap! beat-feedback assoc [(:device device) channel note kind]
           [metronome on off device disconnect-handler]))
  (update-beat-task)
  nil)

(defn identify
  "Sends a MIDI Device Inquiry message to the specified device and
  returns the response, waiting for up to a second, and trying up to
  three times if no response is received in that second. If the third
  attempt fails, returns `nil`."
  [port-in port-out]
  (let [result (promise)
        handler (fn [msg] (deliver result msg))]
    (try
      (amidi/add-sysex-mapping port-in handler)
      (loop [attempts 0]
        (overtone.midi/midi-sysex port-out [240 126 127 6 1 247])
        (let [found (deref result 1000 nil)]
          (or found (when (< attempts 2) (recur (inc attempts))))))
      (finally (amidi/remove-sysex-mapping port-in handler)))))

(defmulti bind-to-show-impl
  "Establish a rich user-interface binding on a supported grid
  controller for `show`. A multimethod which selects the appropriate
  implementation based on passing the value returned by [[identify]]
  to all functions registered in [[recognizers]]. The port used to
  receive MIDI messages from the controller is passed as `port-in`,
  and the port used to send messages to it is passed as `port-out`.

  New controller binding implementations simply need to define an
  appropriate implementation of this multimethod, and add their
  recognition function to [[recognizers]].

  All rich controller binding implementations should honor the
  `:display-name` and `:refresh-interval` optional keyword arguments
  described in [[bind-rich-controller]]. They may also support
  additional optional keyword arguments specific to the details of
  their implementation, which the caller can supply when they know
  they are binding to such a controller."
  (fn [kind show port-in port-out recognizer-result & args]
    kind))

(defonce ^{:doc "A map whose keywords are dispatch values registered
  with [[bind-to-show-impl]] and whose values are functions which are
  called with three arguments: The [[identify]] response for a
  controller and the MIDI input and output ports it registered. The
  recognizer functions examine the device's [[identify]] response and
  ports, if the device is recognized as a controller which is
  supported by the particular binding implementation associated with
  the dispatch keyword, return the binding information needed by their
  controller implementations to complete a binding to that device. In
  other words, non-`nil` responses mean the corresponding dispatch
  value, function result, and input and output ports should be used
  with [[bind-to-show-impl]] to establish a binding with that
  controller.

  New controller binding implementations simply need to add an
  appropriate implementation of that multimethod, and register their
  recognition function in this map."}
  recognizers (atom {}))

(defonce ^{:doc "Controllers which are currently bound to shows must
  register themselves here. All controllers in this set will be passed
  to [[deactivate]] when [[deactivate-all]] is called, and when the
  JVM is shutting down, to gracefully terminate those bindings."
           :private true}
  active-bindings-atom (atom #{}))

(defn add-active-binding
  "Registers a controller which has been bound to a show, and which
  should be deactivated when [[deactivate-all]] is called or the JVM
  is shutting down, to gracefully clean up the binding."
  [controller]
  {:pre [(have? some? controller) (have? keyword? (type controller))]}
  (swap! active-bindings-atom conj controller))

(defn remove-active-binding
  "Removes a controller from the set of active bindings, so it will no
  longer be deactivated when [[deactivate-all]] is called or the JVM
  is shutting down."
  [controller]
  {:pre [(have? some? controller) (have? keyword? (type controller))]}
  (swap! active-bindings-atom disj controller))

(defn active-bindings
  "Returns the set of controllers which are currently bound to
  shows."
  []
  @active-bindings-atom)

(defn- already-bound?
  "Checks whether the specified input port is associated with any
  currently active controller binding."
  [port-in]
  (some (partial amidi/same-device? port-in) (map :port-in (active-bindings))))

(defn bind-to-show
  "Establish a rich user-interface binding on a supported grid
  controller for `show`.

  To locate the controller that you want to bind to, you need to
  supply a `device-filter` which uniquely matches the ports to be used
  to communicate with it. The values returned
  by [[afterglow.midi/open-inputs-if-needed!]]
  and [[afterglow.midi/open-outputs-if-needed!]] will be searched, and
  the first port that matches with [[filter-devices]] will be used.
  There must be both an input and output matching the filter for the
  binding to succeed.

  The controller binding implementation chosen will be determined by
  calling [[identify]] with the ports found, and seeing which member
  of [[recognizers]] recognizes the result and returns a non-null
  value. The corresponding dispatch key will be used with the result
  value to call [[bind-to-show-impl]] with the ports and other
  arguments, and it will do the appropriate things to work with the
  controller that was found.

  All rich controller binding implementations accept a couple of
  standard optional keyword arguments to adjust their behavior. The
  controller will be identified in the user interface (for the
  purposes of linking it to the web cue grid) with a default name
  based on its type (for example \"Ableton Push\"). If you would like
  to use a different name (for example, if you are lucky enough to
  have more than one Push), you can pass in a custom value after
  the optional keyword argument `:display-name`.

  If you want the user interface to be refreshed at a different rate
  than the default for the controller type, pass your desired number
  of milliseconds after `:refresh-interval`.

  If you are binding to a specific controller type whose mapping
  accepts other optional keyword arguments, you can include them as
  well, and they will be passed on to the binding implementation
  function.

  If the controller was bound, the binding will be returned, and you
  can later call [[deactivate]] with it. If the binding failed, `nil`
  will be returned, and there will be messages explaining why in the
  log file."
  [show device-filter & {:keys [auto-binding] :as args}]
  {:pre [(some? show) (some? device-filter)]}
  (load-built-in-controllers)  ; Make sure controller implementations are registered
  (let [port-in  (amidi/find-midi-in device-filter false)
        port-out (amidi/find-midi-out device-filter false)
        ident (when (every? some? [port-in port-out]) (identify port-in port-out))]
    (if (already-bound? port-in)
      (timbre/info "Not binding controller to show, it already has an active binding:" port-in)
      (if ident
        (loop [candidates (seq @recognizers)]
          (if (seq candidates)
            (let [[dispatch recognizer] (first candidates)]
              (if-let [recognizer-result (recognizer ident port-in port-out)]
                (let [args (vec (flatten (seq (dissoc args :auto-binding))))]
                  (timbre/info "Binding controller type" dispatch "to" port-in "with args" args)
                  (apply bind-to-show-impl (concat [dispatch show port-in port-out recognizer-result]
                                                   args)))
                (recur (rest candidates))))
            (when-not auto-binding
              (timbre/warn "Unrecognized controller" (amidi/describe-device-filter device-filter)))))
        (when-not auto-binding
          (timbre/warn "Unable to find rich controller" (amidi/describe-device-filter device-filter)))))))

(defmulti deactivate
  "Deactivates a controller binding established by [[bind-to-show]].
   If `:disconnected` is passed with a
  `true` value, it means that the controller has already been removed
  from the MIDI environment, so no effort will be made to clear its
  display or take it out of User mode.

  The implementation of this multimethod is chosen by using the
  `:type` key in the `controller` map as the dispatch value, so rich
  controller implementations simply need to register their own
  implementations appropriately when their namespaces are loaded."
  (fn [controller & {:keys [disconnected] :or {disconnected false}}]
    (type controller)))

(defn deactivate-all
  "Deactivates all controller bindings which are currently active.
  This will be registered as a shutdown hook to be called when the
  Java environment is shutting down, to clean up gracefully."
  []
  (doseq [controller @active-bindings-atom]
    (deactivate controller)))

(defonce ^{:doc "Deactivates any registered controller bindings when
  Java is shutting down."
           :private true}
  shutdown-hook
  (let [hook (Thread. deactivate-all)]
    (.addShutdownHook (Runtime/getRuntime) hook)
    hook))

(defonce ^{:doc "The MIDI new device handler which will inspect all
  new devices when [[auto-bind]] is active. `nil` if [[auto-bind]] has
  never been enabled, or [[cancel-auto-bind]] has been called."
           :private true}
  auto-bind-handler (atom nil))

(defn auto-bind
  "Watches for a recognized grid controller to be connected, and as
  soon as it is, binds it to the specified show
  using [[bind-to-show]]. If that controller ever gets disconnected,
  it will be re-bound once it reappears.

  If you would like to limit the controllers that Afterglow will
  automatically bind to, you may supply a filter with the optional
  keyword argument `:device-filter` which uniquely matches the MIDI
  ports used by the devices you want to be auto-bound. The values
  returned by [[afterglow.midi/open-inputs-if-needed!]]
  and [[afterglow.midi/open-outputs-if-needed!]] will be searched, and
  any pair of identically-named ports that are accepted by your
  `:device-filter` with [[filter-devices]] will be bound if they are
  attached to a controller type that Afterglow knows how to work with.

  The controller binding implementation chosen will be determined by
  calling [[identify]] with the ports found, and seeing which member
  of [[recognizers]] recognizes the result and returns a dispatch
  value. That value will be used to call [[bind-to-show-impl]] with the
  ports and other arguments, and it will do the appropriate things to
  work with the controller that was found.

  If you are watching for a specific controller type whose mapping
  accepts other optional keyword arguments, you can include them as
  well, and they will be passed along to [[bind-to-show]] when it is
  detected.

  All rich controller binding implementations accept a couple of
  standard optional keyword arguments to adjust their behavior. The
  controller will be identified in the user interface (for the
  purposes of linking it to the web cue grid) with a default name
  based on its type (for example \"Ableton Push\"). If you would like
  to use a different name (for example, if you are lucky enough to
  have more than one Push), you can pass in a custom value after
  the optional keyword argument `:display-name`.

  If you want the user interface to be refreshed at a different rate
  than the default for the controller type, pass your desired number
  of milliseconds after `:refresh-interval`.

  Controllers which perform their own startup animation, or which need
  to be given extra time to become ready after their ports appear in
  the MIDI environment can look for the optional keyword argument
  `:new-connection` to indicate the device has just been connected,
  and react appropriately."
  [show & {:keys [device-filter] :as args}]
  {:pre [(have? some? show)]}
  (load-built-in-controllers)  ; Make sure controller implementations are registered
  (swap! auto-bind-thread start-auto-bind-thread)  ; Make sure the auto-bind thread is running
  (letfn [(connection-handler
            ([device]
             (connection-handler device true))
            ([device new-connection]
             (if (and (pos? (:sources device))  ; Respond only to input ports, since controllers will have both
                      (seq (amidi/filter-devices device-filter [device]))  ; Apply any user-desired filter
                      (not (already-bound? device)))  ; Ignore devices that already have active bindings
               ;; Looks good, queue it for an auto-bind attempt
               (.add bind-queue [device show (merge (dissoc args :device-filter)
                                                    {:auto-binding   true
                                                     :new-connection new-connection})]))))]

    ;; See if any eligible controller seems to already be connected, and if so, try bind to it right away.
    (doseq [found (amidi/filter-devices device-filter (amidi/open-inputs-if-needed!))]
      (when-not (already-bound? found) (connection-handler found false)))

    ;; Set up to bind when connected in future, in the process shutting down any former auto-binding
    ;; that was in place.
    (swap! auto-bind-handler (fn [former-handler]
                               (when former-handler
                                 (amidi/remove-new-device-handler! former-handler))
                               connection-handler))
    (amidi/add-new-device-handler! connection-handler)))

(defn cancel-auto-bind
  "Stop any [[auto-bind]] which may be in effect."
  []
  (swap! auto-bind-handler (fn [former-handler]  ; Stop noticing new device connections
                             (when former-handler
                               (amidi/remove-new-device-handler! former-handler))
                             nil))
  (.add bind-queue [nil nil :done]))  ; Tell the auto-bind thread to shut down
