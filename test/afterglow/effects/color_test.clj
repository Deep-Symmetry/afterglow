(ns afterglow.effects.color-test
  (:require [clojure.test :refer :all]
            [afterglow.effects.color :refer :all]
            [afterglow.channels :as channels]
            [com.evocomputing.colors :as colors]
            [afterglow.fixtures.chauvet :as chauvet]
            [taoensso.timbre :as timbre :refer [error warn info debug spy]]))

(deftest test-extract-rgb
  (testing "Finding RGB color channels")
  (is (= [2 3 4] (map :offset (#'afterglow.effects.color/extract-rgb [(chauvet/slimpar-hex3-irc)])))))
