(ns afterglow.fixtures.american-dj
  "Definitions for fixtures provided by [American DJ](http://adj.com)."
  {:doc/format :markdown}
  (:require [afterglow.channels :as chan]))

(defn hypnotic-rgb
  "[Hypnotic RGB Laser](http://www.adj.com/hypnotic-rgb). Red, green,
  and blue lasers with a web-like diffraction grating providing very
  pretty effects for the price."
  []
  {:name "American DJ Hypnotic RGB Laser"
   :channels [(chan/functions :control 1 0 nil 8 "Beam Red" 38 "Beam Red Green" 68 "Beam Green"
                              98 "Beam Green Blue" 128 "Beam Blue" 158 "Beam Red Blue" 188 "Beam Red Green Blue"
                              218 "Beam All Random" 248 :sound-active)
              (chan/functions :control 2 0 nil
                              10 {:type :beams-ccw
                                  :var-label "CCW (fast->slow)"
                                  :range :variable}
                              121 nil
                              135 {:type :beams-cw
                                   :var-label "CW (slow->fast)"
                                   :range :variable}
                              246 nil)]})
