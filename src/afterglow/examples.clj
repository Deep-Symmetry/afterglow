(ns afterglow.examples
  "Show some simple ways to use Afterglow, and hopefully inspire
  exploration." {:author "James Elliott"}
  (:require [afterglow.core :as core]
            [afterglow.transform :as tf]
            [afterglow.controllers :as ct]
            [afterglow.controllers.ableton-push :as push]
            [afterglow.effects.color :refer [color-effect]]
            [afterglow.effects.cues :as cues]
            [afterglow.effects.dimmer :refer [dimmer-effect master-set-level]]
            [afterglow.effects.fun :as fun]
            [afterglow.effects.movement :as move]
            [afterglow.effects.oscillators :as oscillators]
            [afterglow.effects.params :as params]
            [afterglow.fixtures.american-dj :as adj]
            [afterglow.fixtures.blizzard :as blizzard]
            [afterglow.fixtures.chauvet :as chauvet]
            [afterglow.rhythm :refer :all]
            [afterglow.show :as show]
            [afterglow.show-context :refer :all]
            [com.evocomputing.colors :refer [color-name create-color hue adjust-hue]]
            [overtone.osc :as osc]
            [taoensso.timbre :as timbre]))

(defn use-sample-show
  "Set up a sample show for experimenting with Afterglow. By default
  it will create the show to use universe 1, but if for some reason
  your OLA environment makes using a different universe ID more
  convenient, you can override that by supplying a different ID
  after :universe"
  [& {:keys [universe] :or {universe 1}}]
  ;; Since this class is an entry point for interactive REPL usage,
  ;; make sure a sane logging environment is established.
  (core/init-logging)

  ;; Create a show on the chosen OLA universe, for demonstration purposes.
  ;; Make it the default show so we don't need to wrap everything below
  ;; in a (with-show sample-show ...) binding.
  (set-default-show! (show/show :universes [universe]))

  ;; TODO: Should this be automatic? If so, creating the show should assign the name too.
  ;; Register it with the web interface.
  (show/register-show *show* "Sample Show")

  ;; Throw a couple of fixtures in there to play with. For better fun, use
  ;; fixtures and addresses that correspond to your actual hardware.
  (show/patch-fixture! :torrent-1 (blizzard/torrent-f3) universe 1
                       :x (tf/inches 44) :y (tf/inches 51.75) :z (tf/inches -4.75)
                       :y-rotation (tf/degrees 0))
  (show/patch-fixture! :torrent-2 (blizzard/torrent-f3) universe 17
                       :x (tf/inches -44) :y (tf/inches 51.75) :z (tf/inches -4.75)
                       :y-rotation (tf/degrees 0))
  (show/patch-fixture! :hex-1 (chauvet/slimpar-hex3-irc) universe 129 :x (tf/inches 14) :y (tf/inches 44)
                       :z (tf/inches -4.75)
                       :x-rotation (tf/degrees 90))
  (show/patch-fixture! :hex-2 (chauvet/slimpar-hex3-irc) universe 145 :x (tf/inches -14) :y (tf/inches 44)
                       :z (tf/inches -4.75)
                       :x-rotation (tf/degrees 90))
  (show/patch-fixture! :blade-1 (blizzard/blade-rgbw) universe 225
                       :x (tf/inches 26.5) :y (tf/inches 48.5) :z (tf/inches -4.75)
                       :y-rotation (tf/degrees -45))
  (show/patch-fixture! :blade-2 (blizzard/blade-rgbw) universe 240
                       :x (tf/inches -26.5) :y (tf/inches 48.5) :z (tf/inches -4.75)
                       :y-rotation (tf/degrees 45))
  (show/patch-fixture! :blade-3 (blizzard/blade-rgbw) universe 255
                       :x (tf/inches 0) :y (tf/inches 38.75) :z (tf/inches 207.5)
                       :y-rotation (tf/degrees 180))
  (show/patch-fixture! :blade-4 (blizzard/blade-rgbw) universe 270 :y (tf/inches 48.5) :z (tf/inches -4.75))
  (show/patch-fixture! :ws-1 (blizzard/weather-system) universe 161
                       :x (tf/inches 55) :y (tf/inches 71) :z (tf/inches 261) :y-rotation (tf/degrees 225))
  (show/patch-fixture! :ws-2 (blizzard/weather-system) universe 187
                       :x (tf/inches -55) :y (tf/inches 71) :z (tf/inches 261) :y-rotation (tf/degrees 135))
  (show/patch-fixture! :puck-1 (blizzard/puck-fab5) universe 97 :x (tf/inches -76) :y (tf/inches 8) :z (tf/inches 52))
  (show/patch-fixture! :puck-2 (blizzard/puck-fab5) universe 113 :x (tf/inches -76) :y (tf/inches 8) :z (tf/inches 40))
  (show/patch-fixture! :snowball (blizzard/snowball) universe 33 :x (tf/inches -76) :y (tf/inches 32)
                       :z (tf/inches 164.5))
  (show/patch-fixture! :hyp-rgb (adj/hypnotic-rgb) universe 45)
  '*show*)


(defn global-color-effect
  "Make a color effect which affects all lights in the sample show.
  This became vastly more useful once I implemented dynamic color
  parameters. Can include only a specific set of lights by passing
  them with :lights"
  [color & {:keys [include-color-wheels lights] :or {lights (show/all-fixtures)}}]
  (try
    (let [[c desc] (cond (= (type color) :com.evocomputing.colors/color)
                       [color (color-name color)]
                       (and (satisfies? params/IParam color)
                            (= (params/result-type color) :com.evocomputing.colors/color))
                       [color "variable"]
                       :else
                       [(create-color color) color])]
      (color-effect (str "Color: " desc) c lights :include-color-wheels include-color-wheels))
    (catch Exception e
      (throw (Exception. (str "Can't figure out how to create color from " color) e)))))

(defn global-dimmer-effect
  "Return an effect that sets all the dimmers in the sample rig.
  Originally this had to be to a static value, but now that dynamic
  parameters exist, it can vary in response to a MIDI mapped show
  variable, an oscillator, or (once geometry is implemented), the
  location of the fixture. You can override the default name by
  passing in a value with :effect-name"
  [level & {:keys [effect-name]}]
  (dimmer-effect level (show/all-fixtures) :effect-name effect-name))

(defn fiat-lux
  "Start simple with a cool blue color from all the lights."
  []
  (show/add-effect! :color (global-color-effect "slateblue" :include-color-wheels true))
  (show/add-effect! :dimmers (global-dimmer-effect 255))
  (show/add-effect! :torrent-shutter
                    (afterglow.effects.channel/function-effect
                     "Torrents Open" :shutter-open 50 (show/fixtures-named "torrent"))))

;; Get a little fancier with a beat-driven fade
;; (show/add-effect! :dimmers (global-dimmer-effect
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
     (let [snap (metro-snapshot (:metronome *show*))]
       (println
        (format "Beat %4d (phase %.3f) bar %4d (phase %.3f) 1:%.3f, 2:%.3f, 4:%.3f, 1/2:%.3f, 1/4:%.3f, 3/4:%.3f"
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
   :pan-torrent (afterglow.effects.channel/channel-effect
                 "Pan Torrent"
                 (params/build-variable-param :pan)
                 (afterglow.channels/extract-channels (show/fixtures-named :torrent) #(= (:type %) :pan))))
  (show/add-effect!
   :tilt-torrent (afterglow.effects.channel/channel-effect
                  "Tilt Torrent"
                  (params/build-variable-param :tilt)
                  (afterglow.channels/extract-channels (show/fixtures-named :torrent) #(= (:type %) :tilt)))))

(defn add-xyz-controls
  []
  (show/add-midi-control-to-var-mapping "Slider" 0 4 :x)
  (show/add-midi-control-to-var-mapping "Slider" 0 5 :y)
  (show/add-midi-control-to-var-mapping "Slider" 0 6 :z)
  (show/add-effect! :position
                    (move/direction-effect
                     "Pointer" (params/build-direction-param :x :x :y :y :z :z) (show/all-fixtures)))
  #_(show/add-effect! :position
                    (move/aim-effect
                     "Aimer" (params/build-aim-param :x :x :y :y :z :z) (show/all-fixtures)))
  (show/set-variable! :y 2.6416))  ; Approximate height of ceiling

(defonce osc-server
  (atom nil))

(defn osc-demo
  "Early experiments with using OSC to control shows. This should grow
  into a well-defined API, with integration to show variables, cue
  grids, and the like."
  []
  (swap! osc-server (fn [server] (or server (osc/osc-server 16010 "Afterglow"))))
  (osc/osc-handle @osc-server "/aim" (fn [msg]
                                       (let [left -2.5
                                             right 1.65
                                             width (- right left)
                                             front -0.7
                                             rear 3.1
                                             depth (- rear front)]
                                         (show/set-variable! :x (+ left (* width (first (:args msg)))))
                                         (show/set-variable! :z (+ front (* depth (second (:args msg))))))
                                       #_(timbre/info msg)))
  (osc/osc-handle @osc-server "/sparkle" (fn [msg]
                                       (if (pos? (first (:args msg)))
                                         (show/add-effect! :sparkle (fun/sparkle (show/all-fixtures) :chance 0.1
                                                                                 :fade-time 100))
                                         (show/end-effect! :sparkle))))
  #_(osc/osc-listen @osc-server (fn [msg] (timbre/info msg)) :debug)
  #_(osc/zero-conf-on)
  (show/set-variable! :x 0)
  (show/set-variable! :y 2.6416) ; Approximate height of ceiling
  (show/set-variable! :z 0)
  (show/add-effect! :position
                    (move/aim-effect
                     "Aimer" (params/build-aim-param :x :x :y :y :z :z) (show/all-fixtures))))

(defn osc-shutdown
  "Shut down osc server and clean up."
  []
  #_(osc/zero-conf-off)
  (swap! osc-server (fn [server]
                      (when server
                        (osc/osc-rm-all-listeners server)
                        (osc/osc-rm-all-handlers server)
                        (osc/osc-close server)
                        nil))))

(defn global-color-cue
  "Create a cue-grid entry which establishes a global color effect."
  [color x y & {:keys [include-color-wheels held]}]
  (let [cue (cues/cue :color (fn [_] (global-color-effect color :include-color-wheels include-color-wheels))
                      :held held
                      :color (create-color color))]
    (ct/set-cue! (:cue-grid *show*) x y cue)))

(defn- name-torrent-gobo-cue
  "Come up with a summary name for one of the gobo cues we are
  creating that is concise but meaningful on a controller interface."
  [prefix function]
  (let [simplified (clojure.string/replace (name function) #"^gobo-fixed-" "")
        simplified (clojure.string/replace simplified #"^gobo-moving-" "m/")
        spaced (clojure.string/replace simplified "-" " ")]
    (str (clojure.string/upper-case (name prefix)) " " spaced)))

(defn- make-torrent-gobo-cues
  "Create cues for the fixed and moving gobo options, stationary and
  shaking. Takes up half a page on the Push, with the top left at the
  coordinates specified."
  [prefix fixtures top left]
  ;; Make cues for the stationary and shaking versions of all fixed gobos
  (doseq [_ (map-indexed (fn [i v]
                           (let [blue (create-color :blue)
                                 x (if (< i 8) left (+ left 2))
                                 y (if (< i 8) (- top i) (- top i -1))
                                 cue-key (keyword (str (name prefix) "-gobo-fixed"))]
                             (ct/set-cue! (:cue-grid *show*) x y
                                          (cues/function-cue cue-key (keyword v) fixtures :color blue
                                                             :short-name (name-torrent-gobo-cue prefix v)))
                             (let [function (keyword (str (name v) "-shake"))]
                               (ct/set-cue! (:cue-grid *show*) (inc x) y
                                            (cues/function-cue cue-key function fixtures :color blue
                                                               :short-name (name-torrent-gobo-cue prefix function))))))
                         ["gobo-fixed-mortar" "gobo-fixed-4-rings" "gobo-fixed-atom" "gobo-fixed-jacks"
                          "gobo-fixed-saw" "gobo-fixed-sunflower" "gobo-fixed-45-adapter"
                          "gobo-fixed-star" "gobo-fixed-rose-fingerprint"])])
  ;; Make cues for the stationary and shaking versions of all rotating gobos
  (doseq [_ (map-indexed (fn [i v]
                           (let [green (create-color :green)
                                 cue-key (keyword (str (name prefix) "-gobo-moving"))]
                             (ct/set-cue! (:cue-grid *show*) (+ left 2) (- top i)
                                          (cues/function-cue cue-key (keyword v) fixtures :color green
                                                             :short-name (name-torrent-gobo-cue prefix v)))
                             (let [function (keyword (str (name v) "-shake"))]
                               (ct/set-cue! (:cue-grid *show*) (+ left 3) (- top i)
                                            (cues/function-cue cue-key function fixtures :color green
                                                               :short-name (name-torrent-gobo-cue prefix function))))))
                         ["gobo-moving-rings" "gobo-moving-color-swirl" "gobo-moving-stars"
                          "gobo-moving-optical-tube" "gobo-moving-magenta-bundt"
                          "gobo-moving-blue-megahazard" "gobo-moving-turbine"])]))

(defn make-strobe-cue
  [name fixtures x y]
  (ct/set-cue! (:cue-grid *show*) x y	
               (cues/cue (keyword (str "strobe-" (clojure.string/replace (clojure.string/lower-case name) " " "-")))
                         (fn [var-map] (fun/strobe (str "Strobe " name) fixtures (:level var-map 50)))
                           :held true
                           :priority 100
                           :variables [{:key "level" :min 0 :max 100 :start 50 :name "Level"
                                        :aftertouch true :aftertouch-min 25}
                                       {:key :strobe-lightness :min 0 :max 100 :name "Lightness" :aftertouch true}])))

(defn make-cues
  "Create a bunch of example cues for experimentation."
  []
  (let [hue-bar (params/build-oscillated-param ; Spread a rainbow across a bar of music
                 (oscillators/sawtooth-bar) :max 360)
        hue-gradient (params/build-spatial-param ; Spread a rainbow across the light grid
                      (show/all-fixtures)
                      (fn [head] (- (:x head) (:min-x @(:dimensions *show*)))) :end 360)]
    (global-color-cue "red" 0 0 :include-color-wheels true)
    (global-color-cue "orange" 1 0 :include-color-wheels true)
    (global-color-cue "yellow" 2 0 :include-color-wheels true)
    (global-color-cue "green" 3 0 :include-color-wheels true)
    (global-color-cue "blue" 4 0 :include-color-wheels true)
    (global-color-cue "purple" 5 0 :include-color-wheels true)
    (global-color-cue "white" 6 0 :include-color-wheels true)


    (ct/set-cue! (:cue-grid *show*) 0 1
                 (cues/cue :color (fn [_] (global-color-effect
                                           (params/build-color-param :s 100 :l 50 :h hue-bar)))
                           :short-name "Rainbow Bar Fade"))
    (ct/set-cue! (:cue-grid *show*) 1 1
                 (cues/cue :color (fn [_] (global-color-effect
                                           (params/build-color-param :s 100 :l 50 :h hue-gradient)
                                           :include-color-wheels true))
                           :short-name "Rainbow Grid"))
    (ct/set-cue! (:cue-grid *show*) 2 1
                 (cues/cue :color (fn [_] (global-color-effect
                                           (params/build-color-param :s 100 :l 50 :h hue-gradient
                                                                     :adjust-hue hue-bar)))
                           :short-name "Rainbow Grid+Bar"))

    (ct/set-cue! (:cue-grid *show*) 7 1
                 (cues/cue :color (fn [_] (global-color-effect
                                           (params/build-color-param :s 100 :l 50 :h hue-gradient
                                                                     :adjust-hue hue-bar)
                                           :lights (show/fixtures-named "blade")))
                         :short-name "Rainbow Blades"))


    ;; TODO: Write a macro to make it easier to bind cue variables.
    (ct/set-cue! (:cue-grid *show*) 0 7
                 (cues/cue :sparkle (fn [var-map] (fun/sparkle (show/all-fixtures)
                                                               :chance (:chance var-map 0.05)
                                                               :fade-time (:fade-time var-map 50)))
                         :held true
                         :priority 100
                         :variables [{:key "chance" :min 0.0 :max 0.4 :start 0.05 :aftertouch true}
                                     {:key "fade-time" :name "Fade" :min 1 :max 2000 :start 50 :type :integer}]))

    (ct/set-cue! (:cue-grid *show*) 7 7
                 (cues/function-cue :strobe-all :strobe (show/all-fixtures) :effect-name "Strobe"))


    ;; Dimmer cues to turn on and set brightness of groups of lights
    (ct/set-cue! (:cue-grid *show*) 0 2
                 (cues/function-cue :dimmers :dimmer (show/all-fixtures) :level 100 :color :yellow
                                    :effect-name "All Dimmers" :end-keys [:torrent-dimmers :blade-dimmers :ws-dimmers
                                                                          :puck-dimmers :hex-dimmers]))
    (ct/set-cue! (:cue-grid *show*) 1 2
                 (cues/function-cue :torrent-dimmers :dimmer (show/fixtures-named "torrent") :level 100 :color :orange
                                    :effect-name "Torrent Dimmers" :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 2 2
                 (cues/function-cue :blade-dimmers :dimmer (show/fixtures-named "blade") :level 100 :color :orange
                                    :effect-name "Blade Dimmers" :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 3 2
                 (cues/function-cue :ws-dimmers :dimmer (show/fixtures-named "ws") :level 100 :color :orange
                                    :effect-name "Weather System Dimmers" :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 4 2
                 (cues/function-cue :hex-dimmers :dimmer (show/fixtures-named "hex") :level 100 :color :orange
                                    :effect-name "Hex Dimmers" :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 5 2
                 (cues/function-cue :puck-dimmers :dimmer (show/fixtures-named "puck") :level 100 :color :orange
                                    :effect-name "Puck Dimmers" :end-keys [:dimmers]))

    ;; Dimmer oscillator cues: Sawtooth down each beat
    (ct/set-cue! (:cue-grid *show*) 0 3
                 (cues/cue :dimmers (fn [_] (global-dimmer-effect
                                             (params/build-oscillated-param (oscillators/sawtooth-beat :down? true))
                                             :effect-name "All Saw Down Beat"))
                           :color :yellow :end-keys [:torrent-dimmers :blade-dimmers :ws-dimmers
                                                     :puck-dimmers :hex-dimmers]))
    (ct/set-cue! (:cue-grid *show*) 1 3
                 (cues/cue :torrent-dimmers
                           (fn [_] (dimmer-effect
                                    (params/build-oscillated-param (oscillators/sawtooth-beat :down? true))
                                    (show/fixtures-named "torrent") :effect-name "Torrent Saw Down Beat"))
                           :color :orange :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 2 3
                 (cues/cue :blade-dimmers
                           (fn [_] (dimmer-effect
                                    (params/build-oscillated-param (oscillators/sawtooth-beat :down? true))
                                    (show/fixtures-named "blade") :effect-name "Blade Saw Down Beat"))
                           :color :orange :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 3 3
                 (cues/cue :ws-dimmers
                           (fn [_] (dimmer-effect
                                    (params/build-oscillated-param (oscillators/sawtooth-beat :down? true))
                                    (show/fixtures-named "ws") :effect-name "WS Saw Down Beat"))
                           :color :orange :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 4 3
                 (cues/cue :hex-dimmers
                           (fn [_] (dimmer-effect
                                    (params/build-oscillated-param (oscillators/sawtooth-beat :down? true))
                                    (show/fixtures-named "hex") :effect-name "Hex Saw Down Beat"))
                           :color :orange :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 5 3
                 (cues/cue :puck-dimmers
                           (fn [_] (dimmer-effect
                                    (params/build-oscillated-param (oscillators/sawtooth-beat :down? true))
                                    (show/fixtures-named "puck") :effect-name "Puck Saw Down Beat"))
                           :color :orange :end-keys [:dimmers]))

    ;; Dimmer oscillator cues: Sawtooth up over 2 beat
    (ct/set-cue! (:cue-grid *show*) 0 4
                 (cues/cue :dimmers (fn [_] (global-dimmer-effect
                                             (params/build-oscillated-param (oscillators/sawtooth-beat :beat-ratio 2))
                                             :effect-name "All Saw Up 2 Beat"))
                           :color :yellow :end-keys [:torrent-dimmers :blade-dimmers :ws-dimmers
                                                     :puck-dimmers :hex-dimmers]))
    (ct/set-cue! (:cue-grid *show*) 1 4
                 (cues/cue :torrent-dimmers
                           (fn [_] (dimmer-effect
                                    (params/build-oscillated-param (oscillators/sawtooth-beat :beat-ratio 2))
                                    (show/fixtures-named "torrent") :effect-name "Torrent Saw Up 2 Beat"))
                           :color :orange :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 2 4
                 (cues/cue :blade-dimmers
                           (fn [_] (dimmer-effect
                                    (params/build-oscillated-param (oscillators/sawtooth-beat :beat-ratio 2))
                                    (show/fixtures-named "blade") :effect-name "Blade Saw Up 2 Beat"))
                           :color :orange :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 3 4
                 (cues/cue :ws-dimmers
                           (fn [_] (dimmer-effect
                                    (params/build-oscillated-param (oscillators/sawtooth-beat :beat-ratio 2))
                                    (show/fixtures-named "ws") :effect-name "WS Saw Up 2 Beat"))
                           :color :orange :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 4 4
                 (cues/cue :hex-dimmers
                           (fn [_] (dimmer-effect
                                    (params/build-oscillated-param (oscillators/sawtooth-beat :beat-ratio 2))
                                    (show/fixtures-named "hex") :effect-name "Hex Saw Up 2 Beat"))
                           :color :orange :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 5 4
                 (cues/cue :puck-dimmers
                           (fn [_] (dimmer-effect
                                    (params/build-oscillated-param (oscillators/sawtooth-beat :beat-ratio 2))
                                    (show/fixtures-named "puck") :effect-name "Puck Saw Up 2 Beat"))
                           :color :orange :end-keys [:dimmers]))

    ;; Dimmer oscillator cues: Sine over a bar
    (ct/set-cue! (:cue-grid *show*) 0 5
                 (cues/cue :dimmers (fn [_] (global-dimmer-effect
                                             (params/build-oscillated-param (oscillators/sine-bar) :min 1)
                                             :effect-name "All Sine Bar"))
                           :color :cyan :end-keys [:torrent-dimmers :blade-dimmers :ws-dimmers
                                                   :puck-dimmers :hex-dimmers]))
    (ct/set-cue! (:cue-grid *show*) 1 5
                 (cues/cue :torrent-dimmers
                           (fn [_] (dimmer-effect
                                    (params/build-oscillated-param (oscillators/sine-bar) :min 1)
                                    (show/fixtures-named "torrent") :effect-name "Torrent Sine Bar"))
                           :color :blue :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 2 5
                 (cues/cue :blade-dimmers
                           (fn [_] (dimmer-effect
                                    (params/build-oscillated-param (oscillators/sine-bar) :min 1)
                                    (show/fixtures-named "blade") :effect-name "Blade Sine Bar"))
                           :color :blue :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 3 5
                 (cues/cue :ws-dimmers
                           (fn [_] (dimmer-effect
                                    (params/build-oscillated-param (oscillators/sine-bar) :min 1)
                                    (show/fixtures-named "ws") :effect-name "WS Sine Bar"))
                           :color :blue :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 4 5
                 (cues/cue :hex-dimmers
                           (fn [_] (dimmer-effect
                                    (params/build-oscillated-param (oscillators/sine-bar) :min 1)
                                    (show/fixtures-named "hex") :effect-name "Hex Sine Bar"))
                           :color :blue :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 5 5
                 (cues/cue :puck-dimmers
                           (fn [_] (dimmer-effect
                                    (params/build-oscillated-param (oscillators/sine-bar) :min 1)
                                    (show/fixtures-named "puck") :effect-name "Puck Sine Bar"))
                           :color :blue :end-keys [:dimmers]))

    ;; Strobe cues
    (make-strobe-cue "All" (show/all-fixtures) 0 6)
    (make-strobe-cue "Torrents" (show/fixtures-named "torrent") 1 6)
    (make-strobe-cue "Blades" (show/fixtures-named "blade") 2 6)
    (make-strobe-cue "Weather Systems" (show/fixtures-named "ws") 3 6)
    (make-strobe-cue "Hexes" (show/fixtures-named "hex") 4 6)
    (make-strobe-cue "Pucks" (show/fixtures-named "puck") 5 6)

    (ct/set-cue! (:cue-grid *show*) 1 6
                 (cues/cue :strobe-torrents (fn [var-map] (fun/strobe "Strobe Torrents"
                                                                      (show/fixtures-named "torrent")
                                                                      (:level var-map 50)))
                           :held true
                           :priority 100
                           :variables [{:key "level" :min 0 :max 100 :start 50 :name "Level"
                                        :aftertouch true :aftertouch-min 25}
                                       {:key :strobe-lightness :min 0 :max 100 :name "Lightness" :aftertouch true}]))

    (ct/set-cue! (:cue-grid *show*) 7 6
                 (cues/cue :adjust-strobe (fn [_] (fun/adjust-strobe))
                           :variables [{:key :strobe-hue :min 0 :max 360 :name "Hue" :centered true}
                                       {:key :strobe-saturation :min 0 :max 100 :name "Saturatn"}]))

    ;; The upper page of torrent config cues
    (ct/set-cue! (:cue-grid *show*) 0 15
                 (cues/function-cue :torrent-shutter :shutter-open (show/fixtures-named "torrent")))

    (ct/set-cue! (:cue-grid *show*) 6 15
                 (cues/function-cue :t1-focus :focus (show/fixtures-named "torrent-1") :effect-name "Torrent 1 Focus"
                                    :color (create-color :yellow)))
    (ct/set-cue! (:cue-grid *show*) 7 15
                 (cues/function-cue :t2-focus :focus (show/fixtures-named "torrent-2") :effect-name "Torrent 2 Focus"
                                    :color (create-color :yellow)))
    (ct/set-cue! (:cue-grid *show*) 6 14
                 (cues/function-cue :t1-prism :prism-clockwise (show/fixtures-named "torrent-1") :level 100
                                    :effect-name "T1 Prism Spin CW" :color (create-color :orange)))
    (ct/set-cue! (:cue-grid *show*) 7 14
                 (cues/function-cue :t2-prism :prism-clockwise (show/fixtures-named "torrent-2") :level 100
                                    :effect-name "T2 Prism Spin CW" :color (create-color :orange)))
    (ct/set-cue! (:cue-grid *show*) 6 13
                 (cues/function-cue :t1-prism :prism-in (show/fixtures-named "torrent-1")
                                    :effect-name "T1 Prism In" :color (create-color :orange)))
    (ct/set-cue! (:cue-grid *show*) 7 13
                 (cues/function-cue :t2-prism :prism-in (show/fixtures-named "torrent-2")
                                    :effect-name "T2 Prism In" :color (create-color :orange)))
    (ct/set-cue! (:cue-grid *show*) 6 12
                 (cues/function-cue :t1-prism :prism-counterclockwise (show/fixtures-named "torrent-1")
                                    :effect-name "T1 Prism Spin CCW" :color (create-color :orange)))
    (ct/set-cue! (:cue-grid *show*) 7 12
                 (cues/function-cue :t2-prism :prism-counterclockwise (show/fixtures-named "torrent-2")
                                    :effect-name "T2 Prism Spin CCW" :color (create-color :orange)))
    (ct/set-cue! (:cue-grid *show*) 6 11
                 (cues/function-cue :t1-gobo-fixed :gobo-fixed-clockwise (show/fixtures-named "torrent-1")
                                    :effect-name "T1 Fixed Gobos Swap CW" :color (create-color :blue)))
    (ct/set-cue! (:cue-grid *show*) 7 11
                 (cues/function-cue :t2-gobo-fixed :gobo-fixed-clockwise (show/fixtures-named "torrent-2")
                                    :effect-name "T2 Fixed Gobos Swap CW" :color (create-color :blue)))
    (ct/set-cue! (:cue-grid *show*) 6 10
                 (cues/function-cue :t1-gobo-moving :gobo-moving-clockwise (show/fixtures-named "torrent-1")
                                    :effect-name "T1 Moving Gobos Swap CW" :color (create-color :green)))
    (ct/set-cue! (:cue-grid *show*) 7 10
                 (cues/function-cue :t2-gobo-fixed :gobo-moving-clockwise (show/fixtures-named "torrent-2")
                                    :effect-name "T2 Moving Gobos Swap CW" :color (create-color :green)))
    (ct/set-cue! (:cue-grid *show*) 6 9
                 (cues/function-cue :t1-gobo-rotation :gobo-rotation-clockwise (show/fixtures-named "torrent-1")
                                    :effect-name "T1 Spin Gobo CW" :color (create-color :cyan) :level 100))
    (ct/set-cue! (:cue-grid *show*) 7 9
                 (cues/function-cue :t2-gobo-rotation :gobo-rotation-clockwise (show/fixtures-named "torrent-2")
                                    :effect-name "T2 Spin Gobo CW" :color (create-color :cyan) :level 100))
    (ct/set-cue! (:cue-grid *show*) 6 8
                 (cues/function-cue :t1-gobo-rotation :gobo-rotation-counterclockwise (show/fixtures-named "torrent-1")
                                    :effect-name "T1 Spin Gobo CCW" :color (create-color :cyan)))
    (ct/set-cue! (:cue-grid *show*) 7 8
                 (cues/function-cue :t2-gobo-rotation :gobo-rotation-counterclockwise (show/fixtures-named "torrent-2")
                                    :effect-name "T2 Spin Gobo CCW" :color (create-color :cyan)))

    ;; A couple snowball cues
    (ct/set-cue! (:cue-grid *show*) 0 10 (cues/function-cue :sb-pos :beams-fixed (show/fixtures-named "snowball")
                                                             :effect-name "Snowball Fixed"))
    (ct/set-cue! (:cue-grid *show*) 1 10 (cues/function-cue :sb-pos :beams-moving (show/fixtures-named "snowball")
                                                             :effect-name "Snowball Moving"))

    ;; The separate page of specific gobo cues for each Torrent
    (make-torrent-gobo-cues :t1 (show/fixtures-named "torrent-1") 15 8)
    (make-torrent-gobo-cues :t2 (show/fixtures-named "torrent-2") 15 12)

    ;; TODO: Write a function to create direction cues, like function cues? Unless macro solves.
    (ct/set-cue! (:cue-grid *show*) 0 8
                 (cues/cue :torrent-dir (fn [var-map]
                                          (move/direction-effect
                                           "Direction"
                                           (params/build-pan-tilt-param :pan (:pan var-map 0.0)
                                                                         :tilt (:tilt var-map 0.0)
                                                                         :degrees true)
                                           (show/all-fixtures)))
                           :variables [{:key "pan" :name "Pan"
                                        :min -180.0 :max 180.0 :start 0.0 :centered true :resolution 0.5}
                                       {:key "tilt" :name "Tilt"
                                        :min -180.0 :max 180.0 :start 0.0 :centered true :resolution 0.5}]))
    ;; Some compound cues
    (ct/set-cue! (:cue-grid *show*) 8 0
                 (cues/cue :star-swirl (fn [_] (cues/compound-cues-effect
                                                "Star Swirl" *show* [[8 12]
                                                                     [10 9]
                                                                     [6 15 {:level 60}]
                                                                     [6 8 {:level 25}]]))))
    ;; Some cues to show the Hypnotic RGB Laser
    (ct/set-cue! (:cue-grid *show*) 8 3
                 (cues/function-cue :hypnotic-beam :beam-red (show/fixtures-named "hyp-rgb")
                                    :color :red :effect-name "Hypnotic Red"))
    (ct/set-cue! (:cue-grid *show*) 9 3
                 (cues/function-cue :hypnotic-beam :beam-green (show/fixtures-named "hyp-rgb")
                                    :color :green :effect-name "Hypnotic Green"))
    (ct/set-cue! (:cue-grid *show*) 10 3
                 (cues/function-cue :hypnotic-beam :beam-blue (show/fixtures-named "hyp-rgb")
                                    :color :blue :effect-name "Hypnotic Blue"))
    (ct/set-cue! (:cue-grid *show*) 11 3
                 (cues/function-cue :hypnotic-beam :beam-red-green (show/fixtures-named "hyp-rgb")
                                    :color :yellow :effect-name "Hypnotic Red Green"))
    (ct/set-cue! (:cue-grid *show*) 12 3
                 (cues/function-cue :hypnotic-beam :beam-red-blue (show/fixtures-named "hyp-rgb")
                                    :color :purple :effect-name "Hypnotic Red Blue"))
    (ct/set-cue! (:cue-grid *show*) 13 3
                 (cues/function-cue :hypnotic-beam :beam-green-blue (show/fixtures-named "hyp-rgb")
                                    :color :cyan :effect-name "Hypnotic Green Blue"))
    (ct/set-cue! (:cue-grid *show*) 14 3
                 (cues/function-cue :hypnotic-beam :beam-red-green-blue (show/fixtures-named "hyp-rgb")
                                    :color :white :effect-name "Hypnotic Red Green Blue"))
    (ct/set-cue! (:cue-grid *show*) 15 3
                 (cues/function-cue :hypnotic-beam :beam-all-random (show/fixtures-named "hyp-rgb")
                                    :color :white :effect-name "Hypnotic Random"))
    (ct/set-cue! (:cue-grid *show*) 14 4
                 (cues/function-cue :hypnotic-spin :beams-ccw (show/fixtures-named "hyp-rgb")
                                    :color :cyan :effect-name "Hypnotic Rotate CCW" :level 50))
    (ct/set-cue! (:cue-grid *show*) 15 4
                 (cues/function-cue :hypnotic-spin :beams-cw (show/fixtures-named "hyp-rgb")
                                    :color :cyan :effect-name "Hypnotic Rotate Clockwise" :level 50))

    ;; What else?
    ;; TODO: Refine this and make a cue
    #_(show/add-effect! :torrent-focus (afterglow.effects.channel/function-effect
                                        "F" :focus (params/build-oscillated-param (oscillators/sine-bar)
                                                                                  :min 20 :max 200)
                                        (show/fixtures-named "torrent")))
    ))

(defn use-push
  "A trivial reminder of how to connect the Ableton Push to run the
  show. But also sets up the cues, if you haven't yet."
  []
  (make-cues)
  (push/bind-to-show *show*))
