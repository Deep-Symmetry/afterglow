(ns afterglow.controllers.ableton-push
  "Allows the Ableton Push to be used as a control surface for
  Afterglow."
  {:author "James Elliott"
   :doc/format :markdown}
  (:require [afterglow.util :as util]
            [clojure.math.numeric-tower :as math]
            [com.evocomputing.colors :as colors]
            [overtone.midi :as midi]))

(defonce user-port-out (atom nil))
(defonce live-port-out (atom nil))
(defonce last-display (for [_ (range 4)] (byte-array (take 68 (repeat 32)))))
(defonce next-display (for [_ (range 4)] (byte-array (take 68 (repeat 32)))))

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

(defn set-pad-color
  "Set the color of one of the 64 touch pads to the closest
  approximation available for a desired color."
  [x y color]
  {:pre [(<= 0 x 7) (<= 0 y 7)]}
  (let [note (+ 36 x (* y 8))]  ;; Calculate note from grid coordinates
    (midi/midi-note-on @user-port-out note (velocity-for-color color))))

(def monochrome-button-states
  "The control values and modes for a labeled button which does not
  change color."
  {:off 0 :dim 1 :dim-slow-blink 2 :dim-fast-blink 3
   :bright 4 :bright-slow-blink 5 :bright-fast-blink 6})

(def control-buttons
  "The labeled buttons which send and respond to Control Change
  events."
  {:tap-tempo {:control 3 :states monochrome-button-states}
   :metronome {:control 9 :states monochrome-button-states}
   :left-arrow {:control 44 :states monochrome-button-states}
   :right-arrow {:control 45 :states monochrome-button-states}
   :up-arrow {:control 46 :states monochrome-button-states}
   :down-arrow {:control 47 :states monochrome-button-states}
   :select {:control 48 :states monochrome-button-states}
   :shift {:control 49 :states monochrome-button-states}
   :note {:control 50 :states monochrome-button-states}
   :session {:control 51 :states monochrome-button-states}
   :add-device {:control 52 :states monochrome-button-states}
   :add-track {:control 53 :states monochrome-button-states}
   :octave-down {:control 54 :states monochrome-button-states}
   :octave-up {:control 55 :states monochrome-button-states}
   :repeat {:control 56 :states monochrome-button-states}
   :accent {:control 57 :states monochrome-button-states}
   :scales {:control 58 :states monochrome-button-states}
   :user-mode {:control 59 :states monochrome-button-states}
   :mute {:control 60 :states monochrome-button-states}
   :solo {:control 61 :states monochrome-button-states}
   :in {:control 62 :states monochrome-button-states}
   :out {:control 63 :states monochrome-button-states}

   :play {:control 85 :states monochrome-button-states}
   :record {:control 86 :states monochrome-button-states}
   :new {:control 87 :states monochrome-button-states}
   :duplicate {:control 88 :states monochrome-button-states}
   :automation {:control 89 :states monochrome-button-states}
   :fixed-length {:control 90 :states monochrome-button-states}


   })

(defn set-display-line
  "Sets a line of the text display."
  [line bytes]
  {:pre [(<= 1 line 4)]}
  (let [message (concat [240 71 127 21 (+ line 23) 0 69 0]
                        (take 68 (concat (seq bytes) (repeat 32)))
                        [247])]
    (midi/midi-sysex @live-port-out message)))
 
(defn clear-display-line
  "Clears a line of the text display."
  [line]
  {:pre [(<= 1 line 4)]}
  (midi/midi-sysex @live-port-out [240 71 127 21 (+ line 27) 0 0 247]))

(defn clear-interface
  "Clears the text display and all illuminated buttons and pads."
  []
  (doseq [line (range 1 5)]
    (clear-display-line line))
  (doseq [x (range 8)
          y (range 8)]
    (set-pad-color x y (com.evocomputing.colors/create-color :pink #_:black)))
  (doseq [[k button] control-buttons]
    (midi/midi-control @user-port-out (:control button)
                       (get-in button [:states :bright-fast-blink #_:off]))))

(defn connect-to-push
  "Try to establish the connection to the Ableton Push, if that has
  not already been made, initialize the display, and start the UI
  updater thread. Since SysEx messages are required for updating the
  display, assumes that if you are on a Mac, you have installed
  [osxmidi4j](https://github.com/locurasoft/osxmidi4j) to provide a
  working implementation. You probably want that anyway so you can
  live-plug your MIDI interfaces."
  {:doc/format :markdown}
  [& {:keys [prefix] :or {prefix (when (re-find #"Mac" (System/getProperty "os.name"))
                                   "CoreMidi - ")}}]
  (swap! user-port-out #(or % (midi/midi-out (str prefix "User Port"))))
  (swap! live-port-out #(or % (midi/midi-out (str prefix "Live Port"))))
  (clear-interface))
