(ns afterglow.fixtures.blizzard
  "Models for fixtures provided by [Blizzard
  Lighting](http://www.blizzardlighting.com)."
  {:doc/format :markdown}
  (:require [afterglow.channels :as chan]))

;; TODO functions for rotational tranformatons

(defn blade-rgbw
  "[Blade RGBW](http://www.blizzardlighting.com/index.php?option=com_k2&view=item&layout=item&id=177&Itemid=157)
  moving head."
  {:doc/format :markdown}
  ([]
   (blade-rgbw :15-channel))
  ([mode]
   (assoc (case mode
            ;; TODO missing channels once we have definition support for them
            :15-channel {:channels [(chan/pan 1 3) (chan/tilt 2 4)
                                    (chan/color 6 :red) (chan/color 7 :green) (chan/color 8 :blue) (chan/color 9 :white)
                                    (chan/dimmer 12)]}
            :11-channel {:channels [(chan/pan 1 3) (chan/tilt 2 4)
                                    (chan/color 6 :red) (chan/color 7 :green) (chan/color 8 :blue) (chan/color 9 :white)
                                    (chan/dimmer 10)]})
          :name "Blizzard Blade RGBW"
          :mode mode)))


(defn- ws-head
  "Creates a head definition for one head of the Weather System"
  [index]
  {:channels [(chan/color (+ 2 (* 3 index)) :red) (chan/color (+ 3 (* 3 index)) :green) (chan/color (+ 4 (* 3 index)) :blue)]})

(defn weather-system
  "[Weather System](http://www.blizzardlighting.com/index.php?option=com_k2&view=item&layout=item&id=173&Itemid=152)
  8-fixture LED bar."
  {:doc/format :markdown}
  ([]
   (weather-system :26-channel))
  ([mode]
   (assoc (case mode
            ;; TODO missing channels once we have definition support for them
            :7-channel {:channels [(chan/dimmer 1) (chan/color 2 :red) (chan/color 3 :green) (chan/color 4 :blue)]}
            :26-channel {:channels [(chan/dimmer 1)]
                         :heads (map ws-head (range 8))})
          :name "Blizzard Weather System"
          :mode mode)))
