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
   :undo     {:control 119 :kind :monochrome}
   
   })

(defn set-button-state
  "Set one of the labeled buttons to a particular state, and, if
  supported, color."
  ([button state]
   (set-button-state button state :amber))
  ([button state color]
   (let [base-value ((keyword state) monochrome-button-states)
         color-shift (or (when (= (:kind button) :color)
                           ((keyword color) color-button-colors))
                         0)]
     (midi/midi-control @user-port-out (:control button)
                        (+ base-value color-shift)))))

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
    (set-button-state button :bright-fast-blink :green)))


(defn connect-to-push
  "Try to establish the connection to the Ableton Push, if that has
  not already been made, initialize the display, and start the UI
  updater thread. Since SysEx messages are required for updating the
  display, assumes that if you are on a Mac, you have installed
  [mmj](http://www.humatic.de/htools/mmj.htm) to provide a
  working implementation."
  {:doc/format :markdown}
  [& {:keys [prefix] :or {prefix (when (re-find #"Mac" (System/getProperty "os.name"))
                                   "Ableton Push - ")}}]
  (swap! user-port-out #(or % (midi/midi-out (str prefix "User Port"))))
  (swap! live-port-out #(or % (midi/midi-out (str prefix "Live Port"))))
  (clear-interface))
