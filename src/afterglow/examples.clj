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
            [com.evocomputing.colors :refer [color-name create-color]]
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

;; Create a show that runs on DMX universe 1, for demonstration purposes.
(defonce sample-show (show/show 1))

;; Throw a couple of fixtures in there to play with. For better fun, use
;; fixtures and addresses that correspond to your actual hardware.
(show/patch-fixture! sample-show :hex-1 (chauvet/slimpar-hex3-irc) 1 129)
(show/patch-fixture! sample-show :blade-1 (blizzard/blade-rgbw) 1 270)
(show/patch-fixture! sample-show :ws-1 (blizzard/weather-system) 1 161)

(defn global-color-cue
  "Make a fixed color cue which affects all lights in the sample rig."
  [color]
  (try
    (let [c (if (= (type color) :com.evocomputing.colors/color)
              color
              (create-color color))]
      (color-cue (str "Color: " (color-name c)) c (show/all-fixtures sample-show)))
    (catch Exception e
      (throw (Exception. (str "Can't figure out how to create color from " color))))))

(def blue-cue (global-color-cue :slateblue))

(defn master-cue
  "Return an effect function that sets all the dimmers in the sample rig to a fixed value."
  [level]
  (dimmer-cue level (show/all-fixtures sample-show)))

;; Start simple with a cool blue color from all the lights
(show/add-function! sample-show :color blue-cue)
(show/add-function! sample-show :master (master-cue 255))

;; Get a little fancier with some beat-driven fades
(show/add-function! sample-show :master
                    (dimmer-oscillator (oscillators/sawtooth-beat)
                                       (show/all-fixtures sample-show)))

;; Shift a little around yellow...
;; (let [yellow (create-color :yellow)]
;;   (show/add-function! sample-show :color
;;                       (hue-oscillator (oscillators/sine-beat)
;;                                       (show/all-fixtures sample-show)
;;                                       :min (hue (adjust-hue yellow -5))
;;                                       :max (hue (adjust-hue yellow 5)))))


;; To actually start the effects above (although only the last one assigned to any
;; given keyword will still be in effect), uncomment or evaluate the next line:
;; (show/start! sample-show)

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
