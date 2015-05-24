(ns afterglow.fixtures.blizzard
  "Models for fixtures provided by [Blizzard
  Lighting](http://www.blizzardlighting.com)."
  {:doc/format :markdown}
  (:require [afterglow.channels :as chan]))

(defn blade-rgbw
  "[Blade
  RGBW](http://www.blizzardlighting.com/index.php?option=com_k2&view=item&layout=item&id=177&Itemid=157)
  moving head. The default mounting orientation is sitting on its feet
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
  the default orientation described above), is defined as 84, a full
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
                                    (chan/fine-channel :movement-speed 5 :function-name "Movement Speed (fast->slow)")
                                    (chan/color 6 :red) (chan/color 7 :green) (chan/color 8 :blue) (chan/color 9 :white)
                                    (chan/fine-channel :custom-color 10)
                                    (chan/functions :strobe 11 0 nil 1 :strobe)
                                    (chan/dimmer 12)
                                    (chan/functions :control 13
                                                    0 :linear-dimming 26 :fade-step-increase 51 :color-macros
                                                    91 :color-fade-in-out 131 :color-snap 171 :color-fade
                                                    211 :auto 251 :sound-active)]}
            :11-channel {:channels [(chan/pan 1 3) (chan/tilt 2 4)
                                    (chan/fine-channel :movement-speed 5 :function-name "Movement Speed (fast->slow)")
                                    (chan/color 6 :red) (chan/color 7 :green) (chan/color 8 :blue) (chan/color 9 :white)
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
                                                   48 "purple" 56 "white" 64 "program-1" 72 "program-2"
                                                   80 "program-3" 88 "program-4" 96 "program-5" 104 "program-6"
                                                   112 "program-7" 120 "program-8" 128 "program-9" 136 "program-10"
                                                   144 "program-11" 152 "program-12" 160 "program-13" 168 "program-14"
                                                   176 "program-15" 184 "program-16" 192 "program-17" 200 "program-18"
                                                   208 "program-19" 216 "program-20" 224 "program-21"
                                                   232 "sound-active")
                                   (chan/fine-channel :mic-sensitivity 6)
                                   (chan/functions :strobe 7 0 nil 10 :strobe)]}
            :26-channel {:channels [(chan/dimmer 1)
                                    (chan/functions :strobe 26
                                                    0 nil
                                                    10 {:type :strobe :label "Strobe (slow->fast)"})]
                         :heads (map ws-head (range 8))})
          :name "Blizzard Weather System"
          :mode mode)))
