(ns afterglow.web.routes.visualizer
  (:require [afterglow.web.layout :as layout]
            [afterglow.fixtures :as fixtures]
            [afterglow.show :as show]
            [afterglow.show-context :refer [*show*]])
  (:import [javax.media.j3d Transform3D]
           [javax.vecmath Matrix3d Vector3d]))

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

(defn current-rotation
  "Given a head and pan and tilt values, calculate the current
  orientation of the head."
  [head pan tilt]
  (let [rotation (Transform3D. (:rotation head))]
    (when-let [pan-scale (:pan-half-circle head)]
      (let [dmx-pan (/ (- pan (:pan-center head)) pan-scale)
            adjust (Transform3D.)]
        (.rotY adjust (* Math/PI dmx-pan))
        (.mul rotation adjust)))
    (when-let [tilt-scale (:tilt-half-circle head)]
      (let [dmx-tilt (/ (- tilt (:tilt-center head) tilt-scale))
            adjust (Transform3D.)]
        (.rotX adjust (* Math/PI dmx-tilt))
        (.mul rotation adjust)))
    rotation))

(defn visualizer-pan-tilt
  "Given a head and DMX pan and tilt values, calculate the pan
  and tilt angles to send the visualizer"
  [head pan tilt]
  (let [rotation (current-rotation head pan tilt)
        visualizer-perspective (Transform3D.)
        direction (Vector3d. 0 0 1)]
    ;; Add a rotation so we are seeing the rotation from the
    ;; default perspectve of the visualizer.
    (.rotX visualizer-perspective (/ Math/PI 2))
    (.mul rotation visualizer-perspective)
    
    (.transform rotation direction)
    (let [rot-y (Math/atan2 (.x direction) (.z direction)) ;; Get pan
          new-direction (Vector3d. direction)] ;; Determine aiming vector after pan
      (.rotY visualizer-perspective (- rot-y))
      (.transform visualizer-perspective new-direction)
      [rot-y (- (Math/atan2 (.y direction) (.z direction)))])))

(defn current-pan-tilts
  "Get the current pan and tilt values of the active spotlights for the visualizer.
  Return as a series of two-element vectors of pan and tilt angles in
  the perspective of the visualizer, to save space compared to sending actual
  rotation matrices."
  [show]
  (for [[_ head] (:visualizer-visible @(:dimensions *show*))]
    (visualizer-pan-tilt head 0 0)))

;; TODO: These need to be parameterized by show, once we are managing a list of shows.
(defn page
  "Render the real-time show preview."
  []
  (let [scale (shader-scale *show*)]
    (layout/render
     "visualizer.html" {:timestamp (:timestamp @(:dimensions *show*))
                        :positions (adjusted-positions *show* scale)
                        :rotations (current-pan-tilts *show*)})))

(defn shader
  "Render a GLSL shader capable of volumetric rendering of enough
  lights to accommodate the current show."
  []
  (let [scale (shader-scale *show*)]
    (layout/render-with-type
     "fragment.glsl" "x-shader/x-fragment"
     {:scale scale
      :max-lights (count (:visualizer-visible @(:dimensions *show*)))})))
