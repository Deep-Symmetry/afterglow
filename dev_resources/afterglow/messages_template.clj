(ns afterglow.{{classname|lower}}-messages
  (:require [flatland.protobuf.core :refer :all])
  (:import [{{package}}{% for item in declared-classes %} {{classname}}${{item}}{% endfor %}]
           [flatland.protobuf PersistentProtocolBufferMap$Def$NamingStrategy]))

(def ^:private safe-strategy (reify PersistentProtocolBufferMap$Def$NamingStrategy
                               (protoName [this clojure-name]
                                 (name clojure-name))
                               (clojureName [this proto-name]
                                 (keyword proto-name))))

{% for message in messages %}(def {{message}} (protodef {{classname}}${{message}} {:naming-strategy safe-strategy}))

{% endfor %}
