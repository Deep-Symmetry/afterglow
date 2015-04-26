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

;; Create a show that runs on DMX universe 1, for demonstration purposes.
(defonce sample-show (show/show 1))

;; Throw a couple of fixtures in there to play with. For better fun, use
;; fixtures and addresses that correspond to your actual hardware.
(show/patch-fixture! sample-show :hex-1 (chauvet/slimpar-hex3-irc) 1 129)
(show/patch-fixture! sample-show :blade-1 (blizzard/blade-rgbw) 1 270)

(defn global-color-cue
  "Make a fixed color cue which affects all lights in the sample rig."
  [color-name]
  (afterglow.effects.color/color-cue (com.evocomputing.colors/create-color color-name)
                                     (show/all-fixtures sample-show)))

(def blue-cue (global-color-cue :slateblue))

(defn master-cue
  "Return an effect function that sets all the dimmers in the sample rig to a fixed value."
  [level]
  (afterglow.effects.dimmer/dimmer-cue level (show/all-fixtures sample-show)))

;; Start simple with a cool blue color from all the lights
;;(show/add-function! sample-show :color blue-cue)
;;(show/add-function! sample-show :master (master-cue 255))

;; Get a little fancier with some beat-driven fades
;;(show/add-function! sample-show :master
;;                    (afterglow.effects.dimmer/sawtooth-beat (show/all-fixtures sample-show)))

