(ns afterglow.controllers.color
  "Provides support for adjusting components of a show variable
  containing a color using any MIDI controller."
  {:author "James Elliott"}
  (:require [afterglow.controllers :as controllers]
            [afterglow.midi :as amidi]
            [afterglow.show :as show]
            [afterglow.show-context :refer [*show* with-show]]
            [com.evocomputing.colors :as colors]
            [taoensso.timbre :as timbre]
            [taoensso.truss :as truss :refer (have have! have?)]))

(defn add-midi-control-to-color-mapping
  "Cause specified `component` of the color value stored in the
  specified `variable` in [[*show*]] to be updated by any MIDI
  controller-change messages from the specified device sent on the
  specified `channel` and `control-number`.

  The first MIDI input source whose device matches the
  `device-filter` (using [[filter-devices]]) will be chosen.

  The values you can pass for `component` include `:red`, `:green`,
  `:blue`, `:hue`, `:saturation`, and `:lightness`. 

  As control changes come in, they will be mapped from the MIDI
  range (of `0` to `127`) to the legal range for the chosen color
  component. If `:min` and/or `:max` are specified, the values will be
  scaled to the supplied range instead, but will still be clamped to
  fit within legal values for the chosen color component (RGB values
  can range from `0` to `255`, hues from `0` to `360`, and saturation
  and lightness from `0` to `100`).

  If `:transform-fn` is specified, it will be called with the scaled
  color component value, and its return value will be assigned to the
  color, although once again the value will be clamped to within the
  legal range for the component.

  Returns a MIDI mapping function, which can be passed
  to [[remove-control-mapping]] if you later want to stop the
  MIDI control affecting the color variable."
  [device-filter channel control-number variable component & {:keys [min max transform-fn]}]
  {:pre [(have? some? device-filter) (have? integer? channel) (have? #(<= 0 % 15) channel)
         (have? integer? control-number) (have? #(<= 0 % 127) control-number)
         (have? some? variable) (have? #(= (type (show/get-variable %)) :com.evocomputing.colors/color) variable)
         (have? #{:red :green :blue :hue :saturation :lightness} component)
         (have? #(or (not %) (number? %)) max) (have? #(or (not %) (number? %)) min)
         (have? #(or (not %) (ifn? %)) transform-fn)]}
  (let [min (or min 0)
        max (or max (case component
                      (:red :green :blue) 255
                      :hue 360
                      (:saturation :lightness) 100))
        show *show*  ; Bind so we can pass it to update function running on another thread
        scale-fn (if (< min max)
                   (let [range (- max min)]
                     (fn [midi-val] (float (+ min (/ (* midi-val range) 127)))))
                   (let [range (- min max)]
                     (fn [midi-val] (float (+ max (/ (* midi-val range) 127))))))
        calc-fn (apply comp (filter identity [transform-fn scale-fn]))
        update-fn (fn [msg]
                    (with-show show
                      (let [color (show/get-variable variable)
                            rgb {:r (colors/red color) :g (colors/green color) :b (colors/blue color)}
                            hsl {:h (colors/hue color) :s (colors/saturation color) :l (colors/lightness color)}
                            value (calc-fn (:velocity msg))]
                        (show/set-variable! variable
                                            (case component
                                              :red (colors/create-color
                                                    (merge rgb {:r (colors/clamp-rgb-int (Math/round value))}))
                                              :green (colors/create-color
                                                      (merge rgb {:g (colors/clamp-rgb-int (Math/round value))}))
                                              :blue (colors/create-color
                                                     (merge rgb {:b (colors/clamp-rgb-int (Math/round value))}))
                                              :hue (colors/create-color (merge hsl {:h (colors/clamp-hue value)}))
                                              :saturation (colors/create-color
                                                           (merge hsl {:s (colors/clamp-percent-float value)}))
                                              :lightness (colors/create-color
                                                          (merge hsl {:l (colors/clamp-percent-float value)})))))))]
    (amidi/add-control-mapping device-filter channel control-number update-fn)
    update-fn))
