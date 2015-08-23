(ns afterglow.fixtures.blizzard
  "Definitions for fixtures provided by [Blizzard
  Lighting](http://www.blizzardlighting.com)."
  {:doc/format :markdown}
  (:require [afterglow.channels :as chan]
            [afterglow.effects.channel :as chan-fx]
            [afterglow.fixtures.qxf :refer [sanitize-name]]))

(defn- build-gobo-entries
  "Create a series of function map entries describing the gobos
  available on one of the Torrent gobo wheels, in a format suitable
  for passing to [[chan/functions]]. `moving?` is `true` to generate
  entries for the moving gobo wheel, `false` for the fixed gobo wheel.
  `shake?` is `true` when generating entries for ranges which shake
  the gobo at variable speeds, `false` if the gobo is being projected
  in a static position. `names` is the list of gobo names to expand
  into corresponding function map entries."
  {:doc/format :markdown}
  [moving? shake? names]
  (map (fn [entry]
         (let [prefix (str "gobo-" (if moving? "moving-" "fixed-"))
               type-key (keyword (sanitize-name (str prefix entry (when shake? " shake"))))
               label (str "Gobo" (when moving? "M") " " entry (when shake? " shake"))]
           (merge {:type type-key
                   :label label
                   :range (if shake? :variable :fixed)}
                  (when shake? {:var-label "Shake Speed"}))))
       names))

(defn torrent-f3
  "[Torrent F3](http://www.blizzardlighting.com/index.php?option=com_k2&view=item&id=174:torrent-f3™&Itemid=71)
  moving head effects spotlight. The default patching orientation is sitting on its feet
  with the LCD and control panel right side up and facing the
  audience. In this orientation, at a pan of 0, the beam is straight
  into the audience.

  The origin of the light is, as for all moving heads, at the
  intersection of the pan and tilt axes. That is the point that you
  need to reference when patching the fixture and telling Afterglow
  where it has been hung within [show
  space](https://github.com/brunchboy/afterglow/blob/master/doc/show_space.adoc#show-space). The
  image below shows this default orientation, and the axes, for the
  fixture. If it is hung with this side of the base facing the
  audience, right side up, then you do not need to specify any
  rotations when you patch it. Otherwise, tell Afterglow how far it
  has been rotated around each of the axes when hanging:

  ![F3
  axes](https://raw.githubusercontent.com/brunchboy/afterglow/master/doc/assets/F3.png)

  The center pan value (aimed straight at the audience when hung in
  the default orientation described above), is defined as 85.5, half a
  revolution around from that, so that it has room to move in both
  directions from its resting point. It takes a change of -85.5 in the
  pan channel to rotate a half circle counterclockwise around the Y
  axis.

  At the center pan setting of 85.5, the center tilt value is 25,
  aiming the head straight out at the audience. At this position, it
  takes a change of -203 in the tilt channel to rotate a half circle
  counterclockwise around the X axis. (In other words, it can only
  tilt a little counterclockwise from here, but can flip right over in
  the clockwise direction.)

  If you are wondering why you are getting no light from a torrent,
  note that you need to explicitly set the shutter to open before the
  dimmer has any effect. Try something like:

```clojure
(show/add-effect! :torrent-shutter
  (afterglow.effects.channel/function-effect
    \"Torrents Open\" :shutter-open 50
    (show/fixtures-named \"torrent\")))
```" 
  {:doc/format :markdown}
  []
  {:channels [(chan/pan 1 2) (chan/tilt 3 4)
              (chan/functions :color 5 0 "Color Wheel Open"
                              (range 16 128 16) (chan/color-wheel-hue ["red" "blue" "green" "yellow"
                                                                       "magenta" "cyan" "orange"])
                              128 {:type :color-clockwise
                                   :label "Color Wheel Clockwise (fast->slow)"
                                   :var-label "CW (fast->slow)"
                                   :range :variable}
                              190 "Color Stop" 194 {:type :color-counterclockwise
                                                    :label "Color Wheel Counterclockwise (slow->fast)"
                                                    :var-label "CCW (fast->slow)"
                                                    :range :variable})
              (let [gobo-names ["Rings" "Color Swirl" "Stars" "Optical Tube" "Magenta Bundt"
                                "Blue MegaHazard" "Turbine"]]
                (chan/functions :gobo-moving 6
                                (range 0 80 10) (build-gobo-entries true false (concat ["Open"] gobo-names))
                                (range 80 220 20) (build-gobo-entries true true gobo-names)
                                220 {:type :gobo-moving-clockwise
                                     :label "Clockwise Speed"
                                     :var-label "CW Speed"
                                     :range :variable}))
              (chan/functions :gobo-rotation 7 0 nil
                              4 {:type :gobo-rotation-clockwise
                                 :label "Gobo Rotation Clockwise (fast->slow)"
                                 :var-label "CW (fast->slow)"
                                 :range :variable}
                              128 "gobo-rotation-stop"
                              132 {:type :gobo-rotation-counterclockwise
                                   :label "Gobo Rotation Counterlockwise (slow->fast)"
                                   :var-label "CCW (slow->fast)"
                                   :range :variable})
              (let [gobo-names ["Mortar" "4 Rings" "Atom" "Jacks" "Saw" "Sunflower" "45 Adapter"
                                "Star" "Rose/Fingerprint"]]
                (chan/functions :gobo-fixed 8
                                (range 0 100 10) (build-gobo-entries false false (concat ["Open"] gobo-names))
                                (range 100 208 12) (build-gobo-entries false true gobo-names)
                                208 {:type :gobo-fixed-clockwise
                                     :label "Clockwise Speed"
                                     :var-label "CW Speed"
                                     :range :variable}))
              (chan/functions :shutter 9 0 "Shutter Closed" 32 "Shutter Open"
                              64 {:type :strobe
                                  :scale-fn (partial chan-fx/function-value-scaler 14 100)
                                  :label "Strobe (1.4Hz->10Hz)"
                                  :range :variable}
                              96 "Shutter Open 2" 128 :pulse-strobe 160 "Shutter Open 3"
                              192 :random-strobe
                              224 "Shutter Open 4")
              (chan/dimmer 10)
              (chan/focus 11)
              (chan/functions :prism 12 0 "Prism Out" 6 "Prism In"
                              128 {:type :prism-clockwise
                                   :label "Prism Clockwise (fast->slow)"
                                   :var-label "CW (fast->slow)"
                                   :range :variable}
                              190 "Prism Stop"
                              194 {:type :prism-counterclockwise
                                   :label "Prism Counterclockwsie (slow->fast)"
                                   :var-label "CCW (slow->fast)"
                                   :range :variable})
              (chan/functions :pan-tilt-speed 13 0 "Pan/Tilt Speed Normal"
                              1 :pan-tilt-speed-slow
                              226 "Blackout When Head Moving"
                              236 "Blackout When Wheels Changing"
                              246 nil)
              (chan/functions :control 14 0 "Normal Color Change Mode"
                              20 "Split Colors Possible" 30 "Split Colors and Gobos"
                              40 nil 80 "Motor Reset" (range 100 255 20) "Program")]
   :name "Blizzard Torrent F3"
   :pan-center 85.5 :pan-half-circle -85.5 :tilt-center 25 :tilt-half-circle -203})

(defn blade-rgbw
  "[Blade
  RGBW](http://www.blizzardlighting.com/index.php?option=com_k2&view=item&layout=item&id=177&Itemid=157)
  moving head. The default patching orientation is sitting on its feet
  with the LCD and control panel right side up and facing the
  audience. In this orientation, at a pan of 0, the beam is straight
  into the audience.

  The origin of the light is, as for all moving heads, at the
  intersection of the pan and tilt axes. That is the point that you
  need to reference when patching the fixture and telling Afterglow
  where it has been hung within [show
  space](https://github.com/brunchboy/afterglow/blob/master/doc/show_space.adoc#show-space). The
  image below shows this default orientation, and the axes, for the
  fixture. If it is hung with this side of the base facing the
  audience, right side up, then you do not need to specify any
  rotations when you patch it. Otherwise, tell Afterglow how far it
  has been rotated around each of the axes when hanging:

  ![Blade
  axes](https://raw.githubusercontent.com/brunchboy/afterglow/master/doc/assets/Blade.png)

  The center pan value (aimed straight at the audience when hung in
  the default orientation described above), is defined as 84, a half
  revolution around from that, so that it has room to move in both
  directions from its resting point. It takes a change of +84 in the
  pan channel to rotate a half circle counterclockwise around the Y
  axis.

  At the center pan setting of 84, the center tilt value is 8, aiming
  the head straight out at the audience. At this position, it takes a
  change of -214 in the tilt channel to rotate a half circle
  counterclockwise around the X axis. (In other words, it can
  essentially only tilt clockwise from here.)

  This fixture can be configured to use either 11 or 15 DMX channels.
  If you do not specify a mode when patching it, `:15-channel` is
  assumed; you can pass a value of `:11-channel` for `mode` if you are
  using it that way."
  {:doc/format :markdown}
  ([]
   (blade-rgbw :15-channel))
  ([mode]
   (assoc (case mode
            :15-channel {:channels [(chan/pan 1 3) (chan/tilt 2 4)
                                    (chan/fine-channel :movement-speed 5
                                                       :function-name "Movement Speed (fast->slow)")
                                    (chan/color 6 :red) (chan/color 7 :green)
                                    (chan/color 8 :blue) (chan/color 9 :white)
                                    (chan/fine-channel :custom-color 10)
                                    (chan/functions :strobe 11 0 nil
                                                    1 {:type :strobe
                                                       :scale-fn (partial chan-fx/function-value-scaler 18 100)
                                                       :label "Strobe (1.8Hz->10Hz)"
                                                       :range :variable})
                                    (chan/dimmer 12)
                                    (chan/functions :control 13
                                                    0 :linear-dimming 26 :fade-step-increase
                                                    51 :color-macros 91 :color-fade-in-out
                                                    131 :color-snap 171 :color-fade
                                                    211 :auto 251 :sound-active)]}
            :11-channel {:channels [(chan/pan 1 3) (chan/tilt 2 4)
                                    (chan/fine-channel :movement-speed 5
                                                       :function-name "Movement Speed (fast->slow)")
                                    (chan/color 6 :red) (chan/color 7 :green)
                                    (chan/color 8 :blue) (chan/color 9 :white)
                                    (chan/dimmer 10) (chan/fine-channel :custom-color 11)]})
          :name "Blizzard Blade RGBW"
          :mode mode
          :pan-center 84 :pan-half-circle 84 :tilt-center 8 :tilt-half-circle -214)))

;; TODO: Someday play with channels 13 and 14 more to see if there is anything worth modeling.
;;       Not urgent, though, the main point of Afterglow is custom effects using raw colors and
;;       motions. Also unimplemented is the whole concept of Fiture ID, but that would require
;;       major support throughout Afterglow. Wait until people using overpopulated DMX networks
;;       ask for it...

(def ^:private ws-head-offsets
  "The X-axis positions of the eight weather system heads"
  [-0.406 -0.305 -0.191 -0.083 0.083 0.191 0.305 0.406])

(defn- ws-head
  "Creates a head definition for one head of the Weather System"
  [index]
  {:channels [(chan/color (+ 2 (* 3 index)) :red)
              (chan/color (+ 3 (* 3 index)) :green)
              (chan/color (+ 4 (* 3 index)) :blue)]
   :x (get ws-head-offsets index)})

(defn puck-fab5
  "[Puck
  Fab5](http://www.blizzardlighting.com/index.php?option=com_k2&view=item&id=172:the-puck-fab5™&Itemid=71)
  RGBAW LED.


  This fixture can be configured to use either 3, 5 or 12 DMX
  channels. If you do not specify a mode when patching it,
  `:12-channel` is assumed; you can pass a value of `:3-channel` or
  `:5-channel` for `mode` if you are using it that way.

  When you pass a mode, you can also control whether the amber channel
  is mixed in when creating colors by passing a boolean value with
  `:mix-amber`. The default is `true`."
  {:doc/format :markdown}
  ([]
   (puck-fab5 :12-channel))
  ([mode & {:keys [mix-amber] :or {mix-amber true}}]
   (assoc (case mode
            :3-channel {:channels [(chan/dimmer 1)
                                   (chan/functions :strobe 2
                                                    0 nil
                                                    16 {:type :strobe
                                                        :scale-fn (partial chan-fx/function-value-scaler 8 100)
                                                        :label "Strobe (0.8Hz->10Hz)"
                                                        :range :variable})
                                   (chan/functions :control 3
                                                   0 "R" 5 "G" 9 "B" 13 "A" 17 "W" 21 "RG" 25 "RB" 29 "RA" 32 "RW"
                                                   36 "GB" 40 "GA" 44 "GW" 48 "BA" 52 "BW" 56 "AW" 60 "BAW" 63 "GAW"
                                                   67 "GBW" 71 "GBA" 75 "RAW" 79 "RBW" 83 "RBA" 87 "RGW" 91 "RGA"
                                                   94 "RGB" 98 "RGBA" 102 "RGBW" 106 "RGAW" 110 "RBAW" 114 "GBAW"
                                                   118 "RGBAW" 121 {:type :chase :label "Chase (slow->fast)"
                                                                    :range :variable})] }
            :5-channel {:channels [(chan/color 1 :red) (chan/color 2 :green) (chan/color 3 :blue)
                                   (chan/color 4 :amber :hue (when mix-amber 45))
                                   (chan/color 5 :white)]}
            :12-channel {:channels [(chan/dimmer 1)
                                    (chan/functions :strobe 2
                                                    0 nil
                                                    16 {:type :strobe
                                                        :scale-fn (partial chan-fx/function-value-scaler 8 100)
                                                        :label "Strobe (0.8Hz->10Hz)"
                                                        :range :variable})
                                    (chan/color 3 :red) (chan/color 4 :green) (chan/color 5 :blue)
                                    (chan/color 6 :amber :hue (when mix-amber 45))
                                    (chan/color 7 :white)
                                    (chan/functions :control 8 0 :color-snap)
                                    (chan/functions :control 9 0 nil
                                                    16 {:type :snap-speed :label "Snap speed (slow->fast)"
                                                        :range :variable})
                                    (chan/functions :control 10 0 :color-fade)
                                    (chan/functions :control 11 0 nil
                                                    16 {:type :fade-speed :label "Fade speed (slow->fast)"
                                                        :range :variable})
                                    (chan/functions :control 12 0 nil
                                                    128 {:type :sound-active :var-label "Sensitivity"
                                                         :range :variable})]})
          :name "Blizzard Puck Fab5"
          :mode mode)))

(defn weather-system
  "[Weather
  System](http://www.blizzardlighting.com/index.php?option=com_k2&view=item&layout=item&id=173&Itemid=152)
  8-fixture LED bar. Even though this fixture does not move, it is
  important to patch it at the correct orientation, so Afterglow can
  properly reason about the spatial relationships between the eight
  individual heads.

  The fixture origin is right between the fourth and fifth head, at
  the level of the lenses. The default orientation is with the bar
  parallel to the X axis, and the LED display and sockets all facing
  away from the audience.

  ![Weather System
  axes](https://raw.githubusercontent.com/brunchboy/afterglow/master/doc/assets/WeatherSystem.jpg)

  This fixture can be configured to use either 7 or 26 DMX channels.
  If you do not specify a mode when patching it, `:26-channel` is
  assumed; you can pass a value of `:7-channel` for `mode` if you are
  using it that way."
  {:doc/format :markdown}
  ([]
   (weather-system :26-channel))
  ([mode]
   (assoc (case mode
            :7-channel {:channels [(chan/dimmer 1) (chan/color 2 :red) (chan/color 3 :green) (chan/color 4 :blue)
                                   (chan/functions :control 5
                                                   0 nil 8 "Red" 16 "Yellow" 24 "Green" 32 "Cyan" 40 "Blue"
                                                   48 "Purple" 56 "White" (range 64 232 8) "Program"
                                                   232 "Sound Active")
                                   (chan/fine-channel :mic-sensitivity 6)
                                   (chan/functions :strobe 7 0 nil
                                                   11 {:type :strobe
                                                       :scale-fn (partial chan-fx/function-value-scaler 6.6 100)
                                                       :label "Strobe (0.66Hz->10Hz)"
                                                       :range :variable})]}
            :26-channel {:channels [(chan/dimmer 1)
                                    (chan/functions :strobe 26
                                                    0 nil
                                                    11 {:type :strobe
                                                        :scale-fn (partial chan-fx/function-value-scaler 6.6 100)
                                                        :label "Strobe (0.66Hz->10Hz)"
                                                        :range :variable})]
                         :heads (map ws-head (range 8))})
          :name "Blizzard Weather System"
          :mode mode)))

(defn snowball
  "[Snowball](http://www.blizzardlighting.com/index.php?option=com_k2&view=item&layout=item&id=100&Itemid=96)
  multi-beam moonflower effect light."
  {:doc/format :markdown}
  []
  {:name "Blizzard Snowball"
   :channels [(chan/dimmer 1) (chan/color 2 :red) (chan/color 3 :green) (chan/color 4 :blue) (chan/color 5 :white)
              (chan/functions :strobe 6 0 nil
                              17 {:type :strobe
                                  :label "Strobe (?->?Hz)"
                                  :range :variable})
              (chan/functions :control 7 0 :color-macros)
              (chan/functions :control 8
                              0 {:type :beams-fixed
                                 :var-label "Beam Position"
                                 :range :variable}
                              128 {:type :beams-moving
                                   :var-label "Move Speed"
                                   :range :variable})
              (chan/functions :control 9 0 nil
                              128 {:type :sound-active :var-label "Sensitivity" :range :variable})]})
