(ns afterglow.src-generator
  (:require [clojure.java.io :refer [file resource]]
            [selmer.parser :refer [render-file]]
            [selmer.filters :refer [add-filter!]]
            [clojure.pprint :refer [pprint]]))

(defn ->kebab-case [str]
  str)

(defn format-rpc [[_ method takes returns]]
  {:method method :takes takes :returns returns})

(defn gather-rpcs [service]
  (let [rpcs (re-seq #"\s+rpc\s+(\w+)\s+\((\w+)\)\s+returns\s+\((\w+)\);" (get service 2))]
    (map format-rpc rpcs)))

(defn find-rpcs [spec service-name]
  (let [services (re-seq #"(?ms)^service\s+(\w+)\s*\{([^}]*)\}" spec)]
    (mapcat gather-rpcs (filter #(= (second %) service-name) services))
)
  )

(defn generate-file
  "Render the service source file template into the right place."
  [class-name file-type context]
  (let [lc-name (clojure.string/lower-case class-name)
        dest (file "generated" "afterglow" (str lc-name "_" file-type ".clj"))]
    (.mkdirs (.getParentFile dest))
    (spit dest (render-file (str "afterglow/" file-type "_template.clj") context))))

(defn generate-proto-helper
  "Create Clojure bridge namespaces for the messages and RPCs in the named protobuf spec."
  [name]
  (let [spec (slurp (resource (str "proto/" name ".proto")))
        pkg (first (map second (re-seq #"package\s+(.*);" spec)))
        messages (map second (re-seq #"message\s+(\w+)\s*\{" spec))
        enums (map second (re-seq #"enum\s+(\w+)\s*\{" spec))
        context {:classname name
                 :package (first (map second (re-seq #"package\s+(.*);" spec)))
                 :messages messages
                 :enums enums
                 :declared-classes (concat messages enums)
                 :rpcs (find-rpcs spec "OlaServerService")}]
    (doseq [file-type ["messages" "service"]]
      (generate-file name file-type context))))


(defn generate []
  (add-filter! :kebab ->kebab-case)
  (doseq [name ["Ola" "Rpc"]]
    (generate-proto-helper name)))

(defn -main [& args]
  (generate))
