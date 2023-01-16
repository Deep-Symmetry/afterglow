(ns afterglow.controllers.launchpad-mini
  "Allows the Novation Launchpad Mini and Launchpad S to be used as
  control surfaces for Afterglow."
  {:author "James Elliott"}
  (:require [afterglow.controllers :as controllers]
            [afterglow.controllers.tempo :as tempo]
            [afterglow.midi :as amidi]
            [afterglow.rhythm :as rhythm]
            [afterglow.show :as show]
            [afterglow.show-context :refer [with-show]]
            [overtone.at-at :as at-at]
            [overtone.midi :as midi]
            [taoensso.timbre :as timbre :refer [warn]]
            [taoensso.truss :as truss :refer [have have! have?]]))

(def control-buttons
  "The round buttons along the top which send and respond to Control
  Change events."
  {:up-arrow    104
   :down-arrow  105
   :left-arrow  106
   :right-arrow 107
   :session     108
   :user-1      109
   :user-2      110
   :mixer       111})

(def note-buttons
  "The round buttons along the right which send and respond to Note
  events."
  {:volume     8  ; Used as tap tempo
   :pan        24
   :send-a     40
   :send-b     56
   :stop       72
   :track-on   88
   :solo       104
   :record-arm 120})  ; Used as shift

(def button-off-color
  "The color of buttons that are completely off."
  0x0c)

(def button-dimmed-color
  "The color of buttons that can be pressed but are in conflict or
  otherwise backgrounded."
  0x1d)

(def button-available-color
  "The color of buttons that can be pressed but haven't yet been."
  0x2e)

(def button-active-color
  "The color of an available button that is currently being pressed."
  0x3f)

(def stop-available-color
  "The color of the Stop button when not active."
  0x0d)

(def stop-active-color
  "The color of the Stop button when active."
  0x0f)

(def shift-available-color
  "The color of the Shift button when not active."
  0x2d)

(def shift-active-color
  "The color of the Shift button when active."
  0x3e)

(def tempo-unsynced-beat-color
  "The color of the tap tempo button when synchronization is off and a
  beat is taking place."
  0x3e)

(def tempo-unsynced-off-beat-color
  "The color of the tap tempo button when synchronization is off and a
  beat is not taking place."
  0x1d)

(def tempo-synced-beat-color
  "The color of the tap tempo button when the metronome is
  synchronzied and a beat is taking place."
  0x3c)

(def tempo-synced-off-beat-color
  "The color of the tap tempo button when the metronome is
  synchronized and a beat is not taking place."
  0x1c)

(def cue-held-color
  "The color to light up a pad when a cue runs only as long as it is
  held."
  0x3f)

(def cue-running-color
  "The color to light up a pad when it is running a cue."
  0x3c)

(defn set-pad-color
  "Set one of the 64 grid pads to one of the color values specified
  above."
  [controller x y color]
  (midi/midi-note-on (:port-out controller) (+ x (- 112 (* y 16))) color))

(defn set-control-button-color
  "Set one of the top row of control buttons to one of the color
  values specified above."
  [controller button color]
  (midi/midi-control (:port-out controller) button color))

(defn set-note-button-color
  "Set one of the right hand row of note buttons to one of the color
  values specified above."
  [controller button color]
  (midi/midi-note-on (:port-out controller) button color))

(defn- move-origin
  "Changes the origin of the controller, notifying any registered
  listeners."
  [controller origin]
  (when (not= origin @(:origin controller))
    (reset! (:origin controller) origin)
    (doseq [f @(:move-listeners controller)] (f @(:grid-controller-impl controller) :moved))))

(defn show-round-buttons
  "Illuminates all round buttons."
  ([controller]
   (show-round-buttons controller button-available-color))
  ([controller color]
   (doseq [[_ button] control-buttons]
     (set-control-button-color controller button color))
   (doseq [[_ button] note-buttons]
     (set-note-button-color controller button color))))

(defn clear-interface
  "Clears all illuminated buttons and pads."
  [controller]
  (midi/midi-control (:port-out controller) 0 0)
  (midi/midi-control (:port-out controller) 0 1)
  (reset! (:last-grid-pads controller) nil)  ; Note that no buttons are lit
  (reset! (:last-control-buttons controller) {})
  (reset! (:last-note-buttons controller) {}))

(defn- update-round-buttons
  "Sees if any round buttons have changed state since the last time
  the interface was updated, and if so, sends the necessary MIDI
  commands to update them on the Launchpad Mini."
  [controller]
  ;; First turn off any which were on before but no longer are
  (doseq [[button old-color] @(:last-control-buttons controller)]
    (when-not (get @(:next-control-buttons controller) button)
      (set-control-button-color controller button button-off-color)))
  (doseq [[button old-color] @(:last-note-buttons controller)]
    (when-not (get @(:next-note-buttons controller) button)
      (set-note-button-color controller button button-off-color)))

  ;; Then, light any currently active buttons
  (doseq [[button color] @(:next-control-buttons controller)]
    (when-not (= (get @(:last-control-buttons controller) button) color)
      (set-control-button-color controller button color)))
  (doseq [[button color] @(:next-note-buttons controller)]
    (when-not (= (get @(:last-note-buttons controller) button) color)
      (set-note-button-color controller button color)))

  ;; And record the new state for next time
  (reset! (:last-control-buttons controller) @(:next-control-buttons controller))
  (reset! (:last-note-buttons controller) @(:next-note-buttons controller)))

(defn- render-cue-grid
  "Figure out how the cue grid pads should be illuminated, based on
  the currently active cues and a metronome snapshot identifying the
  point in musical time at which the interface is being rendered.
  Returns a vector of the rows, from bottom to top. Each row is a
  vector of the colors in that row, from left to right."
  [controller snapshot]
  (let [[origin-x origin-y] @(:origin controller)
        active-keys (show/active-effect-keys (:show controller))]
    (vec (for [y (range 8)]
           (vec (for [x (range 8)]
                  (let [[cue active] (show/find-cue-grid-active-effect (:show controller) (+ x origin-x) (+ y origin-y))
                        ending (and active (:ending active))
                        color (when cue (if active
                                          (if ending
                                            (if (> (rhythm/snapshot-beat-phase snapshot) 0.4)
                                              stop-available-color
                                              stop-active-color)
                                            cue-running-color)
                                          (if (or (active-keys (:key cue))
                                                  (seq (clojure.set/intersection active-keys (set (:end-keys cue)))))
                                            button-dimmed-color button-available-color)))]
                    (or color button-off-color))))))))

(defn- update-cue-grid
  "Set the colors of all the cue grid pads, based on the currently
  active cues and overlays."
  [controller]
  (doseq [x (range 8)  ; Send the changes only, individually
              y (range 8)]
        (let [color (get-in @(:next-grid-pads controller) [y x])]
          (when-not (= (get-in @(:last-grid-pads controller) [y x]) color)
            (set-pad-color controller x y color)))))

(defn- update-scroll-arrows
  "Activate the arrow buttons for directions in which scrolling is
  possible."
  [controller]
  (let [[origin-x origin-y] @(:origin controller)]
    (when (pos? origin-x)
      (swap! (:next-control-buttons controller) assoc (:left-arrow control-buttons) button-available-color))
    (when (pos? origin-y)
      (swap! (:next-control-buttons controller) assoc (:down-arrow control-buttons) button-available-color))
    (when (> (- (controllers/grid-width (:cue-grid (:show controller))) origin-x) 7)
      (swap! (:next-control-buttons controller) assoc (:right-arrow control-buttons) button-available-color))
    (when (> (- (controllers/grid-height (:cue-grid (:show controller))) origin-y) 7)
      (swap! (:next-control-buttons controller) assoc (:up-arrow control-buttons) button-available-color))))

(defn add-button-held-feedback-overlay
  "Adds a simple overlay which keeps a control button bright as long
  as the user is holding it down."
  ([controller button]
   (add-button-held-feedback-overlay controller button button-active-color))
  ([controller button color]
   (controllers/add-control-held-feedback-overlay (:overlays controller) button
                                                  (fn [_] (swap! (:next-control-buttons controller)
                                                                 assoc button color)))))

(defn- enter-stop-mode
  "The user has asked to stop the show. Suspend its update task
  and black it out until the stop button is pressed again."
  [controller & {:keys [already-stopped]}]

  (reset! (:stop-mode controller) true)
  (when-not already-stopped
    (with-show (:show controller)
      (show/stop!)
      (Thread/sleep (:refresh-interval (:show controller)))
      (show/blackout-show)))

  (controllers/add-overlay (:overlays controller)
                           (reify controllers/IOverlay
                             (captured-controls [this] #{})
                             (captured-notes [this] #{(:stop note-buttons)})
                             (adjust-interface [this _]
                               (swap! (:next-note-buttons controller)
                                      assoc (:stop note-buttons) stop-active-color)
                               (with-show (:show controller)
                                 (when (show/running?)
                                   (reset! (:stop-mode controller) false))
                                 @(:stop-mode controller)))
                             (handle-control-change [this message])
                             (handle-note-on [this message]
                               (when (pos? (:velocity message))
                                 ;; End stop mode
                                 (with-show (:show controller)
                                   (show/start!))
                                 (reset! (:stop-mode controller) false)
                                 :done))
                             (handle-note-off [this message])
                             (handle-aftertouch [this message])
                             (handle-pitch-bend [this message]))))

(defn- new-beat?
  "Returns true if the metronome is reporting a different marker
  position than the last time this function was called."
  [controller marker]
  (when (not= marker @(:last-marker controller))
    (reset! (:last-marker controller) marker)))

(defn- metronome-sync-colors
  "Determine the colors to light the tap tempo button. Returns a tuple
  of the off-beat and on-beat colors based on the current sync
  status."
  [controller]
  (with-show (:show controller)
    (if (= (:type (show/sync-status)) :manual)
      [tempo-unsynced-off-beat-color tempo-unsynced-beat-color]
      (if (:current (show/sync-status))
        [tempo-synced-off-beat-color tempo-synced-beat-color]
        [stop-available-color stop-active-color]))))

(defn- update-interface
  "Determine the desired current state of the interface, and send any
  changes needed to get it to that state."
  [controller]
  (try
    ;; Assume we are starting out with a blank interface.
    (reset! (:next-control-buttons controller) {})
    (reset! (:next-note-buttons controller) {})

    ;; If the show has stopped without us noticing, enter stop mode
    (with-show (:show controller)
      (when-not (or (show/running?) @(:stop-mode controller))
        (enter-stop-mode controller :already-stopped true)))

    ;; Reflect the shift button state
    (swap! (:next-note-buttons controller)
           assoc (:record-arm note-buttons)
           (if @(:shift-mode controller) shift-active-color shift-available-color))

    (update-scroll-arrows controller)

    ;; Flash the tap tempo button on beats
    (let [snapshot (rhythm/metro-snapshot (get-in controller [:show :metronome]))
          marker (rhythm/snapshot-marker snapshot)
          colors (metronome-sync-colors controller)]
      (swap! (:next-note-buttons controller)
             assoc (:volume note-buttons)
             (if (or (new-beat? controller marker) (< (rhythm/snapshot-beat-phase snapshot) 0.15))
               (second colors) (first colors)))

      ;; Make the User 2 button bright, since we live in that layout
      (swap! (:next-control-buttons controller)
             assoc (:user-2 control-buttons) button-active-color)

      ;; Make the stop button visible, since we support it
      (swap! (:next-note-buttons controller)
             assoc (:stop note-buttons)
             stop-available-color)

      (reset! (:next-grid-pads controller) (render-cue-grid controller snapshot))

      ;; Add any contributions from interface overlays, removing them
      ;; if they report being finished.
      (controllers/run-overlays (:overlays controller) snapshot))

    (update-cue-grid controller)
    (update-round-buttons controller)

    (catch Throwable t
      (warn t "Problem updating Launchpad Mini/S Interface"))))


(defn- leave-user-mode
  "The user has asked to exit user mode, so suspend our display
  updates, and prepare to restore our state when user mode is pressed
  again."
  [controller]
  (swap! (:task controller) (fn [task]
                              (when task (at-at/kill task))
                              nil))
  (clear-interface controller)
  ;; In case Live isn't running, leave the User 2 button dimly lit, to help the user return.
  (midi/midi-control (:port-out controller) (:user-2 control-buttons) button-available-color)
  (controllers/add-overlay (:overlays controller)
                           (reify controllers/IOverlay
                             (captured-controls [this] #{(:user-2 control-buttons)})
                             (captured-notes [this] #{})
                             (adjust-interface [this _]
                               true)
                             (handle-control-change [this message]
                               (when (pos? (:velocity message))
                                 ;; We are returning to user mode, restore display
                                 (clear-interface controller)
                                 (reset! (:task controller) (at-at/every (:refresh-interval controller)
                                                                         #(update-interface controller)
                                                                         controllers/pool
                                                                         :initial-delay 250
                                                                         :desc "Launchpad Mini/S interface update"))
                                 :done))
                             (handle-note-on [this message])
                             (handle-note-off [this message])
                             (handle-aftertouch [this message])
                             (handle-pitch-bend [this message]))))

(defn- control-change-received
  "Process a control change message which was not handled by an
  interface overlay."
  [controller message]
  (case (:note message)
    106 ; Left arrow
    (when (pos? (:velocity message))
      (let [[x y] @(:origin controller)]
        (when (pos? x)
          (move-origin controller [(if @(:shift-mode controller) 0 (max 0 (- x 8))) y])
          (add-button-held-feedback-overlay controller (:left-arrow control-buttons)))))

    107 ; Right arrow
    (when (pos? (:velocity message))
      (let [[x y] @(:origin controller)
            width (max (controllers/grid-width (:cue-grid (:show controller))) 1)]
        (when (> (- (controllers/grid-width (:cue-grid (:show controller))) x) 7)
          (move-origin controller [(if @(:shift-mode controller) (* 8 (quot (dec width) 8)) (+ x 8)) y])
          (add-button-held-feedback-overlay controller (:right-arrow control-buttons)))))

    104 ; Up arrow
    (when (pos? (:velocity message))
      (let [[x y] @(:origin controller)
            height (max (controllers/grid-height (:cue-grid (:show controller))) 1)]
        (when (> (- (controllers/grid-height (:cue-grid (:show controller))) y) 7)
          (move-origin controller [x (if @(:shift-mode controller) (* 8 (quot (dec height) 8)) (+ y 8))])
          (add-button-held-feedback-overlay controller (:up-arrow control-buttons)))))

    105 ; Down arrow
    (when (pos? (:velocity message))
      (let [[x y] @(:origin controller)]
        (when (pos? y)
          (move-origin controller [x (max 0 (if @(:shift-mode controller) 0 (max 0 (- y 8))))])
          (add-button-held-feedback-overlay controller (:down-arrow control-buttons)))))

    110 ; User 2 button
    (when (pos? (:velocity message))
      (leave-user-mode controller))

    ;; Something we don't care about
    nil))

(defn- note-to-cue-coordinates
  "Translate the MIDI note associated with an incoming message to its
  coordinates in the show cue grid."
  [controller note]
  (let [[origin-x origin-y] @(:origin controller)
        pad-x (rem note 16)
        pad-y (- 7 (quot note 16))
        cue-x (+ origin-x pad-x)
        cue-y (+ origin-y pad-y)]
    [cue-x cue-y pad-x pad-y]))

;; TODO: Add a way for user to configure default virtual velocity for whole grid and individual cells.
(defn- cue-grid-pressed
  "One of the pads in the 8x8 cue grid was pressed."
  [controller note]
  (let [[cue-x cue-y pad-x pad-y] (note-to-cue-coordinates controller note)
        [cue active] (show/find-cue-grid-active-effect (:show controller) cue-x cue-y)]
          (when cue
            (with-show (:show controller)
              (if (and active (not (:held cue)))
                (show/end-effect! (:key cue))
                (let [id (show/add-effect-from-cue-grid! cue-x cue-y)
                      holding (and (:held cue) (not @(:shift-mode controller)))]
                  (controllers/add-overlay
                   (:overlays controller)
                   (reify controllers/IOverlay
                     (captured-controls [this] #{})
                     (captured-notes [this] #{note})
                     (adjust-interface [this snapshot]
                       (when holding
                         (swap! (:next-grid-pads controller) assoc-in [cue-y cue-x] cue-held-color))
                       true)
                     (handle-control-change [this message])
                     (handle-note-on [this message])
                     (handle-note-off [this message]
                       (when holding
                         (with-show (:show controller)
                           (show/end-effect! (:key cue) :when-id id)))
                       :done)
                     (handle-aftertouch [this message])
                     (handle-pitch-bend [this message])))))))))

(defn- note-on-received
  "Process a note-on message which was not handled by an interface
  overlay."
  [controller message]
  (let [note (:note message)]
    (if (and (<= (rem note 16) 7) (<= 0 (quot note 16) 7))
      (cue-grid-pressed controller note)
      (case note
        8  ; Tap tempo button
        (when (pos? (:velocity message))
          ((:tempo-tap-handler controller)))

        72  ; Stop button
        (when (pos? (:velocity message))
          (enter-stop-mode controller))

        120  ; Record Arm (shift) button
        (reset! (:shift-mode controller) (pos? (:velocity message)))

        ;; Something we don't care about
        nil))))

(defn- note-off-received
  "Process a note-off message which was not handled by an interface
  overlay."
  [controller message]
  (case (:note message)

    120  ; Record Arm (shift) button
    (reset! (:shift-mode controller) false)

    ;; Something we don't care about
    nil))

(defn- midi-received
  "Called whenever a MIDI message is received from the controller
  while the mapping is active; takes whatever action is appropriate."
  [controller message]
  ;;(timbre/info message)
  (when-not (controllers/overlay-handled? (:overlays controller) message)
    (when (= (:command message) :control-change)
      (control-change-received controller message))
    (when (= (:command message) :note-on)
      (note-on-received controller message))
    (when (= (:command message) :note-off)
      (note-off-received controller message))))

(defn- start-interface
  "Set up the thread which keeps the user interface up to date."
  [controller]
  (clear-interface controller)
  (amidi/add-device-mapping (:port-in controller) @(:midi-handler controller))
  (reset! (:task controller) (at-at/every (:refresh-interval controller)
                                          #(update-interface controller)
                                          controllers/pool
                                          :initial-delay 10
                                          :desc "Launchpad Mini/S interface update")))

(defn- welcome-frame
  "Render a frame of the welcome animation, or if it is done, start
  the main interface update thread, and terminate the task running the
  animation."
  [controller counter task]
  (let [gradient [0x3c 0x3e 0x3f 0x2f 0x1f 0x0f 0x0e 0x0d]]
    (try
      (cond
        (< @counter 8)
        (doseq [y (range 0 (inc @counter))]
          (let [color (gradient (- @counter y))]
            (set-pad-color controller 3 y color)
            (set-pad-color controller 4 y color)))

        (< @counter 12)
        (doseq [x (range 0 (- @counter 7))
                y (range 0 8)]
          (let [color (gradient (- @counter 8 x))]
            (set-pad-color controller (- 3 x) y color)
            (set-pad-color controller (+ 4 x) y color)))

        (= @counter 12)
        (show-round-buttons controller 0x3e)

        (< @counter 20)
        nil  ; Just stall

        (= @counter 20)
        (show-round-buttons controller 0x1f)

        (< @counter 29)
        (doseq [x (range 0 8)]
          (set-pad-color controller x (- 28 @counter) button-off-color))

        :else
        (do
          (start-interface controller)
          (at-at/kill @task)))
      (catch Throwable t
        (warn t "Animation frame failed"))))

  (swap! counter inc))

(defn- welcome-animation
  "Provide a fun animation to make it clear the Launchpad Mini/S is
  online."
  [controller]
  (let [counter (atom 0)
        task (atom nil)]
    (reset! task (at-at/every 50 #(welcome-frame controller counter task)
                              controllers/pool))))

(defn deactivate
  "Deactivates a controller interface, killing its update thread and
  removing its MIDI listeners. If `:disconnected` is passed with a
  `true` value, it means that the controller has already been removed
  from the MIDI environment, so no effort will be made to clear its
  display or take it out of User mode.

  In general you will not need to call this function directly; it will
  be dispatched to via [[controllers/deactivate]] when that is called
  with a controller binding implementation from this namespace. It is
  also called automatically when one of the controllers being used
  disappears from the MIDI environment."
  [controller & {:keys [disconnected] :or {disconnected false}}]
  {:pre (= ::controller (type controller))}
  (swap! (:task controller)
         (fn [task]
           (when task
             (at-at/kill task)
             (show/unregister-grid-controller @(:grid-controller-impl controller))
             (doseq [f @(:move-listeners controller)] (f @(:grid-controller-impl controller) :deactivated))
             (reset! (:move-listeners controller) #{})
             (amidi/remove-device-mapping (:port-in controller) @(:midi-handler controller))

             (when-not disconnected
               (Thread/sleep 35) ; Give the UI update thread time to shut down
               (clear-interface controller)

               ;; Leave the User button bright, in case the user has Live
               ;; running and wants to be able to see how to return to it.
               (set-control-button-color controller (:user-2 control-buttons) button-available-color))

             ;; Cancel any UI overlays which were in effect
             (reset! (:overlays controller) (controllers/create-overlay-state))

             ;; And finally, note that we are no longer active.
             (controllers/remove-active-binding controller))
           nil)))

(defn- recognize
  "Returns a tuple of the controller's device ID and model name if
  `message` is a response from [[controllers/identify]] which marks it
  as a Novation Launchpad Mini or S."
  [message _ _]
  (let [ident (take 5 (drop 4 (:data message)))
        device (int (aget (:data message) 1))]
    (cond (= ident '(0 32 41 54 0))
          [device "Launchpad Mini"]

          (= ident '(0 32 41 32 0))
          [device "Launchpad S"])))

;; Register our recognition function and rich binding with the
;; controller manager.
(swap! controllers/recognizers assoc ::controller recognize)

(defmethod controllers/deactivate ::controller
  [controller & {:keys [disconnected] :or {disconnected false}}]
  (deactivate controller :disconnected disconnected))

(defmethod controllers/bind-to-show-impl ::controller
  [kind show port-in port-out [device model] & {:keys [refresh-interval display-name new-connection]
                                                :or   {refresh-interval (/ 1000 15)}}]
  {:pre [(have? some? show)]}
  (let [shift-mode (atom false)
        controller
        (with-meta
          {:display-name         (or display-name model)
           :model                model
           :device-id            device
           :show                 show
           :origin               (atom [0 0])
           :refresh-interval     refresh-interval
           :port-in              port-in
           :port-out             port-out
           :task                 (atom nil)
           :last-control-buttons (atom {})
           :next-control-buttons (atom {})
           :last-note-buttons    (atom {})
           :next-note-buttons    (atom {})
           :last-grid-pads       (atom nil)
           :next-grid-pads       (atom nil)
           :shift-mode           shift-mode
           :stop-mode            (atom false)
           :midi-handler         (atom nil)
           :tempo-tap-handler    (tempo/create-show-tempo-tap-handler show :shift-fn (fn [] @shift-mode))
           :last-marker          (atom nil)
           :overlays             (controllers/create-overlay-state)
           :move-listeners       (atom #{})
           :grid-controller-impl (atom nil)}
          {:type ::controller})]
    (reset! (:midi-handler controller) (partial midi-received controller))
    (reset! (:grid-controller-impl controller)
            (reify controllers/IGridController
              (display-name [_this] (:display-name controller))
              (controller [_this] controller)
              (physical-height [_this] 8)
              (physical-width [_this] 8)
              (current-bottom [_this] (@(:origin controller) 1))
              (current-bottom [_this y] (move-origin controller (assoc @(:origin controller) 1 y)))
              (current-left [_this] (@(:origin controller) 0))
              (current-left [_this x] (move-origin controller (assoc @(:origin controller) 0 x)))
              (add-move-listener [_this f] (swap! (:move-listeners controller) conj f))
              (remove-move-listener [_this f] (swap! (:move-listeners controller) disj f))))
    (if new-connection
      (at-at/after 3000 (fn []
                          (clear-interface controller)
                          (start-interface controller)) controllers/pool :desc "Start Launchpad Mini/S interface")
      (do
        (clear-interface controller)
        (welcome-animation controller)))
    (controllers/add-active-binding controller)
    (show/register-grid-controller @(:grid-controller-impl controller))
    (amidi/add-disconnected-device-handler! port-in #(deactivate controller :disconnected true))
    controller))
