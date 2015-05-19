(ns afterglow.examples
  "Show some simple ways to use Afterglow, and hopefully inspire
  exploration." {:author "James Elliott"}
  (:require [afterglow.effects.color :refer [color-cue]]
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
            [taoensso.timbre :as timbre]
            [taoensso.timbre.appenders.rotor :as rotor]))

;; Make sure the experimenter does not get blasted with a ton of debug messages
(timbre/set-level! :info)

;; Provide a nice, organized set of log files to help hunt down problems, especially
;; for exceptions which occur on background threads.
(timbre/set-config!
 [:appenders :rotor]
 {:min-level :info
  :enabled? true
  :async? false ; should be always false for rotor
  :max-message-per-msecs nil
  :fn rotor/appender-fn})

(timbre/set-config!
 [:shared-appender-config :rotor]
 {:path "logs/afterglow.log" :max-size 100000 :backlog 5})

;; Create a show that runs on OLA universe 1, for demonstration purposes.
(defonce ^{:doc "An example show which controls only the OLA universe with ID 1."}
  sample-show (show/show 1))

;; Make it the default show so we don't need to wrap everything below
;; in a (with-show sample-show ...) binding.
(set-default-show! sample-show)

;; Throw a couple of fixtures in there to play with. For better fun, use
;; fixtures and addresses that correspond to your actual hardware.
(show/patch-fixture! :hex-1 (chauvet/slimpar-hex3-irc) 1 129)
(show/patch-fixture! :blade-1 (blizzard/blade-rgbw) 1 270)
(show/patch-fixture! :ws-1 (blizzard/weather-system) 1 161 :x 1.0 :y 1.5)

(defn global-color-cue
  "Make a color cue which affects all lights in the sample show. This
  became vastly more useful once I implemented dynamic color
  parameters."
  [color]
  (try
    (let [[c desc] (cond (= (type color) :com.evocomputing.colors/color)
                       [color (color-name color)]
                       (and (satisfies? params/IParam color) (= (params/result-type color) :com.evocomputing.colors/color))
                       [color "variable"]
                       :else
                       [(create-color color) color])]
      (color-cue (str "Color: " desc) c (show/all-fixtures)))
    (catch Exception e
      (throw (Exception. (str "Can't figure out how to create color from " color) e)))))

(def blue-cue
  "An effect which assigns all fixtures to a nice blue color."
  (global-color-cue "slateblue"))

(defn master-cue
  "Return an effect function that sets all the dimmers in the sample
  rig. Originally this had to be to a static value, but now that
  dynamic parameters exist, it can vary in response to a MIDI mapped
  show variable, an oscillator, or (once geometry is implemented), the
  location of the fixture."
  [level]
  (dimmer-cue level (show/all-fixtures)))

;; Start simple with a cool blue color from all the lights
(show/add-function! :color blue-cue)
(show/add-function! :master (master-cue 255))

;; Get a little fancier with a beat-driven fade
;; (show/add-function! :master
;;                     (master-cue (params/build-oscillated-param (oscillators/sawtooth-beat))))

;; To actually start the effects above (although only the last one assigned to any
;; given keyword will still be in effect), uncomment or evaluate the next line:
;; (show/start!)

(defn sparkle-test
  "Set up a sedate rainbow fade and then layer on a sparkle effect to test
  effect mixing."
  []
  (let [hue-param (params/build-oscillated-param (oscillators/sawtooth-phrase) :max 360)]
    (show/add-function! :color
                        (global-color-cue
                         (params/build-color-param :s 100 :l 50 :h hue-param)))
    (show/add-function! :sparkle
                        (fun/sparkle sample-show (show/all-fixtures sample-show) :chance 0.05 :fade-time 50))))

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
    (show/add-function! :color
                        (global-color-cue
                         (params/build-color-param :s 100 :l 50 :h hue-param)))
    (show/add-function! :sparkle
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
