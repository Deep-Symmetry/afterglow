(ns afterglow.effects.color-test
  (:require [clojure.test :refer :all]
            [afterglow.effects.color :refer :all]
            [afterglow.channels :as channels]
            [com.evocomputing.colors :as colors]
            [afterglow.fixtures.chauvet :as chauvet]
            [taoensso.timbre :as timbre :refer [error warn info debug spy]]))

(deftest test-extract-rgb
  (testing "Finding RGB color channels")
  (is (= [:12-channel-mix-uv] (map :mode (#'afterglow.effects.color/find-rgb-heads [(chauvet/slimpar-hex3-irc)])))))
