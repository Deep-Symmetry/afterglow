(ns afterglow.core
  (:require [afterglow.ola-service :as ola]
            [afterglow.ola-messages :refer [DmxData]]
            [taoensso.timbre :as timbre :refer [info debug]])
  (:import [com.google.protobuf ByteString])
  :gen-class)

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
