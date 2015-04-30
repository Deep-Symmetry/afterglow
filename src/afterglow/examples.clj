(ns afterglow.examples
  "Show some simple ways to use Afterglow, inspire exploration."
  {:author "James Elliott"}
  (:require [afterglow.effects.color :refer [color-cue]]
            [afterglow.effects.dimmer :refer [dimmer-cue
                                              dimmer-oscillator]]
            [afterglow.effects.oscillators :as oscillators]
            [afterglow.fixtures.blizzard :as blizzard]
            [afterglow.fixtures.chauvet :as chauvet]
            [afterglow.rhythm :refer :all]
            [afterglow.show :as show]
            [taoensso.timbre :as timbre]))

;; Make sure the experimenter does not get blasted with a ton of debug messages
(timbre/set-level! :info)

;; Create a show that runs on DMX universe 1, for demonstration purposes.
(defonce sample-show (show/show 1))

;; Throw a couple of fixtures in there to play with. For better fun, use
;; fixtures and addresses that correspond to your actual hardware.
(show/patch-fixture! sample-show :hex-1 (chauvet/slimpar-hex3-irc) 1 129)
(show/patch-fixture! sample-show :blade-1 (blizzard/blade-rgbw) 1 270)

(defn global-color-cue
  "Make a fixed color cue which affects all lights in the sample rig."
  [color-name]
  (color-cue (str "Color: " (name color-name))
                                     (com.evocomputing.colors/create-color color-name)
                                     (show/all-fixtures sample-show)))

(def blue-cue (global-color-cue :slateblue))

(defn master-cue
  "Return an effect function that sets all the dimmers in the sample rig to a fixed value."
  [level]
  (dimmer-cue level (show/all-fixtures sample-show)))

;; Start simple with a cool blue color from all the lights
;;(show/add-function! sample-show :color blue-cue)
;;(show/add-function! sample-show :master (master-cue 255))

;; Get a little fancier with some beat-driven fades
;; (show/add-function! sample-show :master
;;                     (dimmer-oscillator (oscillators/sawtooth-beat)
;;                                        (show/all-fixtures sample-show)))


;; This is for testing the enhance multi-beat and fractional-beat phase calculations I am implementing;
;; it should probably more somewhere else, or just go away once there are example effects successfully
;; using these.
(defn test-phases
  ([]
   (test-phases 20))
  ([iterations]
   (dotimes [n iterations]
     (let [snap (metro-snapshot (:metronome sample-show))]
       (println (format "Beat %4d (phase %.3f) bar %4d (phase %.3f) 1:%.3f, 2:%.3f, 4:%.3f, 1/2:%.3f, 1/4:%.3f, 3/4:%.3f"
                        (:beat snap) (:beat-phase snap) (:bar snap) (:bar-phase snap)
                        (snapshot-beat-phase snap 1) (snapshot-beat-phase snap 2) (snapshot-beat-phase snap 4)
                        (snapshot-beat-phase snap 1/2) (snapshot-beat-phase snap 1/4) (snapshot-beat-phase snap 3/4)))
       (Thread/sleep 33)))))
