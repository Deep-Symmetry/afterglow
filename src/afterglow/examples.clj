(ns afterglow.examples
  "Show some simple ways to use Afterglow, and hopefully inspire
  exploration." {:author "James Elliott"}
  (:require [afterglow.beyond :as beyond]
            [afterglow.controllers.ableton-push :as push]
            [afterglow.controllers :as ct]
            [afterglow.core :as core]
            [afterglow.effects :as fx]
            [afterglow.effects.color :as color-fx]
            [afterglow.effects.cues :as cues]
            [afterglow.effects.dimmer :refer [dimmer-effect master-set-level]]
            [afterglow.effects.fun :as fun]
            [afterglow.effects.movement :as move]
            [afterglow.effects.oscillators :as oscillators]
            [afterglow.effects.params :as params]
            [afterglow.fixtures.american-dj :as adj]
            [afterglow.fixtures.blizzard :as blizzard]
            [afterglow.fixtures.chauvet :as chauvet]
            [afterglow.rhythm :as rhythm]
            [afterglow.show :as show]
            [afterglow.show-context :refer [*show* with-show set-default-show!]]
            [afterglow.transform :as tf]
            [com.evocomputing.colors :refer [color-name create-color hue adjust-hue]]
            [overtone.osc :as osc]
            [taoensso.timbre :as timbre]))

(defonce ^{:doc "Holds the sample show if it has been created,
  so it can be unregistered if it is being re-created."}
  sample-show
  (atom nil))

(defn use-sample-show
  "Set up a sample show for experimenting with Afterglow. By default
  it will create the show to use universe 1, but if you want to use a
  different universe (for example, a dummy universe on ID 0, because
  your DMX interface isn't handy right now), you can override that by
  supplying a different ID after :universe."
  [& {:keys [universe] :or {universe 1}}]
  ;; Since this class is an entry point for interactive REPL usage,
  ;; make sure a sane logging environment is established.
  (core/init-logging)

  ;; Create, or re-create the show, on the chosen OLA universe, for demonstration
  ;; purposes. Make it the default show so we don't need to wrap everything below
  ;; in a (with-show sample-show ...) binding
  (set-default-show! (swap! sample-show (fn [s]
                                          (when s
                                            (show/unregister-show s)
                                            (with-show s (show/stop!)))
                                          (show/show :universes [universe] :description "Sample Show"))))

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
                       :x (tf/inches 16) :y (tf/inches 12) :z (tf/inches 0)
                       :y-rotation (tf/degrees 0))
  (show/patch-fixture! :blade-2 (blizzard/blade-rgbw) universe 240
                       :x (tf/inches -26.5) :y (tf/inches 48.5) :z (tf/inches -4.75)
                       :y-rotation (tf/degrees 45))
  (show/patch-fixture! :blade-3 (blizzard/blade-rgbw :15-channel :tilt-center 25 :tilt-half-circle -230)
                       universe 255 :x (tf/inches 0) :y (tf/inches 12) :z (tf/inches 0)
                       :y-rotation (tf/degrees 0))
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
  [color & {:keys [include-color-wheels? lights] :or {lights (show/all-fixtures)}}]
  (try
    (let [[c desc] (cond (= (type color) :com.evocomputing.colors/color)
                       [color (color-name color)]
                       (and (params/param? color)
                            (= (params/result-type color) :com.evocomputing.colors/color))
                       [color "variable"]
                       :else
                       [(create-color color) color])]
      (color-fx/color-effect (str "Color: " desc) c lights :include-color-wheels? include-color-wheels?))
    (catch Exception e
      (throw (Exception. (str "Can't figure out how to create color from " color) e)))))

(defn global-dimmer-effect
  "Return an effect that sets all the dimmers in the sample rig.
  Originally this had to be to a static value, but now that dynamic
  parameters exist, it can vary in response to a MIDI mapped show
  variable, an oscillator, or the location of the fixture. You can
  override the default name by passing in a value with :effect-name"
  [level & {:keys [effect-name add-virtual-dimmers?]}]
  (let [htp? (not add-virtual-dimmers?)]
    (dimmer-effect level (show/all-fixtures) :effect-name effect-name :htp? htp?
                   :add-virtual-dimmers? add-virtual-dimmers?)))

(defn fiat-lux
  "Start simple with a cool blue color from all the lights."
  []
  (show/add-effect! :color (global-color-effect "slateblue" :include-color-wheels? true))
  (show/add-effect! :dimmers (global-dimmer-effect 255))
  (show/add-effect! :torrent-shutter
                    (afterglow.effects.channel/function-effect
                     "Torrents Open" :shutter-open 50 (show/fixtures-named "torrent"))))

;; Get a little fancier with a beat-driven fade
;; (show/add-effect! :dimmers (global-dimmer-effect
;;   (oscillators/build-oscillated-param (oscillators/sawtooth))))

;; To actually start the effects above (although only the last one assigned to any
;; given keyword will still be in effect), uncomment or evaluate the next line:
;; (show/start!)

(defn sparkle-test
  "Set up a sedate rainbow fade and then layer on a sparkle effect to test
  effect mixing."
  []
  (let [hue-param (oscillators/build-oscillated-param (oscillators/sawtooth :interval :phrase) :max 360)]
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
  (let [hue-param (oscillators/build-oscillated-param (oscillators/sawtooth :interval :phrase) :max 360)
        sparkle-color-param (params/build-color-param :s 100 :l :sparkle-lightness :h :sparkle-hue)]
    (show/add-effect! :color
                      (global-color-effect
                       (params/build-color-param :s 100 :l 50 :h hue-param)))
    (show/add-effect! :sparkle
                      (fun/sparkle (show/all-fixtures) :color sparkle-color-param
                                   :chance :sparkle-chance :fade-time :sparkle-fade))))

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

(defn osc-demo
  "Early experiments with using OSC to control shows. This should grow
  into a well-defined API, with integration to show variables, cue
  grids, and the like."
  []
  (when (nil? @core/osc-server) (core/start-osc-server 16010))
  (show/set-variable! :y (tf/inches 118))
  (osc/osc-handle @core/osc-server "/aim" (fn [msg]
                                            (let [left (tf/inches -88)
                                                  right (tf/inches 86)
                                                  width (- right left)
                                                  front (tf/inches -21)
                                                  rear (tf/inches 295)
                                                  depth (- rear front)]
                                              (show/set-variable! :x (+ left (* width (first (:args msg)))))
                                              (show/set-variable! :z (+ front (* depth (second (:args msg))))))
                                            #_(timbre/info msg)))
  (osc/osc-handle @core/osc-server "/sparkle" (fn [msg]
                                                (if (pos? (first (:args msg)))
                                                  (show/add-effect! :sparkle (fun/sparkle (show/all-fixtures)
                                                                                          :chance 0.1
                                                                                          :fade-time 100))
                                                  (show/end-effect! :sparkle))))
  #_(osc/osc-listen @core/osc-server (fn [msg] (timbre/info msg)) :debug)
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
  (core/stop-osc-server))

(defn global-color-cue
  "Create a cue-grid entry which establishes a global color effect."
  [color x y & {:keys [include-color-wheels? held]}]
  (let [cue (cues/cue :color (fn [_] (global-color-effect color :include-color-wheels? include-color-wheels?))
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
                          "gobo-moving-blue-mega-hazard" "gobo-moving-turbine"])]))

(defn make-strobe-cue
  "Create a cue which strobes a set of fixtures as long as the cue pad
  is held down, letting the operator adjust the lightness of the
  strobe color by varying the pressure they are applying to the pad on
  controllers which support pressure sensitivity."
  [name fixtures x y]
  (ct/set-cue! (:cue-grid *show*) x y	
               (cues/cue (keyword (str "strobe-" (clojure.string/replace (clojure.string/lower-case name) " " "-")))
                         (fn [var-map] (fun/strobe (str "Strobe " name) fixtures
                                                   (:level var-map 50) (:lightness var-map 100)))
                         :color :purple
                         :held true
                         :priority 100
                         :variables [{:key "level" :min 0 :max 100 :start 100 :name "Level"}
                                     {:key "lightness" :min 0 :max 100 :name "Lightness" :velocity true}])))

(defn x-phase
  "Return a value that ranges from zero for the leftmost fixture in a
  show to 1 for the rightmost, for staggering the phase of an
  oscillator in making a can-can chase."
  [head show]
  (let [dimensions @(:dimensions *show*)]
    (/ (- (:x head) (:min-x dimensions)) (- (:max-x dimensions) (:min-x dimensions)))))

(defn try-laser-cues
  "Create some cues that integrate Pangolin Beyond. Assumes sample
  show has been created, and takes the beyond server to work with as
  an argument."
  [server]
  (beyond/bind-to-show server *show*)
  (let [hue-bar (oscillators/build-oscillated-param  ; Spread a rainbow across a bar of music
                 (oscillators/sawtooth :interval :bar) :max 360)
        hue-param (params/build-color-param :s :rainbow-saturation :l 50 :h hue-bar)]
    (ct/set-cue! (:cue-grid *show*) 0 1
                 (cues/cue :color (fn [_] (fx/scene "Rainbow with laser" (global-color-effect hue-param)
                                                    (beyond/laser-color-effect server hue-param)))
                           :short-name "Rainbow Bar Fade"
                           :variables [{:key :rainbow-saturation :name "Saturatn" :min 0 :max 100 :start 100
                                        :type :integer}])))
  (ct/set-cue! (:cue-grid *show*) 2 7
               (cues/cue :beyond-cue-1-1 (fn [_] (beyond/cue-effect server 1 1))
                         :short-name "Beyond 1 1"))
  (ct/set-cue! (:cue-grid *show*) 3 7
               (cues/cue :beyond-cue-1-2 (fn [_] (beyond/cue-effect server 1 2))
                         :short-name "Beyond 1 2"))
  (afterglow.controllers/set-cue! (:cue-grid *show*) 6 7
                                  (cues/function-cue :snowball-sound :sound-active (show/fixtures-named "snowball")
                                                     :color :cyan)))

(defonce ^{:doc "A step parameter for controlling example chase cues.
  Change it to experiment with other kinds of timing and fades."}
  step-param
  (atom nil))

(defn make-cues
  "Create a bunch of example cues for experimentation."
  []
  (let [hue-bar (oscillators/build-oscillated-param  ; Spread a rainbow across a bar of music
                 (oscillators/sawtooth :interval :bar) :max 360)
        desat-beat (oscillators/build-oscillated-param  ; Desaturate a color as a beat progresses
                    (oscillators/sawtooth :down? true) :max 100)
        hue-gradient (params/build-spatial-param  ; Spread a rainbow across the light grid
                      (show/all-fixtures)
                      (fn [head] (- (:x head) (:min-x @(:dimensions *show*)))) :max 360)
        hue-z-gradient (params/build-spatial-param  ; Spread a rainbow across the light grid front to back
                        (show/all-fixtures)
                        (fn [head] (- (:z head) (:min-z @(:dimensions *show*)))) :max 360)]
    (global-color-cue "red" 0 0 :include-color-wheels? true)
    (global-color-cue "orange" 1 0 :include-color-wheels? true)
    (global-color-cue "yellow" 2 0 :include-color-wheels? true)
    (global-color-cue "green" 3 0 :include-color-wheels? true)
    (global-color-cue "blue" 4 0 :include-color-wheels? true)
    (global-color-cue "purple" 5 0 :include-color-wheels? true)
    (global-color-cue "white" 6 0 :include-color-wheels? true)


    (ct/set-cue! (:cue-grid *show*) 0 1
                 (cues/cue :color (fn [_] (global-color-effect
                                           (params/build-color-param :s :rainbow-saturation :l 50 :h hue-bar)))
                           :short-name "Rainbow Bar Fade"
                           :variables [{:key :rainbow-saturation :name "Saturatn" :min 0 :max 100 :start 100
                                        :type :integer}]))
    (ct/set-cue! (:cue-grid *show*) 1 1
                 (cues/cue :color (fn [_] (global-color-effect
                                           (params/build-color-param :s :rainbow-saturation :l 50 :h hue-gradient)
                                           :include-color-wheels? true))
                           :short-name "Rainbow Grid"
                           :variables [{:key :rainbow-saturation :name "Saturatn" :min 0 :max 100 :start 100
                                        :type :integer}]))
    (ct/set-cue! (:cue-grid *show*) 2 1
                 (cues/cue :color (fn [_] (global-color-effect
                                           (params/build-color-param :s :rainbow-saturation :l 50 :h hue-gradient
                                                                     :adjust-hue hue-bar)))
                           :short-name "Rainbow Grid+Bar"
                           :variables [{:key :rainbow-saturation :name "Saturatn" :min 0 :max 100 :start 100
                                        :type :integer}]))
    (ct/set-cue! (:cue-grid *show*) 3 1  ; Desaturate the rainbow as each beat progresses
                 (cues/cue :color (fn [_] (global-color-effect
                                           (params/build-color-param :s desat-beat :l 50 :h hue-gradient
                                                                     :adjust-hue hue-bar)))
                           :short-name "Rainbow Pulse"))

    (ct/set-cue! (:cue-grid *show*) 5 1
                 (cues/cue :color (fn [_] (global-color-effect
                                           (params/build-color-param :s 100 :l 50 :h hue-z-gradient)
                                           :include-color-wheels? true))
                           :short-name "Z Rainbow Grid"))
    (ct/set-cue! (:cue-grid *show*) 6 1
                 (cues/cue :color (fn [_] (global-color-effect
                                           (params/build-color-param :s 100 :l 50 :h hue-z-gradient
                                                                     :adjust-hue hue-bar)))
                           :short-name "Z Rainbow Grid+Bar"))

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
                           :variables [{:key "chance" :min 0.0 :max 0.4 :start 0.05 :velocity true}
                                       {:key "fade-time" :name "Fade" :min 1 :max 2000 :start 50 :type :integer}]))

    (ct/set-cue! (:cue-grid *show*) 2 7
                 (cues/cue :transform-colors (fn [_] (color-fx/transform-colors (show/all-fixtures)))
                           :priority 1000))


    (ct/set-cue! (:cue-grid *show*) 7 7
                 (cues/function-cue :strobe-all :strobe (show/all-fixtures) :effect-name "Raw Strobe"))


    ;; Dimmer cues to turn on and set brightness of groups of lights
    (ct/set-cue! (:cue-grid *show*) 0 2
                 (cues/cue :dimmers (fn [var-map] (global-dimmer-effect
                                                   (params/bind-keyword-param (:level var-map 255) Number 255)
                                                   :effect-name "All Dimmers"))
                           :variables [{:key "level" :min 0 :max 255 :start 255 :name "Level"}]
                           :color :yellow :end-keys [:torrent-dimmers :blade-dimmers :ws-dimmers
                                                     :puck-dimmers :hex-dimmers :snowball-dimmers]))
    (ct/set-cue! (:cue-grid *show*) 1 2
                 (cues/cue :torrent-dimmers (fn [var-map] (dimmer-effect
                                                           (params/bind-keyword-param (:level var-map 255) Number 255)
                                                           (show/fixtures-named "torrent")
                                                           :effect-name "Torrent Dimmers"))
                           :variables [{:key "level" :min 0 :max 255 :start 255 :name "Level"}]
                           :color :orange :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 2 2
                 (cues/cue :blade-dimmers (fn [var-map] (dimmer-effect
                                                         (params/bind-keyword-param (:level var-map 255) Number 255)
                                                         (show/fixtures-named "blade")
                                                         :effect-name "Blade Dimmers"))
                           :variables [{:key "level" :min 0 :max 255 :start 255 :name "Level"}]
                           :color :orange :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 3 2
                 (cues/cue :ws-dimmers (fn [var-map] (dimmer-effect
                                                      (params/bind-keyword-param (:level var-map 255) Number 255)
                                                      (show/fixtures-named "ws")
                                                      :effect-name "Weather System Dimmers"))
                           :variables [{:key "level" :min 0 :max 255 :start 255 :name "Level"}]
                           :color :orange :end-keys [:dimmers]))

    (ct/set-cue! (:cue-grid *show*) 4 2
                 (cues/cue :hex-dimmers (fn [var-map] (dimmer-effect
                                                       (params/bind-keyword-param (:level var-map 255) Number 255)
                                                       (show/fixtures-named "hex")
                                                       :effect-name "Hex Dimmers"))
                           :variables [{:key "level" :min 0 :max 255 :start 255 :name "Level"}]
                           :color :orange :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 5 2
                 (cues/cue :puck-dimmers (fn [var-map] (dimmer-effect
                                                        (params/bind-keyword-param (:level var-map 255) Number 255)
                                                        (show/fixtures-named "puck")
                                                        :effect-name "Puck Dimmers"))
                           :variables [{:key "level" :min 0 :max 255 :start 255 :name "Level"}]
                           :color :orange :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 6 2
                 (cues/cue :snowball-dimmers (fn [var-map] (dimmer-effect
                                                            (params/bind-keyword-param (:level var-map 255) Number 255)
                                                            (show/fixtures-named "snowball")
                                                            :effect-name "Snowball Dimmers"))
                           :variables [{:key "level" :min 0 :max 255 :start 255 :name "Level"}]
                           :color :orange :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 7 2
                 (cues/cue :torrent-1-dimmer (fn [var-map] (dimmer-effect
                                                            (params/bind-keyword-param (:level var-map 255) Number 255)
                                                            (show/fixtures-named "torrent-1")
                                                            :effect-name "Torrent 1 Dimmer"))
                           :variables [{:key "level" :min 0 :max 255 :start 255 :name "Level"}]
                           :color :orange :end-keys [:dimmers :torrent-dimmers]))

    ;; Dimmer oscillator cues: Sawtooth down each beat
    (ct/set-cue! (:cue-grid *show*) 0 3
                 (cues/cue :dimmers (fn [_] (global-dimmer-effect
                                             (oscillators/build-oscillated-param
                                              (oscillators/sawtooth :down? true))
                                             :effect-name "All Saw Down Beat"))
                           :color :yellow :end-keys [:torrent-dimmers :blade-dimmers :ws-dimmers
                                                     :puck-dimmers :hex-dimmers :snowball-dimmers]))
    (ct/set-cue! (:cue-grid *show*) 1 3
                 (cues/cue :torrent-dimmers
                           (fn [_] (dimmer-effect
                                    (oscillators/build-oscillated-param (oscillators/sawtooth :down? true))
                                    (show/fixtures-named "torrent") :effect-name "Torrent Saw Down Beat"))
                           :color :orange :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 2 3
                 (cues/cue :blade-dimmers
                           (fn [_] (dimmer-effect
                                    (oscillators/build-oscillated-param (oscillators/sawtooth :down? true))
                                    (show/fixtures-named "blade") :effect-name "Blade Saw Down Beat"))
                           :color :orange :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 3 3
                 (cues/cue :ws-dimmers
                           (fn [_] (dimmer-effect
                                    (oscillators/build-oscillated-param (oscillators/sawtooth :down? true))
                                    (show/fixtures-named "ws") :effect-name "WS Saw Down Beat"))
                           :color :orange :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 4 3
                 (cues/cue :hex-dimmers
                           (fn [_] (dimmer-effect
                                    (oscillators/build-oscillated-param (oscillators/sawtooth :down? true))
                                    (show/fixtures-named "hex") :effect-name "Hex Saw Down Beat"))
                           :color :orange :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 5 3
                 (cues/cue :puck-dimmers
                           (fn [_] (dimmer-effect
                                    (oscillators/build-oscillated-param (oscillators/sawtooth :down? true))
                                    (show/fixtures-named "puck") :effect-name "Puck Saw Down Beat"))
                           :color :orange :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 6 3
                 (cues/cue :snowball-dimmers
                           (fn [_] (dimmer-effect
                                    (oscillators/build-oscillated-param (oscillators/sawtooth :down? true))
                                    (show/fixtures-named "snowball") :effect-name "Snowball Saw Down Beat"))
                           :color :orange :end-keys [:dimmers]))

    ;; Dimmer oscillator cues: Sawtooth up over 2 beat
    (ct/set-cue! (:cue-grid *show*) 0 4
                 (cues/cue :dimmers (fn [_] (global-dimmer-effect
                                             (oscillators/build-oscillated-param
                                              (oscillators/sawtooth :interval-ratio 2))
                                             :effect-name "All Saw Up 2 Beat"))
                           :color :yellow :end-keys [:torrent-dimmers :blade-dimmers :ws-dimmers
                                                     :puck-dimmers :hex-dimmers :snowball-dimmers]))
    (ct/set-cue! (:cue-grid *show*) 1 4
                 (cues/cue :torrent-dimmers
                           (fn [_] (dimmer-effect
                                    (oscillators/build-oscillated-param (oscillators/sawtooth :interval-ratio 2))
                                    (show/fixtures-named "torrent") :effect-name "Torrent Saw Up 2 Beat"))
                           :color :orange :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 2 4
                 (cues/cue :blade-dimmers
                           (fn [_] (dimmer-effect
                                    (oscillators/build-oscillated-param (oscillators/sawtooth :interval-ratio 2))
                                    (show/fixtures-named "blade") :effect-name "Blade Saw Up 2 Beat"))
                           :color :orange :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 3 4
                 (cues/cue :ws-dimmers
                           (fn [_] (dimmer-effect
                                    (oscillators/build-oscillated-param (oscillators/sawtooth :interval-ratio 2))
                                    (show/fixtures-named "ws") :effect-name "WS Saw Up 2 Beat"))
                           :color :orange :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 4 4
                 (cues/cue :hex-dimmers
                           (fn [_] (dimmer-effect
                                    (oscillators/build-oscillated-param (oscillators/sawtooth :interval-ratio 2))
                                    (show/fixtures-named "hex") :effect-name "Hex Saw Up 2 Beat"))
                           :color :orange :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 5 4
                 (cues/cue :puck-dimmers
                           (fn [_] (dimmer-effect
                                    (oscillators/build-oscillated-param (oscillators/sawtooth :interval-ratio 2))
                                    (show/fixtures-named "puck") :effect-name "Puck Saw Up 2 Beat"))
                           :color :orange :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 6 4
                 (cues/cue :snowball-dimmers
                           (fn [_] (dimmer-effect
                                    (oscillators/build-oscillated-param (oscillators/sawtooth :interval-ratio 2))
                                    (show/fixtures-named "snowball") :effect-name "Snowball Saw Up 2 Beat"))
                           :color :orange :end-keys [:dimmers]))

    ;; Dimmer oscillator cues: Sine over a bar
    (ct/set-cue! (:cue-grid *show*) 0 5
                 (cues/cue :dimmers (fn [_] (global-dimmer-effect
                                             (oscillators/build-oscillated-param (oscillators/sine :interval :bar)
                                                                                 :min 1)
                                             :effect-name "All Sine Bar"))
                           :color :cyan :end-keys [:torrent-dimmers :blade-dimmers :ws-dimmers
                                                   :puck-dimmers :hex-dimmers :snowball-dimmers]))
    (ct/set-cue! (:cue-grid *show*) 1 5
                 (cues/cue :torrent-dimmers
                           (fn [_] (dimmer-effect
                                    (oscillators/build-oscillated-param (oscillators/sine :interval :bar) :min 1)
                                    (show/fixtures-named "torrent") :effect-name "Torrent Sine Bar"))
                           :color :blue :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 2 5
                 (cues/cue :blade-dimmers
                           (fn [_] (dimmer-effect
                                    (oscillators/build-oscillated-param (oscillators/sine :interval :bar) :min 1)
                                    (show/fixtures-named "blade") :effect-name "Blade Sine Bar"))
                           :color :blue :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 3 5
                 (cues/cue :ws-dimmers
                           (fn [_] (dimmer-effect
                                    (oscillators/build-oscillated-param (oscillators/sine :interval :bar) :min 1)
                                    (show/fixtures-named "ws") :effect-name "WS Sine Bar"))
                           :color :blue :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 4 5
                 (cues/cue :hex-dimmers
                           (fn [_] (dimmer-effect
                                    (oscillators/build-oscillated-param (oscillators/sine :interval :bar) :min 1)
                                    (show/fixtures-named "hex") :effect-name "Hex Sine Bar"))
                           :color :blue :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 5 5
                 (cues/cue :puck-dimmers
                           (fn [_] (dimmer-effect
                                    (oscillators/build-oscillated-param (oscillators/sine :interval :bar) :min 1)
                                    (show/fixtures-named "puck") :effect-name "Puck Sine Bar"))
                           :color :blue :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 6 5
                 (cues/cue :snowball-dimmers
                           (fn [_] (dimmer-effect
                                    (oscillators/build-oscillated-param (oscillators/sine :interval :bar) :min 1)
                                    (show/fixtures-named "snowball") :effect-name "Snowball Sine Bar"))
                           :color :blue :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 7 5
                 (cues/cue :dimmers
                           (fn [_] (dimmer-effect
                                    (oscillators/build-oscillated-param (oscillators/triangle :interval :bar) :min 1)
                                    (show/all-fixtures) :effect-name "All Triangle Bar"))
                           :color :red :end-keys [:torrent-dimmers :blade-dimmers :ws-dimmers
                                                  :puck-dimmers :hex-dimmers :snowball-dimmers]))

    ;; Strobe cues
    (make-strobe-cue "All" (show/all-fixtures) 0 6)
    (make-strobe-cue "Torrents" (show/fixtures-named "torrent") 1 6)
    (make-strobe-cue "Blades" (show/fixtures-named "blade") 2 6)
    (make-strobe-cue "Weather Systems" (show/fixtures-named "ws") 3 6)
    (make-strobe-cue "Hexes" (show/fixtures-named "hex") 4 6)
    (make-strobe-cue "Pucks" (show/fixtures-named "puck") 5 6)

    (ct/set-cue! (:cue-grid *show*) 7 6
                 (cues/cue :adjust-strobe (fn [_] (fun/adjust-strobe))
                           :color :purple
                           :variables [{:key :strobe-hue :min 0 :max 360 :name "Hue" :centered true}
                                       {:key :strobe-saturation :min 0 :max 100 :name "Saturatn"}]))

    ;; The upper page of torrent config cues
    (ct/set-cue! (:cue-grid *show*) 0 15
                 (cues/function-cue :torrent-shutter :shutter-open (show/fixtures-named "torrent")))
    (ct/set-cue! (:cue-grid *show*) 1 15
                 (cues/function-cue :torrent-reset :motor-reset (show/fixtures-named "torrent")
                                    :color (create-color :red) :held true))

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
                 (cues/function-cue :t2-gobo-moving :gobo-moving-clockwise (show/fixtures-named "torrent-2")
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

    ;; Some basic moving head chases
    (let [triangle-phrase (oscillators/build-oscillated-param ; Move back and forth over a phrase
                           (oscillators/triangle :interval :phrase) :min -90 :max 90)
          staggered-triangle-bar (params/build-spatial-param ; Bounce over a bar, staggered across grid x
                                  (show/all-fixtures)
                                  (fn [head]
                                    (oscillators/build-oscillated-param
                                     (oscillators/triangle :interval :bar :phase (x-phase head *show*))
                                     :min -90 :max 0)))
          can-can-dir (params/build-direction-param-from-pan-tilt :pan triangle-phrase :tilt staggered-triangle-bar)
          can-can-p-t (params/build-pan-tilt-param :pan triangle-phrase :tilt staggered-triangle-bar)]
      (ct/set-cue! (:cue-grid *show*) 0 9
                   (cues/cue :movement (fn [var-map]
                                         (move/direction-effect "Can Can" can-can-dir (show/all-fixtures)))))
      (ct/set-cue! (:cue-grid *show*) 1 9
                   (cues/cue :movement (fn [var-map]
                                         (move/pan-tilt-effect "P/T Can Can" can-can-p-t (show/all-fixtures))))))
    
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
                                           "Pan/Tilt"
                                           (params/build-direction-param-from-pan-tilt :pan (:pan var-map 0.0)
                                                                                       :tilt (:tilt var-map 0.0)
                                                                                       :degrees true)
                                           (show/all-fixtures)))
                           :variables [{:key "pan" :name "Pan"
                                        :min -180.0 :max 180.0 :start 0.0 :centered true :resolution 0.5}
                                       {:key "tilt" :name "Tilt"
                                        :min -180.0 :max 180.0 :start 0.0 :centered true :resolution 0.5}]))
    (ct/set-cue! (:cue-grid *show*) 1 8
                 (cues/cue :torrent-dir (fn [var-map]
                                          (move/aim-effect
                                           "Aim"
                                           (params/build-aim-param :x (:x var-map 0.0)
                                                                   :y (:y var-map 0.0)
                                                                   :z (:z var-map 1.0))
                                           (show/all-fixtures)))
                           :variables [{:key "x" :name "X"
                                        :min -20.0 :max 20.0 :start 0.0 :centered true :resolution 0.05}
                                       {:key "z" :name "Z"
                                        :min -20.0 :max 20.0 :start 0.0 :centered true :resolution 0.05}
                                       {:key "y" :name "Y"
                                        :min 0.0 :max 20.0 :start 0.0 :centered false :resolution 0.05}]))
    (ct/set-cue! (:cue-grid *show*) 3 8
                 (cues/function-cue :blade-speed :movement-speed (show/fixtures-named "blade")
                                    :color :purple :effect-name "Slow Blades"))

    ;; Some fades
    (ct/set-cue! (:cue-grid *show*) 0 12
                 (cues/cue :color-fade (fn [var-map]
                                         (fx/fade "Color Fade"
                                                  (global-color-effect :red :include-color-wheels? true)
                                                  (global-color-effect :green :include-color-wheels? true)
                                                  (params/bind-keyword-param (:phase var-map 0) Number 0)))
                           :variables [{:key "phase" :min 0.0 :max 1.0 :start 0.0 :name "Fade"}]
                           :color :yellow))

    (ct/set-cue!
     (:cue-grid *show*) 1 12
     (cues/cue :fade-test (fn [var-map]
                            (fx/fade "Fade Test"
                                     (fx/blank)
                                     (global-color-effect :blue :include-color-wheels? true)
                                     (params/bind-keyword-param (:phase var-map 0) Number 0)))
               :variables [{:key "phase" :min 0.0 :max 1.0 :start 0.0 :name "Fade"}]
               :color :cyan))

    (ct/set-cue!
     (:cue-grid *show*) 2 12
     (cues/cue :fade-test-2 (fn [var-map]
                              (fx/fade "Fade Test 2"
                                       (move/direction-effect
                                        "p/t" (params/build-direction-param-from-pan-tilt :pan 0 :tilt 0)
                                        (show/fixtures-named "torrent"))
                                       (move/direction-effect
                                        "p/t" (params/build-direction-param-from-pan-tilt :pan 0 :tilt 0)
                                        (show/fixtures-named "blade"))
                                       (params/bind-keyword-param (:phase var-map 0) Number 0)))
               :variables [{:key "phase" :min 0.0 :max 1.0 :start 0.0 :name "Fade"}]
               :color :red))

    (ct/set-cue!
     (:cue-grid *show*) 3 12
     (cues/cue :fade-test-3 (fn [var-map]
                              (fx/fade "Fade Test P/T"
                                       (move/pan-tilt-effect
                                        "p/t" (params/build-pan-tilt-param :pan 0 :tilt 0)
                                        (show/fixtures-named "torrent"))
                                       (move/pan-tilt-effect
                                        "p/t" (params/build-pan-tilt-param :pan 0 :tilt 0)
                                        (show/fixtures-named "blade"))
                                       (params/bind-keyword-param (:phase var-map 0) Number 0)))
               :variables [{:key "phase" :min 0.0 :max 1.0 :start 0.0 :name "Fade"}]
               :color :orange))

    ;; Some chases
    (ct/set-cue!
     (:cue-grid *show*) 0 13
     (cues/cue :chase (fn [var-map]
                        (fx/chase "Chase Test"
                                  [(global-color-effect :red :include-color-wheels? true)
                                   (global-color-effect :green :lights (show/fixtures-named "hex"))
                                   (global-color-effect :blue :include-color-wheels? true)]
                                  (params/bind-keyword-param (:position var-map 0) Number 0)
                                  :beyond :bounce)
                        )
               :variables [{:key "position" :min -0.5 :max 10.5 :start 0.0 :name "Position"}]
               :color :purple))

    ;; Set up an initial value for our step parameter
    (reset! step-param (params/build-step-param :fade-fraction 0.3 :fade-curve :sine))

    (ct/set-cue!
     (:cue-grid *show*) 1 13
     (cues/cue :chase (fn [var-map]
                        (fx/chase "Chase Test 2"
                                  [(global-color-effect :red :lights (show/fixtures-named "hex"))
                                   (global-color-effect :green :lights (show/fixtures-named "blade"))
                                   (global-color-effect :blue :lights (show/fixtures-named "hex"))
                                   (global-color-effect :white :lights (show/all-fixtures))]
                                  @step-param :beyond :loop))
               :color :magenta))

    ;; Some compound cues
    (ct/set-cue! (:cue-grid *show*) 8 0
                 (cues/cue :star-swirl (fn [_] (cues/compound-cues-effect
                                                "Star Swirl" *show* [[8 12]
                                                                     [10 9]
                                                                     [6 15 {:level 60}]
                                                                     [6 8 {:level 25}]]))))
    ;; Some color cycle chases
    (ct/set-cue! (:cue-grid *show*) 8 1
                 (cues/cue :color (fn [_] (fun/iris-out-color-cycle-chase (show/all-fixtures)))))
    (ct/set-cue! (:cue-grid *show*) 9 1
                 (cues/cue :color (fn [_] (fun/wipe-right-color-cycle-chase
                                           (show/all-fixtures) :transition-phase-function rhythm/snapshot-bar-phase))))
    (ct/set-cue! (:cue-grid *show*) 10 1
                 (cues/cue :color (fn [_] (fun/wipe-right-color-cycle-chase
                                           (show/all-fixtures)
                                           :color-index-function rhythm/snapshot-beat-within-phrase
                                           :transition-phase-function rhythm/snapshot-beat-phase
                                           :effect-name "Wipe Right Beat"))))

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
                                        "F" :focus (oscillators/build-oscillated-param (oscillators/sine :interval :bar)
                                                                                  :min 20 :max 200)
                                        (show/fixtures-named "torrent")))))

(defn use-push
  "A trivial reminder of how to connect the Ableton Push to run the
  show. But also sets up the cues, if you haven't yet."
  [& {:keys [device-filter refresh-interval display-name]
           :or {device-filter "User Port"
                refresh-interval (/ 1000 15)
                display-name "Ableton Push"}}]
  (make-cues)
  (push/auto-bind *show* :device-filter device-filter :refresh-interval refresh-interval :display-name display-name))
