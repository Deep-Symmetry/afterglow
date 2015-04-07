(ns afterglow.core
  (:require [flatland.protobuf.core :refer :all]
            [afterglow.ola-client :refer [send-request]]
            [taoensso.timbre :as timbre])
  (:import [ola.proto Ola$PluginListRequest Ola$PluginListReply])
  (:gen-class))

(timbre/refer-timbre)

(def PluginListRequest (protodef Ola$PluginListRequest))
(def PluginListReply (protodef Ola$PluginListReply))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (println "Hello, World!")
  (let [p (protobuf PluginListRequest)]
    (send-request "GetPlugins" p PluginListReply #(info "handling" %))))
