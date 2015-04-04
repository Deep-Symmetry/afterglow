(ns afterglow.core
  (:require [flatland.protobuf.core :refer :all])
  (:import [ola.proto Ola$TimeCode])
  (:gen-class))

(def TimeCode (protodef ola.proto.Ola$TimeCode))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (println "Hello, World!")
  (def p (protobuf TimeCode :hours 1 :minutes 42)))
