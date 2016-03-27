(ns afterglow.controllers.launchpad-mk2
    "Allows the Novation Launchpad Mk2 to be used as a control surface
  for Afterglow." {:author "James Elliott"}
  (:require [afterglow.controllers :as controllers]
            [afterglow.controllers.tempo :as tempo]
            [afterglow.effects.cues :as cues]
            [afterglow.midi :as amidi]
            [afterglow.rhythm :as rhythm]
            [afterglow.show :as show]
            [afterglow.show-context :refer [with-show]]
            [afterglow.util :as util]
            [clojure.math.numeric-tower :as math]
            [com.evocomputing.colors :as colors]
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
  {:volume     89  ; Used as tap tempo
   :pan        79
   :send-a     69
   :send-b     59
   :stop       49
   :mute       39
   :solo       29
   :record-arm 19})  ; Used as shift

(def button-off-color
  "The color of buttons that are completely off."
  (colors/create-color :black))

(def button-available-color
  "The color of buttons that can be pressed but haven't yet been."
  (colors/darken (colors/create-color :orange) 45))

(def button-active-color
  "The color of an available button that is currently being pressed."
  (colors/create-color :yellow))

(def stop-available-color
  "The color of the Stop button when not active."
  (colors/darken (colors/create-color :red) 45))

(def stop-active-color
  "The color of the stop button when active."
  (colors/create-color :red))

(def tempo-unsynced-beat-color
  "The color of the tap tempo button when synchronization is off and a
  beat is taking place."
  (colors/create-color :blue))

(def tempo-unsynced-off-beat-color
  "The color of the tap tempo button when synchronization is off and a
  beat is not taking place."
  (colors/darken tempo-unsynced-beat-color 45))

(def tempo-synced-beat-color
  "The color of the tap tempo button when the metronome is
  synchronzied and a beat is taking place."
  (colors/create-color :green))

(def tempo-synced-off-beat-color
  "The color of the tap tempo button when the metronome is
  synchronized and a beat is not taking place."
  (colors/darken tempo-synced-beat-color 45))

(defn set-led-color
  "Set one of the LEDs, given its control or note number, to a
  specific RGB color."
  [controller led color]
  (let [r (colors/red color)
        g (colors/green color)
        b (colors/blue color)]
    (midi/midi-sysex (:port-out controller) [240 0 32 41 2 24 11 led (quot r 4) (quot g 4) (quot b 4) 247])))

(defn set-pad-color
  "Set the color of one of the 64 touch pads to a specific RGB color."
  [controller x y color]
  (set-led-color controller (+ 11 x (* y 10)) color))

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
   (doseq [[_ led] (concat control-buttons note-buttons)]
     (set-led-color controller led color))))

(defn clear-interface
  "Clears all illuminated buttons and pads."
  [controller]
  (midi/midi-sysex (:port-out controller) [240 0 32 41 2 24 14 0 247])  ; Sets all LEDs to blackout
  (reset! (:last-grid-pads controller) nil)  ; Note that no buttons are lit
  (reset! (:last-round-buttons controller) {}))

(defn- update-round-buttons
  "Sees if any round buttons have changed state since the last time
  the interface was updated, and if so, sends the necessary MIDI
  commands to update them on the Launchpad Mk2."
  [controller]
  ;; First turn off any which were on before but no longer are
  (doseq [[button old-color] @(:last-round-buttons controller)]
    (when-not (get @(:next-round-buttons controller) button)
      (set-led-color controller button button-off-color)))

  ;; Then, light any currently active buttons
  (doseq [[button color] @(:next-round-buttons controller)]
    (when-not (= (get @(:last-round-buttons controller) button) color)
      (set-led-color controller button color)))

  ;; And record the new state for next time
  (reset! (:last-round-buttons controller) @(:next-round-buttons controller)))

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
                        base-color (when cue (cues/current-cue-color cue active (:show controller) snapshot))
                        l-boost (when base-color (if (zero? (colors/saturation base-color)) 2.0 0.0))
                        color (when base-color
                                (colors/create-color
                                 :h (colors/hue base-color)
                                 :s (colors/saturation base-color)
                                 ;; Figure the brightness. Active, non-ending cues are full brightness;
                                 ;; when ending, they blink between middle and low. If they are not active,
                                 ;; they are at middle brightness unless there is another active effect with
                                 ;; the same keyword, in which case they are dim.
                                 :l (+ (if active
                                         (if ending
                                           (if (> (rhythm/snapshot-beat-phase snapshot) 0.4) 5 22)
                                           60)
                                         (if (or (active-keys (:key cue))
                                                 (seq (clojure.set/intersection active-keys (set (:end-keys cue)))))
                                           5 22))
                                       l-boost)))]
                    (or color button-off-color))))))))

(defn- update-cue-grid
  "Set the colors of all the cue grid pads, based on the currently
  active cues and overlays."
  [controller]
  (doseq [x (range 8)
          y (range 8)]
    (let [color (get-in @(:next-grid-pads controller) [y x])]
      (when-not (= (get-in @(:last-grid-pads controller) [y x]) color)
        (set-pad-color controller x y color))))
  (reset! (:last-grid-pads controller) @(:next-grid-pads controller)))

(defn- update-scroll-arrows
  "Activate the arrow buttons for directions in which scrolling is
  possible."
  [controller]
  (let [[origin-x origin-y] @(:origin controller)]
    (when (pos? origin-x)
      (swap! (:next-round-buttons controller) assoc (:left-arrow control-buttons) button-available-color))
    (when (pos? origin-y)
      (swap! (:next-round-buttons controller) assoc (:down-arrow control-buttons) button-available-color))
    (when (> (- (controllers/grid-width (:cue-grid (:show controller))) origin-x) 7)
      (swap! (:next-round-buttons controller) assoc (:right-arrow control-buttons) button-available-color))
    (when (> (- (controllers/grid-height (:cue-grid (:show controller))) origin-y) 7)
      (swap! (:next-round-buttons controller) assoc (:up-arrow control-buttons) button-available-color))))

(defn add-button-held-feedback-overlay
  "Adds a simple overlay which keeps a control button bright as long
  as the user is holding it down."
  ([controller button]
   (add-button-held-feedback-overlay controller button button-active-color))
  ([controller button color]
   (controllers/add-control-held-feedback-overlay (:overlays controller) button
                                                  (fn [_] (swap! (:next-round-buttons controller)
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
                               (swap! (:next-round-buttons controller)
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
    (reset! (:next-round-buttons controller) {})

    ;; If the show has stopped without us noticing, enter stop mode
    (with-show (:show controller)
      (when-not (or (show/running?) @(:stop-mode controller))
        (enter-stop-mode controller :already-stopped true)))

    ;; Reflect the Record Arm (shift) button state
    (swap! (:next-round-buttons controller)
           assoc (:record-arm note-buttons)
           (if @(:shift-mode controller) button-active-color button-available-color))

    (update-scroll-arrows controller)

    ;; Flash the volume (tap tempo) button on beats
    (let [snapshot (rhythm/metro-snapshot (get-in controller [:show :metronome]))
          marker (rhythm/snapshot-marker snapshot)
          colors (metronome-sync-colors controller)]
      (swap! (:next-round-buttons controller)
             assoc (:volume note-buttons)
             (if (or (new-beat? controller marker) (< (rhythm/snapshot-beat-phase snapshot) 0.15))
               (second colors) (first colors)))

      ;; Make the User 2 button bright, since we live in that layout
      (swap! (:next-round-buttons controller)
             assoc (:user-2 control-buttons) button-active-color)

      ;; Make the stop button visible, since we support it
      (swap! (:next-round-buttons controller)
             assoc (:stop note-buttons)
             stop-available-color)

      (reset! (:next-grid-pads controller) (render-cue-grid controller snapshot))

      ;; Add any contributions from interface overlays, removing them
      ;; if they report being finished.
      (controllers/run-overlays (:overlays controller) snapshot))

    (update-cue-grid controller)
    (update-round-buttons controller)

    (catch Throwable t
      (warn t "Problem updating Launchpad Mk2 Interface"))))

(defn- set-layout
  "Put the controller in the User 2 layout, which is most appropriate
  for Afterglow."
  [controller]
  (midi/midi-sysex (:port-out controller) [240 0 32 41 2 24 34 2 247]))

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
  (set-led-color controller (:user-2 control-buttons) button-available-color)
  (controllers/add-overlay (:overlays controller)
                           (reify controllers/IOverlay
                             (captured-controls [this] #{(:user-2 control-buttons)})
                             (captured-notes [this] #{})
                             (adjust-interface [this _]
                               true)
                             (handle-control-change [this message]
                               (when (pos? (:velocity message))
                                 ;; We are returning to user mode, restore layout and display
                                 (set-layout controller)
                                 (clear-interface controller)
                                 (reset! (:task controller) (at-at/every (:refresh-interval controller)
                                                                         #(update-interface controller)
                                                                         controllers/pool
                                                                         :initial-delay 250
                                                                         :desc "Launchpad Mk2 interface update"))
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
          (move-origin controller [(max 0 (- x 8)) y])
          (add-button-held-feedback-overlay controller (:left-arrow control-buttons)))))

    107 ; Right arrow
    (when (pos? (:velocity message))
      (let [[x y] @(:origin controller)]
        (when (> (- (controllers/grid-width (:cue-grid (:show controller))) x) 7)
          (move-origin controller [(+ x 8) y])
          (add-button-held-feedback-overlay controller (:right-arrow control-buttons)))))

    104 ; Up arrow
    (when (pos? (:velocity message))
      (let [[x y] @(:origin controller)]
        (when (> (- (controllers/grid-height (:cue-grid (:show controller))) y) 7)
          (move-origin controller [x (+ y 8)])
          (add-button-held-feedback-overlay controller (:up-arrow control-buttons)))))

    105 ; Down arrow
    (when (pos? (:velocity message))
      (let [[x y] @(:origin controller)]
        (when (pos? y)
          (move-origin controller [x (max 0 (- y 8))])
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
  (let [base (- note 11)
        [origin-x origin-y] @(:origin controller)
        pad-x (rem base 10)
        pad-y (quot base 10)
        cue-x (+ origin-x pad-x)
        cue-y (+ origin-y pad-y)]
    [cue-x cue-y pad-x pad-y]))

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
                         (let [active (show/find-effect (:key cue))
                               base-color (cues/current-cue-color cue active (:show controller) snapshot)
                               color (colors/create-color
                                      :h (colors/hue base-color)
                                      :s (colors/saturation base-color)
                                      :l 75)]
                           (swap! (:next-grid-pads controller) assoc-in [cue-y cue-x] color)))
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
    (if (and (<= 1 (rem note 10) 8) (<= 1 (quot note 10) 8))
      (cue-grid-pressed controller note)
      (case note
        89  ; Volume (tap tempo) button
        (when (pos? (:velocity message))
          ((:tempo-tap-handler controller)))

        19  ; Record Arm (shift) button
        (reset! (:shift-mode controller) (pos? (:velocity message)))

        49  ; Stop button
        (when (pos? (:velocity message))
          (enter-stop-mode controller))

        ;; Something we don't care about
        nil))))

(defn- note-off-received
  "Process a note-off message which was not handled by an interface
  overlay."
  [controller message]
  (case (:note message)

    19  ; Record Arm (shift) button
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
                                          :desc "Launchpad Mk2 interface update")))

(defn- welcome-frame
  "Render a frame of the welcome animation, or if it is done, start
  the main interface update thread, and terminate the task running the
  animation."
  [controller counter task]
  (try
    (cond
      (< @counter 8)
      (doseq [y (range 0 (inc @counter))]
        (let [color (colors/create-color
                     :h 0 :s 0 :l (max 10 (- 75 (/ (* 50 (- @counter y)) 6))))]
          (set-pad-color controller 3 y color)
          (set-pad-color controller 4 y color)))

      (< @counter 12)
      (doseq [x (range 0 (- @counter 7))
              y (range 0 8)]
        (let [color (colors/create-color
                     :h 340 :s 100 :l (- 75 (* (- @counter 8 x) 20)))]
          (set-pad-color controller (- 3 x) y color)
          (set-pad-color controller (+ 4 x) y color)))

      (< @counter 15)
      (doseq [y (range 0 8)]
        (let [color (colors/create-color
                     :h (* 13 (- @counter 11)) :s 100 :l 50)]
          (set-pad-color controller (- @counter 7) y color)
          (set-pad-color controller (- 14 @counter) y color)))

      (= @counter 15)
      (show-round-buttons controller (colors/create-color :cyan))

      (< @counter 24)
      (doseq [x (range 0 8)]
        (let [lightness-index (if (> x 3) (- 7 x) x)
              lightness ([10 30 50 70] lightness-index)
              color (colors/create-color
                     :h (+ 60 (* 40 (- @counter 18))) :s 100 :l lightness)]
          (set-pad-color controller x (- 23 @counter) color)))

      (= @counter 24)
      (show-round-buttons controller (colors/create-color :blue))

      (< @counter 33)
      (doseq [x (range 0 8)]
        (set-pad-color controller x (- 32 @counter) button-off-color))

      :else
      (do
        (start-interface controller)
        (at-at/kill @task)))
    (catch Throwable t
      (warn t "Animation frame failed")))

  (swap! counter inc))

(defn- welcome-animation
  "Provide a fun animation to make it clear the Launchpad Mk2 is online."
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
  {:pre [(have? (= ::controller (type controller)))]}
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
               (set-led-color controller (:user-2 control-buttons) (colors/create-color :white)))

             ;; Cancel any UI overlays which were in effect
             (reset! (:overlays controller) (controllers/create-overlay-state))

             ;; And finally, note that we are no longer active.
             (controllers/remove-active-binding controller))
           nil)))

(defn- recognize
  "Returns the controller's device ID if `message` is a response
  from [[controllers/identify]] which marks it as a Novation Launchpad
  Mk2."
  [message]
  (when (= (take 5 (drop 4 (:data message))) '(0 32 41 105 0))
    (int (aget (:data message) 1))))

;; Register our recognition function and rich binding with the
;; controller manager.
(swap! controllers/recognizers assoc ::controller recognize)

(defmethod controllers/deactivate ::controller
  [controller & {:keys [disconnected] :or {disconnected false}}]
  (deactivate controller :disconnected disconnected))

(defmethod controllers/bind-to-show-impl ::controller
  [kind show port-in port-out device & {:keys [refresh-interval display-name new-connection]
                                        :or   {refresh-interval (/ 1000 15)
                                               display-name     "Launchpad Mk2"}}]
  {:pre [(some? show)]}
  (let [shift-mode (atom false)
        controller
        (with-meta
          {:display-name         display-name
           :device-id            device
           :show                 show
           :origin               (atom [0 0])
           :refresh-interval     refresh-interval
           :port-in              port-in
           :port-out             port-out
           :task                 (atom nil)
           :last-round-buttons    (atom {})
           :next-round-buttons    (atom {})
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
              (display-name [this] (:display-name controller))
              (physical-height [this] 8)
              (physical-width [this] 8)
              (current-bottom [this] (@(:origin controller) 1))
              (current-bottom [this y] (move-origin controller (assoc @(:origin controller) 1 y)))
              (current-left [this] (@(:origin controller) 0))
              (current-left [this x] (move-origin controller (assoc @(:origin controller) 0 x)))
              (add-move-listener [this f] (swap! (:move-listeners controller) conj f))
              (remove-move-listener [this f] (swap! (:move-listeners controller) disj f))))
    (if new-connection
      (at-at/after 3000 (fn []
                          (set-layout controller)
                          (clear-interface controller)
                          (start-interface controller)) controllers/pool :desc "Start Launchpad Mk2 interface")
      (do
        (set-layout controller)
        (clear-interface controller)
        (welcome-animation controller)))
    (controllers/add-active-binding controller)
    (show/register-grid-controller @(:grid-controller-impl controller))
    (amidi/add-disconnected-device-handler! port-in #(deactivate controller :disconnected true))
    controller))
