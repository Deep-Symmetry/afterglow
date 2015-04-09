(ns afterglow.src-generator
  (:require [clojure.java.io :refer [file resource]]
            [selmer.parser :refer [render-file]]))

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
        enums (map second (re-seq #"enum\s+(\w+)\s*\{" spec))
        context {:classname name
                 :package (first (map second (re-seq #"package\s+(.*);" spec)))
                 :messages messages
                 :enums enums
                 :declared-classes (concat messages enums)}]
    (.mkdirs (.getParentFile src))
    (spit src
          (render-file "afterglow/messages_template.clj" context))
    
    (spit (file "target" "generated" "afterglow" (str lc-name "_service.clj"))
          (render-file "afterglow/service_template.clj" context))))


(defn generate []
  (doseq [name ["Ola" "Rpc"]]
    (generate-proto-helper name)))

(defn -main [& args]
  (generate))
