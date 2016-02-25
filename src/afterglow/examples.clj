(ns afterglow.examples
  "Show some simple ways to use Afterglow, and hopefully inspire
  exploration." {:author "James Elliott"}
  (:require [afterglow.beyond :as beyond]
            [afterglow.controllers.ableton-push :as push]
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
            [afterglow.fixtures.american-dj :as adj]
            [afterglow.fixtures.blizzard :as blizzard]
            [afterglow.fixtures.chauvet :as chauvet]
            [afterglow.rhythm :as rhythm]
            [afterglow.show :as show]
            [afterglow.show-context :refer [*show* with-show set-default-show!]]
            [afterglow.transform :as tf]
            [com.evocomputing.colors :as colors :refer [color-name create-color hue adjust-hue]]
            [overtone.osc :as osc]
            [taoensso.timbre :as timbre]))

(defonce ^{:doc "Allows effects to set variables in the running show."}
  var-binder
  (atom nil))

(defonce ^{:doc "Holds the sample show if it has been created,
  so it can be unregistered if it is being re-created."}
  sample-show
  (atom nil))

(defn patch-lighting-rig
  "An example of how to patch a whole group of lights with a
  parameterized location. We mount our lights on our lighting rig in
  standard positions, so knowing the position of the rig and the
  height to which it has been adjusted allows us to figure out where
  all the lights are on it.

  For the moment the orientation of the rig defines the orientation of
  the show, and the origins of the show axes are the spot on the floor
  underneath the center of the horizontal truss. The _z_ axis
  increases towards the audience from that point.

  Because the height of the rig can be adjusted, you can pass in a
  value with `:y` to set the height of the center of the lower bar on
  the horizontal truss. If omitted a default height of 62.5 inches is
  used, which is approximately the height of the bar when the
  extension poles are collapsed for load-in and strike.

  We try to hang blades 1-4 at an angle of 72.5 degrees leaning towards
  the audience, and blade 5 sags to about 101 degrees but if any angle
  ends up being off and difficult to correct, it can be passed in with
  `:blade-1-angle` through `:blade-5-angle` The actual mounting height
  of blade 5, if it differs from 4 inches can be passed with
  `:blade-5-height`.

  It would be possible to extend this function to support positioning
  and rotating the truss within show space, now that `patch-fixture`
  allows you to pass in a transformation matrix. But until that
  complexity is needed, this simpler approach seems practical. The
  truss is the main component of our show, so having it be at the
  origin makes sense."
  [& {:keys [universe y blade-1-angle blade-2-angle blade-3-angle blade-4-angle blade-5-angle blade-5-height]
      :or {universe 1 y (tf/inches 62.5)
           blade-1-angle (tf/degrees 72.5) blade-2-angle (tf/degrees 72.5)
           blade-3-angle (tf/degrees 72.5) blade-4-angle (tf/degrees 72.5)
           blade-5-angle (tf/degrees 101) blade-5-height (tf/inches 4)}}]

  ;; Torrent F3 moving head effect spots
  (show/patch-fixture! :torrent-1 (blizzard/torrent-f3) universe 1
                       :x (tf/inches 50) :y (+ y (tf/inches -14))
                       :x-rotation (tf/degrees 180)
                       :y-rotation (tf/degrees -90))
  (show/patch-fixture! :torrent-2 (blizzard/torrent-f3) universe 17
                       :x (tf/inches -50) :y (+ y (tf/inches -14))
                       :x-rotation (tf/degrees 180)
                       :y-rotation (tf/degrees -90))

  ;; Hex IRC mini RGBAW+UV pars
  (show/patch-fixture! :hex-1 (chauvet/slimpar-hex3-irc) universe 129
                       :x (tf/inches 51.5) :y (+ y (tf/inches 15)))
  (show/patch-fixture! :hex-2 (chauvet/slimpar-hex3-irc) universe 145
                       :x (tf/inches -51.5) :y (+ y (tf/inches 15)))

  ;; Blade moving-head RGBA pinspots
  (show/patch-fixture! :blade-1 (blizzard/blade-rgbw) universe 225
                       :x (tf/inches -21) :y (+ y (tf/inches 9))
                       :relative-rotations [[:y-rotation (tf/degrees 90)]
                                            [:z-rotation blade-1-angle]])
  (show/patch-fixture! :blade-2 (blizzard/blade-rgbw) universe 240
                       :x (tf/inches 20.5) :y (+ y (tf/inches 9))
                       :relative-rotations [[:y-rotation (tf/degrees 90)]
                                            [:z-rotation blade-2-angle]])
  (show/patch-fixture! :blade-3 (blizzard/blade-rgbw) universe 255
                       :x (tf/inches -37) :y y
                       :relative-rotations [[:y-rotation (tf/degrees 90)]
                                            [:z-rotation blade-3-angle]])
  (show/patch-fixture! :blade-4 (blizzard/blade-rgbw) universe 270
                       :x (tf/inches 37) :y y
                       :relative-rotations [[:y-rotation (tf/degrees 90)]
                                            [:z-rotation blade-4-angle]])
  (show/patch-fixture! :blade-5 (blizzard/blade-rgbw :15-channel :tilt-center 25 :tilt-half-circle -230) universe 285
                       :y (+ y blade-5-height)
                       :relative-rotations [[:y-rotation (tf/degrees 90)]
                                            [:z-rotation blade-5-angle]])

  ;; Snowball RGBA moonflower effect
  (show/patch-fixture! :snowball (blizzard/snowball) universe 33
                       :x (tf/inches 6.5) :y (+ y (tf/inches 15)))

  ;; Hypnotic RGB web effect diffraction pattern laser
  (show/patch-fixture! :hyp-rgb (adj/hypnotic-rgb) universe 45
                       :y (+ y (tf/inches -5)))

  ;; These last two can be patched as generic dimmers instead if you want them to respond to dimmer cues,
  ;; although they can only be on or off:

  ;; LED UV bar
  (show/patch-fixture! :eco-uv (afterglow.fixtures/generic-switch) universe 61)

  ;; LED colored water effect
  (show/patch-fixture! :h2o-led (afterglow.fixtures/generic-switch) universe 62))

(def rig-height
  "The height of the center of the bottom horizontal truss bar of the
  main lighting rig as set up in the current venue."
  (tf/inches 62.5))

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
  (patch-lighting-rig :universe universe :y rig-height :blade-3-angle (tf/degrees 71.2))
  #_(show/patch-fixture! :ws-1 (blizzard/weather-system) universe 161
                       :x (tf/inches 55) :y (tf/inches 71) :z (tf/inches 261) :y-rotation (tf/degrees 225))
  #_(show/patch-fixture! :ws-2 (blizzard/weather-system) universe 187
                       :x (tf/inches -55) :y (tf/inches 71) :z (tf/inches 261) :y-rotation (tf/degrees 135))
  #_(show/patch-fixture! :puck-1 (blizzard/puck-fab5) universe 97 :x (tf/inches -76) :y (tf/inches 8) :z (tf/inches 52))
  #_(show/patch-fixture! :puck-2 (blizzard/puck-fab5) universe 113 :x (tf/inches -76) :y (tf/inches 8) :z (tf/inches 40))

  (reset! var-binder (var-fx/create-for-show *show*))
  '*show*)

(defn global-color-effect
  "Make a color effect which affects all lights in the sample show.
  This became vastly more useful once I implemented dynamic color
  parameters. Can include only a specific set of lights by passing
  them with :fixtures"
  [color & {:keys [include-color-wheels? fixtures effect-name] :or {fixtures (show/all-fixtures)}}]
  (try
    (let [[c desc] (cond (= (type color) :com.evocomputing.colors/color)
                       [color (color-name color)]
                       (and (params/param? color)
                            (= (params/result-type color) :com.evocomputing.colors/color))
                       [color "variable"]
                       :else
                       [(create-color color) color])]
      (color-fx/color-effect (or effect-name (str "Global " desc)) c fixtures
                             :include-color-wheels? include-color-wheels?))
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
  (show/add-effect! :all-color (global-color-effect "slateblue" :include-color-wheels? true))
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
    (show/add-effect! :all-color
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
    (show/add-effect! :all-color
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

(defn make-color-cue
  "Create a cue-grid entry which establishes a global color effect,
  given a named color. Also set up a cue color parameter so the color
  can be tweaked in the Web UI or on the Ableton Push, and changes
  can be saved to persist between invocations."
  [color-name x y & {:keys [include-color-wheels? held fixtures effect-key effect-name priority]
                     :or   {fixtures    (show/all-fixtures)
                            effect-key  :color
                            effect-name (str "Color " color-name)
                            priority    0}}]
  (let [color     (create-color color-name)
        color-var {:key "color" :type :color :start color :name "Color"}
        cue       (cues/cue effect-key
                            (fn [var-map]
                              (global-color-effect (params/bind-keyword-param (:color var-map)
                                                                              :com.evocomputing.colors/color
                                                                              color)
                                                   :effect-name effect-name
                                                   :fixtures fixtures
                                                   :include-color-wheels? include-color-wheels?))
                            :priority priority
                            :held held
                            :color color
                            :color-fn (cues/color-fn-from-cue-var color-var x y)
                            :variables [color-var])]
    (show/set-cue! x y cue)))

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
  shaking. Takes up half a page, with the top left at the coordinates
  specified."
  [prefix fixtures top left]
  ;; Make cues for the stationary and shaking versions of all fixed gobos
  (doseq [_ (map-indexed (fn [i v]
                           (let [blue (create-color :blue)
                                 x (if (< i 8) left (+ left 2))
                                 y (if (< i 8) (- top i) (- top i -1))
                                 cue-key (keyword (str (name prefix) "-gobo-fixed"))]
                             (show/set-cue! x y (cues/function-cue cue-key (keyword v) fixtures :color blue
                                                                   :short-name (name-torrent-gobo-cue prefix v)))
                             (let [function (keyword (str (name v) "-shake"))]
                               (show/set-cue! (inc x) y (cues/function-cue
                                                         cue-key function fixtures :color blue
                                                         :short-name (name-torrent-gobo-cue prefix function))))))
                         ["gobo-fixed-mortar" "gobo-fixed-4-rings" "gobo-fixed-atom" "gobo-fixed-jacks"
                          "gobo-fixed-saw" "gobo-fixed-sunflower" "gobo-fixed-45-adapter"
                          "gobo-fixed-star" "gobo-fixed-rose-fingerprint"])])
  ;; Make cues for the stationary and shaking versions of all rotating gobos
  (doseq [_ (map-indexed (fn [i v]
                           (let [green (create-color :green)
                                 cue-key (keyword (str (name prefix) "-gobo-moving"))]
                             (show/set-cue! (+ left 2) (- top i)
                                            (cues/function-cue cue-key (keyword v) fixtures :color green
                                                               :short-name (name-torrent-gobo-cue prefix v)))
                             (let [function (keyword (str (name v) "-shake"))]
                               (show/set-cue! (+ left 3) (- top i)
                                              (cues/function-cue
                                               cue-key function fixtures :color green
                                               :short-name (name-torrent-gobo-cue prefix function))))))
                         ["gobo-moving-rings" "gobo-moving-color-swirl" "gobo-moving-stars"
                          "gobo-moving-optical-tube" "gobo-moving-magenta-bundt"
                          "gobo-moving-blue-mega-hazard" "gobo-moving-turbine"])]))

(defn make-strobe-cue
  "_This is no longer used in the sample cue set, but is left as an
  example in case you want to create a strobe cue that depends only on
  numeric parameters, rather than the newer color paramter
  capabilities._

  Create a cue which strobes a set of fixtures as long
  as the cue pad is held down, letting the operator adjust the
  lightness of the strobe color by varying the pressure they are
  applying to the pad on controllers which support pressure
  sensitivity, and having the base strobe color depend on a set of
  shared numeric show variable."
  [name fixtures x y]
  (show/set-cue! x y
                 (cues/cue (keyword (str "strobe-" (clojure.string/replace (clojure.string/lower-case name) " " "-")))
                           (fn [var-map] (fun/strobe (str "Strobe " name) fixtures
                                                     (:level var-map 50) (:lightness var-map 100)))
                           :color :purple
                           :held true
                           :priority 100
                           :variables [{:key "level" :min 0 :max 100 :start 100 :name "Level"}
                                       {:key "lightness" :min 0 :max 100 :name "Lightness" :velocity true}])))

(def white
  "The color to flash strobe cues to identify them as such."
  (create-color :white))

(defn make-strobe-cue-2
  "Create a cue which strobes a set of fixtures as long as the cue pad
  is held down, letting the operator adjust the lightness of the
  strobe color by varying the pressure they are applying to the pad on
  controllers which support pressure sensitivity, and having the base
  strobe color depend on a shared show color variable. On controllers
  which support it, the color of the cue pad will be also driven by
  this shared color variable, with a white flicker to emphasize them
  as strobing cues."
  [name fixtures x y]
  (when-not (= (type (show/get-variable :strobe-color)) :com.evocomputing.colors/color)
    ;; If the default strobe color has not yet been established, set it to purple.
    (show/set-variable! :strobe-color (create-color :purple)))
  (let [color-var {:key :strobe-color :type :color :name "Strobe Color"}]
    (show/set-cue! x y
                   (cues/cue (keyword (str "strobe-" (clojure.string/replace (clojure.string/lower-case name) " " "-")))
                             (fn [var-map] (fun/strobe-2 (str "Strobe " name) fixtures
                                                         (:level var-map 50) (:lightness var-map 100)))
                             :color :purple
                             :color-fn (fn [cue active show snapshot]
                                         (if (> (rhythm/snapshot-beat-phase snapshot 0.5) 0.7)
                                           white
                                           (or (show/get-variable :strobe-color)
                                               (:color cue))))
                             :held true
                             :priority 100
                             :variables [{:key "level" :min 0 :max 100 :start 100 :name "Level"}
                                         {:key "lightness" :min 0 :max 100 :name "Lightness" :velocity true}
                                         color-var]))))

(def light-groups
  "The named groupings of lights to build rows of effects in the cue grid."
  [:torrent :blade :ws :hex :puck :snowball])

(defn group-end-keys
  "Helper function to produce a vector of effect keywords to end all
  effects running on light groups with a given suffix."
  [effect-suffix]
  (mapv #(keyword (str (name %) "-" effect-suffix)) light-groups))

(defn build-group-cue-elements
  "Helper function which builds the common variables needed to create
  a cue which runs on either all lights or a named group of lights."
  [group effect-suffix name-suffix]
  (let [effect-key (or (when group (keyword (str (name group) "-" effect-suffix)))
                       (keyword effect-suffix))
        fixtures (or (when group (show/fixtures-named group))
                     (show/all-fixtures))
        end-keys (or (when group [(keyword effect-suffix)])
                     (group-end-keys effect-suffix))
        effect-name (str (case group
                           nil "All"
                           :ws "WS"
                           (clojure.string/capitalize (name group)))
                         " " (clojure.string/capitalize name-suffix))]
    [effect-key fixtures end-keys effect-name]))

(defn make-dimmer-cue
  "Creates a cue which lets the operator adjust the dimmer level of a
  group of fixtures. Group will be one of the values
  in [[light-groups]], or `nil` if the cue should affect all lights."
  [group x y color]
  (let [[effect-key fixtures end-keys effect-name] (build-group-cue-elements group "dimmers" "dimmers")]
    (show/set-cue! x y
                   (cues/cue effect-key
                             (fn [var-map] (dimmer-effect
                                            (params/bind-keyword-param (:level var-map 255) Number 255)
                                            fixtures :effect-name effect-name))
                             :variables [{:key "level" :min 0 :max 255 :start 255 :name "Level"}]
                             :color color :end-keys end-keys))))

(defn build-ratio-param
  "Creates a dynamic parameter for setting the beat ratio of one of
  the dimmer oscillator cues in [[make-cues]] by forming the ratio of
  the cue variables introduced by the cue. This allows the show
  operator to decide over how many beats the oscillator runs, and how
  many times it cycles in that interval.

  Expects the cue's variable map to contain entries `:beats` and
  `:cycles` which will form the numerator and denominator of the
  ratio. If any entry is missing, a default value of `1` is used
  for it."
  [var-map]
  (let [beats-param (params/bind-keyword-param (:beats var-map) Number 1)
        cycles-param (params/bind-keyword-param (:cycles var-map) Number 1)]
    (params/build-param-formula Number #(/ %1 %2) beats-param cycles-param)))

(defn make-sawtooth-dimmer-cue
  "Create a cue which applies a sawtooth oscillator to the dimmers of
  the specified group of fixtures, with cue variables to adjust the
  oscillator parameters."
  [group x y color]
  (let [[effect-key fixtures end-keys effect-name] (build-group-cue-elements group "dimmers" "saw")]
    (show/set-cue! x y
                   (cues/cue effect-key
                             (fn [var-map] (dimmer-effect
                                            (oscillators/build-oscillated-param
                                             (oscillators/sawtooth :down? (:down var-map)
                                                                   :interval-ratio (build-ratio-param var-map)
                                                                   :phase (:phase var-map)))
                                            fixtures
                                            :effect-name effect-name))
                             :color color
                             :variables [{:key "beats" :min 1 :max 32 :type :integer :start 2 :name "Beats"}
                                         {:key "down" :type :boolean :start true :name "Down?"}
                                         {:key "cycles" :min 1 :max 10 :type :integer :start 1 :name "Cycles"}
                                         {:key "phase" :min 0 :max 1 :start 0 :name "Phase"}]
                             :end-keys end-keys))))

(defn make-triangle-dimmer-cue
  "Create a cue which applies a triangle oscillator to the dimmers of
  the specified set of fixtures, with cue variables to adjust the
  oscillator parameters."
  [group x y color]
  (let [[effect-key fixtures end-keys effect-name] (build-group-cue-elements group "dimmers" "triangle")]
    (show/set-cue! x y
                   (cues/cue effect-key
                             (fn [var-map] (dimmer-effect
                                            (oscillators/build-oscillated-param
                                             (oscillators/triangle :interval-ratio (build-ratio-param var-map)
                                                                   :phase (:phase var-map)))
                                            fixtures
                                            :effect-name effect-name))
                             :color color
                             :variables [{:key "beats" :min 1 :max 32 :type :integer :start 2 :name "Beats"}
                                         {:key "cycles" :min 1 :max 10 :type :integer :start 1 :name "Cycles"}
                                         {:key "phase" :min 0 :max 1 :start 0 :name "Phase"}]
                             :end-keys end-keys))))

(defn make-sine-dimmer-cue
  "Create a cue which applies a sine oscillator to the dimmers of the
  specified set of fixtures, with cue variables to adjust the
  oscillator parameters."
  [group x y color]
  (let [[effect-key fixtures end-keys effect-name] (build-group-cue-elements group "dimmers" "sine")]
    (show/set-cue! x y
                   (cues/cue effect-key
                             (fn [var-map] (dimmer-effect
                                            (oscillators/build-oscillated-param
                                             (oscillators/sine :interval-ratio (build-ratio-param var-map)
                                                               :phase (:phase var-map))
                                             :min 1)
                                            fixtures
                                            :effect-name effect-name))
                             :color color
                             :variables [{:key "beats" :min 1 :max 32 :type :integer :start 2 :name "Beats"}
                                         {:key "cycles" :min 1 :max 10 :type :integer :start 1 :name "Cycles"}
                                         {:key "phase" :min 0 :max 1 :start 0 :name "Phase"}]
                             :end-keys end-keys))))

(defn make-square-dimmer-cue
  "Create a cue which applies a square oscillator to the dimmers of
  the specified set of fixtures, with cue variables to adjust the
  oscillator parameters."
  [group x y color]
  (let [[effect-key fixtures end-keys effect-name] (build-group-cue-elements group "dimmers" "square")]
    (show/set-cue! x y
                   (cues/cue effect-key
                             (fn [var-map] (dimmer-effect
                                            (oscillators/build-oscillated-param
                                             (oscillators/square :interval-ratio (build-ratio-param var-map)
                                                                 :width (:width var-map)
                                                                 :phase (:phase var-map)))
                                            fixtures
                                            :effect-name effect-name))
                             :color color
                             :variables [{:key "beats" :min 1 :max 32 :type :integer :start 2 :name "Beats"}
                                         {:key "width" :min 0 :max 1 :start 0.5 :name "Width"}
                                         {:key "cycles" :min 1 :max 10 :type :integer :start 1 :name "Cycles"}
                                         {:key "phase" :min 0 :max 1 :start 0 :name "Phase"}]
                             :end-keys end-keys))))

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
    (show/set-cue! 0 1
                   (cues/cue :all-color (fn [_] (fx/scene "Rainbow with laser" (global-color-effect hue-param)
                                                          (beyond/laser-color-effect server hue-param)))
                             :short-name "Rainbow Bar Fade"
                             :variables [{:key :rainbow-saturation :name "Saturatn" :min 0 :max 100 :start 100
                                          :type :integer}])))
  (show/set-cue! 2 7 (cues/cue :beyond-cue-1-1 (fn [_] (beyond/cue-effect server 1 1)) :short-name "Beyond 1 1"))
  (show/set-cue! 3 7 (cues/cue :beyond-cue-1-2 (fn [_] (beyond/cue-effect server 1 2)) :short-name "Beyond 1 2"))
  (show/set-cue! 6 7 (cues/function-cue :snowball-sound :sound-active (show/fixtures-named "snowball") :color :cyan)))

(defonce ^{:doc "A step parameter for controlling example chase cues.
  Change it to experiment with other kinds of timing and fades."}
  step-param
  (atom nil))

(defn- build-focus-oscillator
  "Returns a cue which oscillates a fixture's focus between a minimum
  and minimum value using a sine oscillator with cue variables to
  adjust the range and the oscillator's parameters."
  [effect-key effect-name fixtures]
  (cues/cue effect-key
            (fn [var-map] (afterglow.effects.channel/function-effect
                           effect-name :focus
                           (oscillators/build-oscillated-param
                            (oscillators/sine :interval-ratio (build-ratio-param var-map) :phase (:phase var-map))
                            :min (:min var-map) :max (:max var-map))
                           fixtures))
            :color (create-color :yellow)
            :variables [{:key "min" :min 0 :max 100 :start 0 :name "Min"}
                        {:key "max" :min 0 :max 100 :start 100 :name "Max"}
                        {:key "beats" :min 1 :max 32 :type :integer :start 4 :name "Beats"}
                        {:key "cycles" :min 1 :max 10 :type :integer :start :starting-cycles
                         :name "Cycles"}
                        {:key "phase" :min 0 :max 1 :start :starting-phase :name "Phase"}]))

(defn make-main-color-dimmer-cues
  "Creates a page of cues that assign dimmers and colors to the
  lights. This is probably going to be assigned as the first page, but
  can be moved by passing non-zero values for `page-x` and `page-y`."
  [page-x page-y]
  (let [x-base (* page-x 8)
        y-base (* page-y 8)
        hue-bar (oscillators/build-oscillated-param  ; Spread a rainbow across a bar of music
                 (oscillators/sawtooth :interval :bar) :max 360)
        desat-beat (oscillators/build-oscillated-param  ; Desaturate a color as a beat progresses
                    (oscillators/sawtooth :down? true) :max 100)
        hue-gradient (params/build-spatial-param  ; Spread a rainbow across the light grid
                      (show/all-fixtures)
                      (fn [head] (- (:x head) (:min-x @(:dimensions *show*)))) :max 360)
        hue-z-gradient (params/build-spatial-param  ; Spread a rainbow across the light grid front to back
                        (show/all-fixtures)
                        (fn [head] (- (:z head) (:min-z @(:dimensions *show*)))) :max 360)]

    ;; Bottom row assigns colors, first to all fixtures, and then (at a higher priority, so they can
    ;; run a the same time as the first, and locally override it) individual fixture groups.
    (make-color-cue "white" (+ x-base 0) (+ y-base 0) :include-color-wheels? true
                    :fixtures (show/all-fixtures) :effect-key :all-color :effect-name "Color All")
    (doall (map-indexed (fn [i group]
                          (make-color-cue "white" (+ x-base (inc i)) (+ y-base 0) :include-color-wheels? true
                                          :fixtures (show/fixtures-named group)
                                          :effect-key (keyword (str (name group) "-color"))
                                          :effect-name (str "Color " (name group))
                                          :priority 1))
                        light-groups))

    ;; Some special/fun cues
    (show/set-variable! :rainbow-saturation 100)
    (show/set-cue! (+ x-base 0) (+ y-base 1)
                   (let [color-param (params/build-color-param :s :rainbow-saturation :l 50 :h hue-bar)]
                     (cues/cue :all-color (fn [_] (global-color-effect color-param))
                               :color-fn (cues/color-fn-from-param color-param)
                               :short-name "Rainbow Bar Fade"
                               :variables [{:key :rainbow-saturation :name "Saturatn" :min 0 :max 100 :start 100
                                            :type :integer}])))
    (show/set-cue! (+ x-base 1) (+ y-base 1)
                   (cues/cue :all-color (fn [_] (global-color-effect
                                                 (params/build-color-param :s :rainbow-saturation :l 50 :h hue-gradient)
                                                 :include-color-wheels? true))
                             :short-name "Rainbow Grid"
                             :variables [{:key :rainbow-saturation :name "Saturatn" :min 0 :max 100 :start 100
                                          :type :integer}]))
    (show/set-cue! (+ x-base 2) (+ y-base 1)
                   (let [color-param (params/build-color-param :s :rainbow-saturation :l 50 :h hue-gradient
                                                               :adjust-hue hue-bar)]
                     (cues/cue :all-color (fn [_] (global-color-effect color-param))
                               :color-fn (cues/color-fn-from-param color-param)
                               :short-name "Rainbow Grid+Bar"
                               :variables [{:key :rainbow-saturation :name "Saturatn" :min 0 :max 100 :start 100
                                            :type :integer}])))
    (show/set-cue! (+ x-base 3) (+ y-base 1) ; Desaturate the rainbow as each beat progresses
                   (let [color-param (params/build-color-param :s desat-beat :l 50 :h hue-gradient
                                                               :adjust-hue hue-bar)]
                     (cues/cue :all-color (fn [_] (global-color-effect color-param))
                               :color-fn (cues/color-fn-from-param color-param)
                               :short-name "Rainbow Pulse")))

    (show/set-cue! (+ x-base 4) (+ y-base 1)
                   (cues/cue :transform-colors (fn [_] (color-fx/transform-colors (show/all-fixtures)))
                             :priority 1000))

    (show/set-cue! (+ x-base 5) (+ y-base 1)
                   (cues/cue :all-color (fn [_] (global-color-effect
                                                 (params/build-color-param :s 100 :l 50 :h hue-z-gradient)
                                                 :include-color-wheels? true))
                             :short-name "Z Rainbow Grid"))
    (show/set-cue! (+ x-base 6) (+ y-base 1)
                   (let [color-param (params/build-color-param :s 100 :l 50 :h hue-z-gradient
                                                               :adjust-hue hue-bar)]
                     (cues/cue :all-color (fn [_] (global-color-effect color-param))
                               :color-fn (cues/color-fn-from-param color-param)
                               :short-name "Z Rainbow Grid+Bar")))

    (show/set-cue! (+ x-base 7) (+ y-base 1)
                   (let [color-param (params/build-color-param :s 100 :l 50 :h hue-gradient
                                                               :adjust-hue hue-bar)]
                     (cues/cue :all-color (fn [_] (global-color-effect color-param
                                                                       :fixtures (show/fixtures-named "blade")))
                               :color-fn (cues/color-fn-from-param color-param)
                               :short-name "Rainbow Blades")))

    #_(show/set-cue! (+ x-base 7) (+ y-base 7)
                     (cues/function-cue :strobe-all :strobe (show/all-fixtures) :effect-name "Raw Strobe"))


    ;; Dimmer cues to turn on and set brightness of groups of lights
    (make-dimmer-cue nil (+ x-base 0) (+ y-base 2) :yellow)
    (doall (map-indexed (fn [i group] (make-dimmer-cue group (+ x-base (inc i)) (+ y-base 2) :yellow)) light-groups))

    ;; TODO: Write a macro to make it easier to bind cue variables?
    (show/set-cue! (+ x-base 7) (+ y-base 2)
                   (cues/cue :sparkle (fn [var-map] (fun/sparkle (show/all-fixtures)
                                                                 :chance (:chance var-map 0.05)
                                                                 :fade-time (:fade-time var-map 50)))
                             :held true
                             :priority 100
                             :variables [{:key "chance" :min 0.0 :max 0.4 :start 0.05 :velocity true}
                                         {:key "fade-time" :name "Fade" :min 1 :max 2000 :start 50 :type :integer}]))

    ;; Dimmer oscillator cues: Sawtooth
    (make-sawtooth-dimmer-cue nil (+ x-base 0) (+ y-base 3) :yellow)
    (doall (map-indexed (fn [i group]
                          (make-sawtooth-dimmer-cue group (+ x-base (inc i)) (+ y-base 3) :orange)) light-groups))

    ;; Dimmer oscillator cues: Triangle
    (make-triangle-dimmer-cue nil (+ x-base 0) (+ y-base 4) :orange)
    (doall (map-indexed (fn [i group]
                          (make-triangle-dimmer-cue group (+ x-base (inc i)) (+ y-base 4) :red)) light-groups))

    ;; Dimmer oscillator cues: Sine
    (make-sine-dimmer-cue nil (+ x-base 0) (+ y-base 5) :cyan)
    (doall (map-indexed (fn [i group]
                          (make-sine-dimmer-cue group (+ x-base (inc i)) (+ y-base 5) :blue)) light-groups))

    ;; Dimmer oscillator cues: Square
    (make-square-dimmer-cue nil (+ x-base 0) (+ y-base 6) :cyan)
    (doall (map-indexed (fn [i group]
                          (make-square-dimmer-cue group (+ x-base (inc i)) (+ y-base 6) :green)) light-groups))

    ;; Strobe cues
    (make-strobe-cue-2 "All" (show/all-fixtures) (+ x-base 0) (+ y-base 7))
    (make-strobe-cue-2 "Torrents" (show/fixtures-named "torrent") (+ x-base 1) (+ y-base 7))
    (make-strobe-cue-2 "Blades" (show/fixtures-named "blade") (+ x-base 2) (+ y-base 7))
    (make-strobe-cue-2 "Weather Systems" (show/fixtures-named "ws") (+ x-base 3) (+ y-base 7))
    (make-strobe-cue-2 "Hexes" (show/fixtures-named "hex") (+ x-base 4) (+ y-base 7))
    (make-strobe-cue-2 "Pucks" (show/fixtures-named "puck") (+ x-base 5) (+ y-base 7))
    (make-strobe-cue-2 "Snowball" (show/fixtures-named "snowball") (+ x-base 6) (+ y-base 7))

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

(defn make-torrent-cues
  "Create a page of cues for configuring aspects of the Torrent F3s
  and another to its right for their gobo selection."
  [page-x page-y]
  (let [x-base (* page-x 8)
        y-base (* page-y 8)]
    (show/set-cue! (+ x-base 0) (+ y-base 7)
                   (cues/function-cue :torrent-shutter :shutter-open (show/fixtures-named "torrent")))
    (show/set-cue! (+ x-base 1) (+ y-base 7)
                   (cues/function-cue :torrent-reset :motor-reset (show/fixtures-named "torrent")
                                      :color (create-color :red) :held true))

    (show/set-cue! (+ x-base 6) (+ y-base 7)
                   (cues/function-cue :t1-focus :focus (show/fixtures-named "torrent-1") :effect-name "Torrent 1 Focus"
                                      :color (create-color :yellow)))
    (show/set-cue! (+ x-base 7) (+ y-base 7)
                   (cues/function-cue :t2-focus :focus (show/fixtures-named "torrent-2") :effect-name "Torrent 2 Focus"
                                      :color (create-color :yellow)))
    (show/set-cue! (+ x-base 4) (+ y-base 7)
                   (build-focus-oscillator :t1-focus "Torrent 1 Focus Sine" (show/fixtures-named "torrent-1")))
    (show/set-cue! (+ x-base 5) (+ y-base 7)
                   (build-focus-oscillator :t2-focus "Torrent 2 Focus Sine" (show/fixtures-named "torrent-2")))
    (show/set-cue! (+ x-base 6) (+ y-base 6)
                   (cues/function-cue :t1-prism :prism-clockwise (show/fixtures-named "torrent-1") :level 100
                                      :effect-name "T1 Prism Spin CW" :color (create-color :orange)))
    (show/set-cue! (+ x-base 7) (+ y-base 6)
                   (cues/function-cue :t2-prism :prism-clockwise (show/fixtures-named "torrent-2") :level 100
                                      :effect-name "T2 Prism Spin CW" :color (create-color :orange)))
    (show/set-cue! (+ x-base 6) (+ y-base 5)
                   (cues/function-cue :t1-prism :prism-in (show/fixtures-named "torrent-1")
                                      :effect-name "T1 Prism In" :color (create-color :orange)))
    (show/set-cue! (+ x-base 7) (+ y-base 5)
                   (cues/function-cue :t2-prism :prism-in (show/fixtures-named "torrent-2")
                                      :effect-name "T2 Prism In" :color (create-color :orange)))
    (show/set-cue! (+ x-base 6) (+ y-base 4)
                   (cues/function-cue :t1-prism :prism-counterclockwise (show/fixtures-named "torrent-1")
                                      :effect-name "T1 Prism Spin CCW" :color (create-color :orange)))
    (show/set-cue! (+ x-base 7) (+ y-base 4)
                   (cues/function-cue :t2-prism :prism-counterclockwise (show/fixtures-named "torrent-2")
                                      :effect-name "T2 Prism Spin CCW" :color (create-color :orange)))
    (show/set-cue! (+ x-base 6) (+ y-base 3)
                   (cues/function-cue :t1-gobo-fixed :gobo-fixed-clockwise (show/fixtures-named "torrent-1")
                                      :effect-name "T1 Fixed Gobos Swap CW" :color (create-color :blue)))
    (show/set-cue! (+ x-base 7) (+ y-base 3)
                   (cues/function-cue :t2-gobo-fixed :gobo-fixed-clockwise (show/fixtures-named "torrent-2")
                                      :effect-name "T2 Fixed Gobos Swap CW" :color (create-color :blue)))
    (show/set-cue! (+ x-base 6) (+ y-base 2)
                   (cues/function-cue :t1-gobo-moving :gobo-moving-clockwise (show/fixtures-named "torrent-1")
                                      :effect-name "T1 Moving Gobos Swap CW" :color (create-color :green)))
    (show/set-cue! (+ x-base 7) (+ y-base 2)
                   (cues/function-cue :t2-gobo-moving :gobo-moving-clockwise (show/fixtures-named "torrent-2")
                                      :effect-name "T2 Moving Gobos Swap CW" :color (create-color :green)))
    (show/set-cue! (+ x-base 6) (+ y-base 1)
                   (cues/function-cue :t1-gobo-rotation :gobo-rotation-clockwise (show/fixtures-named "torrent-1")
                                      :effect-name "T1 Spin Gobo CW" :color (create-color :cyan) :level 100))
    (show/set-cue! (+ x-base 7) (+ y-base 1)
                   (cues/function-cue :t2-gobo-rotation :gobo-rotation-clockwise (show/fixtures-named "torrent-2")
                                      :effect-name "T2 Spin Gobo CW" :color (create-color :cyan) :level 100))
    (show/set-cue! (+ x-base 6) (+ y-base 0)
                   (cues/function-cue :t1-gobo-rotation :gobo-rotation-counterclockwise
                                      (show/fixtures-named "torrent-1")
                                      :effect-name "T1 Spin Gobo CCW" :color (create-color :cyan)))
    (show/set-cue! (+ x-base 7) (+ y-base 0)
                   (cues/function-cue :t2-gobo-rotation :gobo-rotation-counterclockwise
                                      (show/fixtures-named "torrent-2")
                                      :effect-name "T2 Spin Gobo CCW" :color (create-color :cyan)))

    ;; Some compound cues
    (show/set-cue! (+ x-base 0) (+ y-base 0)
                   (cues/cue :star-swirl (fn [_] (cues/compound-cues-effect
                                                  "Star Swirl" *show* [[(+ x-base 8) (+ y-base 4)]
                                                                       [(+ x-base 10) (+ y-base 1)]
                                                                       [(+ x-base 6) (+ y-base 7) {:level 60}]
                                                                       [(+ x-base 6) y-base {:level 25}]]))))

    (make-torrent-gobo-cues :t1 (show/fixtures-named "torrent-1") (+ y-base 7) (+ x-base 8))
    (make-torrent-gobo-cues :t2 (show/fixtures-named "torrent-2") (+ y-base 7) (+ x-base 12))))

(defn make-ambient-cues
  "Create a page of cues for controlling lasers, and ambient effects
  like the H2O LED and black light.

  Also holds cues for turning on sound active mode when the show
  operator wants to let things take care of themselves for a while,
  and doesn't mind losing the ability to control show brightness via
  dimmer masters."
  [page-x page-y]
  (let [x-base (* page-x 8)
        y-base (* page-y 8)]

    ;; Various ultraviolet options. Start by defining the pair of effects needed to turn the hex fixtures on
    ;; in full UV mode.
    (let [hex-uv-fx [(chan-fx/function-effect "Hex UV" :uv 100 (show/fixtures-named "hex"))
                     (dimmer-effect 255 (show/fixtures-named "hex")
                                    :effect-name "Hex Dimmers for UV")]]
      (show/set-cue! (+ x-base 2) (+ y-base 0)
                     (cues/cue :hex-uv (fn [_] (apply fx/scene "Hex UV" hex-uv-fx))
                               :color :purple :end-keys [:uv]))

      (show/set-cue! (+ x-base 1) (+ y-base 0)
                     (cues/function-cue :eco-uv :on (show/fixtures-named :eco-uv)
                                        :color :purple :effect-name "Eco UV Bar" :end-keys [:uv]))

      (show/set-cue! (+ x-base 0) (+ y-base 0)
                     (cues/cue :uv (fn [_]
                                     (apply fx/scene "All UV"
                                            (conj hex-uv-fx
                                                  (chan-fx/function-effect "Eco UV Bar" :on 100
                                                                           (show/fixtures-named :eco-uv)))))
                               :color :purple :end-keys [:eco-uv :hex-uv])))

    ;; Turn on the H2O LED
    (show/set-cue! (+ x-base 7) (+ y-base 0)
                   (cues/function-cue :h2o-led :on (show/fixtures-named :h2o-led) :effect-name "H2O LED"))

    ;; Control the Hypnotic RGB Laser
    (show/set-cue! (+ x-base 0) (+ y-base 3)
                   (cues/function-cue :hypnotic-beam :beam-red (show/fixtures-named "hyp-rgb")
                                      :color :red :effect-name "Hypnotic Red"))
    (show/set-cue! (+ x-base 1) (+ y-base 3)
                   (cues/function-cue :hypnotic-beam :beam-green (show/fixtures-named "hyp-rgb")
                                      :color :green :effect-name "Hypnotic Green"))
    (show/set-cue! (+ x-base 2) (+ y-base 3)
                   (cues/function-cue :hypnotic-beam :beam-blue (show/fixtures-named "hyp-rgb")
                                      :color :blue :effect-name "Hypnotic Blue"))
    (show/set-cue! (+ x-base 3) (+ y-base 3)
                   (cues/function-cue :hypnotic-beam :beam-red-green (show/fixtures-named "hyp-rgb")
                                      :color :yellow :effect-name "Hypnotic Red Green"))
    (show/set-cue! (+ x-base 4) (+ y-base 3)
                   (cues/function-cue :hypnotic-beam :beam-red-blue (show/fixtures-named "hyp-rgb")
                                      :color :purple :effect-name "Hypnotic Red Blue"))
    (show/set-cue! (+ x-base 5) (+ y-base 3)
                   (cues/function-cue :hypnotic-beam :beam-green-blue (show/fixtures-named "hyp-rgb")
                                      :color :cyan :effect-name "Hypnotic Green Blue"))
    (show/set-cue! (+ x-base 6) (+ y-base 3)
                   (cues/function-cue :hypnotic-beam :beam-red-green-blue (show/fixtures-named "hyp-rgb")
                                      :color :white :effect-name "Hypnotic Red Green Blue"))
    (show/set-cue! (+ x-base 7) (+ y-base 3)
                   (cues/function-cue :hypnotic-beam :beam-all-random (show/fixtures-named "hyp-rgb")
                                      :color :white :effect-name "Hypnotic Random"))
    (show/set-cue! (+ x-base 6) (+ y-base 4)
                   (cues/function-cue :hypnotic-spin :beams-ccw (show/fixtures-named "hyp-rgb")
                                      :color :cyan :effect-name "Hypnotic Rotate CCW" :level 50))
    (show/set-cue! (+ x-base 7) (+ y-base 4)
                   (cues/function-cue :hypnotic-spin :beams-cw (show/fixtures-named "hyp-rgb")
                                      :color :cyan :effect-name "Hypnotic Rotate Clockwise" :level 50))

    ;; Sound active mode for groups of lights
    (show/set-cue! (+ x-base 2) (+ y-base 7)
                   (cues/function-cue :blade-sound :sound-active (show/fixtures-named "blade")
                                      :color :orange :effect-name "Blade Sound"))
    (show/set-cue! (+ x-base 4) (+ y-base 7)
                   (cues/function-cue :hex-sound :sound-active (show/fixtures-named "hex")
                                      :color :orange :effect-name "Hex Sound"))
    (show/set-cue! (+ x-base 5) (+ y-base 7)
                   (cues/function-cue :puck-sound :sound-active (show/fixtures-named "puck")
                                      :color :orange :effect-name "Puck Sound"))
    (show/set-cue! (+ x-base 6) (+ y-base 7)
                   (cues/function-cue :snowball-sound :sound-active (show/fixtures-named "snowball")
                                      :color :orange :effect-name "Snowball Sound"))))

(defn make-cues
  "Create a bunch of example cues for experimentation."
  []
  (make-main-color-dimmer-cues 0 0)  ; Creates a sigle 8x8 page at the origin
  (make-torrent-cues 0 2)  ; Creates 2 8x8 pages: two pages up from the origin, and the next page to the right
  (make-ambient-cues 1 0)  ; Creates a single 8x8 page to the right of the origin

  ;; Not sure what to do with the rest of this yet! Move or discard...
  ;; For now they are hardcoded to appear on the page above the origin.

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
    (show/set-cue! 0 9
                   (cues/cue :movement (fn [var-map]
                                         (move/direction-effect "Can Can" can-can-dir (show/all-fixtures)))))
    (show/set-cue! 1 9
                   (cues/cue :movement (fn [var-map]
                                         (move/pan-tilt-effect "P/T Can Can" can-can-p-t (show/all-fixtures))))))

  (show/set-cue! 2 9
                 (cues/cue :movement (fn [var-map]
                                       (fun/twirl (concat (show/fixtures-named "blade")
                                                          (show/fixtures-named "torrent"))))
                           :color :green))

  ;; A couple snowball cues
  (show/set-cue! 0 10 (cues/function-cue :sb-pos :beams-fixed (show/fixtures-named "snowball")
                                         :effect-name "Snowball Fixed"))
  (show/set-cue! 1 10 (cues/function-cue :sb-pos :beams-moving (show/fixtures-named "snowball")
                                         :effect-name "Snowball Moving"))

  ;; TODO: Write a function to create direction cues, like function cues? Unless macro solves.
  (show/set-cue! 0 8
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
  (show/set-cue! 1 8 (cues/cue :torrent-dir (fn [var-map]
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
  (show/set-cue! 3 8 (cues/function-cue :blade-speed :movement-speed (show/fixtures-named "blade")
                                        :color :purple :effect-name "Slow Blades"))

  ;; Some fades
  (show/set-cue! 0 12 (cues/cue :color-fade (fn [var-map]
                                              (fx/fade "Color Fade"
                                                       (global-color-effect :red :include-color-wheels? true)
                                                       (global-color-effect :green :include-color-wheels? true)
                                                       (params/bind-keyword-param (:phase var-map 0) Number 0)))
                                :variables [{:key "phase" :min 0.0 :max 1.0 :start 0.0 :name "Fade"}]
                                :color :yellow))

  (show/set-cue! 1 12 (cues/cue :fade-test (fn [var-map]
                                             (fx/fade "Fade Test"
                                                      (fx/blank)
                                                      (global-color-effect :blue :include-color-wheels? true)
                                                      (params/bind-keyword-param (:phase var-map 0) Number 0)))
                                :variables [{:key "phase" :min 0.0 :max 1.0 :start 0.0 :name "Fade"}]
                                :color :cyan))

  (show/set-cue! 2 12
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

  (show/set-cue! 3 12 (cues/cue :fade-test-3 (fn [var-map]
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
  (show/set-cue! 0 13
                 (cues/cue :chase (fn [var-map]
                                    (fx/chase "Chase Test"
                                              [(global-color-effect :red :include-color-wheels? true)
                                               (global-color-effect :green :fixtures (show/fixtures-named "hex"))
                                               (global-color-effect :blue :include-color-wheels? true)]
                                              (params/bind-keyword-param (:position var-map 0) Number 0)
                                              :beyond :bounce))
                           :variables [{:key "position" :min -0.5 :max 10.5 :start 0.0 :name "Position"}]
                           :color :purple))

  ;; Set up an initial value for our step parameter
  (reset! step-param (params/build-step-param :fade-fraction 0.3 :fade-curve :sine))

  (show/set-cue! 1 13
                 (cues/cue :chase (fn [var-map]
                                    (fx/chase "Chase Test 2"
                                              [(global-color-effect :red :fixtures (show/fixtures-named "hex"))
                                               (global-color-effect :green :fixtures (show/fixtures-named "blade"))
                                               (global-color-effect :blue :fixtures (show/fixtures-named "hex"))
                                               (global-color-effect :white :fixtures (show/all-fixtures))]
                                              @step-param :beyond :loop))
                           :color :magenta))

  ;; Some color cycle chases
  (show/set-cue! 0 15 (cues/cue :all-color (fn [_] (fun/iris-out-color-cycle-chase (show/all-fixtures)))))
  (show/set-cue! 1 15 (cues/cue :all-color
                                (fn [_] (fun/wipe-right-color-cycle-chase
                                         (show/all-fixtures)
                                         :transition-phase-function rhythm/snapshot-bar-phase))))
  (show/set-cue! 2 15 (cues/cue :all-color (fn [_] (fun/wipe-right-color-cycle-chase
                                                    (show/all-fixtures)
                                                    :color-index-function rhythm/snapshot-beat-within-phrase
                                                    :transition-phase-function rhythm/snapshot-beat-phase
                                                    :effect-name "Wipe Right Beat")))))

(defn use-push
  "A trivial reminder of how to connect the Ableton Push to run the
  show. But also sets up the cues, if you haven't yet."
  [& {:keys [device-filter refresh-interval display-name]
           :or {device-filter "User Port"
                refresh-interval (/ 1000 15)
                display-name "Ableton Push"}}]
  (make-cues)
  (push/auto-bind *show* :device-filter device-filter :refresh-interval refresh-interval :display-name display-name))
