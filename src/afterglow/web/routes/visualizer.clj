(ns afterglow.web.routes.visualizer
  (:require [afterglow.effects.movement :as movement]
            [afterglow.fixtures :as fixtures]
            [afterglow.show :as show]
            [afterglow.web.layout :as layout]
            [com.evocomputing.colors :as colors])
  (:import [javax.media.j3d Transform3D]
           [javax.vecmath Matrix3d Vector3d]))

(def max-lights
  "The maximum number of lights that the visualizer will attempt to
  render. Adjust this based on the performance of your graphics hardware."
  4)

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
  [lights show scale]
  (let [offsets (shader-offsets show scale)]
    (partition 3 (for [[_ head] lights
                       [axis flip] [["x" -1] ["y" -1] ["z" 1]]]
                   (* flip (+ (* ((keyword axis) head) scale)
                              ((keyword (str axis "-offset")) offsets)))))))

(defn adjusted-rotations
  "Get the current orientations of the active spotlights for the visualizer.
  Return as a series of columns of the rotation matrices, since it
  looks like WebGL or THREE.js is a lot happier passing vectors than
  matrices as uniforms."
  [show]
  (apply concat (for [[_ head] (:visualizer-visible @(:dimensions show))]
                  (let [rot (Matrix3d.)
                        adjust (Matrix3d.)]
                    ;; Transform from show orientation to shader orientation
                    (.rotX adjust (/ Math/PI 2))
                    (.get (:rotation head) rot)
                    (.mulNormalize rot adjust)
                    [[(.-m00 rot) (.-m10 rot) (.-m20 rot)]
                     [(.-m01 rot) (.-m11 rot) (.-m21 rot)]
                     [(.-m02 rot) (.-m12 rot) (.-m22 rot)]]))))

(defn visualizer-pan-tilt
  "Given a head and DMX pan and tilt values, calculate the pan
  and tilt angles to send the visualizer"
  [head pan tilt]
  (let [rotation (movement/current-rotation head pan tilt)
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

;; TODO: Need to take into account dimmers, and someday be based on raw DMX
;;       values rather than the current higher-level abstractions.
(defn active?
  "Check whether the given fixture (represented as a tuple of [id
  spec], as found in a show's :visualizer-visible map) should be
  included in the current visualizer frame, because it is emitting
  light."
  [show [id fixture-or-head]]
  (when-let [color ((keyword (str "color-" id)) (:previous @(:movement show)))]
    (pos? (colors/lightness color))))

(defn active-fixtures
  "Return the fixtures which should currently be rendered, because they
  are emitting light."
  [show]
  (filter (partial active? show) (:visualizer-visible @(:dimensions show))))

(defn current-pan-tilts
  "Get the current pan and tilt values of the active spotlights for the visualizer.
  Return as a series of two-element vectors of pan and tilt angles in
  the perspective of the visualizer, to save space compared to sending actual
  rotation matrices."
  [lights show]
  (for [[id head] lights]
    (let [[pan tilt] ((keyword (str "pan-tilt-" id)) (:previous @(:movement show)) [0 0])]
      (visualizer-pan-tilt head pan tilt))))

(defn byte-to-float
  "Convert a one-byte color component, as used in Afterglow, to a
  floating point color component as used in OpenGL, where 255 becomes
  1.0."
  [val]
  (double (/ val 255)))

(defn current-colors
  "Get the current color values of the active spotlights for the visualizer.
  Return as a series of four-element vectors of red, green, blue, and alpha."
  [lights show]
  (for [[id head] lights]
    (let [color ((keyword (str "color-" id)) (:previous @(:movement show)))]
      [(byte-to-float (colors/red color)) (byte-to-float (colors/green color))
       (byte-to-float (colors/blue color)) (byte-to-float (colors/alpha color))])))

(defn page
  "Render the real-time show preview."
  [show-id]
  (let [[show description] (get @show/shows (Integer/valueOf show-id))
        scale (shader-scale show)
        lights (take max-lights (active-fixtures show))]
    (layout/render
     "visualizer.html" {:show show
                        :timestamp (:timestamp @(:dimensions show))
                        :count (count lights)
                        :positions (adjusted-positions lights show scale)
                        :colors (current-colors lights show)
                        :rotations (current-pan-tilts lights show)})))

(defn shader
  "Render a GLSL shader capable of volumetric rendering of enough
  lights to accommodate the current show."
  [show-id]
  (let [[show description] (get @show/shows (Integer/valueOf show-id))
        scale (shader-scale show)]
    (layout/render-with-type
     "fragment.glsl" "x-shader/x-fragment"
     {:show show
      :scale scale
      :max-lights (min max-lights (count (:visualizer-visible @(:dimensions show))))})))

(defn update-preview
  "Render updated lighting information for the preview."
  [show-id]
  (let [[show description] (get @show/shows (Integer/valueOf show-id))
        scale (shader-scale show)
        lights (take max-lights (active-fixtures show))]
    (layout/render
     "current-scene.json" {:timestamp (:timestamp @(:dimensions show))
                           :count (count lights)
                           :positions (adjusted-positions lights show scale)
                           :colors (current-colors lights show)
                           :rotations (current-pan-tilts lights show)})))
