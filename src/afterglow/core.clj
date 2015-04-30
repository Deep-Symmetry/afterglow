(ns afterglow.core
  "This will become the main class for running Afterglow as a self-contained JAR application, once
  there is some sort of interface for configuration and control. For now it just has some testing
  code, and the main namespace you want to be using in your REPL is afterglow.examples"
  (:require [afterglow.ola-service :as ola]
            [taoensso.timbre :as timbre :refer [info]])
  (:import (com.google.protobuf ByteString))
  (:gen-class))

;; Make sure the experimenter does not get blasted with a ton of debug messages
(timbre/set-level! :info)

(defn -main
  "Test connectivity to the OLA server by sending a few messages and pretty-printing the responses.
This is eventually where stanadlone functionality will be implemented; for now it is where I test
things, especially before they get moved to the new examples namespace."
  [& args]
  (ola/GetPlugins #(info "Plugins" (with-out-str (clojure.pprint/pprint %))))
  (ola/GetUniverseInfo #(info "Universes" (with-out-str (clojure.pprint/pprint %))))
  ;; TODO Build DMX values in a ByteBuffer, keeping track of max one set, then copy to ByteString and send to OLA. Profit!
  (ola/UpdateDmxData {:universe 1 :data (ByteString/copyFrom (byte-array [3 2 1 52]))}
                     #(info "Updated DMX Data" (with-out-str (clojure.pprint/pprint %))))

  ;; Original way below was much more tedious! Also requried [flatland.protobuf.core :refer :all]
  #_(let [p (protobuf PluginListRequest)]
    (ola/send-request "GetPlugins" p PluginListReply #(info "Plugins" (with-out-str (clojure.pprint/pprint %)))))
  #_(let [p (protobuf OptionalUniverseRequest)]
    (ola/send-request "GetUniverseInfo" p UniverseInfoReply #(info "Universes" (with-out-str (clojure.pprint/pprint %)))))
  #_(let [p (protobuf DmxData :universe 2 :data (ByteString/copyFrom (byte-array [3 2 1 52])))]
    (ola/send-request "UpdateDmxData" p Ack #(info "Updated DMX Data" (with-out-str (clojure.pprint/pprint %))))))
