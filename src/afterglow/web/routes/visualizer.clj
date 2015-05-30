(ns afterglow.web.routes.visualizer
  (:require [afterglow.web.layout :as layout]
            [afterglow.fixtures :as fixtures]
            [afterglow.show :as show]
            [afterglow.show-context :refer [*show*]]))

(defn shader-scale
  "Determine how much we need to scale the show so it fits in the
  shader's bounding cube which has a width of 1.0, a height of 0.5,
  and a depth of 0.5. We are not going to shift lights down to the
  floor, though."
  [show]
  (double (apply min (map / [1 0.5 0.5]
                          (for [axis ["x" "y" "z"]]
                            (max 0.0001 (- ((keyword (str "max-" axis)) @(:dimensions show))
                                           (if (= axis "y")
                                             0
                                             ((keyword (str "min-" axis)) @(:dimensions show))))))))))

(defn axis-shift
  "Determine how much we need to translate show space points along an
  axis after they have been scaled to move them into the shader's
  bounding cube."
  [show axis origin scale]
  [(keyword (str axis "-offset"))
   (double (- origin (* scale (if (= axis "y")
                                0
                                ((keyword (str "min-" axis)) @(:dimensions show))))))])

;; TODO: Center the fixtures, don't put them flush to the bottom left.
(defn shader-offsets
  "Determine the values to add to the coordinates of scaled light positions
  to move them inside the shader's bounding cube, whose origin is
  [-0.5, -0.25, 0]."
  [show scale]
  (into {} (for [[axis origin] [["x" -0.5] ["y" -0.25] ["z" 0]]]
             (axis-shift show axis origin scale))))

(defn adjusted-positions
  "Move the spotlights so they all fit within the shader's bounding
  cube, which extends from [-0.5, 0.25, 0.5] to [0.5, -0.25, 0]."
  [show scale]
  (let [offsets (shader-offsets show scale)]
    (partition 3 (for [[id head] (:visualizer-visible @(:dimensions *show*))
                       [axis flip] [["x" -1] ["y" -1] ["z" 1]]]
                   (* flip (+ (* ((keyword axis) head) scale)
                              ((keyword (str axis "-offset")) offsets)))))))

;; TODO: These need to be parameterized by show, once we are managing a list of shows.
(defn page
  "Render the real-time show preview."
  []
  (let [scale (shader-scale *show*)]
    (layout/render
     "visualizer.html" {:timestamp (:timestamp @(:dimensions *show*))
                        :positions (adjusted-positions *show* scale)})))

(defn shader
  "Render a GLSL shader capable of volumetric rendering of enough
  lights to accommodate the current show."
  []
  (let [scale (shader-scale *show*)]
    (layout/render-with-type
     "fragment.glsl" "x-shader/x-fragment"
     {:scale scale
      :max-lights (count (:visualizer-visible @(:dimensions *show*)))})))
