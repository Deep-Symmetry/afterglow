(ns afterglow.effects-test
  (:require [clojure.test :refer :all]
            [afterglow.effects :refer :all]
            [afterglow.fixtures.blizzard :as blizzard]
            [afterglow.show :as show]
            [afterglow.show-context :refer [with-show]]
            [afterglow.rhythm :as rhythm]
            [afterglow.transform :as tf]
            [afterglow.util :as util]
            [com.evocomputing.colors :as colors])
  (:import [javax.vecmath Point3d Vector3d]))

(defonce test-show (atom nil))
(defonce test-snapshot (atom nil))

(defn effects-fixture
  "Set up a show and snapshot for the duration of the tests."
  [f]
  (reset! test-show (show/show))
  (reset! test-snapshot (rhythm/metro-snapshot (:metronome @test-show)))
  (with-show @test-show
    (show/patch-fixture! :torrent-1 (blizzard/torrent-f3) 1 1
                         :x (tf/inches 44) :y (tf/inches 51.75) :z (tf/inches -4.75)
                         :y-rotation (tf/degrees 0))
    (f))
  (reset! test-snapshot nil)
  (reset! test-show nil))

(use-fixtures :once effects-fixture)

(deftest test-validations
  (testing "Assigners must have compatible types and target IDs"
    (let [base (->Assignment :fnord :xyz {:some "target values"} 10)]
      (is (= base (fade-assignment base base 0 @test-show @test-snapshot)))
      (is (thrown? AssertionError (fade-assignment base (merge base {:kind :boom}) 0 @test-show @test-snapshot)))
      (is (thrown? AssertionError (fade-assignment base (merge base {:target-id :pdq}) 0 @test-show @test-snapshot))))))

(deftest test-fade-unknown-type
  (testing "Fading an unrecognized assigner flips the value at midpoint"
    (let [from (->Assignment :fnord :xyz {:some "target values"} 10)
          to (->Assignment :fnord :xyz {:some "target values"} 42)]
      (is (= from (fade-assignment from to 0 @test-show @test-snapshot)))
      (is (= to (fade-assignment from to 1 @test-show @test-snapshot)))
      (is (= from (fade-assignment from to -1 @test-show @test-snapshot)))
      (is (= to (fade-assignment from to 10 @test-show @test-snapshot)))
      (is (= from (fade-assignment from to 0.4 @test-show @test-snapshot)))
      (is (= to (fade-assignment from to 0.6 @test-show @test-snapshot)))
      (is (= to (fade-assignment from to 0.5 @test-show @test-snapshot)))
      (is (= from (fade-assignment from nil 0.3 @test-show @test-snapshot)))
      (is (= to (fade-assignment nil to 0.7 @test-show @test-snapshot)))
      (is (nil? (:value (fade-assignment from nil 0.9 @test-show @test-snapshot))))
      (is (= :fnord (:kind (fade-assignment from nil 0.9 @test-show @test-snapshot))))
      (is (nil? (:value (fade-assignment nil to 0.02 @test-show @test-snapshot))))
      (is (= :fnord (:kind (fade-assignment nil to 0.02 @test-show @test-snapshot))))
      (is (nil? (fade-assignment nil nil 0.2 @test-show @test-snapshot))))))

(deftest test-fade-channel
  (testing "Fading a channel assigner scales the value"
    (let [from (->Assignment :channel :u1a3 {:some "target values"} 10)
          to (->Assignment :channel :u1a3 {:some "target values"} 20)
          too-big (->Assignment :channel :u1a3 {:some "target values"} 420)
          too-small (->Assignment :channel :u1a3 {:some "target values"} -20)]
      (is (= from (fade-assignment from to 0 @test-show @test-snapshot)))
      (is (= to (fade-assignment from to 1 @test-show @test-snapshot)))
      (is (= from (fade-assignment from to -1 @test-show @test-snapshot)))
      (is (= to (fade-assignment from to 10 @test-show @test-snapshot)))
      (is (util/float= 14.0 (:value (fade-assignment from to 0.4 @test-show @test-snapshot))))
      (is (util/float= 16.0 (:value (fade-assignment from to 0.6 @test-show @test-snapshot))))
      (is (util/float= 15.0 (:value (fade-assignment from to 0.5 @test-show @test-snapshot))))
      (is (util/float= 132.5 (:value (fade-assignment from too-big 0.5 @test-show @test-snapshot))))
      (is (util/float= 5.0 (:value (fade-assignment from too-small 0.5 @test-show @test-snapshot))))
      (is (util/float= 7.0 (:value (fade-assignment from nil 0.3 @test-show @test-snapshot))))
      (is (util/float= 14.0 (:value (fade-assignment nil to 0.7 @test-show @test-snapshot))))
      (is (util/float= 1.0 (:value (fade-assignment from nil 0.9 @test-show @test-snapshot))))
      (is (= :channel (:kind (fade-assignment from nil 0.9 @test-show @test-snapshot))))
      (is (util/float= 0.4 (:value (fade-assignment nil to 0.02 @test-show @test-snapshot))))
      (is (= :channel (:kind (fade-assignment nil to 0.02 @test-show @test-snapshot)))))))

(deftest test-fade-function
  (testing "Fading a function assigner scales the value only if active on both sides"
    (let [from (->Assignment :function :3-strobe {:some "target values"} 10)
          to (->Assignment :function :3-strobe {:some "target values"} 20)
          too-big (->Assignment :function :3-strobe {:some "target values"} 120)
          too-small (->Assignment :function :3-strobe {:some "target values"} -20)]
      (is (= from (fade-assignment from to 0 @test-show @test-snapshot)))
      (is (= to (fade-assignment from to 1 @test-show @test-snapshot)))
      (is (= from (fade-assignment from to -1 @test-show @test-snapshot)))
      (is (= to (fade-assignment from to 10 @test-show @test-snapshot)))
      (is (util/float= 14.0 (:value (fade-assignment from to 0.4 @test-show @test-snapshot))))
      (is (util/float= 16.0 (:value (fade-assignment from to 0.6 @test-show @test-snapshot))))
      (is (util/float= 15.0 (:value (fade-assignment from to 0.5 @test-show @test-snapshot))))
      (is (util/float= 55.0 (:value (fade-assignment from too-big 0.5 @test-show @test-snapshot))))
      (is (util/float= 5.0 (:value (fade-assignment from too-small 0.5 @test-show @test-snapshot))))
      (is (util/float= 10.0 (:value (fade-assignment from nil 0.3 @test-show @test-snapshot))))
      (is (util/float= 20.0 (:value (fade-assignment nil to 0.7 @test-show @test-snapshot))))
      (is (nil? (:value (fade-assignment from nil 0.9 @test-show @test-snapshot))))
      (is (= :function (:kind (fade-assignment from nil 0.9 @test-show @test-snapshot))))
      (is (nil? (:value (fade-assignment nil to 0.02 @test-show @test-snapshot))))
      (is (= :function (:kind (fade-assignment nil to 0.02 @test-show @test-snapshot)))))))

(deftest test-fade-color
  (testing "Fading a color assigner blends the colors"
    (let [red (->Assignment :color :i42 {:some "target values"} (colors/create-color :red))
          green (->Assignment :color :i42 {:some "target values"} (colors/create-color :green))
          white (->Assignment :color :i42 {:some "target values"} (colors/create-color :white))]
      (is (= red (fade-assignment red green 0 @test-show @test-snapshot)))
      (is (= green (fade-assignment red green 1 @test-show @test-snapshot)))
      (is (= red (fade-assignment red green -1 @test-show @test-snapshot)))
      (is (= green (fade-assignment red green 10 @test-show @test-snapshot)))
      (is (util/float= 60.0 (colors/hue (:value (fade-assignment red green 0.5 @test-show @test-snapshot)))))
      (is (util/float= 50 (colors/lightness (:value (fade-assignment red green 0.5 @test-show @test-snapshot)))))
      (is (util/float= 100 (colors/saturation (:value (fade-assignment red green 0.5 @test-show @test-snapshot)))))
      (is (util/float= 30.0 (colors/hue (:value (fade-assignment red green 0.25 @test-show @test-snapshot)))))
      (is (util/float= 90 (colors/hue (:value (fade-assignment red green 0.75 @test-show @test-snapshot)))))
      (is (util/float= 0.0 (colors/hue (:value (fade-assignment red white 0.5 @test-show @test-snapshot)))))
      (is (util/float= 75.0 (colors/lightness (:value (fade-assignment red white 0.5 @test-show @test-snapshot)))))
      (is (util/float= 0.0 (colors/hue (:value (fade-assignment red nil 0.9 @test-show @test-snapshot)))))
      (is (util/float= 5.0 (colors/lightness (:value (fade-assignment red nil 0.9 @test-show @test-snapshot)))))
      (is (util/float= 120 (colors/hue (:value (fade-assignment nil green 0.02 @test-show @test-snapshot)))))
      (is (util/float= 1.0 (colors/lightness (:value (fade-assignment nil green 0.02 @test-show @test-snapshot))))))))

(deftest test-fade-direction
  (testing "Fading a direction assigner moves between the directions"
    (let [fixture (first (show/fixtures-named :torrent-1))
          from (->Assignment :direction :i13 fixture (Vector3d. -1.0 1.0 0.0))
          to (->Assignment :direction :i13 fixture (Vector3d. 1.0 1.0 0.0))]
      (is (= from (fade-assignment from to 0 @test-show @test-snapshot)))
      (is (= to (fade-assignment from to 1 @test-show @test-snapshot)))
      (is (= from (fade-assignment from to -1 @test-show @test-snapshot)))
      (is (= to (fade-assignment from to 10 @test-show @test-snapshot)))
      (is (= (Vector3d. -0.196116135138184, 0.9805806756909201, 0.0)
             (:value (fade-assignment from to 0.4 @test-show @test-snapshot))))
      (is (= (Vector3d. 0.196116135138184, 0.9805806756909201, 0.0)
             (:value (fade-assignment from to 0.6 @test-show @test-snapshot))))
      (is (= (Vector3d. 0.0, 1.0, 0.0)
             (:value (fade-assignment from to 0.5 @test-show @test-snapshot))))
      (is (= (Vector3d. -0.679071744760261, 0.6739354942419622, -0.2909854207157071)
             (:value (fade-assignment from nil 0.3 @test-show @test-snapshot))))
      (is (= (Vector3d. 0.679071744760261, 0.6739354942419622, -0.29098542071570715)
             (:value (fade-assignment nil to 0.7 @test-show @test-snapshot))))
      (is (= (Vector3d. -0.10997491972977708, 0.09250690755105184, -0.9896201236261165)
             (:value (fade-assignment from nil 0.9 @test-show @test-snapshot))))
      (is (= :direction (:kind (fade-assignment from nil 0.9 @test-show @test-snapshot))))
      (is (= (Vector3d. 0.020407013973550377, 0.002759527209833684, -0.9997879469118747)
             (:value (fade-assignment nil to 0.02 @test-show @test-snapshot))))
      (is (= :direction (:kind (fade-assignment nil to 0.02 @test-show @test-snapshot)))))))

(deftest test-fade-aim
  (testing "Fading an aim assigner moves between the aim points"
    (let [fixture (first (show/fixtures-named :torrent-1))
          from (->Assignment :aim :i13 fixture (Vector3d. -1.0 4.0 1.0))
          to (->Assignment :aim :i13 fixture (Vector3d. 1.0 2.0 2.0))]
      (is (= from (fade-assignment from to 0 @test-show @test-snapshot)))
      (is (= to (fade-assignment from to 1 @test-show @test-snapshot)))
      (is (= from (fade-assignment from to -1 @test-show @test-snapshot)))
      (is (= to (fade-assignment from to 10 @test-show @test-snapshot)))
      (is (= (Point3d. -0.19999999999999996, 3.2, 1.4)
             (:value (fade-assignment from to 0.4 @test-show @test-snapshot))))
      (is (= (Point3d. 0.19999999999999996, 2.8, 1.6)
             (:value (fade-assignment from to 0.6 @test-show @test-snapshot))))
      (is (= (Point3d. 0.0, 3.0, 1.5)
             (:value (fade-assignment from to 0.5 @test-show @test-snapshot))))
      (is (= (Point3d. -0.36471999999999993, 3.1890404558070613, 0.36385172396890036)
             (:value (fade-assignment from nil 0.3 @test-show @test-snapshot))))
      (is (= (Point3d. 1.03528, 1.7890404558070614, 1.0638517239689003)
             (:value (fade-assignment nil to 0.7 @test-show @test-snapshot))))
      (is (= (Point3d. 0.9058400000000001, 1.5671213674211841, -0.9084448280932987)
             (:value (fade-assignment from nil 0.9 @test-show @test-snapshot))))
      (is (= :aim (:kind (fade-assignment from nil 0.9 @test-show @test-snapshot))))
      (is (= (Point3d. 1.1152480000000002, 1.310865488969734, -1.0580843683682586)
             (:value (fade-assignment nil to 0.02 @test-show @test-snapshot))))
      (is (= :aim (:kind (fade-assignment nil to 0.02 @test-show @test-snapshot)))))))
