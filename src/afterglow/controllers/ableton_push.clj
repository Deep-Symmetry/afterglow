(ns afterglow.controllers.ableton-push
  "Allows the Ableton Push to be used as a control surface for
  Afterglow. Its features are described in the [online
  documentation](https://github.com/brunchboy/afterglow/blob/master/doc/mapping_sync.adoc#using-ableton-push)."
  {:author "James Elliott"}
  (:require [afterglow.controllers :as controllers]
            [afterglow.effects.cues :as cues]
            [afterglow.effects.dimmer :refer [master-get-level master-set-level]]
            [afterglow.midi :as amidi]
            [afterglow.rhythm :as rhythm]
            [afterglow.show :as show]
            [afterglow.show-context :refer [with-show]]
            [afterglow.util :as util]
            [afterglow.version :as version]
            [clojure.math.numeric-tower :as math]
            [com.evocomputing.colors :as colors]
            [overtone.at-at :as at-at]
            [overtone.midi :as midi]
            [taoensso.timbre :as timbre :refer [warn]])
  (:import [java.util Arrays]))

(defn velocity-for-color
  "Given a target color, calculate the MIDI note velocity which will
  achieve the closest approximation available on an Ableton Push
  pad, using the thoughtful hue palette provided by Ableton:

  ![Push pad palette](http://deepsymmetry.org/afterglow/research/PushColors.jpg)"
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
          (let [base-hue (colors/hue color)
                ;; Hue to velocity gets a little non-linear at blue; tweak to look right.
                adjusted-hue (if (> base-hue 230)
                               (min 360 (* base-hue 1.2))
                               base-hue)
                hue-section (+ 4 (* 4 (math/floor (* 13 (/ adjusted-hue 360)))))]
            (int (+ hue-section brightness-shift))))))

(defonce ^{:doc "The color of buttons that are completely off."}
  off-color (colors/create-color :black))

(defn set-pad-velocity
  "Set the velocity of one of the 64 touch pads."
  [controller x y velocity]
  {:pre [(<= 0 x 7) (<= 0 y 7)]}
  (let [note (+ 36 x (* y 8))]  ;; Calculate note from grid coordinates
    (midi/midi-note-on (:port-out controller) note velocity)))

(defn set-pad-color-approximate
  "*Deprecated in favor of new [[set-pad-color]] implementation.*

  Set the color of one of the 64 touch pads to the closest
  approximation available for a desired color. This was the first
  implementation that was discovered, but there is now a much more
  powerful way to specify an exact color using a SysEx message."
  {:deprecated "0.1.4"}
  [controller x y color]
  (set-pad-velocity controller x y (velocity-for-color color)))

(defn set-pad-color
  "Set the color of one of the 64 touch pads to a specific RGB
  color."
  [controller x y color]
  (let [pad (+ x (* y 8))
        r (colors/red color)
        g (colors/green color)
        b (colors/blue color)]
    (midi/midi-sysex (:port-out controller) [240 71 127 21 4 0 8 pad 0
                                             (quot r 2r10000) (bit-and r 2r1111)
                                             (quot g 2r10000) (bit-and g 2r1111)
                                             (quot b 2r10000) (bit-and b 2r1111)
                                             247])))

(def monochrome-button-states
  "The control values and modes for a labeled button which does not
  change color."
  {:off 0 :dim 1 :dim-slow-blink 2 :dim-fast-blink 3
   :bright 4 :bright-slow-blink 5 :bright-fast-blink 6})

(def color-button-colors
  "The control values and modes for a labeled button which changes
  color. These are added to the monochrome states (except for off)
  to obtain the color and brightness/behavior."
  {:red 0 :amber 6 :yellow 12 :green 18})

(def control-buttons
  "The labeled buttons which send and respond to Control Change
  events."
  {:tap-tempo             {:control 3 :kind :monochrome}
   :metronome             {:control 9 :kind :monochrome}

   :master                {:control 28 :kind :monochrome}
   :stop                  {:control 29 :kind :monochrome}

   :quarter               {:control 36 :kind :color}
   :quarter-triplet       {:control 37 :kind :color}
   :eighth                {:control 38 :kind :color}
   :eighth-triplet        {:control 39 :kind :color}
   :sixteenth             {:control 40 :kind :color}
   :sixteenth-triplet     {:control 41 :kind :color}
   :thirty-second         {:control 42 :kind :color}
   :thirty-second-triplet {:control 43 :kind :color}

   :left-arrow            {:control 44 :kind :monochrome}
   :right-arrow           {:control 45 :kind :monochrome}
   :up-arrow              {:control 46 :kind :monochrome}
   :down-arrow            {:control 47 :kind :monochrome}

   :select                {:control 48 :kind :monochrome}
   :shift                 {:control 49 :kind :monochrome}
   :note                  {:control 50 :kind :monochrome}
   :session               {:control 51 :kind :monochrome}
   :add-device            {:control 52 :kind :monochrome}
   :add-track             {:control 53 :kind :monochrome}

   :octave-down           {:control 54 :kind :monochrome}
   :octave-up             {:control 55 :kind :monochrome}
   :repeat                {:control 56 :kind :monochrome}
   :accent                {:control 57 :kind :monochrome}
   :scales                {:control 58 :kind :monochrome}
   :user-mode             {:control 59 :kind :monochrome}
   :mute                  {:control 60 :kind :monochrome}
   :solo                  {:control 61 :kind :monochrome}
   :in                    {:control 62 :kind :monochrome}
   :out                   {:control 63 :kind :monochrome}

   :play                  {:control 85 :kind :monochrome}
   :record                {:control 86 :kind :monochrome}
   :new                   {:control 87 :kind :monochrome}
   :duplicate             {:control 88 :kind :monochrome}
   :automation            {:control 89 :kind :monochrome}
   :fixed-length          {:control 90 :kind :monochrome}

   :device-mode           {:control 110 :kind :monochrome}
   :browse-mode           {:control 111 :kind :monochrome}
   :track-mode            {:control 112 :kind :monochrome}
   :clip-mode             {:control 113 :kind :monochrome}
   :volume-mode           {:control 114 :kind :monochrome}
   :pan-send-mode         {:control 115 :kind :monochrome}

   :quantize              {:control 116 :kind :monochrome}
   :double                {:control 117 :kind :monochrome}
   :delete                {:control 118 :kind :monochrome}
   :undo                  {:control 119 :kind :monochrome}})

(def special-symbols
  "The byte values which draw special-purpose characters on the Push
  display."
  {:up-arrow            (byte 0)
   :down-arrow          (byte 1)
   :pancake             (byte 2)
   :fader-left          (byte 3)
   :fader-right         (byte 4)
   :fader-center        (byte 5)
   :fader-empty         (byte 6)
   :folder              (byte 7)
   :split-vertical-line (byte 8)
   :degree              (byte 9)
   :ellipsis            (byte 28)
   :solid-block         (byte 29)
   :right-arrow         (byte 30)
   :left-arrow          (byte 31)
   :selected-triangle   (byte 127)})

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

(defn show-labels
  "Illuminates all buttons with text labels, for development assistance."
  ([controller]
   (show-labels controller :bright :amber))
  ([controller state]
   (show-labels controller state :amber))
  ([controller state color]
   (doseq [[_ button] control-buttons]
     (set-button-state controller button state color))))

(defn- update-text
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

(defn- update-top-pads
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

(defn- update-text-buttons
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

(defn write-display-text
  "Update a batch of characters within the display to be rendered on
  the next update."
  [controller row start text]
  {:pre [(<= 0 row 3) (<= 0 start 67)]}
  (let [bytes (take (- 68 start) (map int text))]
    (doseq [[i val] (map-indexed vector bytes)]
      (aset (get (:next-display controller) row) (+ start i) (util/ubyte val)))))

(defn- write-display-cell
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
  17 characters, or a full display cell."
  [value & {:keys [lowest highest width] :or {lowest 0 highest 100 width 17}}]
  (let [range (* 1.01 (- highest lowest))
        midpoint (/ (- highest lowest) 2)
        scaled (int (* 2 width (/ (- value lowest) range)))
        filler (repeat (:fader-empty special-symbols))
        centered (< (math/abs (- (- value lowest) midpoint)) (/ range 256))
        marker ((if (and centered (odd? width))
                  :fader-center
                  (if (even? scaled) :fader-left :fader-right))
                special-symbols)
        leader (if (and centered (even? width) (even? scaled))
                 (concat (take (dec (int (/ scaled 2))) filler) [(:fader-right special-symbols)])
                 (take (int (/ scaled 2)) filler))]
    (take width (concat leader [marker]
                        (when (and centered (even? width) (odd? scaled)) [(:fader-left special-symbols)])
                        filler))))

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

(defn- metronome-sync-label
  "Determine the sync type label to display under the BPM section."
  [controller]
  (with-show (:show controller)
    (case (:type (show/sync-status))
      :manual " Manual"
      :midi "  MIDI"
      :dj-link "DJ Link"
      :traktor-beat-phase "Traktor"
      "Unknown")))

(defn- metronome-sync-color
  "Determine the color to light the sync pad under the BPM section."
  [controller]
  (with-show (:show controller)
    (if (= (:type (show/sync-status)) :manual)
      :amber
      (if (:current (show/sync-status))
        :green
        :red))))

(defn- bpm-adjusting-interface
  "Add an arrow showing the BPM is being adjusted, or point out that
  it is being externally synced."
  [controller]
  (if (= (:type (show/sync-status)) :manual)
    (let [arrow-pos (if @(:shift-mode controller) 14 16)]
      (aset (get (:next-display controller) 2) arrow-pos (:up-arrow special-symbols)))
    (do
      (aset (get (:next-display controller) 2) 9 (:down-arrow special-symbols))
      (when-not (:showing @(:metronome-mode controller))
        ;; We need to display the sync mode in order to point at it
        (write-display-cell controller 3 0
                            (str "         " (metronome-sync-label controller)))))))

(defn sign-velocity
  "Convert a midi velocity to its signed equivalent, to translate
  encoder rotations, which are twos-complement seven bit numbers."
  [val]
   (if (>= val 64)
     (- val 128)
     val))

(defn- adjust-bpm-from-encoder
  "Adjust the current BPM based on how the encoder was twisted, unless
  the metronome is synced."
  [controller message]
  (with-show (:show controller)
    (when (= (:type (show/sync-status)) :manual)
      (let [scale (if @(:shift-mode controller) 1 10)
            delta (/ (sign-velocity (:velocity message)) scale)
            bpm (rhythm/metro-bpm (:metronome (:show controller)))]
        (rhythm/metro-bpm (:metronome (:show controller)) (min controllers/maximum-bpm
                                                               (max controllers/minimum-bpm (+ bpm delta))))))))

(defn- encoder-above-bpm-touched
  "Add a user interface overlay to give feedback when turning the
  encoder above the BPM display when metronome was already active,
  since it is easy to grab that one rather than the actual BPM
  encoder, being right above the display."
  [controller]
  (controllers/add-overlay (:overlays controller)
               (reify controllers/IOverlay
                 (captured-controls [this] #{72})
                 (captured-notes [this] #{1 9})
                 (adjust-interface [this]
                   (bpm-adjusting-interface controller)
                   true)
                 (handle-control-change [this message]
                   (adjust-bpm-from-encoder controller message))
                 (handle-note-on [this  message]
                   ;; Suppress the actual BPM encoder while we are active.
                   true)
                 (handle-note-off [this message]
                   (when (= (:note message) 1)
                     ;; They released us, end the overlay.
                     :done))
                 (handle-aftertouch [this message]))))

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
        metronome (:metronome (:show controller))
        units (if @(:shift-mode controller)
                ;; User is adjusting the current bar
                (rhythm/metro-tock metronome)
                ;; User is adjusting the current beat
                (rhythm/metro-tick metronome))
        ms-delta (- (* delta units))]
    (rhythm/metro-adjust metronome ms-delta)))

(defn- encoder-above-beat-touched
  "Add a user interface overlay to give feedback when turning the
  encoder above the beat display when metronome was already active,
  since it is easy to grab that one rather than the actual beat
  encoder, being right above the display."
  [controller]
  (controllers/add-overlay (:overlays controller)
               (reify controllers/IOverlay
                 (captured-controls [this] #{71})
                 (captured-notes [this] #{0 10})
                 (adjust-interface [this]
                   (beat-adjusting-interface controller))
                 (handle-control-change [this message]
                   (adjust-beat-from-encoder controller message))
                 (handle-note-on [this message]
                   ;; Suppress the actual beat encoder while we are active.
                   true)
                 (handle-note-off [this message]
                   (when (zero? (:note message))
                     ;; They released us, end the overlay.
                     :done))
                 (handle-aftertouch [this message]))))

(defn- enter-metronome-showing
  "Activate the persistent metronome display, with sync and reset pads
  illuminated."
  [controller]
  (swap! (:metronome-mode controller) assoc :showing true)
  (controllers/add-overlay (:overlays controller)
               (reify controllers/IOverlay
                 (captured-controls [this] #{3 9 20 21})
                 (captured-notes [this] #{0 1})
                 (adjust-interface [this]
                   ;; Make the metronome button bright, since its information is active
                   (swap! (:next-text-buttons controller)
                          assoc (:metronome control-buttons)
                          (button-state (:metronome control-buttons) :bright))

                   ;; Add the labels for reset and sync, and light the pads
                   (write-display-cell controller 3 0
                                       (str " Reset   " (metronome-sync-label controller)))
                   (aset (:next-top-pads controller) 0 (top-pad-state :dim :red))
                   (aset (:next-top-pads controller) 1 (top-pad-state :dim (metronome-sync-color controller))))
                 (handle-control-change [this message]
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
                       (controllers/add-control-held-feedback-overlay (:overlays controller) 20
                                                                      #(aset (:next-top-pads controller)
                                                                             0 (top-pad-state :bright :red)))
                       true)
                     21 ; Sync pad
                     (when (pos? (:velocity message))
                       ;; TODO: Actually implement a new overlay
                       (controllers/add-control-held-feedback-overlay
                        (:overlays controller) 21 #(aset (:next-top-pads controller)
                                                         1 (top-pad-state :bright
                                                                          (metronome-sync-color controller))))
                       true)))
                 (handle-note-on [this message]
                   ;; Whoops, user grabbed encoder closest to beat or BPM display
                   (case (:note message)
                     0 (encoder-above-beat-touched controller)
                     1 (encoder-above-bpm-touched controller))
                   true)
                 (handle-note-off [this message]
                   false)
                 (handle-aftertouch [this message]))))

(defn- new-beat?
  "Returns true if the metronome is reporting a different marker
  position than the last time this function was called."
  [controller marker]
  (when (not= marker @(:last-marker controller))
    (reset! (:last-marker controller) marker)))

(defn- update-metronome-section
  "Updates the sections of the interface related to metronome
  control."
  [controller]
  (let [metronome (:metronome (:show controller))
        marker (rhythm/metro-marker metronome)
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
                         (if (or (new-beat? controller marker) (< (rhythm/metro-beat-phase metronome) 0.15))
                           :bright :dim)))))

(defn- render-cue-grid
  "Figure out how the cue grid pads should be illuminated, based on the
  currently active cues."
  [controller]
  (let [[origin-x origin-y] @(:origin controller)
        active-keys (show/active-effect-keys (:show controller))]
    (doseq [x (range 8)
            y (range 8)]
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
        (aset (:next-grid-pads controller) (+ x (* y 8)) (or color off-color))))))

(defn- update-cue-grid
  "See if any of the cue grid button states have changed, and send any
  required updates."
  [controller]
  (doseq [x (range 8)
          y (range 8)]
    (let [index (+ x (* y 8))
          color (aget (:next-grid-pads controller) index)]
      (when-not (= color (aget (:last-grid-pads controller) index))
        (set-pad-color controller x y color)
        (aset (:last-grid-pads controller) index color)))))

(defn- fit-cue-variable-name
  "Picks the best version of a cue variable name to fit in the specified
  number of characters, then truncates it if necessary."
  [v len]
  (let [longer (or (:name v) (name (:key v)))
        shorter (or (:short-name v) longer)
        padding (clojure.string/join (repeat len " "))]
    (if (<= (count longer) len)
      (clojure.string/join (take len (str longer padding)))
      (clojure.string/join (take len (str shorter padding))))))

(defn- cue-variable-names
  "Determines the names of adjustable variables to display under an
  active cue."
  [cue]
  (if (seq (:variables cue))
    (if (= (count (:variables cue)) 1)
      (fit-cue-variable-name (first (:variables cue)) 17)
      (str (fit-cue-variable-name (first (:variables cue)) 8) " "
           (fit-cue-variable-name (second (:variables cue)) 8)))
    ""))

(defn- fit-cue-variable-value
  "Truncates the current value of a cue variable to fit available
  space."
  [controller cue v len effect-id]
  (let [val (cues/get-cue-variable cue v :controller controller :when-id effect-id)
        formatted (if val
                    (case (:type v)
                      :integer (int val)
                      ;; If we don't know what else to do, at least turn ratios to floats
                      (float val))
                    "...")
        padding (clojure.string/join (repeat len " "))]
    (clojure.string/join (take len (str formatted padding)))))

(defn- cue-variable-values
  "Formats the current values of the adjustable variables to display
  under an active cue."
  [controller cue effect-id]
  (if (seq (:variables cue))
    (if (= (count (:variables cue)) 1)
      (fit-cue-variable-value controller cue (first (:variables cue)) 17 effect-id)
      (str (fit-cue-variable-value controller cue (first (:variables cue)) 8 effect-id) " "
           (fit-cue-variable-value controller cue (second (:variables cue)) 8 effect-id)))
    ""))

(defn- room-for-effects
  "Determine how many display cells are available for displaying
  effect information."
  [controller]
  (if (seq @(:metronome-mode controller)) 3 4))

(defn- update-effect-list
  "Display information about the four most recently activated
  effects (or three, if the metronome is taking up a slot)."
  [controller]
  (let [room (room-for-effects controller)
        first-cell (- 4 room)
        fx-info @(:active-effects (:show controller))
        fx (:effects fx-info)
        fx-meta (:meta fx-info)
        num-skipped (- (count fx-meta) room @(:effect-offset controller))]
    (if (seq fx)
      (do (loop [fx (take room (drop num-skipped fx))
                 fx-meta (take room (drop num-skipped fx-meta))
                 x first-cell]
            (let [effect (:effect (first fx))
                  info (first fx-meta)
                  ending ((:key info) (:ending fx-info))
                  cue (:cue info)]
              (write-display-cell controller 0 x (cue-variable-names cue))
              (write-display-cell controller 1 x (cue-variable-values controller cue (:id info)))
              (write-display-cell controller 2 x (or (:name cue) (:name (first fx))))
              (write-display-cell controller 3 x (if ending " Ending" "  End"))
              (aset (:next-top-pads controller) (* 2 x) (top-pad-state :dim :red))
              (when (seq (rest fx))
                (recur (rest fx) (rest fx-meta) (inc x)))))
          ;; Draw indicators if there are effects hidden from view in either direction
          (when (pos? num-skipped)
            (aset (get (:next-display controller) 3) (* first-cell 17) (util/ubyte (:left-arrow special-symbols))))
          (when (pos? @(:effect-offset controller))
            (aset (get (:next-display controller) 3) 67 (util/ubyte (:right-arrow special-symbols)))))
      (do (write-display-cell controller 2 1 "       No effects")
          (write-display-cell controller 2 2 "are active.")))))

(declare enter-stop-mode)

(defn- find-effect-offset-range
  "Determine the valid offset range for scrolling through the effect
  list, based on how many effects are running, and how many currently
  fit on the display. If we are currently scrolled beyond the sensible
  range, correct that. Returns a tuple of the current offset, the
  maximum sensible offset, and the number of effects displayed."
  [controller]
  (let [room (room-for-effects controller)
        size (count (:effects @(:active-effects (:show controller))))
        max-offset (max 0 (- size room))
        ;; If we are offset more than now makes sense, fix that.
        offset (swap! (:effect-offset controller) min max-offset)]
    [offset max-offset room]))

(defn- update-scroll-arrows
  "Activate the arrow buttons for directions in which scrolling is
  possible."
  [controller]
  (if @(:shift-mode controller)
    ;; In shift mode, scroll through the effects list
    (let [[offset max-offset] (find-effect-offset-range controller)]
      ;; If there is an offset, user can scroll to the right
      (when (pos? offset)
        (swap! (:next-text-buttons controller)
               assoc (:right-arrow control-buttons)
               (button-state (:right-arrow control-buttons) :dim))
        (swap! (:next-text-buttons controller)
               assoc (:down-arrow control-buttons)
               (button-state (:down-arrow control-buttons) :dim)))
      ;; Is there room to scroll to the left?
      (when (< offset max-offset)
        (swap! (:next-text-buttons controller)
               assoc (:left-arrow control-buttons)
               (button-state (:left-arrow control-buttons) :dim))
        (swap! (:next-text-buttons controller)
               assoc (:up-arrow control-buttons)
               (button-state (:up-arrow control-buttons) :dim))))

    ;; In unshifted mode, scroll through the cue grid
    (let [[origin-x origin-y] @(:origin controller)]
      (when (pos? origin-x)
        (swap! (:next-text-buttons controller)
               assoc (:left-arrow control-buttons)
               (button-state (:left-arrow control-buttons) :dim)))
      (when (pos? origin-y)
        (swap! (:next-text-buttons controller)
               assoc (:down-arrow control-buttons)
               (button-state (:down-arrow control-buttons) :dim)))
      (when (> (- (controllers/grid-width (:cue-grid (:show controller))) origin-x) 7)
        (swap! (:next-text-buttons controller)
               assoc (:right-arrow control-buttons)
               (button-state (:right-arrow control-buttons) :dim)))
      (when (> (- (controllers/grid-height (:cue-grid (:show controller))) origin-y) 7)
        (swap! (:next-text-buttons controller)
               assoc (:up-arrow control-buttons)
               (button-state (:up-arrow control-buttons) :dim))))))

(defn- update-interface
  "Determine the desired current state of the interface, and send any
  changes needed to get it to that state."
  [controller]
  (try
    ;; Assume we are starting out with a blank interface.
    (doseq [row (range 4)]
      (Arrays/fill (get (:next-display controller) row) (byte 32)))
    (reset! (:next-text-buttons controller) {})
    (Arrays/fill (:next-top-pads controller) 0)

    (update-effect-list controller)
    (update-metronome-section controller)

    ;; If the show has stopped without us noticing, enter stop mode
    (with-show (:show controller)
      (when-not (or (show/running?) @(:stop-mode controller))
        (enter-stop-mode controller)))

    ;; Reflect the shift button state
    (swap! (:next-text-buttons controller)
           assoc (:shift control-buttons)
           (button-state (:shift control-buttons)
                         (if @(:shift-mode controller) :bright :dim)))
    
    (render-cue-grid controller)
    (update-scroll-arrows controller)
    
    ;; Make the User button bright, since we live in User mode
    (swap! (:next-text-buttons controller)
           assoc (:user-mode control-buttons)
           (button-state (:user-mode control-buttons) :bright))

    ;; Make the stop button visible, since we support it
    (swap! (:next-text-buttons controller)
           assoc (:stop control-buttons)
           (button-state (:stop control-buttons) :dim))

    ;; Add any contributions from interface overlays, removing them
    ;; if they report being finished.
    (controllers/run-overlays (:overlays controller))

    (update-cue-grid controller)
    (update-text controller)
    (update-top-pads controller)
    (update-text-buttons controller)

    (catch Throwable t
      (warn t "Problem updating Ableton Push Interface"))))

(declare clear-interface)

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
      (show-labels controller :bright :amber)
      
      (= @counter 16)
      (doseq [x (range 0 8)]
        (set-top-pad-state controller x :bright :amber))
      
      (= @counter 17)
      (doseq [x (range 0 8)]
        (set-second-pad-color controller x
                                (colors/create-color :h 45 :s 100 :l 50))
        (set-top-pad-state controller x :bright :red))

      (< @counter 26)
      (doseq [x (range 0 8)]
        (let [lightness-index (if (> x 3) (- 7 x) x)
              lightness ([10 30 50 70] lightness-index)
              color (colors/create-color
                     :h (+ 60 (* 40 (- @counter 18))) :s 100 :l lightness)]
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

(defn- welcome-animation
  "Provide a fun animation to make it clear the Push is online."
  [controller]
  (set-display-line controller 1 (concat (repeat 24 \space) (seq (str "Welcome to" (version/title)))))
  (set-display-line controller 2 (concat (repeat 27 \space)
                              (seq (str "version" (version/tag)))))
  (let [counter (atom 0)
        task (atom nil)]
    (reset! task (at-at/every 50 #(welcome-frame controller counter task)
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
  (Arrays/fill (:last-grid-pads controller) off-color)
  (doseq [[_ button] control-buttons]
    (set-button-state controller button :off))
  (reset! (:last-text-buttons controller) {}))

(defonce ^{:doc "Counts the controller bindings which have been made,
  so each can be assigned a unique ID."}
  controller-counter (atom 0))

(defonce ^{:doc "Controllers which are currently bound to shows,
  indexed by the controller binding ID."}
  active-bindings (atom {}))

(defn- master-encoder-touched
  "Add a user interface overlay to give feedback when turning the
  master encoder."
  [controller]
  (controllers/add-overlay (:overlays controller)
                           (reify controllers/IOverlay
                             (captured-controls [this] #{79})
                             (captured-notes [this] #{8})
                             (adjust-interface [this]
                               (let [level (master-get-level (get-in controller [:show :grand-master]))]
                                 (write-display-cell controller 0 3 (make-gauge level))
                                 (write-display-cell controller 1 3
                                                     (str "GrandMaster " (format "%5.1f" level))))
                               true)
                             (handle-control-change [this message]
                               ;; Adjust the BPM based on how the encoder was twisted
                               (let [delta (/ (sign-velocity (:velocity message)) 2)
                                     level (master-get-level (get-in controller [:show :grand-master]))]
                                 (master-set-level (get-in controller [:show :grand-master]) (+ level delta))
                                 true))
                             (handle-note-on [this message]
                               false)
                             (handle-note-off [this message]
                               ;; Exit the overlay
                               :done)
                             (handle-aftertouch [this message]))))

(defn- bpm-encoder-touched
  "Add a user interface overlay to give feedback when turning the BPM
  encoder."
  [controller]
  ;; Reserve the metronome area for its coordinated set of overlays
  (swap! (:metronome-mode controller) assoc :adjusting-bpm :true)
  (controllers/add-overlay (:overlays controller)
                           (reify controllers/IOverlay
                             (captured-controls [this] #{15})
                             (captured-notes [this] #{9 1})
                             (adjust-interface [this]
                               (bpm-adjusting-interface controller)
                               true)
                             (handle-control-change [this message]
                               (adjust-bpm-from-encoder controller message))
                             (handle-note-on [this message]
                               ;; Suppress the extra encoder above the BPM display.
                               ;; We can't get a note on for the BPM encoder, because
                               ;; that was the event that created this overlay.
                               true)
                             (handle-note-off [this message]
                               (when (= (:note message) 9)
                                 ;; They released us, end the overlay
                                 (swap! (:metronome-mode controller) dissoc :adjusting-bpm)
                                 :done))
                             (handle-aftertouch [this message]))))

(defn- beat-encoder-touched
  "Add a user interface overlay to give feedback when turning the beat
  encoder."
  [controller]
  ;; Reserve the metronome area for its coordinated set of overlays
  (swap! (:metronome-mode controller) assoc :adjusting-beat :true)
  (controllers/add-overlay (:overlays controller)
                           (reify controllers/IOverlay
                             (captured-controls [this] #{14})
                             (captured-notes [this] #{10 0})
                             (adjust-interface [this]
                               (beat-adjusting-interface controller))
                             (handle-control-change [this message]
                               (adjust-beat-from-encoder controller message))
                             (handle-note-on [this message]
                               ;; Suppress the extra encoder above the beat display.
                               ;; We can't get a note on for the beat encoder, because
                               ;; that was the event that created this overlay.
                               true)
                             (handle-note-off [this message]
                               (when (= (:note message) 10)
                                 ;; They released us, exit the overlay
                                 (swap! (:metronome-mode controller) dissoc :adjusting-beat)
                                 :done))
                             (handle-aftertouch [this message]))))

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
  (set-button-state controller (:user-mode control-buttons)
                    (button-state (:user-mode control-buttons) :dim))
  (controllers/add-overlay (:overlays controller)
                           (reify controllers/IOverlay
                             (captured-controls [this] #{59})
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
                                                                         :desc "Push interface update"))
                                 :done))
                             (handle-note-on [this message])
                             (handle-note-off [this message])
                             (handle-aftertouch [this message]))))

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
                             (captured-controls [this] #{29})
                             (captured-notes [this] #{})
                             (adjust-interface [this]
                               (write-display-cell controller 0 1 "")
                               (write-display-cell controller 0 2 "")
                               (write-display-cell controller 1 1 "         *** Show")
                               (write-display-cell controller 1 2 "Stop ***")
                               (write-display-cell controller 2 1 "       Press Stop")
                               (write-display-cell controller 2 2 "to resume.")
                               (write-display-cell controller 3 1 "")
                               (write-display-cell controller 3 2 "")
                               (swap! (:next-text-buttons controller)
                                      assoc (:stop control-buttons)
                                      (button-state (:stop control-buttons) :bright))
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

(defn add-button-held-feedback-overlay
  "Adds a simple overlay which keeps a control button bright as long
  as the user is holding it down."
  [controller button]
  (controllers/add-control-held-feedback-overlay (:overlays controller) (:control button)
                                                 #(swap! (:next-text-buttons controller)
                                                         assoc button (button-state button :bright))))

(defn- handle-end-effect
  "Process a tap on one of the pads which indicate the user wants to
  end the associated effect."
  [controller note]
  (let [room (room-for-effects controller)
        fx-info @(:active-effects (:show controller))
        fx (vec (drop (- (count (:effects fx-info)) room) (:effects fx-info)))
        fx-meta (vec (drop (- (count (:meta fx-info)) room) (:meta fx-info)))
        offset (- 4 room)
        x (quot (- note 20) 2)
        index (- x offset)]
    (when (and (seq fx) (< index (count fx)))
      (let [effect (get fx index)
            info (get fx-meta index)]
        (with-show (:show controller)
          (show/end-effect! (:key info) :when-id (:id info)))
        (controllers/add-overlay (:overlays controller)
                                 (reify controllers/IOverlay
                                   (captured-controls [this] #{note (inc note)})
                                   (captured-notes [this] #{})
                                   (adjust-interface [this]
                                     (write-display-cell controller 0 x "")
                                     (write-display-cell controller 1 x "Ending:")
                                     (write-display-cell controller 2 x (or (:name (:cue info)) (:name effect)))
                                     (write-display-cell controller 3 x "")
                                     (aset (:next-top-pads controller) (* 2 x) (top-pad-state :bright :red))
                                     (aset (:next-top-pads controller) (inc (* 2 x)) (top-pad-state :off))
                                     true)
                                   (handle-control-change [this message]
                                     (when (and (= (:note message) note) (zero? (:velocity message)))
                                       :done))
                                   (handle-note-on [this message])
                                   (handle-note-off [this message])
                                   (handle-aftertouch [this message])))))))

(defn- move-origin
  "Changes the origin of the controller, notifying any registered
  listeners."
  [controller origin]
  (when (not= origin @(:origin controller))
    (reset! (:origin controller) origin)
    (doseq [f @(:move-listeners controller)] (f @(:grid-controller-impl controller) :moved))))

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

    (20 22 24 26) ; Effect end pads
    (when (pos? (:velocity message))
      (handle-end-effect controller (:note message)))
    
    ;; 28 ; Master button

    29 ; Stop button
    (when (pos? (:velocity message))
      (enter-stop-mode controller))

    49 ; Shift button
    (swap! (:shift-mode controller) (fn [_] (pos? (:velocity message))))

    44 ; Left arrow
    (when (pos? (:velocity message))
      (if @(:shift-mode controller)
        ;; Trying to scroll back to older effects
        (let [[offset max-offset room] (find-effect-offset-range controller)
              new-offset (min max-offset (+ offset room))]
          (when (not= offset new-offset)
            (reset! (:effect-offset controller) new-offset)
            (add-button-held-feedback-overlay controller (:left-arrow control-buttons))))

        ;; Trying to scroll left in cue grid
        (let [[x y] @(:origin controller)]
          (when (pos? x)
            (move-origin controller [(max 0 (- x 8)) y])
            (add-button-held-feedback-overlay controller (:left-arrow control-buttons))))))

    45 ; Right arrow
    (when (pos? (:velocity message))
      (if @(:shift-mode controller)
        ;; Trying to scroll forward to newer effects
        (let [[offset max-offset room] (find-effect-offset-range controller)
              new-offset (max 0 (- offset room))]
          (when (not= offset new-offset)
            (reset! (:effect-offset controller) new-offset)
            (add-button-held-feedback-overlay controller (:right-arrow control-buttons))))

        ;; Trying to scroll right in cue grid
        (let [[x y] @(:origin controller)]
          (when (> (- (controllers/grid-width (:cue-grid (:show controller))) x) 7)
            (move-origin controller [(+ x 8) y])
            (add-button-held-feedback-overlay controller (:right-arrow control-buttons))))))

    46 ; Up arrow
    (when (pos? (:velocity message))
      (if @(:shift-mode controller)
        ;; Jump back to oldest effect
        (let [[offset max-offset] (find-effect-offset-range controller)]
          (when (not= offset max-offset)
            (reset! (:effect-offset controller) max-offset)
            (add-button-held-feedback-overlay controller (:up-arrow control-buttons))))

        ;; Trying to scroll up in cue grid
        (let [[x y] @(:origin controller)]
          (when (> (- (controllers/grid-height (:cue-grid (:show controller))) y) 7)
            (move-origin controller [x (+ y 8)])
            (add-button-held-feedback-overlay controller (:up-arrow control-buttons))))))

    47 ; Down arrow
    (when (pos? (:velocity message))
      (if @(:shift-mode controller)
        ;; Jump forward to newest effect
        (when (pos? @(:effect-offset controller))
          (reset! (:effect-offset controller) 0)
          (add-button-held-feedback-overlay controller (:down-arrow control-buttons)))

        ;; Trying to scroll down in cue grid
        (let [[x y] @(:origin controller)]
          (when (pos? y)
            (move-origin controller [x (max 0 (- y 8))])
            (add-button-held-feedback-overlay controller (:down-arrow control-buttons))))))

    59 ; User mode button
    (when (pos? (:velocity message))
      (leave-user-mode controller))

    ;; Something we don't care about
    nil))

(defn- note-to-cue-coordinates
  "Translate the MIDI note associated with an incoming message to its
  coordinates in the show cue grid."
  [controller note]
  (let [base (- note 36)
        [origin-x origin-y] @(:origin controller)
        pad-x (rem base 8)
        pad-y (quot base 8)
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
                           (aset (:next-grid-pads controller) (+ pad-x (* pad-y 8)) color)))
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

(defn- control-for-top-encoder-note
  "Return the control number on which rotation of the encoder whose
  touch events are sent on the specified note will be sent."
  [note]
  (+ note 71))

(defn- draw-variable-gauge
  "Display the value of a variable being adjusted in the effect list."
  [controller cell width offset cue v effect-id]
  (let [value (or (cues/get-cue-variable cue v :controller controller :when-id effect-id) 0)
        low (min value (:min v))  ; In case user set "out of bounds".
        high (max value (:max v))
        gauge (if (:centered v)
                (make-pan-gauge value :lowest low :highest high :width width)
                (make-gauge value :lowest low :highest high :width width))]
    (write-display-text controller 0 (+ offset (* cell 17)) gauge)))

(defn- adjust-variable-value
  "Handle a control change from turning an encoder associated with a
  variable being adjusted in the effect list."
  [controller message cue v effect-id]
  (let [value (or (cues/get-cue-variable cue v :controller controller :when-id effect-id) 0)
        low (min value (:min v))  ; In case user set "out of bounds".
        high (max value (:max v))
        resolution (or (:resolution v) (/ (- high low) 200))
        delta (* (sign-velocity (:velocity message)) resolution)]
    (cues/set-cue-variable! cue v (max low (min high (+ value delta))) :controller controller :when-id effect-id)))

(defn- same-effect-active
  "See if the specified effect is still active with the same id."
  [controller cue id]
  (with-show (:show controller)
    (let [effect-found (show/find-effect (:key cue))]
      (and effect-found (= (:id effect-found) id)))))

(defn- display-encoder-touched
  "One of the eight encoders above the text display was touched."
  [controller note]
  (let [room (room-for-effects controller)
        fx-info @(:active-effects (:show controller))
        fx (:effects fx-info)
        fx-meta (:meta fx-info)
        num-skipped (- (count fx-meta) room @(:effect-offset controller))
        fx (vec (drop num-skipped (:effects fx-info)))
        fx-meta (vec (drop num-skipped (:meta fx-info)))
        offset (- 4 room)
        x (quot note 2)
        index (- x offset)
        var-index (rem note 2)]
    (when (and (seq fx) (< index (count fx)))
      (let [effect (get fx index)
            info (get fx-meta index)
            cue (:cue info)]
        (case (count (:variables cue))
          0 nil ; No variables to adjust
          1 (controllers/add-overlay (:overlays controller)
                                     ;; Just one variable, take full cell, using either encoder,
                                     ;; suppress the other one.
                                     (let [paired-note (if (odd? note) (dec note) (inc note))]
                                       (reify controllers/IOverlay
                                         (captured-controls [this] #{(control-for-top-encoder-note note)})
                                         (captured-notes [this] #{note paired-note})
                                         (adjust-interface [this]
                                           (when (same-effect-active controller cue (:id info))
                                             (draw-variable-gauge controller x 17 0 cue
                                                                  (first (:variables cue)) (:id info))
                                             true))
                                         (handle-control-change [this message]
                                           (adjust-variable-value controller message cue
                                                                  (first (:variables cue)) (:id info)))
                                         (handle-note-on [this message]
                                           true)
                                         (handle-note-off [this message]
                                           (when (= (:note message) note)
                                             :done))
                                         (handle-aftertouch [this message]))))
          (controllers/add-overlay (:overlays controller)
                                   ;; More than one variable, adjust whichever's encoder was touched
                                   (reify controllers/IOverlay
                                     (captured-controls [this] #{(control-for-top-encoder-note note)})
                                     (captured-notes [this] #{note})
                                     (adjust-interface [this]
                                       (when (same-effect-active controller cue (:id info))
                                         (draw-variable-gauge controller x 8 (* 9 var-index)
                                                              cue (get (:variables cue) var-index) (:id info))
                                         true))
                                     (handle-control-change [this message]
                                       (adjust-variable-value controller message cue
                                                              (get (:variables cue) var-index) (:id info)))
                                     (handle-note-on [this message])
                                     (handle-note-off [this message]
                                       :done)
                                     (handle-aftertouch [this message]))))))))

(defn- note-on-received
  "Process a note-on message which was not handled by an interface
  overlay."
  [controller message]
  (let [note (:note message)]
    (cond (<= 0 note 7)
          (display-encoder-touched controller note)

          (<= 36 note 99)
          (cue-grid-pressed controller note (:velocity message))

          :else
          ;; Some other UI element was touched
          (case note
            8 ; Master encoder
            (master-encoder-touched controller)
            
            9 ; BPM encoder
            (bpm-encoder-touched controller)
            
            10 ; Beat encoder
            (beat-encoder-touched controller)
            
            ;; Something we don't care about
            nil))))

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
    ;(timbre/info message)
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
  {:pre [(#{:ableton-push :push-watcher} (type controller))]}
  (if (= (type controller) :push-watcher)
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
        (set-button-state controller (:user-mode control-buttons) :bright))

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

(defonce ^{:doc "Deactivates any Push bindings when Java is shutting down."
           :private true}
  shutdown-hook
  (let [hook (Thread. deactivate-all)]
    (.addShutdownHook (Runtime/getRuntime) hook)
    hook))

(defn bind-to-show
  "Establish a connection to the Ableton Push, for managing the given
  show.

  Initializes the display, and starts the UI updater thread. Since
  SysEx messages are required for updating the display, if you are on
  a Mac, you must
  install [CoreMIDI4J](https://github.com/DerekCook/CoreMidi4J) to
  provide a working implementation. (If you need to work with Java
  1.6, you can instead
  use [mmj](http://www.humatic.de/htools/mmj.htm), but that is no
  longer developed, and does not support connecting or disconnecting
  MIDI devices after Java has started.)

  If you have more than one Ableton Push connected, or have renamed
  how it appears in your list of MIDI devices, you need to supply a
  value after `:device-filter` which identifies the ports to be used to
  communicate with the Push you want this function to use. The values
  returned by [[afterglow.midi/open-inputs-if-needed!]]
  and [[afterglow.midi/open-outputs-if-needed!]] will be searched, and
  the first port that matches with [[filter-devices]] will be used.

  The controller will be identified in the user interface (for the
  purposes of linking it to the web cue grid) as \"Ableton Push\". If
  you would like to use a different name (for example, if you are
  lucky enough to have more than one Push), you can pass in a custom
  value after `:display-name`.

  If you want the user interface to be refreshed at a different rate
  than the default of fifteen times per second, pass your desired
  number of milliseconds after `:refresh-interval`."
  [show & {:keys [device-filter refresh-interval display-name]
           :or   {device-filter    "User Port"
                  refresh-interval (/ 1000 15)
                  display-name     "Ableton Push"}}]
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
               :effect-offset        (atom 0)
               :refresh-interval     refresh-interval
               :port-in              port-in
               :port-out             port-out
               :task                 (atom nil)
               :last-display         (vec (for [_ (range 4)] (byte-array (take 68 (repeat 32)))))
               :next-display         (vec (for [_ (range 4)] (byte-array (take 68 (repeat 32)))))
               :last-text-buttons    (atom {})
               :next-text-buttons    (atom {})
               :last-top-pads        (int-array 8)
               :next-top-pads        (int-array 8)
               :last-grid-pads       (make-array clojure.lang.IPersistentMap 64)
               :next-grid-pads       (make-array clojure.lang.IPersistentMap 64)
               :metronome-mode       (atom {})
               :last-marker          (atom nil)
               :shift-mode           (atom false)
               :stop-mode            (atom false)
               :midi-handler         (atom nil)
               :tap-tempo-handler    (amidi/create-tempo-tap-handler (:metronome show))
               :overlays             (controllers/create-overlay-state)
               :move-listeners       (atom #{})
               :grid-controller-impl (atom nil)}
              {:type :ableton-push})]
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
        ;; Set controller in User mode
        (midi/midi-sysex (:port-out controller) [240 71 127 21 98 0 1 1 247])
        ;; Put pads in aftertouch (poly) pressure mode
        (midi/midi-sysex (:port-out controller) [240 71 127 21 92 0 1 0 247])
        ;; Set pad sensitivity level to 1 to avoid stuck pads
        (midi/midi-sysex (:port-out controller)
                         [0xF0 0x47 0x7F 0x15 0x5D 0x00 0x20 0x00 0x00 0x0C 0x07 0x00 0x00 0x0D 0x0C 0x00
                          0x00 0x00 0x01 0x04 0x0C 0x00 0x08 0x00 0x00 0x00 0x01 0x0D 0x04 0x0C 0x00 0x00
                          0x00 0x00 0x00 0x0E 0x0A 0x06 0x00 0xF7])
        (clear-interface controller)
        (welcome-animation controller)
        (swap! active-bindings assoc (:id controller) controller)
        (show/register-grid-controller @(:grid-controller-impl controller))
        (amidi/add-disconnected-device-handler! port-in #(deactivate controller :disconnected true))
        controller)
      (timbre/error "Unable to find Ableton Push" (amidi/describe-device-filter device-filter)))))

(defn auto-bind
  "Watches for an Ableton Push controller to be connected, and as soon
  as it is, binds it to the specified show using [[bind-to-show]]. If
  that controller ever gets disconnected, it will be re-bound once it
  reappears. Returns a watcher structure which can be passed
  to [[deactivate]] if you would like to stop it watching for
  reconnections. The underlying controller mapping, once bound, can be
  accessed through the watcher's `:controller` key.

  If you have more than one Ableton Push that might beconnected, or
  have renamed how it appears in your list of MIDI devices, you need
  to supply a value after `:device-filter` which identifies the ports to
  be used to communicate with the Push you want this function to use.
  The values returned by [[afterglow.midi/open-inputs-if-needed!]]
  and [[afterglow.midi/open-outputs-if-needed!]] will be searched, and
  the first port that matches using [[filter-devices]] will be used.

  Once bound, the controller will be identified in the user
  interface (for the purposes of linking it to the web cue grid) as
  \"Ableton Push\". If you would like to use a different name (for
  example, if you are lucky enough to have more than one Push), you
  can pass in a custom value after `:display-name`.

  If you want the user interface to be refreshed at a different rate
  than the default of fifteen times per second, pass your desired
  number of milliseconds after `:refresh-interval`."
  [show & {:keys [device-filter refresh-interval display-name]
           :or {device-filter "User Port"
                refresh-interval (/ 1000 15)
                display-name "Ableton Push"}}]
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
                      (when (every? some? [port-in port-out])  ; We found our Push! Bind to it in the background.
                        (timbre/info "Auto-binding to" device)
                        (future
                          (reset! controller (bind-to-show show :device-filter device-filter
                                                           :refresh-interval refresh-interval
                                                           :display-name display-name))
                          (amidi/add-disconnected-device-handler! (:port-in @controller) disconnection-handler))))
                    (reset! idle true))))
            (cancel-handler []
              (amidi/remove-new-device-handler! connection-handler)
              (when-let [device (:port-in @controller)]
                (amidi/remove-disconnected-device-handler! device disconnection-handler)))]

      ;; See if our Push seems to already be connected, and if so, bind to it right away.
      (when-let [found (first (amidi/filter-devices device-filter (amidi/open-inputs-if-needed!)))]
        (connection-handler found))

      ;; Set up to bind when connected in future.
      (amidi/add-new-device-handler! connection-handler)

      ;; Return a watcher object which can provide access to the bound controller, and be canceled later.
      (with-meta
        {:controller controller
         :device-filter device-filter
         :cancel cancel-handler}
        {:type :push-watcher}))))

