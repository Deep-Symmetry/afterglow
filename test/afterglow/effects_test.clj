(ns afterglow.effects-test
  (:require [clojure.test :refer :all]
            [afterglow.effects :refer :all]
            [afterglow.show :as show]
            [afterglow.rhythm :as rhythm]
            [afterglow.util :as util]
            [com.evocomputing.colors :as colors]))

(defonce test-show (atom nil))
(defonce test-snapshot (atom nil))

(defn effects-fixture
  "Set up a show and snapshot for the duration of the tests."
  [f]
  (reset! test-show (show/show))
  (reset! test-snapshot (rhythm/metro-snapshot (:metronome @test-show)))
  (f)
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
      (is (= :channel(:kind (fade-assignment nil to 0.02 @test-show @test-snapshot)))))))

(deftest test-fade-function
  (testing "Fading a function assigner scales the value"
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
      (is (util/float= 7.0 (:value (fade-assignment from nil 0.3 @test-show @test-snapshot))))
      (is (util/float= 14.0 (:value (fade-assignment nil to 0.7 @test-show @test-snapshot))))
      (is (util/float= 1.0 (:value (fade-assignment from nil 0.9 @test-show @test-snapshot))))
      (is (= :function (:kind (fade-assignment from nil 0.9 @test-show @test-snapshot))))
      (is (util/float= 0.4 (:value (fade-assignment nil to 0.02 @test-show @test-snapshot))))
      (is (= :function(:kind (fade-assignment nil to 0.02 @test-show @test-snapshot)))))))

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
      (is (util/float= 50 (colors/lightness (:value (fade-assignment red green 0.5 @test-show @test-snapshot))) 0.2))
      (is (util/float= 100 (colors/saturation (:value (fade-assignment red green 0.5 @test-show @test-snapshot))) 0.2))
      (is (util/float= 30.0 (colors/hue (:value (fade-assignment red green 0.25 @test-show @test-snapshot)))))
      (is (util/float= 90 (colors/hue (:value (fade-assignment red green 0.75 @test-show @test-snapshot)))))
      (is (util/float= 0.0 (colors/hue (:value (fade-assignment red white 0.5 @test-show @test-snapshot)))))
      (is (util/float= 75.0 (colors/lightness (:value (fade-assignment red white 0.5 @test-show @test-snapshot))) 0.2))
      (is (util/float= 0.0 (colors/hue (:value (fade-assignment red nil 0.9 @test-show @test-snapshot)))))
      (is (util/float= 5.0 (colors/lightness (:value (fade-assignment red nil 0.9 @test-show @test-snapshot))) 0.2))
      (is (util/float= 120 (colors/hue (:value (fade-assignment nil green 0.02 @test-show @test-snapshot)))))
      (is (util/float= 1.0 (colors/lightness (:value (fade-assignment nil green 0.02 @test-show @test-snapshot)))
                       0.2)))))
