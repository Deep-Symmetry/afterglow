(ns afterglow.fixtures.blizzard
  "Models for fixtures provided by [Blizzard
  Lighting](http://www.blizzardlighting.com)."
  {:doc/format :markdown}
  (:require [afterglow.channels :as chan]
            [afterglow.effects.channel :refer [function-value-scaler]]))

;; TODO: Figure out how to integrate color wheel into color assigner.
;;       For now, just mapped as a function channel.
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
  space](https://github.com/brunchboy/afterglow/wiki/Show-Space). The
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
  (afterglow.effects.channel/function-cue
    \"Torrent Shutter Open\" :shutter-open 50
    (show/fixtures-named \"torrent\")))
```" 
  {:doc/format :markdown}
  []
  {:channels [(chan/pan 1 2) (chan/tilt 3 4)
              (chan/functions :color 5 0 "color-wheel-open"
                              (range 16 128 16) (chan/color-wheel-hue ["red" "blue" "green" "yellow"
                                                                       "magenta" "cyan" "orange"])
                              128 {:type :color-clockwise
                                   :label "Color Wheel Clockwise (fast->slow)"}
                              190 "color-stop" 194 {:type :color-counterclockwise
                                                    :label "Color Wheel Counterclockwise (slow->fast)"})
              (chan/functions :gobo-moving 6
                              (range 0 80 10) ["gobo-moving-open" "gobo-moving-rings"
                                               "gobo-moving-color-swirl" "gobo-moving-stars"
                                               "gobo-moving-optical-tube" "gobo-moving-magenta-bundt"
                                               "gobo-moving-blue-megahazard" "gobo-moving-turbine"]
                              (range 80 220 20) [:gobo-moving-rings-shake :gobo-moving-color-swirl-shake
                                                 :gobo-moving-stars-shake :gobo-moving-optical-tube-shake
                                                 :gobo-moving-magenta-bundt-shake
                                                 :gobo-moving-blue-megahazard-shake
                                                 :gobo-moving-turbine-shake]
                              220 :gobo-moving-clockwise)
              (chan/functions :gobo-rotation 7 0 nil
                              4 {:type :gobo-rotation-clockwise
                                 :label "Gobo Rotation Clockwise (fast->slow)"}
                              128 "gobo-rotation-stop"
                              192 {:type :gobo-rotation-counterclockwise
                                   :label "Gobo Rotation Counterlockwise (slow->fast)"})
              (chan/functions :gobo-fixed 8
                              (range 0 100 10) ["gobo-fixed-open" "gobo-fixed-mortar"
                                                "gobo-fixed-4-rings" "gobo-fixed-atom"
                                                "gobo-fixed-jacks" "gobo-fixed-saw"
                                                "gobo-fixed-sunflower" "gobo-fixed-45-adapter"
                                                "gobo-fixed-star" "gobo-fixed-fose-fingerprint"]
                              (range 100 208 12) [:gobo-fixed-mortar-shake :gobo-fixed-4-rings-shake
                                                  :gobo-fixed-atom-shake :gobo-fixed-jacks-shake
                                                  :gobo-fixed-saw-shake :gobo-fixed-sunflower-shake
                                                  :gobo-fixed-45-adapter-shake :gobo-fixed-star-shake
                                                  :gobo-fixed-rose-fingerprint-shake]
                              208 :gobo-fixed-clockwise)
              (chan/functions :shutter 9 0 "shutter-closed" 32 "shutter-open"
                              64 :strobe
                              96 "shutter-open-2" 128 :pulse-strobe 160 "shutter-open-3"
                              192 :random-strobe
                              224 "shutter-open-4")
              (chan/dimmer 10)
              (chan/focus 11)
              (chan/functions :prism 12 0 "prism-out" 6 "prism-in"
                              128 {:type :prism-clockwise
                                   :label "Prism Clockwise (fast->slow)"}
                              190 "prism-stop"
                              194 {:type :prism-counterclockwise
                                   :label "Prism Counterclockwsie (slow->fast)"})
              (chan/functions :pan-tilt-speed 13 0 "pan-tilt-speed-normal"
                              1 :pan-tilt-speed-slow
                              226 "blackout-when-head-moving"
                              236 "blackout-when-wheels-changing"
                              246 nil)
              (chan/functions :control 14 0 "normal-color-change-mode"
                              20 "split-colors-possible" 30 "split-colors-and-gobos"
                              40 nil 80 "motor-reset" (range 100 255 20) "program")]
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
  space](https://github.com/brunchboy/afterglow/wiki/Show-Space). The
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
                                                       :scale-fn (partial function-value-scaler 1.8 27)
                                                       :label "Strobe (1.8Hz->27Hz)"})
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
  {:channels [(chan/color (+ 2 (* 3 index)) :red) (chan/color (+ 3 (* 3 index)) :green) (chan/color (+ 4 (* 3 index)) :blue)]
   :x (get ws-head-offsets index)})

;; TODO: Document origin and default hanging orientation

(defn weather-system
  "[Weather System](http://www.blizzardlighting.com/index.php?option=com_k2&view=item&layout=item&id=173&Itemid=152)
  8-fixture LED bar."
  {:doc/format :markdown}
  ([]
   (weather-system :26-channel))
  ([mode]
   (assoc (case mode
            :7-channel {:channels [(chan/dimmer 1) (chan/color 2 :red) (chan/color 3 :green) (chan/color 4 :blue)
                                   (chan/functions :control 5
                                                   0 nil 8 "red" 16 "yellow" 24 "green" 32 "cyan" 40 "blue"
                                                   48 "purple" 56 "white" (range 64 232 8) "program"
                                                   232 "sound-active")
                                   (chan/fine-channel :mic-sensitivity 6)
                                   (chan/functions :strobe 7 0 nil
                                                   11 {:type :strobe
                                                       :scale-fn (partial function-value-scaler 0.66 25)
                                                       :label "Strobe (0.66Hz->25Hz)"})]}
            :26-channel {:channels [(chan/dimmer 1)
                                    (chan/functions :strobe 26
                                                    0 nil
                                                    11 {:type :strobe
                                                        :scale-fn (partial function-value-scaler 0.66 25)
                                                        :label "Strobe (0.66Hz->25Hz)"})]
                         :heads (map ws-head (range 8))})
          :name "Blizzard Weather System"
          :mode mode)))
