(ns afterglow.controllers.ableton-push
  "Allows the Ableton Push to be used as a control surface for
  Afterglow."
  {:author "James Elliott"
   :doc/format :markdown}
  (:require [afterglow.controllers :as controllers]
            [afterglow.effects.dimmer :refer [master-get-level master-set-level]]
            [afterglow.midi :as amidi]
            [afterglow.rhythm :as rhythm]
            [afterglow.show :as show]
            [afterglow.util :as util]
            [clojure.math.numeric-tower :as math]
            [environ.core :refer [env]]
            [com.evocomputing.colors :as colors]
            [overtone.midi :as midi]
            [overtone.at-at :as at-at]
            [taoensso.timbre :refer [info warn]])
  (:import [java.util Arrays]))

(defonce
  ^{:private true
    :doc "Protect protocols against namespace reloads"}
  _PROTOCOLS_
  (do
    (defprotocol IOverlay
  "An activity which takes over part of the user interface
  while it is active."
  (captured-controls [this]
    "The MIDI control-change events that will be consumed by this
  overlay while it is active, a set of integers.")
  (captured-notes [this]
    "The MIDI note events that will be consumed by this overlay while
  it is active, a set of integers.")
  (adjust-interface [this controller]
    "Set values for the next frame of the controller interface; return
  a falsey value if the overlay is finished and should be removed.")
  (handle-control-change [this controller message]
    "Called when a MIDI control-change event matching the
  captured-controls lists has been received. Return a truthy value if
  the overlay has consumed the event, so it should not be processed
  further. If the special value :done is returned, it further
  indicates the overlay is finished and should be removed.")
  (handle-note-on [this controller message]
    "Called when a MIDI note-on event matching the captured-notes
  lists has been received. Return a truthy value if the overlay has
  consumed the event, so it should not be processed further. If the
  special value :done is returned, it further indicates the overlay is
  finished and should be removed.")
  (handle-note-off [this controller message]
    "Called when a MIDI note-off event matching the captured-notes
  lists has been received. Return a truthy value if the overlay has
  consumed the event, so it should not be processed further. If the
  special value :done is returned, it further indicates the overlay is
  finished and should be removed."))))

(defn add-overlay
  "Add a temporary overlay to the interface."
  [controller overlay]
  (let [id (swap! (:next-overlay controller) inc)]
    (swap! (:overlays controller) assoc id overlay)))

(defn add-pad-held-feedback-overlay
  "Builds a simple overlay which just adds something to the user
  interface until a particular pad is released, and adds it to the
  controller. The overlay will end when a control-change message with
  value 0 is sent to the specified control number. Other than that,
  all it does is call the supplied function every time the interface
  is being updated."
  [controller control-num f]
  (add-overlay controller
               (reify IOverlay
                 (captured-controls [this] #{control-num})
                 (captured-notes [this] #{})
                 (adjust-interface [this controller] (f))
                 (handle-control-change [this controller message]
                   (when (zero? (:velocity message))
                     :done))
                 (handle-note-off [this controller message])
                 (handle-note-on [this controller message]))))

(defn velocity-for-color
  "Given a target color, calculate the MIDI note velocity which will
  achieve the closest approximation available on an Ableton Push
  pad, using the thoughtful hue palette provided by Ableton:

  ![Push pad palette](http://deepsymmetry.org/afterglow/research/PushColors.jpg)"
  {:doc/format :markdown}
  [color]
  {:pre [(= (type color) :com.evocomputing.colors/color)]}
  ;; Each hue section starts with a lightened version
  ;; of the hue, then a bright, medium, and dim version
  ;; of the fully-saturated hue.
  (let [brightness-shift (condp < (colors/lightness color)
                           60 0
                           37 1
                           15 2
                           3)]
    (cond (< (colors/lightness color) 3)
          ;; The color is effectively black.
          0

          ;; The color is effectively gray, so map it to the grayscale
          ;; section, which ranges from black at zero, through white as
          ;; three.
          (< (colors/saturation color) 20)
          (min 3 (- 4 brightness-shift))

          ;; Find the note value that approximates the hue and lightness.
          ;; From note 4 to note 59, the pads are in groups of four for
          ;; a single hue, starting with the lightened version, then
          ;; the bright, medium, and dim versions.
          :else
          (let [hue-section (+ 4 (* 4 (math/floor (* 13 (/ (colors/hue color) 360)))))]
            (+ hue-section brightness-shift)))))

(defonce ^{:doc "The color of buttons that are completely off."}
  off-color (com.evocomputing.colors/create-color :black))

(defn set-pad-color
  "Set the color of one of the 64 touch pads to the closest
  approximation available for a desired color."
  [controller x y color]
  {:pre [(<= 0 x 7) (<= 0 y 7)]}
  (let [note (+ 36 x (* y 8))]  ;; Calculate note from grid coordinates
    (midi/midi-note-on (:port-out controller) note (velocity-for-color color))))

(def monochrome-button-states
  "The control values and modes for a labeled button which does not
  change color."
  {:off 0 :dim 1 :dim-slow-blink 2 :dim-fast-blink 3
   :bright 4 :bright-slow-blink 5 :bright-fast-blink 6})

(def color-button-colors
  "The control values and modes for a labeled button which changes
  color. These are added to the monocrome states (except for off)
  to obtain the color and brightness/behavior."
  {:red 0 :amber 6 :yellow 12 :green 18})

(def control-buttons
  "The labeled buttons which send and respond to Control Change
  events."
  {:tap-tempo {:control 3 :kind :monochrome}
   :metronome {:control 9 :kind :monochrome}

   :master {:control 28 :kind :monochrome}
   :stop   {:control 29 :kind :monochrome}

   :quarter               {:control 36 :kind :color}
   :quarter-triplet       {:control 37 :kind :color}
   :eighth                {:control 38 :kind :color}
   :eighth-triplet        {:control 39 :kind :color}
   :sixteenth             {:control 40 :kind :color}
   :sixteenth-triplet     {:control 41 :kind :color}
   :thirty-second         {:control 42 :kind :color}
   :thirty-second-triplet {:control 43 :kind :color}

   :left-arrow  {:control 44 :kind :monochrome}
   :right-arrow {:control 45 :kind :monochrome}
   :up-arrow    {:control 46 :kind :monochrome}
   :down-arrow  {:control 47 :kind :monochrome}

   :select      {:control 48 :kind :monochrome}
   :shift       {:control 49 :kind :monochrome}
   :note        {:control 50 :kind :monochrome}
   :session     {:control 51 :kind :monochrome}
   :add-device  {:control 52 :kind :monochrome}
   :add-track   {:control 53 :kind :monochrome}

   :octave-down {:control 54 :kind :monochrome}
   :octave-up   {:control 55 :kind :monochrome}
   :repeat      {:control 56 :kind :monochrome}
   :accent      {:control 57 :kind :monochrome}
   :scales      {:control 58 :kind :monochrome}
   :user-mode   {:control 59 :kind :monochrome}
   :mute        {:control 60 :kind :monochrome}
   :solo        {:control 61 :kind :monochrome}
   :in          {:control 62 :kind :monochrome}
   :out         {:control 63 :kind :monochrome}

   :play         {:control 85 :kind :monochrome}
   :record       {:control 86 :kind :monochrome}
   :new          {:control 87 :kind :monochrome}
   :duplicate    {:control 88 :kind :monochrome}
   :automation   {:control 89 :kind :monochrome}
   :fixed-length {:control 90 :kind :monochrome}

   :device-mode   {:control 110 :kind :monochrome}
   :browse-mode   {:control 111 :kind :monochrome}
   :track-mode    {:control 112 :kind :monochrome}
   :clip-mode     {:control 113 :kind :monochrome}
   :volume-mode   {:control 114 :kind :monochrome}
   :pan-send-mode {:control 115 :kind :monochrome}

   :quantize {:control 116 :kind :monochrome}
   :double   {:control 117 :kind :monochrome}
   :delete   {:control 118 :kind :monochrome}
   :undo     {:control 119 :kind :monochrome}})

(def special-symbols
  "The byte values which draw special-purpose characters on the Push
  display."
  {:up-arrow (byte 0)
   :down-arrow (byte 1)
   :pancake (byte 2)
   :fader-left (byte 3)
   :fader-right (byte 4)
   :fader-center (byte 5)
   :fader-empty (byte 6)
   :folder (byte 7)
   :split-vertical-line (byte 8)
   :degree (byte 9)
   :ellipsis (byte 28)
   :solid-block (byte 29)
   :right-arrow (byte 30)
   :left-arrow (byte 31)
   :selected-triangle (byte 127)})

(defn button-state
  "Calculate the numeric value that corresponds to a particular
  named state for the specified button, and (if supported and
  supplied), a named color."
  ([button state]
   (button-state button state :amber))
  ([button state color-key]
   (let [base-value ((keyword state) monochrome-button-states)
         color-shift (or (when (and (= (:kind button) :color)
                                    (not= state :off))
                           ((keyword color-key) color-button-colors))
                         0)]
     (+ base-value color-shift))))

(defn set-button-state
  "Set one of the labeled buttons to a particular state, and, if
  supported, color. If the state is already a number, it is used
  as-is, otherwise it is calculated using button-state."
  ([controller button state]
   (set-button-state controller button state :amber))
  ([controller button state color-key]
   (let [state (if (number? state)
                 state
                 (button-state button state color-key))]
     (midi/midi-control (:port-out controller) (:control button) state))))

(defn top-pad-state
  "Calculate the numeric value that corresponds to a particular
  named state for the specified top-row pad, and (if supplied),
  named color."
  ([state]
   (top-pad-state state :amber))
  ([state color-key]
   (let [base-value ((keyword state) monochrome-button-states)
         color-shift (or (when-not (= state :off)
                           ((keyword color-key) color-button-colors))
                         0)]
     (+ base-value color-shift))))

(defn set-top-pad-state
  "Set one of the top-row pads to a particular state and color.
  If state is already a number, it is used as-is, otherwise it is
  calculated using top-pad-state."
  ([controller x state]
   (set-top-pad-state controller x state :amber))
  ([controller x state color-key]
   {:pre [(<= 0 x 7)]}
   (let [state (if (number? state)
                 state
                 (top-pad-state state color-key))]
     (midi/midi-control (:port-out controller) (+ x 20) state))))

(defn set-second-pad-color
  "Set the color of one of the 8 second-row touch pads (right above
  the 8x8 pad of larger, velocity sensitive, pads) to the closest
  approximation available for a desired color."
  [controller x color]
  {:pre [(<= 0 x 7)]}
  (let [control (+ 102 x)]  ;; Calculate controller number
    (midi/midi-control (:port-out controller) control (velocity-for-color color))))

(defn set-display-line
  "Sets a line of the text display."
  [controller line bytes]
  {:pre [(<= 0 line 3)]}
  (let [message (concat [240 71 127 21 (+ line 24) 0 69 0]
                        (take 68 (concat (map int bytes) (repeat 32)))
                        [247])]
    (midi/midi-sysex (:port-out controller) message)))
 
(defn clear-display-line
  "Clears a line of the text display."
  [controller line]
  {:pre [(<= 0 line 3)]}
  (midi/midi-sysex (:port-out controller) [240 71 127 21 (+ line 28) 0 0 247]))

(defn- show-labels
  "Illuminates all buttons with text labels, for development assistance."
  ([controller]
   (show-labels controller :bright :amber))
  ([controller state]
   (show-labels controller state :amber))
  ([controller state color]
   (doseq [[_ button] control-buttons]
     (set-button-state controller button state color))))

(defn update-text
  "Sees if any text has changed since the last time the display
  was updated, and if so, sends the necessary MIDI SysEx values
  to update it on the Push."
  [controller]
  (doseq [row (range 4)]
    (when-not (.equals (get (:next-display controller) row)
                       (get (:last-display controller) row))
      (set-display-line controller row (get (:next-display controller) row))
      (System/arraycopy (get (:next-display controller) row) 0
                        (get (:last-display controller) row) 0 68))))

(defn update-top-pads
  "Sees if any of the top row of pads have changed state since
  the interface was updated, and if so, sends the necessary MIDI
  control values to update them on the Push."
  [controller]
  (doseq [x (range 8)]
    (let [next-state (aget (:next-top-pads controller) x)]
      (when (not= next-state
                  (aget (:last-top-pads controller) x))
        (set-top-pad-state controller x next-state)
        (aset (:last-top-pads controller) x next-state)))))

(defn update-text-buttons
  "Sees if any labeled buttons have changed state since the last time
  the interface was updated, and if so, sends the necessary MIDI
  control values to update them on the Push."
  [controller]
  ;; First turn off any which were on before but no longer are
  (doseq [[button old-state] @(:last-text-buttons controller)]
    (when-not (button @(:next-text-buttons controller))
      (when-not (#{0 :off} old-state)
        (set-button-state controller button :off))))

  ;; Then, set any currently requested states
  (doseq [[button state] @(:next-text-buttons controller)]
    (set-button-state controller button state))

  ;; And record the new state for next time
  (reset! (:last-text-buttons controller) @(:next-text-buttons controller)))

(defn write-display-cell
  "Update a single text cell (of which there are four per row) in the
  display to be rendered on the next update."
  [controller row cell text]
  {:pre [(<= 0 row 3) (<= 0 cell 3)]}
  (let [bytes (take 17 (concat (map int text) (repeat 32)))]
    (doseq [[i val] (map-indexed vector bytes)]
      (aset (get (:next-display controller) row) (+ (* cell 17) i) (util/ubyte val)))))

(defn make-gauge
  "Create a graphical gauge with an indicator that fills a line.
  The default range is from zero to a hundred, and the default size is
  17 characters, or a full display cell."
  [value & {:keys [lowest highest width] :or {lowest 0 highest 100 width 17}}]
  (let [range (- highest lowest)
        scaled (int (* 2 width (/ (- value lowest) range)))
        marker ((if (< (- value lowest) 0.1)
                  :fader-empty
                  (if (even? scaled) :fader-left :fader-center))
                special-symbols)
        leader (take (int (/ scaled 2)) (repeat (:fader-center special-symbols)))]
    (take width (concat leader [marker] (repeat (:fader-empty special-symbols))))))

(defn make-pan-gauge
  "Create a graphical gauge with an indicator that moves along a line.
  The default range is from zero to a hundred, and the default size is
  17 characters, or a full display cell. The centered display only
  works for an odd width, so it is suppressed otherwise."
  [value & {:keys [lowest highest width] :or {lowest 0 highest 100 width 17}}]
  (let [range (* 1.01 (- highest lowest))
        midpoint (/ (- highest lowest) 2)
        scaled (int (* 2 width (/ (- value lowest) range)))
        filler (repeat (:fader-empty special-symbols))
        centered (and (odd? width)
                      (< (math/abs (- (- value lowest) midpoint)) (/ range 200)))
        marker ((if centered
                   :fader-center
                   (if (even? scaled) :fader-left :fader-right))
                special-symbols)
        leader (take (int (/ scaled 2)) filler)]
    (take width (concat leader [marker] filler))))

(defn- interpret-tempo-tap
  "React appropriately to a tempo tap, based on the sync mode of the
  show metronome."
  [controller]
  ;; TODO: Do other things when synced.
  ((:tap-tempo-handler controller)))

(defn- bpm-adjusting-interface
  "Add an arrow showing the BPM is being adjusted."
  [controller]
  ;; TODO: Unless it is being externally synced...
  (aset (get (:next-display controller) 2) 16 (:up-arrow special-symbols)))

(defn sign-velocity
  "Convert a midi velocity to its signed equivalent, to translate
  encoder rotations, which are twos-complement seven bit numbers."
  [val]
   (if (>= val 64)
     (- val 128)
     val))

(defn- adjust-bpm-from-encoder
  "Adjust the current BPM based on how the encoder was twisted."
  [controller message]
  (let [delta (/ (sign-velocity (:velocity message)) 10)
        bpm (rhythm/metro-bpm (:metronome (:show controller)))]
    (rhythm/metro-bpm (:metronome (:show controller)) (+ bpm delta))))

(defn- encoder-above-bpm-touched
  "Add a user interface overlay to give feedback when turning the
  encoder above the BPM display when metronome was already active,
  since it is easy to grab that one rather than the actual BPM
  encoder, being right above the display."
  [controller]
  (add-overlay controller
               (reify IOverlay
                 (captured-controls [this] #{72})
                 (captured-notes [this] #{1 9})
                 (adjust-interface [this controller]
                   (bpm-adjusting-interface controller))
                 (handle-control-change [this controller message]
                   (adjust-bpm-from-encoder controller message))
                 (handle-note-on [this controller message]
                   ;; Suppress the actual BPM encoder while we are active.
                   true)
                 (handle-note-off [this controller message]
                   (when (= (:note message) 1)
                     ;; They released us, end the overlay.
                     :done)))))

(defn- beat-adjusting-interface
  "Add an arrow showing the beat is being adjusted."
  [controller]
  (let [marker (rhythm/metro-marker (:metronome (:show controller)))
                         arrow-pos (if @(:shift-mode controller)
                                     (dec (.indexOf marker "." (inc (.indexOf marker "."))))
                                     (dec (count marker)))]
    (aset (get (:next-display controller) 2) arrow-pos (:up-arrow special-symbols))))

(defn- adjust-beat-from-encoder
  "Adjust the current beat based on how the encoder was twisted."
  [controller message]
  (let [delta (sign-velocity (:velocity message))
                         snapshot (rhythm/metro-snapshot (:metronome (:show controller)))]
                     (if @(:shift-mode controller)
                       ;; User is adjusting the current bar
                       (rhythm/metro-bar-start (:metronome (:show controller)) (+ (:bar snapshot) delta))
                       ;; User is adjusting the current beat; keep the phase unchanged
                       (do
                         (rhythm/metro-start (:metronome (:show controller)) (+ (:beat snapshot) delta))
                         (rhythm/metro-beat-phase (:metronome (:show controller) (:beat-phase snapshot)))))))

(defn- encoder-above-beat-touched
  "Add a user interface overlay to give feedback when turning the
  encoder above the beat display when metronome was already active,
  since it is easy to grab that one rather than the actual beat
  encoder, being right above the display."
  [controller]
  (add-overlay controller
               (reify IOverlay
                 (captured-controls [this] #{71})
                 (captured-notes [this] #{0 10})
                 (adjust-interface [this controller]
                   (beat-adjusting-interface controller))
                 (handle-control-change [this controller message]
                   (adjust-beat-from-encoder controller message))
                 (handle-note-on [this controller message]
                   ;; Suppress the actual beat encoder while we are active.
                   true)
                 (handle-note-off [this controller message]
                   (when (zero? (:note message))
                     ;; They released us, end the overlay.
                     :done)))))

(defn- enter-metronome-showing
  "Activate the persistent metronome display, with sync and reset pads
  illuminated."
  [controller]
  (swap! (:metronome-mode controller) assoc :showing true)
  (add-overlay controller
               (reify IOverlay
                 (captured-controls [this] #{3 9 20 21})
                 (captured-notes [this] #{0 1})
                 (adjust-interface [this controller]
                   ;; Make the metronome button bright, since its information is active
                   (swap! (:next-text-buttons controller)
                          assoc (:metronome control-buttons)
                          (button-state (:metronome control-buttons) :bright))

                   ;; Add the labels for reset and sync, and light the pads
                   (write-display-cell controller 3 0 "Reset    Manual")
                   (aset (:next-top-pads controller) 0 (top-pad-state :dim :red))
                   (aset (:next-top-pads controller) 1 (top-pad-state :dim :green)))
                 (handle-control-change [this controller message]
                   (case (:note message)
                     3 ; Tap tempo button
                     (when (pos? (:velocity message))
                       (interpret-tempo-tap controller)
                       true)
                     
                     9 ; Metronome button
                     (when (pos? (:velocity message))
                       (swap! (:metronome-mode controller) dissoc :showing)
                       ;; Exit the overlay
                       :done)

                     20 ; Reset pad
                     (when (pos? (:velocity message))
                       (rhythm/metro-phrase-start (:metronome (:show controller)) 1)
                       (add-pad-held-feedback-overlay controller 20
                                                      #(aset (:next-top-pads controller)
                                                             0 (top-pad-state :bright :red)))
                       true)
                     21 ; Sync pad
                     (when (pos? (:velocity message))
                       ;; TODO: Actually implement a new overlay
                       (add-pad-held-feedback-overlay controller 21
                                                      #(aset (:next-top-pads controller)
                                                             1 (top-pad-state :bright :green)))
                       true)))
                 (handle-note-on [this controller message]
                   ;; Whoops, user grabbed encoder closest to beat or BPM display
                   (case (:note message)
                     0 (encoder-above-beat-touched controller)
                     1 (encoder-above-bpm-touched controller))
                   true)
                 (handle-note-off [this controller message]
                   false))))

(defn- update-metronome-section
  "Updates the sections of the interface related to metronome
  control."
  [controller]
  (let [metronome (:metronome (:show controller))
        metronome-button (:metronome control-buttons)
        tap-tempo-button (:tap-tempo control-buttons)
        metronome-mode @(:metronome-mode controller)]
    ;; Is the first cell reserved for metronome information?
    (if (seq metronome-mode)
      ;; Draw the beat and BPM information
      (let [metronome (:metronome (:show controller))
            marker (rhythm/metro-marker metronome)
            bpm (format "%.1f" (float (rhythm/metro-bpm metronome)))
            chars (+ (count marker) (count bpm))
            padding (clojure.string/join (take (- 17 chars) (repeat " ")))]
        (write-display-cell controller 1 0 (str marker padding bpm))
        (write-display-cell controller 0 0 "Beat        BPM  ")

        ;; Make the metronome button bright, since some overlay is present
        (swap! (:next-text-buttons controller)
               assoc metronome-button
               (button-state metronome-button :bright)))

      ;; The metronome section is not active, so make its button dim
      (swap! (:next-text-buttons controller)
             assoc metronome-button (button-state metronome-button :dim)))
    ;; Regardless, flash the tap tempo button on beats
    (swap! (:next-text-buttons controller)
           assoc tap-tempo-button
           (button-state tap-tempo-button
                         (if (< (rhythm/metro-beat-phase metronome) 0.15)
                           :bright :dim)))))

(defn update-interface
  "Determine the desired current state of the interface, and send any
  changes needed to get it to that state."
  [controller]
  (try
    ;; Assume we are starting out with a blank interface.
    (doseq [row (range 4)]
      (Arrays/fill (get (:next-display controller) row) (byte 32)))
    (reset! (:next-text-buttons controller) {})
    (Arrays/fill (:next-top-pads controller) 0)

    ;; TODO: Loop over the most recent four active cues, rendering information
    ;;       about them.

    (update-metronome-section controller)

    ;; Reflect the shift button state
    (swap! (:next-text-buttons controller)
           assoc (:shift control-buttons)
           (button-state (:shift control-buttons)
                         (if @(:shift-mode controller) :bright :dim)))
    
    ;; Make the User button bright, since we live in User mode
    (swap! (:next-text-buttons controller)
           assoc (:user-mode control-buttons)
           (button-state (:user-mode control-buttons) :bright))

    ;; Add any contributions from interface overlays, removing them
    ;; if they report being finished. Reverse the order so that the
    ;; most recent overlays run last, having the opportunity to
    ;; override older ones.
    (doseq [[k overlay] (reverse @(:overlays controller))]
      (when-not (adjust-interface overlay controller)
        (swap! (:overlays controller) dissoc k)))

    (update-text controller)
    (update-top-pads controller)
    (update-text-buttons controller)

    (catch Throwable t
      (warn t "Problem updating Ableton Push Interface"))))

(declare clear-interface)

(defn welcome-frame
  "Render a frame of the welcome animation, or if it is done, start
  the main interface update thread, and terminate the task running the
  animation."
  [controller counter task]
  (try
    (cond
      (< @counter 8)
      (doseq [y (range 0 (inc @counter))]
        (let [color (com.evocomputing.colors/create-color
                     :h 0 :s 0 :l (max 10 (- 50 (/ (* 50 (- @counter y)) 4))))]
          (set-pad-color controller 3 y color)
          (set-pad-color controller 4 y color)))

      (< @counter 12)
      (doseq [x (range 0 (- @counter 7))
              y (range 0 8)]
        (let [color (com.evocomputing.colors/create-color
                     :h 340 :s 100 :l (if (= x (- @counter 8)) 75 50))]
          (set-pad-color controller (- 3 x) y color)
          (set-pad-color controller (+ 4 x) y color)))

      (< @counter 15)
      (doseq [y (range 0 8)]
        (let [color (com.evocomputing.colors/create-color
                     :h (* 13 (- @counter 11)) :s 100 :l 50)]
          (set-pad-color controller (- @counter 7) y color)
          (set-pad-color controller (- 14 @counter) y color)))

      (= @counter 15)
      (show-labels controller :bright :amber)
      
      (= @counter 16)
      (doseq [x (range 0 8)]
        (set-top-pad-state controller x :bright :amber))
      
      (= @counter 17)
      (doseq [x (range 0 8)]
        (set-second-pad-color controller x
                                (com.evocomputing.colors/create-color :h 45 :s 100 :l 50))
        (set-top-pad-state controller x :bright :red))

      (< @counter 26)
      (doseq [x (range 0 8)]
        (let [color (com.evocomputing.colors/create-color
                     :h (+ 60 (* 40 (- @counter 18))) :s 100 :l 50)]
          (set-pad-color controller x (- 25 @counter) color)))
      
      (= @counter 26)
      (do
        (show-labels controller :dim :amber)
        (doseq [x (range 0 8)]
          (set-top-pad-state controller x :off)))

      (= @counter 27)
      (doseq [x (range 0 8)]
          (set-second-pad-color controller x off-color))

      (< @counter 36)
      (doseq [x (range 0 8)]
        (set-pad-color controller x (- 35 @counter) off-color))
      
      :else
      (do
        (clear-interface controller)
        (amidi/add-global-handler! @(:midi-handler controller))
        (enter-metronome-showing controller)
        (reset! (:task controller) (at-at/every (:refresh-interval controller)
                                                #(update-interface controller)
                                                controllers/pool
                                                :initial-delay 10
                                                :desc "Push interface update"))
        (at-at/kill @task)))
    (catch Throwable t
      (warn t "Animation frame failed")))

  (swap! counter inc))

(defn welcome-animation
  "Provide a fun animation to make it clear the Push is online."
  [controller]
  (set-display-line controller 0 (concat (repeat 24 \space) (seq "Welcome toAfterglow")))
  (set-display-line controller 2 (concat (repeat 27 \space)
                              (seq (str "version" (env  :afterglow-version)))))
  (let [counter (atom 0)
        task (atom nil)]
    (reset! task (at-at/every 30 #(welcome-frame controller counter task)
                              controllers/pool))))

(defn clear-interface
  "Clears the text display and all illuminated buttons and pads."
  [controller]
  (doseq [line (range 4)]
    (clear-display-line controller line)
    (Arrays/fill (get (:last-display controller) line) (byte 32)))

  (doseq [x (range 8)]
    (set-top-pad-state controller x :off)
    (set-second-pad-color controller x off-color)
    (doseq [y (range 8)]
      (set-pad-color controller x y off-color)))
  (Arrays/fill (:last-top-pads controller) 0)
  (doseq [[_ button] control-buttons]
    (set-button-state controller button :off))
  (reset! (:last-text-buttons controller) {}))

(defonce ^{:doc "Counts the controller bindings which have been made,
  so each can be assigned a unique ID."}
  controller-counter (atom 0))

(defonce ^{:doc "Controllers which are currently bound to shows,
  indexed by the controller binding ID."}
  active-bindings (atom {}))

(defn- overlay-handled?
  "See if there is an interface overlay active which wants to consume
  this message; if so, send it, and see if the overlay consumes it.
  Returns truthy if an overlay consumed the message, and it should
  not be given to anyone else."
  [controller message]
  (case (:command message)
    (:note-on :note-off)
    (some (fn [[k overlay]]
            (when (contains? (captured-notes overlay) (:note message))
              (let [result (if (= :note-on (:command message))
                             (handle-note-on overlay controller message)
                             (handle-note-off overlay controller message))]
                (when (= result :done)
                  (swap! (:overlays controller) dissoc k))
                result)))
          (seq @(:overlays controller)))

    :control-change
    (some (fn [[k overlay]]
            (when (contains? (captured-controls overlay) (:note message))
              (let [result (handle-control-change overlay controller message)]
                (when (= result :done)
                  (swap! (:overlays controller) dissoc k))
                result)))
          (seq @(:overlays controller)))

    ;; Nothing we process
    false))

(defn- master-encoder-touched
  "Add a user interface overlay to give feedback when turning the
  master encoder."
  [controller]
  (add-overlay controller
               (reify IOverlay
                 (captured-controls [this] #{79})
                 (captured-notes [this] #{8})
                 (adjust-interface [this controller]
                   (let [level (master-get-level (get-in controller [:show :grand-master]))]
                     (write-display-cell controller 0 3
                                         (str "GrandMaster " (format "%5.1f" level)))
                     (write-display-cell controller 1 3 (make-gauge level)))
                   true)
                 (handle-control-change [this controller message]
                   ;; Adjust the BPM based on how the encoder was twisted
                   (let [delta (/ (sign-velocity (:velocity message)) 2)
                         level (master-get-level (get-in controller [:show :grand-master]))]
                     (master-set-level (get-in controller [:show :grand-master]) (+ level delta))))
                 (handle-note-on [this controller message]
                   false)
                 (handle-note-off [this controller message]
                   ;; Exit the overlay
                   :done))))

(defn- bpm-encoder-touched
  "Add a user interface overlay to give feedback when turning the BPM
  encoder."
  [controller]
  ;; Reserve the metronome area for its coordinated set of overlays
  (swap! (:metronome-mode controller) assoc :adjusting-bpm :true)
  (add-overlay controller
               (reify IOverlay
                 (captured-controls [this] #{15})
                 (captured-notes [this] #{9 1})
                 (adjust-interface [this controller]
                   (bpm-adjusting-interface controller))
                 (handle-control-change [this controller message]
                   (adjust-bpm-from-encoder controller message))
                 (handle-note-on [this controller message]
                   ;; Suppress the extra encoder above the BPM display.
                   ;; We can't get a note on for the BPM encoder, because
                   ;; that was the event that created this overlay.
                   true)
                 (handle-note-off [this controller message]
                   (when (= (:note message) 9)
                     ;; They released us, end the overlay
                     (swap! (:metronome-mode controller) dissoc :adjusting-bpm)
                     :done)))))

(defn- beat-encoder-touched
  "Add a user interface overlay to give feedback when turning the beat
  encoder."
  [controller]
  ;; Reserve the metronome area for its coordinated set of overlays
  (swap! (:metronome-mode controller) assoc :adjusting-beat :true)
  (add-overlay controller
               (reify IOverlay
                 (captured-controls [this] #{14})
                 (captured-notes [this] #{10 0})
                 (adjust-interface [this controller]
                   (beat-adjusting-interface controller))
                 (handle-control-change [this controller message]
                   (adjust-beat-from-encoder controller message))
                 (handle-note-on [this controller message]
                   ;; Suppress the extra encoder above the beat display.
                   ;; We can't get a note on for the beat encoder, because
                   ;; that was the event that created this overlay.
                   true)
                 (handle-note-off [this controller message]
                   (when (= (:note message) 10)
                     ;; They released us, exit the overlay
                     (swap! (:metronome-mode controller) dissoc :adjusting-beat)
                     :done)))))

(defn- leave-user-mode
  "The user has asked to exit user mode, so suspend our display
  updates, and prepare to restore our state when user mode is pressed
  again."
  [controller]
  (swap! (:task controller) (fn [task]
                              (when task (at-at/kill task))
                              nil))
  (add-overlay controller
               (reify IOverlay
                 (captured-controls [this] #{59})
                 (captured-notes [this] #{})
                 (adjust-interface [this controller]
                   true)
                 (handle-control-change [this controller message]
                   (when (pos? (:velocity message))
                     ;; We are returning to user mode, restore display
                     (clear-interface controller)
                     (reset! (:task controller) (at-at/every (:refresh-interval controller)
                                                             #(update-interface controller)
                                                             controllers/pool
                                                             :initial-delay 250
                                                             :desc "Push interface update"))
                     :done))
                 (handle-note-on [this controller message]
                   false)
                 (handle-note-off [this controller message]
                   false))))

(defn- control-change-received
  "Process a control change message which was not handled by an
  interface overlay."
  [controller message]
  (case (:note message)
    3 ; Tap tempo button
    (when (pos? (:velocity message))
      (interpret-tempo-tap controller)
      (enter-metronome-showing controller))

    9 ; Metronome button
    (when (pos? (:velocity message))
      (enter-metronome-showing controller))

    49 ; Shift button
    (swap! (:shift-mode controller) (fn [_] (pos? (:velocity message))))

    59 ; User mode button
    (when (pos? (:velocity message))
      (leave-user-mode controller))

    ;; Something we don't care about
    nil))

(defn- note-on-received
  "Process a note-on message which was not handled by an interface
  overlay."
  [controller message]
  (case (:note message)
    8 ; Master encoder
    (master-encoder-touched controller)

    9 ; BPM encoder
    (bpm-encoder-touched controller)

    10 ; Beat encoder
    (beat-encoder-touched controller)

    ;; Something we don't care about
    nil))

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
  (when (= (:device message) (:port-in controller))
    ;(info message)
    (when-not (overlay-handled? controller message)
      (when (= (:status message) :control-change)
        (control-change-received controller message))
      (when (= (:command message) :note-on)
        (note-on-received controller message))
      (when (= (:command message) :note-off)
        (note-off-received controller message)))))

(defn bind-to-show
  "Establish a connection to the Ableton Push, for managing the given
  show.

  Initializes the display, and starts the UI updater thread. Since
  SysEx messages are required for updating the display, if you are on
  a Mac, you must install
  [mmj](http://www.humatic.de/htools/mmj.htm) to provide a
  working implementation.

  If you have more than one Ableton Push connected, or have renamed
  how it appears in your list of MIDI devices, you need to supply a
  value after `:device-name` which identifies the ports to be used to
  communicate with the Push you want this function to use. The values
  returned by [[afterglow.midi/open-inputs-if-needed!]]
  and [[afterglow.midi/open-outputs-if-needed!]] will be searched, and
  the first port whose name or description contains the supplied
  string will be used.

  If you want the user interface to be refreshed at a different rate
  than the default of thirty times per second, pass your desired
  number of milliseconds after `:refresh-interval`."
  {:doc/format :markdown}
  [show & {:keys [device-name refresh-interval]
           :or {device-name "User Port"
                refresh-interval show/default-refresh-interval}}]
  (let [controller
        {:id (swap! controller-counter inc)
         :show show
         :refresh-interval refresh-interval
         :port-in (midi/midi-find-device (amidi/open-inputs-if-needed!) device-name)
         :port-out (midi/midi-find-device (amidi/open-outputs-if-needed!) device-name)
         :task (atom nil)
         :last-display (vec (for [_ (range 4)] (byte-array (take 68 (repeat 32)))))
         :next-display (vec (for [_ (range 4)] (byte-array (take 68 (repeat 32)))))
         :last-text-buttons (atom {})
         :next-text-buttons (atom {})
         :last-top-pads (int-array 8)
         :next-top-pads (int-array 8)
         :metronome-mode (atom {})
         :shift-mode (atom false)
         :midi-handler (atom nil)
         :tap-tempo-handler (amidi/create-tempo-tap-handler (:metronome show))
         :overlays (atom (sorted-map-by >))
         :next-overlay (atom 0)}]
    (reset! (:midi-handler controller) (partial midi-received controller))
    ;; Set controller in User mode
    (midi/midi-sysex (:port-out controller) [240 71 127 21 98 0 1 1 247])
    (clear-interface controller)
    (welcome-animation controller)
    (swap! active-bindings assoc (:id controller) controller)
    controller))

(defn deactivate
  "Deactivates a controller interface, killing its update thread
  and removing its MIDI listeners."
  [controller]
  (swap! (:task controller) (fn [task]
                              (when task (at-at/kill task))
                              nil))

  (amidi/remove-global-handler! @(:midi-handler controller))
  
  (clear-interface controller)
  ;; Leave the User button bright, in case the user has Live
  ;; running and wants to be able to see how to return to it.
  (set-button-state controller (:user-mode control-buttons) :bright)

  ;; TODO: Clear out any interface stacks, MIDI listeners, etc.

  (swap! active-bindings dissoc (:id controller)))

(defn deactivate-all
  "Deactivates all controller bindings which are currently active."
  []
  (doseq [[_ controller] @active-bindings]
    (deactivate controller)))
