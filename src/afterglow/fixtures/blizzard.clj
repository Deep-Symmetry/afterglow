(ns afterglow.fixtures.blizzard
  "Models for fixtures provided by [Blizzard
  Lighting](http://www.blizzardlighting.com)."
  {:doc/format :markdown}
  (:require [afterglow.channels :as chan]))

;; TODO functions for rotational tranformatons

(defn blade-rgbw
  "[Blade
  RGBW](http://www.blizzardlighting.com/index.php?option=com_k2&view=item&layout=item&id=177&Itemid=157)
  moving head. The default mounting orientation is sitting on its feet
  with the LCD and control panel right side up and facing away from
  the audience. In this orientation, at a pan of 0, the beam is nearly
  straight into the audience.

  The origin of the light is, as for all moving heads, at the
  intersection of the pan and tilt axes. The image below shows this
  default orientation, and the axes, for the fixture. If it is hung
  with this side of the base facing the audience, right side up, then
  you do not need to specify any rotations when you patch it.
  Otherwise, tell afterglow how far it has been rotated around each of
  the axes when hanging:

  ![Blade
  axes](https://raw.githubusercontent.com/brunchboy/afterglow/master/doc/assets/blade.png)

  The center pan value (aimed straight at the audience when hung in
  the default orientation described above), is defined as 85, a full
  revolution around from that, so that it has room to move in both
  directions from its resting point. It takes a change of +85 in the
  pan channel to rotate a half circle counterclockwise around the Y
  axis.

  At the center pan setting of 85, the center tilt value is 8, aiming
  the head straight out at the audience. At this position, it takes a
  change of -214 in the tilt channel to rotate a half circle
  counterclockwise around the X axis. (In other words, it can
  essentially only tilt clockwise from here."
  {:doc/format :markdown}
  ([]
   (blade-rgbw :15-channel))
  ([mode]
   (assoc (case mode
            ;; TODO: missing channels once we have definition support for them
            :15-channel {:channels [(chan/pan 1 3) (chan/tilt 2 4)
                                    (chan/color 6 :red) (chan/color 7 :green) (chan/color 8 :blue) (chan/color 9 :white)
                                    (chan/dimmer 12)]}
            :11-channel {:channels [(chan/pan 1 3) (chan/tilt 2 4)
                                    (chan/color 6 :red) (chan/color 7 :green) (chan/color 8 :blue) (chan/color 9 :white)
                                    (chan/dimmer 10)]})
          :name "Blizzard Blade RGBW"
          :mode mode
          :pan-center 84 :pan-half-circle 85 :tilt-center 8 :tilt-half-circle -214)))

(def ^:private ws-head-offsets
  "The X-axis positions of the eight weather system heads"
  [-0.406 -0.305 -0.191 -0.083 0.083 0.191 0.305 0.406])

(defn- ws-head
  "Creates a head definition for one head of the Weather System"
  [index]
  {:channels [(chan/color (+ 2 (* 3 index)) :red) (chan/color (+ 3 (* 3 index)) :green) (chan/color (+ 4 (* 3 index)) :blue)]
   :x (get ws-head-offsets index)})

(defn weather-system
  "[Weather System](http://www.blizzardlighting.com/index.php?option=com_k2&view=item&layout=item&id=173&Itemid=152)
  8-fixture LED bar."
  {:doc/format :markdown}
  ([]
   (weather-system :26-channel))
  ([mode]
   (assoc (case mode
            ;; TODO: missing channels once we have definition support for them
            :7-channel {:channels [(chan/dimmer 1) (chan/color 2 :red) (chan/color 3 :green) (chan/color 4 :blue)]}
            :26-channel {:channels [(chan/dimmer 1)]
                         :heads (map ws-head (range 8))})
          :name "Blizzard Weather System"
          :mode mode)))
