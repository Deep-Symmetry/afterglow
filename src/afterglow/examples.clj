(ns afterglow.examples
  "Show some simple ways to use Afterglow, and hopefully inspire
  exploration." {:author "James Elliott"}
  (:require [afterglow.core :as core]
            [afterglow.transform :as tf]
            [afterglow.controllers :as ct]
            [afterglow.controllers.ableton-push :as push]
            [afterglow.effects.color :refer [color-cue]]
            [afterglow.effects.dimmer :refer [dimmer-cue master-set-level]]
            [afterglow.effects.fun :as fun]
            [afterglow.effects.oscillators :as oscillators]
            [afterglow.effects.params :as params]
            [afterglow.fixtures.blizzard :as blizzard]
            [afterglow.fixtures.chauvet :as chauvet]
            [afterglow.rhythm :refer :all]
            [afterglow.show :as show]
            [afterglow.show-context :refer :all]
            [com.evocomputing.colors :refer [color-name create-color hue adjust-hue]]
            [taoensso.timbre :as timbre]))

;; Since this class is an entry point for interactive REPL usage,
;; make sure a sane logging environment is established.
(core/init-logging)

;; Create a show that runs on OLA universe 1, for demonstration purposes.
(defonce ^{:doc "An example show which controls only the OLA universe with ID 1."}
  sample-show (show/show 1))

;; Make it the default show so we don't need to wrap everything below
;; in a (with-show sample-show ...) binding.
(set-default-show! sample-show)

;; Throw a couple of fixtures in there to play with. For better fun, use
;; fixtures and addresses that correspond to your actual hardware.
(show/patch-fixture! :torrent-1 (blizzard/torrent-f3) 1 1 :x (tf/inches 49) :y (tf/inches 61.5) :z (tf/inches 6)
                     :y-rotation (tf/degrees -45))
(show/patch-fixture! :hex-1 (chauvet/slimpar-hex3-irc) 1 129 :y (tf/inches 4) :z (tf/inches 10)
                     :x-rotation (tf/degrees 90))
(show/patch-fixture! :blade-1 (blizzard/blade-rgbw) 1 270 :y (tf/inches 9))
(show/patch-fixture! :blade-2 (blizzard/blade-rgbw) 1 240 :x (tf/inches 40) :y (tf/inches 58) :z (tf/inches -15)
                     :y-rotation (tf/degrees -45))
(show/patch-fixture! :ws-1 (blizzard/weather-system) 1 161 :x (tf/inches 22) :y (tf/inches 7) :z (tf/inches 7)
                     :x-rotation (tf/degrees 90))

(defn global-color-effect
  "Make a color effect which affects all lights in the sample show.
  This became vastly more useful once I implemented dynamic color
  parameters."
  [color & {:keys [include-color-wheels]}]
  (try
    (let [[c desc] (cond (= (type color) :com.evocomputing.colors/color)
                       [color (color-name color)]
                       (and (satisfies? params/IParam color) (= (params/result-type color) :com.evocomputing.colors/color))
                       [color "variable"]
                       :else
                       [(create-color color) color])]
      (color-cue (str "Color: " desc) c (show/all-fixtures) :include-color-wheels include-color-wheels))
    (catch Exception e
      (throw (Exception. (str "Can't figure out how to create color from " color) e)))))

(def blue-cue
  "An effect which assigns all fixtures to a nice blue color."
  (global-color-effect "slateblue" :include-color-wheels true))

(defn global-dimmer-cue
  "Return an effect that sets all the dimmers in the sample rig.
  Originally this had to be to a static value, but now that dynamic
  parameters exist, it can vary in response to a MIDI mapped show
  variable, an oscillator, or (once geometry is implemented), the
  location of the fixture."
  [level]
  (dimmer-cue level (show/all-fixtures)))

(defn fiat-lux
  "Start simple with a cool blue color from all the lights."
  []
  (show/add-effect! :color blue-cue)
  (show/add-effect! :dimmers (global-dimmer-cue 255))
  (show/add-effect! :torrent-shutter
                    (afterglow.effects.channel/function-cue
                     "Torrent Shutter Open" :shutter-open 50 (show/fixtures-named "torrent"))))

;; Get a little fancier with a beat-driven fade
;; (show/add-effect! :dimmers (global-dimmer-cue
;;   (params/build-oscillated-param (oscillators/sawtooth-beat))))

;; To actually start the effects above (although only the last one assigned to any
;; given keyword will still be in effect), uncomment or evaluate the next line:
;; (show/start!)

(defn sparkle-test
  "Set up a sedate rainbow fade and then layer on a sparkle effect to test
  effect mixing."
  []
  (let [hue-param (params/build-oscillated-param (oscillators/sawtooth-phrase) :max 360)]
    (show/add-effect! :color
                      (global-color-effect
                       (params/build-color-param :s 100 :l 50 :h hue-param)))
    (show/add-effect! :sparkle
                      (fun/sparkle (show/all-fixtures) :chance 0.05 :fade-time 50))))

(defn mapped-sparkle-test
  "A verion of the sparkle test that creates a bunch of MIDI-mapped
  show variables to adjust parameters while it runs."
  []
  (show/add-midi-control-to-var-mapping "Slider" 0 16 :sparkle-hue :max 360)
  (show/add-midi-control-to-var-mapping "Slider" 0 0 :sparkle-lightness :max 100.0)
  (show/add-midi-control-to-var-mapping  "Slider" 0 17 :sparkle-fade :min 10 :max 2000)
  (show/add-midi-control-to-var-mapping  "Slider" 0 1 :sparkle-chance :max 0.3)
  (let [hue-param (params/build-oscillated-param (oscillators/sawtooth-phrase) :max 360)
        sparkle-color-param (params/build-color-param :s 100 :l :sparkle-lightness :h :sparkle-hue)]
    (show/add-effect! :color
                      (global-color-effect
                       (params/build-color-param :s 100 :l 50 :h hue-param)))
    (show/add-effect! :sparkle
                      (fun/sparkle (show/all-fixtures) :color sparkle-color-param
                                   :chance :sparkle-chance :fade-time :sparkle-fade))))

(defn ^:deprecated test-phases
  "This is for testing the enhanced multi-beat and fractional-beat
  phase calculations I am implementing; it should probably more
  somewhere else, or just go away once there are example effects
  successfully using these."
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

;; Temporary for working on light aiming code

(defn add-pan-tilt-controls
  []
  (show/add-midi-control-to-var-mapping "Slider" 0 0 :tilt :max 255.99)
  (show/add-midi-control-to-var-mapping "Slider" 0 16 :pan :max 255.99)
  (show/add-effect!
   :pan-torrent (afterglow.effects.channel/channel-cue
                 "Pan Torrent"
                 (params/build-variable-param :pan)
                 (afterglow.channels/extract-channels (show/fixtures-named :torrent) #(= (:type %) :pan))))
  (show/add-effect!
   :tilt-torrent (afterglow.effects.channel/channel-cue
                  "Tilt Torrent"
                  (params/build-variable-param :tilt)
                  (afterglow.channels/extract-channels (show/fixtures-named :torrent) #(= (:type %) :tilt)))))

(defn add-xyz-controls
  []
  (show/add-midi-control-to-var-mapping "Slider" 0 4 :x)
  (show/add-midi-control-to-var-mapping "Slider" 0 5 :y)
  (show/add-midi-control-to-var-mapping "Slider" 0 6 :z)
  #_(show/add-effect! :position
                    (afterglow.effects.movement/direction-cue
                     "Pointer" (params/build-direction-param :x :x :y :y :z :z) (show/all-fixtures)))
  (show/add-effect! :position
                    (afterglow.effects.movement/aim-cue
                     "Aimer" (params/build-aim-param :x :x :y :y :z :z) (show/all-fixtures)))
  (show/set-variable! :y  2.6416))  ; Approximate height of ceiling

(defn global-color-cue
  "Create a cue-grid entry which establishes a global color effect."
  [color x y & {:keys [include-color-wheels held]}]
  (let [cue (ct/cue :color (fn [] (global-color-effect color :include-color-wheels include-color-wheels))
                    :held held
                    :color (create-color color))]
    (ct/set-cue! (:cue-grid *show*) x y cue)))

(defn use-push
  []
  (defonce pc (push/bind-to-show *show*))
  (global-color-cue "red" 0 0 :include-color-wheels true)
  (global-color-cue "orange" 1 0 :include-color-wheels true)
  (global-color-cue "yellow" 2 0 :include-color-wheels true)
  (global-color-cue "green" 3 0 :include-color-wheels true)
  (global-color-cue "blue" 4 0 :include-color-wheels true)
  (global-color-cue "purple" 5 0 :include-color-wheels true)
  (global-color-cue "white" 6 0 :include-color-wheels true)

  (ct/set-cue! (:cue-grid *show*) 0 7
               (ct/cue :sparkle (fn [] (fun/sparkle (show/all-fixtures) :chance 0.05 :fade-time 50))
                       :held true
                       :priority 100)))
