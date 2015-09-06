(ns afterglow.effects-test
  (:require [clojure.test :refer :all]
            [afterglow.effects :refer :all]
            [afterglow.show :as show]
            [afterglow.rhythm :as rhythm]))

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
      (is (= base (fade base base 0 @test-show @test-snapshot)))
      (is (thrown? AssertionError (fade base (merge base {:kind :boom}) 0 @test-show @test-snapshot)))
      (is (thrown? AssertionError (fade base (merge base {:target-id :pdq}) 0 @test-show @test-snapshot))))))

(deftest test-fade-unknown-type
  (testing "Fading an unrecognized assigner flips the value at midpoint"
    (let [from (->Assignment :fnord :xyz {:some "target values"} 10)
          to (->Assignment :fnord :xyz {:some "target values"} 42)]
      (is (= from (fade from to 0 @test-show @test-snapshot)))
      (is (= to (fade from to 1 @test-show @test-snapshot)))
      (is (= from (fade from to -1 @test-show @test-snapshot)))
      (is (= to (fade from to 10 @test-show @test-snapshot)))
      (is (= from (fade from to 0.4 @test-show @test-snapshot)))
      (is (= to (fade from to 0.6 @test-show @test-snapshot)))
      (is (= to (fade from to 0.5 @test-show @test-snapshot)))
      (is (= from (fade from nil 0.3 @test-show @test-snapshot)))
      (is (= to (fade nil to 0.7 @test-show @test-snapshot)))
      (is (nil? (:value (fade from nil 0.9 @test-show @test-snapshot))))
      (is (= :fnord (:kind (fade from nil 0.9 @test-show @test-snapshot))))
      (is (nil? (:value (fade nil to 0.02 @test-show @test-snapshot))))
      (is (= :fnord (:kind (fade nil to 0.02 @test-show @test-snapshot))))
      (is (nil? (fade nil nil 0.2 @test-show @test-snapshot))))))
