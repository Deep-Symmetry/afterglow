(ns afterglow.transform
  "Functions for modeling light position and rotation. If you want to
  make use of Afterglow's spatial reasoning capabilities, you need to
  tell it, when patching a fixture, the location and orientation of
  that fixture.

  The coordinate system in afterglow is from the perspective of the
  audience: Standing facing your show, the X axis passes through it
  increasing from left to right, the Y axis passes through it
  increasing away from the floor, and the Z axis passes through it
  increasing towards the audience. (There is a diagram on the [Show
  Space](https://github.com/brunchboy/afterglow/blob/master/doc/show_space.adoc#show-space)
  documentation page.)

  Pick an origin when setting up your show; in our case it is easiest
  to use the spot on the floor directly under the center of gravity of
  the main truss in our lighting rig. Something else may be better for
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
  {:author "James Elliott"}
  (:require [afterglow.channels :as chan]
            [clojure.math.numeric-tower :as math]
            [taoensso.timbre :as timbre :refer [debug]])
  (:import [javax.media.j3d Transform3D]
           [javax.vecmath Point3d Vector3d]))

(defn inches
  "Converts a number of inches to the corresponding number of meters."
  [in]
  (* in 0.0254))

(defn feet
  "Converts a number of feet to the corresponding number of meters."
  [f]
  (inches (* f 12)))

(defn degrees
  "Converts a number of degrees into the corresponding number of
  radians."
  [deg]
  (* (/ deg 180) Math/PI))

(defn interpret-rotation
  "Given a fixture definition or head, checks whether it has been
  assigned extrinsic (Euler) angle rotations, or a list of intrinsic
  rotations. Either way, construct and return the appropriate rotation
  matrix to represent its orientation."
  [head]
  (let [rotation (Transform3D.)]
    (if-let [rotations (seq (:relative-rotations head))]
      (if (every? zero? (select-keys head [:x-rotation :y-rotation :z-rotation]))
        (doseq [[axis angle] rotations]
          (let [rotation-step (Transform3D.)]
            (case axis
              :x-rotation (.rotX rotation-step angle)
              :y-rotation (.rotY rotation-step angle)
              :z-rotation (.rotZ rotation-step angle)
              (throw (IllegalArgumentException. (str "Unknown intrinsic rotation: " axis
                                                     " (must be :x-rotation, :y-rotation, or :z-rotation) "
                                                     " for head: " head))))
            (.mul rotation rotation-step)))
        (throw (IllegalArgumentException.
                (str "Head cannot have both extrinsic and intrinsic rotations: ") head)))
      (let [euler-angles (Vector3d. (:x-rotation head 0.0) (:y-rotation head 0.0) (:z-rotation head 0.0))]
        (.setEuler rotation euler-angles)))
    rotation))

;; TODO: Today we are considering the heads to be in a fixed location relative to
;;       the fixture. Someday this needs to be generalized to cope with fixtures
;;       with multiple heads where the base fixture can pan and tilt, moving the
;;       heads themselves, which can then individually pan and tilt.
(defn- transform-head
  "Determine the position and orientation of a fixture's head."
  [fixture head]
  (let [rotation (Transform3D. (:rotation fixture)) ; Not immutable, so copy!
        point (Point3d. (:x head 0.0) (:y head 0.0) (:z head 0.0))]

    ;; Transform the location of the head based on the fixture rotation
    (.transform rotation point)

    ;; Calculate the compound rotation applied to the head
    (.mul rotation (interpret-rotation head))
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

(defn transform-fixture-euler
  "Determine the positions and orientations of the fixture and its
  heads when it is patched into a show. `x`, `y`, and `z` are the
  position of the fixture's origin point with respect to the show's
  origin.

  The fixture rotation is expressed as Euler angles in `x-rotation`,
  `y-rotation`, and `z-rotation`. These are interpreted as the
  counter-clockwise extrinsic rotations around the show space axes, in
  that order, needed to get the fixture from its reference orientation
  to the orientation in which it was actually hung. Extrinsic means
  that the axes are always the fixed axes of show space, they do not
  change as you rotate the fixture."
  [fixture x y z x-rotation y-rotation z-rotation]
  (let [fixture (assoc fixture :x x :y y :z z
                       :x-rotation x-rotation :y-rotation y-rotation :z-rotation z-rotation)]
    (transform-heads (assoc fixture :rotation (interpret-rotation fixture)))))

(defn transform-fixture-relative
  "Determine the positions and orientations of the fixture and its
  heads when it is patched into a show. `x`, `y`, and `z` are the
  position of the fixture's origin point with respect to the show's
  origin.

  The fixture rotation is expressed as a list of `relative-rotations`
  in any order. Each list element is a tuple of the rotation
  type (`:x-rotation`, `:y-rotation`, or `:z-rotation`) and the
  counter-clockwise angle in radians around that axis which the
  fixture should be rotated. The fixtue starts at its reference
  orientation, and these intrinsic rotations are applied, in order,
  always from the frame of reference of the fixture, including any
  accumulated previous rotations."
  [fixture x y z relative-rotations]
  (let [fixture (assoc fixture :x x :y y :z z
                       :relative-rotations relative-rotations)]
    (transform-heads (assoc fixture :rotation (interpret-rotation fixture)))))

(defn transform-fixture-rotation-matrix
  "Determine the positions and orientations of the fixture and its
  heads when it is patched into a show. `x`, `y`, and `z` are the
  position of the fixture's origin point with respect to the show's
  origin.

  The fixture rotation is expressed as a `javax.media.j3d.Transform3D`
  matrix containing only rotational components which represents the
  transformation needed to get the fixture from its reference
  orientation to the orientation at which it was actually hung.

  This function is useful when you are precomupting a set of rotations
  based on groups of fixtures that are being patched as an assembly."
  [fixture x y z rotation]
  (transform-heads (assoc fixture :x x :y y :z z :rotation rotation)))

(defn show-head-positions
  "To help sanity-check fixtures' position and orientation after
  patching them, displays the positions of all heads of the list of
  fixtures supplied. In the resulting list, top-level fixtures will be
  identifiable by the presence of their :key entry in the map that
  includes their position, and will be followed by their heads, if
  any, until the next top-level fixture entry."
  [fixtures]
  (map #(select-keys % [:key :x :y :z]) (chan/expand-heads fixtures)))


(def two-pi
  "The angle which represents a full rotation around a circle."
  (* 2 Math/PI))

(defn invert-direction
  "Transform a direction vector in show coordinate space to the way it
  appears to a head or fixture that has been rotated when hanging."
  [fixture direction]
  (let [rotation (Transform3D. (:rotation fixture))
        direction (Vector3d. direction)]  ;; Copy since it is mutable
    (.invert rotation)
    #_(.normalize direction)
    (.transform rotation direction)
    (debug "Inverted direction:" direction)
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
  (let [candidates (map #(angle-to-dmx-value % center-value half-circle-value)
                        [angle (+ angle two-pi) (- angle two-pi)])
        legal (filter #(<= 0 % 255.99) candidates)]
    (debug "candidates:" candidates)
    (debug "legal:" legal)
    (debug "target-value:" target-value)
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
  [fixture direction rot-y target-pan target-tilt]
  (let [direction (Vector3d. direction)  ; Make a copy since we are going to mutate it
        rotation (Transform3D.)]
    (.rotY rotation (- rot-y))  ; Determine what the aiming vector looks like after we have panned
    (.transform rotation direction)
    ;; Now figure out the tilt angle
    (let [rot-x (- (Math/atan2 (.y direction) (.z direction)))
          pan-solution (find-closest-legal-dmx-value-for-angle rot-y (:pan-center fixture) (:pan-half-circle fixture)
                                                               :target-value target-pan)
          tilt-solution (find-closest-legal-dmx-value-for-angle rot-x (:tilt-center fixture) (:tilt-half-circle fixture)
                                                                :target-value target-tilt)]
      (debug "For pan of" (/ rot-y Math/PI) "Pi, we get tilt:" (/ rot-x Math/PI) "Pi," [pan-solution tilt-solution])
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
  target pan and tilt. Consider pan changes to be twice as disruptive
  as tilt changes, since they are more obvious."
  [[[pan _] [tilt _]] target-pan target-tilt]
  (math/sqrt (+ (math/expt (* 2 (math/abs (- pan target-pan))) 2)
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

(defn direction-to-dmx
  "Given a fixture or head and vector representing a direction in the
  frame of reference of the light show, calculate the best pan and
  tilt values to send to that fixture or head in order to aim it in
  that direction.

  If there is more than one legal solution, return the one that is
  closest to the former values supplied. If no former values were
  specified, then use the fixture's center position as the
  default target value to stay close to."
  [fixture direction former-values]
  {:pre [(some? fixture) (instance? Vector3d direction)]}
  (let [direction (invert-direction fixture direction)  ;; Transform to perspective of hung fixture
        rot-y (Math/atan2 (.x direction) (.z direction))  ;; Calculate pan
        [target-pan target-tilt] (or former-values [(:pan-center fixture) (:tilt-center fixture)])
        ;; Try both our calculated pan, and flips halfway around the circle in both directions,
        ;; hunting for the best solution.
        candidates (map #(solve-for-tilt-given-pan fixture direction % target-pan target-tilt)
                        [rot-y (+ rot-y Math/PI) (- rot-y Math/PI)])
        best (reduce (partial pick-best-solution target-pan target-tilt) candidates)]
    (debug "All solutions found: " candidates)
    (debug "Best solution found: " best)
    (let [[[pan _] [tilt _]] best]
      [pan tilt])))

(defn aim-to-dmx
  "Given a fixture or head and a point in the frame of reference of
  the light show, calculate the best pan and tilt values to send to
  that fixture or head in order to aim it at that point.

  If there is more than one legal solution, return the one that is
  closest to the former values supplied. If no former values were
  specified, then use the fixture's center position as the
  default target value to stay close to."
  [fixture target-point former-values]
  {:pre [(some? fixture) (instance? Point3d target-point)]}
  ;; Find direction from fixture to point
  (let [direction (Vector3d. (- (.x target-point) (:x fixture))
                             (- (.y target-point) (:y fixture))
                             (- (.z target-point) (:z fixture)))]
    (debug "Calculating aim as direction" direction)
    (direction-to-dmx fixture direction former-values)))

(defn calculate-bounds
  "Given a list of fixtures, determine the corners of the smallest
  box which contains them all, and the center of that box."
  [fixtures]
  (let [all-heads (chan/expand-heads fixtures)
        min-x (apply min (map :x all-heads))
        min-y (apply min (map :y all-heads))
        min-z (apply min (map :z all-heads))
        max-x (apply max (map :x all-heads))
        max-y (apply max (map :y all-heads))
        max-z (apply max (map :z all-heads))
        center-x (/ (+ min-x max-x) 2)
        center-y (/ (+ min-y max-y) 2)
        center-z (/ (+ min-z max-z) 2)]
    {:min-x min-x :min-y min-y :min-z min-z
     :max-x max-x :max-y max-y :max-z max-z
     :center-x center-x :center-y center-y :center-z center-z}))

(defn build-distance-measure
  "Returns a function which calculates the distance from a fixture or
  head to a specified point, optionally ignoring one or more axes.
  Useful in building transitions which depend on the locations of
  lights, perhaps only within certain planes or along a single axis.

  The function returned takes a fixture definition, and returns its
  distance from the configured reference point, after discarding any
  axes which have been marked as ignored by passing true values along
  with :ignore-x, :ignore-y, or :ignore-z."
  [x y z & {:keys [ignore-x ignore-y ignore-z]}]
  (let [reference (Point3d. (if ignore-x 0 x) (if ignore-y 0 y) (if ignore-z 0 z))]
    (fn [head]
      (let [location (Point3d. (if ignore-x 0 (:x head)) (if ignore-y 0 (:y head)) (if ignore-z 0 (:z head)))]
        (.distance reference location)))))

(defn max-distance
  "Calculates the maximum distance that will ever be returned by a
  distance measure for a set of fixtures."
  [measure fixtures]
  (let [all-heads (chan/expand-heads fixtures)]
    (apply max (map measure all-heads))))

