(ns afterglow.core
  (:require [flatland.protobuf.core :refer :all]
            [afterglow.ola-client :refer [send-request]]
            [clojure.pprint :refer [pprint]])
  (:import [ola.proto Ola$PluginListRequest Ola$PluginListReply])
  (:gen-class))

(def PluginListRequest (protodef Ola$PluginListRequest))
(def PluginListReply (protodef Ola$PluginListReply))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (println "Hello, World!")
  (let [p (protobuf PluginListRequest)]
    (pprint (send-request "GetPlugins" p PluginListReply))
    ))
