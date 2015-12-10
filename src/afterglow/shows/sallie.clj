(ns afterglow.shows.sallie
  "Cues for Sallie's birthday/housewarming party. Useful as an example
  of how an actual small show was put together early in Afterglow's
  development, and also as a source of effects that may want to make
  there way into a more central place."
  {:author "James Elliott"}
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
            [afterglow.effects.show-variable :as var-fx]
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

(defonce ^{:doc "Holds the show if it has been created,
  so it can be unregistered if it is being re-created."}
  sallie-show
  (atom nil))

(defonce ^{:doc "Allows commands to be sent to the instance of
  Pangolin Beyond running alongside this light show, in order to
  affect laser cues."}
  laser-show
  (afterglow.beyond/beyond-server "192.168.212.128" 16062))

(defonce ^{:doc "Allows effects to set variables in the running show."}
  var-binder
  (atom nil))

(defn use-sallie-show
  "Set up the show for Sallie's party. By default it will create the
  show to use universe 1, but if you want to use a different
  universe (for example, a dummy universe on ID 0, because your DMX
  interface isn't handy right now), you can override that by supplying
  a different ID after :universe."
  [& {:keys [universe] :or {universe 1}}]
  ;; Since this class is an entry point for interactive REPL usage,
  ;; make sure a sane logging environment is established.
  (core/init-logging)

  ;; Create, or re-create the show, on the chosen OLA universe, for demonstration
  ;; purposes. Make it the default show so we don't need to wrap everything below
  ;; in a (with-show sallie-show ...) binding
  (set-default-show! (swap! sallie-show (fn [s]
                                          (when s
                                            (show/unregister-show s)
                                            (with-show s (show/stop!)))
                                          (show/show :universes [universe] :description "Sallie Show"))))

  (show/patch-fixture! :hex-1 (chauvet/slimpar-hex3-irc) universe 129 :x (tf/inches 14) :y (tf/inches 44)
                       :z (tf/inches -4.75)
                       :x-rotation (tf/degrees 90))
  (show/patch-fixture! :hex-2 (chauvet/slimpar-hex3-irc) universe 145 :x (tf/inches -14) :y (tf/inches 44)
                       :z (tf/inches -4.75)
                       :x-rotation (tf/degrees 90))
  (show/patch-fixture! :ws-1 (blizzard/weather-system) universe 161
                       :x (tf/inches 55) :y (tf/inches 71) :z (tf/inches 261) :y-rotation (tf/degrees 225))
  (show/patch-fixture! :snowball (blizzard/snowball) universe 33 :x (tf/inches -76) :y (tf/inches 32)
                       :z (tf/inches 164.5))
  (beyond/bind-to-show laser-show *show*)
  (reset! var-binder (var-fx/create-for-show *show*))
  '*show*)


(defn global-color-effect
  "Make a color effect which affects all lights in the Sallie show.
  If the show variable `:also-color-laser` has a value other than `0`,
  the color will be sent to Beyond to affect laser cues as well. Can
  include only a specific set of lights by passing them with
  `:lights`"
  [color & {:keys [include-color-wheels? lights] :or {lights (show/all-fixtures)}}]
  (try
    (let [[c desc] (cond (= (type color) :com.evocomputing.colors/color)
                       [color (color-name color)]
                       (and (satisfies? params/IParam color)
                            (= (params/result-type color) :com.evocomputing.colors/color))
                       [color "variable"]
                       :else
                       [(create-color color) color])]
      (fx/scene (str "Color: " desc)
                (color-fx/color-effect (str "Color: " desc) c lights :include-color-wheels? include-color-wheels?)
                (fx/conditional-effect "Color Laser?" (params/build-variable-param :also-color-laser)
                                       (beyond/laser-color-effect laser-show c))))
    (catch Exception e
      (throw (Exception. (str "Can't figure out how to create color from " color) e)))))

(defn global-dimmer-effect
  "Return an effect that sets all the dimmers in the sallie rig.
  Originally this had to be to a static value, but now that dynamic
  parameters exist, it can vary in response to a MIDI mapped show
  variable, an oscillator, or the location of the fixture. You can
  override the default name by passing in a value with :effect-name"
  [level & {:keys [effect-name]}]
  (dimmer-effect level (show/all-fixtures) :effect-name effect-name))

(defn global-color-cue
  "Create a cue-grid entry which establishes a global color effect."
  [color x y & {:keys [include-color-wheels? held]}]
  (let [cue (cues/cue :color (fn [_] (global-color-effect color :include-color-wheels? include-color-wheels?))
                      :held held
                      :color (create-color color))]
    (ct/set-cue! (:cue-grid *show*) x y cue)))

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
  "Create some cues that integrate Pangolin Beyond. Assumes sallie
  show has been created, and takes the beyond server to work with as
  an argument."
  [server]
  (ct/set-cue! (:cue-grid *show*) 2 7
               (cues/cue :beyond-cue-1-1 (fn [_] (beyond/cue-effect server 1 1))
                         :short-name "Beyond 1 1"))
  (ct/set-cue! (:cue-grid *show*) 3 7
               (cues/cue :beyond-cue-1-2 (fn [_] (beyond/cue-effect server 1 2))
                         :short-name "Beyond 1 2")))


(defn make-cues
  "Create the cues for the Sallie show."
  []
  {:pre [(some? *show*)]}
  (let [hue-bar (oscillators/build-oscillated-param  ; Spread a rainbow across a bar of music
                 (oscillators/sawtooth-bar) :max 360)
        desat-beat (oscillators/build-oscillated-param  ; Desaturate a color as a beat progresses
                    (oscillators/sawtooth-beat :down? true) :max 100)
        hue-gradient (params/build-spatial-param  ; Spread a rainbow across the light grid
                      (show/all-fixtures)
                      (fn [head] (- (:x head) (:min-x @(:dimensions *show*)))) :max 360)]
    (global-color-cue "red" 0 0 :include-color-wheels? true)
    (global-color-cue "orange" 1 0 :include-color-wheels? true)
    (global-color-cue "yellow" 2 0 :include-color-wheels? true)
    (global-color-cue "green" 3 0 :include-color-wheels? true)
    (global-color-cue "blue" 4 0 :include-color-wheels? true)
    (global-color-cue "purple" 5 0 :include-color-wheels? true)
    (global-color-cue "violet" 6 0 :include-color-wheels? true)
    (global-color-cue "white" 7 0 :include-color-wheels? true)
    (global-color-cue "pink" 0 1 :include-color-wheels? true)
    (global-color-cue "salmon" 1 1 :include-color-wheels? true)
    (global-color-cue "teal" 2 1 :include-color-wheels? true)
    (global-color-cue "cyan" 3 1 :include-color-wheels? true)

    (ct/set-cue! (:cue-grid *show*) 4 1
                 (cues/cue :color (fn [_] (global-color-effect
                                           (params/build-color-param :s :rainbow-saturation :l 50 :h hue-bar)))
                           :short-name "Rainbow Bar Fade"
                           :variables [{:key :rainbow-saturation :name "Saturatn" :min 0 :max 100 :start 100
                                        :type :integer}]))
    (ct/set-cue! (:cue-grid *show*) 5 1
                 (cues/cue :color (fn [_] (global-color-effect
                                           (params/build-color-param :s :rainbow-saturation :l 50 :h hue-gradient)
                                           :include-color-wheels? true))
                           :short-name "Rainbow Grid"
                           :variables [{:key :rainbow-saturation :name "Saturatn" :min 0 :max 100 :start 100
                                        :type :integer}]))
    (ct/set-cue! (:cue-grid *show*) 6 1
                 (cues/cue :color (fn [_] (global-color-effect
                                           (params/build-color-param :s :rainbow-saturation :l 50 :h hue-gradient
                                                                     :adjust-hue hue-bar)))
                           :short-name "Rainbow Grid+Bar"
                           :variables [{:key :rainbow-saturation :name "Saturatn" :min 0 :max 100 :start 100
                                        :type :integer}]))
    ;; TODO: Make this a high-priroity cue which desaturates whatever else is going on.
    (ct/set-cue! (:cue-grid *show*) 7 1  ; Desaturate the rainbow as each beat progresses
                 (cues/cue :color (fn [_] (global-color-effect
                                           (params/build-color-param :s desat-beat :l 50 :h hue-gradient
                                                                     :adjust-hue hue-bar)))
                           :short-name "Rainbow Pulse"))

    ;; A cue which causes any colors to be desaturated over the course of each beat.
    (ct/set-cue! (:cue-grid *show*) 2 7
                 (cues/cue :transform-colors (fn [_] (color-fx/transform-colors (show/all-fixtures)
                                                                                :beyond-server laser-show))
                           :priority 1000))

    (ct/set-cue! (:cue-grid *show*) 3 7
                 (cues/cue :color (fn [var-map]
                                    (let [hue (fun/random-beat-number-param :max 360 :min-change 60)]
                                      (global-color-effect
                                       (params/build-color-param :h hue :l 50 :s (:saturation var-map 100)))))
                           :short-name "Random Beat Color"))

    (ct/set-cue! (:cue-grid *show*) 0 7
                 (cues/cue :sparkle (fn [var-map] (fun/sparkle (show/all-fixtures)
                                                               :chance (:chance var-map 0.05)
                                                               :fade-time (:fade-time var-map 50)))
                           :held true
                           :priority 100
                           :variables [{:key "chance" :min 0.0 :max 0.4 :start 0.05 :velocity true}
                                       {:key "fade-time" :name "Fade" :min 1 :max 2000 :start 50 :type :integer}]))

    (ct/set-cue! (:cue-grid *show*) 5 6
                 (cues/function-cue :strobe-all :strobe (show/all-fixtures) :effect-name "Raw Strobe"))

    (ct/set-cue! (:cue-grid *show*) 5 7
                 (cues/cue :color-laser (fn [_] (var-fx/variable-effect @var-binder :also-color-laser 1))
                           :color :red :short-name "Also color laser"))

    (ct/set-cue! (:cue-grid *show*) 6 7
                 (cues/function-cue :snowball-sound :sound-active (show/fixtures-named "snowball")
                                    :color :cyan))

    (ct/set-cue! (:cue-grid *show*) 7 7
                 (cues/function-cue :hex-uv :uv (show/all-fixtures)
                                    :level 100 :color :blue :short-name "All UV"))
    
    ;; Dimmer cues to turn on and set brightness of groups of lights
    (ct/set-cue! (:cue-grid *show*) 0 2
                 (cues/cue :dimmers (fn [var-map] (global-dimmer-effect
                                                   (params/bind-keyword-param (:level var-map 255) Number 255)
                                                   :effect-name "All Dimmers"))
                           :variables [{:key "level" :min 0 :max 255 :start 255 :name "Level"}]
                           :color :yellow :end-keys [:torrent-dimmers :blade-dimmers :ws-dimmers
                                                     :puck-dimmers :hex-dimmers :snowball-dimmers]))
    (ct/set-cue! (:cue-grid *show*) 1 2
                 (cues/cue :ws-dimmers (fn [var-map] (dimmer-effect
                                                      (params/bind-keyword-param (:level var-map 255) Number 255)
                                                      (show/fixtures-named "ws")
                                                      :effect-name "Weather System Dimmers"))
                           :variables [{:key "level" :min 0 :max 255 :start 255 :name "Level"}]
                           :color :orange :end-keys [:dimmers]))

    (ct/set-cue! (:cue-grid *show*) 2 2
                 (cues/cue :hex-dimmers (fn [var-map] (dimmer-effect
                                                       (params/bind-keyword-param (:level var-map 255) Number 255)
                                                       (show/fixtures-named "hex")
                                                       :effect-name "Hex Dimmers"))
                           :variables [{:key "level" :min 0 :max 255 :start 255 :name "Level"}]
                           :color :orange :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 3 2
                 (cues/cue :snowball-dimmers (fn [var-map] (dimmer-effect
                                                            (params/bind-keyword-param (:level var-map 255) Number 255)
                                                            (show/fixtures-named "snowball")
                                                            :effect-name "Snowball Dimmers"))
                           :variables [{:key "level" :min 0 :max 255 :start 255 :name "Level"}]
                           :color :orange :end-keys [:dimmers]))

    ;; Dimmer oscillator cues: Sawtooth down each beat
    (ct/set-cue! (:cue-grid *show*) 0 3
                 (cues/cue :dimmers (fn [_] (global-dimmer-effect
                                             (oscillators/build-oscillated-param
                                              (oscillators/sawtooth-beat :down? true))
                                             :effect-name "All Saw Down Beat"))
                           :color :yellow :end-keys [:ws-dimmers :hex-dimmers :snowball-dimmers]))
    (ct/set-cue! (:cue-grid *show*) 1 3
                 (cues/cue :ws-dimmers
                           (fn [_] (dimmer-effect
                                    (oscillators/build-oscillated-param (oscillators/sawtooth-beat :down? true))
                                    (show/fixtures-named "ws") :effect-name "WS Saw Down Beat"))
                           :color :orange :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 2 3
                 (cues/cue :hex-dimmers
                           (fn [_] (dimmer-effect
                                    (oscillators/build-oscillated-param (oscillators/sawtooth-beat :down? true))
                                    (show/fixtures-named "hex") :effect-name "Hex Saw Down Beat"))
                           :color :orange :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 3 3
                 (cues/cue :snowball-dimmers
                           (fn [_] (dimmer-effect
                                    (oscillators/build-oscillated-param (oscillators/sawtooth-beat :down? true))
                                    (show/fixtures-named "snowball") :effect-name "Snowball Saw Down Beat"))
                           :color :orange :end-keys [:dimmers]))

    ;; Dimmer oscillator cues: Sawtooth up each beat
    (ct/set-cue! (:cue-grid *show*) 4 3
                 (cues/cue :dimmers (fn [_] (global-dimmer-effect
                                             (oscillators/build-oscillated-param (oscillators/sawtooth-beat))
                                             :effect-name "All Saw Up Beat"))
                           :color :yellow :end-keys [:ws-dimmers :hex-dimmers :snowball-dimmers]))
    (ct/set-cue! (:cue-grid *show*) 5 3
                 (cues/cue :ws-dimmers
                           (fn [_] (dimmer-effect
                                    (oscillators/build-oscillated-param (oscillators/sawtooth-beat))
                                    (show/fixtures-named "ws") :effect-name "WS Saw Up Beat"))
                           :color :orange :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 6 3
                 (cues/cue :hex-dimmers
                           (fn [_] (dimmer-effect
                                    (oscillators/build-oscillated-param (oscillators/sawtooth-beat))
                                    (show/fixtures-named "hex") :effect-name "Hex Saw Up Beat"))
                           :color :orange :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 7 3
                 (cues/cue :snowball-dimmers
                           (fn [_] (dimmer-effect
                                    (oscillators/build-oscillated-param (oscillators/sawtooth-beat))
                                    (show/fixtures-named "snowball") :effect-name "Snowball Saw Up Beat"))
                           :color :orange :end-keys [:dimmers]))

    ;; Dimmer oscillator cues: Sawtooth down over 2 beat
    (ct/set-cue! (:cue-grid *show*) 0 4
                 (cues/cue :dimmers (fn [_] (global-dimmer-effect
                                             (oscillators/build-oscillated-param
                                              (oscillators/sawtooth-beat :beat-ratio 2 :down? true))
                                             :effect-name "All Saw Down 2 Beat"))
                           :color :yellow :end-keys [:ws-dimmers :hex-dimmers :snowball-dimmers]))
    (ct/set-cue! (:cue-grid *show*) 1 4
                 (cues/cue :ws-dimmers
                           (fn [_] (dimmer-effect
                                    (oscillators/build-oscillated-param (oscillators/sawtooth-beat :beat-ratio 2
                                                                                              :down? true))
                                    (show/fixtures-named "ws") :effect-name "WS Saw Down 2 Beat"))
                           :color :orange :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 2 4
                 (cues/cue :hex-dimmers
                           (fn [_] (dimmer-effect
                                    (oscillators/build-oscillated-param (oscillators/sawtooth-beat :beat-ratio 2
                                                                                              :down? true))
                                    (show/fixtures-named "hex") :effect-name "Hex Saw Down 2 Beat"))
                           :color :orange :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 3 4
                 (cues/cue :snowball-dimmers
                           (fn [_] (dimmer-effect
                                    (oscillators/build-oscillated-param (oscillators/sawtooth-beat :beat-ratio 2
                                                                                              :down? true))
                                    (show/fixtures-named "snowball") :effect-name "Snowball Saw Down 2 Beat"))
                           :color :orange :end-keys [:dimmers]))

    ;; Dimmer oscillator cues: Sawtooth up over 2 beat
    (ct/set-cue! (:cue-grid *show*) 4 4
                 (cues/cue :dimmers (fn [_] (global-dimmer-effect
                                             (oscillators/build-oscillated-param
                                              (oscillators/sawtooth-beat :beat-ratio 2))
                                             :effect-name "All Saw Up 2 Beat"))
                           :color :yellow :end-keys [:ws-dimmers :hex-dimmers :snowball-dimmers]))
    (ct/set-cue! (:cue-grid *show*) 5 4
                 (cues/cue :ws-dimmers
                           (fn [_] (dimmer-effect
                                    (oscillators/build-oscillated-param (oscillators/sawtooth-beat :beat-ratio 2))
                                    (show/fixtures-named "ws") :effect-name "WS Saw Up 2 Beat"))
                           :color :orange :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 6 4
                 (cues/cue :hex-dimmers
                           (fn [_] (dimmer-effect
                                    (oscillators/build-oscillated-param (oscillators/sawtooth-beat :beat-ratio 2))
                                    (show/fixtures-named "hex") :effect-name "Hex Saw Up 2 Beat"))
                           :color :orange :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 7 4
                 (cues/cue :snowball-dimmers
                           (fn [_] (dimmer-effect
                                    (oscillators/build-oscillated-param (oscillators/sawtooth-beat :beat-ratio 2))
                                    (show/fixtures-named "snowball") :effect-name "Snowball Saw Up 2 Beat"))
                           :color :orange :end-keys [:dimmers]))

    ;; Dimmer oscillator cues: Sine over a bar
    (ct/set-cue! (:cue-grid *show*) 0 5
                 (cues/cue :dimmers (fn [_] (global-dimmer-effect
                                             (oscillators/build-oscillated-param (oscillators/sine-bar) :min 1)
                                             :effect-name "All Sine Bar"))
                           :color :cyan :end-keys [:ws-dimmers :hex-dimmers :snowball-dimmers]))
    (ct/set-cue! (:cue-grid *show*) 1 5
                 (cues/cue :ws-dimmers
                           (fn [_] (dimmer-effect
                                    (oscillators/build-oscillated-param (oscillators/sine-bar) :min 1)
                                    (show/fixtures-named "ws") :effect-name "WS Sine Bar"))
                           :color :blue :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 2 5
                 (cues/cue :hex-dimmers
                           (fn [_] (dimmer-effect
                                    (oscillators/build-oscillated-param (oscillators/sine-bar) :min 1)
                                    (show/fixtures-named "hex") :effect-name "Hex Sine Bar"))
                           :color :blue :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 3 5
                 (cues/cue :snowball-dimmers
                           (fn [_] (dimmer-effect
                                    (oscillators/build-oscillated-param (oscillators/sine-bar) :min 1)
                                    (show/fixtures-named "snowball") :effect-name "Snowball Sine Bar"))
                           :color :blue :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 4 5
                 (cues/cue :dimmers
                           (fn [_] (dimmer-effect
                                    (oscillators/build-oscillated-param (oscillators/triangle-bar) :min 1)
                                    (show/all-fixtures) :effect-name "All Triangle Bar"))
                           :color :purple :end-keys [:ws-dimmers :hex-dimmers :snowball-dimmers]))
    (ct/set-cue! (:cue-grid *show*) 5 5
                 (cues/cue :ws-dimmers
                           (fn [_] (dimmer-effect
                                    (oscillators/build-oscillated-param (oscillators/triangle-bar) :min 1)
                                    (show/fixtures-named "ws") :effect-name "WS Triangle Bar"))
                           :color :violet :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 6 5
                 (cues/cue :hex-dimmers
                           (fn [_] (dimmer-effect
                                    (oscillators/build-oscillated-param (oscillators/triangle-bar) :min 1)
                                    (show/fixtures-named "hex") :effect-name "Hex Triangle Bar"))
                           :color :violet :end-keys [:dimmers]))
    (ct/set-cue! (:cue-grid *show*) 7 5
                 (cues/cue :snowball-dimmers
                           (fn [_] (dimmer-effect
                                    (oscillators/build-oscillated-param (oscillators/triangle-bar) :min 1)
                                    (show/fixtures-named "snowball") :effect-name "Snowball Triangle Bar"))
                           :color :violet :end-keys [:dimmers]))

    ;; Strobe cues
    (make-strobe-cue "All" (show/all-fixtures) 0 6)
    (make-strobe-cue "Weather Systems" (show/fixtures-named "ws") 1 6)
    (make-strobe-cue "Hexes" (show/fixtures-named "hex") 2 6)
    (make-strobe-cue "Snowball" (show/fixtures-named "snowball") 3 6)

    (ct/set-cue! (:cue-grid *show*) 7 6
                 (cues/cue :adjust-strobe (fn [_] (fun/adjust-strobe))
                           :color :purple
                           :variables [{:key :strobe-hue :min 0 :max 360 :name "Hue" :centered true}
                                       {:key :strobe-saturation :min 0 :max 100 :name "Saturatn"}]))

    ;; A couple snowball cues
    (ct/set-cue! (:cue-grid *show*) 0 10 (cues/function-cue :sb-pos :beams-fixed (show/fixtures-named "snowball")
                                                            :effect-name "Snowball Fixed"))
    (ct/set-cue! (:cue-grid *show*) 1 10 (cues/function-cue :sb-pos :beams-moving (show/fixtures-named "snowball")
                                                            :effect-name "Snowball Moving"))

    ;; Some example chases
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
                                           :effect-name "Wipe Right Beat"))))))

(defn use-push
  "A trivial reminder of how to connect the Ableton Push to run the
  show. But also sets up the cues, if you haven't yet."
  []
  (make-cues)
  (push/bind-to-show *show*))
