(ns afterglow.controllers.ableton-push
  "Allows the Ableton Push to be used as a control surface for
  Afterglow."
  (:require [clojure.math.numeric-tower :as math]
            [com.evocomputing.colors :as colors]
            [overtone.midi :as midi]))

(defonce user-port-out (midi/midi-out "Ableton Push User Port"))

(defn velocity-for-color
  "Given a target color, calculate the MIDI note velocity which will
  achieve the closest approximation available on an Ableton Push
  pad."
  [color]
  {:pre [(= (type color) :com.evocomputing.colors/color)]}
  ;; Each hue section, starts with a desaturated version,
  ;; then does a bright, medium, and dim version.
  (let [brightness-shift (condp < (colors/lightness color)
                           60 0
                           37 1
                           15 2
                           3)]
    (cond (< (colors/lightness color) 3)  ;; Black, off
          0

          ;; The first four velocities are a white/gray section, starting
          ;; with black as zero, through white as three.
          (< (colors/saturation color) 20)
          (min 3 (- 4 brightness-shift))

          ;; Find the note value that approximates the hue and lightness.
          ;; From note 4 to note 59, the pads are in groups of four for
          ;; a single hue, starting with the desaturated version, then
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
    (midi/midi-note-on user-port-out note (velocity-for-color color))))

