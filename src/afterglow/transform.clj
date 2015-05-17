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
  (:import [javax.media.j3d Transform3D]
           [javax.vecmath Point3d]))

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
  (let [fixture-rotation (:rotation fixture)
        point (Point3d. (:x head 0.0) (:y head 0.0) (:z head 0.0))
        rotation (Transform3D. fixture-rotation)
        axis (Transform3D.)]

    ;; Calculate the compound rotation applied to the head
    (.rotX axis (:x-rotation head 0.0))
    (.mul rotation axis)
    (.rotY axis (:y-rotation head 0.0))
    (.mul rotation axis)
    (.rotZ axis (:z-rotation head 0.0))
    (.mul rotation axis)

    ;; Transform the location of the head based on the fixture rotation
    (.transform fixture-rotation point)

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
  (let [rotation (Transform3D.)
        axis (Transform3D.)]
    ;; Calculate the compound rotation applied to the fixture
    (.rotX axis x-rotation)
    (.mul rotation axis)
    (.rotY axis y-rotation)
    (.mul rotation axis)
    (.rotZ axis z-rotation)
    (.mul rotation axis)
    (transform-heads (assoc fixture :x x :y y :z z :rotation rotation
                            :x-rotation x-rotation :y-rotation y-rotation :z-rotation z-rotation))))

;; Examples until I really write something
(def foo (Transform3D.))

(.rotX foo Math/PI)


;; See http://download.java.net/media/java3d/javadoc/1.3.2/javax/media/j3d/Transform3D.html#rotX(double)
