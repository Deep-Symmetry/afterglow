(ns afterglow.controllers
  "Provides shared services for all controller implementations."
  {:author "James Elliott"
   :doc/format :markdown}
  (:require [overtone.at-at :as at-at]))

(defonce
  ^{:doc "Provides thread scheduling for all controller user interface updates."}
  pool
  (at-at/mk-pool))
