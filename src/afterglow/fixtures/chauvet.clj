(ns afterglow.fixtures.chauvet
  "Models for fixtures provided by [Chauvet Lighting](http://www.chauvetlighting.com)."
  {:doc/format :markdown}
  (:require [afterglow.channels :as chan]
            [afterglow.effects.channel :refer [function-value-scaler]]))

(defn color-strip-mini
  "[ColorStrip](http://www.chauvetlighting.com/colorstrip.html) LED fixture.
  Also works with the ColorStrip Mini.

  This was created by Afterglow from the QLC+ Fixture Definintion
  (.qxf) file, and revised by James Elliott.

  The original fixture defintition was created by JL Griffin
  using Q Light Controller version 3.1.0.

  QLC+ Fixture Type: Color Changer."
  []
  {:channels [(chan/functions :control 1
                              0 "Blackout"
                              10 "Red"
                              20 "Green"
                              30 "Blue"
                              40 "Yellow"
                              50 "Magenta"
                              60 "Cyan"
                              70 "White"
                              (range 80 139 10) "Auto Change"
                              (range 140 209 10) "Color Chase"
                              210 "RGB Control"
                              220 "Chase Fade"
                              230 "Auto Run")
              (chan/color 2 :red)
              (chan/color 3 :green)
              (chan/color 4 :blue)]
   :name "ColorStrip"})

(defn led-techno-strobe
  "[LED Techno Strobe](http://www.chauvetlighting.com/led-techno-strobe.html)
  strobe light."
  {:doc/format :markdown
   :author "James Elliott"}
  []
  {:channels [(chan/functions :control 1
                              0 "Intensity Control"
                              (range 30 209 30) "Program"
                              210 :sound-active)
              (chan/functions :strobe 2 0 nil
                              1 :strobe)  ; TODO: Measure speed, add scale-fn
              (chan/dimmer 3)]  ; TODO: Manual implies this might be reversed? If so, what a pain.
   :name "LED Techno Strobe"})

(defn led-techno-strobe-rgb
  "[LED Techno Strobe RGB](http://www.chauvetlighting.com/led-techno-strobe-rgb.html)
  color mixing strobe light.

  This was created by Afterglow from the QLC+ Fixture Definintion
  (.qxf) file, and revised by James Elliott.

  The original fixture defintition was created by Davey D
  using Q Light Controller Plus version 4.6.0.

  QLC+ Fixture Type: Color Changer."
  {:doc/format :markdown}
  []
  {:channels [(chan/functions :control 1
                              0 "RGB Control"
                              (range 25 224 25) "Program"
                              225 :sound-active)
              (chan/color 2 :red)
              (chan/color 3 :green)
              (chan/color 4 :blue)
              (chan/functions :strobe 5 0 nil
                              1 :strobe)  ; TODO: Measure speed, add scale-fn
              (chan/dimmer 6)]
   :name "LED Techno Strobe RGB"})

(defn slimpar-hex3-irc
  "[SlimPAR HEX 3 IRC](http://www.chauvetlighting.com/slimpar-hex3irc.html)
  six-color low-profile LED PAR.

  This fixture can be configured to use either 6, 8 or 12 DMX
  channels. If you do not specify a mode when patching it,
  `:12-channel` is assumed; you can pass a value of `:6-channel` or
  `:8-channel` for `mode` if you are using it that way.

  When you pass a mode, you can also control whether the amber and UV
  channels are mixed in when creating colors by passing a boolean
  value with `:mix-amber` and `:mix-uv`. The default for each is
  `true`."
  {:doc/format :markdown
   :author "James Elliott"}
  ([]
   (slimpar-hex3-irc :12-channel))
  ([mode & {:keys [mix-amber mix-uv] :or {mix-amber true mix-uv true}}]
   (assoc (case mode
            :12-channel {:channels [(chan/dimmer 1) (chan/color 2 :red) (chan/color 3 :green) (chan/color 4 :blue)
                                    (chan/color 5 :amber :hue (when mix-amber 45)) (chan/color 6 :white)
                                    (chan/color 7 :uv :label "UV" :hue (when mix-uv 270))
                                    (chan/functions :strobe 8 0 nil
                                                    11 {:type :strobe
                                                        :scale-fn (partial function-value-scaler 0.87 25)
                                                        :label "Strobe (0.87Hz->25Hz)"})
                                    (chan/functions :color-macros 9 0 nil 16 :color-macros)
                                    (chan/functions :control 10 0 nil (range 11 200 50) "Program"
                                                    201 :sound-active-6-color 226 :sound-active)
                                    (chan/functions :program-speed 11 0 :program-speed)
                                    (chan/functions :dimmer-mode 12 0 "Dimmer Mode Manual" 52 "Dimmer Mode Off"
                                                    102 "Dimmer Mode Fast" 153 "Dimmer Mode Medium"
                                                    204 "Dimmer Mode Slow")]}
            :8-channel {:channels [(chan/dimmer 1) (chan/color 2 :red) (chan/color 3 :green) (chan/color 4 :blue)
                                   (chan/color 5 :amber :hue (when mix-amber 45)) (chan/color 6 :white)
                                   (chan/color 7 :uv :label "UV" :hue (when mix-uv 270))
                                   (chan/functions :strobe 8 0 nil
                                                   11 {:type :strobe
                                                       :scale-fn (partial function-value-scaler 0.87 25)
                                                       :label "Strobe (0.87Hz->25Hz)"})]}
            :6-channel {:channels [(chan/color 1 :red) (chan/color 2 :green) (chan/color 3 :blue)
                                   (chan/color 4 :amber :hue (when mix-amber 45)) (chan/color 5 :white)
                                   (chan/color 6 :uv :label "UV" :hue (when mix-uv 270))]})
          :name "Chauvet SlimPAR Hex 3 IRC"
          :mode mode)))
