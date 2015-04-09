(ns afterglow.src-generator
  (:require [clojure.java.io :refer [file resource]]))

(defn find-messages [spec]
  )

(defn generate-proto-helper
  "Create Clojure bridge namespaces for the messages and RPCs in the named protobuf spec."
  [name]
  (let [lc-name (clojure.string/lower-case name)
        spec (slurp (resource (str "proto/" name ".proto")))
        pkg (first (map second (re-seq #"package\s+(.*);" spec)))
        src (file "target" "generated" "afterglow" (str lc-name "_messages.clj"))
        messages (map second (re-seq #"message\s+(\w+)\s*\{" spec))
        enums (map second (re-seq #"enum\s+(\w+)\s*\{" spec))]
    (.mkdirs (.getParentFile src))
    (spit src (str "(ns afterglow." lc-name "-messages
  (:require [flatland.protobuf.core :refer :all])
  (:import [" pkg " " (clojure.string/join " " (map #(str name "$" %) (concat messages enums))) "]
           [flatland.protobuf PersistentProtocolBufferMap$Def$NamingStrategy])

(def safe-strategy (reify PersistentProtocolBufferMap$Def$NamingStrategy
                          (protoName [this clojure-name]
                                     (name clojure-name))
                          (clojureName [this proto-name]
                                       (keyword proto-name)))\n\n"
  (clojure.string/join "\n\n" (map #(str "(def " % " (protodef " name "$" % " {:naming-strategy safe-strategy}))") messages)) "\n"))))

(defn generate []
  (doseq [name ["Ola" "Rpc"]]
    (generate-proto-helper name)))

(defn -main [& args]
  (generate))
