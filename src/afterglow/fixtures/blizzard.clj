(ns
    ^{:doc "Models for fixtures provided by Blizzard Lighting"}
  afterglow.fixtures.blizzard
  (:require [afterglow.channels :as chan]
            [taoensso.timbre :as timbre :refer [error info debug]]))

;; TODO functions for rotational tranformatons
;; TODO multi-head support, with relative locations

(defn blade-rgbw
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
          :mode mode))
  )
