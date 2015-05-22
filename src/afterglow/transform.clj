(ns afterglow.transform
  "Functions for modeling light position and rotation. If you want to
  make use of Afterglow's spatial reasoning capabilities, you need to
  tell it, when patching a fixture, the location and orientation of
  that fixture.

  The coordinate system in afterglow is from the perspective of the
  audience: Standing facing your show, the X axis passes through it
  increasing from left to right, the Y axis passes through it
  increasing away from the floor, and the Z axis passes through it
  increasing towards the audience. Pick an origin when setting up your
  show; in our case it is easiest to use the center of gravity of the
  main truss in our lighting rig, something else may be better for
  you.

  When hanging your lights, measure how far the center of the light is
  from the origin of the show, in the X, Y, and Z directions. You
  don't need to get this precise to the millimeter level, but having
  it roughly right will make your spatial cues look great.

  Distances in Afterglow are expressed in meters, though there are
  functions in this namespace for translating other units, if you do
  not have metric measuring tools handy.

  Fixture definitions for Afterglow should include information about
  the positions of the light-emitting heads as offsets from the origin
  of the fixture, and a description of how to identify the origin of
  the fixture, so that when you patch the fixture and tell Afterglow
  the position of that origin with respect to the origin of the light
  show itself, it can calculate the location of each head.

  For this to work you also need to tell Afterglow the orientation in
  which you have hung the fixture, expressed as a rotation away from
  the standard orientation described in the fixture definition. Yes,
  this can be painful to figure out, especially the first few times,
  and we have ideas about an iPhone app to help automate this, using
  the phone's camera and ability to track its own orientation. But
  until then, figure out how much you have rotated the fixture, in
  radians, around the X, then Y, then Z axes, in that order, and feed
  that in as well when patching it.

  Again there are functions in this namespace to convert degrees to
  radians if they are easier for you to work with. In many cases,
  hopefully, the rotations will be simple. For example, if a light is
  hanging backwards with respect to its reference orientation, that is
  a rotation of zero around the X axis, Pi radians about the Y axis,
  and zero about the Z axis.

  If a fixture does not have multiple heads, and is not a moving head
  fixture, you can generally ignore the rotation completely when
  patching it. Getting the orientation right is most important for
  moving heads, because Afterglow relies on having that information in
  order to figure out how to aim the light where you want it aimed."

  {:author "James Elliott"
   :doc/format :markdown}
  (:require [afterglow.channels :as chan]
            [afterglow.show-context :refer :all]
            [clojure.math.numeric-tower :as math])
  (:import [javax.media.j3d Transform3D]
           [javax.vecmath Point3d Vector3d]))

(defn inches
  "Converts a number of inches to the corresponding number of meters."
  [in]
  (* in 0.0254))

(defn degrees
  "Converts a number of degrees into the corresponding number of
  radians."
  [deg]
  (* (/ deg 180) Math/PI))

(defn- transform-head
  "Determine the position and orientation of a fixture's head."
  [fixture head]
  (let [rotation (Transform3D. (:rotation fixture)) ; Not immutable, so copy!
        point (Point3d. (:x head 0.0) (:y head 0.0) (:z head 0.0))
        head-euler-angles (Vector3d. (:x-rotation head 0.0) (:y-rotation head 0.0) (:z-rotation head 0.0))
        base-head-rotation (Transform3D.)
        axis (Transform3D.)]

    ;; Transform the location of the head based on the fixture rotation
    (.transform rotation point)

    ;; Calculate the compound rotation applied to the head
    (.setEuler base-head-rotation head-euler-angles)
    (.mul rotation base-head-rotation)
    ;; fixture-rotation now holds the result of both rotations: First the rotation
    ;; from hanging the fixture, then the rotation of the individual head with
    ;; respect to the fixture itself, if any.

    (assoc head
           :rotation rotation
           :x (+ (:x fixture) (.-x point))
           :y (+ (:y fixture) (.-y point))
           :z (+ (:z fixture) (.-z point)))))

(defn- transform-heads
  "Determine the positions and orientations of the individual heads of
  a fixture once its own position and orientation have been
  established."
  [fixture]
  (let [transformer (partial transform-head fixture)]
    (update-in fixture [:heads] #(map transformer %))))

(defn transform-fixture
  "Determine the positions and orientations of the fixture and its
  heads when it is patched into a show. X, Y, and Z are the position
  of the fixture's origin point with respect to the show's origin, and
  x-rotation, y-rotation, and z-rotation are the counter-clockwise
  rotations around those axes, in that order, needed to get the
  fixture from its reference orientation to the orientation in which
  it was actually hung."
  [fixture x y z x-rotation y-rotation z-rotation]
  (let [euler (Vector3d. x-rotation y-rotation z-rotation)
        rotation (Transform3D.)]
    ;; Calculate the compound rotation applied to the fixture
    (.setEuler rotation euler)
    (transform-heads (assoc fixture :x x :y y :z z :rotation rotation
                            :x-rotation x-rotation :y-rotation y-rotation :z-rotation z-rotation))))

(defn show-head-positions
  "To help sanity-check fixtures' position and orientation after
  patching them, displays the positions of all heads of the list of
  fixtures supplied. In the resulting list, top-level fixtures will be
  identifiable by the presence of their :key entry in the map that
  includes their position, and will be followed by their heads, if
  any, until the next top-level fixture entry."
  [fixtures]
  (map #(select-keys % [:key :x :y :z]) (chan/expand-heads fixtures)))


(def ^:private two-pi (* 2 Math/PI))

(defn invert-direction
  "Transform a direction vector in show coordinate space to the way it 
  appears to a head or fixture that has been rotated when hanging."
  [fixture x y z]
  (let [rotation (Transform3D. (:rotation fixture))
        direction (Vector3d. x y z)]
    (.invert rotation)
    #_(.normalize direction)
    (.transform rotation direction)
    (taoensso.timbre/debug "Inverted direction:" direction)
    direction))

(defn angle-to-dmx-value
  "Given an angle in radians, where positive means counterclockwise
  around the axis, determine the DMX value needed to achieve that
  amount of rotation of a fixture away from its center position, given
  the DMX value of that center position, and the amount the DMX value
  must change to cause a counterclockwise rotation halfway around a
  circle. The return value may not be a legal DMX value if the fixture
  cannot rotate that far; this will be weeded out in other parts of
  the algorithm."
  [angle center-value half-circle-value]
  (+ center-value (* (/ angle Math/PI) half-circle-value)))

(defn distance-from-legal-dmx-value
  "Measure how far a value is from being within the legal DMX range,
  to help choose the least-worst solution if a fixture cannot actually
  reach the desired position."
  [value]
  (if (neg? value)
    (- value)
    (- value 255)))

(defn find-closest-legal-dmx-value-for-angle
  "Given a desired rotation from the center position (expressed in
  radians, with positive values representing counterclockwise
  rotations around the axis), the DMX value at which the fixture is in
  its center position, and the amount the DMX value must change in
  order to cause a counterclockwise rotation halfway around a circle,
  find the DMX value which achieves that rotation, or if that is not
  possible, the legal value which yields the closest possible
  approximation, considering forward and backward rotations beyond a
  full circle if necessary. Return a tuple of the resulting value and
  a boolean indicating whether it achieves the desired angle.

  If there is more than one legal solution, return the one that is
  closest to the specified target value. If no target value is
  specified (using the keyword parameter :target-value), then use the
  fixture's center position as the default target value to stay close
  to."
  [angle center-value half-circle-value & {:keys [target-value] :or {target-value center-value}}]
  (let [candidates (map #(angle-to-dmx-value % center-value half-circle-value) [angle (+ two-pi angle) (- two-pi angle)])
        legal (filter #(<= 0 % 255.99) candidates)]
    (taoensso.timbre/spy :debug "candidates:" candidates)
    (taoensso.timbre/spy :debug "legal:" legal)
    (taoensso.timbre/spy :debug "target-value:" target-value)
    (if (empty? legal)
      ;; No legal values, return the closest legal value to the candidate whose value
      ;; is closest to the valid DMX range.
      (let [closest (reduce (fn [a b]
                              (if (< (distance-from-legal-dmx-value a) (distance-from-legal-dmx-value b))
                                a
                                b)) candidates)]
        [(if (neg? closest) 0 255.99) false])
      ;; We have legal values, return the one cloest to the target value
      [(reduce (fn [a b]
                  (if (< (math/abs (- a target-value)) (math/abs (- b target-value)))
                    a
                    b)) legal) true])))

(defn- solve-for-tilt-given-pan
  "Once we have chosen a candidate pan angle to try to aim the
  fixture, see what kind of a solution we can get for tilt and DMX
  values. Returns the best legal DMX values, and whether they actually
  achieve the desired position.

  If there is more than one legal solution, return the one that is
  closest to the specified target value."
  [fixture x y z direction rot-y target-pan target-tilt]
  (let [direction (Vector3d. direction)  ; Make a copy since we are going to mutate it
        rotation (Transform3D.)]
    (.rotY rotation (- rot-y))  ; Determine what the aiming vector looks like after we have panned
    (.transform rotation direction)
    ;; Now figure out the tilt angle
    (let [rot-x (- (Math/atan2 (. direction y) (. direction z)))
          pan-solution (find-closest-legal-dmx-value-for-angle rot-y (:pan-center fixture) (:pan-half-circle fixture)
                                                               :target-value target-pan)
          tilt-solution (find-closest-legal-dmx-value-for-angle rot-x (:tilt-center fixture) (:tilt-half-circle fixture)
                                                                :target-value target-tilt)]
      (taoensso.timbre/debug "For pan of" (/ rot-y Math/PI) "Pi, we get tilt:" (/ rot-x Math/PI) "Pi," [pan-solution tilt-solution])
      [pan-solution tilt-solution])))

(defn- success-score
  "Given a pan and tilt solution, assign it a score based on how many
  components were actually achieved. Currently both pan and tilt
  solutions re weighted equally; if experience dictates that one
  matters more than the other, this can be adjusted."
  [[[_ pan-correct] [_ tilt-correct]]]
  (+ (if pan-correct 1 0) (if tilt-correct 1 0)))

(defn- target-distance
  "Given a pan and tilt solution, measure how close it is to the
  target pan and tilt."
  [[[pan _] [tilt _]] target-pan target-tilt]
  (math/sqrt (+ (math/expt (math/abs (- pan target-pan)) 2)
                (math/expt (math/abs (- tilt target-tilt)) 2))))

(defn- pick-best-solution
  "Given a pair of potential solutions for pan and tilt, choose the
  best. First, if they differ in how many of the components were
  successfully achieved, that determines the best fit. If they are
  both equally successful, then choose the one which is closest to the
  target values."
  [target-pan target-tilt solution-a solution-b]
  (let [success-a (success-score solution-a)
        success-b (success-score solution-b)]
    (if (> success-a success-b)
      solution-a
      (if (> success-b success-a)
        solution-b
        (if (< (target-distance solution-a target-pan target-tilt) (target-distance solution-b target-pan target-tilt))
          solution-a
          solution-b)))))

(defn calculate-position
  "Given a fixture and vector representing a direction in the frame of
  reference of the light show, calculate the best pan and tilt values
  to send to that fixture in order to aim it in that direction.

  If there is more than one legal solution, return the one that is
  closest to the specified target value. If no target value is
  specified (using the keyword parameters :target-pan
  and :target-tilt), then use the fixture's center position as the
  default target value to stay close to."
  [fixture x y z & {:keys [target-pan target-tilt] :or {target-pan (:pan-center fixture) target-tilt (:tilt-center fixture)}}]
  {:pre [(some? fixture) (number? x) (number? y) (number? z) (number? target-pan) (number? target-tilt)]}
  (let [direction (invert-direction fixture x y z)
        rot-y (Math/atan2 (. direction x) (. direction z))
        ;; Try both our calculated pan, and flips halfway around the circle in both directions, hunting for best solution.
        candidates (map #(solve-for-tilt-given-pan fixture x y z direction % target-pan target-tilt)
                        [rot-y (+ rot-y Math/PI) (- rot-y Math/PI)])
        best (reduce (partial pick-best-solution target-pan target-tilt) candidates)]
    (taoensso.timbre/debug "All solutions found: " candidates)
    (taoensso.timbre/debug "Best solution found: " best)
    (let [[[pan _] [tilt _]] best]
      [pan tilt])))


;; Experimental functions which are no longer needed. I will probably just go ahead
;; and delete these for real soon.
#_(defn transform-direction
  [fixture-key]
  (let [rotation (Transform3D. (:rotation ((keyword fixture-key) @(:fixtures *show*))))
        direction (Vector3d. 0 0 1)]
    (.transform rotation direction)
    direction))

#_(defn reverse-euler
  "See if I can get euler angles back out of a transformation matrix."
    [x-rotation y-rotation z-rotation]
  (let [rotation (Transform3D.)
        euler (Vector3d. x-rotation y-rotation z-rotation)
        matrix (javax.vecmath.Matrix3d.)]
    (.setEuler rotation euler)
    (.get rotation matrix)
    (taoensso.timbre/debug "phi:" (Math/atan2 (.-m20 matrix) (.-m21 matrix)))
    (taoensso.timbre/debug "theta" (Math/acos (.m22 matrix)))
    (taoensso.timbre/debug "psi:" (Math/atan2 (.-m02 matrix) (.-m12 matrix)))))

#_(defn compare-euler
  "Test whether setting euler angles is the same as what I am doing
  in one step."
  [x-rotation y-rotation z-rotation]
  (let [rotation (Transform3D.)
        axis (Transform3D.)
        euler (Vector3d. x-rotation y-rotation z-rotation)
        from-euler (Transform3D.)
        compound-point (Point3d. 0 0 1)
        euler-point (Point3d. 0 0 1)]
    ;; Calculate the compound rotation applied to the fixture
    (.rotX axis x-rotation)
    (.mul rotation axis)
    (.rotY axis y-rotation)
    (.mul rotation axis)
    (.rotZ axis z-rotation)
    (.mul rotation axis)
    (taoensso.timbre/debug "Compound rotation:\n" rotation)
    (.setEuler from-euler euler)
    (taoensso.timbre/debug "Euler angle rotation:\n" from-euler)
    (taoensso.timbre/debug "Equal?" (.equals rotation from-euler))
    (.transform rotation compound-point)
    (.transform from-euler euler-point)
    (taoensso.timbre/debug "Compound transformed:\n" compound-point)
    (taoensso.timbre/debug "Euler transformed:\n" euler-point)
    (taoensso.timbre/debug "Equal?" (.equals compound-point euler-point))))
