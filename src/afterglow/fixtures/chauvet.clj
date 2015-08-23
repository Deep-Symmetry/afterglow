(ns afterglow.fixtures.chauvet
  "Models for fixtures provided by [Chauvet Lighting](http://www.chauvetlighting.com)."
  {:doc/format :markdown}
  (:require [afterglow.channels :as chan]
            [afterglow.effects.channel :as chan-fx]
            [afterglow.fixtures.qxf :refer [sanitize-name]]))

(defn color-strip-mini
  "[ColorStrip](http://www.chauvetlighting.com/colorstrip.html) LED fixture.
  Also works with the ColorStrip Mini.

  This was created by Afterglow from the QLC+ Fixture Definintion
  (.qxf) file, and revised by James Elliott.

  The original fixture defintition was created by JL Griffin
  using Q Light Controller version 3.1.0.

  QLC+ Fixture Type: Color Changer."
  {:doc/format :markdown}
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

(defn intimidator-spot-led-150
  "[Intimidator Spot LED 150](http://www.chauvetlighting.com/intimidator-spot-led-150.html) moving yoke.

  This fixture can be configured to use either 6 or 11 DMX channels.
  If you do not specify a mode when patching it, `:11-channel` is
  assumed; you can pass a value of `:6-channel` for `mode` if you are
  using it that way.

  This was created by Afterglow from the QLC+ Fixture Definintion
  (.qxf) file, and heavily revised by James Elliott.

  The original fixture defintition was created by Tavon Markov
  using Q Light Controller Plus version 4.8.3 GIT.

  QLC+ Fixture Type: Moving Head."
  {:doc/format :markdown}
  ([]
   (intimidator-spot-led-150 :11-channel))
  ([mode]
   (let [build-color-wheel (fn [channel]
                             (chan/functions :color channel
                                             0 "Color Wheel Open"
                                             (range 6 47 6) (chan/color-wheel-hue ["yellow" "magenta" "green" "red"
                                                                                   "cyan" "orange" "blue"])
                                             48 "Color Wheel Light Green"
                                             54 (chan/color-wheel-hue 45 :label "Color Wheel Amber")
                                             64 "Color Wheel White + Yellow"
                                             70 "Color Wheel Yellow + Magenta"
                                             76 "Color Wheel Magenta + Green"
                                             82 "Color Wheel Green + Red"
                                             88 "Color Wheel Red + Cyan"
                                             94 "Color Wheel Cyan + Orange"
                                             100 "Color Wheel Orange + Blue"
                                             106 "Color Wheel Blue + Light Green"
                                             112 "Color Wheel Light Green + Amber"
                                             118 "Color Wheel Amber + White"
                                             128 {:type :color-clockwise
                                                  :label "Color Wheel Clockwise (fast->slow)"
                                                  :var-label "CW (fast->slow)"
                                                  :range :variable}
                                             192 {:type :color-counterclockwise
                                                  :label "Color Wheel Counterclockwise (slow->fast)"
                                                  :var-label "CCW (fast->slow)"
                                                  :range :variable}))
         build-shutter (fn [channel]
                         (chan/functions :shutter channel
                                         0 "Shutter Closed"
                                         4 "Shutter Open"
                                         8 {:type :strobe
                                            :label "Strobe (0-20Hz)"
                                            :scale-fn (partial chan-fx/function-value-scaler 0 200)
                                            :range :variable}
                                         216 "Shutter Open 2"))
         gobo-names ["Quotes" "Warp Spots" "4 Dots" "Sail Swirl" "Starburst" "Star Field" "Optical Tube"
                     "Sun Swirl" "Star Echo"]
         build-gobo-entries (fn [shake? names]
                              (map (fn [entry]
                                     (let [label (str "Gobo " entry (when shake? " shake"))
                                           type-key (keyword (sanitize-name label))]
                                       (merge {:type type-key
                                               :label label
                                               :range (if shake? :variable :fixed)}
                                              (when shake? {:var-label "Shake Speed"}))))
                                   names))
         build-gobo-wheel (fn [channel]
                            (chan/functions :gobo channel
                                            (range 0 63 6) (build-gobo-entries false (concat ["Open"] gobo-names))
                                            (range 64 121 6) (build-gobo-entries true (reverse gobo-names))
                                            122 "Gobo Open 2"
                                            128 {:type :gobo-clockwise
                                                 :label "Gobo Clockwise Speed"
                                                 :var-label "CW Speed"
                                                 :range :variable}
                                            192 {:type :gobo-counterclockwise
                                                 :label "Gobo Counterclockwise Speed"
                                                 :var-label "CCW Speed"
                                                 :range :variable}))]
        (merge {:name "Intimidator Spot LED 150"
                :mode mode}
               (case mode
                 :11-channel
                 {:pan-center 128 :pan-half-circle 128 ; TODO: Fix these values
                  :tilt-center 128 :tilt-half-circle 128 ; TODO: Fix these values
                  :channels [(chan/pan 1 3)
                             (chan/tilt 2 4)
                             (chan/fine-channel :pan-tilt-speed 5
                                                :function-name "Pan / Tilt Speed"
                                                :var-label "P/T fast->slow")
                             (build-color-wheel 6)
                             (build-shutter 7)
                             (chan/dimmer 8)
                             (build-gobo-wheel 9)
                             (chan/functions :control-functions 10
                                             0 nil
                                             8 "Blackout while moving Pan/Tilt"
                                             28 "Blackout while moving Gobo Gheel"
                                             48 "Disable blackout while Pan/Tilt / moving Gobo Wheel"
                                             68 "Blackout while moving Color Wheel"
                                             88 "Disable blackout while Pan/Tilt / moving Color Wheel"
                                             108 "Disable blackout while moving Gobo/Color Wheel"
                                             128 "Diable blackout while moving all options"
                                             148 "Reset Pan"
                                             168 "Reset Tilt"
                                             188 "Reset Color Wheel"
                                             208 "Reset Gobo Wheel"
                                             228 "Reset All Channels"
                                             248 nil)
                             (chan/functions :movement-macros 11
                                             0 nil
                                             (range 8 135 16) "Program"
                                             (range 136 255 16) "Sound Active")]}
                 :6-channel
                 {:pan-center 128 :pan-half-circle 128 ; TODO: Fix these values
                  :tilt-center 128 :tilt-half-circle 128 ; TODO: Fix these values
                  :channels [(chan/pan 1)
                             (chan/tilt 2)
                             (build-color-wheel 3)
                             (build-shutter 4)
                             (chan/dimmer 5)
                             (build-gobo-wheel 6)]})))))

(defn kinta-x
  "[Kinta X](http://www.chauvetlighting.com/kinta-x.html) derby effect."
  {:doc/format :markdown
   :author "James Elliott"}
  []
  {:channels [(chan/functions :control 1
                              0 "LEDs off"
                              15 "LEDs on"
                              101 "Auto")
              (chan/functions :rotation 2
                              0 nil
                              1 :counter-clockwise
                              86 :clockwise
                              171 :back-and-forth)]
   :name "Kinta X"})

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
                                                        :scale-fn (partial chan-fx/function-value-scaler 0.87 25)
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
                                                       :scale-fn (partial chan-fx/function-value-scaler 0.87 25)
                                                       :label "Strobe (0.87Hz->25Hz)"})]}
            :6-channel {:channels [(chan/color 1 :red) (chan/color 2 :green) (chan/color 3 :blue)
                                   (chan/color 4 :amber :hue (when mix-amber 45)) (chan/color 5 :white)
                                   (chan/color 6 :uv :label "UV" :hue (when mix-uv 270))]})
          :name "Chauvet SlimPAR Hex 3 IRC"
          :mode mode)))
