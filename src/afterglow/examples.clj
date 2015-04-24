(ns
    ^{:doc "Show some simple ways to use Afterglow, inspire exploration."
      :author "James Elliott"}
  afterglow.examples
  (:require [afterglow.show :as show]
            [afterglow.rhythm :refer :all]
            [afterglow.channels :refer [patch-fixture]]
            [afterglow.fixtures.chauvet :as chauvet]
            [afterglow.fixtures.blizzard :as blizzard]
            [afterglow.effects.color :refer [color-cue]]
            [afterglow.effects.dimmer :refer [dimmer-cue]]
            [com.evocomputing.colors :as colors]
            [taoensso.timbre :as timbre :refer [error info debug spy]]))

(def sample-rig
  [(patch-fixture (chauvet/slimpar-hex3-irc) 1 129) (patch-fixture (blizzard/blade-rgbw) 1 270)])

(defn global-color-cue
  "Make a fixed color cue which affects all lights in the sample rig."
  [color-name]
  (afterglow.effects.color/color-cue (com.evocomputing.colors/create-color color-name) sample-rig))

(def blue-cue (global-color-cue :slateblue))

(defn master-cue
  "Return an effect function that sets all the dimmers in the sample rig to a fixed value."
  [level]
  (afterglow.effects.dimmer/dimmer-cue level sample-rig))

;; Create a show that runs on DMX universe 1, for demonstration purposes.
(defonce sample-show (show/show 1))

;; Start simple with a cool blue color from all the lights
;(show/add-function! sample-show :color blue-cue)
;(show/add-function! sample-show :master (master-cue 255))

;; Get a little fancier with some beat-driven fades
;(show/add-function! sample-show :master (afterglow.effects.dimmer/sawtooth-beat (:metronome sample-show) sample-rig))

