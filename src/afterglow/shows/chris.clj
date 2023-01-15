(ns afterglow.shows.chris
  "Cues for shows Chris wants to run for Rhett. Useful as an example of
  how a small show run by someone unfamiliar with Afterglow was
  organized after a few years of not working with it."
  (:require [afterglow.beyond :as beyond]
            [afterglow.channels :as chan]
            [afterglow.controllers :as ct]
            [afterglow.controllers.tempo]
            [afterglow.core :as core]
            [afterglow.effects :as fx]
            [afterglow.effects.channel :as chan-fx]
            [afterglow.effects.color :as color-fx]
            [afterglow.effects.cues :as cues]
            [afterglow.effects.dimmer :refer [dimmer-effect master-set-level]]
            [afterglow.effects.fun :as fun]
            [afterglow.effects.movement :as move]
            [afterglow.effects.oscillators :as oscillators]
            [afterglow.effects.params :as params]
            [afterglow.effects.show-variable :as var-fx]
            [afterglow.examples :as ex]
            [afterglow.fixtures.american-dj :as adj]
            [afterglow.fixtures.blizzard :as blizzard]
            [afterglow.fixtures.chauvet :as chauvet]
            [afterglow.rhythm :as rhythm]
            [afterglow.show :as show]
            [afterglow.show-context :refer [*show* with-show set-default-show!]]
            [afterglow.transform :as tf]
            [amalloy.ring-buffer :refer [ring-buffer]]
            [clojure.math.numeric-tower :as math]
            [clojure.set :as set]
            [com.evocomputing.colors :as colors :refer [color-name create-color hue adjust-hue]]
            [overtone.osc :as osc]
            [taoensso.timbre :as timbre]))

(defonce ^{:doc "Holds the show if it has been created,
  so it can be unregistered if it is being re-created."}
  chris-show
  (atom nil))

(defonce ^{:doc "Allows effects to set variables in the running show."}
  var-binder
  (atom nil))

(defn make-movement-cues
  "Create a page of with some large scale and layered movement
  effects. And miscellany which I'm not totally sure what to do with
  yet."
  [page-x page-y]
  (let [x-base (* page-x 8)
        y-base (* page-y 8)]

    (show/set-cue! (+ x-base 2) (inc y-base)
                   (cues/cue :movement (fn [var-map]
                                         (cues/apply-merging-var-map var-map fun/aim-fan
                                                                     (concat (show/fixtures-named "blade")
                                                                             (show/fixtures-named "torrent"))))
                             :variables [{:key "x-scale" :min -5 :max 5 :start 1 :name "X Scale"}
                                         {:key "y-scale" :min -10 :max 10 :start 5 :name "Y Scale"}
                                         {:key "z" :min 0 :max 20 :start 4}
                                       {:key "y" :min -10 :max 10 :start ex/rig-height}
                                       {:key "x" :min -10 :max 10 :start 0.0}]
                           :color :blue :end-keys [:move-blades :move-torrents]))

    (show/set-cue! (+ x-base 2) (+ y-base 2)
                   (cues/cue :movement (fn [var-map]
                                         (cues/apply-merging-var-map var-map fun/twirl
                                                                     (concat (show/fixtures-named "blade")
                                                                             (show/fixtures-named "torrent"))))
                             :variables [{:key "beats" :min 1 :max 32 :type :integer :start 8 :name "Beats"}
                                         {:key "cycles" :min 1 :max 10 :type :integer :start 1 :name "Cycles"}
                                         {:key "radius" :min 0 :max 10 :start 0.25 :name "Radius"}
                                         {:key "z" :min -10 :max 10 :start -1.0}
                                         {:key "y" :min -10 :max 10 :start ex/rig-height}
                                         {:key "x" :min -10 :max 10 :start 0.0}]
                             :color :green :end-keys [:move-blades :move-torrents]))

    (show/set-cue! (inc x-base) (+ y-base 3)
                   (cues/cue :move-torrents
                             (fn [var-map] (cues/apply-merging-var-map var-map ex/torrent-8))
                             :variables [{:key "bars" :name "Bars" :min 1 :max 8 :type :integer :start 2}
                                         {:key "cycles" :name "Cycles" :min 1 :max 8 :type :integer :start 1}
                                         {:key "stagger" :name "Stagger" :min 0 :max 4 :start 0}
                                         {:key "spread" :name "Spread" :min -45 :max 45
                                          :centered true :resolution 0.25 :start 0}
                                         {:key "pan-min" :name "Pan min" :min -180 :max 180
                                          :centered true :resolution 0.5 :start -75}
                                         {:key "pan-max" :name "Pan max" :min -180 :max 180
                                          :centered true :resolution 0.5 :start 90}
                                         {:key "tilt-min" :name "Tilt min" :min -180 :max 180
                                          :centered true :resolution 0.5 :start -10}
                                         {:key "tilt-max" :name "Tilt max" :min -180 :max 180
                                          :centered true :resolution 0.5 :start 75}]
                             :color :yellow :end-keys [:movement]))

    (show/set-cue! (+ x-base 3) (+ y-base 3)
                   (cues/cue :move-blades
                             (fn [var-map] (cues/apply-merging-var-map var-map ex/can-can))
                             :variables [{:key "bars" :name "Bars" :min 1 :max 8 :type :integer :start 1}
                                         {:key "cycles" :name "Cycles" :min 1 :max 8 :type :integer :start 1}
                                         {:key "stagger" :name "Stagger" :min 0 :max 4 :start 0.5}
                                         {:key "spread" :name "Spread" :min -45 :max 45
                                          :centered true :resolution 0.25 :start 0}
                                         {:key "pan-min" :name "Pan min" :min -180 :max 180
                                          :centered true :resolution 0.5 :start 0}
                                         {:key "pan-max" :name "Pan max" :min -180 :max 180
                                          :centered true :resolution 0.5 :start 0}
                                         {:key "tilt-min" :name "Tilt min" :min -180 :max 180
                                          :centered true :resolution 0.5 :start -60}
                                         {:key "tilt-max" :name "Tilt max" :min -180 :max 180
                                          :centered true :resolution 0.5 :start 100}]
                             :color :yellow :end-keys [:movement]))

    (show/set-cue! (+ x-base 3) (+ y-base 4)
                   (cues/cue :blade-circles
                             (fn [var-map] (cues/apply-merging-var-map var-map ex/circle-chain
                                                                       (show/fixtures-named :blade) true))
                             :variables [{:key "bars" :name "Bars" :min 1 :max 8 :type :integer :start 2}
                                         {:key "radius" :name "Radius" :min 0.1 :max 2
                                          :resolution 0.1 :start 1.0}
                                         {:key "stagger" :name "Stagger" :min 0 :max 2 :start 0
                                          :resolution 0.1}]
                             :short-name "Blade Circles" :color :green :priority 4))

    (show/set-cue! (inc x-base) (+ y-base 4)
                   (cues/cue :torrent-circles
                             (fn [var-map] (cues/apply-merging-var-map var-map ex/circle-chain
                                                                       (show/fixtures-named :torrent) false))
                             :variables [{:key "bars" :name "Bars" :min 1 :max 8 :type :integer :start 2}
                                         {:key "radius" :name "Radius" :min 0.1 :max 2
                                          :resolution 0.1 :start 1.0}
                                         {:key "stagger" :name "Stagger" :min 0 :max 2 :start 0
                                          :resolution 0.1}]
                             :short-name "Torrent Circles" :color :green :priority 4))

    ;; A chase which overlays on other movement cues, gradually taking over the lights
    (show/set-cue! (+ x-base 2) (+ y-base 5)
                   (cues/cue :crossover (fn [var-map] (cues/apply-merging-var-map var-map ex/crossover-chase))
                             :variables [{:key "beats" :min 1 :max 8 :start 2 :type :integer :name "Beats"}
                                         {:key "fade-fraction" :min 0 :max 1 :start 0 :name "Fade"}
                                         {:key "cross-color" :type :color :start (colors/create-color :red)
                                          :name "X Color"}
                                         {:key "end-color" :type :color :start (colors/create-color :yellow)
                                          :name "End Color"}]
                             :color :cyan :priority 5))))

(defn misc-movement-cues
  "some miscellany which I'm not totally sure what to do with.
  yet."
  [page-x page-y]
  (let [x-base (* page-x 8)
        y-base (* page-y 8)]

    (show/set-cue! x-base (+ y-base 6)
                   (cues/cue :confetti
                             (fn [var-map]
                               (let [beats (params/bind-keyword-param (:beats var-map) Number 2)
                                     cycles (params/bind-keyword-param (:cycles var-map ) Number 1)
                                     step-ratio (params/build-param-formula Number #(/ %1 %2) beats cycles)
                                     step (params/build-step-param :interval-ratio step-ratio)]
                                 (cues/apply-merging-var-map var-map
                                                             fun/confetti (show/all-fixtures)
                                                             :step step)))
                             :variables [{:key "beats" :min 1 :max 8 :start 2 :type :integer :name "Beats"}
                                         {:key "cycles" :min 1 :max 8 :start 1 :type :integer :name "Cycles"}
                                         {:key "min-added" :min 0 :max 20 :start 1 :type :integer :name "Min Add"}
                                         {:key "max-added" :min 1 :max 20 :start 4 :type :integer :name "Max Add"}
                                         {:key "min-duration" :min 1 :max 16 :start 1 :type :integer :name "Min Last"}
                                         {:key "max-duration" :min 1 :max 16 :start 4 :type :integer :name "Max Last"}
                                         {:key "min-saturation" :min 0 :max 100 :start 100 :name "Min Sat"}]
                             :color :orange :priority 5))
    (show/set-cue! (inc x-base) (+ y-base 6)
                   (cues/cue :confetti
                             (fn [var-map]
                               (let [beats (params/bind-keyword-param (:beats var-map) Number 2)
                                     cycles (params/bind-keyword-param (:cycles var-map ) Number 1)
                                     step-ratio (params/build-param-formula Number #(/ %1 %2) beats cycles)
                                     step (params/build-step-param :interval-ratio step-ratio)]
                                 (cues/apply-merging-var-map var-map
                                                             fun/confetti (show/all-fixtures)
                                                             :step step :aim? true)))
                             :variables [{:key "beats" :min 1 :max 8 :start 2 :type :integer :name "Beats"}
                                         {:key "cycles" :min 1 :max 8 :start 1 :type :integer :name "Cycles"}
                                         {:key "min-added" :min 0 :max 20 :start 1 :type :integer :name "Min Add"}
                                         {:key "max-added" :min 1 :max 20 :start 4 :type :integer :name "Max Add"}
                                         {:key "min-duration" :min 1 :max 16 :start 1 :type :integer :name "Min Last"}
                                         {:key "max-duration" :min 1 :max 16 :start 4 :type :integer :name "Max Last"}
                                         {:key "min-saturation" :min 0 :max 100 :start 100 :name "Min Sat"}]
                             :color :orange :priority 5 :short-name "Confetti Dance"))))

(defn make-main-color-cues
  "Creates a page of cues that assign colors to the lights. If Beyond
  laser show integration is desired, `add-beyond?` will be `true`."
  [page-x page-y add-beyond?]
  (let [x-base (* page-x 8)
        y-base (* page-y 8)
        rig-left (:x (first (show/fixtures-named :torrent-2)))
        rig-right (:x (first (show/fixtures-named :torrent-1)))
        rig-width (- rig-right rig-left)
        hue-bar (oscillators/build-oscillated-param  ; Spread a rainbow across a bar of music
                 (oscillators/sawtooth :interval :bar) :max 360)
        desat-beat (oscillators/build-oscillated-param  ; Desaturate a color as a beat progresses
                    (oscillators/sawtooth :down? true) :max 100)
        hue-gradient (params/build-spatial-param  ; Spread a rainbow across the light grid
                      (show/all-fixtures)
                      (fn [head] (- (:x head) (:min-x @(:dimensions *show*)))) :max 360)
        rig-hue-gradient (params/build-spatial-param  ; Spread a rainbow across just the main rig, repeating
                          (show/all-fixtures)         ; beyond that, irrespective of other lights' positions.
                          (fn [head] (colors/clamp-hue (* 360 (/ (- (:x head) rig-left) rig-width)))))
        hue-z-gradient (params/build-spatial-param  ; Spread a rainbow across the light grid front to back
                        (show/all-fixtures)
                        (fn [head] (- (:z head) (:min-z @(:dimensions *show*)))) :max 360)]

    ;; Bottom row assigns colors, first to all fixtures, and then (at a higher priority, so they can
    ;; run a the same time as the first, and locally override it) individual fixture groups.
    (ex/make-color-cue "white" x-base y-base :include-color-wheels? true
                    :fixtures (show/all-fixtures) :effect-key :all-color :effect-name "Color all")
    (doall (map-indexed (fn [i group]
                          (ex/make-color-cue "white" (+ x-base (inc i)) y-base :include-color-wheels? true
                                             :fixtures (show/fixtures-named group)
                                             :effect-key (keyword (str (name group) "-color"))
                                             :effect-name (str "Color " (name group))
                                             :priority 1))
                        ex/light-groups))

    ;; Some special/fun cues
    (show/set-variable! :rainbow-saturation 100)
    (show/set-cue! x-base (inc y-base)
                   (let [color-param (params/build-color-param :s :rainbow-saturation :l 50 :h hue-bar)]
                     (cues/cue :all-color (fn [_] (ex/global-color-effect color-param))
                               :color-fn (cues/color-fn-from-param color-param)
                               :short-name "Rainbow Bar Fade"
                               :variables [{:key :rainbow-saturation :name "Saturatn" :min 0 :max 100 :start 100
                                            :type :integer}])))
    (show/set-cue! (inc x-base) (inc y-base)
                   (cues/cue :all-color (fn [_] (ex/global-color-effect
                                                 (params/build-color-param :s :rainbow-saturation :l 50
                                                                           :h rig-hue-gradient)
                                                 :include-color-wheels? true))
                             :short-name "Rainbow Rig"
                             :variables [{:key :rainbow-saturation :name "Saturatn" :min 0 :max 100 :start 100
                                          :type :integer}]))
    (show/set-cue! (+ x-base 2) (inc y-base)
                   (let [color-param (params/build-color-param :s :rainbow-saturation :l 50 :h hue-gradient
                                                               :adjust-hue hue-bar)]
                     (cues/cue :all-color (fn [_] (ex/global-color-effect color-param))
                               :color-fn (cues/color-fn-from-param color-param)
                               :short-name "Rainbow Grid+Bar"
                               :variables [{:key :rainbow-saturation :name "Saturatn" :min 0 :max 100 :start 100
                                            :type :integer}])))
    (show/set-cue! (+ x-base 3) (inc y-base) ; Desaturate the rainbow as each beat progresses
                   (let [color-param (params/build-color-param :s desat-beat :l 50 :h hue-gradient
                                                               :adjust-hue hue-bar)]
                     (cues/cue :all-color (fn [_] (ex/global-color-effect color-param))
                               :color-fn (cues/color-fn-from-param color-param)
                               :short-name "Rainbow Pulse")))

    (show/set-cue! (+ x-base 4) (inc y-base)
                   (cues/cue :transform-colors (fn [_] (color-fx/transform-colors (show/all-fixtures)))
                             :priority 1000))

    (show/set-cue! (+ x-base 5) (inc y-base)
                   (cues/cue :all-color (fn [_] (ex/global-color-effect
                                                 (params/build-color-param :s 100 :l 50 :h hue-z-gradient)
                                                 :include-color-wheels? true))
                             :short-name "Z Rainbow Grid"))
    (when add-beyond?
      (show/set-cue! (+ x-base 6) (inc y-base)
                     (let [color-param (params/build-color-param :s :rainbow-saturation :l 50 :h hue-bar)]
                       (cues/cue :all-color (fn [_] (fx/scene "Rainbow with laser" (ex/global-color-effect color-param)
                                                              (beyond/laser-color-effect ex/laser-show color-param)))
                                 :color-fn (cues/color-fn-from-param color-param)
                                 :short-name "Rainbow with Laser"
                                 :variables [{:key :rainbow-saturation :name "Saturatn" :min 0 :max 100 :start 100
                                              :type :integer}]))))
    (show/set-cue! (+ x-base 7) (inc y-base)
                   (let [color-param (params/build-color-param :s 100 :l 50 :h hue-gradient
                                                               :adjust-hue hue-bar)]
                     (cues/cue :all-color (fn [_] (ex/global-color-effect color-param
                                                                          :fixtures (show/fixtures-named "blade")))
                               :color-fn (cues/color-fn-from-param color-param)
                               :short-name "Rainbow Blades")))

    ;; The fun sparkle cue.
    (show/set-cue! (+ x-base 7) (+ y-base 2)
                   (cues/cue :sparkle (fn [var-map]
                                        (cues/apply-merging-var-map var-map fun/sparkle (show/all-fixtures)))
                             :held true
                             :priority 100
                             :variables [{:key "chance" :min 0.0 :max 0.4 :start 0.05 :velocity true}
                                         {:key "fade-time" :name "Fade" :min 1 :max 2000 :start 50 :type :integer}]))))

(defn make-main-dimmer-cues
  "Creates a page of cues that assign dimmers to the lights."
  [page-x page-y]
  (let [x-base (* page-x 8)
        y-base (* page-y 8)]

    (show/set-cue! (+ x-base 7) (+ y-base 7)
                   (cues/function-cue :strobe-all :strobe (show/all-fixtures) :effect-name "Raw Strobe"))

    ;; Dimmer cues to turn on and set brightness of groups of lights
    (ex/make-dimmer-cue nil x-base (+ y-base 2) :yellow)
    (doall (map-indexed (fn [i group] (ex/make-dimmer-cue group (+ x-base (inc i)) (+ y-base 2) :yellow))
                        ex/light-groups))

    ;; Dimmer oscillator cues: Sawtooth
    (ex/make-sawtooth-dimmer-cue nil x-base (+ y-base 3) :yellow)
    (doall (map-indexed (fn [i group]
                          (ex/make-sawtooth-dimmer-cue group (+ x-base (inc i)) (+ y-base 3) :orange))
                        ex/light-groups))

    ;; Dimmer oscillator cues: Triangle
    (ex/make-triangle-dimmer-cue nil x-base (+ y-base 4) :orange)
    (doall (map-indexed (fn [i group]
                          (ex/make-triangle-dimmer-cue group (+ x-base (inc i)) (+ y-base 4) :red)) ex/light-groups))

    ;; Dimmer oscillator cues: Sine
    (ex/make-sine-dimmer-cue nil x-base (+ y-base 5) :cyan)
    (doall (map-indexed (fn [i group]
                          (ex/make-sine-dimmer-cue group (+ x-base (inc i)) (+ y-base 5) :blue)) ex/light-groups))

    ;; Dimmer oscillator cues: Square
    (ex/make-square-dimmer-cue nil x-base (+ y-base 6) :cyan)
    (doall (map-indexed (fn [i group]
                          (ex/make-square-dimmer-cue group (+ x-base (inc i)) (+ y-base 6) :green)) ex/light-groups))

    ;; Strobe cues
    (ex/make-strobe-cue-2 "All" (show/all-fixtures) x-base (+ y-base 7))
    (ex/make-strobe-cue-2 "Torrents" (show/fixtures-named "torrent") (inc x-base) (+ y-base 7))
    (ex/make-strobe-cue-2 "Blades" (show/fixtures-named "blade") (+ x-base 2) (+ y-base 7))
    (ex/make-strobe-cue-2 "Weather Systems" (show/fixtures-named "ws") (+ x-base 3) (+ y-base 7))
    (ex/make-strobe-cue-2 "Hexes" (show/fixtures-named "hex") (+ x-base 4) (+ y-base 7))
    (ex/make-strobe-cue-2 "Pucks" (show/fixtures-named "puck") (+ x-base 5) (+ y-base 7))
    (ex/make-strobe-cue-2 "Snowball" (show/fixtures-named "snowball") (+ x-base 6) (+ y-base 7))

    (let [color-var {:key :strobe-color :type :color :name "Strobe Color"}]
      (show/set-cue! (+ x-base 7) (+ y-base 7) (cues/cue :strobe-color (fn [_] (fx/blank "Strobe Color"))
                                                         :color :purple
                                                         :color-fn (cues/color-fn-from-cue-var color-var)
                                                         :variables [color-var])))

    ;; This was the old way of adjusting strobe cues with only numeric parameters. The above
    ;; cue shows how to do it with the newer color parameter approach.
    #_(show/set-cue! (+ x-base 7) (+ y-base 6)
                     (cues/cue :adjust-strobe (fn [_] (fun/adjust-strobe))
                               :color :purple
                               :variables [{:key :strobe-hue :min 0 :max 360 :name "Hue" :centered true}
                                           {:key :strobe-saturation :min 0 :max 100 :name "Saturatn"}]))))

(defn more-color-cues
  "Some miscellany which I'm not totally sure what to do with."
  [page-x page-y]
  (let [x-base (* page-x 8)
        y-base (* page-y 8)]

    ;; Some dimmer sweeps
    (let [dimmer-sweep-fixtures (concat (show/fixtures-named :torrent) (show/fixtures-named :blade)
                                        (show/fixtures-named :hex))]
      (show/set-cue! x-base y-base
                     (cues/cue :dimmers
                               (fn [var-map] (cues/apply-merging-var-map
                                              var-map ex/dimmer-sweep  dimmer-sweep-fixtures
                                              (oscillators/sawtooth :down? (:down var-map)
                                                                    :interval-ratio (ex/build-ratio-param var-map))))
                               :color :red :short-name "Sawtooth Sweep"
                               :variables [{:key "beats" :min 1 :max 32 :type :integer :start 2 :name "Beats"}
                                           {:key "down" :type :boolean :start true :name "Down?"}
                                           {:key "cycles" :min 1 :max 10 :type :integer :start 1 :name "Cycles"}
                                           {:key "width" :min 0 :max 1 :start 0.1 :name "Width"}
                                           {:key "level" :min 0 :max 255 :start 255 :name "Level"}
                                           {:key "fade" :type :boolean :start false :name "Fade?"}]))

      (show/set-cue! x-base (inc y-base)
                     (cues/cue :dimmers
                               (fn [var-map] (cues/apply-merging-var-map
                                              var-map ex/dimmer-sweep dimmer-sweep-fixtures
                                              (oscillators/triangle :interval-ratio (ex/build-ratio-param var-map))))
                               :color :red :short-name "Triangle Sweep"
                               :variables [{:key "beats" :min 1 :max 32 :type :integer :start 2 :name "Beats"}
                                           {:key "cycles" :min 1 :max 10 :type :integer :start 1 :name "Cycles"}
                                           {:key "width" :min 0 :max 1 :start 0.25 :name "Width"}
                                           {:key "level" :min 0 :max 255 :start 255 :name "Level"}
                                           {:key "fade" :type :boolean :start false :name "Fade?"}])))

    (show/set-cue! (inc x-base) y-base
                   (cues/cue :blade-dimmers
                             (fn [var-map] (cues/apply-merging-var-map
                                            var-map ex/dimmer-sweep  (show/fixtures-named :blade)
                                            (oscillators/sawtooth :down? (:down var-map)
                                                                  :interval-ratio (ex/build-ratio-param var-map))))
                             :color :red :short-name "Blade Saw Sweep"
                             :variables [{:key "beats" :min 1 :max 32 :type :integer :start 2 :name "Beats"}
                                         {:key "down" :type :boolean :start true :name "Down?"}
                                         {:key "cycles" :min 1 :max 10 :type :integer :start 1 :name "Cycles"}
                                         {:key "width" :min 0 :max 1 :start 0.1 :name "Width"}
                                         {:key "level" :min 0 :max 255 :start 255 :name "Level"}
                                         {:key "fade" :type :boolean :start false :name "Fade?"}]))

    (show/set-cue! (inc x-base) (inc y-base)
                   (cues/cue :blade-dimmers
                             (fn [var-map] (cues/apply-merging-var-map
                                            var-map ex/dimmer-sweep (show/fixtures-named :blade)
                                            (oscillators/triangle :interval-ratio (ex/build-ratio-param var-map))))
                               :color :red :short-name "Blade Triangle Sweep"
                               :variables [{:key "beats" :min 1 :max 32 :type :integer :start 2 :name "Beats"}
                                           {:key "cycles" :min 1 :max 10 :type :integer :start 1 :name "Cycles"}
                                           {:key "width" :min 0 :max 1 :start 0.25 :name "Width"}
                                           {:key "level" :min 0 :max 255 :start 255 :name "Level"}
                                           {:key "fade" :type :boolean :start false :name "Fade?"}]))

    (show/set-cue! (inc x-base) (+ 2 y-base)
                   (cues/cue :blade-dimmers
                             (fn [var-map]
                               (let [step (params/build-step-param :interval-ratio (ex/build-ratio-param var-map)
                                                                   :fade-fraction (:fade-fraction var-map))]
                                 (fx/chase "Blade Cross"
                                           (map #(dimmer-effect (:level var-map) (show/fixtures-named %))
                                                [:blade-1 :blade-3 :blade-2 :blade-4])
                                           step :beyond :loop)))
                               :color :red :short-name "Blade Cross"
                               :variables [{:key "beats" :min 1 :max 32 :type :integer :start 1 :name "Beats"}
                                           {:key "cycles" :min 1 :max 10 :type :integer :start 4 :name "Cycles"}
                                           {:key "level" :min 0 :max 255 :start 255 :name "Level"}
                                           {:key "fade-fraction" :min 0 :max 1 :start 0 :name "Fade"}]))

    ;; A fun pressure-sensitive dimmer spread effect
    (show/set-cue! x-base (+ y-base 2)
                   (cues/cue :bloom (fn [var-map]
                                      (cues/apply-merging-var-map
                                       var-map fun/bloom (show/all-fixtures)
                                       :measure (tf/build-distance-measure 0 ex/rig-height 0 :ignore-z true)))
                             :variables [{:key "color" :type :color :start (colors/create-color :white)
                                          :name "Color"}
                                         {:key "fraction" :min 0 :max 1 :start 0 :velocity true}]
                             :held true :priority 1000 :color :purple))

    ;; Some color cycle chases  TODO: These probably belong in the color section.
    (show/set-cue! x-base (+ y-base 7)
                   (cues/cue :all-color (fn [_] (fun/iris-out-color-cycle-chase (show/all-fixtures)))))
    (show/set-cue! (inc x-base) (+ y-base 7)
                   (cues/cue :all-color
                             (fn [_] (fun/wipe-right-color-cycle-chase
                                      (show/all-fixtures)
                                      :transition-phase-function rhythm/snapshot-bar-phase))))
    (show/set-cue! (+ x-base 2) (+ y-base 7)
                   (cues/cue :all-color (fn [_] (fun/wipe-right-color-cycle-chase
                                                 (show/all-fixtures)
                                                 :color-index-function rhythm/snapshot-beat-within-phrase
                                                 :transition-phase-function rhythm/snapshot-beat-phase
                                                 :effect-name "Wipe Right Beat"))))

    (show/set-cue! (+ x-base 5) y-base
                   (cues/cue :pinstripes
                             (fn [var-map]
                               (let [step-ratio (ex/build-ratio-param var-map)
                                     step (params/build-step-param :interval-ratio step-ratio
                                                                   :fade-fraction (:fade-fraction var-map))
                                     colors [(:color-1 var-map) (:color-2 var-map)]]
                                 (fun/pinstripes (set/difference
                                                  (set (show/all-fixtures))
                                                  (set (show/fixtures-named :snowball)))
                                                 :step step :colors colors)))
                             :variables [{:key "beats" :name "Beats" :min 1 :max 32 :type :integer :start 1}
                                         {:key "cycles" :name "Cycles" :min 1 :max 16 :type :integer :start 1}
                                         {:key "color-1" :type :color :start (colors/create-color :red)
                                          :name "Color 1"}
                                         {:key "color-2" :type :color :start (colors/create-color :white)
                                          :name "Color 2"}
                                         {:key "fade-fraction" :min 0 :max 1 :start 0 :name "Fade"}]
                             :color :yellow))

    (show/set-cue! (+ x-base 5) (inc y-base)
                   (cues/cue :pinstripes
                             (fn [var-map]
                               (let [step-ratio (ex/build-ratio-param var-map)
                                     step (params/build-step-param :interval-ratio step-ratio
                                                                   :fade-fraction (:fade-fraction var-map))
                                     colors [(:color-1 var-map) (:color-2 var-map) (:color-3 var-map)]]
                                 (fun/pinstripes (set/difference
                                                  (set (show/all-fixtures))
                                                  (set (show/fixtures-named :snowball)))
                                                 :step step :colors colors)))
                             :variables [{:key "beats" :name "Beats" :min 1 :max 32 :type :integer :start 1}
                                         {:key "cycles" :name "Cycles" :min 1 :max 16 :type :integer :start 1}
                                         {:key "color-1" :type :color :start (colors/create-color :red)
                                          :name "Color 1"}
                                         {:key "color-2" :type :color :start (colors/create-color :white)
                                          :name "Color 2"}
                                         {:key "color-3" :type :color :start (colors/create-color :blue)
                                          :name "Color 3"}
                                         {:key "fade-fraction" :min 0 :max 1 :start 0 :name "Fade"}]
                             :color :orange :short-name "Pin 3"))

    (show/set-cue! (+ x-base 5) (+ 2 y-base)
                   (cues/cue :pinstripes
                             (fn [var-map]
                               (let [step-ratio (ex/build-ratio-param var-map)
                                     step (params/build-step-param :interval-ratio step-ratio
                                                                   :fade-fraction (:fade-fraction var-map))
                                     colors [(:color-1 var-map) (:color-2 var-map)
                                             (:color-3 var-map) (:color-4 var-map)]]
                                 (fun/pinstripes (set/difference
                                                  (set (show/all-fixtures))
                                                  (set (show/fixtures-named :snowball)))
                                                 :step step :colors colors)))
                             :variables [{:key "beats" :name "Beats" :min 1 :max 32 :type :integer :start 1}
                                         {:key "cycles" :name "Cycles" :min 1 :max 16 :type :integer :start 1}
                                         {:key "color-1" :type :color :start (colors/create-color :yellow)
                                          :name "Color 1"}
                                         {:key "color-2" :type :color :start (colors/create-color :purple)
                                          :name "Color 2"}
                                         {:key "color-3" :type :color :start (colors/create-color :white)
                                          :name "Color 3"}
                                         {:key "color-4" :type :color :start (colors/create-color :cyan)
                                          :name "Color 4"}
                                         {:key "fade-fraction" :min 0 :max 1 :start 0 :name "Fade"}]
                             :color :orange :short-name "Pin 4"))))

(defn make-cues
  "Set up the pages of cues."
  []
  (make-movement-cues 0 0)
  (misc-movement-cues 1 0)
  (ex/make-main-direction-cues 2 0)
  (ex/make-main-aim-cues 3 0)
  (ex/make-torrent-cues 0 1)
  (make-main-dimmer-cues 0 2)
  (ex/make-ambient-cues 1 2)
  (make-main-color-cues 0 3 false)
  (more-color-cues 1 3))

(defn use-chris-show
  "Set up the show for Chris. By default it will create the
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
  (set-default-show! (swap! chris-show (fn [s]
                                         (when s
                                           (show/unregister-show s)
                                           (with-show s (show/stop!)))
                                         (show/show :universes [universe] :description "Chris Show"))))

  (ex/patch-lighting-rig :universe universe :y ex/rig-height
                         :blade-3-angle (tf/degrees 71.2) :blade-4-angle (tf/degrees 78.7))
  (show/patch-fixture! :ws-1 (blizzard/weather-system) universe 161 :x 2.2 :y 1.33 :z -1.1 :y-rotation 0.0)
  (show/patch-fixture! :ws-2 (blizzard/weather-system) universe 187 :x -2.2 :y 1.33 :z -1.1 :y-rotation 0.0)
  (show/patch-fixture! :puck-1 (blizzard/puck-fab5) universe 97 :x (tf/inches -76) :y (tf/inches 8) :z (tf/inches 52))
  (show/patch-fixture! :puck-2 (blizzard/puck-fab5) universe 113 :x (tf/inches -76) :y (tf/inches 8) :z (tf/inches 40))

  (reset! var-binder (var-fx/create-for-show *show*))

  ;; Turn on the OSC server, and clear any variable and cue bindings that might have been around from previous runs.
  ;; Some of the example cues we use depend on this being available.
  (when (nil? @core/osc-server)
    (core/start-osc-server 16010))
  (ex/clear-osc-var-bindings)
  (ex/clear-osc-cue-bindings)

  (make-cues)

  '*show*)
