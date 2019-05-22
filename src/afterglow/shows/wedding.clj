(ns afterglow.shows.wedding
  "Cues for Joey and Courtney's wedding reception. Useful as an example
  of how an actual small show was put together using a subset of the
  Deep Symmetry lighting rig, and perhaps as a source of effects that
  may want to make their way to a more central place"
  {:author "James Elliott"}
  (:require [afterglow.channels :as chan]
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
            [amalloy.ring-buffer :refer [ring-buffer]]
            [clojure.math.numeric-tower :as math]
            [com.evocomputing.colors :as colors :refer [color-name create-color hue adjust-hue]]
            [taoensso.timbre :as timbre])
  (:import [afterglow.effects Effect]
           [javax.media.j3d Transform3D]
           [javax.vecmath Point3d Vector3d]))

(defonce ^{:doc "Allows effects to set variables in the running show."}
  var-binder
  (atom nil))

(defonce ^{:doc "Holds the show if it has been created,
  so it can be unregistered if it is being re-created."}
  wedding-show
  (atom nil))

(def rig-height
  "The height of the center of the T-bar of the tripod holding the
  moving heads as set up in the current venue."
  (tf/inches 88))

(def stage-wall
  "The location of the wall behind the rig on the show Z axis."
  -0.66)

(def house-rear-wall
  "The location of the wall behind the audience on the show Z axis."
  6.29)

(def left-wall
  "The location of the house left wall on the show X axis."
  -1.47)

(def right-wall
  "The location of the house right wall on the show X axis."
  1.52)

(def ceiling
  "The location of the ceiling on the show Y axis."
  2.71)

(defn patch-other-lights
  "Patches the Weather Systems and Hex IRCs, which are going to be
  separately positioned so this will need to be hand-edited to reflect
  their actual locations before using the show."
  [& {:keys [universe]
      :or   {universe 1}}]

  ;; Hex IRC mini RGBAW+UV pars
  (show/patch-fixture! :hex-1 (chauvet/slimpar-hex3-irc) universe 129
                       :x (tf/feet 6) :y (tf/inches 5) :z (tf/feet 4))
  (show/patch-fixture! :hex-2 (chauvet/slimpar-hex3-irc) universe 145
                       :x (tf/feet -6) :y (tf/inches 5) :z (tf/feet 4))

  ;; Weather Systems
  (show/patch-fixture! :ws-1 (blizzard/weather-system) universe 161
                       :x (tf/inches 43) :y (tf/inches 73) :z 0.0 :y-rotation (tf/degrees -30.0))
  (show/patch-fixture! :ws-2 (blizzard/weather-system) universe 187
                       :x (tf/inches -40.0) :y (tf/inches 75) :z 0.0 :y-rotation (tf/degrees 30.0)))

(defn patch-torrents-and-snowball
  "Patches the moving heads which are on the larger tripod stand, which
  defines the origin of the show, the spot on the floor underneath the
  center of the T-bar. The _z_ axis increases towards the audience
  from that point.

  Because the height of the tripod can be adjusted, you can pass in a
  value with `:y` to set the height of the center of the T-bar. If
  omitted a default height of 88 inches is used, which is the height
  to which it was raised during our rehearsals.

  Fixture numbers are assigned stage left to stage right (looking at
  the lights from behind)."
  [& {:keys [universe y]
      :or   {universe 1 y (tf/inches 88)}}]

  ;; Torrent F3 moving head effect spots
  (show/patch-fixture! :torrent-1 (blizzard/torrent-f3) universe 1
                       :x (tf/inches 15) :y (+ y (tf/inches -14))
                       :x-rotation (tf/degrees 180)
                       :y-rotation (tf/degrees -90))
  (show/patch-fixture! :torrent-2 (blizzard/torrent-f3) universe 17
                       :x (tf/inches -15) :y (+ y (tf/inches -14))
                       :x-rotation (tf/degrees 180)
                       :y-rotation (tf/degrees -90))

  ;; Snowball RGBA moonflower effect
  (show/patch-fixture! :snowball (blizzard/snowball) universe 33
                       :x (tf/inches 4.5) :y (+ y (tf/inches 2))))

(declare make-cues)

(defn use-wedding-show
  "Set up the show for the wedding reception. By default it will create
  the show to use universe 1, but if you want to use a different
  universe (for example, a dummy universe on ID 0, because your DMX
  interface isn't handy right now), you can override that by supplying
  a different ID after `:universe`."
  [& {:keys [universe] :or {universe 1}}]

  ;; Since this class is an entry point for interactive REPL usage,
  ;; make sure a sane logging environment is established.
  (core/init-logging)

  ;; Create, or re-create the show, on the chosen OLA universe. Make
  ;; it the default show so we don't need to wrap everything below in
  ;; a (with-show wedding-show ...) binding
  (set-default-show! (swap! wedding-show (fn [s]
                                          (when s
                                            (show/unregister-show s)
                                            (with-show s
                                              (show/stop!)
                                              (show/blackout-show)))
                                          (show/show :universes [1]
                                                     :description "Joey + Courtney Show"))))

  (patch-torrents-and-snowball :universe universe :y rig-height)
  (patch-other-lights :universe universe)

  ;; Enable cues whose purpose is to set show variable values while they run.
  (reset! var-binder (var-fx/create-for-show *show*))

  ;; Create a bunch of cues
  (make-cues)

  ;; Automatically bind the show to any compatible grid controllers that are connected now
  ;; or in the future.
  (ct/auto-bind *show*)

  ;; Return the symbol through which the show can be accessed, rather than the value itself,
  ;; which is huge and causes issues for some REPL environments.
  '*show*)

(defn global-color-effect
  "Make a color effect which affects all lights in the show.
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
  "Return an effect that sets all the dimmers in the show.
  Originally this had to be to a static value, but now that dynamic
  parameters exist, it can vary in response to a MIDI mapped show
  variable, an oscillator, or the location of the fixture. You can
  override the default name by passing in a value with :effect-name"
  [level & {:keys [effect-name add-virtual-dimmers?]}]
  (let [htp? (not add-virtual-dimmers?)]
    (dimmer-effect level (show/all-fixtures) :effect-name effect-name :htp? htp?
                   :add-virtual-dimmers? add-virtual-dimmers?)))

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
  [:torrent :ws :hex :snowball])

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

(defn- make-sawtooth-dimmer-param
  "Creates the sawtooth oscillated parameter used by
  `make-sawtooth-dimmer-cue` for both the actual effect and its
  visualizer."
  [var-map]
  (oscillators/build-oscillated-param (oscillators/sawtooth :down? (:down var-map)
                                                            :interval-ratio (build-ratio-param var-map)
                                                            :phase (:phase var-map))
                                      :min (:min var-map) :max (:max var-map)))

(defn make-sawtooth-dimmer-cue
  "Create a cue which applies a sawtooth oscillator to the dimmers of
  the specified group of fixtures, with cue variables to adjust the
  oscillator parameters."
  [group x y color]
  (let [[effect-key fixtures end-keys effect-name] (build-group-cue-elements group "dimmers" "saw")]
    (show/set-cue! x y
                   (cues/cue effect-key
                             (fn [var-map] (dimmer-effect (make-sawtooth-dimmer-param var-map) fixtures
                                            :effect-name effect-name))
                             :color color
                             :variables [{:key "beats" :min 1 :max 32 :type :integer :start 2 :name "Beats"}
                                         {:key "down" :type :boolean :start true :name "Down?"}
                                         {:key "cycles" :min 1 :max 10 :type :integer :start 1 :name "Cycles"}
                                         {:key "phase" :min 0 :max 1 :start 0 :name "Phase"}
                                         {:key "min" :min 0 :max 255 :start 0 :name "Min"}
                                         {:key "max" :min 0 :max 255 :start 255 :name "Max"}]
                             :visualizer (fn [var-map show]
                                           (let [p (make-sawtooth-dimmer-param var-map)]
                                             (fn [snapshot]
                                               (/ (params/evaluate p show snapshot nil) 255.0))))
                             :end-keys end-keys))))

(defn- make-triangle-dimmer-param
  "Creates the triangle oscillated parameter used by
  `make-triangle-dimmer-cue` for both the actual effect and its
  visualizer."
  [var-map]
  (oscillators/build-oscillated-param (oscillators/triangle :interval-ratio (build-ratio-param var-map)
                                                            :phase (:phase var-map))
                                      :min (:min var-map) :max (:max var-map)))

(defn make-triangle-dimmer-cue
  "Create a cue which applies a triangle oscillator to the dimmers of
  the specified set of fixtures, with cue variables to adjust the
  oscillator parameters."
  [group x y color]
  (let [[effect-key fixtures end-keys effect-name] (build-group-cue-elements group "dimmers" "triangle")]
    (show/set-cue! x y
                   (cues/cue effect-key
                             (fn [var-map] (dimmer-effect (make-triangle-dimmer-param var-map) fixtures
                                                          :effect-name effect-name))
                             :color color
                             :variables [{:key "beats" :min 1 :max 32 :type :integer :start 2 :name "Beats"}
                                         {:key "cycles" :min 1 :max 10 :type :integer :start 1 :name "Cycles"}
                                         {:key "phase" :min 0 :max 1 :start 0 :name "Phase"}
                                         {:key "min" :min 0 :max 255 :start 0 :name "Min"}
                                         {:key "max" :min 0 :max 255 :start 255 :name "Max"}]
                             :visualizer (fn [var-map show]
                                           (let [p (make-triangle-dimmer-param var-map)]
                                             (fn [snapshot]
                                               (/ (params/evaluate p show snapshot nil) 255.0))))
                             :end-keys end-keys))))

(defn- make-sine-dimmer-param
  "Creates the sine oscillated parameter used by
  `make-sine-dimmer-cue` for both the actual effect and its
  visualizer."
  [var-map]
  (oscillators/build-oscillated-param (oscillators/sine :interval-ratio (build-ratio-param var-map)
                                                        :phase (:phase var-map))
                                      :min (:min var-map) :max (:max var-map)))

(defn make-sine-dimmer-cue
  "Create a cue which applies a sine oscillator to the dimmers of the
  specified set of fixtures, with cue variables to adjust the
  oscillator parameters."
  [group x y color]
  (let [[effect-key fixtures end-keys effect-name] (build-group-cue-elements group "dimmers" "sine")]
    (show/set-cue! x y
                   (cues/cue effect-key
                             (fn [var-map] (dimmer-effect (make-sine-dimmer-param var-map) fixtures
                                                          :effect-name effect-name))
                             :color color
                             :variables [{:key "beats" :min 1 :max 32 :type :integer :start 2 :name "Beats"}
                                         {:key "cycles" :min 1 :max 10 :type :integer :start 1 :name "Cycles"}
                                         {:key "phase" :min 0 :max 1 :start 0 :name "Phase"}
                                         {:key "min" :min 0 :max 255 :start 1 :name "Min"}
                                         {:key "max" :min 0 :max 255 :start 255 :name "Max"}]
                             :visualizer (fn [var-map show]
                                           (let [p (make-sine-dimmer-param var-map)]
                                             (fn [snapshot]
                                               (/ (params/evaluate p show snapshot nil) 255.0))))
                             :end-keys end-keys))))

(defn- make-square-dimmer-param
  "Creates the square oscillated parameter used by
  `make-square-dimmer-cue` for both the actual effect and its
  visualizer."
  [var-map]
  (oscillators/build-oscillated-param (oscillators/square :interval-ratio (build-ratio-param var-map)
                                                          :width (:width var-map)
                                                          :phase (:phase var-map))
                                      :min (:min var-map) :max (:max var-map)))

(defn make-square-dimmer-cue
  "Create a cue which applies a square oscillator to the dimmers of
  the specified set of fixtures, with cue variables to adjust the
  oscillator parameters."
  [group x y color]
  (let [[effect-key fixtures end-keys effect-name] (build-group-cue-elements group "dimmers" "square")]
    (show/set-cue! x y
                   (cues/cue effect-key
                             (fn [var-map] (dimmer-effect (make-square-dimmer-param var-map) fixtures
                                                          :effect-name effect-name))
                             :color color
                             :variables [{:key "beats" :min 1 :max 32 :type :integer :start 2 :name "Beats"}
                                         {:key "width" :min 0 :max 1 :start 0.5 :name "Width"}
                                         {:key "cycles" :min 1 :max 10 :type :integer :start 1 :name "Cycles"}
                                         {:key "phase" :min 0 :max 1 :start 0 :name "Phase"}
                                         {:key "min" :min 0 :max 255 :start 0 :name "Min"}
                                         {:key "max" :min 0 :max 255 :start 255 :name "Max"}]
                             :visualizer (fn [var-map show]
                                           (let [p (make-square-dimmer-param var-map)]
                                             (fn [snapshot]
                                               (/ (params/evaluate p show snapshot nil) 255.0))))
                             :end-keys end-keys))))

(defn x-phase
  "Return a value that ranges from zero for the leftmost fixture in a
  show to 1 for the rightmost, for staggering the phase of an
  oscillator in making a can-can chase."
  [head show]
  (let [dimensions @(:dimensions *show*)]
    (/ (- (:x head) (:min-x dimensions)) (- (:max-x dimensions) (:min-x dimensions)))))

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
                        {:key "cycles" :min 1 :max 10 :type :integer :start 1 :name "Cycles"}
                        {:key "phase" :min 0 :max 1 :start 0 :name "Phase"}]))

(defn make-main-color-dimmer-cues
  "Creates a page of cues that assign dimmers and colors to the
  lights. This is probably going to be assigned as the first page, but
  can be moved by passing non-zero values for `page-x` and `page-y`."
  [page-x page-y]
  (let [x-base (* page-x 8)
        y-base (* page-y 8)
        rig-left (:x (first (show/fixtures-named :hex-2)))
        rig-right (:x (first (show/fixtures-named :hex-1)))
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
    (make-color-cue "white" x-base y-base :include-color-wheels? true
                    :fixtures (show/all-fixtures) :effect-key :all-color :effect-name "Color all")
    (doall (map-indexed (fn [i group]
                          (make-color-cue "white" (+ x-base (inc i)) y-base :include-color-wheels? true
                                          :fixtures (show/fixtures-named group)
                                          :effect-key (keyword (str (name group) "-color"))
                                          :effect-name (str "Color " (name group))
                                          :priority 1))
                        light-groups))

    ;; Some special/fun cues
    (show/set-variable! :rainbow-saturation 100)
    (show/set-cue! x-base (inc y-base)
                   (let [color-param (params/build-color-param :s :rainbow-saturation :l 50 :h hue-bar)]
                     (cues/cue :all-color (fn [_] (global-color-effect color-param))
                               :color-fn (cues/color-fn-from-param color-param)
                               :short-name "Rainbow Bar Fade"
                               :variables [{:key :rainbow-saturation :name "Saturatn" :min 0 :max 100 :start 100
                                            :type :integer}])))
    (show/set-cue! (inc x-base) (inc y-base)
                   (cues/cue :all-color (fn [_] (global-color-effect
                                                 (params/build-color-param :s :rainbow-saturation :l 50
                                                                           :h rig-hue-gradient)
                                                 :include-color-wheels? true))
                             :short-name "Rainbow Rig"
                             :variables [{:key :rainbow-saturation :name "Saturatn" :min 0 :max 100 :start 100
                                          :type :integer}]))
    (show/set-cue! (+ x-base 2) (inc y-base)
                   (let [color-param (params/build-color-param :s :rainbow-saturation :l 50 :h hue-gradient
                                                               :adjust-hue hue-bar)]
                     (cues/cue :all-color (fn [_] (global-color-effect color-param))
                               :color-fn (cues/color-fn-from-param color-param)
                               :short-name "Rainbow Grid+Bar"
                               :variables [{:key :rainbow-saturation :name "Saturatn" :min 0 :max 100 :start 100
                                            :type :integer}])))
    (show/set-cue! (+ x-base 3) (inc y-base) ; Desaturate the rainbow as each beat progresses
                   (let [color-param (params/build-color-param :s desat-beat :l 50 :h hue-gradient
                                                               :adjust-hue hue-bar)]
                     (cues/cue :all-color (fn [_] (global-color-effect color-param))
                               :color-fn (cues/color-fn-from-param color-param)
                               :short-name "Rainbow Pulse")))

    (show/set-cue! (+ x-base 4) (inc y-base)
                   (cues/cue :all-color (fn [_] (global-color-effect
                                                 (params/build-color-param :s 100 :l 50 :h hue-z-gradient)
                                                 :include-color-wheels? true))
                             :short-name "Z Rainbow Grid"))

    (show/set-cue! (+ x-base 7) (inc y-base)
                   (cues/cue :transform-colors (fn [_] (color-fx/transform-colors (show/all-fixtures)))
                             :priority 1000 :short-name "Beat Bleach"))

    ;; The fun sparkle cue
    (show/set-cue! (+ x-base 7) (+ y-base 2)
                   (cues/cue :sparkle (fn [var-map]
                                        (cues/apply-merging-var-map var-map fun/sparkle (show/all-fixtures)))
                             :held true
                             :priority 100
                             :variables [{:key "chance" :min 0.0 :max 0.4 :start 0.05 :velocity true}
                                         {:key "fade-time" :name "Fade" :min 1 :max 2000 :start 50 :type :integer}]))

    ;; Dimmer cues to turn on and set brightness of groups of lights
    (make-dimmer-cue nil x-base (+ y-base 2) :yellow)
    (doall (map-indexed (fn [i group] (make-dimmer-cue group (+ x-base (inc i)) (+ y-base 2) :yellow)) light-groups))

    ;; Dimmer oscillator cues: Sawtooth
    (make-sawtooth-dimmer-cue nil x-base (+ y-base 3) :yellow)
    (doall (map-indexed (fn [i group]
                          (make-sawtooth-dimmer-cue group (+ x-base (inc i)) (+ y-base 3) :orange)) light-groups))

    ;; Dimmer oscillator cues: Triangle
    (make-triangle-dimmer-cue nil x-base (+ y-base 4) :orange)
    (doall (map-indexed (fn [i group]
                          (make-triangle-dimmer-cue group (+ x-base (inc i)) (+ y-base 4) :red)) light-groups))

    ;; Dimmer oscillator cues: Sine
    (make-sine-dimmer-cue nil x-base (+ y-base 5) :cyan)
    (doall (map-indexed (fn [i group]
                          (make-sine-dimmer-cue group (+ x-base (inc i)) (+ y-base 5) :blue)) light-groups))

    ;; Dimmer oscillator cues: Square
    (make-square-dimmer-cue nil x-base (+ y-base 6) :cyan)
    (doall (map-indexed (fn [i group]
                          (make-square-dimmer-cue group (+ x-base (inc i)) (+ y-base 6) :green)) light-groups))

    ;; Strobe cues
    (make-strobe-cue-2 "All" (show/all-fixtures) x-base (+ y-base 7))
    (make-strobe-cue-2 "Torrents" (show/fixtures-named "torrent") (inc x-base) (+ y-base 7))
    (make-strobe-cue-2 "Weather Systems" (show/fixtures-named "ws") (+ x-base 2) (+ y-base 7))
    (make-strobe-cue-2 "Hexes" (show/fixtures-named "hex") (+ x-base 3) (+ y-base 7))
    (make-strobe-cue-2 "Snowball" (show/fixtures-named "snowball") (+ x-base 4) (+ y-base 7))

    (let [color-var {:key :strobe-color :type :color :name "Strobe Color"}]
      (show/set-cue! (+ x-base 5) (+ y-base 7) (cues/cue :strobe-color (fn [_] (fx/blank "Strobe Color"))
                                                         :color :purple
                                                         :color-fn (cues/color-fn-from-cue-var color-var)
                                                         :variables [color-var])))))

(defn make-torrent-cues
  "Create a page of cues for configuring aspects of the Torrent F3s
  and another to its right for their gobo selection."
  [page-x page-y]
  (let [x-base (* page-x 8)
        y-base (* page-y 8)]
    (show/set-cue! x-base (+ y-base 7)
                   (cues/function-cue :torrent-shutter :shutter-open (show/fixtures-named "torrent")))
    (show/set-cue! (inc x-base) (+ y-base 7)
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
    (show/set-cue! (+ x-base 6) (inc y-base)
                   (cues/function-cue :t1-gobo-rotation :gobo-rotation-clockwise (show/fixtures-named "torrent-1")
                                      :effect-name "T1 Spin Gobo CW" :color (create-color :cyan) :level 100))
    (show/set-cue! (+ x-base 7) (inc y-base)
                   (cues/function-cue :t2-gobo-rotation :gobo-rotation-clockwise (show/fixtures-named "torrent-2")
                                      :effect-name "T2 Spin Gobo CW" :color (create-color :cyan) :level 100))
    (show/set-cue! (+ x-base 6) y-base
                   (cues/function-cue :t1-gobo-rotation :gobo-rotation-counterclockwise
                                      (show/fixtures-named "torrent-1")
                                      :effect-name "T1 Spin Gobo CCW" :color (create-color :cyan)))
    (show/set-cue! (+ x-base 7) y-base
                   (cues/function-cue :t2-gobo-rotation :gobo-rotation-counterclockwise
                                      (show/fixtures-named "torrent-2")
                                      :effect-name "T2 Spin Gobo CCW" :color (create-color :cyan)))

    ;; Simple color wheel settings for each Torrent
    (dotimes [x 2]
      (doall (map-indexed (fn [y color]
                            (make-color-cue color (+ x-base x) (+ y-base y) :include-color-wheels? true
                                            :fixtures (show/fixtures-named (str "torrent-" (inc x)))
                                            :effect-key (keyword (str "torrent-" (inc x) "-color"))
                                            :effect-name (str "Torrent " (inc x) " " color)
                                            :priority 2))
                          ["red" "blue" "green" "yellow" "magenta" "cyan" "orange"])))


    ;; Some compound cues
    (show/set-cue! (+ x-base 2) y-base
                   (cues/cue :star-swirl (fn [_] (cues/compound-cues-effect
                                                  "Star Swirl" *show* [[(+ x-base 8) (+ y-base 4)]
                                                                       [(+ x-base 10) (inc y-base)]
                                                                       [(+ x-base 6) (+ y-base 7) {:level 60}]
                                                                       [(+ x-base 6) y-base {:level 25}]]))))

    (make-torrent-gobo-cues :t1 (show/fixtures-named "torrent-1") (+ y-base 7) (+ x-base 8))
    (make-torrent-gobo-cues :t2 (show/fixtures-named "torrent-2") (+ y-base 7) (+ x-base 12))))

(defn circle-chain
  "Create a chase that generates a series of circles on either the
  floor or the ceiling, causing a single head to trace out each, and
  passing them along from head to head.

  The number of bars taken to trace out each circle defaults to 2 and
  can be adjusted by passing a different value with the optional
  keyword argument `:bars`. The radius of each circle defaults to one
  meter, and can be adjusted with `:radius`. If you want each head to
  be tracing a different position in its circle, you can pass a value
  between zero and one with `:stagger`."
  [fixtures ceiling? & {:keys [bars radius stagger] :or {bars 2 radius 1.0 stagger 0.0}}]
  (let [bars (params/bind-keyword-param bars Number 2)
        radius (params/bind-keyword-param radius Number 1.0)
        stagger (params/bind-keyword-param stagger Number 0.0)
        snapshot (rhythm/metro-snapshot (:metronome *show*))
        bars (params/resolve-unless-frame-dynamic bars *show* snapshot)
        radius (params/resolve-unless-frame-dynamic radius *show* snapshot)
        stagger (params/resolve-unless-frame-dynamic stagger *show* snapshot)
        step (params/build-step-param :interval :bar :interval-ratio bars)
        phase-osc (oscillators/sawtooth :interval :bar :interval-ratio bars)
        width (- right-wall left-wall)
        front (if ceiling? 0.5 stage-wall)  ; The blades can't reach behind the rig
        depth (- house-rear-wall front)
        y (if ceiling? ceiling 0.0)
        heads (sort-by :x (move/find-moving-heads fixtures))
        points (ref (ring-buffer (count heads)))
        running (ref true)
        current-step (ref nil)]
    ;; It would also be possible to build this using a scene with fomula parameters to create
    ;; the aiming points, but it is slightly more concise and direct to drop to implementing
    ;; the lower-level effect interface. If this is too deep for your current stage of Afterglow
    ;; learning, ignore it until you are ready to dig deeper into the rendering pipeline.
    (Effect. "Circle Chain"
             (fn [show snapshot]
               ;; Continue running until all circles are finished
               (dosync
                (or @running (seq @points))))
             (fn [show snapshot]
               (dosync
                (let [now (math/round (params/resolve-param step show snapshot))
                      phase (oscillators/evaluate phase-osc show snapshot nil)
                      stagger (params/resolve-param stagger show show snapshot)
                      head-phases (map #(* stagger %) (range))]
                  (when (not= now @current-step)
                    (ref-set current-step now)
                    (if @running  ;; Either add a new circle, or just drop the oldest
                      (alter points conj (Point3d. (+ left-wall (rand width)) y (+ front (rand depth))))
                      (alter points pop)))
                  (map (fn [head point head-phase]
                         (let [radius (params/resolve-param radius show snapshot head)
                               theta (* 2.0 Math/PI (+ phase head-phase))
                               head-point (Point3d. (+ (.x point) (* radius (Math/cos theta)))
                                                    (.y point)
                                                    (+ (.z point) (* radius (Math/sin theta))))]
                           (fx/build-head-parameter-assigner :aim head head-point show snapshot)))
                       heads @points head-phases))))
             (fn [show snapshot]
                 ;; Stop making new circles, and shut down once all exiting ones have ended.
                 (dosync (ref-set running false))))))

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
      (show/set-cue! (+ x-base 2) y-base
                     (cues/cue :hex-uv (fn [_] (apply fx/scene "Hex UV" hex-uv-fx))
                               :color :purple :end-keys [:uv])))

    ;; Sound active mode for groups of lights
    (show/set-cue! (+ x-base 4) (+ y-base 7)
                   (cues/cue :hex-sound
                             (fn [var-map]
                               (fx/scene "Hex Sound"
                                         (chan-fx/function-effect
                                          "sound on" :sound-active (:level var-map) (show/fixtures-named "hex"))
                                         (dimmer-effect (:dimmer var-map) (show/fixtures-named "hex"))))
                             :color :orange
                             :variables [{:key "level" :min 1 :max 100 :type :integer :start 50 :name "Level"}
                                         {:key "dimmer" :min 0 :max 255 :type :integer :start 255 :name "Dimmer"}]))

    (show/set-cue! (+ x-base 6) (+ y-base 7)
                   (cues/function-cue :snowball-sound :sound-active (show/fixtures-named "snowball")
                                      :color :orange :effect-name "Snowball Sound" :end-keys [:snowball-pos]))

    ;; A couple other snowball cues
    (show/set-cue! (+ x-base 6) (+ y-base 6)
                   (cues/function-cue :snowball-pos :beams-moving (show/fixtures-named "snowball")
                                      :effect-name "Snowball Moving" :color :yellow :end-keys [:snowball-sound]))
    (show/set-cue! (+ x-base 6) (+ y-base 5)
                   (cues/function-cue :snowball-pos :beams-fixed (show/fixtures-named "snowball")
                                      :effect-name "Snowball Fixed" :end-keys [:snowball-sound]))))

(defn torrent-8
  "A effect that moves the torrents in a figure 8."
  [& {:keys [bars cycles stagger spread pan-min pan-max tilt-min tilt-max]
      :or {bars 1 cycles 1 stagger 0 spread 0 pan-min -45 pan-max 45 tilt-min 0 tilt-max 45}}]
  (let [bars (params/bind-keyword-param bars Number 1)
        cycles (params/bind-keyword-param cycles Number 1)
        stagger (params/bind-keyword-param stagger Number 0)
        spread (params/bind-keyword-param spread Number 0)
        pan-min (params/bind-keyword-param pan-min Number -45)
        pan-max (params/bind-keyword-param pan-max Number 45)
        tilt-min (params/bind-keyword-param tilt-min Number 0)
        tilt-max (params/bind-keyword-param tilt-max Number 45)
        pan-ratio (params/build-param-formula Number #(/ (* 2 %1) %2) bars cycles)
        tilt-ratio (params/build-param-formula Number #(/ %1 %2) bars cycles)
        fx (for [head [{:key :torrent-1 :phase 0.0 :pan-offset 1}
                       {:key :torrent-2 :phase 0.5 :pan-offset -1}]]
             (let [head-phase (params/build-param-formula Number #(+ 0.25 (* % (:phase head 0))) stagger)
                   tilt-osc (oscillators/sine :interval :bar :interval-ratio tilt-ratio :phase head-phase)
                   tilt-param (oscillators/build-oscillated-param tilt-osc :min tilt-min :max tilt-max)
                   pan-osc (oscillators/sine :interval :bar :interval-ratio pan-ratio :phase head-phase)
                   head-pan-min (params/build-param-formula Number #(+ (if (zero? (:phase head)) %1 (- %2))
                                                                       (* (:pan-offset head 0) %3))
                                                            pan-min pan-max spread)
                   head-pan-max (params/build-param-formula Number #(+ (if (zero? (:phase head)) %1 (- %2))
                                                                       (* (:pan-offset head 0) %3))
                                                            pan-max pan-min spread)
                   pan-param (oscillators/build-oscillated-param pan-osc
                                                                 :min head-pan-min
                                                                 :max head-pan-max)
                   direction (params/build-pan-tilt-param :pan pan-param :tilt tilt-param)]
               (move/pan-tilt-effect "t8 element" direction (show/fixtures-named (:key head)))))]
    (apply fx/scene "Torrent 8" fx)))

(defn dimmer-sweep
  "An effect which uses an oscillator to move a bar of light across a
  group of fixtures. The width of the bar, maximum dimmer level, and
  whether the level should fade from the center of the bar to the
  edge, can be controlled with optional keyword arguments."
  [fixtures osc & {:keys [width level fade] :or {width 0.1 level 255 fade false}}]
  (let [width (params/bind-keyword-param width Number 0.1)
        level (params/bind-keyword-param level Number 255)
        fade (params/bind-keyword-param fade Boolean false)
        min-x (apply min (map :x fixtures))
        max-x (apply max (map :x fixtures))
        position (oscillators/build-oscillated-param osc :min min-x :max max-x)]
    (apply fx/scene "Dimmer Sweep"
           (for [fixture fixtures]
             (let [fixture-level (params/build-param-formula
                                  Number
                                  (fn [position width level fade]
                                    (let [range (/ width 2)
                                          distance (math/abs (- position (:x fixture)))]
                                      (if (> distance range)
                                        0
                                        (if fade
                                          (* level (/ (- range distance) range))
                                          level))))
                                  position width level fade)]
               (dimmer-effect fixture-level [fixture]))))))

(defn make-movement-cues
  "Create a page of with some large scale and layered movement
  effects. And miscellany which I'm not totally sure what to do with
  yet."
  [page-x page-y]
  (let [x-base (* page-x 8)
        y-base (* page-y 8)]

    ;; Some dimmer sweeps
    (let [dimmer-sweep-fixtures (concat (show/fixtures-named :torrent) (show/fixtures-named :hex))]
      (show/set-cue! x-base y-base
                     (cues/cue :dimmers
                               (fn [var-map] (cues/apply-merging-var-map
                                              var-map dimmer-sweep  dimmer-sweep-fixtures
                                              (oscillators/sawtooth :down? (:down var-map)
                                                                    :interval-ratio (build-ratio-param var-map))))
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
                                              var-map dimmer-sweep dimmer-sweep-fixtures
                                              (oscillators/triangle :interval-ratio (build-ratio-param var-map))))
                               :color :red :short-name "Triangle Sweep"
                               :variables [{:key "beats" :min 1 :max 32 :type :integer :start 2 :name "Beats"}
                                           {:key "cycles" :min 1 :max 10 :type :integer :start 1 :name "Cycles"}
                                           {:key "width" :min 0 :max 1 :start 0.25 :name "Width"}
                                           {:key "level" :min 0 :max 255 :start 255 :name "Level"}
                                           {:key "fade" :type :boolean :start false :name "Fade?"}])))


    ;; A fun pressure-sensitive dimmer spread effect
    (show/set-cue! x-base (+ y-base 2)
                   (cues/cue :bloom (fn [var-map]
                                      (cues/apply-merging-var-map
                                       var-map fun/bloom (show/all-fixtures)
                                       :measure (tf/build-distance-measure 0 rig-height 0 :ignore-z true)))
                             :variables [{:key "color" :type :color :start (colors/create-color :white)
                                          :name "Color"}
                                         {:key "fraction" :min 0 :max 1 :start 0 :velocity true}]
                             :held true :priority 1000 :color :purple))

    (show/set-cue! (+ x-base 2) (inc y-base)
                   (cues/cue :movement (fn [var-map]
                                         (cues/apply-merging-var-map var-map fun/aim-fan
                                                                     (show/fixtures-named "torrent")))
                             :variables [{:key "x-scale" :min -5 :max 5 :start 1 :name "X Scale"}
                                         {:key "y-scale" :min -10 :max 10 :start 5 :name "Y Scale"}
                                         {:key "z" :min 0 :max 20 :start 4}
                                       {:key "y" :min -10 :max 10 :start rig-height}
                                       {:key "x" :min -10 :max 10 :start 0.0}]
                           :color :blue :end-keys [:move-torrents]))

    (show/set-cue! (+ x-base 2) (+ y-base 2)
                   (cues/cue :movement (fn [var-map]
                                         (cues/apply-merging-var-map var-map fun/twirl
                                                                     (show/fixtures-named "torrent")))
                             :variables [{:key "beats" :min 1 :max 32 :type :integer :start 8 :name "Beats"}
                                         {:key "cycles" :min 1 :max 10 :type :integer :start 1 :name "Cycles"}
                                         {:key "radius" :min 0 :max 10 :start 0.25 :name "Radius"}
                                         {:key "z" :min -10 :max 10 :start -1.0}
                                         {:key "y" :min -10 :max 10 :start rig-height}
                                         {:key "x" :min -10 :max 10 :start 0.0}]
                             :color :green :end-keys [:move-torrents]))

    (show/set-cue! (inc x-base) (+ y-base 3)
                   (cues/cue :move-torrents
                             (fn [var-map] (cues/apply-merging-var-map var-map torrent-8))
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

    (show/set-cue! (inc x-base) (+ y-base 4)
                   (cues/cue :torrent-circles
                             (fn [var-map] (cues/apply-merging-var-map var-map circle-chain
                                                                       (show/fixtures-named :torrent) false))
                             :variables [{:key "bars" :name "Bars" :min 1 :max 8 :type :integer :start 2}
                                         {:key "radius" :name "Radius" :min 0.1 :max 2
                                          :resolution 0.1 :start 1.0}
                                         {:key "stagger" :name "Stagger" :min 0 :max 2 :start 0
                                          :resolution 0.1}]
                             :short-name "Torrent Circles" :color :green :priority 4))

    ;; Some color cycle chases
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
                             :color :orange :priority 5 :short-name "Confetti Dance"))

    (show/set-cue! (+ x-base 5) y-base
                   (cues/cue :pinstripes
                             (fn [var-map]
                               (let [step-ratio (build-ratio-param var-map)
                                     step (params/build-step-param :interval-ratio step-ratio
                                                                   :fade-fraction (:fade-fraction var-map))
                                     colors [(:color-1 var-map) (:color-2 var-map)]]
                                 (fun/pinstripes (clojure.set/difference
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
                               (let [step-ratio (build-ratio-param var-map)
                                     step (params/build-step-param :interval-ratio step-ratio
                                                                   :fade-fraction (:fade-fraction var-map))
                                     colors [(:color-1 var-map) (:color-2 var-map) (:color-3 var-map)]]
                                 (fun/pinstripes (clojure.set/difference
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
                               (let [step-ratio (build-ratio-param var-map)
                                     step (params/build-step-param :interval-ratio step-ratio
                                                                   :fade-fraction (:fade-fraction var-map))
                                     colors [(:color-1 var-map) (:color-2 var-map)
                                             (:color-3 var-map) (:color-4 var-map)]]
                                 (fun/pinstripes (clojure.set/difference
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
                             :color :orange :short-name "Pin 4"))

    ;; Some macro-based chases
    (show/set-cue! (+ x-base 7) (inc y-base)
                   (cues/cue :move-torrents
                             (fn [_] (cues/compound-cues-effect
                                      "Torrent Nod" *show*
                                      [[17 15 {:pan-min 90.0 :pan-max 179.0 :pan-bars 2 :pan-phase 0.0
                                               :tilt-min 148.0 :tilt-max 255.0 :tilt-bars 1, :tilt-phase 0.0}]
                                       [16 15 {:pan-min 77.0 :pan-max 164.0 :pan-bars 2 :pan-phase 0.0
                                               :tilt-min 148.0 :tilt-max 255.0, :tilt-bars 1, :tilt-phase 0.0}]]))
                             :end-keys [:movement]))

    (show/set-cue! (+ x-base 7) (+ y-base 2)
                   (cues/cue :move-torrents
                             (fn [_] (cues/compound-cues-effect
                                      "Torrent Cross Nod" *show*
                                      [[16 15 {:pan-min 77.0, :pan-max 164.0, :pan-bars 2, :pan-phase 0.5,
                                               :tilt-min 148.0, :tilt-max 255.0, :tilt-bars 1, :tilt-phase 0.25}]
                                       [17 15 {:pan-min 90.0, :pan-max 179.0, :pan-bars 2, :pan-phase 0.0,
                                               :tilt-min 148.0, :tilt-max 255.0, :tilt-bars 1, :tilt-phase 0.0}]]))
                             :end-keys [:movement]))))

(defn- aim-cue-var-key
  "Determine the cue variable key value to use for a variable being
  created for an aim cue page cue. `base-name` is the name that will
  be used for the variable if it is not part of a group of cues
  sharing variables; if `shared-prefix` is not blank then the variable
  key will refer to a show variable with that prefix identifying which
  group it belongs to."
  [base-name shared-prefix]
  (if (clojure.string/blank? shared-prefix)
    (name base-name)
    (keyword (str "aim-group-" shared-prefix "-" (name base-name)))))

(defn- build-aim-cue
  "Build an aim cue for the mutiplexable fixture aiming page."
  [fixture-key shared-prefix transform? color]
  (let [isolated? (clojure.string/blank? shared-prefix)]
    (cues/cue (keyword (str "aim-" (name fixture-key)))
              (fn [var-map]
                (let [base-aim (if isolated?
                                 (cues/apply-merging-var-map var-map params/build-aim-param)
                                 (params/build-aim-param :x (aim-cue-var-key "x" shared-prefix)
                                                         :y (aim-cue-var-key "y" shared-prefix)
                                                         :z (aim-cue-var-key "z" shared-prefix)))
                      aim-param (if transform?
                                  (params/build-aim-transformer base-aim
                                                                (keyword (str "aim-group-" shared-prefix "-transform")))
                                  base-aim)]
                  (move/aim-effect (str "Aim " (name fixture-key)
                                        (when-not isolated?
                                          (str " (Group " (clojure.string/upper-case shared-prefix)
                                               (when transform? " flip") ")")))
                                   aim-param (show/fixtures-named fixture-key))))
              :variables [(merge {:key (aim-cue-var-key "x" shared-prefix) :name "X" :min -20.0 :max 20.0
                                  :centered true :resolution 0.05}
                                 (when isolated? {:start 0.0}))
                          (merge {:key (aim-cue-var-key "y" shared-prefix) :name "Y" :min -20.0 :max 20.0
                                  :centered true :resolution 0.05}
                                 (when isolated? {:start 0.0}))
                          (merge {:key (aim-cue-var-key "z" shared-prefix) :name "Z" :min -20.0 :max 20.0
                                  :centered true :resolution 0.05}
                                 (when isolated? {:start 2.0}))]
              :color color :priority 1)))

(defn- make-main-aim-cues
  "Create a page of cues for aiming lights in particular points,
  individually and in groups."
  [page-x page-y]
  (let [x-base (* page-x 8)
        y-base (* page-y 8)
        fixtures [:torrent-1 :torrent-2]
        transform (Transform3D.)
        width (- right-wall left-wall)
        depth (- house-rear-wall stage-wall)]

    ;; Set up default shared aiming coordinates
    (show/set-variable! :aim-group-a-x 0.0)
    (show/set-variable! :aim-group-a-y 0.0)
    (show/set-variable! :aim-group-a-z 2.0)
    (show/set-variable! :aim-group-b-x 0.0)
    (show/set-variable! :aim-group-b-y 0.0)
    (show/set-variable! :aim-group-b-z 2.0)

    ;; Set up default transformation of a reflection over the Y axis
    (.setScale transform (Vector3d. -1.0 1.0 1.0))
    (show/set-variable! :aim-group-a-transform transform)
    (show/set-variable! :aim-group-b-transform transform)

    (loop [fixtures fixtures
           index 0]
      (when (seq fixtures)
        (let [fixture (first fixtures)]
          ;; Disconnected individual aim cues
          (show/set-cue! (+ x-base index) y-base (build-aim-cue fixture nil false :white))

          ;; Group A untransformed aim cues
          (show/set-cue! (+ x-base index) (inc y-base) (build-aim-cue fixture "a" false :red))

          ;; Group A transformed aim cues
          (show/set-cue! (+ x-base index) (+ y-base 2) (build-aim-cue fixture "a" true :orange))

          ;; Group B untransformed aim cues
          (show/set-cue! (+ x-base index) (+ y-base 3) (build-aim-cue fixture "b" false :blue))

          ;; Group B transformed aim cues
          (show/set-cue! (+ x-base index) (+ y-base 4) (build-aim-cue fixture "b" true :cyan)))
        (recur (rest fixtures) (inc index))))

    ;; Transformation modifiers for group A
    (show/set-cue! (+ x-base 7) (inc y-base)
                   (cues/cue :aim-group-a-transform
                             (fn [_]
                               (let [transform (Transform3D.)]
                                 (.setScale transform (Vector3d. 1.0 -1.0 1.0))
                                 (var-fx/variable-effect @var-binder :aim-group-a-transform transform)))
                             :color :cyan :short-name "Group A flip Y"))
    (show/set-cue! (+ x-base 7) (+ y-base 2)
                   (cues/cue :aim-group-a-transform
                             (fn [_]
                               (let [transform (Transform3D.)]
                                 (.setScale transform (Vector3d. -1.0 -1.0 1.0))
                                 (var-fx/variable-effect @var-binder :aim-group-a-transform transform)))
                             :color :cyan :short-name "Group A flip XY"))

    ;; Transformation modifiers for group B
    (show/set-cue! (+ x-base 7) (+ y-base 3)
                   (cues/cue :aim-group-b-transform
                             (fn [_]
                               (let [transform (Transform3D.)]
                                 (.setScale transform (Vector3d. 1.0 -1.0 1.0))
                                 (var-fx/variable-effect @var-binder :aim-group-b-transform transform)))
                             :color :orange :short-name "Group B flip Y"))
    (show/set-cue! (+ x-base 7) (+ y-base 4)
                   (cues/cue :aim-group-b-transform
                             (fn [_]
                               (let [transform (Transform3D.)]
                                 (.setScale transform (Vector3d. -1.0 -1.0 1.0))
                                 (var-fx/variable-effect @var-binder :aim-group-b-transform transform)))
                             :color :orange :short-name "Group B flip XY"))))

(defn- direction-cue-var-key
  "Determine the cue variable key value to use for a variable being
  created for an direction cue page cue. `base-name` is the name that
  will be used for the variable if it is not part of a group of cues
  sharing variables; if `shared-prefix` is not blank then the variable
  key will refer to a show variable with that prefix identifying which
  group it belongs to."
  [base-name shared-prefix]
  (if (clojure.string/blank? shared-prefix)
    (name base-name)
    (keyword (str "direction-group-" shared-prefix "-" (name base-name)))))

(defn- build-direction-cue
  "Build a direction cue for the mutiplexable fixture direction page."
  [fixture-key shared-prefix transform? color]
  (let [isolated? (clojure.string/blank? shared-prefix)]
    (cues/cue (keyword (str "dir-" (name fixture-key)))
              (fn [var-map]
                (let [base-direction (if isolated?
                                       (cues/apply-merging-var-map var-map params/build-direction-param-from-pan-tilt)
                                       (params/build-direction-param-from-pan-tilt
                                        :pan (direction-cue-var-key "pan" shared-prefix)
                                        :tilt (direction-cue-var-key "tilt" shared-prefix)))
                      direction-param (if transform?
                                        (params/build-direction-transformer
                                         base-direction (keyword (str "direction-group-" shared-prefix "-transform")))
                                        base-direction)]
                  (move/direction-effect (str "P/T " (name fixture-key)
                                              (when-not isolated?
                                                (str " (Group " (clojure.string/upper-case shared-prefix)
                                                     (when transform? " flip") ")")))
                                         direction-param (show/fixtures-named fixture-key))))
              :variables [(merge {:key (direction-cue-var-key "pan" shared-prefix) :name "Pan" :min -180.0 :max 180.0
                                  :centered true :resolution 0.5}
                                 (when isolated? {:start 0.0}))
                          (merge {:key (direction-cue-var-key "tilt" shared-prefix) :name "Tilt" :min -180.0 :max 180.0
                                  :centered true :resolution 0.5}
                                 (when isolated? {:start 0.0}))]
              :color color :priority 1)))

(defn- build-pan-tilt-osc-cue
  "Build a raw pan/tilt oscillator cue."
  [fixture-key]
  (cues/cue (keyword (str "p-t-" (name fixture-key)))
            (fn [var-map]
              (let [pan-osc (oscillators/sine :interval :bar :interval-ratio (:pan-bars var-map)
                                              :phase (:pan-phase var-map))
                    pan-param (oscillators/build-oscillated-param pan-osc :min (:pan-min var-map)
                                                                  :max (:pan-max var-map))
                    tilt-osc (oscillators/sine :interval :bar :interval-ratio (:tilt-bars var-map)
                                               :phase (:tilt-phase var-map))
                      tilt-param (oscillators/build-oscillated-param tilt-osc :min (:tilt-min var-map)
                                                                     :max (:tilt-max var-map))]
                (fx/scene (str "P/T " (name fixture-key))
                          (chan-fx/channel-effect
                           "pan" pan-param
                           (chan/extract-channels (chan/expand-heads
                                                   (show/fixtures-named fixture-key))
                                                  #(= (:type %) :pan)))
                          (chan-fx/channel-effect
                           "tilt" tilt-param
                           (chan/extract-channels (chan/expand-heads (show/fixtures-named fixture-key))
                                                  #(= (:type %) :tilt))))))
            :variables [{:key "pan-min" :name "Pan min" :min 0 :max 255
                         :centered true :resolution 0.5 :start 0}
                        {:key "pan-max" :name "Pan max" :min 0 :max 255
                         :centered true :resolution 0.5 :start 255}
                        {:key "pan-bars" :name "Pan bars" :min 1 :max 16
                         :type :integer :start 1}
                        {:key "pan-phase" :name "Pan phase" :min 0.0 :max 1.0 :start 0.0}
                        {:key "tilt-min" :name "Tilt min" :min 0 :max 255
                         :centered true :resolution 0.5 :start 0}
                        {:key "tilt-max" :name "Tilt max" :min 0 :max 255
                         :centered true :resolution 0.5 :start 255}
                        {:key "tilt-bars" :name "Tilt bars" :min 1 :max 16
                         :type :integer :start 1}
                        {:key "tilt-phase" :name "Tilt phase" :min 0.0 :max 1.0 :start 0.0}]
            :color :green :priority 1))

(defn- make-main-direction-cues
  "Create a page of cues for aiming lights in particular directions,
  individually and in groups."
  [page-x page-y]
  (let [x-base (* page-x 8)
        y-base (* page-y 8)
        fixtures [:torrent-1 :torrent-2]
        transform (Transform3D.)]

    ;; Set up default shared direction coordinates
    (show/set-variable! :direction-group-a-pan 0.0)
    (show/set-variable! :direction-group-a-tilt 0.0)
    (show/set-variable! :direction-group-b-pan 0.0)
    (show/set-variable! :direction-group-b-tilt 0.0)

    ;; Set up default transformation of a reflection over the Y axis
    (.setScale transform (Vector3d. -1.0 1.0 1.0))
    (show/set-variable! :direction-group-a-transform transform)
    (show/set-variable! :direction-group-b-transform transform)

    (loop [fixtures fixtures
           index 0]
      (when (seq fixtures)
        (let [fixture (first fixtures)]
          ;; Disconnected individual direction cues
          (show/set-cue! (+ x-base index) y-base (build-direction-cue fixture nil false :white))

          ;; Group A untransformed direction cues
          (show/set-cue! (+ x-base index) (inc y-base) (build-direction-cue fixture "a" false :blue))
          ;; Group A transformed direction cues
          (show/set-cue! (+ x-base index) (+ y-base 2) (build-direction-cue fixture "a" true :cyan))

          ;; Group B untransformed direction cues
          (show/set-cue! (+ x-base index) (+ y-base 3) (build-direction-cue fixture "b" false :red))
          ;; Group B transformed direction cues
          (show/set-cue! (+ x-base index) (+ y-base 4) (build-direction-cue fixture "b" true :orange))

          ;; Raw pan/tilt oscillated cues
          (show/set-cue! (+ x-base index) (+ y-base 7) (build-pan-tilt-osc-cue fixture)))
        (recur (rest fixtures) (inc index))))

    ;; Transformation modifiers for group A
    (show/set-cue! (+ x-base 7) (inc y-base)
                   (cues/cue :direction-group-a-transform
                             (fn [_]
                               (let [transform (Transform3D.)]
                                 (.setScale transform (Vector3d. 1.0 -1.0 1.0))
                                 (var-fx/variable-effect @var-binder :direction-group-a-transform transform)))
                             :color :cyan :short-name "Group B flip Y"))
    (show/set-cue! (+ x-base 7) (+ y-base 2)
                   (cues/cue :direction-group-a-transform
                             (fn [_]
                               (let [transform (Transform3D.)]
                                 (.setScale transform (Vector3d. -1.0 -1.0 1.0))
                                 (var-fx/variable-effect @var-binder :direction-group-a-transform transform)))
                             :color :cyan :short-name "Group B flip XY"))

    ;; Transformation modifiers for group B
    (show/set-cue! (+ x-base 7) (+ y-base 3)
                   (cues/cue :direction-group-b-transform
                             (fn [_]
                               (let [transform (Transform3D.)]
                                 (.setScale transform (Vector3d. 1.0 -1.0 1.0))
                                 (var-fx/variable-effect @var-binder :direction-group-b-transform transform)))
                             :color :orange :short-name "Group B flip Y"))
    (show/set-cue! (+ x-base 7) (+ y-base 4)
                   (cues/cue :direction-group-b-transform
                             (fn [_]
                               (let [transform (Transform3D.)]
                                 (.setScale transform (Vector3d. -1.0 -1.0 1.0))
                                 (var-fx/variable-effect @var-binder :direction-group-b-transform transform)))
                             :color :orange :short-name "Group B flip XY"))))

(defn make-cues
  "Create the cues for the show, using the above helper functions."
  []
  ;; Fill in some cue pages
  (make-main-color-dimmer-cues 0 0)  ; Creates a sigle 8x8 page at the origin
  (make-torrent-cues 0 2)  ; Creates 2 8x8 pages: two pages up from the origin, and the next page to the right
  (make-ambient-cues 1 0)  ; Creates a single 8x8 page to the right of the origin
  (make-movement-cues 0 1)  ; Creates an 8x8 page above the origin
  (make-main-aim-cues 1 1)  ; Creates an 8x8 page above the ambient cues
  (make-main-direction-cues 2 1)  ; Creates an 8x8 page to the right of that

)
