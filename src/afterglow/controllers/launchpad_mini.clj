(ns afterglow.controllers.launchpad-mini
  "Allows the Novation Launchpad Mini to be used as a control surface
  for Afterglow."
  {:author "James Elliott"}
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

(defonce ^{:doc "Counts the controller bindings which have been made,
  so each can be assigned a unique ID."}
  controller-counter (atom 0))

(defonce ^{:doc "Controllers which are currently bound to shows,
  indexed by the controller binding ID."}
  active-bindings (atom {}))

(def control-buttons
  "The round buttons which send and respond to Control Change events.
  These assignments don't correspond with any standard decal beyond
  the first four (arrows), but reflect Afterglow's needs. Maybe
  someone can make an Afterglow sticker for us."
  {:up-arrow    104
   :down-arrow  105
   :left-arrow  106
   :right-arrow 107
   :tap-tempo   108
   :shift       109
   :device-mode 110
   :stop        111})

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
  "The color of the stop button when active."
  0x0f)

(def click-unsynced-beat-color
  "The color of the tap tempo button when synchronization is off and a
  beat is taking place."
  0x3e)

(def click-unsynced-off-beat-color
  "The color of the tap tempo button when synchronization is off and a
  beat is not taking place."
  0x1d)

(def click-synced-beat-color
  "The color of the tap tempo button when the metronome is
  synchronzied and a beat is taking place."
  0x3c)

(def click-synced-off-beat-color
  "The color of the tap tempo button when the metronome is
  synchronized and a beat is not taking place."
  0x1c)

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

(defn- move-origin
  "Changes the origin of the controller, notifying any registered
  listeners."
  [controller origin]
  (when (not= origin @(:origin controller))
    (reset! (:origin controller) origin)
    (doseq [f @(:move-listeners controller)] (f @(:grid-controller-impl controller) :moved))))



(defn clear-interface
  "Clears all illuminated buttons and pads."
  [controller]
  (midi/midi-control (:port-out controller) 0 0)
  (midi/midi-control (:port-out controller) 0 1)
  (reset! (:last-grid-pads controller) nil)
  (reset! (:last-control-buttons controller) {}))




(defn- midi-received
  "Called whenever a MIDI message is received while the controller is
  active; checks if it came in on the right port, and if so, decides
  what should be done."
  [controller message]
  #_(when (amidi/same-device? (:device message) (:port-in controller))
    ;;(timbre/info message)
    (when-not (controllers/overlay-handled? (:overlays controller) message)
      (when (= (:command message) :control-change)
        (control-change-received controller message))
      (when (= (:command message) :note-on)
        (note-on-received controller message))
      (when (= (:command message) :note-off)
        (note-off-received controller message)))))





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
  {:pre [(#{:launchpad-mini :launchpad-mini-watcher} (type controller))]}
  (if (= (type controller) :launchpad-mini-watcher)
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
        (set-control-button-color controller (:user-mode control-buttons) button-available-color))

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

(defonce ^{:doc "Deactivates any Launchpad Mini bindings when Java is shutting down."
           :private true}
  shutdown-hook
  (let [hook (Thread. deactivate-all)]
    (.addShutdownHook (Runtime/getRuntime) hook)
    hook))

(defn- valid-identity
  "Checks that the device we are trying to bind to reports the proper
  identity in response to a MIDI Device Inquiry message. This also
  gives it time to boot if it has just powered on. Returns the
  assigned device ID if the identity is correct, or logs an error and
  returns nil if it is not."
  [port-in port-out]
  (let [ident (controllers/identify port-in port-out)]
    (if (= (take 5 (drop 4 (:data ident))) '(0 32 41 54 0))
      (aget (:data ident) 1)
      (timbre/error "Device does not identify as a Launchpad Mini:" port-in))))

(defn bind-to-show
  "Establish a connection to the Novation Launchpad Mini, for managing
  the given show.

  Makes sure it identifies as a Launchpad Mini, then initializes the
  display and starts the UI updater thread. Since SysEx messages are
  required for communicating with it, if you are on a Mac, you must
  install [CoreMIDI4J](https://github.com/DerekCook/CoreMidi4J) to
  provide a working implementation. (If you need to work with Java
  1.6, you can instead
  use [mmj](http://www.humatic.de/htools/mmj.htm), but that is no
  longer developed, and does not support connecting or disconnecting
  MIDI devices after Java has started.)

  If you have more than one Launchpad Mini connected, or have renamed
  how it appears in your list of MIDI devices, you need to supply a
  value after `:device-filter` which identifies the ports to be used
  to communicate with the Launchpad you want this function to use. The
  values returned by [[afterglow.midi/open-inputs-if-needed!]]
  and [[afterglow.midi/open-outputs-if-needed!]] will be searched, and
  the first port that matches with [[filter-devices]] will be used.

  The controller will be identified in the user interface (for the
  purposes of linking it to the web cue grid) as \"Launchpad Mini\".
  If you would like to use a different name (for example, if have more
  than one Launchpad), you can pass in a custom value after
  `:display-name`.

  If you want the user interface to be refreshed at a different rate
  than the default of fifteen times per second, pass your desired
  number of milliseconds after `:refresh-interval`.

  If you would like to skip the startup animation (for example because
  the device has just powered on and run its own animation), pass
  `true` after `:skip-animation`."
  [show & {:keys [device-filter refresh-interval display-name skip-animation]
           :or   {device-filter    "Mini"
                  refresh-interval (/ 1000 15)
                  display-name     "Launchpad Mini"}}]
  {:pre [(some? show)]}
  (let [port-in  (first (amidi/filter-devices device-filter (amidi/open-inputs-if-needed!)))
        port-out (first (amidi/filter-devices device-filter (amidi/open-outputs-if-needed!)))]
    (if (and (every? some? [port-in port-out]) (valid-identity port-in port-out))
      (let [shift-mode (atom false)
            controller
            (with-meta
              {:id                   (swap! controller-counter inc)
               :display-name         display-name
               :show                 show
               :origin               (atom [0 0])
               :refresh-interval     refresh-interval
               :port-in              port-in
               :port-out             port-out
               :task                 (atom nil)
               :last-control-buttons (atom {})
               :next-control-buttons (atom {})
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
              {:type :launchpad-mini})]
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
        (clear-interface controller)
        #_(if skip-animation
          (start-interface controller)
          (welcome-animation controller))
        (swap! active-bindings assoc (:id controller) controller)
        (show/register-grid-controller @(:grid-controller-impl controller))
        (amidi/add-disconnected-device-handler! port-in #(deactivate controller :disconnected true))
        controller)
      (timbre/error "Unable to find Launchpad Mini" (amidi/describe-device-filter device-filter)))))

(defn auto-bind
  "Watches for a Novation Launchpad Mini controller to be connected,
  and as soon as it is, binds it to the specified show
  using [[bind-to-show]]. If that controller ever gets disconnected,
  it will be re-bound once it reappears. Returns a watcher structure
  which can be passed to [[deactivate]] if you would like to stop it
  watching for reconnections. The underlying controller mapping, once
  bound, can be accessed through the watcher's `:controller` key.

  If you have more than one Launchpad Mini that might beconnected, or
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
           :or {device-filter "Mini"
                refresh-interval (/ 1000 15)
                display-name "Launchpad Mini"}}]
  {:pre [(some? show)]}
  (let [idle (atom true)
        controller (atom nil)]
    (letfn [(disconnection-handler []
              (reset! controller nil)
              (reset! idle true))
            (connection-handler
              ([device]
               (connection-handler device true))
              ([device wait-for-boot]
               (when (compare-and-set! idle true false)
                 (if (and (nil? @controller) (seq (amidi/filter-devices device-filter [device])))
                   (let [port-in (first (amidi/filter-devices device-filter (amidi/open-inputs-if-needed!)))
                         port-out (first (amidi/filter-devices device-filter (amidi/open-outputs-if-needed!)))]
                     (when (every? some? [port-in port-out])  ; We found our Launchpad! Bind to it in the background.
                       (timbre/info "Auto-binding to Launchpad Mini" device)
                       (future
                         (when wait-for-boot (Thread/sleep 3000)) ; Allow for firmware's own welcome animation
                         (reset! controller (bind-to-show show :device-filter device-filter
                                                          :refresh-interval refresh-interval
                                                          :display-name display-name
                                                          :skip-animation wait-for-boot))
                         (amidi/add-disconnected-device-handler! (:port-in @controller) disconnection-handler))))
                   (reset! idle true)))))
            (cancel-handler []
              (amidi/remove-new-device-handler! connection-handler)
              (when-let [device (:port-in @controller)]
                (amidi/remove-disconnected-device-handler! device disconnection-handler)))]

      ;; See if our Launchpad seems to already be connected, and if so, bind to it right away.
      (when-let [found (first (amidi/filter-devices device-filter (amidi/open-inputs-if-needed!)))]
        (connection-handler found false))

      ;; Set up to bind when connected in future.
      (amidi/add-new-device-handler! connection-handler)

      ;; Return a watcher object which can provide access to the bound controller, and be canceled later.
      (with-meta
        {:controller controller
         :device-filter device-filter
         :cancel cancel-handler}
        {:type :launchpad-mini-watcher}))))
