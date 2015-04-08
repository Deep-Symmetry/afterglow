(ns afterglow.core
  (:require [flatland.protobuf.core :refer :all]
            [afterglow.ola-client :as ola]
            [taoensso.timbre :as timbre])
  (:import [ola.proto Ola$PluginListRequest Ola$PluginListReply Ola$OptionalUniverseRequest Ola$UniverseInfoReply])
  (:gen-class))

(timbre/refer-timbre)

(def PluginListRequest (protodef Ola$PluginListRequest))
(def PluginListReply (protodef Ola$PluginListReply))
(def OptionalUniverseRequest (protodef Ola$OptionalUniverseRequest))
(def UniverseInfoReply (protodef Ola$UniverseInfoReply))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (println "Hello, World!")
  (let [p (protobuf PluginListRequest)]
    (ola/send-request "GetPlugins" p PluginListReply #(info "handling" %)))
  (let [p (protobuf OptionalUniverseRequest)]
    (ola/send-request "GetUniverseInfo" p UniverseInfoReply #(info "universes" %))))
