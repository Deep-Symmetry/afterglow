(ns afterglow.fixtures.chauvet
  "Models for fixtures provided by Chauvet Lighting."
  (:require [afterglow.channels :as chan]))

;; TODO functions for rotational tranformatons
;; TODO multi-head support, with relative locations
;; TODO make mixing of amber and UV keyword flags in the constructor, defaulting to on

(defn slimpar-hex3-irc
  ([]
   (slimpar-hex3-irc :12-channel-mix-uv))
  ([mode]
   (assoc (case mode
            ;; TODO missing channels once we have definition support for them
            :12-channel {:channels [(chan/dimmer 1) (chan/color 2 :red) (chan/color 3 :green) (chan/color 4 :blue)
                                    (chan/color 5 :amber :hue 45) (chan/color 6 :white) (chan/color 7 :uv :label "UV")]}
            :8-channel {:channels [(chan/dimmer 1) (chan/color 2 :red) (chan/color 3 :green) (chan/color 4 :blue)
                                   (chan/color 5 :amber :hue 45) (chan/color 6 :white) (chan/color 7 :uv :label "UV")]}
            :6-channel {:channels [(chan/color 1 :red) (chan/color 2 :green) (chan/color 3 :blue)
                                   (chan/color 4 :amber :hue 45) (chan/color 5 :white) (chan/color 6 :uv :label "UV")]}
            :12-channel-mix-uv {:channels [(chan/dimmer 1) (chan/color 2 :red) (chan/color 3 :green) (chan/color 4 :blue)
                                    (chan/color 5 :amber :hue 45) (chan/color 6 :white) (chan/color 7 :uv :label "UV" :hue 270)]}
            :8-channel-mix-uv {:channels [(chan/dimmer 1) (chan/color 2 :red) (chan/color 3 :green) (chan/color 4 :blue)
                                   (chan/color 5 :amber :hue 45) (chan/color 6 :white) (chan/color 7 :uv :label "UV" :hue 270)]}
            :6-channel-mix-uv {:channels [(chan/color 1 :red) (chan/color 2 :green) (chan/color 3 :blue)
                                   (chan/color 4 :amber :hue 45) (chan/color 5 :white) (chan/color 6 :uv :label "UV" :hue 270)]})
          :name "Chauvet SlimPAR Hex 3 IRC"
          :mode mode)))
