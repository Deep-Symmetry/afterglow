(ns afterglow.web.routes.visualizer
  (:require [afterglow.web.layout :as layout]
            [afterglow.fixtures :as fixtures]
            [afterglow.show :as show]
            [afterglow.show-context :refer [*show*]])
  (:import [javax.media.j3d Transform3D]
           [javax.vecmath Matrix3d]))

(defn show-span
  "Determine the degree to which a show spreads over an axis. For the
  X and Z axes, this is simply the difference in bounding box
  coordinates. For Y, we want to preserve height from the floor, so we
  use zero as a lower bound on the minimum coordinate."
  [show axis]
  (let [dim @(:dimensions show)
        min-val ((keyword (str "min-" axis)) dim)
        lower-bound (if (= axis "y")
                      (min 0 min-val)
                      min-val)]
    (- ((keyword (str "max-" axis)) dim) lower-bound)))

(defn shader-scale
  "Determine how much we need to scale the show so it fits in the
  shader's bounding cube which has a width of 1.0, a height of 0.5,
  and a depth of 0.5. We are not going to shift lights down to the
  floor, though."
  [show]
  (double (apply min (map / [1 0.5 0.5]
                          (for [axis ["x" "y" "z"]]
                            (max 0.0001 (show-span show axis)))))))

(defn axis-shift
  "Determine how much we need to translate show space points along an
  axis after they have been scaled to move them into the shader's
  bounding cube. If there is extra room for this axis, center it
  within the bounding cube."
  [show axis origin available-span scale]
  (let [scaled-span (* scale (show-span show axis))
        padding (/ (- available-span scaled-span) 2)
        scaled-smallest-value (* scale (if (= axis "y")
                                         0
                                         ((keyword (str "min-" axis)) @(:dimensions show))))
        full-shift (double (- origin scaled-smallest-value))]
    (println axis "ssv:" scaled-smallest-value "origin:" origin "padding:" padding)
    [(keyword (str axis "-offset"))
     (+ full-shift padding)]))

(defn shader-offsets
  "Determine the values to add to the coordinates of scaled light positions
  to move them inside the shader's bounding cube, whose origin is
  [-0.5, -0.25, 0]."
  [show scale]
  (into {} (for [[axis origin available-span] [["x" -0.5 1.0] ["y" -0.25 0.5] ["z" 0 0.5]]]
             (axis-shift show axis origin available-span scale))))

(defn adjusted-positions
  "Move the spotlights so they all fit within the shader's bounding
  cube, which extends from [-0.5, 0.25, 0.5] to [0.5, -0.25, 0]."
  [show scale]
  (let [offsets (shader-offsets show scale)]
    (partition 3 (for [[_ head] (:visualizer-visible @(:dimensions *show*))
                       [axis flip] [["x" -1] ["y" -1] ["z" 1]]]
                   (* flip (+ (* ((keyword axis) head) scale)
                              ((keyword (str axis "-offset")) offsets)))))))

(defn adjusted-rotations
  "Get the current orientations of the active spotlights for the visualizer.
  Return as a series of columns of the rotation matrices, since it
  looks like WebGL or THREE.js is a lot happier passing vectors than
  matrices as uniforms."
  [show]
  (apply concat (for [[_ head] (:visualizer-visible @(:dimensions *show*))]
                  (let [rot (Matrix3d.)
                        adjust (Matrix3d.)]
                    ;; Transform from show orientation to shader orientation
                    (.rotX adjust (/ Math/PI 2))
                    (.get (:rotation head) rot)
                    (.mulNormalize rot adjust)
                    [[(.-m00 rot) (.-m10 rot) (.-m20 rot)]
                     [(.-m01 rot) (.-m11 rot) (.-m21 rot)]
                     [(.-m02 rot) (.-m12 rot) (.-m22 rot)]]))))

;; TODO: These need to be parameterized by show, once we are managing a list of shows.
(defn page
  "Render the real-time show preview."
  []
  (let [scale (shader-scale *show*)]
    (layout/render
     "visualizer.html" {:timestamp (:timestamp @(:dimensions *show*))
                        :positions (adjusted-positions *show* scale)
                        :rotations (adjusted-rotations *show*)})))

(defn shader
  "Render a GLSL shader capable of volumetric rendering of enough
  lights to accommodate the current show."
  []
  (let [scale (shader-scale *show*)]
    (layout/render-with-type
     "fragment.glsl" "x-shader/x-fragment"
     {:scale scale
      :max-lights (count (:visualizer-visible @(:dimensions *show*)))})))
