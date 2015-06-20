(ns afterglow.web.routes.show-control
  (:require [afterglow.web.layout :as layout]
            [afterglow.rhythm :as rhythm]
            [afterglow.show :as show]
            [afterglow.controllers :as controllers]
            [com.evocomputing.colors :as colors]
            [compojure.core :refer [defroutes GET]]
            [ring.util.http-response :refer [ok]]))

(defn cue-view
  "Returns a nested structure of rows of cue information starting at
  the specified origin, with the specified width and height. Ideal for
  looping over and rendering in textual form, such as in a HTML table.
  Each cell contains a tuple [cue effect], the cue assigned to that
  grid location, and the currently-running effect, if any, launched
  from that cue. Cells which do not have associated cues will be nil."
  [show left bottom width height]
  (let [active-keys (show/active-effect-keys show)]
    (for [y (range (dec (+ bottom height)) (dec bottom) -1)]
      (for [x (range left (+ left width))]
        (when-let [[cue active] (show/find-cue-grid-active-effect show x y)]
          (let [ending (and active (:ending active))
                l-boost (if (zero? (colors/saturation (:color cue))) 10 0)
                color (colors/create-color
                       :h (colors/hue (:color cue))
                       ;; Figure the brightness. Active, non-ending cues are full brightness;
                       ;; when ending, they blink between middle and low. If they are not active,
                       ;; they are at middle brightness unless there is another active effect with
                       ;; the same keyword, in which case they are dim.
                       :s (colors/saturation (:color cue))
                       :l (+ (if active
                               (if ending
                                 (if (> (rhythm/metro-beat-phase (:metronome show)) 0.4) 20 40)
                                 65)
                               (if (active-keys (:key cue)) 20 40))
                             l-boost))]
            (assoc cue :x x :y y :id (str "cue-at-" x "-" y)
                   :style-color (str "style=\"background-color: " (colors/rgb-hexstr color) "\""))))))))

(defn show-page [id]
  (let [[show description] (get @show/shows (Integer/valueOf id))
        grid (cue-view show 0 0 8 8)]
    (layout/render "show.html" {:show show :title description :grid grid})))
