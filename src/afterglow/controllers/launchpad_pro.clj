(ns afterglow.controllers.launchpad-pro
  "Allows the Novation Launchpad Pro to be used as a control surface
  for Afterglow."
  {:author "James Elliott"}
  (:require [afterglow.controllers :as controllers]
            [afterglow.effects.cues :as cues]
            [afterglow.effects.dimmer :refer [master-get-level master-set-level]]
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


(defonce ^{:doc "Counts the controller bindings which have been made,
  so each can be assigned a unique ID."}
  controller-counter (atom 0))

(defonce ^{:doc "Controllers which are currently bound to shows,
  indexed by the controller binding ID."}
  active-bindings (atom {}))

(def control-buttons
  "The labeled buttons which send and respond to Control Change
  events."
  {:record-arm   1
   :track-select 2
   :mute         3
   :solo         4
   :volume       5
   :pan          6
   :sends        7
   :stop         8

   :circle       10
   :double       20
   :duplicate    30
   :quantize     40
   :delete       50
   :undo         60
   :tap-tempo    70
   :shift        80

   :row-0        19
   :row-1        29
   :row-2        39
   :row-3        49
   :row-4        59
   :row-5        69
   :row-6        79
   :row-7        89
   
   :up-arrow     91
   :down-arrow   92
   :left-arrow   93
   :right-arrow  94
   :session-mode 95
   :note-mode    96
   :device-mode  97
   :user-mode    98})

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

(defn led-color-values
  "Given a color, return the values that should be sent in a Sysex
  message to set an LED to that color."
  [color]
  (let [r (colors/red color)
        g (colors/green color)
        b (colors/blue color)]
    (list (quot r 4) (quot g 4) (quot b 4))))

(defn set-led-color
  "Set one of the LEDs, given its control or note number, to a
  specific RGB color."
  [controller led color]
  (let [r (colors/red color)
        g (colors/green color)
        b (colors/blue color)]
    (midi/midi-sysex (:port-out controller) [240 0 32 41 2 16 11 led (quot r 4) (quot g 4) (quot b 4) 247])))

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

(defn show-labels
  "Illuminates all buttons with text labels, for development assistance."
  ([controller]
   (show-labels controller button-available-color))
  ([controller color]
   (doseq [[_ led] control-buttons]
     (set-led-color controller led color))))

(defn clear-interface
  "Clears all illuminated buttons and pads."
  [controller]
  (midi/midi-sysex (:port-out controller) [240 0 32 41 2 16 14 0 247])  ; Sets all LEDs to blackout
  (reset! (:last-grid-pads controller) nil)
  (reset! (:last-text-buttons controller) {}))  ; Note that no buttons are lit

(defn- update-text-buttons
  "Sees if any labeled buttons have changed state since the last time
  the interface was updated, and if so, sends the necessary MIDI
  commands to update them on the Launchpad Pro."
  [controller]
  ;; First turn off any which were on before but no longer are
  (doseq [[button old-color] @(:last-text-buttons controller)]
    (when-not (get @(:next-text-buttons controller) button)
      (set-led-color controller button button-off-color)))

  ;; Then, light any currently active buttons
  (doseq [[button color] @(:next-text-buttons controller)]
    (when-not (= (get @(:last-text-buttons controller) button) color)
      (set-led-color controller button color)))

  ;; And record the new state for next time
  (reset! (:last-text-buttons controller) @(:next-text-buttons controller)))

(defn- render-cue-grid
  "Figure out how the cue grid pads should be illuminated, based on
  the currently active cues. Returns a vector of the rows, from bottom
  to top. Each row is a vector of the colors in that row, from left to
  right."
  [controller]
  (let [[origin-x origin-y] @(:origin controller)
        active-keys (show/active-effect-keys (:show controller))]
    (vec (for [y (range 8)]
           (vec (for [x (range 8)]
                  (let [[cue active] (show/find-cue-grid-active-effect (:show controller) (+ x origin-x) (+ y origin-y))
                        ending (and active (:ending active))
                        l-boost (when cue (if (zero? (colors/saturation (:color cue))) 20.0 0.0))
                        color (when cue
                                (colors/create-color
                                 :h (colors/hue (:color cue))
                                 :s (colors/saturation (:color cue))
                                 ;; Figure the brightness. Active, non-ending cues are full brightness;
                                 ;; when ending, they blink between middle and low. If they are not active,
                                 ;; they are at middle brightness unless there is another active effect with
                                 ;; the same keyword, in which case they are dim.
                                 :l (+ (if active
                                         (if ending
                                           (if (> (rhythm/metro-beat-phase (:metronome (:show controller))) 0.4) 10 20)
                                           50)
                                         (if (active-keys (:key cue)) 10 20))
                                       l-boost)))]
                    (or color button-off-color))))))))

(defn- count-grid-changes
  "See how many changes we can find in the grid, with an upper limit
  Returns the number of changes we see, or the upper bound if that is
  reached before the end of the grid."
  [controller limit]
  (loop [found 0
         old (flatten @(:last-grid-pads controller))
         new (flatten @(:next-grid-pads controller))]
    (if (or (>= found limit) (empty? new))
      found
      (recur
       (if (= (first old) (first new)) found (inc found))
       (rest old)
       (rest new)))))

(defn- update-cue-grid
  "Set the colors of all the cue grid pads, based on the currently
  active cues and overlays. On the Launchpad Pro this can be done with
  a single Sysex message, but doing that when there are few changes
  results in a visible flicker. So as a compromise, we update the
  whole grid when six or more cells have changed, otherwise we send
  the differences individually."
  [controller]
  (let [limit 6
        changes (count-grid-changes controller limit)]
    (if (>= changes limit)
      (midi/midi-sysex (:port-out controller)  ; Send the entire grid as one Sysex message
                       (concat [240 0 32 41 2 16 15 1]
                               (flatten (map led-color-values (flatten @(:next-grid-pads controller))))
                               [247]))
      (doseq [x (range 8)  ; Send the changes only, individually
              y (range 8)]
        (let [color (get-in @(:next-grid-pads controller) [y x])]
          (when-not (= (get-in @(:last-grid-pads controller) [y x]) color)
            (set-pad-color controller x y color)))))
    (reset! (:last-grid-pads controller) @(:next-grid-pads controller))))


(defn- update-scroll-arrows
  "Activate the arrow buttons for directions in which scrolling is
  possible."
  [controller]
  (let [[origin-x origin-y] @(:origin controller)]
    (when (pos? origin-x)
      (swap! (:next-text-buttons controller) assoc (:left-arrow control-buttons) button-available-color))
    (when (pos? origin-y)
      (swap! (:next-text-buttons controller) assoc (:down-arrow control-buttons) button-available-color))
    (when (> (- (controllers/grid-width (:cue-grid (:show controller))) origin-x) 7)
      (swap! (:next-text-buttons controller) assoc (:right-arrow control-buttons) button-available-color))
    (when (> (- (controllers/grid-height (:cue-grid (:show controller))) origin-y) 7)
      (swap! (:next-text-buttons controller) assoc (:up-arrow control-buttons) button-available-color))))

(defn add-button-held-feedback-overlay
  "Adds a simple overlay which keeps a control button bright as long
  as the user is holding it down."
  ([controller button]
   (add-button-held-feedback-overlay controller button button-active-color))
  ([controller button color]
   (controllers/add-control-held-feedback-overlay (:overlays controller) button
                                                  #(swap! (:next-text-buttons controller)
                                                          assoc button color))))

(defn- enter-stop-mode
  "The user has asked to stop the show. Suspend its update task
  and black it out until the stop button is pressed again."
  [controller]

  (reset! (:stop-mode controller) true)
  (with-show (:show controller)
    (show/stop!)
    (Thread/sleep (:refresh-interval (:show controller)))
    (show/blackout-show))

  (controllers/add-overlay (:overlays controller)
                           (reify controllers/IOverlay
                             (captured-controls [this] #{8})
                             (captured-notes [this] #{})
                             (adjust-interface [this]
                               (swap! (:next-text-buttons controller)
                                      assoc (:stop control-buttons) stop-active-color)
                               (with-show (:show controller)
                                 (when (show/running?)
                                   (reset! (:stop-mode controller) false))
                                 @(:stop-mode controller)))
                             (handle-control-change [this message]
                               (when (pos? (:velocity message))
                                 ;; End stop mode
                                 (with-show (:show controller)
                                   (show/start!))
                                 (reset! (:stop-mode controller) false)
                                 :done))
                             (handle-note-on [this message])
                             (handle-note-off [this message])
                             (handle-aftertouch [this message]))))

(defn- new-beat?
  "Returns true if the metronome is reporting a different marker
  position than the last time this function was called."
  [controller marker]
  (when (not= marker @(:last-marker controller))
    (reset! (:last-marker controller) marker)))

(defn- update-interface
  "Determine the desired current state of the interface, and send any
  changes needed to get it to that state."
  [controller]
  (try
    ;; Assume we are starting out with a blank interface.
    (reset! (:next-text-buttons controller) {})

    ;; If the show has stopped without us noticing, enter stop mode
    (with-show (:show controller)
      (when-not (or (show/running?) @(:stop-mode controller))
        (enter-stop-mode controller)))

    ;; Reflect the shift button state
    (swap! (:next-text-buttons controller)
           assoc (:shift control-buttons)
           (if @(:shift-mode controller) button-active-color button-available-color))
    
    (update-scroll-arrows controller)
    
    ;; Flash the tap tempo button on beats
    (let [metronome (:metronome (:show controller))
          marker (rhythm/metro-marker metronome)]
      (swap! (:next-text-buttons controller)
             assoc (:tap-tempo control-buttons)
             (if (or (new-beat? controller marker) (< (rhythm/metro-beat-phase metronome) 0.15))
               button-active-color button-available-color)))

    ;; Make the User button bright, since we live in User mode
    (swap! (:next-text-buttons controller)
           assoc (:user-mode control-buttons) button-active-color)

    ;; Make the stop button visible, since we support it
    (swap! (:next-text-buttons controller)
           assoc (:stop control-buttons)
           stop-available-color)

    (reset! (:next-grid-pads controller) (render-cue-grid controller))

    ;; Add any contributions from interface overlays, removing them
    ;; if they report being finished.
    (controllers/run-overlays (:overlays controller))

    (update-cue-grid controller)
    (update-text-buttons controller)

    (catch Throwable t
      (warn t "Problem updating Ableton Push Interface"))))

(defn- interpret-tempo-tap
  "React appropriately to a tempo tap, based on the sync mode of the
  show metronome. If it is manual, invoke the metronome tap-tempo
  handler. If MIDI, align the current beat to the tap. If DJ Link, set
  the current beat to be a down beat (first beat of a bar)."
  [controller]
  (with-show (:show controller)
    (let [metronome (get-in controller [:show :metronome])]
      (case (:level (show/sync-status))
        nil ((:tap-tempo-handler controller))
        :bpm (rhythm/metro-beat-phase metronome 0)
        :beat (rhythm/metro-bar-start metronome (rhythm/metro-bar metronome))
        :bar (rhythm/metro-phrase-start metronome (rhythm/metro-bar metronome))
        (warn "Don't know how to tap tempo for sync type" (show/sync-status))))))

(defn- leave-user-mode
  "The user has asked to exit user mode, so suspend our display
  updates, and prepare to restore our state when user mode is pressed
  again."
  [controller]
  (swap! (:task controller) (fn [task]
                              (when task (at-at/kill task))
                              nil))
  (clear-interface controller)
  ;; In case Live isn't running, leave the User Mode button dimly lit, to help the user return.
  (set-led-color controller (:user-mode control-buttons) button-available-color)
  (controllers/add-overlay (:overlays controller)
                           (reify controllers/IOverlay
                             (captured-controls [this] #{98})
                             (captured-notes [this] #{})
                             (adjust-interface [this]
                               true)
                             (handle-control-change [this message]
                               (when (pos? (:velocity message))
                                 ;; We are returning to user mode, restore display
                                 (clear-interface controller)
                                 (reset! (:task controller) (at-at/every (:refresh-interval controller)
                                                                         #(update-interface controller)
                                                                         controllers/pool
                                                                         :initial-delay 250
                                                                         :desc "Launchpad Pro interface update"))
                                 :done))
                             (handle-note-on [this message])
                             (handle-note-off [this message])
                             (handle-aftertouch [this message]))))

(defn- control-change-received
  "Process a control change message which was not handled by an
  interface overlay."
  [controller message]
  (case (:note message)
    70  ; Tap tempo (click) button
    (when (pos? (:velocity message))
      (interpret-tempo-tap controller))

    8  ; Stop button
    (when (pos? (:velocity message))
      (enter-stop-mode controller))

    80 ; Shift button
    (swap! (:shift-mode controller) (fn [_] (pos? (:velocity message))))

    93 ; Left arrow
    (when (pos? (:velocity message))
      (let [[x y] @(:origin controller)]
        (when (pos? x)
          (move-origin controller [(max 0 (- x 8)) y])
          (add-button-held-feedback-overlay controller (:left-arrow control-buttons)))))

    94 ; Right arrow
    (when (pos? (:velocity message))
      (let [[x y] @(:origin controller)]
        (when (> (- (controllers/grid-width (:cue-grid (:show controller))) x) 7)
          (move-origin controller [(+ x 8) y])
          (add-button-held-feedback-overlay controller (:right-arrow control-buttons)))))

    91 ; Up arrow
    (when (pos? (:velocity message))
      (let [[x y] @(:origin controller)]
        (when (> (- (controllers/grid-height (:cue-grid (:show controller))) y) 7)
          (move-origin controller [x (+ y 8)])
          (add-button-held-feedback-overlay controller (:up-arrow control-buttons)))))

    92 ; Down arrow
    (when (pos? (:velocity message))
      (let [[x y] @(:origin controller)]
        (when (pos? y)
          (move-origin controller [x (max 0 (- y 8))])
          (add-button-held-feedback-overlay controller (:down-arrow control-buttons)))))

    98 ; User mode button
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
  "One of the pads in the 8x8 pressure-sensitve cue grid was pressed."
  [controller note velocity]
  (let [[cue-x cue-y pad-x pad-y] (note-to-cue-coordinates controller note)
        [cue active] (show/find-cue-grid-active-effect (:show controller) cue-x cue-y)]
          (when cue
            (with-show (:show controller)
              (if (and active (not (:held cue)))
                (show/end-effect! (:key cue))
                (let [vars (controllers/starting-vars-for-velocity cue velocity)
                      id (show/add-effect-from-cue-grid! cue-x cue-y :var-overrides vars)
                      holding (and (:held cue) (not @(:shift-mode controller)))]
                  (controllers/add-overlay
                   (:overlays controller)
                   (reify controllers/IOverlay
                     (captured-controls [this] #{})
                     (captured-notes [this] #{note})
                     (adjust-interface [this]
                       (when holding
                         (let [color (colors/create-color
                                      :h (colors/hue (:color cue))
                                      :s (colors/saturation (:color cue))
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
                     (handle-aftertouch [this message]
                       (if (zero? (:velocity message))
                         (do (when holding
                               (with-show (:show controller)
                                 (show/end-effect! (:key cue) :when-id id)))
                             :done)
                         (doseq [v (:variables cue)]
                           (when (:velocity v)
                             (cues/set-cue-variable! cue v
                                                     (controllers/value-for-velocity v (:velocity message))
                                                     :controller controller :when-id id)))))))))))))

(defn- note-on-received
  "Process a note-on message which was not handled by an interface
  overlay."
  [controller message]
  (let [note (:note message)]
    (when (and (<= 1 (rem note 10) 8) (<= 1 (quot note 10) 8))
      (cue-grid-pressed controller note (:velocity message)))))

(defn- note-off-received
  "Process a note-off message which was not handled by an interface
  overlay."
  [controller message]
  (case (:note message)

    ;; Something we don't care about
    nil))

(defn- midi-received
  "Called whenever a MIDI message is received while the controller is
  active; checks if it came in on the right port, and if so, decides
  what should be done."
  [controller message]
  (when (amidi/same-device? (:device message) (:port-in controller))
    ;;(timbre/info message)
    (when-not (controllers/overlay-handled? (:overlays controller) message)
      (when (= (:command message) :control-change)
        (control-change-received controller message))
      (when (= (:command message) :note-on)
        (note-on-received controller message))
      (when (= (:command message) :note-off)
        (note-off-received controller message)))))

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
      (show-labels controller (colors/create-color :cyan))
      
      (< @counter 24)
      (doseq [x (range 0 8)]
        (let [lightness-index (if (> x 3) (- 7 x) x)
              lightness ([10 30 50 70] lightness-index)
              color (colors/create-color
                     :h (+ 60 (* 40 (- @counter 18))) :s 100 :l lightness)]
          (set-pad-color controller x (- 23 @counter) color)))
      
      (= @counter 24)
      (show-labels controller (colors/create-color :blue))

      (< @counter 33)
      (doseq [x (range 0 8)]
        (set-pad-color controller x (- 32 @counter) button-off-color))
      
      :else
      (do
        (clear-interface controller)
        (amidi/add-global-handler! @(:midi-handler controller))
        (reset! (:task controller) (at-at/every (:refresh-interval controller)
                                                #(update-interface controller)
                                                controllers/pool
                                                :initial-delay 10
                                                :desc "Launchpad Pro interface update"))
        (at-at/kill @task)))
    (catch Throwable t
      (warn t "Animation frame failed")))

  (swap! counter inc))

(defn- welcome-animation
  "Provide a fun animation to make it clear the Launchpad Pro is online."
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

  You can also pass a watcher object created by [[auto-bind]] as
  `controller`; this will both deactivate the controller being managed
  by the watcher, if it is currently connected, and cancel the
  watcher itself. In such cases, `:disconnected` is meaningless."
  [controller & {:keys [disconnected] :or {disconnected false}}]
  {:pre [(#{:launchpad-pro :launchpad-pro-watcher} (type controller))]}
  (if (= (type controller) :launchpad-pro-watcher)
    (do ((:cancel controller))  ; Shut down the watcher
        (when-let [watched-controller @(:controller controller)]
          (deactivate watched-controller)))  ; And deactivate the controller it was watching for
    (do ;; We were passed an actual controller, not a watcher, so deactivate it.
      (swap! (:task controller) (fn [task]
                                  (when task (at-at/kill task))
                                  nil))
      (show/unregister-grid-controller @(:grid-controller-impl controller))
      (doseq [f @(:move-listeners controller)] (f @(:grid-controller-impl controller) :deactivated))
      (reset! (:move-listeners controller) #{})
      (amidi/remove-global-handler! @(:midi-handler controller))

      (when-not disconnected
        (Thread/sleep 35) ; Give the UI update thread time to shut down
        (clear-interface controller)
        ;; Leave the User button bright, in case the user has Live
        ;; running and wants to be able to see how to return to it.
        (set-led-color controller (:user-mode control-buttons) (colors/create-color :white)))

      ;; Cancel any UI overlays which were in effect
      (reset! (:overlays controller) (controllers/create-overlay-state))

      ;; And finally, note that we are no longer active.
      (swap! active-bindings dissoc (:id controller)))))

(defn deactivate-all
  "Deactivates all controller bindings which are currently active.
  This will be regustered as a shutdown hook to be called when the
  Java environment is shutting down, to clean up gracefully."
  []
  (doseq [[_ controller] @active-bindings]
    (deactivate controller)))

(defonce ^{:doc "Deactivates any Launchpad Pro bindings when Java is shutting down."
           :private true}
  shutdown-hook
  (let [hook (Thread. deactivate-all)]
    (.addShutdownHook (Runtime/getRuntime) hook)
    hook))

(defn bind-to-show
  "Establish a connection to the Novation Launchpad Pro, for managing
  the given show.

  Initializes the display, and starts the UI updater thread. Since
  SysEx messages are required for updating the display, if you are on
  a Mac, you must
  install [CoreMIDI4J](https://github.com/DerekCook/CoreMidi4J) to
  provide a working implementation. (If you need to work with Java
  1.6, you can instead
  use [mmj](http://www.humatic.de/htools/mmj.htm), but that is no
  longer developed, and does not support connecting or disconnecting
  MIDI devices after Java has started.)

  If you have more than one Launchpad connected, or have renamed how
  it appears in your list of MIDI devices, you need to supply a value
  after `:device-filter` which identifies the ports to be used to
  communicate with the Launchpad you want this function to use. The
  values returned by [[afterglow.midi/open-inputs-if-needed!]]
  and [[afterglow.midi/open-outputs-if-needed!]] will be searched, and
  the first port that matches with [[filter-devices]] will be used.

  The controller will be identified in the user interface (for the
  purposes of linking it to the web cue grid) as \"Launchpad Pro\". If
  you would like to use a different name (for example, if have more
  than one Launchpad), you can pass in a custom value after
  `:display-name`.

  If you want the user interface to be refreshed at a different rate
  than the default of fifteen times per second, pass your desired
  number of milliseconds after `:refresh-interval`."
  [show & {:keys [device-filter refresh-interval display-name]
           :or   {device-filter    "Standalone Port"
                  refresh-interval (/ 1000 15)
                  display-name     "Launchpad Pro"}}]
  {:pre [(some? show)]}
  (let [port-in  (first (amidi/filter-devices device-filter (amidi/open-inputs-if-needed!)))
        port-out (first (amidi/filter-devices device-filter (amidi/open-outputs-if-needed!)))]
    (if (every? some? [port-in port-out])
      (let [controller
            (with-meta
              {:id                   (swap! controller-counter inc)
               :display-name         display-name
               :show                 show
               :origin               (atom [0 0])
               :refresh-interval     refresh-interval
               :port-in              port-in
               :port-out             port-out
               :task                 (atom nil)
               :last-text-buttons    (atom {})
               :next-text-buttons    (atom {})
               :last-grid-pads       (atom nil)
               :next-grid-pads       (atom nil)
               :shift-mode           (atom false)
               :stop-mode            (atom false)
               :midi-handler         (atom nil)
               :tap-tempo-handler    (amidi/create-tempo-tap-handler (:metronome show))
               :last-marker          (atom nil)
               :overlays             (controllers/create-overlay-state)
               :move-listeners       (atom #{})
               :grid-controller-impl (atom nil)}
              {:type :launchpad-pro})]
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
        ;; Set controller in Programmer mode
        (midi/midi-sysex (:port-out controller) [240 0 32 41 2 16 44 3 247])
        (clear-interface controller)
        (welcome-animation controller)
        (swap! active-bindings assoc (:id controller) controller)
        (show/register-grid-controller @(:grid-controller-impl controller))
        (amidi/add-disconnected-device-handler! port-in #(deactivate controller :disconnected true))
        controller)
      (timbre/error "Unable to find Launchpad Pro" (amidi/describe-device-filter device-filter)))))

(defn auto-bind
  "Watches for a Novation Launchpad Pro controller to be connected,
  and as soon as it is, binds it to the specified show
  using [[bind-to-show]]. If that controller ever gets disconnected,
  it will be re-bound once it reappears. Returns a watcher structure
  which can be passed to [[deactivate]] if you would like to
  stop it watching for reconnections. The underlying controller
  mapping, once bound, can be accessed through the watcher's
  `:controller` key.

  If you have more than one Launchpad Pro that might beconnected, or
  have renamed how it appears in your list of MIDI devices, you need
  to supply a value after `:device-filter` which identifies the ports
  to be used to communicate with the Launchpad you want this function
  to use. The values returned
  by [[afterglow.midi/open-inputs-if-needed!]]
  and [[afterglow.midi/open-outputs-if-needed!]] will be searched, and
  the first port that matches using [[filter-devices]] will be used.

  Once bound, the controller will be identified in the user
  interface (for the purposes of linking it to the web cue grid) as
  \"Launchpad Pro\". If you would like to use a different name (for
  example, if you have more than one Launchpad), you can pass in a
  custom value after `:display-name`.

  If you want the user interface to be refreshed at a different rate
  than the default of fifteen times per second, pass your desired
  number of milliseconds after `:refresh-interval`."
  [show & {:keys [device-filter refresh-interval display-name]
           :or {device-filter "Standalone Port"
                refresh-interval (/ 1000 15)
                display-name "Launchpad Pro"}}]
  {:pre [(some? show)]}
  (let [idle (atom true)
        controller (atom nil)]
    (letfn [(disconnection-handler []
              (reset! controller nil)
              (reset! idle true))
            (connection-handler [device]
              (when (compare-and-set! idle true false)
                (if (and (nil? @controller) (seq (amidi/filter-devices device-filter [device])))
                    (let [port-in (first (amidi/filter-devices device-filter (amidi/open-inputs-if-needed!)))
                          port-out (first (amidi/filter-devices device-filter (amidi/open-outputs-if-needed!)))]
                      (when (every? some? [port-in port-out])  ; We found our Launchpad! Bind to it in the background.
                        (timbre/info "Auto-binding to" device)
                        (future
                          (Thread/sleep 3000)  ; Allow for firmware's own welcome animation
                          (reset! controller (bind-to-show show :device-filter device-filter
                                                           :refresh-interval refresh-interval
                                                           :display-name display-name))
                          (amidi/add-disconnected-device-handler! (:port-in @controller) disconnection-handler))))
                    (reset! idle true))))
            (cancel-handler []
              (amidi/remove-new-device-handler! connection-handler)
              (when-let [device (:port-in @controller)]
                (amidi/remove-disconnected-device-handler! device disconnection-handler)))]

      ;; See if our Launchpad seems to already be connected, and if so, bind to it right away.
      (when-let [found (first (amidi/filter-devices device-filter (amidi/open-inputs-if-needed!)))]
        (connection-handler found))
      
      ;; Set up to bind when connected in future.
      (amidi/add-new-device-handler! connection-handler)

      ;; Return a watcher object which can provide access to the bound controller, and be canceled later.
      (with-meta
        {:controller controller
         :device-filter device-filter
         :cancel cancel-handler}
        {:type :launchpad-pro-watcher}))))
