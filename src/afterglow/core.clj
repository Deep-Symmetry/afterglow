(ns afterglow.core
  (:require [flatland.protobuf.core :refer :all]
            [afterglow.ola-client :as ola]
            [taoensso.timbre :as timbre])
  (:import [ola.proto Ola$PluginListRequest Ola$PluginListReply Ola$OptionalUniverseRequest Ola$UniverseInfoReply
            Ola$DmxData Ola$Ack]
           [com.google.protobuf ByteString])
  (:gen-class))

(timbre/refer-timbre)

(def PluginListRequest (protodef Ola$PluginListRequest))
(def PluginListReply (protodef Ola$PluginListReply))
(def OptionalUniverseRequest (protodef Ola$OptionalUniverseRequest))
(def UniverseInfoReply (protodef Ola$UniverseInfoReply))
(def DmxData (protodef Ola$DmxData))
(def Ack (protodef Ola$Ack))

(defn -main
  "Test connectivity to the OLA server by sending a few messages and pretty-printing the responses."
  [& args]
  #_(let [p (protobuf PluginListRequest)]
    (ola/send-request "GetPlugins" p PluginListReply #(info "Plugins" (with-out-str (clojure.pprint/pprint %)))))
  #_(let [p (protobuf OptionalUniverseRequest)]
    (ola/send-request "GetUniverseInfo" p UniverseInfoReply #(info "Universes" (with-out-str (clojure.pprint/pprint %)))))
  ;; TODO Build DMX values in a ByteBuffer, keeping track of max one set, then copy to ByteString and send to OLA. Profit!
  (let [p (protobuf DmxData :universe 2 :data (ByteString/copyFrom (byte-array [3 2 1 52])))]
    (ola/send-request "UpdateDmxData" p Ack #(info "Updated DMX Data" (with-out-str (clojure.pprint/pprint %))))))
